package com.seer.srd.device.lift

import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import com.seer.srd.device.charger.toPositive
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.scheduler.GlobalTimer
import com.seer.srd.scheduler.backgroundFixedExecutor
import com.seer.srd.util.mapper
import org.litote.kmongo.json
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

const val zeroShort = 0.toShort()

class LiftManagerModbusTcp(val config: LiftConfig) : AbstractLiftManager(config) {

    private var initialized = false

    // 电梯跟交通管制区域不同，一定是需要外设信号输入才能实现逻辑
    private val helper: ModbusTcpMasterHelper = ModbusTcpMasterHelper(config.host, config.port)

    // 正在执行call指令
    @Volatile
    private var calling = false

    // 正在执行go指令
    @Volatile
    private var going = false

    // 正在执行开门指令
    @Volatile
    private var opening = false

    // 正在执行关门指令
    @Volatile
    private var closing = false

    private val mc =
        if (config.modbusConfig == null)
            throw BusinessError("Lift[${config.name}] modbusConfig cannot be null when its mode=${config.mode}!")
        else mapper.readValue(config.modbusConfig!!.json, ModbusConfig::class.java)

    private val dest = mc.destFloor

    private val open = mc.openDoor

    private val close = mc.closeDoor

    private val writeAddrList: MutableList<Address> = mutableListOf(dest, open, close)

    // 只在初始化的时候才会修改
    private var writeQty: Int = 0

    private fun writeQty() = writeQty

    @Volatile
    private var writeValues: ByteArray? = null

    private fun writeValues() = writeValues

    private val readAddrList: MutableList<Address> = mutableListOf(
        mc.emergency, mc.moveStatus, mc.liftDoorStatus, mc.liftFloor
    )

    private val infosAndItsAddrNo: MutableMap<Int, LiftInfoField> = mutableMapOf()

    private var readQty: Int = 0
    private fun readQty() = readQty

    private val rate = if (mc.readAndWriteMultiply) CONFIG.liftStatusPollingPeriod / 2 else CONFIG.liftStatusPollingPeriod

    private val liftInUse = Delay(5, rate)

    private val delayForClearDestFloor = Delay(5, rate)
    private val delayForCancelOpenDoor = Delay(mc.delayForCancelOpen, rate)
    private val delayForCancelCloseDoor = Delay(mc.delayForCancelClose, rate)

    private var setOccupiedOn: Instant? = null

    private val timer: ScheduledFuture<*>

    init {
        logInfo("$mc")
        sortAddrListByAddrNo(this.writeAddrList, true)
        sortAddrListByAddrNo(this.readAddrList, false)

        infosAndItsAddrNo[mc.emergency.addrNo!!] = LiftInfoField.LiftEmergency
        infosAndItsAddrNo[mc.moveStatus.addrNo!!] = LiftInfoField.MoveStatus
        infosAndItsAddrNo[mc.liftDoorStatus.addrNo!!] = LiftInfoField.LiftDoorStatus
        infosAndItsAddrNo[mc.liftFloor.addrNo!!] = LiftInfoField.LiftFloor

        // todo: 完成这两个状态的可靠性、实时性。
        model = model.copy(commandTimeout = false, online = true)

        timer = GlobalTimer.executor.scheduleAtFixedRate(
            this::syncStatusAndControl,
            2000,
            rate,
            TimeUnit.MILLISECONDS
        )
    }

    private fun sortAddrListByAddrNo(addrList: MutableList<Address>, writeable: Boolean) {
        addrList.sortBy {
            if (it.type == null || it.addrNo == null) throw BusinessError("配置不完善$it")
            if (it.addrNo!! < 0) throw BusinessError("地址必须是不小于0的整数，$it")
            if (writeable) {
                if (it.type!! !in listOf("0x", "4x")) throw BusinessError("地址类型必须是0x、4x, $it")
            } else {
                if (it.type!! !in listOf("0x", "1x", "3x", "4x"))
                    throw BusinessError("地址类型必须是0x、1x、3x、4x, $it")
            }
            it.addrNo
        }
        if (writeable) {
            writeQty = addrList.last().addrNo!! - addrList.first().addrNo!! + 1
            // 写入一个寄存器的数据是short(2bytes)的，writeValue的长度应该是被操作地址数量的2倍
            writeValues = ByteArray(writeQty() * 2)
        } else {
            readQty = addrList.last().addrNo!! - addrList.first().addrNo!! + 1
        }
    }

    @Synchronized
    private fun modifyWriteValues(addrNoAndItsValue: Map<Int, Short>) {
        addrNoAndItsValue.forEach { (addrNo, value) ->
            val ba = ByteArray(2)
            val bb = ByteBuffer.wrap(ba)
            bb.putShort(value)
            val index = (addrNo - writeAddrList.first().addrNo!!) * 2
            writeValues()?.set(index, ba[0])
            writeValues()?.set(index + 1, ba[1])
        }
    }

    private fun syncStatusAndControl() {
        try {
            // checkLiftAvailable()
            if (!initialData()) return
            if (mc.readAndWriteMultiply) {
                // 让关门信号持续1秒钟
                holdCloseForSomeTime()
                syncLiftStatus("sync lift[${config.name}] timely")
                Thread.sleep(rate)  // 避免高频访问，每个 rate毫秒 进行一次读或者写操作。
                controlLift("control lift[${config.name}] timely")
//                delayForCancelCloseDoor.doSomethingWhenDelayed(config.name) {
//                    logDebug("delayed and cancel close")
//                    modifyWriteValues(mapOf(close.addrNo!! to 0.toShort()))
//                }
                liftInUse.doSomethingWhenDelayed(config.name) {
                    // 连续5秒未收到请求，就清空电梯指令
                    modifyWriteValues(mapOf(
                        dest.addrNo!! to zeroShort,
                        open.addrNo!! to zeroShort,
                        close.addrNo!! to zeroShort
                    ))
                    controlLift("clear all cmd for out of use", true)
                }
                return
            }
            syncStatus()
            // openDoorSafely("timely") // call或者go 的时候再尝试开门，call和go时，destFloor是不一样的，不用担心电梯在call时一直开门
            delayForClearDestFloor.doSomethingWhenDelayed(config.name) { clearDest("delayed and clear dest") }
            delayForCancelOpenDoor.doSomethingWhenDelayed(config.name) { cancelOpen("delayed and cancel open") }
            delayForCancelCloseDoor.doSomethingWhenDelayed(config.name) { cancelClose("delayed and cancel close") }
        } catch (e: Exception) {
            logError("sync status and control failed !", e)
        }
    }

    private fun holdCloseForSomeTime(): Boolean {
        if (null == setOccupiedOn) return false
        return if (toPositive(Duration.between(Instant.now(), setOccupiedOn).toSeconds()) <= mc.delayForCancelClose) {
            // 保持关门信号，清空开门信号
            modifyWriteValues(mapOf(dest.addrNo!! to (model.destFloor?.toShort() ?: zeroShort), open.addrNo!! to zeroShort))
            true
        } else {
            setOccupiedOn = null
            false
        }
    }

    override fun syncStatus() {
        try {
            getLiftInfo(LiftInfoField.LiftFloor)            // 电梯所在楼层
            getLiftInfo(LiftInfoField.LiftDoorStatus)       // 电梯门的状态
            getLiftInfo(LiftInfoField.MoveStatus)           // 电梯上下行状态
            getLiftInfo(LiftInfoField.LiftEmergency)        // 电梯是否处于紧急状态
        } catch (e: Exception) {
            logError("sync status failed: $e")
        }
    }

    private fun initialData(): Boolean {
        try {
            if (initialized) return true
            if (mc.readAndWriteMultiply) {
                controlLift("initialize")
                initialized = true
                return true
            }
            // srd 启动之后，清空开门信号、关门信号、楼层信息
            clearDest("initialize", true)
            cancelOpen("initialize", true)
            cancelClose("initialize", true)
            initialized = true
            return true
        } catch (e: Exception) {
            logError("initialData failed!", e)
            return false
        }
    }

    override fun checkOffline() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    private fun getLiftInfo(infoField: LiftInfoField) {
        try {
            val address = when (infoField) {
                LiftInfoField.LiftFloor -> mc.liftFloor
                LiftInfoField.LiftDoorStatus -> mc.liftDoorStatus
                LiftInfoField.MoveStatus -> mc.moveStatus
                LiftInfoField.LiftEmergency -> mc.emergency
            }
            val result = readSingleValue(address, infoField.toString())
                ?: throw BusinessError("Lift[${config.name}] get $infoField failed: result cannot be null !")
            when (infoField) {
                LiftInfoField.LiftFloor -> checkFloor(result.toString())
                LiftInfoField.LiftDoorStatus -> checkDoorStatus(result)
                LiftInfoField.MoveStatus -> checkMoveStatus(result)
                LiftInfoField.LiftEmergency -> checkEmergency(result)
            }
        } catch (e: Exception) {
            // 获取电梯状态异常时，出于安全性考虑，需要将对应的状态设置为ERROR .
            when (infoField) {
                LiftInfoField.LiftDoorStatus -> checkDoorStatus(-1)
                LiftInfoField.MoveStatus -> checkMoveStatus(-1)
                LiftInfoField.LiftEmergency -> checkEmergency(1)
                LiftInfoField.LiftFloor -> checkFloor("InvalidValue")
            }
            logError("get lift info failed: $e")
        }
    }

    private fun checkFloor(rawValue: String) {
        if (model.currentFloor != rawValue) liftChanged(model.copy(currentFloor = rawValue))
    }

    private fun checkDoorStatus(rawValue: Int) {
        val message = "Lift[${config.name}] get door status failed"
        val address = mc.liftDoorStatus
        val doorStatus = if (address.type!! in listOf("0x", "1x")) {
            if (rawValue !in listOf(0, 1)) {
                throw BusinessError("$message: result($rawValue) should be 0 or 1 !")
            }
            val inverse = address.inverseValueOf0xOr1x
            if ((inverse && rawValue == 0) || (!inverse && rawValue == 1)) LiftDoorStatus.OPEN
            else LiftDoorStatus.CLOSE
        } else when (rawValue) {
            -1 -> LiftDoorStatus.ERROR
            0 -> LiftDoorStatus.CLOSE
            1 -> LiftDoorStatus.OPENING
            2, 3 -> LiftDoorStatus.CLOSING  // 当数值为3时，无法确定是开门中或者是关门中
            4 -> LiftDoorStatus.OPEN
            else ->
                throw BusinessError("$message: unrecognized value=$rawValue .")
        }
        if (model.doorStatus != doorStatus) liftChanged(model.copy(doorStatus = doorStatus))
    }

    // 这个状态的取值至少得有3种：上行，下行，停止，因此不能用线圈量来表示。
    private fun checkMoveStatus(rawValue: Int) {
        val message = "Lift[${config.name}] get move status failed"
        val moveStatus = when (rawValue) {
            -1 -> LiftMoveStatus.Error
            0 -> LiftMoveStatus.Hold
            1 -> LiftMoveStatus.Up
            2 -> LiftMoveStatus.Down
            else ->
                throw BusinessError("$message: unrecognized value=$rawValue .")
        }
        if (model.moveStatus != moveStatus) liftChanged(model.copy(moveStatus = moveStatus))
    }

    private fun checkEmergency(rawValue: Int) {
        val checkedValue = when (rawValue) {
            0 -> false
            1 -> true
            else ->
                throw BusinessError("Lift[${config.name}] get emergency failed: value=$rawValue must be 0 or 1 .")
        }
        if (model.isEmergency != checkedValue) liftChanged(model.copy(isEmergency = checkedValue))
    }

    override fun dispose() {
        helper.disconnect()
        timer.cancel(true)
    }

    override fun call(floor: String, remark: String) {
        if (model.isOccupy) {
            logError("call but occupied")
            throw BusinessError("CallButOccupied ${liftConfig.name}")
        }

        logInfo("call to floor=$floor : $remark")
        // convert floor=0001 to floor=1
        model = model.copy(destFloor = floor.toInt().toString())

        if (mc.readAndWriteMultiply) {
            liftInUse.setToLimit()

            // 让关门信号持续一段时间
            if (setOccupiedOn != null) {
                logInfo("保持关门信号一段时间")
                return
            }

            val openHoldOn = if (model.currentFloor == model.destFloor) mc.openHoldOn.toShort() else zeroShort
            modifyWriteValues(mapOf(dest.addrNo!! to floor.toShort(), open.addrNo!! to openHoldOn, close.addrNo!! to zeroShort))
            return
        }

        if (calling) return // 防止连接超时的时候，高频的调用导致大量堆积的指令
        backgroundFixedExecutor.submit {
            calling = true
            openDoorSafely("call()")
            controlLift(dest, floor.toInt(), remark)
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

        if (mc.readAndWriteMultiply) {
            liftInUse.setToLimit()

            // 让关门信号持续一段时间
            if (setOccupiedOn != null) {
                logInfo("保持关门信号一段时间")
                return
            }

            val openHoldOn = if (model.currentFloor == model.destFloor) mc.openHoldOn.toShort() else zeroShort
            modifyWriteValues(mapOf(dest.addrNo!! to floor.toShort(), open.addrNo!! to openHoldOn, close.addrNo!! to zeroShort))
            return
        }

        if (going) return
        backgroundFixedExecutor.submit {
            going = true
            openDoorSafely("go()")
            controlLift(dest, floor.toInt(), remark)
            going = false
        }
    }

    override fun setOccupied(occupied: Boolean, remark: String) {
        val message = "setOccupied ${model.isOccupy}=>$occupied"
        logInfo("$message . $remark")

        model = model.copy(isOccupy = occupied)
        // 当电梯 occupy 和 unoccupy 时，都要关闭电梯门

        if (mc.readAndWriteMultiply) {
            liftInUse.setToLimit()

            // 为了让关门信号持续一段时间
            if (model.currentFloor == model.destFloor) setOccupiedOn = Instant.now()

            // 楼层编号可以不用清空
            modifyWriteValues(mapOf(open.addrNo!! to zeroShort, close.addrNo!! to mc.closeHoldOn.toShort()))
//            delayForCancelCloseDoor.setToLimit()
            return
        }

        backgroundFixedExecutor.submit {
            closeDoor(message)
        }
    }

    private fun openDoorSafely(remark: String) {
        try {
            // logger.debug("curr=${model.currentFloor}, dest=${model.destFloor}}")
            if (model.currentFloor == model.destFloor) openDoor(remark)
        } catch (e: Exception) {
            logError("open door safely failed !", e)
        }
    }

    // 机器人可以进入电梯时，发送开门请求；当电梯到达目标楼层时调用此方法。
    override fun openDoor(remark: String) {
        if (mc.ignoreOpenOrClose) return
        logInfo("open door : $remark")
        if (opening) {
            logInfo("open door while opening!")
            return
        }
        backgroundFixedExecutor.submit {
            opening = true
            controlLift(close, 0, remark, writeWhenDiff = true) // 释放关门按钮
            if (mc.resetBeforeCtrlDoor) {
                controlLift(open, 0, "reset open before set") // 清空开门信号500ms之后再从新写入值
                Thread.sleep(mc.delayForSet)
            }
            if (!closing) {
                controlLift(open, mc.openHoldOn, remark) // 按住开门按钮5秒
                Thread.sleep(1000) // 保证开门信号至少持续1000ms
                delayForCancelOpenDoor.setToLimit()
            }
            opening = false
        }
    }

    // 占用或者解除占用电梯时，都要关门
    override fun closeDoor(remark: String) {
        if (mc.ignoreOpenOrClose) return
        logInfo("close door : $remark")
        if (closing) {
            logInfo("close door while closing!")
            return
        }
        backgroundFixedExecutor.submit {
            // if (opening) return@submit 关门一定是在机器人到达SM点或者电梯前置点之后才会发送；当前条件可以忽略
            closing = true
            controlLift(open, 0, remark, writeWhenDiff = true) // 释放开门按钮
            if (mc.resetBeforeCtrlDoor) {
                controlLift(close, 0, "reset close before set") // 清空关门信号500ms之后再从新写入值
                Thread.sleep(mc.delayForSet)
            }
            controlLift(close, mc.closeHoldOn, remark) // 按住关门按钮5秒
            Thread.sleep(1000) // 保证开门信号至少持续1000ms
            delayForCancelCloseDoor.setToLimit()
            closing = false
        }
    }

    private fun clearDest(remark: String, throwError: Boolean = false) {
        controlLift(dest, 0, remark, throwError)
    }

    private fun cancelOpen(remark: String, throwError: Boolean = false) {
        controlLift(open, 0, remark, throwError)
    }

    private fun cancelClose(remark: String, throwError: Boolean = false) {
        controlLift(close, 0, remark, throwError)
    }

    private fun controlLift(address: Address, value: Int, remark: String, throwError: Boolean = false, writeWhenDiff: Boolean = false) {
        try {
            if (address.type == null || address.addrNo == null) {
                logError("control lift failed(ModbusTcp): $address")
                return
            }
            writeSingleValue(address, value, remark, writeWhenDiff)
        } catch (e: Exception) {
            if (throwError) throw e
            logError("control lift failed(ModbusTcp): $e")
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
            "0x" -> helper.write05SingleCoil(address.addrNo!!, value == 1, mc.unitId, remark)
            "4x" -> helper.write06SingleRegister(address.addrNo!!, value, mc.unitId, remark)
            else -> logError("control lift failed(ModbusTcp): unwritable addressType=${address.type}")
        }
    }

    private fun readSingleValue(address: Address, remark: String): Int? {
        try {
            if (address.type == null || address.addrNo == null) return null
            val result = when (address.type) {
                "0x" ->
                    helper.read01Coils(address.addrNo!!, 1, mc.unitId, remark)?.getByte(0)?.toInt()
                "1x" ->
                    helper.read02DiscreteInputs(address.addrNo!!, 1, mc.unitId, remark)?.getByte(0)?.toInt()
                "3x" ->
                    helper.read04InputRegisters(address.addrNo!!, 1, mc.unitId, remark)?.getShort(0)?.toInt()
                "4x" ->
                    helper.read03HoldingRegisters(address.addrNo!!, 1, mc.unitId, remark)?.getShort(0)?.toInt()
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

    private fun syncLiftStatus(remark: String) {
        try {
            val firstAddrNo = readAddrList.first().addrNo!!
            val result = helper.read03HoldingRegisters(firstAddrNo, readQty(), mc.unitId, remark)
            if (result == null) {
                logError("sync lift status multiply failed(ModbusTcp): read null!")
                return
            }
            (0 until readQty()).forEach { parseInfo(firstAddrNo + it, result.getShort(it * 2).toInt()) }

        } catch (e: Exception) {
            logError("sync lift status multiply failed(ModbusTcp): $e")
        }
    }

    private fun parseInfo(addrNo: Int, value: Int) {
        try {
            when (infosAndItsAddrNo[addrNo]) {
                LiftInfoField.LiftEmergency -> checkEmergency(value)
                LiftInfoField.MoveStatus -> checkMoveStatus(value)
                LiftInfoField.LiftDoorStatus -> checkDoorStatus(value)
                LiftInfoField.LiftFloor -> checkFloor(value.toString())
            }
        } catch (e: Exception) {
            // 获取电梯状态异常时，出于安全性考虑，需要将对应的状态设置为ERROR .
            when (infosAndItsAddrNo[addrNo]) {
                LiftInfoField.LiftEmergency -> checkEmergency(1)
                LiftInfoField.MoveStatus -> checkMoveStatus(-1)
                LiftInfoField.LiftDoorStatus -> checkDoorStatus(-1)
                LiftInfoField.LiftFloor -> checkFloor("InvalidValue")
            }
            logError("$e")
        }
    }

    private fun controlLift(remark: String, force: Boolean = false) {
        try {
//            if (writeValues().toString() != lastWriteValuesString)
            if (!liftInUse.terminated() || force)
                helper.write10MultipleRegisters(writeAddrList.first().addrNo!!, writeQty(), writeValues()!!, mc.unitId, remark)
        } catch (e: Exception) {
            logError("control lift multiply failed(ModbusTcp): $e")
        }
    }

}

data class ModbusConfig(
    var unitId: Int = 0,                            // 从站ID
    var readAndWriteMultiply: Boolean = false,      // true: 批量读写寄存器地址，一定程度上避免高频访问PLC
    var ignoreOpenOrClose: Boolean = false,         // true: 不发送开门和关门指令
    var resetBeforeCtrlDoor: Boolean = false,       // 写入开门/关门值之前，先将当前值置为0，500ms之后再写入值 （金峰）
    var delayForSet: Long = 500,                    // 将开关门信号设置为0之后500ms再写入开门信号。(金峰)
    var openHoldOn: Int = 5,                        // 期望的开门的持续时间
    var closeHoldOn: Int = 5,                       // 期望的关门的持续时间
    var delayForCancelOpen: Int = 5,                // 一段时间之后清空开门信号
    var delayForCancelClose: Int = 5,               // 一段时间之后清空关门信号
    // reads
    var liftFloor: Address = Address(),             // 电梯所在楼层的编号
    var moveStatus: Address = Address(),            // 电梯上下行状态
    var liftDoorStatus: Address = Address(),        // 电梯门的状态
    var emergency: Address = Address(),             // 电梯是否处于紧急状态
    // writes
    var openDoor: Address = Address(),              // 开门地址
    var closeDoor: Address = Address(),             // 关门地址
    var destFloor: Address = Address()              // 电梯的目标楼层
)

enum class LiftInfoField {
    LiftFloor, LiftDoorStatus, LiftEmergency, MoveStatus
}
