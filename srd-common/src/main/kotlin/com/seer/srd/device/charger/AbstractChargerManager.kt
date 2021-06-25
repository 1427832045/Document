package com.seer.srd.device.charger

import com.seer.srd.BusinessError
import com.seer.srd.eventbus.EventBus.onChargerChanged
import com.seer.srd.route.getVehicleDetails
import com.seer.srd.route.routeConfig
import com.seer.srd.route.service.TransportOrderIOService
import com.seer.srd.route.service.VehicleService
import com.seer.srd.vehicle.Vehicle
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.Float
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.time.Instant
import kotlin.experimental.xor
import kotlin.math.floor

abstract class AbstractChargerManager(private val config: ChargerConfig) {

    val logger: Logger = LoggerFactory.getLogger(this.javaClass)

    @Volatile
    var model = ChargerModel(config = config)

    // record information from remote for compare
    @Volatile
    private var data: String = ""

    @Volatile
    private var vehicleData = ""

    @Volatile
    private var vehicleChargingLastTime = false
    fun vehicleIsChargingLastTime(): Boolean {
        return vehicleChargingLastTime
    }

    @Volatile
    var vehicleLastStatus: Boolean? = null // 用于AioTcp的充电超时判断逻辑

    abstract fun tryReconnect()

    abstract fun turnOn(on: Boolean, remark: String?)

    abstract fun doCharge(remark: String?)

    abstract fun cancelCharge(remark: String?)

    abstract fun dispose()

    fun getChargerModel(): ChargerModel {
        return model
    }

    fun isWorking(): Boolean {
        return model.chargerReports.charging
    }

    fun isVehicleCharging(): Boolean {
        return model.vehicleInfo.charging
    }

    fun chargerHasError(): ResultAndMsg {
        val chargerReports = model.chargerReports
        val message =
            if (!model.enabled) "disabled, " else "" +
            if (!model.online) "offline, " else "" +
            if (model.timedOut) "do charge timedOut" else "" +
            if (chargerReports.hdError) "hardware error, " else "" +
            if (chargerReports.temperature) "temperature error, " else "" +
            if (chargerReports.inputVoltageStatus) "input voltage to high, " else ""
            // if (chargerReports.communicationStatus) "communication timeout, " else ""
        // if (message.isNotBlank()) logger.error("charger cannot charge: $message")
        val msg = if (message.isNotBlank()) "charger cannot charge: $message" else null

        val result = !model.enabled                       // 被禁用
            || !model.online                        // 充电机离线
            || model.timedOut                       // 充电超时
            || chargerReports.hdError               // 硬件故障
            || chargerReports.temperature           // 电池温度异常
            || chargerReports.inputVoltageStatus    // 输入电压异常
            // || chargerReports.communicationStatus   // 通讯故障
        return ResultAndMsg(result, msg)
    }

    fun vehicleHasError(): ResultAndMsg {
        val vehicleInfo = model.vehicleInfo
        val newProto = routeConfig.newCommAdapter
        val message =
            if (!vehicleInfo.onPosition) "vehicle not on position, " else "" +
            if (newProto && !vehicleInfo.forkAutoFlag) "vehicle on manual mode, " else "" + // 手动模式下，充电机不能工作
            if (newProto && !vehicleInfo.isDominating) "vehicle.isDominating=false" else "" +
            if (vehicleInfo.state == Vehicle.State.ERROR) "vehicle is ERROR, " else "" +
            if (vehicleInfo.state == Vehicle.State.UNKNOWN) "vehicle is UNKNOWN, " else "" +
            if (vehicleInfo.state == Vehicle.State.UNAVAILABLE) "vehicle is UNAVAILABLE, " else "" +
            if (vehicleInfo.integrationLevel == Vehicle.IntegrationLevel.TO_BE_IGNORED) "vehicle is TO_BE_IGNORED, " else "" +
            if (vehicleInfo.integrationLevel == Vehicle.IntegrationLevel.TO_BE_NOTICED) "vehicle is TO_BE_NOTICED, " else ""
        // if (message.isNotBlank()) logger.error("vehicle cannot do charge: $message")
        val msg = if (message.isNotBlank()) "charger cannot charge: $message" else null

        val result = !vehicleInfo.onPosition                                            // 充电位置没有机器人
            || (newProto && !vehicleInfo.forkAutoFlag)                                  // 机器人处于手动模式，充电机不能工作（仅适配新协议！！！）
            || (newProto && !vehicleInfo.isDominating)                                  // 机器人未被当前系统控制（仅适配新协议！！！）
            || vehicleInfo.state == Vehicle.State.ERROR                                 // 机器人故障
            || vehicleInfo.state == Vehicle.State.UNKNOWN                               // 机器人处于未知状态
            || vehicleInfo.state == Vehicle.State.UNAVAILABLE                           // 机器人被回收控制权，或者超时掉线
            || vehicleInfo.integrationLevel == Vehicle.IntegrationLevel.TO_BE_IGNORED   // 离线不可见
            || vehicleInfo.integrationLevel == Vehicle.IntegrationLevel.TO_BE_NOTICED   // 离线可见
            // || vehicleInfo.integrationLevel == Vehicle.IntegrationLevel.TO_BE_RESPECTED // 在线不接单的机器人不会被派遣任务
        return ResultAndMsg(result, msg)
    }

    fun parseInfo(rawData: ByteArray) {
        val coreData = checkResAndGetCoreData(rawData) ?: return

        val buffer = ByteBuffer.wrap(coreData)
        val voltageRaw = buffer.getShort(0).toInt()
        val currentRaw = buffer.getShort(2).toInt()
        val newChargerReports = ChargerReports(
            voltage = "${voltageRaw / 10.0} V",   // 充电机的实际电压
            current = "${currentRaw / 10.0} A"    // 充电机的实际电流
        )

        var status = buffer.get(4).toInt()      // 充电机状态
        val statusDetails: MutableList<Int> = mutableListOf()
        listOf(0, 0, 0, 0, 0, 0, 0, 0).forEach { _ ->
            statusDetails += status % 2
            status = floor(status / 2.0).toInt()
        }

        // "启动状态" 表示的是手臂的状态
        // 充电机是否"在工作"，需要通过从充电机获取到的电流判断。
        newChargerReports.hdError = statusDetails[0] == 1
        newChargerReports.temperature = statusDetails[1] == 1
        newChargerReports.inputVoltageStatus = statusDetails[2] == 1
        newChargerReports.armOut = statusDetails[3] == 1
        newChargerReports.communicationStatus = statusDetails[4] == 1
        newChargerReports.relayStatus = statusDetails[5] == 1
        newChargerReports.forceBreak = statusDetails[6] == 1

        // charging的状态依据
        newChargerReports.charging = (newChargerReports.armOut && voltageRaw > 0 && currentRaw > 0)

        // 一次性更新数据
        model.chargerReports = newChargerReports

        val resFlowNo = buffer.getShort(5).toInt()

        val infos = "flowNo=$resFlowNo $statusDetails $newChargerReports"

        if (this.config.printChangedStatus) {// 值记录变化的数据
            if (data != infos) {
                data = infos
                logger.debug("Charger[${config.name}] info changed: ${this.data}")
            }
        } else logger.debug("Charger[${config.name}] info changed: $infos")
    }

    /** 校验接收到的数据，包含充电机信息的字节 */
    private fun checkResAndGetCoreData(buffer: ByteArray): ByteArray? {
        val check = buffer.last().toInt()

        // 计算校验字
        val recheck = checkTheBuffer(buffer).toInt()
        if (recheck != check) {
            logger.error("bad check of current data ! expectedValue=[${check}], currentValue=[${recheck}]")
            return null
        }

        val coreData = ByteArray(8)
        System.arraycopy(buffer, 8, coreData, 0, coreData.size)

        return coreData
    }

    fun updateChargerStatus() {
        try {
            val cError = chargerHasError()
            if (cError.result) {
                if (model.status == ChargerStatus.ERROR) return
                model.status = ChargerStatus.ERROR
                // 充电机故障之后，立即停止充电、撤销相关运单、设置相关机器人为在线不接单状态
                logger.error("Charger[${config.name}] occurred error for ${cError.msg}!")
                onError("call updateChargerStatus")
                cancelCharge("call updateChargerStatus occurred error!")
            } else {
                model.status = if (model.chargerReports.charging) ChargerStatus.CHARGING
                else ChargerStatus.IDLE
            }
        } catch (e: Exception) {
            logger.error("Charger[${config.name}] update charger status failed! $e .")
        }
    }

    fun updateVehicleInfo() {
        try {
            val details = getVehicleDetails()
            val name = config.name
            val location = config.location
            details.forEach { (vehicleName, vehicleDetails) ->
                val currentLocation = getStringValueFromMap("current_station", vehicleDetails)
                if ((currentLocation == location)) {
                    logger.info("vehicle=$vehicleName on charger=$name location=$location")
                    val rv = getStringValueFromMap("requestVoltage", vehicleDetails)
                    val requestVoltageF = Float.parseFloat(rv)
                    val rc = getStringValueFromMap("requestCurrent", vehicleDetails)
                    val requestCurrentF = Float.parseFloat(rc)

                    // 统一数量级
                    val requestVoltage = floor(requestVoltageF * 10).toInt()
                    val requestCurrent = floor(requestCurrentF * 10).toInt()

                    // requestVoltage 不能大于 maxVoltage
                    val maxVoltage = model.config.maxVoltage
                    if (maxVoltage < requestVoltage)
                        logger.error("requestVoltage=[$requestVoltage] should smaller than maxVoltage=[$maxVoltage].")

                    // maxCurrent 不能大于 requestCurrent
                    val maxCurrent = model.config.maxCurrent
                    if (maxCurrent < requestCurrent)
                        logger.error("requestCurrent=[$requestCurrent] should smaller than maxCurrent=[$maxCurrent].")

                    val latestVehicle = model.vehicleInfo
                    val latestOrder = if (vehicleName == latestVehicle.id) latestVehicle.transportOrder else ""
                    val charging = getBooleanValueFromMap("charging", vehicleDetails)
                    if (vehicleChargingLastTime != model.vehicleInfo.charging) vehicleChargingLastTime = model.vehicleInfo.charging
                    val forkAutoFlag =
                        if (routeConfig.newCommAdapter) getBooleanValueFromMap("fork_auto_flag", vehicleDetails)
                        else false
                    printVehicleInfoIfChanged(VehicleInfo(
                        id = vehicleName, onPosition = true, charging = charging, forkAutoFlag = forkAutoFlag,
                        requestVoltage = if (requestVoltage > maxVoltage) maxVoltage else requestVoltage,
                        requestCurrent = if (requestCurrent > maxCurrent) maxCurrent else requestCurrent
                    ), latestOrder)
                    return
                }
            }
            printVehicleInfoIfChanged(VehicleInfo(), "")
            logger.warn("Charger[$name] no vehicle on $location!")

        } catch (e: Exception) {
            printVehicleInfoIfChanged(VehicleInfo(), "")
            logger.error("update vehicle info failed because: ${e.message}")
        }
    }

    private fun getStringValueFromMap(key: String, map: Map<*, *>): String {
        try {
            return map[key].toString().trim()
        } catch (e: Exception) {
            throw BusinessError("cannot get value of filed \"$key\" in vehicleDetails，$e")
        }
    }

    private fun getBooleanValueFromMap(key: String, map: Map<*, *>): Boolean {
        try {
            return map[key] as Boolean
        } catch (e: Exception) {
            throw BusinessError("cannot get value of filed \"$key\" in vehicleDetails，$e")
        }
    }

    private fun updateVehicleStatus(vehicleInfo: VehicleInfo, latestOrder: String): VehicleInfo {
        val id = vehicleInfo.id
        for (vehicle in VehicleService.listVehiclesOutputs()) {
            if (vehicle.name == id) {
                vehicleInfo.state = vehicle.state
                vehicleInfo.isDominating = vehicle.isDominating
                vehicleInfo.integrationLevel = vehicle.integrationLevel
                vehicleInfo.transportOrder =
                    if (vehicle.transportOrder.isNullOrBlank()) latestOrder else vehicle.transportOrder
                return vehicleInfo
            }
        }

        // 无法获取到机器人状态时，是否需要停止充电
        throw BusinessError("cannot get vehicle.status and vehicle.integrationLevel of vehicle=$id !")
    }

    private fun printVehicleInfoIfChanged(newVehicleInfo: VehicleInfo, latestOrder: String) {
        vehicleLastStatus = model.vehicleInfo.charging
        model.vehicleInfo =
            if (newVehicleInfo.id == "NO_VEHICLE") VehicleInfo()
            else updateVehicleStatus(newVehicleInfo, latestOrder)
        val newVehicleData = newVehicleInfo.toString()
        val name = model.config.name
        val location = model.config.location
        if (newVehicleData != vehicleData) {
            vehicleData = newVehicleData
            if (vehicleData == VehicleInfo().toString() || vehicleData.isBlank())
                logger.info("Charger[$name] at pos: $location, vehicle info changed: no vehicle, $vehicleData")
            else
                logger.info("Charger[$name] at pos: $location, vehicle info changed: $vehicleData")
        }
    }

    fun onError(remark: String?) {
        logger.debug("Charger[${config.name}] onError() for $remark")
        model.turnedOn = false // 超时之后，终止当前充电行为
        model.voltageToCharger = 0
        model.currentToCharger = 0
        disableVehicleAndWithdrawExecutingOrder()
        onChargerChanged(getChargerModel())
    }

    private fun disableVehicleAndWithdrawExecutingOrder() {
        try {
            // 此时机器人一定是在充电点位上，可以立即撤销运单，并将将机器人设置为在线不接单，防止再次创建充电任务
            // 如果机器人上有运单，则撤销运单，否则只需要将机器人设置为在线不接单
            val vehicleId = model.vehicleInfo.id
            val transportOrderId = VehicleService.getVehicleOrNull(vehicleId)?.transportOrder
            if (!transportOrderId.isNullOrBlank()) {
                TransportOrderIOService.withdrawTransportOrder(transportOrderId, immediate = true, disableVehicle = true)
            } else if (vehicleId != "NO_VEHICLE") {
                VehicleService.updateVehicleIntegrationLevel(vehicleId, Vehicle.IntegrationLevel.TO_BE_RESPECTED)
            } else {
                // do nothing
            }
        } catch (e: Exception) {
            logger.error("disableVehicleAndWithdrawExecutingOrder failed! $e .")
        }
    }

    companion object {
        // // header:2 + mode:1 + summary:1 + id:4 + data:8 + timestamp:3 + check:1 = 20
        const val totalSize = 20
        val pkgStart = byteArrayOf(0xFE.toByte(), 0xFD.toByte())
        const val mode: Byte = 0x00                                     // mode，固定值[0x00]
        private const val summary: Byte = 0x88.toByte()                 // summary，固定值[0x88]
        val id = byteArrayOf(0x18, 0x06, 0xE5.toByte(), 0xF4.toByte())  // id，固定值[0x18 0x06 0xE5 0xF4]
        val timestamp = byteArrayOf(0x00, 0x00, 0x00)                   // 时间戳（非必要数据），固定值[0x00, 0x00, 0x00]

        fun buildTotalBuffer(voltage: Int, current: Int, turnOn: Boolean, flowNo: Short): ByteBuffer {
            val totalBuffer = ByteBuffer.allocate(totalSize)
            totalBuffer.order(ByteOrder.BIG_ENDIAN)
            totalBuffer.put(pkgStart)
            totalBuffer.put(mode)
            totalBuffer.put(summary)
            totalBuffer.put(id)
            totalBuffer.putShort(voltage.toShort())                 // 电压
            totalBuffer.putShort(current.toShort())                 // 电流
            totalBuffer.put((if (turnOn) 0 else 1).toByte())        // 控制类型 0-充电机开启充电；1-电池保护，充电机关闭输出
            totalBuffer.putShort(flowNo)                            // 流水号
            totalBuffer.put(0x00)                                   // 保留字节
            totalBuffer.put(timestamp)
            totalBuffer.put(checkTheBuffer(totalBuffer.array()))    // 添加 校验字, 前面19个字节的异或值

            totalBuffer.flip()
            return totalBuffer
        }
    }
}

fun isTimedOut(timestamp: Instant?, timeout: Int): ResultAndMsg? {
    if (timestamp == null) return null
    val duration = toPositive(Duration.between(timestamp, Instant.now()).toSeconds())
    return ResultAndMsg(duration > timeout, duration.toString())
}

fun isTimedOut(aim: Instant, base: Instant, timeout: Int): ResultAndMsg {
    val duration = toPositive(Duration.between(base, aim).toSeconds())
    return ResultAndMsg(duration > timeout, duration.toString())
}

fun convertByteArrayToHexString(ba: ByteArray, totalLen: Int): String {
    val baTxt: MutableList<String> = mutableListOf()
    val len = if (ba.size > totalLen) totalLen else ba.size
    for (i in 0 until len) {
        baTxt += "0x${ba[i]}"
    }
    return baTxt.toString()
}

/** 计算校验字， byte0^...^byte18;  buffer包含校验字，但是不会对校验字进行异或 */
fun checkTheBuffer(buffer: ByteArray): Byte {
    var check = buffer.first()
    for (i in 1 until buffer.size - 1) {
        // 防止重复重复异或第一个字节，不校验最后一个字节
        check = check xor buffer[i]
    }
    return check
}

data class ChargerModel(
    val config: ChargerConfig = ChargerConfig(),
    val newProto: Boolean = false, // 适配的是不是新协议的机器人（弃用），换用 routeConfig.newCommAdapter
    var turnedOn: Boolean = false, // true: 开始充电; false: 停止充电    //-
    var enabled: Boolean = true,    // true: 启用充电机; false: 禁用充电机
    var timedOut: Boolean = false,  // 充电机是否处于超时状态
    // 充电机每隔 1S 发送广播信息（报文 2），显示仪表可以根据信息显示充电机状态
    var online: Boolean = false, // 充电机在线状态 //-
    var voltageToCharger: Int = 0, // 写入到充电机的电压，数值为实际电压的10倍 //-
    var currentToCharger: Int = 0, // 写入到充电机的电流，数值为实际电压的10倍 //-
    var status: ChargerStatus = ChargerStatus.UNKNOWN, // 充电机状态    //-
    var chargerReports: ChargerReports = ChargerReports(),      // 充电机上报的信息
    var vehicleInfo: VehicleInfo = VehicleInfo()    // 充电机上机器人的状态
)

class ChargerConfig(
    var name: String = "",
    var host: String = "",
    var port: Int = 0,
    var location: String = "",
    var timeout: Int = 60,                  // 充电超时时间， timeout（配置） = timeout（期望）+ 手臂伸缩一次的总时间（7+7）
    var connectTimeout: Int = 100,          // 充电机连接超时时间
    var printChangedStatus: Boolean = true, // 只打印状态变化的数据
    var mode: String = "Tcp",               // Tcp, AioTcp
    var maxVoltage: Int = 0,                // 安全电压，默认值为30V，数值为实际电压的10倍
    var maxCurrent: Int = 0                 // 安全电流，默认值为250A，数值为实际电压的10倍
)

enum class ChargerStatus {
    UNKNOWN,    // 初始状态时充电机的状态
    ERROR,
    IDLE,
    CHARGING
}

data class ChargerReports(
    var voltage: String = "0.0V",               // 从充电机读取的电压，数值为实际电压的10倍
    var current: String = "0.0A",               // 从充电机读取的电流，数值为实际电流的10倍
    var hdError: Boolean = false,               // 硬件故障         false: 正常          true: 硬件故障
    var temperature: Boolean = false,           // 电池温度是否异常  false: 正常          true: 充电机温度过高保护
    var inputVoltageStatus: Boolean = false,    // 输入电压状态     false: 输入电压正常   true: 输入电压错误，充电机停止工作
    var armOut: Boolean = false,                // 手臂状态         false: 缩回          true: 伸出
    var communicationStatus: Boolean = false,   // 通信状态         false: 通信正常;      true: 通信接收超时
    var relayStatus: Boolean = false,           // 继电器状态       false: 闭合           true: 断开
    var forceBreak: Boolean = false,            // 强制断开标志     false: 关闭           true: 打开
    var charging: Boolean = false               // 工作状态         false: 处于关闭状态;  true: 充电中
)

// 在充电点位的机器人名称
data class VehicleInfo(
    val id: String = "NO_VEHICLE",

    // true: 当前充电机点位能获取到机器人信息; false: 当前充电机点位无法获取机器人信息
    // 当调度无法获取到机器人信息时，就会停止给充电机充电。
    var onPosition: Boolean = false,
    var requestVoltage: Int = 0, // 从电池读取到的电压
    var requestCurrent: Int = 0, // 从电池读取到的电流
    var charging: Boolean = false,  // 机器人的充电状态
    var forkAutoFlag: Boolean = false, // false: 手动模式（充电机不能工作）； true: 自动模式（充电机可以工作）。
    var state: Vehicle.State = Vehicle.State.UNKNOWN,         // 机器人状态
    var isDominating: Boolean = false, // 机器人是否被本系统占用
    var integrationLevel: Vehicle.IntegrationLevel = Vehicle.IntegrationLevel.TO_BE_IGNORED,   // 机器人在线状态
    var transportOrder: String = "" // 机器人最近一次执行的运单
)

data class RepBody(
    val lastActionStatus: String = "",
    val name: String = "",
    val lastAction: String = "",
    val status: String = ""
)

data class ResultAndMsg(
    val result: Boolean = true,
    val msg: String? = null
)
