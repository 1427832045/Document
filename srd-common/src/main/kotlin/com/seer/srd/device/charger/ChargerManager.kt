package com.seer.srd.device.charger

import com.seer.srd.CONFIG
import com.seer.srd.io.tcp.ByteArrayBuffer
import com.seer.srd.io.tcp.KeepReceivingTcpClient
import com.seer.srd.io.tcp.PkgExtractor
import com.seer.srd.scheduler.GlobalTimer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

val detailsTest: String = """
[
    {
        "DI": [false, false, false, false, false, false, false, false, false],
        "angle": -1.5429, "battery_level": 0.63, "battery_temp": 24.0, "blocked": false, "brake": false, "charging": true,
        "confidence": 0.9792, "current_map": "L0309MZ", "current_station": "LM9", "dispatch_mode": 1, "dispatch_state": 3,
        "emergency": false, "map_version": "v1.0.6", "model": "Lingde-Fork", "model_version": "v3.0.2", "odo": 25875.977,
        "requestCurrent": 90.0, "requestVoltage": 29.2, "time": 830713, "today_odo": 323.682, "total_time": 366105601,
        "vehicle_id": "RBT-S01", "version": "v3.2.3", "voltage": 26.103, "vx": -0.0, "vy": -0.0, "w": -0.0, 
        "x": 10.7583, "y": -1.4858, "fork_auto_flag": false
    }
]
""".trimIndent()

class ChargerManager(val config: ChargerConfig) : AbstractChargerManager(config) {

    @Volatile
    private var doChargeErrorCount = 0  // 充电失败的次数

    @Volatile
    private var cancelChargeErrorCount = 0  // 取消充电失败次数

    private val initTimestamp = Instant.now()

    private var onPkgTimestamp = initTimestamp

    @Volatile
    private var flowNo = 0

    @Synchronized
    private fun getNextFlowNo(): Short {
        flowNo = (flowNo + 1) % 10000
        return flowNo.toShort()
    }

    @Volatile
    private var doChargeTimestamp: Instant? = null

    @Volatile
    private var reconnectForOffline = true

    private val pkgExtractor = PkgExtractor(pkgStart, this::parsePkgLen, this::onPkg)

    private val tcpClient =
        KeepReceivingTcpClient(config.host, config.port, this.pkgExtractor)

    private val timer: ScheduledFuture<*>

    private lateinit var thread: Thread

    init {
        logger.info("init charger ${config.name}")
        timer = GlobalTimer.executor.scheduleAtFixedRate(
            this::keepControl,
            2000,
            CONFIG.chargerStatusPollingPeriod,
            TimeUnit.MILLISECONDS
        )

        updateInfo()
    }

    private fun initDoChargeTimestamp() {
        doChargeTimestamp = initTimestamp
    }

    private fun socketWrite(data: ByteArray) {
        if (!model.online) logger.error("charger[${config.name}] is off-line, operate charger failed.")
        else {
            if (!tcpClient.isError()) tcpClient.write(data)
            else logger.error("write failed because tcp client error!")
        }
    }

    @Synchronized
    private fun keepControl() {
        try {
            logger.debug("thread[charger-manager-update-info=${config.name}] state=${thread.state}")

            // 充电 - 如果充电机5秒内没有接收到充电请求，就会停止充电，故需要每秒给充电机发送充电请求
            if (model.turnedOn) {

                // 充电时，如果充电机故障，或者叉车故障，则停止充电
                if (chargerHasError().result || vehicleHasError().result) {
                    // 充电机或者机器人仍然处于充电状态时，取消充电
                    if (isWorking() || isVehicleCharging()) cancelCharge("keepControl() occurred error.")
                    return
                }

                checkDoChargeTimedOut()

                // 执行充电
                if (tcpClient.isError()) logger.error("cannot write because tcp client error!")
                else doCharge("")

            } else {
                if (reconnectForOffline) {
                    reconnectForOffline = false
                    tryReconnect()
                }
            }
        } catch (e: Exception) {
            logger.error("keepControl error: ${e.message}")
        }
    }

    override fun tryReconnect() {
        logger.info("try reconnect...")

        model.voltageToCharger = 0
        model.currentToCharger = 0

        val reqBuf = buildTotalBuffer(0, 0, false, getNextFlowNo()).array()
        tcpClient.write(reqBuf)
    }

    @Synchronized
    private fun updateInfo() {
        thread = thread(name = "charger-manager-update-info[${config.name}]") {
            while (false) {
                try {
                    checkOffline()

                    // 刷新机器人信息
                    updateVehicleInfo()

                    // 定时更新充电机状态
                    updateChargerStatus()

                } catch (e: Exception) {
                    logger.error("charger-manager-update-info=${config.name} failed!", e)

                } finally {
                    Thread.sleep(1000L)
                }
            }
        }
    }

    // 判断充电机连接是否超时，如果连接超时，则 online = false
    private fun checkOffline() {
        // 跟充电机建立连接之后，充电机会一直发送报文信息。
        if (!model.online) return

        val duration = toPositive(Duration.between(Instant.now(), onPkgTimestamp).toSeconds())
        if (duration % 20 == 0L) // 通信异常之后，每20秒打印一次日志
            logger.debug(" ------------------------- check offline [$duration/${model.config.connectTimeout}]")
        if (duration > model.config.connectTimeout && initTimestamp != onPkgTimestamp) {
            logger.info("charger offline cause connect timed-out[${model.config.connectTimeout}]!")
            onPkgTimestamp = initTimestamp
            model.online = false
            reconnectForOffline = true

            // 充电机离线后，取消充电、终止执行中的任务，并将机器人设置为在线不阶段状态
            // 离线状态下，即使发送取消从充电的请求也是无效的，只需要 model.turnedOn = false 即可
            // cancelCharge("disconnected beyond [${model.connectTimeout}]")
            onError("call checkOffline.")
            initDoChargeTimestamp() // 取消充电时，重置时间戳
        }
    }

    private fun checkDoChargeTimedOut() {
        // 执行有效充电请求时，如果机器人未处于充电状态，判断充电是否超时，如果超时，撤销运单，并将机器人设置为在线不接单
        // logger.info("vehicle charging=${model.vehicleInfo.charging}")

        // 当机器人到达充电位置后才开始超时判断的逻辑
        if (!model.vehicleInfo.onPosition) return

        if (!model.vehicleInfo.charging) {
            if (null == doChargeTimestamp) {
                logger.info("it is init doChargeTimestamp")
                return
            }
            if (initTimestamp == doChargeTimestamp) return
            val now = Instant.now()
            val duration = Duration.between(now, doChargeTimestamp).toSeconds()
            val positiveDuration = if (duration >= 0) duration else 0 - duration
            logger.info("timeout duration is $positiveDuration")
            if (positiveDuration > model.config.timeout) {
                model.timedOut = true

                // 停止充电
                cancelCharge(" timeout[${model.config.timeout}] [$doChargeTimestamp, $now] ")

                onError("checkDoChargeTimedOut.")
                logger.error("do charge error: timeout!!! cancel charge.")
            }
        } else {
            model.timedOut = false
            initDoChargeTimestamp() // 更新第一次有效充电请求的时间
        }
    }

    /** 控制充电机开始/停止充电 */
    override fun turnOn(on: Boolean, remark: String?) {
        logger.info("charger[${config.name}]${if (on) "do" else "cancel"} charge for $remark")

        model.turnedOn = on
        if (!on) {
            model.timedOut = false
            initDoChargeTimestamp() // 取消充电时，重置时间戳
            cancelCharge(remark)
        }
    }

    /** 执行充电 */
    override fun doCharge(remark: String?) {
        // fe fd 00 88 18 06 e5 f4 01 24 03 84 00 00 00 00 00 00 00 26
        // 当叉车不在充电位置上时，停止充电
        val vi = model.vehicleInfo
        val onPos = vi.onPosition
        if (!onPos) {
            cancelCharge("do charge but no vehicle on ${config.name}")
            return
        }

        if (initTimestamp == doChargeTimestamp || null == doChargeTimestamp) doChargeTimestamp = Instant.now()

        val on = onPos && model.turnedOn
        val reqTotal =
            buildTotalBuffer(model.voltageToCharger, model.currentToCharger, on, getNextFlowNo()).array()
        try {
            socketWrite(reqTotal)
        } catch (e: Exception) {
            doChargeErrorCount++
            logger.error("do charge occurred error[$doChargeErrorCount]: ${e.message}")
        }
    }

    override fun dispose() {
        tcpClient.close()
        timer.cancel(true)
    }

    /** 取消充电 */
    @Synchronized
    override fun cancelCharge(remark: String?) {
        logger.info("cancel charge for $remark")

        model.voltageToCharger = 0
        model.currentToCharger = 0
        model.turnedOn = false

        val reqBuf = buildTotalBuffer(0, 0, false, getNextFlowNo()).array()
        try {
            socketWrite(reqBuf)
        } catch (e: Exception) {
            cancelChargeErrorCount++
            logger.error("cancel charge occurred error[$cancelChargeErrorCount]: ${e.message}")
        }
    }

    /** 获取完整的数据包长度 */
    private fun parsePkgLen(buffer: ByteArrayBuffer): Int {
        // 缓存的数据长度小于期望的总长度时，直接返回 -1
        return if (buffer.validEndIndex - buffer.validStartIndex < totalSize) -1 else totalSize
    }

    private fun onPkg(buffer: ByteArrayBuffer, len: Int) {
        // 测试数据-空闲中 FE FD 00 88 18 FF 50 E5 00 00 00 00 10 01 00 00 2F 22 01 C4
        // 测试数据-充电中 FE FD 00 88 18 FF 50 E5 00 C8 09 C4 08 00 00 00 2F 22 01 D8
        model.online = true
        reconnectForOffline = false

        onPkgTimestamp = Instant.now()

        val baTotal = ByteArray(len)
        System.arraycopy(buffer.buffer, 0, baTotal, 0, len)
        // logger.debug("rep-total from charger[${model.config.name}]: ${convertByteArraytoHexString(baTotal)}")

        parseInfo(baTotal)
    }
}

fun toPositive(value: Long): Long {
    return if (value >= 0) value else 0 - value
}

