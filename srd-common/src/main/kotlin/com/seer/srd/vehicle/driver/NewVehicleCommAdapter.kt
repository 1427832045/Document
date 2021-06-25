package com.seer.srd.vehicle.driver

import com.fasterxml.jackson.databind.JsonNode
import com.seer.srd.RbkNotConnected
import com.seer.srd.SystemError
import com.seer.srd.route.VehicleSimulation
import com.seer.srd.route.routeConfig
import com.seer.srd.route.service.VehicleService
import com.seer.srd.util.mapper
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.vehicle.driver.io.VehicleStatus
import com.seer.srd.vehicle.driver.tcp2.AioTcpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.opentcs.drivers.vehicle.*
import org.opentcs.drivers.vehicle.synchronizer.DeviceSynchronizer
import org.opentcs.kernel.getInjector
import org.opentcs.util.ExplainedBoolean
import org.slf4j.LoggerFactory
import java.beans.PropertyChangeEvent
import java.io.IOException
import java.time.Instant
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import java.net.InetAddress

class NewVehicleCommAdapter(private val vehicle: Vehicle, private val ip: String) : AbstractVehicleCommAdapter(vehicle) {
    
    private val lock = Object()
    
    @Volatile
    private var initialized = false

    @Volatile
    private var enabled = false
    
    private var paused = vehicle.paused
    
    override val processModel = VehicleProcessModel(vehicle.name)
    
    override val commandQueueCapacity = routeConfig.commandQueueCapacity
    
    // 这就是此适配器的命令队列了
    private val commandQueue: Queue<MovementCommand> = ConcurrentLinkedQueue()
    
    private val sentQueueCapacity = routeConfig.sentQueueCapacity
    
    private val forceResend = routeConfig.forceResend
    
    /**
     * 返回已经送达机器人但尚未被机器人处理的命令。
     * todo yy 尚未处理是指：没处理、处理中但没处理完
     */
    private val sentQueue: Queue<MovementCommand> = ConcurrentLinkedQueue()
    
    // 按顺序执行sentQueue中的命令，每次只检查第一个命令的状态
    private fun currentTaskId() = if (sentQueue.isNotEmpty()) sentQueue.peek().id else ""
    
    private var lastStatus: TaskStatus = TaskStatus("", -1)
    
    private val deviceSynchronizer: DeviceSynchronizer
    
    // The flag for requestOnLocationAtSendCommand.
    private val requestAtSendState = AtomicBoolean(true)
    
    @Volatile
    private var lastStatusReceivedOn: Long? = null
    
    private var latestVehicleStatus: VehicleStatus? = null
    
    private var cmdProcessingExecutor: ExecutorService? = Executors.newSingleThreadExecutor()
    
    @Volatile
    private var timeout = false
    
    @Volatile
    private var statusClient: AioTcpClient? = null
    
    @Volatile
    private var reportClient: AioTcpClient? = null
    
    @Volatile
    private var moveClient: AioTcpClient? = null
    
    @Volatile
    private var configClient: AioTcpClient? = null
    
    @Volatile
    private var rebuilding = false
    
    @Volatile
    private var fetchExceptionCount = 0
    
    private var rebuildExecutor: ExecutorService? = Executors.newSingleThreadExecutor()
    private var fetchReportExecutor: ExecutorService? = Executors.newSingleThreadExecutor()
    private var adapterTimer: ScheduledExecutorService? = Executors.newScheduledThreadPool(3)
//    private lateinit var queryTaskStatusFuture: Future<*>
    private lateinit var fetchFuture: Future<*>
    private lateinit var updateOwnerFuture: Future<*>

    // 此变量记录了机器人 block 的时间戳
    @Volatile
    private var blockedTimeStamp: Long? = null
    
    /**
     * 控制权逻辑：
     * 1. SRD不会自动获取控制权
     * 2. 每隔一段时间询问一次rbk，更新控制权获取情况
     * 3. 如果没有控制权，不能发送新命令，不能暂停
     */
    // 如果srd控制，owner是srd
    @Volatile
    var owner: String = ""
    
    @Volatile
    var isDominating: Boolean = false
    
    // this adapter only supports Tcp2
    init {
        val injector = getInjector() ?: throw SystemError("No Injector")
        deviceSynchronizer = injector.getInstance(DeviceSynchronizer::class.java)
    }
    
    override fun lock() = lock
    
    override fun initialize() {
        if (initialized) return
        processModel.addPropertyChangeListener(this)
        processModel.state = Vehicle.State.UNKNOWN
        this.initialized = true
    }

    override fun getSentQueue(): Queue<MovementCommand> {
        return sentQueue
    }

    override fun getCommandQueue(): Queue<MovementCommand> {
        return commandQueue
    }
    
    override fun isInitialized() = initialized
    
    override fun terminate() {
        if (isEnabled()) {
            logError("TerminateWhenStillEnabled", "", null)
        }
        if (!initialized) return
        processModel.removePropertyChangeListener(this)
        this.initialized = false
    }
    
    override fun enable() {
        synchronized(lock) {
            logInfo("Enable", "now=${isEnabled()}")
            if (!isInitialized()) {
                logError("EnableBeforeInitialized", "", null)
            }
            if (isEnabled()) return
            enabled = true

            VehicleService.updateVehicleAdapterEnabled(vehicle.name, true)

            if (cmdProcessingExecutor == null) {
                cmdProcessingExecutor = Executors.newSingleThreadExecutor()
            }
            cmdProcessingExecutor!!.submit(this::processNextCommand)

            if (fetchReportExecutor == null) {
                fetchReportExecutor = Executors.newSingleThreadExecutor()
            }
            fetchFuture = fetchReportExecutor!!.submit(this::fetch)

            if (rebuildExecutor == null) {
                rebuildExecutor = Executors.newSingleThreadExecutor()
            }

            if (adapterTimer == null) {
                adapterTimer = Executors.newScheduledThreadPool(3)
            }
//            queryTaskStatusFuture = adapterTimer!!.scheduleAtFixedRate(this::checkCurrentMoveTaskStatusJob, 3, 2, TimeUnit.SECONDS)
            updateOwnerFuture = adapterTimer!!.scheduleAtFixedRate(this::updateOwnerTask, 3, 1, TimeUnit.SECONDS);

            processModel.isCommAdapterEnabled = true
            timeout = false
        }
    }
    
    override fun disable() {
        synchronized(lock) {
            logInfo("Disable", "now=${isEnabled()}")
            if (!isEnabled()) return
            
            enabled = false
            
            VehicleService.updateVehicleAdapterEnabled(vehicle.name, false)
            
            cmdProcessingExecutor?.shutdown()
            cmdProcessingExecutor = null
            
            // Update the vehicle's state for the rest of the system.
            processModel.isCommAdapterEnabled = false
            processModel.state = Vehicle.State.UNKNOWN
            
            fetchFuture.cancel(true)
            fetchReportExecutor?.shutdown()
            fetchReportExecutor = null
            
            rebuildExecutor?.shutdown()
            rebuildExecutor?.awaitTermination(100, TimeUnit.MILLISECONDS)
            rebuildExecutor = null

//            queryTaskStatusFuture.cancel(true)
            updateOwnerFuture.cancel(true)
            adapterTimer?.shutdown()
            adapterTimer = null

            closeAllClients()
        }
    }
    
    private fun isEnabled(): Boolean {
        synchronized(lock) {
            return enabled
        }
    }
    
    override fun setVehiclePaused(b: Boolean) {
        if (!initialized || !isEnabled() || rebuilding)
        {
            logger.debug("${vehicle.name} can not set pause/resume, adapter initialized=$initialized, enable=${isEnabled()}, rebuilding=$rebuilding.")
            return
        }
        synchronized(lock) {
            paused = b
            try {
                val code = 3001 + if (paused) 0 else 1
                val resString = getMoveClient().requestBlocking(code, "")
                val infoNode = mapper.readTree(resString)
                logIfFailed(infoNode, code, "")
            } catch (e: RbkNotConnected) {
                logger.error("${vehicle.name} is not connected, so set paused/resume failed.", e)
            } catch (e: IOException) {
                logger.error("${vehicle.name} gets IOexception, will rebuild.", e)
                rebuild("${vehicle.name} set pause/resume failed")
            } catch (ex: Exception) {
                logger.error("${vehicle.name} set paused/resume failed.", ex)
            }
        }
    }
    
    override fun onAckMovementCommandOfFlowNo() {
        TODO("Not yet implemented")
    }
    
    private fun rebuild(reason: String) {
        if (rebuilding) {
            logger.debug("${vehicle.name} has been rebuilding, so return.")
            return
        }
        rebuilding = true
        rebuildExecutor?.submit { rebuildInBackground(reason) }
    }
    
    // must be run in a single thread executor since there is no locking
    private fun rebuildInBackground(reason: String) {
        // ------ 跑在 singleThreadExecutor 里面，一定不会并发，会导致重复 rebuilding
//        if (rebuilding) {
//            return
//        }
//        rebuilding = true
        // ------
        // lock section above to run in a multi-thread executor
        try {
            closeAllClients()
            runBlocking {
                var rebuildCount = 0
                while (isEnabled()) {
                    logger.info("${vehicle.name} rebuilding tcp clients, reason: $reason, rebuildCount=$rebuildCount, rebuilding=$rebuilding")
                    rebuildCount++
                    try {
                        statusClient = AioTcpClient(vehicle.name, ip, 19204)
                        moveClient = AioTcpClient(vehicle.name, ip, 19206)
                        reportClient = AioTcpClient(vehicle.name, ip, 19301)
                        configClient = AioTcpClient(vehicle.name, ip, 19207)

                        configReportChannel()
                        return@runBlocking
                    } catch (e: IOException) {
                        logger.error("IOException while rebuilding clients for ${vehicle.name}", e)
                        closeAllClients()
                        delay(1000)
                    } catch (e: Exception) {
                        logger.error("Error rebuilding", e)
                        closeAllClients()
                        delay(1000)
                    }
                }
            }
        } catch (e: InterruptedException) {
            logger.error("Rebuilding for ${vehicle.name}, but interrupted")
            return
        } finally {
            rebuilding = false
        }
    }
    
    private fun logIfFailed(infoNode: JsonNode, code: Int, reqStr: String) {
        val returnCode = infoNode["ret_code"]?.asInt()
        if (returnCode == 0) return
        logger.error("Request rbk failed [$code] res=$infoNode req=$reqStr}")
    }
    
    private fun configReportChannel() {
        val req: MutableMap<String, Any> = mutableMapOf("interval" to routeConfig.reportConfig.interval)
        // excluded 和 included 不兼容。excluded 采用机器人默认配置，然后排除不需要的字段
        if (routeConfig.reportConfig.excludedFields.isNotEmpty()) {
            req["excluded_fields"] = routeConfig.reportConfig.excludedFields
        } else {
            // 默认需要的字段
            val includedFields = arrayListOf<String>(
                "controller_temp",
                "x",
                "y",
                "angle",
                "current_station",
                "vx",
                "vy",
                "w",
                "steer",
                "blocked",
                "battery_level",
                "charging",
                "emergency",
                "DI",
                "DO",
                "fatals",
                "errors",
                "warnings",
                "notices",
                "current_map",
                "vehicle_id",
                "create_on",
                "requestVoltage",
                "requestCurrent",
                "brake",
                "soft_emc",
                "task_status_package",
                "fork_auto_flag"
            )
            if (routeConfig.reportConfig.includedFields.isNotEmpty()) {
                includedFields.addAll(routeConfig.reportConfig.includedFields)
            }
            req["included_fields"] = includedFields
        }
        val reqStr = mapper.writeValueAsString(req)
        val resString = getConfigClient().requestBlocking(4091, reqStr)
        val infoNode = mapper.readTree(resString)
        logIfFailed(infoNode, 4091, reqStr)
    }
    
    override fun acquireVehicle(nickName: String): Boolean {
        if (!initialized || !isEnabled() || rebuilding)
        {
            logger.debug("Can not acquire ${vehicle.name}, adapter initialized=$initialized, enable=${isEnabled()}, rebuilding=$rebuilding.")
            return false
        }
        try{
            val req = mapOf("nick_name" to nickName)
            val reqStr = mapper.writeValueAsString(req)
            val resString = getConfigClient().requestBlocking(4005, reqStr)
            if (resString == "current is already locked!") return true
            val infoNode = mapper.readTree(resString)
            val returnCode = infoNode["ret_code"]?.asInt()
            return returnCode == 0
        } catch (e: RbkNotConnected) {
            logger.error("${vehicle.name} is not connected, so acquire failed.", e)
        } catch (e: IOException) {
            logger.error("${vehicle.name} gets IOexception, will rebuild.", e)
            rebuild("${vehicle.name} acquire failed")
        } catch (ex: Exception) {
            logger.error("${vehicle.name} acquire failed.", ex)
        }
        return false
    }
    
    override fun disownVehicle(): Boolean {
        if (!initialized || !isEnabled() || rebuilding)
        {
            logger.debug("Can not disown ${vehicle.name}, adapter initialized=$initialized, enable=${isEnabled()}, rebuilding=$rebuilding.")
            return false
        }
        try {
            val resString = getConfigClient().requestBlocking(4006, "")
            // TODO 没有锁定的情况
            val infoNode = mapper.readTree(resString)
            val returnCode = infoNode["ret_code"]?.asInt()
            return returnCode == 0
        } catch (e: RbkNotConnected) {
            logger.error("${vehicle.name} is not connected, so disown failed.", e)
        } catch (e: IOException) {
            logger.error("${vehicle.name} gets IOexception, will rebuild.", e)
            rebuild("${vehicle.name} disown failed")
        } catch (ex: Exception) {
            logger.error("${vehicle.name} disown failed.", ex)
        }
        return false
    }
    
    private fun updateOwnerTask() {
        try {
            if (!initialized || !isEnabled() || rebuilding)
            {
//                logger.debug("${vehicle.name} cannot update owner, adapter initialized=$initialized, enable=${isEnabled()}, rebuilding=$rebuilding.")
                return
            }
            updateOwner()
        } catch (e: Exception) {
            // TODO 异常处理
            synchronized(lock) {
                processModel.isDominating = false
                processModel.owner = "unknown"
            }
            logger.error("${vehicle.name} updateOwnerTask", e)
        }
    }
    
    private fun updateOwner() {
        val locked: Boolean?
        try {
            val resString = getStatusClient().requestBlocking(1060, "")
            val infoNode = mapper.readTree(resString)
            val nickName = infoNode["nick_name"]?.asText()
            val ip = infoNode["ip"]?.asText()
            locked = infoNode["locked"]?.asBoolean()
            isDominating =
                if (routeConfig.checkWithOwnerIp) ips.contains(ip) && (nickName == routeConfig.srdNickName)
                else (nickName == routeConfig.srdNickName)
            owner = (nickName ?: "Unknown") + "@" + (ip ?: "UnknownIP")
        } catch (e: RbkNotConnected) {
            logger.error("${vehicle.name} is not connected, so update owner failed.", e)
            synchronized(lock) {
                processModel.isDominating = false
                processModel.owner = "unknown"
            }
            return
        } catch (e: IOException) {
            logger.error("${vehicle.name} gets IOexception, will rebuild.", e)
            rebuild("${vehicle.name} update owner failed")
            synchronized(lock) {
                processModel.isDominating = false
                processModel.owner = "unknown"
            }
            return
        } catch (ex: Exception) {
            logger.error("${vehicle.name} update owner failed.", ex)
            synchronized(lock) {
                processModel.isDominating = false
                processModel.owner = "unknown"
            }
            return
        }
        // update the owner
        synchronized(lock) {
            processModel.isDominating = isDominating
            processModel.owner = owner
        }
        // 目标机器人无人占用时，发起自动抢占
        if (locked == false && routeConfig.autoOwnRobot) {
            acquireVehicle(routeConfig.srdNickName)
        }
    }
    
    private fun closeAllClients() {
        statusClient?.close()
        moveClient?.close()
        reportClient?.close()
        configClient?.close()
    }
    
    private fun getStatusClient(): AioTcpClient {
        val client = statusClient
        if (client != null) return client
        rebuild("${vehicle.name} getStatusClient")
        throw RbkNotConnected()
    }
    
    private fun getMoveClient(): AioTcpClient {
        val client = moveClient
        if (client != null) return client
        rebuild("${vehicle.name} getMoveClient")
        throw RbkNotConnected()
    }
    
    private fun getReportClient(): AioTcpClient {
        val client = reportClient
        if (client != null) return client
        rebuild("${vehicle.name} getReportClient")
        throw RbkNotConnected()
    }
    
    private fun getConfigClient(): AioTcpClient {
        val client = configClient
        if (client != null) return client
        logger.info("${vehicle.name} configClient is null, going to rebuild")
        rebuild("${vehicle.name} getConfigClient")
        throw RbkNotConnected()
    }
    
    /**
     * 向此适配器的命令队列追加一条命令。返回该命令是否成功添加到队列。添加失败的主要因素是超过队列长度。
     */
    override fun appendToCommandQueue(command: MovementCommand): Boolean {
        synchronized(lock) {
            if (commandQueue.size >= commandQueueCapacity) return false
            logInfo("AppendToCommandQueue", command.toString())
            commandQueue.add(command)
            processModel.commandEnqueued(command)
            triggerCommandDispatcherTask()
            return true
        }
    }
    
    // 让队列继续执行下一条命令
    private fun triggerCommandDispatcherTask() {
        synchronized(lock) {
            lock.notifyAll()
        }
    }
    
    /**
     * 指定执行的 [AdapterCommand].
     */
    override fun execute(command: AdapterCommand) {
        throw NotImplementedError()
    }
    
    private fun processNextCommand() {
        while (initialized && isEnabled()) {
            deviceXXX()
            val cmd = commandQueue.peek()
            if (cmd != null) {
                synchronized(lock) {
                    try {
                        logger.debug("${vehicle.name} Sending command : $cmd")
                        val sendOk = sendCommand(cmd)
                        if (sendOk) {
                            commandQueue.poll()
                            if (!sentQueue.add(cmd)) logError("FailedAppendSentCommand", cmd.toString(), null)
                            // Notify listeners that this command was sent.
                            processModel.commandSent(cmd)
                        } else {
                            Thread.sleep(1000)
                        }
                    } catch (e: Exception) {
                        logError("FailedSendToVehicle", cmd.toString(), e)
                        // processModel.commandFailed(cmd)
                    }
                }
            }
        }
    }
    
    /**
     * 将此命令转换为车可以理解的并发送给车
     * 此方法在外部使用同步
     */
    private fun sendCommand(cmd: MovementCommand): Boolean {

        synchronized(lock) {
            return if (sendMovementCommand(cmd)) {
                // Do synchronizer operation
                requestAtSendState.set(deviceSynchronizer.requestAtSend(processModel.getName(), cmd).value)
                true
            } else {
                false
            }
        }
    }
    
    private fun sendMovementCommand(cmd: MovementCommand): Boolean {
        logInfo("SendToVehicle", cmd.toString())
        if (!initialized || !isEnabled() || rebuilding)
        {
            logger.debug("${vehicle.name} cannot send command, adapter initialized=$initialized, enable=${isEnabled()}, rebuilding=$rebuilding.")
            return false
        }
        return try {
            val moveTaskList = mapOf("move_task_list" to listOf(movementCommand2JsonNode(cmd)))
            val reqStr = mapper.writeValueAsString(moveTaskList)
            val resString = getMoveClient().requestBlocking(3066, reqStr)
            val infoNode = mapper.readTree(resString)
            val returnCode = infoNode["ret_code"]?.asInt()
            val success = returnCode == 0
            if (!success) {
                logger.error("Error sendMovementCommand: $resString")
            }
            success
        } catch (e: RbkNotConnected) {
            logger.error("${vehicle.name} is not connected, send command failed.", e)
            false
        } catch (e: IOException) {
            logger.error("${vehicle.name} gets IOexception, will rebuild.", e)
            rebuild("${vehicle.name} send command failed")
            false
        } catch (e: Exception) {
            logError("SendCommandFailed", e.message ?: "", null)
            false
        }
    }
    
    /**
     * 告诉 process model 此命令已执行。sent queue 出队。返回该命令执行是否成功。
     */
    private fun onCommandExecuted(executedCommand: MovementCommand): Boolean {
        synchronized(lock) {
            logger.debug("${vehicle.name} cmd executed: $executedCommand")
            val syncState = deviceSynchronizer.queryAtExecuted(processModel.getName(), executedCommand)
            if (!syncState.value) {
                logInfo("Synchroizer queryAtExecuted FAILED", "because: ${syncState.reason}.")
                return false
            }
            
            // Send command to device synchronize server.
            val reqState = deviceSynchronizer.requestAfterExecuted(processModel.getName(), executedCommand)
            if (!reqState.value) {
                logInfo("The synchroizer requestAfterExecuted FAILED", "because: ${reqState.reason}.")
                return false
            }
            
            // todo 在干嘛
            if (!requestAtSendState.get()) {
                logInfo("", "The synchroizer requestAtSend need reqeust again.")
                return false
            }
            
            val sentCommand = sentQueue.poll()
            logInfo("", "sentQueue poll $sentCommand")
            return if (executedCommand == sentCommand) {
                // Let the vehicle manager know we've finished this command.
                // yy 这就是到点了
                processModel.vehiclePosition = executedCommand.step.destinationPoint
                processModel.commandExecuted(executedCommand)
                true
                // There is no need to trigger, "getProcessModel().commandExecuted(executedCommand)"
                // emit a COMMAND_EXECUTED event to this propertyChange() function
                // triggerCommandDispatcherTask();
            } else {
                logError("", "The executed command isn't current sent command. " +
                    "Executed=${executedCommand}, current=${sentCommand}", null)
                false
            }
        }
    }
    
    private fun checkCurrentMoveTaskStatusJob() {
        try {
            if (!isEnabled()) return
            if (currentTaskId().isBlank()) return
            val newStatus = queryMoveTaskListStatus(currentTaskId())
            logger.debug("${vehicle.name} Query MoveTaskStatusList: $newStatus")
//            checkCurrentMoveTaskStatus(newStatus)
        } catch (e: Exception) {
            logger.error("${vehicle.name} checkCurrentMoveTaskStatus exception: ", e)
            rebuild("${vehicle.name} checkCurrentMoveTaskStatus exception")
        }
    }
    
    private fun checkCurrentMoveTaskStatus(newStatus: TaskStatus, currentPosition: String?, errorInfoMap: Map<String, List<ErrorItem>>) {
        if (!(lastStatus.taskId == newStatus.taskId && lastStatus.status == newStatus.status)) {
            lastStatus = newStatus
        }
        // Failed = 5, Canceled = 6, OverTime = 7
        if (newStatus.status == 4) {
            // 命令完成
            for (cmd in sentQueue) {
                if (cmd.id == newStatus.taskId) {
                    logger.debug("${vehicle.name} ${cmd.id} finished")
                    if (!onCommandExecuted(cmd)) return
                }
            }
            logger.info("${vehicle.name} ${newStatus.status}")
        }
        // 52202 是充电超时，此时将命令彻底 fail，并且清除机器人错误，等待下一次 dispatch 重建充电运单。
        else if (newStatus.status == 5 && errorInfoMap["errors"]?.map { it.code }?.contains(52202) == true) {
            logInfo("RechargeFailed", "")
            for (cmd in sentQueue) {
                if (cmd.id == newStatus.taskId) {
                    logInfo("FailedCommand", "$cmd")
                    processModel.commandFailed(cmd)
                    break
                }
            }
            // 要清除机器人所有错误，然后才会重建调度运单
            clearAllErrors()
        }
        else if (newStatus.status == 5 || newStatus.status == 6 || newStatus.status == 7 || newStatus.status == 404) {
            if (isVehicleInNormalState(processModel.state) && canResend(currentPosition)) {
                logger.debug("${vehicle.name} resend ok")
                // 调整sentQueue
                adjustSentQueue(currentPosition)
                // 改id
                if (newStatus.status != 404) {
                    sentQueue.forEachIndexed { index, movementCommand -> movementCommand.id = "${vehicle.name}:${System.currentTimeMillis() - 10000000L * index}" }
                }
                // 重发
                sentQueue.forEach {
                    sendMovementCommand(it)
                }
            }
            logger.error("${vehicle.name} get new status: ${newStatus.status}")
        }
        logger.debug("${vehicle.name} Finished CheckMoveTaskListStatus: $newStatus")
    }
    
    // 根据实际位置调整sentQueue
    private fun adjustSentQueue(currentPosition: String?) {
        if (sentQueue.isEmpty()) return
        
        // 如果当前位置没有在命令里，说明还在原点到第一个点的路上
        if (!sentQueue.any { it.step.destinationPoint == currentPosition }) return
        
        while (!sentQueue.isEmpty()) {
            val cmd = sentQueue.peek()
            if (cmd.step.destinationPoint != currentPosition) {
                logInfo("destinationPoint = ${cmd.step.destinationPoint},$$$ currentPosition = $currentPosition", "")
                logInfo("CommandSkipped", cmd.toString())
                if (!onCommandExecuted(cmd)) return // continue loop
            } else {
                if (!cmd.isFinalMovement) {
                    // boolean success = commandExecuted(executedCommand); if (!success) {break;}
                    logInfo("Command is not final movement, so executed", cmd.toString())
                    onCommandExecuted(cmd)
                }
                break;
            }
        }
    }
    
    private fun queryMoveTaskListStatus(taskId: String): TaskStatus {
        // 19204端口, API号1110, 查询命令状态
        val msg = mapper.writeValueAsString(mapOf("task_ids" to listOf(taskId)))
        val responseString = getStatusClient().requestBlocking(1110, msg)
        val infoNode = mapper.readTree(responseString)
        val statusNode = infoNode["task_status_package"]?.get("task_status_list")?.get(0)
        return if (statusNode == null) {
            TaskStatus(
                "", -1
            )
        } else
            TaskStatus(
                statusNode["task_id"]?.asText() ?: "",
                statusNode["status"]?.asInt() ?: -1
            )
    }
    
    /**
     * 清除此适配器的命令队列。命令队列中的所有尚未送到车的命令会被清除。车当前可能正在执行的命令将仍会完成。
     */
    override fun clearCommandQueue() {
        logInfo("ClearCommandQueue", "")
        synchronized(lock) {
            try {
                if (initialized && isEnabled() && !rebuilding) {
                    getMoveClient().requestBlocking(3067, "")
                }
            } catch (e: Exception) {
                logError("FailedSendClearAllCommands", "", e)
            }
            
            commandQueue.clear()
            sentQueue.clear()
        }
    }

    override fun safeClearCommandQueue() {
        TODO("Not yet implemented")
    }
    
    /**
     * 处理一条到通讯适配器的一般命令。此方法提供到通讯适配器的单向信道。
     * 消息可以是任何东西，包括 null。如果消息无意义，忽略就行，而不是抛出异常。
     * 如果此通讯适配性不支持处理消息，就全忽略他们。
     * 期待此方法快速返回，不要执行太长时间。
     */
    override fun processMessage(message: Any) {
        logger.debug("${vehicle.name} process message $message")
        // do nothing
    }
    
    /**
     * 检查车是否能够处理指定的动作序列，考虑其当前状态。
     */
    override fun canProcess(): ExplainedBoolean {
        synchronized(lock) {
            return if (isEnabled()) ExplainedBoolean(true, "") else ExplainedBoolean(false, "adapter not enabled")
        }
    }
    
    // yy todo 不能会有什么影响
    private fun canSendNextCommand(): Boolean {
        synchronized(lock) {
            if (!isDominating) {
//                logger.debug("${vehicle.name} is owned by $owner, not srd")
                return false
            }
            if (!isVehicleInNormalState(processModel.state)) {
                logger.debug("${vehicle.name} adapter cannot send, vehicle state is ${processModel.state}")
                return false
            }
            val canSend = sentQueue.size < sentQueueCapacity && !commandQueue.isEmpty()
            
            if (routeConfig.vehicleSimulation == VehicleSimulation.None) {
                if (sentQueue.size >= sentQueueCapacity) {
                    logger.debug("${vehicle.name} adapter cannot send, sentQueue is full")
                }
                if (commandQueue.isEmpty()) {
                    logger.debug("${vehicle.name} adapter cannot send, commandQueue is empty")
                }
            }
            if (!canSend) {
                return false
            }
            
            
            // 如果是暂停的，则不能发送下一步指令
            if (paused) {
                logger.info("${vehicle.name} adapter cannot send, vehicle is paused")
                return false
            }
            
            // Fetch the next movementCommand.
            val nextCommand = commandQueue.peek()
            if (null != nextCommand) {
                // Check if current position is on the route
                val tempQueue: Queue<MovementCommand> = ConcurrentLinkedQueue()
                tempQueue.addAll(sentQueue)
                tempQueue.add(nextCommand)
                val onRoute = tempQueue.any {
                    val sourcePoint = it.step.sourcePoint ?: it.step.destinationPoint
                    sourcePoint == processModel.vehiclePosition || it.step.destinationPoint == processModel.vehiclePosition
                }
                if (!onRoute) {
                    logError("RobotNotOnPath", "Robot is not on route, position is ${processModel.vehiclePosition}", null)
                    if (forceResend && processModel.vehiclePosition == null) {
                        logInfo("ForceSendWhenPositionIsNull", "${vehicle.name} force send, config item forceResend == true.")
                    } else {
                        return false
                    }
                }
                // If the sent queue is not empty, and next command has "device:" properties, so can not send.
                // If the sent queue is not empty, and current command has "device:" properties, so can not send.
                if (sentQueue.size != 0 && (hasDeviceProp(nextCommand) || hasDeviceProp(sentQueue.peek()))) return false
                
                // 解决管制区已经属于我方时，漏加引用计数的问题
                if (nextCommand.properties.entries.any { entry -> entry.key == "device:enterMutexZone"}){
                    deviceSynchronizer.requestBefore(processModel.getName(), nextCommand)
                }

                // Synchronizer logic
                val syncState = deviceSynchronizer.queryBefore(processModel.getName(), nextCommand)
                if (!syncState.value) {
                    logInfo("", "The synchroizer query before send command FAILED because: ${syncState.reason}.")
                    // Request again
                    deviceSynchronizer.requestBefore(processModel.getName(), nextCommand)
                    // Request failed, can not send
                    return false
                }
                return true
            }
            
            return true
        }
    }
    
    override fun propertyChange(evt: PropertyChangeEvent) {
        // If a command was executed by this comm adapter, wake up the command dispatcher task to see if
        // we should send another command to the vehicle.
        if (evt.propertyName == VehicleProcessModel.Attribute.COMMAND_EXECUTED.name) {
            triggerCommandDispatcherTask()
        }
        // todo 改成阻塞队列，去掉这个方法。但有可能：命令能否出队的逻辑比较复杂，不能仅靠阻塞队列实现
    }
    
    private fun deviceXXX() {
        try {
            // Wait until we're terminated or we can send the next command.
            while (!canSendNextCommand() && initialized && isEnabled()) {
                synchronized(lock) {
                    // Keep on requesting device synchronizer while vehicle is executing.
                    val cmd = sentQueue.peek()
                    if (cmd != null) {
                        deviceSynchronizer.requestWhile(processModel.getName(), cmd)
                        // If request send failed, then resend.
                        if (!requestAtSendState.get()) {
                            requestAtSendState.set(deviceSynchronizer.requestAtSend(processModel.getName(), cmd).value)
                        }
                    }
                }
                Thread.sleep(1000)
            }
        } catch (e: InterruptedException) {
            return
        }
    }
    
    private fun fetch() {
        while (!Thread.interrupted() && isEnabled() && initialized) {
            try {
                if (!fetchReport()) {
                    processModel.state = Vehicle.State.UNAVAILABLE
                    Thread.sleep(1000L)
                }
            } catch (e: InterruptedException) {
                logger.error("${vehicle.name} Fetch interrupted.")
                return
            } catch (e: Exception) {
                logger.error("${vehicle.name} Fetch report", e)
                Thread.sleep(5400)
            }
        }
    }
    
    // Fetch report from report client
    // Report will be saved as details in processModel, as String
    private fun fetchReport(): Boolean {
        if (rebuilding) return false
        val msg = try {
            runBlocking { getReportClient().readResponse(null) }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: RbkNotConnected) {
            throw e
        } catch (e: Exception) {
            fetchExceptionCount++
            if (fetchExceptionCount == 10) {
                logger.error("Failed to fetch report from ${vehicle.name}, exception: ", e)
                fetchExceptionCount = 0
            }
            logger.error("${vehicle.name} read report exception: ", e)
            rebuild("${vehicle.name} read report exception")
            return false
        }
        // update detail with report String
        onVehicleDetails(msg)
        // build VehicleStatus instance from report
        val infoNode = mapper.readTree(msg)
        val energyLevel = infoNode["battery_level"]?.asDouble()?.times(100)?.roundToInt() ?: 0
        val loadHandlingDevices = emptyList<LoadHandlingDevice>() // ignored
        val errorInfoMap = buildErrorInfoList(infoNode)
        val currentStation = infoNode["current_station"]?.asText()
        val tempBlocked = infoNode["blocked"]?.asBoolean() ?: false
        val relocStatus = infoNode["reloc_status"]?.asInt() ?: -1
        if (!tempBlocked) {
            blockedTimeStamp = null
        } else if (blockedTimeStamp == null) {
            blockedTimeStamp = System.currentTimeMillis()
        }
        val blocked = if (blockedTimeStamp == null) false else {
            System.currentTimeMillis() - blockedTimeStamp!! >= routeConfig.vehicleBlockDelay
        }

        logInfo("OriginReport", "pos=$currentStation, energy=$energyLevel.")

        val position: String?
        val status: VehicleStatus

        synchronized(lock) {
            var newStatus: TaskStatus?
            val currentTaskId = currentTaskId()
            // 当前有任务在跑
            if (!currentTaskId.isBlank()) {
                newStatus = infoNode["task_status_package"]
                    ?.get("task_status_list")
                    ?.find { it["task_id"]?.asText() == currentTaskId }
                    ?.let {
                        TaskStatus(
                            it["task_id"]?.asText() ?: "",
                            it["status"]?.asInt() ?: -1
                        )
                    }
                // 如果 19301 里面的 task list 没有当前 currentTaskId，则主动查询一次
                if (newStatus == null) {
                    try {
                        newStatus = queryMoveTaskListStatus(currentTaskId)
                        logger.debug("${vehicle.name} Query MoveTaskStatus: $currentTaskId")
                    } catch (e: IOException) {
                        logger.error("${vehicle.name} query current MoveTaskStatus IOxception: ", e)
                        rebuild("${vehicle.name} query current MoveTaskStatus exception")
                        return false
                    } catch (e: Exception) {
                        logger.error("${vehicle.name} query current MoveTaskStatus exception: ", e)
                        return false
                    }
                }
                // then
                // 检查一遍任务执行到哪里了
                logger.debug("${vehicle.name} check move status, ${newStatus.taskId}, ${newStatus.status}")
                checkCurrentMoveTaskStatus(newStatus, currentStation, errorInfoMap)
                if (newStatus.status == 2 || newStatus.status == 3) {
                    val sourcePosition = infoNode["task_status_package"]?.get("source_name")?.asText()
                    val targetPosition = infoNode["task_status_package"]?.get("target_name")?.asText()
                    if (sourcePosition.isNullOrBlank() || targetPosition.isNullOrBlank()) {
                        logInfo("MoveStatusUnexpected", "source=$sourcePosition, target=$targetPosition.")
                        position = currentStation
                    } else if (currentStation == sourcePosition || currentStation == targetPosition) {
                        position = currentStation
                    } else {
                        // 针对 currentStation == null，或者其他站点的情况
                        val percentage = infoNode["task_status_package"]?.get("percentage")?.asDouble(0.0) ?: 0.0
                        logger.debug("${vehicle.name} report $currentStation, on path $sourcePosition --- $targetPosition, " +
                            "percentage is $percentage.")
                        position = if (percentage > 0.5) {
                            targetPosition
                        } else {
                            sourcePosition
                        }
                        logger.debug("${vehicle.name} adapter change position to $position")
                    }
                } else {
                    position = currentStation
                }
            } else {
                position = currentStation
            }
            // UNAVAILABLE
            // ERROR
            // IDLE
            // CHARGING
            // EXECUTING
            val state = determineVehicleState(infoNode, errorInfoMap)
            status = VehicleStatus(
                energyLevel, loadHandlingDevices, errorInfoMap.values.flatten().map { it.errorInfo }, position, state, "", blocked, relocStatus
            )
        }
        onVehicleStatus(status)
        return true
    }
    
    private fun determineVehicleState(infoNode: JsonNode, errorInfoMap: Map<String, List<ErrorItem>>): String {
        // UNAVAILABLE
        // ERROR
        if (!errorInfoMap["fatals"].isNullOrEmpty() || !errorInfoMap["errors"].isNullOrEmpty()) return "ERROR"
        val errorItems = errorInfoMap["warnings"] ?: emptyList()
        errorItems.forEach {
            if (arrayOf(54004, 54025, 54013).contains(it.code))
                return "ERROR"
        }
        if (infoNode["fork_auto_flag"]?.asBoolean() == false) return "ERROR"
        // CHARGING
        if (infoNode["charging"]?.asBoolean() == true) return "CHARGING"
        // EXECUTING
        if (sentQueue.size > 0) return "EXECUTING"
        // IDLE
        return if (sentQueue.size == 0) "IDLE"
        else "UNAVAILABLE"
    }
    
    override fun onVehicleStatus(req: VehicleStatus) {
        synchronized(lock) {
            if (latestVehicleStatus == null || latestVehicleStatus != req) {
                logInfo("OnVehicleStatusChangedTo", req.toString())
            }
            latestVehicleStatus = req
        }
        
        // 不管过滤 VehicleStatus 的处理，因为可能之前上报状态时下面用的条件已变化
        // todo 过滤连续相同的 VehicleStatus
        synchronized(lock) {
            lastStatusReceivedOn = System.currentTimeMillis()
            timeout = false
            processModel.energyLevel = req.energyLevel
            processModel.loadHandlingDevices = req.loadHandingDevices
            processModel.errorInfos = req.errorInfos ?: emptyList()
            if (!req.position.isNullOrBlank()) {
                processModel.vehiclePosition = req.position
            } else {
                processModel.vehiclePosition = null
                logError("NullPosition", "The vehicle position is null.", null)
            }
            // compare vehicle state
            val newState = Vehicle.State.valueOf(req.state)
            processModel.state = newState
            processModel.blocked = req.blocked
            processModel.relocStatus = req.relocStatus
        }
    }
    
    override fun canResend(currentPosition: String?): Boolean {
        //如果位置相同就重发，否则报警，如果在当前工作站，回收再释放，依然重发
        // 命令不存在，没必要重发。车子没位置，不能重发
        // 命令是存在的，命令里的 sourcePoint 是存在的，和车的当前点是同一个点，重发 ||
        // 命令是存在的，命令里的 sorucePoint 不存在，但是 destinationPoint 和车的当前点是同一个点，重发
        return if (sentQueue.isEmpty()) {
            logInfo("CannotResend", "No resendable command")
            false
        } else if (currentPosition.isNullOrBlank()) {
            if (forceResend) {
                logInfo("ForceResend", "VehiclePosition is Null, but still resend")
                true
            } else {
                logInfo("CannotResend", "VehiclePosition is Null")
                false
            }
        } else if (sentQueue.any {
                it.step.sourcePoint == currentPosition ||
                    it.step.destinationPoint == currentPosition
            }) {
            true
        } else {
            logError("VehicleGetOutOfPath", "Please take it to ${sentQueue.peek()?.step?.destinationPoint}", null)
            false
        }
    }
    
    override fun onVehicleDetails(req: String) {
        processModel.details = req
    }
    
    private fun isVehicleInNormalState(state: Vehicle.State): Boolean {
        return state == Vehicle.State.IDLE || state == Vehicle.State.EXECUTING || state == Vehicle.State.CHARGING
    }
    
    private fun hasDeviceProp(command: MovementCommand): Boolean {
        // device:leaveMutexZone 属性的线路
        return command.properties.entries.any { entry ->
            (entry.key.startsWith("device:") && entry.key != "device:leaveMutexZone") || entry.key == "robot:switchMap"
        }
    }
    
    private fun logInfo(type: String, details: String) {
        logger.info("[${vehicle.name}][$type] $details")
    }
    
    private fun logError(type: String, details: String, e: Throwable?) {
        logger.error("[${vehicle.name}][$type] $details", e)
    }
    
    private fun buildErrorInfoList(infoNode: JsonNode): Map<String, List<ErrorItem>> {
        val levels = listOf("fatals", "errors", "warnings")
        return levels.map { level ->
            level to buildErrorInfoItems(infoNode, level)
        }.toMap()
    }
    
    private fun buildErrorInfoItems(infoNode: JsonNode, level: String): List<ErrorItem> {
        val nodes = infoNode[level]?.asIterable() ?: emptyList()
        val items = mutableListOf<ErrorItem>()
        nodes.forEach {
            it.fieldNames().forEach { name ->
                val code = name.toIntOrNull()
                if (code != null) {
                    val message = it["desc"]?.asText() ?: ""
                    val times = it["times"].asInt()
                    items += ErrorItem(errorInfo = VehicleProcessModel.ErrorInfo(Instant.ofEpochSecond(it[name].asLong()), it["times"]?.asInt()
                        ?: 1, level, "[$code] $message"), code = code, times = times, message = message)
                }
            }
        }
        return items
    }
    
    override fun clearAllErrors(): Boolean {
        if (!initialized || !isEnabled() || rebuilding)
        {
            logger.debug("${vehicle.name} can not clear errors adapter initialized=$initialized, enable=${isEnabled()}, rebuilding=$rebuilding.")
            return false
        }
        logger.info("Clearing ${vehicle.name} errors...")
        try {
            val resString = getConfigClient().requestBlocking(4009, "")
            val infoNode = mapper.readTree(resString)
            val returnCode = infoNode["ret_code"]?.asInt()
            return returnCode == 0
        } catch (e: RbkNotConnected) {
            logger.error("${vehicle.name} is not connected, so clear errors failed.", e)
        } catch (e: IOException) {
            logger.error("${vehicle.name} gets IOexception, will rebuild.", e)
            rebuild("${vehicle.name} clear errors failed")
        } catch (ex: Exception) {
            logger.error("${vehicle.name} clear errors failed.", ex)
        }
        return false
    }
    
    companion object {
        // 所有车的适配器共用
        private val logger = LoggerFactory.getLogger(NewVehicleCommAdapter::class.java)
        private var ips: List<String> = emptyList()
        
        init {
            val hostName = try {
                val address = InetAddress.getLocalHost()
                address.hostName
            } catch (e: Exception) {
                logger.error("Failed to get host name: " + e.message)
                "Failed to get host name"
            }
            
            try {
                if (hostName.isNotBlank()) {
                    val addresses = InetAddress.getAllByName(hostName)
                    if (addresses.isNotEmpty()) {
                        ips += addresses.map { it.hostAddress }
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to get host address: " + e.message)
            }
            
            if (ips.isEmpty()) {
                logger.error("Failed to get host address")
            }
        }
    }
}

data class TaskStatus(
    val taskId: String,
    val status: Int
)

data class ErrorItem(
    val errorInfo: VehicleProcessModel.ErrorInfo,
    val code: Int,
    val times: Int,
    val message: String

)