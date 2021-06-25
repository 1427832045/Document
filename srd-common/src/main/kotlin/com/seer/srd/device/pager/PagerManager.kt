package com.seer.srd.device.pager

import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import com.seer.srd.db.MongoDBManager
import com.seer.srd.device.charger.toPositive
import com.seer.srd.device.lift.Address
import com.seer.srd.device.lift.checkAddressNum
import com.seer.srd.device.pager.PagerPersistenceService.getPersistedPagerByName
import com.seer.srd.device.pager.PagerPersistenceService.updatePersistedPager
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.robottask.*
import com.seer.srd.robottask.RobotTaskService.saveNewRobotTask
import com.seer.srd.route.service.VehicleService
import com.seer.srd.scheduler.GlobalTimer
import com.seer.srd.vehicle.Vehicle.State
import org.bson.types.ObjectId
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.opentcs.data.order.TransportOrder
import org.opentcs.data.order.TransportOrderState
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class PagerConfig(
    var name: String = "",
    var host: String = "127.0.0.1",
    var port: Int = 502,
    var slaveId: Int = 0,

    // 当前呼叫器绑定的柔性任务
    var taskDefName: String = "",

    // 连续两条有效指令的最短时间间隔，默认值为1000，单位为ms
    var commandCD: Long = 1000,

    // 指令信号，只读线圈量（3x）
    var command: Address = Address(),

    // 信号灯状态对应的地址，可读写寄存器（4x）
    var signal: Address = Address()
)

data class PagerModel(
    val config: PagerConfig,
    val taskId: String,
    val vehicleName: String,
    val vehicleState: String,
    val signalType: Int,
    val lastCreateOn: String,
    val lastAbortOn: String,
    val lastCreateError: String,
    val lastAbortError: String
)

object CommandType {
    const val None = 0                  // 无操作
    const val Create = 1                // 创建任务
    const val Abort = 2                 // 撤销任务
}

object SignalType {
    const val TaskCreated = 1           // 任务被创建
    const val TaskBeingProcessed = 2    // 任务正在被执行
    const val TaskSuccess = 3           // 任务执行完成
    const val VehicleError = 4          // 执行任务时，AGV 报错
    const val TaskAbortedByPager = 5    // 任务执行之前，被呼叫器终止
    const val TaskAbortedBySrd = 6      // 任务被 SRD 终止
    const val TaskFailed = 7            // 任务失败
}

val df: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

class ErrorMessage() {
    var message = ""
    var timestamp: String = LocalDateTime.now(ZoneId.systemDefault()).format(df)

    fun update(new: String) {
        if (new != message) message = new
        timestamp = LocalDateTime.now(ZoneId.systemDefault()).format(df)
    }

    fun clear() {
        update("")
    }

    override fun toString(): String {
        return if (message.isNotBlank()) "$timestamp $message" else ""
    }
}

const val FIELD_CANNOT_ABORT_BY_PAGER = "cannotAbortByPager"

class PagerManager(val config: PagerConfig) {
    private val tag = "Pager[${config.name}]"

    private val readAndWriteRemark = "Pager[${config.name}][${config.host}:${config.port}]"

    private val logger = LoggerFactory.getLogger(PagerManager::class.java)

    private fun logInfo(message: String) {
        logger.info("$tag: $message")
    }

    private fun logError(message: String) {
        logger.error("$tag: $message")
    }

    private val taskDef = getRobotTaskDef(config.taskDefName)
        ?: throw BusinessError("$tag: Cannot find taskDef=${config.taskDefName}, please check file srd-config.yaml")

    private val c = MongoDBManager.collection<RobotTask>()

    private var initialized = false

    private var helper: ModbusTcpMasterHelper? = null
    private fun getHelper(): ModbusTcpMasterHelper {
        if (helper == null) {
            helper = ModbusTcpMasterHelper(config.host, config.port)
            val message = "Build modbusTcp master from null ..."
            if (initialized) throw BusinessError("$tag: $message")
            else logError(message)
        }
        return helper!!
    }

    // 每200ms进行一次读或者写
    private val period = CONFIG.pagersStatusPollingPeriod

    private val command =
        if (config.command.type in listOf("3x", "4x")) checkAddressNum(config.command)
        else throw BusinessError("$tag: Address type of command must be 3x or 4x!")

    @Volatile
    private var createOn = Instant.now()

    @Volatile
    private var abortOn = Instant.now()

    private fun commandEffect(on: Instant): Boolean {
        return toPositive(Duration.between(on, Instant.now()).toMillis()) > config.commandCD
    }

    private val createMessage = ErrorMessage()

    private val abortMessage = ErrorMessage()

    private val signal =
        if (config.signal.type == "4x") checkAddressNum(config.signal)
        else throw BusinessError("$tag: Address type of signal must be 4x!")

    @Volatile
    private var vehicleName: String = ""
    @Volatile
    private var vehicleState: State = State.UNKNOWN

    @Volatile
    private var taskId: String = ""

    fun getTaskId() = taskId

    @Volatile
    private var signalValue = SignalType.TaskSuccess

    private fun updateSignalValue(signalType: Int, newTaskId: String, newVehicleName: String) {
        signalValue = when (signalType) {
            SignalType.TaskCreated,
            SignalType.TaskBeingProcessed,
            SignalType.VehicleError,
            SignalType.TaskSuccess,
            SignalType.TaskAbortedByPager,
            SignalType.TaskAbortedBySrd,
            SignalType.TaskFailed ->
                signalType
            else -> throw BusinessError("Undefined signal_type=$signalType !")
        }
        taskId = newTaskId
        vehicleName = newVehicleName
        updatePersistedPager(PersistedPager(ObjectId(), config.name, taskId, signalValue))
    }

    private fun markTaskCreated(newTaskId: String) {
        updateSignalValue(SignalType.TaskCreated, newTaskId, "")
    }

    fun tryToMarkVehicleError() {
        if (SignalType.TaskBeingProcessed == signalValue && taskId.isNotBlank() && vehicleName.isNotBlank()) {
            val vehicle = VehicleService.getVehicleOrNull(vehicleName)
            if (vehicle == null) {
                logError("Try to mark vehicle error failed: Cannot find vehicle=$vehicleName !")
            } else if (vehicle.state in listOf(State.ERROR, State.UNAVAILABLE, State.UNKNOWN)) {
                updateSignalValue(SignalType.VehicleError, taskId, vehicleName)
                vehicleState = vehicle.state
            }
        }
    }

    private fun markTaskFinished(signalType: Int) {
        updateSignalValue(signalType, "", "")
    }

    private val timer: ScheduledFuture<*>

    init {
        initSignalValue()

        getHelper()

        timer = GlobalTimer.executor.scheduleAtFixedRate(
            this::readCommandsAndControlLights,
            2000,
            period,
            TimeUnit.MILLISECONDS
        )

        initialized = true
    }

    private fun readCommandsAndControlLights() {
        readCommandsAndExecute()
        Thread.sleep(period)
        controlLights()
    }

    private fun readCommandsAndExecute() {
        try {
            // 批量读 ModbusTcp 的线圈量
            val addrNo = command.addrNo!!
            val result = when (val type = command.type) {
                "3x" -> getHelper().read04InputRegisters(addrNo, 1, config.slaveId, readAndWriteRemark)
                "4x" -> getHelper().read03HoldingRegisters(addrNo, 1, config.slaveId, readAndWriteRemark)
                else -> throw BusinessError("Unsupported address type=$type!")
            } ?: throw BusinessError("Read null from slave[${config.host}:${config.port}]!")

            when (val value = result.getShort(0).toInt()) {
                CommandType.None -> return
                CommandType.Create, CommandType.Abort -> execCommand(value)
                else -> logError("Undefined command value=$value!")
            }
        } catch (e: Exception) {
            logger.error("$tag: Read commands failed: ", e)
        }
    }

    private fun execCommand(command: Int) {
        when (command) {
            CommandType.Create -> createTask()
            CommandType.Abort -> abortTask()
            else -> logError("Exec command failed: Undefined command=$command!")
        }
    }

    private fun createTask() {
        try {
            // 防止PLC的数据长时间未清空，导致指令被频繁触发
            if (!commandEffect(createOn)) return
            val task = findUnfinishedTask()
            if (task != null) throw BusinessError("Existed unfinished task=${task.id}!")
            val newTask = buildTaskInstanceByDef(taskDef)
            newTask.persistedVariables[FIELD_PAGER] = config.name
            saveNewRobotTask(newTask)
            createOn = Instant.now()   // 指令执行成功之后，才能更新时间戳
            val newTaskId = newTask.id
            logInfo("New task=$newTaskId created ...")
            markTaskCreated(newTaskId)
            createMessage.clear()
        } catch (e: Exception) {
            val message = "$tag: Create new task failed: $taskId, $e"
            createMessage.update(message)
            if (message != createMessage.message) {
                logger.error("$tag: Create new task failed: ", e)
            }
        }
    }

    private fun abortTask() {
        try {
            // 防止PLC的数据长时间未清空，导致指令被频繁触发
            if (!commandEffect(abortOn)) return
            // 判断呼叫器是否存在未结束的任务
            val taskFromDB = findUnfinishedTask() ?: throw BusinessError("Task already finished!")
            // 判断当前任务是否可以被撤销
            checkUnfinishedTaskAbortAble(taskFromDB)
            RobotTaskService.abortTask(taskFromDB.id)
            abortOn = Instant.now()   // 指令执行成功之后，才能更新时间戳
            logInfo("Abort task=${taskFromDB.id} ...")
            markTaskFinished(SignalType.TaskAbortedByPager)
            abortMessage.clear()
        } catch (e: Exception) {
            val message = "$tag: Abort task failed: $taskId, $e"
            abortMessage.update(message)
            if (message != abortMessage.message) {
                logger.error("$tag: Abort task failed: $taskId, ", e)
            }
        }
    }

    private fun findUnfinishedTask(): RobotTask? {
        if (taskId.isBlank()) return null
        return c.findOne(RobotTask::id eq taskId, RobotTask::state eq RobotTaskState.Created)
    }

    private fun checkUnfinishedTaskAbortAble(task: RobotTask): Boolean {
        val cannotAbort = task.persistedVariables[FIELD_CANNOT_ABORT_BY_PAGER]
        val result = taskAbortAbleByPager(cannotAbort)
            && (signalValue !in listOf(SignalType.TaskBeingProcessed, SignalType.VehicleError))
        return if (result) result
        else throw BusinessError("task=${task.id} cannot abort, $FIELD_CANNOT_ABORT_BY_PAGER=$cannotAbort, signalValue=$signalValue")
    }

    private fun taskAbortAbleByPager(cannotAbortByPager: Any?): Boolean {
        return if (cannotAbortByPager is Boolean) !cannotAbortByPager else true
    }

    private fun controlLights() {
        try {
            val addrNo = signal.addrNo!!
            getHelper().write06SingleRegister(addrNo, signalValue, config.slaveId, readAndWriteRemark)
        } catch (e: Exception) {
            logger.error("$tag: Output signal value failed: ", e)
        }
    }

    // 初始化呼叫器时，调用此方法
    private fun initSignalValue() {
        val pp = getPersistedPagerByName(config.name)
        taskId = pp.taskId
        updateSignalValue(pp.signalType, taskId, "")

        if (taskId.isBlank()) return
        val taskFromDB = c.findOne(RobotTask::id eq taskId)
        if (taskFromDB == null) {
            // 目标任务应该是在 SRD 关闭之后，被直接从数据库中删除了
            logError("Cannot find task=$taskId from DB!")
            markTaskFinished(SignalType.TaskSuccess)
        } else updateSignalValueByTask(taskFromDB)
    }

    fun updateSignalValueByTask(task: RobotTask) {
        /**
         * 宽泛的定义 - 任务开始占用资源就算是正在执行了。
         *      - 任务在下个给调度之前，基本上都会涉及到占用资源（例如操作库位）的操作，这种情况下，任务也应该被判定为执行中。
         *
         * 传统的定义 - 根据运单（TransportOrder）的状态判断任务的状态。
         *      - 任务中的一个运单处于 BEING_PROCESSED 状态;
         *      - 或者有未封口的运单序列时，至少有一个运单已经结束了（FINISHED）。
         *
         * 涉及到操作系统资源时，需要配合组件 MarkTaskCannotAbortByPager 使用。
         * 判断条件：
         *      - 如果任务中的一个运单（TransportOrder）处于 BEING_PROCESSED 状态，则当前任务不能被撤销；
         *      - 或者，如果任务中的一个运单（TransportOrder）处于 FINISHED 状态，则当前任务不能被撤销；
         *      - 或者，在不满足上述两个条件时，任务被标记为“不能被呼叫器撤销”的状态时，当前任务不能被撤销。
         */
        when (task.state) {
            RobotTaskState.Created -> recordUnfinishedTaskState(task)
            RobotTaskState.Failed -> markTaskFinished(SignalType.TaskFailed)
            RobotTaskState.Success -> markTaskFinished(SignalType.TaskSuccess)
            RobotTaskState.Aborted -> {
                if (signalValue != SignalType.TaskAbortedByPager) {
                    markTaskFinished(SignalType.TaskAbortedBySrd)
                } else {
                    // SRD 重启之前记录的任务状态就是被呼叫器撤销的，因此不用重复记录
                    // markTaskFinished(SignalType.TaskAbortedByPager)
                }
            }
        }
    }

    private fun recordUnfinishedTaskState(task: RobotTask) {
        // 此时的任务一定是未结束的，或者说是非终态的。
        val transports = task.transports
        val abortAbleByPager = taskAbortAbleByPager(task.persistedVariables[FIELD_CANNOT_ABORT_BY_PAGER])

        val taskId = task.id
        // 如果任务已经占用了相关的资源，则说明此任务已经开始执行了
        if (!abortAbleByPager) {
            // 这种情况下，任务一定已经占用了部分任务资源了；任务不能被呼叫器撤销，则说明此任务已经开始执行了
            updateSignalValue(SignalType.TaskBeingProcessed, taskId, vehicleName)
        } else {
            // 任务可以被呼叫器撤销时，有如下几种情况
            //      - 当前任务是不需要占用资源的。
            //      - 当前任务是需要占用资源的，但是还未抢占到资源。

            val taskUnSent = transports.all {
                // 任务中只存在未下发的运单，或者是被跳过的运单时，这条任务都是可以直接被撤销的。
                it.state < RobotTransportState.Sent || it.state == RobotTransportState.Skipped
            }
            if (taskUnSent) {
                // 当前任务可以被呼叫器撤销，且没有任何已经下发给调度的运单
                updateSignalValue(SignalType.TaskCreated, taskId, "")
            } else {
                // 当前任务可以被呼叫器撤销，但是有部分运单已经下发给调度了，需要进一步判断 TransportOrder 的状态。
                // 如果已经下发给 Route 的运单的状态是 BEING_PROCESSED 了，则将当前任务标记为 BeingProcessed 。
                // 否则将任务标记为 Created 。
                var signalType = SignalType.TaskCreated
                val processingVehicle = getProcessingVehicle(transports)
                if (transports.any { it.state == RobotTransportState.Success } && !processingVehicle.isNullOrBlank()) {
                    // 存在被 AGV 完成的运单
                    signalType = SignalType.TaskBeingProcessed
                    vehicleName = processingVehicle
                } else {
                    // 如果已经下发的任务是 BEING_PROCESSED 状态，则标记当前任务状态为 BeingProcessed；否则标记当前任务状态为 Created 。
                    transports.filter { it.state == RobotTransportState.Sent }.map { it.routeOrderName }.forEach {
                        val transportOrder = MongoDBManager.collection<TransportOrder>()
                            .findOne(TransportOrder::name eq it) ?: return@forEach
                        if (transportOrder.state == TransportOrderState.BEING_PROCESSED) {
                            signalType = SignalType.TaskBeingProcessed
                            vehicleName = transportOrder.processingVehicle!!
                            return@forEach
                        }
                    }
                    updateSignalValue(signalType, taskId, vehicleName)
                }
            }
        }
    }

    private fun getProcessingVehicle(transports: List<RobotTransport>): String? {
        transports.forEach {
            val vehicleName = it.processingRobot
            if (!vehicleName.isNullOrBlank()) return vehicleName
        }
        return null
    }

    fun getModel(): PagerModel {
        return PagerModel(
            config = config,
            taskId = taskId,
            vehicleName = vehicleName,
            vehicleState = vehicleState.toString(),
            signalType = signalValue,
            lastCreateOn = toFormattedString(createOn, df),
            lastAbortOn = toFormattedString(abortOn, df),
            lastCreateError = createMessage.toString(),
            lastAbortError = abortMessage.toString()
        )
    }

    fun reset() {
        if (signalValue in listOf(SignalType.TaskCreated, SignalType.TaskBeingProcessed, SignalType.VehicleError))
            throw BusinessError("呼叫器[${config.name}]创建的任务还未结束，操作失败")
        markTaskFinished(SignalType.TaskSuccess)
        createMessage.clear()
        abortMessage.clear()
    }
}

fun toFormattedString(instant: Instant, format: DateTimeFormatter): String {
    return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(format)
}