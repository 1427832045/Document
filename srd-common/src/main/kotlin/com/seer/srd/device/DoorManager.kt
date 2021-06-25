package com.seer.srd.device

import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import com.seer.srd.db.MongoDBManager
import com.seer.srd.device.charger.toPositive
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.scheduler.GlobalTimer
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class DoorConfig(
    var host: String = "127.0.0.1",
    var port: Int = 0,
    var unitId: Int = 0,                // PLC 的 slaveId，不同 PLC 设置 的 slaveId 不一定都相同， 台达的PLC用默认值 0 即可
    var recordOpenOpt: Boolean = false, // 记录每一波开门请求的第一条请求
    // 适配施耐德PLC：施耐德PLC 不支持 功能码 “05”；true: 用功能码 “15” 写入数据；false: 用功能码 “05” 写入数据。
    var writeInFunc0F: Boolean = false,
    var openAddr: Int = 0,
    var closeAddr: Int = 0,
    var stopAddr: Int? = null,
    var feedback: Boolean = false,
    var openStatusAddr: Int = 0,
    var errorAddr: Int? = null,         // 获取自动门是否处于报警状态的地址（成都有英特尔的需求）
    var delayForOpened: Int = 0,        // 一段时间后，设置自动门状态为OPEN，当 feedback = false 时有效
    var delayForCancelOpen: Long = 0L,  // 一段时间后取消写入开门信号，单位为 ms    0: 不清空
    var delayForCancelClose: Long = 0L, // 一段时间后取消写入关门信号，单位为 ms    0: 不清空
    var delayForByPass: Long = 10L       // 手动将自动门设置为开门状态或者关门状态一段时间之后，自动门就会返回实际的状态，或者计算之后的状态
)

data class DoorStatusDO(
    val name: String,
    val status: String
)

data class DoorStatusRecord(
    @BsonId val id: ObjectId = ObjectId(),
    val name: String = "",
    val item: String = "",
    val value: String = "",
    val timeStamp: Instant = Instant.now()
)

class DoorManager(val name: String, private val config: DoorConfig) {

    private val logger = LoggerFactory.getLogger(DoorManager::class.java)

    private fun logInfo(message: String) {
        logger.info("Door[$name]: $message")
    }

    private fun logDebug(message: String) {
        logger.debug("Door[$name]: $message")
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (throwable == null) logger.error("Door[$name]: $message")
        else logger.error("Door[$name]: $message", throwable)
    }

    private val writeInFunc0F = config.writeInFunc0F

    @Volatile
    var status: String = "CLOSE"
        private set

    /*
        -1: 自动获取自动门的状态
        0：手动将自动门的状态设置为 CLOSE
        1：手动将自动门的状态设置为 OPEN
     */
    @Volatile
    var optMode: Int = -1
        private set

    @Volatile
    var byPassedOn: Instant? = null

    @Volatile
    var prevStatus: String = "CLOSE"
        private set

    @Volatile
    var count: Int = config.delayForOpened

    @Volatile
    var error: Boolean = false

    var lastOpenTime: Instant = Instant.now()

    @Volatile
    private var syncErrorCount = 0

    private val helper = ModbusTcpMasterHelper(config.host, config.port)

    private val timer: ScheduledFuture<*>

    init {
        timer = GlobalTimer.executor.scheduleAtFixedRate(
            this::syncStatus,
            2000,
            CONFIG.doorsStatusPollingPeriod,
            TimeUnit.MILLISECONDS
        )
    }

    fun dispose() {
        timer.cancel(true)
    }

    /** 开门 */
    fun open(remark: String) {
        logInfo("Open door: $name for $remark")
        recordOpenOperation()

        val unitId = config.unitId

        if (config.openAddr == config.closeAddr) {
            // 开关门共用一个地址
            if (writeInFunc0F) helper.write0FMultipleCoils(config.openAddr, 1, byteArrayOf(1), unitId, remark)
            else helper.write05SingleCoil(config.openAddr, true, unitId, remark)
        } else {
            // 开关门各用一个地址
            if (writeInFunc0F) {
                helper.write0FMultipleCoils(config.openAddr, 1, byteArrayOf(1), unitId, remark)
                helper.write0FMultipleCoils(config.closeAddr, 1, byteArrayOf(0), unitId, remark)
            } else {
                helper.write05SingleCoil(config.openAddr, true, unitId, remark)
                helper.write05SingleCoil(config.closeAddr, false, unitId, remark)
                if (config.stopAddr != null) {
                    helper.write05SingleCoil(config.stopAddr!!, false, unitId, remark)
                }
            }
        }

        // 设置门的状态为期望状态的前一个状态
        if (!config.feedback && !listOf("OPENING", "OPEN").contains(status)) {
            logDebug("feedback is false, set status from $status to OPENING.")
            status = "OPENING"
        }

        // 清空开门信号
        val cancelOpen = config.delayForCancelOpen
        if (cancelOpen > 0) {
            Thread.sleep(cancelOpen)
            if (writeInFunc0F) helper.write0FMultipleCoils(config.openAddr, 1, byteArrayOf(0), unitId, remark)
            else helper.write05SingleCoil(config.openAddr, false, unitId, remark)

        }
    }

    /** 关门 */
    fun close(remark: String) {
        logInfo("Close door: $name for $remark")
        count = config.delayForOpened
        val unitId = config.unitId
        if (config.openAddr == config.closeAddr) {
            // 开关门共用一个地址
            if (writeInFunc0F) helper.write0FMultipleCoils(config.openAddr, 1, byteArrayOf(1), unitId, remark)
            else helper.write05SingleCoil(config.openAddr, false, unitId, remark)

        } else {
            // 开关门各用一个地址
            if (writeInFunc0F) {
                helper.write0FMultipleCoils(config.openAddr, 1, byteArrayOf(0), unitId, remark)
                helper.write0FMultipleCoils(config.closeAddr, 1, byteArrayOf(1), unitId, remark)

            } else {
                helper.write05SingleCoil(config.openAddr, false, unitId, remark)
                helper.write05SingleCoil(config.closeAddr, true, unitId, remark)
                if (config.stopAddr != null) {
                    helper.write05SingleCoil(config.stopAddr!!, false, unitId, remark)
                }
            }
        }

        // 设置门的状态为期望状态的前一个状态
        if (!config.feedback && !listOf("CLOSING", "CLOSE").contains(status)) {
            logDebug("feedback is false, set status from $status to CLOSING.")
            status = "CLOSING"
        }

        // 清空关门信号
        val cancelClose = config.delayForCancelClose
        if (cancelClose > 0) {
            Thread.sleep(cancelClose)
            if (writeInFunc0F) helper.write0FMultipleCoils(config.closeAddr, 1, byteArrayOf(0), unitId, remark)
            else helper.write05SingleCoil(config.closeAddr, false, unitId, remark)
        }
    }

    /** 手动设置自动门的状态 */
    fun byPass(mode: Int, remark: String) {
        if (mode !in listOf(-1, 0, 1))
            throw BusinessError("undefined value:$mode of manualOpt !!!")

        val from = parseManualOpt(optMode)
        val to = parseManualOpt(mode)
        when (mode) {
            -1 -> {
                byPassedOn = null
                logInfo("update status of door: $name automatically for $remark.")
            }
            0, 1 -> {
                if (byPassedOn == null) byPassedOn = Instant.now()
                logInfo("set status of door: $name from $from to $to[manual] for $remark.")
            }
        }

        optMode = mode
    }

    private fun parseManualOpt(manualOpt: Int): String {
        return when (manualOpt) {
            0 -> "CLOSE"
            1 -> "OPEN"
            else -> "$status[auto]"
        }
    }

    @Synchronized
    fun syncStatus() {
        try {
            syncError()

            if (syncDoorStatusManually()) return

            if (!config.feedback) {
                // 将暂态设置为终态
                if (status == "OPENING") {
                    if (count > 0) {
                        count--
                    } else {
                        status = "OPEN"
                        count = config.delayForOpened
                        logDebug("Sync door $name status, last status is $status")
                    }
                } else if (status == "CLOSING") {
                    status = "CLOSE"
                    logDebug("Sync door $name status, last status is $status")
                }
            } else {
                // 此方法不抛异常
                try {
                    val openStatus = helper.read02DiscreteInputs(config.openStatusAddr, 1, config.unitId, "SyncOpenStatus")
                        ?.getByte(0)?.toInt()
                        ?: throw BusinessError("value door status cannot be null .")
                    status = if ((openStatus % 2) != 0) "OPEN" else "CLOSE"
                    if (status != prevStatus) {
                        prevStatus = status
                        logDebug("Sync door $name status, modbus status $openStatus")
                    }
                    syncErrorCount = 0
                } catch (e: Exception) {
                    syncErrorCount++
                    if (syncErrorCount <= 3) logError("sync door $name status failed! ", e)
                    else logError("sync door $name status failed! $e ")
                }
            }
        } catch (e: Exception) {
            logError("Door[$name] sync status failed", e)
        }
    }

    private fun syncDoorStatusManually(): Boolean {
        if (optMode > -1) {
            when (optMode) {
                0 -> {
                    if ("CLOSE" != status) {
                        status = "CLOSE"
                        logInfo("Sync door $name status manually, last status is $status")
                    }
                }
                1 -> {
                    if ("OPEN" != status) {
                        status = "OPEN"
                        logInfo("Sync door $name status manually, last status is $status")
                    }
                }
            }
            if (byPassedOn != null ) {
                val duration = toPositive(Duration.between(byPassedOn, Instant.now()).toSeconds())
                if (duration > config.delayForByPass) {
                    byPassedOn = null
                    logInfo("update status automatically for timedOut[$duration/${config.delayForByPass}].")
                    optMode = -1
                }
            }
            return true
        } else return false
    }

    // 成都英特尔的需求
    private fun syncError() {
        if (config.errorAddr != null) {
            val errorStatus = helper.read02DiscreteInputs(config.errorAddr!!, 1, config.unitId, "SyncErrorStatus")
                ?.getByte(0)?.toInt()
            val newValue = errorStatus == 1
            if (newValue != error) {
                error = newValue
                logDebug("Sync door $name error-status, modbus status $errorStatus:${error}.")
                // 记录信息到数据库中
                MongoDBManager.collection<DoorStatusRecord>()
                    .insertOne(DoorStatusRecord(name = name, item = "error", value = error.toString()))
            }
        }
    }

    // 成都英特尔的需求
    private fun recordOpenOperation() {
        if (!config.recordOpenOpt) return
        val now = Instant.now()
        val duration = toPositive(Duration.between(now, lastOpenTime).toSeconds())
        lastOpenTime = now
        if (duration > 3) { // 3秒之后才收到下一波开门请求, 并记录
            MongoDBManager.collection<DoorStatusRecord>()
                .insertOne(DoorStatusRecord(name = name, item = "open", value = "true"))
        }
    }

    fun getDoorStatusDO(): DoorStatusDO {
        return DoorStatusDO(name, status)
    }

}