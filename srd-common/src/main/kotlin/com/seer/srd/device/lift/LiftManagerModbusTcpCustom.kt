package com.seer.srd.device.lift

import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.scheduler.GlobalTimer
import com.seer.srd.scheduler.backgroundFixedExecutor
import com.seer.srd.util.mapper
import org.litote.kmongo.json
import org.slf4j.LoggerFactory
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class LiftManagerModbusTcpCustom(val config: LiftConfig) : AbstractLiftManager(config) {

    private var initialized = false

    private var helper: ModbusTcpMasterHelper? = null

    private val mc =
        if (config.modbusConfig == null)
            throw BusinessError("Lift[${config.name}] modbusConfig cannot be null when its mode=${config.mode}!")
        else mapper.readValue(config.modbusConfig!!.json, ModbusConfigCustom::class.java)

    private val open = mc.open

    private val close = mc.close

    private val openSameAsClose = (open.type == close.type) && (open.addrNo == close.addrNo)

    private val go = mc.go ?: mc.call

    var enterPermitted = false
    var leavePermitted = false

    @Volatile
    private var calling = false
    @Volatile
    private var going = false

    private val rate = CONFIG.liftStatusPollingPeriod

    private val delayForClearCall = Delay(mc.delayForClearCall, rate)

    private val delayForClearGo = Delay(mc.delayForClearGo, rate)

    private val delayForClearIdleInside = Delay(mc.delayForClearIdleInside, rate)

    private val delayForClearIdleOutside = Delay(mc.delayForClearIdleOutside, rate)

    private var lastMessage = ""

    private val timer: ScheduledFuture<*>

    init {
        logInfo("$mc")

        // todo: 完成这两个状态的可靠性、实时性。
        model = model.copy(commandTimeout = false, online = true)

        getHelper()

        timer = GlobalTimer.executor.scheduleAtFixedRate(
            this::syncStatus,
            2000,
            rate,
            TimeUnit.MILLISECONDS
        )
//        checkLiftAvailable()
    }

    private fun initialData(): Boolean {
        try {
            if (initialized) return true
            controlLift(mc.call, 0, "initialize")
            controlLift(go, 0, "initialize")
            controlLift(mc.idleInside!!, 0, "initialize")
            controlLift(mc.idleOutside!!, 0, "initialize")
            initialized = true
            return true
        } catch (e: Exception) {
            logError("initialData failed!", e)
            return false
        }
    }

    private fun getHelper(): ModbusTcpMasterHelper? {
        if (config.standAlone) return null
        if (helper == null) helper = ModbusTcpMasterHelper(config.host, config.port)
        return helper
    }

    override fun dispose() {
        helper?.disconnect()
        timer.cancel(true)
    }

    override fun call(floor: String, remark: String) {
        if (model.isOccupy) {
            logError("call but occupied")
            throw BusinessError("CallButOccupied ${liftConfig.name}")
        }

        logInfo("call to floor=$floor : $remark")
        model = model.copy(destFloor = floor.toInt().toString())
        delayForClearCall.setToLimit()
        if (calling) return  // call 会被高频调用，如果写入时连接超时了，会缓存大量开门请求，及时运单被取消了，这些请求还是会被继续执行。
        backgroundFixedExecutor.submit {
            calling = true
            controlLift(mc.call, floor.toInt(), remark)
            calling = false
        }
    }

    override fun go(floor: String, remark: String) {
        if (!model.isOccupy) {
            logError("go but not occupied")
            throw BusinessError("GoButNotOccupied ${liftConfig.name}")
        }

        logInfo("go to floor=$floor : $remark")
        model = model.copy(destFloor = floor.toInt().toString())
        delayForClearGo.setToLimit()
        if (going) return
        backgroundFixedExecutor.submit {
            going = true
            controlLift(go, floor.toInt(), remark)
            going = false
        }
    }

    override fun setOccupied(occupied: Boolean, remark: String) {
        logInfo("setOccupied from ${model.isOccupy} to $occupied . $remark")

        model = model.copy(isOccupy = occupied)
        backgroundFixedExecutor.submit {
            // 当电梯 occupy 和 unoccupy 时，都要关闭电梯门
            closeDoor("setOccupied")
            // 定制功能
            if (model.isOccupy) {
                vehicleIdleInsideTheLift(true)
                // 机器人在电梯内就位，需要“清空call的数值”
                controlLift(mc.call, 0, remark)
                // 一段时间之后（默认值为5秒），清空“机器人到达SM点”的信号
                delayForClearIdleInside.setToLimit()
            } else {
                vehicleIdleOutsideTheLift(true)
                // 机器人已经离开电梯，需要“清空go的数值”
                controlLift(go, 0, remark)
                // 一段时间之后（默认值为5秒），清空“机器人到达电梯的前置点”的信号
                delayForClearIdleOutside.setToLimit()
            }
        }
    }

    // 机器人可以进入电梯时，发送开门请求
    override fun openDoor(remark: String) {
        if (mc.ignoreOpenOrClose) return
        logInfo("open door : $remark")
        val value = if (!open.inverseValueOf0xOr1x) 1 else 0
        // 开门信号和关门信号的地址相同时，只需要操作开门地址就行了
        if (openSameAsClose) controlLift(open, value, remark)
        else {
            // 两者不同时，先清空关门信号
            controlLift(close, (value + 1) % 2, remark)
            controlLift(open, value, remark)
        }
    }

    // 占用或者解除占用电梯时，都要关门
    override fun closeDoor(remark: String) {
        if (mc.ignoreOpenOrClose) return
        logInfo("close door : $remark")
        val value = if (!close.inverseValueOf0xOr1x) 1 else 0
        if (openSameAsClose) controlLift(close, value, remark)
        else {
            // 两者不同时，先清空开门信号
            controlLift(open, (value + 1) % 2, remark)
            controlLift(close, value, remark)
        }
    }

    fun vehicleIdleInsideTheLift(set: Boolean) {
        val remark = "(${if (set) "set" else "reset"}) vehicle idle inside the lift"
        try {
            val idleInside = mc.idleInside ?: return
            if (lastMessage != remark) {
                lastMessage = remark
                logInfo(remark)
            }
            val value = if (!idleInside.inverseValueOf0xOr1x) 1 else 0
            val value2 = if (set) value else (value + 1) % 2
            val throwError = !set
            controlLift(idleInside, value2, remark, throwError, true)
        } catch (e: Exception) {
            logError("$remark failed: $e")
        }
    }

    fun vehicleIdleOutsideTheLift(set: Boolean) {
        val remark = "(${if (set) "set" else "reset"}) vehicle idle outside the lift"
        try {
            val idleOutside = mc.idleOutside ?: return
            if (lastMessage != remark) {
                lastMessage = remark
                logInfo(remark)
            }
            val value = if (!idleOutside.inverseValueOf0xOr1x) 1 else 0
            val value2 = if (set) value else (value + 1) % 2
            controlLift(idleOutside, value2, remark, writeWhenDiff = true)
        } catch (e: Exception) {
            logError("$remark failed: $e")
        }
    }

    private fun controlLift(address: Address, value: Int, remark: String, throwError: Boolean = false, writeWhenDiff: Boolean = false) {
        try {
            if (config.standAlone || address.type == null || address.addrNo == null) {
                logError("control lift failed(ModbusTcp): standAlone=${config.standAlone}, $address")
                return
            }
            writeSingleValue(address, value, remark, writeWhenDiff)
        } catch (e: Exception) {
            if (throwError) throw e
            logError("control lift failed(modbusTcp): $e")
        }
    }

    private fun writeSingleValue(address: Address, value: Int, remark: String, writeWhenDiff: Boolean = false) {
        // 如果读取到的数据跟需要写入的数据相同，则放弃写入操作
        if (writeWhenDiff) {
            val oldValue = readSingleValue(address, "read before write[$remark].")
                ?: throw BusinessError("read $address failed before write it!")
            if (oldValue == value) return
        }

        when (address.type) {
            "0x" -> getHelper()?.write05SingleCoil(address.addrNo!!, value == 1, mc.unitId, remark)
            "4x" -> getHelper()?.write06SingleRegister(address.addrNo!!, value, mc.unitId, remark)
            else -> logError("control lift failed(ModbusTcp): unsupported funNo=${address.type}")
        }
    }

    private fun readSingleValue(address: Address, remark: String): Int? {
        try {
            if (config.standAlone || address.type == null || address.addrNo == null) return null
            val result = when (address.type) {
                "0x" ->
                    getHelper()?.read01Coils(address.addrNo!!, 1, mc.unitId, remark)?.getByte(0)?.toInt()
                "1x" ->
                    getHelper()?.read02DiscreteInputs(address.addrNo!!, 1, mc.unitId, remark)?.getByte(0)?.toInt()
                "3x" ->
                    getHelper()?.read04InputRegisters(address.addrNo!!, 1, mc.unitId, remark)?.getShort(0)?.toInt()
                "4x" ->
                    getHelper()?.read03HoldingRegisters(address.addrNo!!, 1, mc.unitId, remark)?.getShort(0)?.toInt()
                else -> {
                    logError("get info failed(ModbusTcp): unsupported addressType=${address.type}, addrNo=${address.addrNo} .")
                    null
                }
            }
            return result
        } catch (e: Exception) {
            logError("get info failed(modbusTcp): $e")
            return null
        }
    }

    override fun checkOffline() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun syncStatus() {
        try {
            // 初始化数据
            if (!initialData()) return

            // 电梯门的状态
            getLiftDoorStatus()
            // 电梯所在楼层编号
            getLiftFloor()
            // 控制电梯开门  成都西门子项目不用发送开门请求
            // if (model.currentFloor == model.destFloor && model.doorStatus == LiftDoorStatus.OPEN) {
            //     openDoor("open door")
            //     // 清空“机器人到达SM点”的信号
            //     vehicleIdleInsideTheLift(false)
            // }

            // 机器人是否可以离开进入电梯
            enterPermitted()
            // 机器人是否可以离开电梯
            leavePermitted()

            // 一段时间后未收到持续的call信号，就清空call信号
            delayForClearCall.doSomethingWhenDelayed(config.name) {
                try {
                    controlLift(mc.call, 0, "clear call for no more request!")
                } catch (e: Exception) {
                    logError("clear call failed!", e)
                }
            }

            // 一段时间后未收到持续的go信号，就清空go信号
            delayForClearGo.doSomethingWhenDelayed(config.name) {
                try {
                    controlLift(go, 0, "clear go for no more request!")
                } catch (e: Exception) {
                    logError("clear go failed!", e)
                }
            }

            // 一段时间后(默认值为5s)，清空 “机器人到达SM点” 的信号
            delayForClearIdleInside.doSomethingWhenDelayed(config.name) {
                try {
                    vehicleIdleInsideTheLift(false)
                } catch (e: Exception) {
                    logError("clear vehicle-idle-inside failed!", e)
                }
            }

            // 一段时间后(默认值为5s)，清空 “机器人到达电梯前置点” 的信号
            delayForClearIdleOutside.doSomethingWhenDelayed(config.name) {
                try {
                    vehicleIdleOutsideTheLift(false)
                } catch (e: Exception) {
                    logError("clear vehicle-idle-outside failed!", e)
                }
            }

        } catch (e: Exception) {
            logError("sync status failed: $e")
        }
    }

    private fun getLiftFloor() {
        try {
            val floor = mc.liftFloor ?: return
            val currFloor = readSingleValue(floor, "read floor")
                ?: throw BusinessError("Lift[${config.name}] get floor failed: result cannot be null !")
            // 等 enterPermitted = true 时，再写入model，否者可能会导致电梯在目标楼层开门之后，机器人就直接进入/离开电梯了
            if (model.currentFloor != currFloor.toString()) liftChanged(model.copy(currentFloor = currFloor.toString()))
        } catch (e: Exception) {
            logError("get current floor falied: $e")
        }
    }

    private fun getLiftDoorStatus() {
        try {
            val door = mc.liftDoorStatus ?: return
            // 0: door closed; 1: door opened
            val message = "Lift[${config.name}] get door status=$door"
            val doorStatus = readSingleValue(door, "read door status")
                ?: throw BusinessError("$message failed: result cannot be null !")
            if (doorStatus != 0 && doorStatus != 1)
                throw BusinessError("$message failed: result($doorStatus) should be 0 or 1 !")
            val inverse = door.inverseValueOf0xOr1x
            val formatedStatus =
                if ((inverse && doorStatus == 0) || (!inverse && doorStatus == 1)) LiftDoorStatus.OPEN
                else LiftDoorStatus.CLOSE
            if (model.doorStatus != formatedStatus) liftChanged(model.copy(doorStatus = formatedStatus))
        } catch (e: Exception) {
            logError("get door status failed: $e")
        }
    }

    private fun readBoolean(address: Address, valueDescriptor: String): Boolean {
        try {
            val rawValue = readSingleValue(address, valueDescriptor)
                ?: throw BusinessError("Lift[${config.name}] get door status(enter permission) failed: result cannot be null !")
            if (rawValue != 0 && rawValue != 1)
                throw BusinessError("Lift[${config.name}] get door status(enter permission) failed: result($rawValue) should be 0 or 1 !")
            val inverse = address.inverseValueOf0xOr1x
            return if (inverse) (rawValue == 0) else (rawValue == 1)
        } catch (e: Exception) {
            throw BusinessError("Lift[${config.name}] get door status($valueDescriptor) failed: $e")
        }
    }

    private fun enterPermitted() {
        val ep = mc.enterPermission ?: return
        val result = readBoolean(ep, "enter permission")
        if (result != enterPermitted) enterPermitted = result
    }

    private fun leavePermitted() {
        val lp = mc.leavePermission ?: return
        val result = readBoolean(lp, "leave permission")
        if (result != leavePermitted) leavePermitted = result
    }
}

/*
【西门子 - 成都】
    06(4x0001) call的楼层信息
    06(4x0002) go的楼层信息
    05(0x0001) 机器人到达SM点 (占用电梯时，执行此操作）
    05(0x0002) 机器人到达电梯的前置点 （解除占用电梯时，执行此操作）
    02(1x0001) 机器人是否可以进入电梯 syncStatus()
    02(1x0002) 机器人是否可以离开电梯 syncStatus()
    02(1x0003) 电梯门的状态 syncStatus()
    04(3x0001) 电梯所在的楼层信息 syncStatus()
*/

data class ModbusConfigCustom(
    var unitId: Int = 0,                            // 从站ID
    var ignoreOpenOrClose: Boolean = false,         // true: 不发送开门和关门指令
    var delayForClearCall: Int = 5,                 // 未收到连续的Call指令5秒之后,清空Call指令
    var delayForClearGo: Int = 5,                   // 未收到连续的Go指令5秒之后，清空Go指令
    var delayForClearIdleInside: Int = 5,           // 机器人到达SM点 5秒 之后，清空此到位信号
    var delayForClearIdleOutside: Int = 5,          // 机器人到达电梯前置点 5秒 之后，清空此到位信号
    // [4x]
    var call: Address = Address(),                  // [写] 不带车呼叫电梯（楼层编号）
    var go: Address? = Address(),                   // [写] 带车呼叫电梯（楼层编号）
    // [0x]
    var open: Address = Address(),                  // [写] 控制电梯开门
    var close: Address = Address(),                 // [写] 控制电梯关门
    // [3x]
    var liftFloor: Address? = null,                 // [读] 电梯所在楼层
    // [1x]
    var liftDoorStatus: Address? = null,            // [读] 电梯门的状态
    var idleInside: Address? = null,                // [读] 机器人到达SM点
    var idleOutside: Address? = null,               // [读] 机器人到达电梯的前置点
    var enterPermission: Address? = null,           // [读] 机器人可以进入电梯
    var leavePermission: Address? = null,           // [读] 机器人可以离开电梯

    var extra: Map<String, Address> = mapOf()       // [读] 额外配置，用于定制需求
)

data class Address(
    var type: String? = null,                      // 地址类型 0x, 1x, 3x, 4x
    var addrNo: Int? = null,                        // 地址编号
    var inverseValueOf0xOr1x: Boolean = false       // 线圈量的数值取反
)

fun checkAddressNum(address: Address): Address {
    if (address.addrNo == null) throw BusinessError("Address Num cannot be null!")
    else return address
}

class Delay(
    private val limitSeconds: Int,
    private val rateMileSeconds: Long
) {
    private val logger = LoggerFactory.getLogger("c.s.s.d.lift.LiftManagerModbusTcpCustom")
    @Volatile
    private var value: Long = -1

    fun terminated() = (-1L == value)

    private fun initValue() {
        value = -1
    }

    private fun reduce(): Long {
        if (value > 0) value--
        return value
    }

    fun setToLimit() {
        // 需要处理延时和执行频率之间的关系
        value = limitSeconds * (1000 / rateMileSeconds)
    }

    fun doSomethingWhenDelayed(liftName: String, action: (() -> Unit)) {
        try {
            if (reduce() == 0L) {
                action()
                initValue() // 防止一直在更新数据
            }
        } catch (e: Exception) {
            logger.error("Lift[$liftName] doSomethingWhenDelayed failed!", e)
        }
    }
}
