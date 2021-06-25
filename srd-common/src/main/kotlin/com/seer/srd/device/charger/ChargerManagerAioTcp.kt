package com.seer.srd.device.charger

import com.seer.srd.BusinessError
import com.seer.srd.eventlog.EventLogLevel
import com.seer.srd.eventlog.SystemEvent
import com.seer.srd.eventlog.recordSystemEventLog
import com.seer.srd.route.routeConfig
import com.seer.srd.scheduler.backgroundFixedExecutor
import com.seer.srd.vehicle.Vehicle
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Instant
import kotlin.concurrent.thread

class ChargerManagerAioTcp(config: ChargerConfig) : AbstractChargerManager(config) {
    val name = config.name
    val host = config.host
    val port = config.port

    @Volatile
    private var rebuilding = false  // rebuilding=true 时，充电机就已经离线了

    @Volatile
    private var aioTcpClient: ChargerAioTcp? = null // 初始化时就链接，如果报错的话，SRD就挂了

    @Volatile
    private var cancelChargeMsg = ""

    @Volatile
    private var chargeFailedOn: Instant? = null // 用于计算充电超时

    private fun checkDoChargeTimeout() {    // 充电超时
        try {
            if (!model.timedOut && model.turnedOn && model.vehicleInfo.onPosition
                && listOf(Vehicle.State.IDLE, Vehicle.State.CHARGING).contains(model.vehicleInfo.state)
            ) {
                val vls = vehicleLastStatus
                if (vls == null) return                                     // 此时系统还未获取到机器人的状态
                else if (!vls) doChargeTimedOut()                           // 机器人执行充电运单时
                else if (!model.vehicleInfo.charging) doChargeTimedOut()    // 机器人在充电的过程中
                else if (chargeFailedOn != null) chargeFailedOn = null      // 机器人持续充电时，清空chargeFailedOn
            }
        } catch (e: Exception) {
            logger.error("Charger[$name] check do charger timeout failed! $e")
        }
    }

    private fun doChargeTimedOut() {
        if (chargeFailedOn == null) chargeFailedOn = Instant.now()
        val doChargeTimeout = model.config.timeout
        val ram = isTimedOut(chargeFailedOn, doChargeTimeout)
        if (ram?.result == true) {
            logger.error("Charger[$name] do-charge timedOut[${ram.msg}/$doChargeTimeout] ...")
            recordSystemEventLog("Charger", EventLogLevel.Error, SystemEvent.Extra,
                "Charger[$name] do charge timedOut[${ram.msg}/$doChargeTimeout] ...")
            model.timedOut = true
            chargeFailedOn = null
            onError("call doChargeTimedOut.")
        }
    }

    @Volatile
    private var requestFailedOn: Instant? = null    // 记录请求（读/写）失败的时间，用于计算连接超时

    private fun requestFailed() {
        if (requestFailedOn == null) requestFailedOn = Instant.now()
    }

    private fun requestSuccessAndSetNull() {
        if (requestFailedOn != null) requestFailedOn = null
    }

    private fun checkOffline() { // 连接超时
        try {
            if (model.online && requestFailedOn != null) {
                // 长时间不能控制充电机，或者读取充电机状态，就判定充电机已经离线
                val connectTimeout = model.config.connectTimeout
                val ram = isTimedOut(requestFailedOn, connectTimeout)
                if (ram?.result == true) {
                    logger.error("Charger[$name] connect timedOut[${ram.msg}/$connectTimeout] ...")
                    recordSystemEventLog("Charger", EventLogLevel.Error, SystemEvent.Extra,
                        "Charger[$name] connect timedOut[${ram.msg}/$connectTimeout] ...")
                    model.online = false
                    onError("call checkOffline.")
                    requestSuccessAndSetNull()
                }
            }
        } catch (e: Exception) {
            logger.error("Charger[$name] check offline failed! $e")
        }
    }

    private fun getClient(): ChargerAioTcp {
        val client = aioTcpClient
        if (client != null) return client
        rebuild("Charger[$name] get client failed!")
        throw BusinessError("client is null")
    }

    init {
        thread(name = "read-charger-info") {
            var errCount = 0
            while (true) {
                try {
                    syncStatus()
                    if (errCount > 0) errCount = 0
                } catch (e: Exception) {
                    if (errCount < 3) {
                        logger.error("Charger[$name] sync status failed!", e)
                        errCount++
                    } else {
                        logger.error("Charger[$name] sync status failed! $e")
                    }
                }
            }
        }

        thread(name = "control-charger") {
            var errCount = 0
            while (true) {
                try {
                    // 此处每个被调用的函数都必须被执行，因此被直接调用的方法其内部必须try-catch
                    updateVehicleInfo()     // 获取机器人状态
                    checkOffline()          // 检测充电机是否已经离线
                    updateChargerStatus()   // 定时更新充电机状态
                    checkDoChargeTimeout()  // 检测充电是否超时
                    sendCommand()           // 发送充电请求或者取消充电的请求
                    if (errCount > 0) errCount = 0
                } catch (e: Exception) {
                    if (errCount < 3) {
                        logger.error("Charger[$name] control failed", e)
                        errCount++
                    } else logger.error("Charger[$name] control failed! $e")
                } finally {
                    Thread.sleep(1000)
                }
            }
        }
    }

    private fun rebuild(reason: String) {
        if (rebuilding) {
            logger.debug("$name has been rebuilding, so return.")
            return
        }
        rebuilding = true

        backgroundFixedExecutor.submit {
            try {
                // 重连之前，一定要先 close
                close()
                runBlocking {
                    var rebuildCount = 0
                    while (true) {
                        logger.info("$name rebuilding tcp clients, reason: $reason, rebuildCount=$rebuildCount, rebuilding=$rebuilding")
                        rebuildCount++
                        try {
                            aioTcpClient = ChargerAioTcp(name, host, port)  // 如果连接失败就会一直连接
                            return@runBlocking
                        } catch (e: IOException) {
                            logger.error("IOException while rebuilding clients for $name", e)
                            close()
                            delay(1000)
                        } catch (e: Exception) {
                            logger.error("Error rebuilding", e)
                            close()
                            delay(1000)
                        }
                    }
                }
            } catch (e: InterruptedException) {
                logger.error("Rebuilding for $name, but interrupted")
            } finally {
                rebuilding = false
            }
        }
    }

    private fun close() {
        aioTcpClient?.close()
    }

    private fun syncStatus() {
        try {
            if (rebuilding) return
            val rawData = getClient().readResponseBlocking()
            requestSuccessAndSetNull()
            if (!model.online) {
                recordSystemEventLog("Charger", EventLogLevel.Info, SystemEvent.Extra,
                    "Charger[$name] connected ...")
                model.online = true
            }
            parseInfo(rawData.array())

        } catch (e: Exception) {
            logger.error("Charger[$name] read response by aioTcpClient failed ", e)
            requestFailed()
            rebuild("Charger[$name] sync status failed!")
        }
    }

    private fun sendCommand() {
        try {
            if (model.turnedOn && (model.timedOut || !model.online)) onError("call sendCommand.")
            if (!model.online || rebuilding) return //
            if (model.turnedOn) { // 充电
                // 充电时，如果充电机故障，或者叉车故障，则停止充电
                // 充电机故障在 sendCommand() 调用之前有已经执行了updateChargerStatus()，
                // 并且充电机故障后，会 onError() 并 cancelCharge()。
                // val cError = chargerHasError()
                val vError = vehicleHasError()
                if (vError.result) {
                    // 充电机或者机器人仍然处于充电状态时，取消充电
                    if (isWorking() || isVehicleCharging()) {
                        logger.warn("Charger[$name] cancel charge because do charge occurred error!")
                    }
                    cancelCharge("${vError.msg}")
                    return
                }

                // 兼容二次充电逻辑（two stage recharge）
                // 机器人从充电状态变为非充电状态时（此时会触发调度的二次充电逻辑），控制充电机停止充电；
                // （此时充电运单已经完成了，不需要再撤销运单了）
                // 否则机器人在执行二次充电逻辑时，充电机的手臂会来回伸缩，造成安全隐患。
                if (routeConfig.dispatcher.twoStageRecharge && vehicleIsChargingLastTime() && !isVehicleCharging()) {
                    cancelCharge("Charger[$name] cancel charge because vehicle from charging to not-charge. " +
                        "ensure executing two-stage-recharge safely")
                    model.turnedOn = false
                }

                doCharge("sendCommand")
            } else { // 取消充电
                cancelCharge("sendCommand")
            }
        } catch (e: Exception) {
            logger.error("Charger[$name] request by aioTcpClient failed ", e)
            requestSuccessAndSetNull()
            rebuild("Charger[$name] send command failed!")
        }
    }

    override fun turnOn(on: Boolean, remark: String?) {
        logger.info("Charger[${name}] is requested to ${if (on) "do" else "cancel"} charge for $remark")
        model.turnedOn = on
        if (!on) {
            model.timedOut = false
            chargeFailedOn = null
        }
    }

    override fun doCharge(remark: String?) {
        model.voltageToCharger = model.vehicleInfo.requestVoltage
        model.currentToCharger = model.vehicleInfo.requestCurrent
        getClient().requestBlocking(model.voltageToCharger, model.currentToCharger, true)
        cancelChargeMsg = ""
    }

    override fun cancelCharge(remark: String?) {
        logger.debug("Charger[$name] cancel charge! $remark ")
        if (model.chargerReports.charging) {
            getClient().requestBlocking(0, 0, false)
            cancelChargeMsg = ""
        } else {
            val msg = "Charger[$name] charging=false, stop request of cancel charge."
            if (cancelChargeMsg == msg) return
            cancelChargeMsg = msg
            logger.debug(cancelChargeMsg)
        }
    }

    override fun dispose() {
        close()
    }

    override fun tryReconnect() {
        // do nothing while config.mode=AioTcp
    }

}