package com.seer.srd.device

import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import com.seer.srd.device.converter.formatWriteDataForCoils
import com.seer.srd.device.converter.parseCoilsData
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.scheduler.GlobalTimer
import com.seer.srd.scheduler.backgroundCacheExecutor
import org.slf4j.LoggerFactory
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class ZoneConfig(
    var boxes: List<BoxDetails> = listOf(), // 同一个交通管制区域可能存在若干个指示灯和复位开关
    var involvedSystems: Map<String, String> = emptyMap(), // 参与交通管制的调度系统 sysId:ip
    // true: 区域空闲时，按下后再释放按钮，区域状态才会变为被占用，再次按下并释放，区域状态才会恢复为空闲
    // false: 区域空闲时，按钮按钮后，区域状态变为被占用，释放按钮后，区域状态恢复为空闲
    var resetBtn: Boolean = true,
    // true：批量写入信号；false：逐个写入信号
    var writeBatch: Boolean = false
)

/*  */
data class BoxDetails(
    var boxId: String = "",
    var host: String = "",
    var port: Int = 0,
    var unitId: Int = 0,                // PLC 的 slaveId，不同 PLC 设置 的 slaveId 不一定都相同， 台达的PLC用默认值 0 即可
    var lightRedAddr: ModbusWriteAddr? = null,
    var lightGreenAddr: ModbusWriteAddr? = null,
    var lightYellowAddr: ModbusWriteAddr? = null,
    var switchAddr: ModbusReadAddr? = null,             // 复位开关对应的地址信息
    // 当区域状态改变时，改变所有指示灯的状态，例如区域被机器人占用时，点亮红灯，熄灭黄灯和绿灯；
    // 只在PLC有高级的逻辑控制时，outputAllSignal = false, 区域被机器人占用时，只点亮红灯，不控制黄灯和绿灯。
    var outputAllSignal: Boolean = true,
    var standAlone: Boolean = false     // false:工作时需要连接网络设备；true:工作时不需要连接网络设备
)

data class ModbusWriteAddr(
    var addrNo: Int = 0,
    var funcNo: String = "05"
)

data class ModbusReadAddr(
    var addrNo: Int = 0,
    var funcNo: String = "02"
)

data class SysAndItsRobots(
    val ip: String = "",
    val robots: List<String> = emptyList()
)

data class StartAddrAndLength(
    val start: Int,
    val funcNo: String,
    val length: Int
)

class ZoneManager(val name: String, private val config: ZoneConfig) {

    private val logger = LoggerFactory.getLogger(ZoneManager::class.java)

    // 定义仙知机器人为甲方，对方设备/人/机器人等为乙方。
    // 当区域被甲方获取时，status 为区域内的甲方数量，即 status > 0。
    // 当区域未被任何一方获取时，status 为 0。
    // 当区域被乙方获取时，status 为 -1。
    @Volatile
    var status: Int = 0
        private set

    val details: MutableMap<String, SysAndItsRobots> = HashMap()

    @Volatile
    private var switched = false

    private var helperMap: MutableMap<String, ModbusTcpMasterHelper?> = HashMap()

    @Volatile
    private var lastStatus: Int? = null

    // <boxId, <color, ModbusWriteAddr>>
    private val colorMapForBatch = mutableMapOf<String, MutableMap<String, ModbusWriteAddr>>()

    private val startAddrAndLengthMap: MutableMap<String, StartAddrAndLength?> = mutableMapOf()

    private val timer: ScheduledFuture<*>

    init {
        config.involvedSystems.forEach { (sysId, ip) -> details[sysId] = SysAndItsRobots(ip) }
        config.boxes.forEach {
            helperMap[it.boxId] = if (it.standAlone) null else ModbusTcpMasterHelper(it.host, it.port)
            startAddrAndLengthMap[it.boxId] = if (it.standAlone) null else getStartAddrAndLength(it)
            if (!it.standAlone) colorMapForBatch[it.boxId] = getColorMapForBatch(it)
        }

        timer = GlobalTimer.executor.scheduleAtFixedRate(
            this::syncStatus,
            2000,
            CONFIG.zonesStatusPollingPeriod,
            TimeUnit.MILLISECONDS
        )

        // todo: 开机时亮绿灯
    }

    fun dispose() {
        timer.cancel(true)
    }

    private fun getStartAddrAndLength(details: BoxDetails): StartAddrAndLength {
        val sorted = listOfNotNull(details.lightGreenAddr, details.lightRedAddr, details.lightYellowAddr).sortedBy { it.addrNo }
        if (sorted.isEmpty()) throw BusinessError("Zone[$name]: 交通管制区域配置异常，请配置必要的地址后重启SRD！")
        val first = sorted.first()
        val length = (if (sorted.size > 1) sorted.last().addrNo - first.addrNo else 0) + 1
        return StartAddrAndLength(first.addrNo, first.funcNo, length)
    }

    private fun getColorMapForBatch(details: BoxDetails): MutableMap<String, ModbusWriteAddr> {
        val colorMap = mutableMapOf<String, ModbusWriteAddr>()
        if (details.lightRedAddr != null) colorMap["red"] = details.lightRedAddr!!
        if (details.lightYellowAddr != null) colorMap["yellow"] = details.lightYellowAddr!!
        if (details.lightGreenAddr != null) colorMap["green"] = details.lightGreenAddr!!
        return colorMap
    }

    fun updateTrafficLightColor() {
        // 设置目标交通管制区的信号灯的颜色
        lastStatus = status
        config.boxes.forEach {
            val unitId = it.unitId
            val helper = helperMap[it.boxId] ?: return
            if (it.outputAllSignal) {
                // 批量写入数据
                if (config.writeBatch) {
                    val startAddrAndLength = startAddrAndLengthMap[it.boxId]
                        ?: throw BusinessError("Zone[$name]: 写入数据失败，无法获取批量操作的起始地址和长度，请检查配置文件！")
                    setLightBatchIfDiff(it.boxId, startAddrAndLength, helper, unitId)
                    return
                }
                if (it.lightRedAddr != null) setLight(LightColor.Red, it.lightRedAddr!!, helper, unitId)
                if (it.lightGreenAddr != null) setLight(LightColor.Green, it.lightGreenAddr!!, helper, unitId)
                if (it.lightYellowAddr != null) setLight(LightColor.Yellow, it.lightYellowAddr!!, helper, unitId)
            } else when (status) {
                -1 -> if (it.lightYellowAddr != null) setLight(LightColor.Yellow, it.lightYellowAddr!!, helper, unitId)
                0 -> if (it.lightGreenAddr != null) setLight(LightColor.Green, it.lightGreenAddr!!, helper, unitId)
                else -> if (status > 0) if (it.lightRedAddr != null) setLight(LightColor.Red, it.lightRedAddr!!, helper, unitId)
            }
        }
    }

    /*
    1 如果这个地址编号 < 0 不执行写操作
    2 根据功能码，写不同的地址 05 & 06
     */
    fun setLight(color: LightColor, modbusAddr: ModbusWriteAddr, helper: ModbusTcpMasterHelper, unitId: Int) {
        val no = status
        val addrNo = modbusAddr.addrNo

        val value = when (color) {
            LightColor.Red -> no > 0
            LightColor.Green -> no == 0
            else -> no == -1
        }

        when (modbusAddr.funcNo) {
            "05" ->
                helper.write05SingleCoil(addrNo, value, unitId, "updateTrafficLightColor $color $no")
            "06" ->
                helper.write06SingleRegister(addrNo, no, unitId, "updateTrafficLightColor $color $no")
        }
    }

    private fun setLightBatchIfDiff(boxId: String, startAddrAndLength: StartAddrAndLength, helper: ModbusTcpMasterHelper, unitId: Int) {
        val start = startAddrAndLength.start
        val valueList = (0 until startAddrAndLength.length).map { 0 }.toMutableList()
        val colorText = if (status == -1) "yellow"  // 亮黄灯
        else if (status == 0) "green"               // 亮绿灯
        else if (status > 0) "red"                  // 亮红灯
        else throw BusinessError("Zone[$name]: 操作信号灯失败，无法识别的区域状态【${status}】")

        val colorAddrNo = colorMapForBatch[boxId]?.get(colorText)?.addrNo!!
        val index = colorAddrNo - startAddrAndLength.start
        valueList[index] = 1
        val value = formatWriteDataForCoils(valueList)
        val length = startAddrAndLength.length
        when (startAddrAndLength.funcNo) {
            "05" -> {
                val currentBuf = helper.read01Coils(start, length, unitId, "Zone[$name] read before write")
                    ?: throw BusinessError("Zone[$name]: 操作失败，写入数据之前，读取到的数据为空。")
                val currentList = parseCoilsData(currentBuf, length)
                if (currentList.toString() != valueList.toString())
                    helper.write0FMultipleCoils(start, length, value, unitId, "Zone[$name] updateTrafficLightColor to $colorText")

            }
            "06" -> {
                // 暂时不考虑通过操作可读写寄存器的方式输出信号灯的状态。
            }
        }
    }

    /** 进入交通管制区 */
    @Synchronized
    fun enter(robot: String, systemId: String, remark: String) {
        // 操作员进入
        if (robot == "OPERATOR") {
            operatorEnter()
            return
        }

        if (status < 0) throw BusinessError("Zone[$name]: 区域中存在操作员，机器人无法进入")

        val r = robotEnter(robot, systemId)

        // 更新区域中的机器人总数
        if (r) {
            ++status
            updateTrafficLightColor()
        }
    }

    /** 操作员进入 */
    private fun operatorEnter() {
        if (status == 0) {
            if (this.switched) {
                throw BusinessError("Zone[$name]: 区域的复位开关未释放，请先释放开关")
            } else {
                logger.info("Zone[$name]: 操作员进入管制区域")
                status = -1
                updateTrafficLightColor()
            }
        } else if (status > 0) {
            throw BusinessError("Zone[$name]: 区域中存在机器人，操作员进入失败")
        }
    }

    // 机器人进入
    private fun robotEnter(robot: String, sysId: String): Boolean {
        logger.info("Zone[$name]: 机器人 $robot 进入管制区域 $name, systemId=$sysId")

        val sysRobots = details[sysId]
            ?: throw BusinessError("Zone[$name]: 无法识别请求方 $sysId。请检查请求参数 $sysId 和配置文件 involvedSystems")

        var total = 0
        details.values.forEach { total += it.robots.size }

        val robotList = ArrayList(sysRobots.robots)
        return when {
            total == 0 -> {
                robotList += robot
                details[sysId] = sysRobots.copy(robots = robotList)
                true
            }
            robotList.size > 0 -> {
                if (robotList.contains(robot)) {
                    logger.error("Zone[$name]: 由系统[${sysId}]控制的机器人[${robot}]已经在管制区内")
                    false
                } else {
                    robotList += robot
                    details[sysId] = sysRobots.copy(robots = robotList)
                    logger.info("Zone[$name]: 由系统[${sysId}]控制的机器人[${robot}]进入管制区")
                    true
                }
            }
            total - robotList.size > 0 -> throw BusinessError("Zone[$name]: 管制区域正在被其它系统管制")
            else -> false
        }
    }

    /** 机器人离开 */
    fun leave(robot: String, sysId: String, remark: String) {
        // 操作员离开
        if (robot == "OPERATOR") {
            operatorLeave()
            return
        }

        logger.info("Zone[$name]: 机器人 $robot 离开管制区域 $name, systemId=$sysId")
        this.kickRobot(robot, sysId)
    }

    private fun operatorLeave() {
        if (status == -1) {
            if (this.switched) {
                throw BusinessError("Zone[$name]: 区域的复位开关未释放，请先释放开关")
            } else {
                status = 0
                updateTrafficLightColor()
                logger.info("Zone[$name]: 操作员离开管制区域")
            }
        } else {
            throw BusinessError("Zone[$name]: 区域中不存在操作员")
        }
    }

    /** 踢除指定区域的目标机器人 */
    fun kickRobot(robot: String, sysId: String) {
        if (status < 1) {
            logger.error("Zone[$name]: 区域中不存在由[${sysId}]控制的机器人[${robot}]")
            return
//            throw BusinessError("区域[${name}]中不存在由[${sysId}]控制的机器人[${robot}]")
        }

        logger.info("Zone[$name]: Kick robot $robot zone=$name")

        this.removeRobotFromRobotListAndUpdateStatus(robot, sysId)
        this.updateTrafficLightColor()
    }

    /** 从机器人列表中移除目标机器人 */
    private fun removeRobotFromRobotListAndUpdateStatus(robot: String, sysId: String) {
        val r = robotLeave(robot, sysId)

        // 更新区域中机器人数量
        if (r) {
            if (status >= 1) --status
        }
    }

    // 机器人离开
    private fun robotLeave(robot: String, sysId: String): Boolean {
        logger.info("Zone[$name]: Robot $robot leave $name, sysId=$sysId")

        val sysRobots = details[sysId]
            ?: throw BusinessError("Zone[$name]: 无法识别请求方 $sysId。请检查请求参数 $sysId 和配置文件 involvedSystems")

        return if (!sysRobots.robots.contains(robot)) {
            // 操作失败
            logger.info("Zone[$name]: 管制区域中不存在系统[${sysId}]控制的机器人[${robot}]")
            false
            //            throw BusinessError("管制区域[${name}]中不存在系统[${sysId}]控制的机器人[${robot}]")
        } else {
            // 从列表中移除目标机器人
            val robots = ArrayList(sysRobots.robots)
            robots -= robot
            details[sysId] = sysRobots.copy(robots = robots)
            logger.info("Zone[$name]: 由系统[${sysId}]控制的机器人[${robot}]离开管制区")
            true
        }
    }

    /** 踢除指定区域的所有机器人 */
    @Synchronized
    fun kickAllRobots(sysId: String) {
        val sysRobots = details[sysId]
            ?: throw BusinessError("Zone[$name]: 无法识别请求方 $sysId。请检查请求参数 $sysId 和配置文件 involvedSystems")

        val count = sysRobots.robots.size
        details[sysId] = sysRobots.copy(robots = emptyList())

        // 更新交通管制区的状态为 0
        val final = status - count
        status = if (final > 0) final else 0
        // 更新交通管制区的信号灯颜色
        updateTrafficLightColor()
    }

    @Synchronized
    private fun syncStatus() {
        try {
            // 获取自动门的实际状态
            config.boxes.forEach {
                // 确保每一个复位开关的状态检测都独立
                backgroundCacheExecutor.submit {
                    val switchAddr = it.switchAddr
                    if (switchAddr != null) {
                        val helper = helperMap[it.boxId] ?: return@submit
                        val addrNo = switchAddr.addrNo
                        val unitId = it.unitId
                        val remark = "Zone[$name]: syncTrafficLightColor"
                        val value = when (switchAddr.funcNo) {
                            "01" -> helper.read01Coils(addrNo, 1, unitId, remark)
                                ?.getByte(0)?.toInt()
                            "02" -> helper.read02DiscreteInputs(addrNo, 1, unitId, remark)
                                ?.getByte(0)?.toInt()
                            "03" -> helper.read03HoldingRegisters(addrNo, 1, unitId, remark)
                                ?.getShort(0)?.toInt()
                            "04" -> helper.read04InputRegisters(addrNo, 1, unitId, remark)
                                ?.getShort(0)?.toInt()
                            else -> 0
                        }

                        // 通过单次信号改变，模拟人按按钮操作
                        if (!config.resetBtn) {
                            when (status) {
                                -1 -> {
                                    logOperationIfStatusChanged("Zone[$name]: 操作员释放控制权")
                                    if (value == 0) status = 0
                                }
                                0 -> {
                                    logOperationIfStatusChanged("Zone[$name]: 操作员获取控制权")
                                    if (value != 0) status = -1
                                }
                                1 -> {
                                    logOperationIfStatusChanged("Zone[$name]: 交通管制区中存在机器人，操作员禁止入内")
                                }
                            }
                            updateTrafficLightColor()
                            return@submit
                        }

                        if (value != 0) { // 开关被触发
                            if (!switched) {
                                // 只有当目标区域为被占用时，才能修改此区域的状态为 -1
                                when (status) {
                                    -1 -> {
                                        logOperationIfStatusChanged("操作员释放控制权")
                                        status = 0
                                    }
                                    0 -> {
                                        logOperationIfStatusChanged("操作员获取控制权")
                                        status = -1
                                    }
                                    else -> {
                                        logOperationIfStatusChanged("交通管制区中存在机器人，操作员禁止入内")
                                    }
                                }
                                switched = true
                                updateTrafficLightColor()
                            }
                        } else { // 开关被释放
                            switched = false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Zone[${name}] sync status failed", e)
        }
    }

    private fun logOperationIfStatusChanged(message: String) {
        if (lastStatus != status) logger.info("区域【${name}】【按钮信号改变】resetBtn=${config.resetBtn}：$message")
    }

    fun getStatusDO(sysId: String): ZoneStatusDO {
        val robotCount = details[sysId]?.robots?.size ?: 0
        return ZoneStatusDO(name, if (robotCount > 0) robotCount else (if (status == 0) 0 else -1))
    }

    fun getZoneMoreStatusDO(): ZoneMoreStatusDO {
        return ZoneMoreStatusDO(name, status, details)
    }
}

data class ZoneStatusDO(
    val name: String,
    val status: Int
)

data class ZoneMoreStatusDO(
    val name: String,
    val status: Int,
    val details: Map<String, SysAndItsRobots>
)

enum class LightColor {
    Red, Yellow, Green
}