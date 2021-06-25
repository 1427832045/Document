package com.seer.srd.device.lift

import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import com.seer.srd.device.charger.toPositive
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.scheduler.GlobalTimer
import com.seer.srd.scheduler.backgroundFixedExecutor
import com.seer.srd.util.mapper
import org.litote.kmongo.json
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class LiftInfoFieldSiemensCd {
    Call, Go, IdleInside, IdleOutside, EnterPermitted, LeavePermitted, LiftDoorStatus, LiftFloor
}

class LiftManagerModbusTcpCustomV2(val config: LiftConfig) : AbstractLiftManager(config) {
    /**
     * diff:
     *      1、当前版本采用批量读写的方式进行交互
     *      2、对于之前只会写入一次的信号，如果写入失败了，就会重复写入，直到写入写入成功。
     *          - AGV在电梯内就位
     *          - AGV已经离开电梯
     *
     * SRD 重启之后，会重置所有写入电梯的指令。
     *
     * 一段时间之后，会分别清空相关的指令，时间可以配置。
     *      - AGV 当前楼层
     *      - AGV 目标楼层
     *      - AGV 在电梯内就位（到达 SM 点）
     *      - AGV 已经离开电梯（到达电梯前置点）
     */

    private var initialized = false

    private var helper: ModbusTcpMasterHelper? = null

    private val mc =
        if (config.modbusConfig == null)
            throw BusinessError("Lift[${config.name}] modbusConfig cannot be null when its mode=${config.mode}!")
        else mapper.readValue(config.modbusConfig!!.json, ModbusConfigCustom::class.java)

    private val rate = CONFIG.liftStatusPollingPeriod

    private val writeAddrList = mutableListOf(mc.call, mc.go!!, mc.idleInside!!, mc.idleOutside!!)

    private var writeQty = 0

    @Volatile
    private var writeValues: ByteArray? = null

    private val readAddrList = mutableListOf(
        mc.call, mc.go!!, mc.idleInside!!, mc.idleOutside!!,
        mc.enterPermission!!, mc.leavePermission!!, mc.liftDoorStatus!!, mc.liftFloor!!
    )

    private var readQty = 0

    private val infosAndItsAddrNo: MutableMap<Int, LiftInfoFieldSiemensCd> = mutableMapOf()

    @Volatile
    private var callFromSlave = 0

    @Volatile
    private var goFromSlave = 0

    @Volatile
    private var idleInsideFromSlave = false

    @Volatile
    private var idleOutsideFromSlave = false

    @Volatile
    var enterPermitted = false

    @Volatile
    var leavePermitted = false

    @Volatile
    var lastLiftInfoStr = ""

    private fun updateAndPrintLiftInfoIfChanged() {
        val liftInfosStr = "call=$callFromSlave, go=$goFromSlave, idleInside=$idleInsideFromSlave, " +
            "idleOutside=$idleOutsideFromSlave, enterPermitted=$enterPermitted, leavePermitted=$leavePermitted, " +
            "doorStatus=${model.doorStatus}, floor=${model.currentFloor}"
        if (liftInfosStr == lastLiftInfoStr) return
        lastLiftInfoStr = liftInfosStr
        logInfo("info changed to $lastLiftInfoStr")
    }

    private val delayForClearCall = DelayElapse(mc.delayForClearCall)

    private val delayForClearGo = DelayElapse(mc.delayForClearGo)

    private val delayForClearIdleInside = DelayElapse(mc.delayForClearIdleInside)

    private val delayForClearIdleOutside = DelayElapse(mc.delayForClearIdleOutside)

    private val timer: ScheduledFuture<*>

    init {
        logInfo("$mc")

        sortAddrListByAddrNo(writeAddrList, true)
        logInfo("writeAddress=${writeAddrList}")
        sortAddrListByAddrNo(readAddrList, false)
        logInfo("readAddress=${readAddrList}")

        infosAndItsAddrNo[mc.call.addrNo!!] = LiftInfoFieldSiemensCd.Call
        infosAndItsAddrNo[mc.go!!.addrNo!!] = LiftInfoFieldSiemensCd.Go
        infosAndItsAddrNo[mc.idleInside!!.addrNo!!] = LiftInfoFieldSiemensCd.IdleInside
        infosAndItsAddrNo[mc.idleOutside!!.addrNo!!] = LiftInfoFieldSiemensCd.IdleOutside
        infosAndItsAddrNo[mc.enterPermission!!.addrNo!!] = LiftInfoFieldSiemensCd.EnterPermitted
        infosAndItsAddrNo[mc.leavePermission!!.addrNo!!] = LiftInfoFieldSiemensCd.LeavePermitted
        infosAndItsAddrNo[mc.liftDoorStatus!!.addrNo!!] = LiftInfoFieldSiemensCd.LiftDoorStatus
        infosAndItsAddrNo[mc.liftFloor!!.addrNo!!] = LiftInfoFieldSiemensCd.LiftFloor

        // todo: 完成这两个状态的可靠性、实时性。
        model = model.copy(commandTimeout = false, online = true)

        getHelper()

        clearAllCommands()

        timer = GlobalTimer.executor.scheduleAtFixedRate(
            this::syncStatusAndControl,
            2000,
            rate,
            TimeUnit.MILLISECONDS
        )
        initialized = true
    }

    private fun sortAddrListByAddrNo(addrList: MutableList<Address>, writeable: Boolean) {
        addrList.sortBy {
            if (it.type == null || it.addrNo == null) throw BusinessError("配置不完善$it")
            if (it.addrNo!! < 0) throw BusinessError("地址必须是不小于0的整数，$it")
            if (it.type!! != "4x") throw BusinessError("地址类型必须是4x, $it")
            it.addrNo
        }
        if (writeable) {
            writeQty = addrList.last().addrNo!! - addrList.first().addrNo!! + 1
            // 写入一个寄存器的数据是short(2bytes)的，writeValue的长度应该是被操作地址数量的2倍
            writeValues = ByteArray(writeQty * 2)
        } else {
            readQty = addrList.last().addrNo!! - addrList.first().addrNo!! + 1
        }
    }

    @Synchronized
    private fun modifyWriteValues(addrNoAndItsValue: Map<Int, Short>) {
        addrNoAndItsValue.forEach { (addrNo, value) ->
            try {
                val ba = ByteArray(2)
                val bb = ByteBuffer.wrap(ba)
                bb.putShort(value)
                val index = (addrNo - writeAddrList.first().addrNo!!) * 2
                writeValues!![index] = ba[0]
                writeValues!![index + 1] = ba[1]
            } catch (e: Exception) {
                logError("Modify write values failed: ", e)
            }
        }
    }

    private fun getHelper(): ModbusTcpMasterHelper {
        if (helper == null) {
            helper = ModbusTcpMasterHelper(config.host, config.port)
            if (initialized) throw BusinessError("Lift[${config.name}] initialize connection from null ...")
        }
        return helper!!
    }

    private fun clearAllCommands() {
        backgroundFixedExecutor.submit {
            while (!initialized) {
                try {
                    modifyWriteValues(mapOf(
                        mc.call.addrNo!! to zeroShort,
                        mc.go!!.addrNo!! to zeroShort,
                        mc.idleInside!!.addrNo!! to zeroShort,
                        mc.idleOutside!!.addrNo!! to zeroShort
                    ))
                    controlLift(true)
                    initialized = true
                } catch (e: Exception) {
                    logError("Clear all commands failed when initializing: ", e)
                    Thread.sleep(200)
                }
            }
        }
    }

    private fun syncStatusAndControl() {
        if (!initialized) return
        syncStatus()
        Thread.sleep(rate)
        delayForClearCall.doSomethingWhenElapsed(config.name) {
            modifyWriteValues(mapOf(mc.call.addrNo!! to zeroShort))
        }
        delayForClearGo.doSomethingWhenElapsed(config.name) {
            modifyWriteValues(mapOf(mc.go!!.addrNo!! to zeroShort))
        }
        delayForClearIdleInside.doSomethingWhenElapsed(config.name) {
            modifyWriteValues(mapOf(mc.idleInside!!.addrNo!! to zeroShort))
        }
        delayForClearIdleOutside.doSomethingWhenElapsed(config.name) {
            modifyWriteValues(mapOf(mc.idleOutside!!.addrNo!! to zeroShort))
        }
        controlLift()
    }

    override fun syncStatus() {
        try {
            // 定制协议中规定，电梯状态对应的地址和控制电梯的地址都是4x，且几乎都是连续的，可以一次性全部读取
            val firstAddrNo = readAddrList.first().addrNo!!
            val result = getHelper().read03HoldingRegisters(firstAddrNo, readQty, mc.unitId, "Lift[${config.name}] sync status")
            if (result == null) {
                logError("Sync lift status multiply failed(ModbusTcp): read null!")
                return
            }
            (0 until readQty).forEach { parseInfo(firstAddrNo + it, result.getShort(it * 2).toInt()) }
            updateAndPrintLiftInfoIfChanged()
        } catch (e: Exception) {
            logError("Sync lift status failed(ModbusTcp): ", e)
        }
    }

    private fun parseInfo(addrNo: Int, value: Int) {
        when (infosAndItsAddrNo[addrNo]) {
            LiftInfoFieldSiemensCd.Call -> if (value != callFromSlave) callFromSlave = value
            LiftInfoFieldSiemensCd.Go -> if (value != goFromSlave) goFromSlave = value
            LiftInfoFieldSiemensCd.IdleInside -> checkBooleanValue(value, LiftInfoFieldSiemensCd.IdleInside)
            LiftInfoFieldSiemensCd.IdleOutside -> checkBooleanValue(value, LiftInfoFieldSiemensCd.IdleOutside)
            LiftInfoFieldSiemensCd.EnterPermitted -> checkBooleanValue(value, LiftInfoFieldSiemensCd.EnterPermitted)
            LiftInfoFieldSiemensCd.LeavePermitted -> checkBooleanValue(value, LiftInfoFieldSiemensCd.LeavePermitted)
            LiftInfoFieldSiemensCd.LiftDoorStatus -> checkBooleanValue(value, LiftInfoFieldSiemensCd.LiftDoorStatus)
            LiftInfoFieldSiemensCd.LiftFloor ->
                if (model.currentFloor != value.toString()) liftChanged(model.copy(currentFloor = value.toString()))
        }
    }

    private fun checkBooleanValue(rawValue: Int, field: LiftInfoFieldSiemensCd) {
        val checkedValue = when (rawValue) {
            0 -> false
            1 -> true
            else -> {
                logError("Lift[${config.name}] get $field failed: value=$rawValue must be 0 or 1 .")
                false
            }
        }
        when (field) {
            LiftInfoFieldSiemensCd.IdleInside -> if (checkedValue != idleInsideFromSlave) idleInsideFromSlave = checkedValue
            LiftInfoFieldSiemensCd.IdleOutside -> if (checkedValue != idleOutsideFromSlave) idleOutsideFromSlave = checkedValue
            LiftInfoFieldSiemensCd.EnterPermitted -> if (checkedValue != enterPermitted) enterPermitted = checkedValue
            LiftInfoFieldSiemensCd.LeavePermitted -> if (checkedValue != leavePermitted) leavePermitted = checkedValue
            LiftInfoFieldSiemensCd.LiftDoorStatus -> {
                val doorStatus = if (checkedValue) LiftDoorStatus.OPEN else LiftDoorStatus.CLOSE
                if (model.doorStatus != doorStatus) liftChanged(model.copy(doorStatus = doorStatus))
            }
            else -> {
                // do nothing
            }
        }
    }

    private fun controlLift(throwError: Boolean = false) {
        try {
            val firstAddrNo = writeAddrList.first().addrNo!!
            getHelper().write10MultipleRegisters(firstAddrNo, writeQty, writeValues!!, mc.unitId, "Lift[${config.name}] control")
        } catch (e: Exception) {
            logError("Control lift failed(ModbusTcp): ", e)
            if (throwError) throw e
        }
    }

    override fun dispose() {
        helper?.disconnect()
        timer.cancel(true)
    }

    override fun call(floor: String, remark: String) {
        controlLiftMove(true, floor, remark)
    }

    override fun go(floor: String, remark: String) {
        controlLiftMove(false, floor, remark)
    }

    @Synchronized
    private fun controlLiftMove(call: Boolean, floor: String, remark: String) {
        if (!call && !model.isOccupy) {
            logError("go but not occupied")
            throw BusinessError("GoButNotOccupied ${liftConfig.name}")
        }

        if (call && model.isOccupy) {
            logError("call but occupied")
            throw BusinessError("CallButOccupied ${liftConfig.name}")
        }

        logInfo("${if (call) "call" else "go"} to floor=$floor : $remark")
        model = model.copy(destFloor = floor.toInt().toString())

        if (call) {
            delayForClearCall.update()
            modifyWriteValues(mapOf(mc.call.addrNo!! to floor.toShort(), mc.go!!.addrNo!! to zeroShort))
        } else {
            delayForClearGo.update()
            modifyWriteValues(mapOf(mc.call.addrNo!! to zeroShort, mc.go!!.addrNo!! to floor.toShort()))
        }
    }

    override fun setOccupied(occupied: Boolean, remark: String) {
        val message = "setOccupied ${model.isOccupy}=>$occupied"
        logInfo("$message . $remark")

        model = model.copy(isOccupy = occupied)
        tellPositionOfAgv()
    }

    private fun tellPositionOfAgv() {
        if (model.isOccupy) {
            // 当电梯 occupy 时，需要告诉电梯，AGV在电梯内就位
            delayForClearIdleInside.update()
            modifyWriteValues(mapOf(mc.idleInside!!.addrNo!! to 1.toShort(), mc.idleOutside!!.addrNo!! to zeroShort))
        } else {
            // 当电梯 unoccupy 时，需要告诉电梯，AGV已经离开电梯
            delayForClearIdleOutside.update()
            modifyWriteValues(mapOf(mc.idleInside!!.addrNo!! to zeroShort, mc.idleOutside!!.addrNo!! to 1.toShort()))
        }
    }

    override fun openDoor(remark: String) {
        // 此协议规定，电梯到达目标楼层之后，会自动开门，直到SRD告诉电梯“AGV在电梯内就位”或者“AGV已经离开电梯”，电梯才会关门。
        // do nothing
    }

    override fun closeDoor(remark: String) {
        // 相当于是告诉电梯“AGV在电梯内就位”或者“AGV已经离开电梯”。
        // 此功能已经在setOccupied()中实现了，不用重复实现。
        // do nothing
    }

    override fun checkOffline() {
        // do nothing
    }
}

/**
 * 记录初始时间，并在一段时间之后，调用指定的方法。
 * 相比于 Delay 采用定时器计数的方法, DelayElapse 直接采用计算时间差的方法，一定程度上，可以优化多次循环累计的时间差的问题。
 */
class DelayElapse(
    private val limitSeconds: Int
) {
    private val logger = LoggerFactory.getLogger("c.s.s.d.lift.LiftManagerModbusTcpCustomV2")

    @Volatile
    private var updatedOn: Instant? = null

    fun update() {
        updatedOn = Instant.now()
    }

    fun doSomethingWhenElapsed(liftName: String, action: (() -> Unit)) {
        try {
            if (updatedOn == null) return
            if (toPositive(Duration.between(updatedOn, Instant.now()).toSeconds()) > limitSeconds) {
                action()
                updatedOn = null
            }
        } catch (e: Exception) {
            logger.error("Lift[$liftName] doSomethingWhenElapsed failed!", e)
        }
    }
}
