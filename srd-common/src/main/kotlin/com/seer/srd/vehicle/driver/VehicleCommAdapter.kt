package com.seer.srd.vehicle.driver

import com.seer.srd.BusinessError
import com.seer.srd.SystemError
import com.seer.srd.route.VehicleCommAdapterIOType
import com.seer.srd.route.VehicleSimulation
import com.seer.srd.route.routeConfig
import com.seer.srd.util.mapper
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.vehicle.driver.io.AdapterIO
import com.seer.srd.vehicle.driver.io.VehicleStatus
import com.seer.srd.vehicle.driver.io.http.HttpAdapterIO
import com.seer.srd.vehicle.driver.io.redis.RedisAdapterIO
import com.seer.srd.vehicle.driver.io.tcp.AioTcpAdapterIO
import com.seer.srd.vehicle.driver.io.tcp.TcpAdapterIO
import org.opentcs.drivers.vehicle.AdapterCommand
import org.opentcs.drivers.vehicle.MovementCommand
import org.opentcs.drivers.vehicle.VehicleProcessModel
import org.opentcs.drivers.vehicle.synchronizer.DeviceSynchronizer
import org.opentcs.kernel.getInjector
import org.opentcs.util.ExplainedBoolean
import org.slf4j.LoggerFactory
import java.beans.PropertyChangeEvent
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class VehicleCommAdapter(private val vehicle: Vehicle) : AbstractVehicleCommAdapter(vehicle) {
    
    private val logger = LoggerFactory.getLogger(VehicleCommAdapter::class.java)
    
    private val lock = Object()
    
    @Volatile
    private var initialized = false
    
    private var enabled = false
    
    private var paused = vehicle.paused
    
    override val processModel = VehicleProcessModel(vehicle.name)
    
    override val commandQueueCapacity = routeConfig.commandQueueCapacity
    
    // 这就是此适配器的命令队列了
    private val commandQueue: Queue<MovementCommand> = ConcurrentLinkedQueue()
    
    private val sentQueueCapacity = routeConfig.sentQueueCapacity
    
    private val forceResend = routeConfig.forceResend

    private val robotStoppingDelayMs = routeConfig.robotStoppingDelayMs

    private val sendCommandArrayToRbk = routeConfig.sendCommandArrayToRbk
    
    /**
     * 返回已经送达机器人但尚未被机器人处理的命令。
     * todo yy 尚未处理是指：没处理、处理中但没处理完
     */
    private val sentQueue: Queue<MovementCommand> = ConcurrentLinkedQueue()
    
    private val deviceSynchronizer: DeviceSynchronizer
    
    // The flag for requestOnLocationAtSendCommand.
    private val requestAtSendState = AtomicBoolean(true)
    
    // 最近一次发送 MovementCommand 命令的时间
    @Volatile
    private var waitForAckBeganOn: Long? = null
    
    @Volatile
    private var lastStatusReceivedOn: Long? = null
    
    private var lastVehicleExecutingState = VehicleExecutingState.NONE
    
    private var latestVehicleStatus: VehicleStatus? = null
    
    val io: AdapterIO
    
    private var timeoutChecker: ScheduledFuture<*>? = null
    
    private var cmdProcessingExecutor: ExecutorService? = Executors.newSingleThreadExecutor()

    private var adapterTimer: ScheduledExecutorService? = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var timeout = false

    @Volatile
    private var robotStop = true

    private var robotStopTimeStamp: Long? = null
    
    init {
        val injector = getInjector() ?: throw SystemError("No Injector")
        deviceSynchronizer = injector.getInstance(DeviceSynchronizer::class.java)
        
        io = when (routeConfig.commAdapterIO) {
            VehicleCommAdapterIOType.Redis -> RedisAdapterIO(vehicle.name, this::onAckMovementCommand)
            VehicleCommAdapterIOType.Http -> HttpAdapterIO(vehicle.name, this::onAckMovementCommand)
            VehicleCommAdapterIOType.Tcp -> TcpAdapterIO(vehicle.name)
            VehicleCommAdapterIOType.AioTcp -> AioTcpAdapterIO(vehicle.name)
            else -> throw BusinessError("[${vehicle.name}] Unknown vehicle communication adapter io type")
        }
    }

    override fun getSentQueue(): Queue<MovementCommand> {
        return sentQueue
    }

    override fun getCommandQueue(): Queue<MovementCommand> {
        return commandQueue
    }
    
    override fun lock(): Any {
        return lock
    }
    
    override fun initialize() {
        if (initialized) return
        processModel.addPropertyChangeListener(this)
        processModel.state = Vehicle.State.IDLE
        this.initialized = true
    }
    
    override fun isInitialized(): Boolean {
        return initialized
    }
    
    override fun terminate() {
        if (!initialized) return
        processModel.removePropertyChangeListener(this)
        this.initialized = false
    }
    
    override fun enable() {
        synchronized(lock) {
            logInfo("Enable", "now=${isEnabled()}")
            if (isEnabled()) return

            if (adapterTimer == null) {
                adapterTimer = Executors.newSingleThreadScheduledExecutor()
            }
            timeoutChecker = adapterTimer!!.scheduleAtFixedRate(this::checkTimeout, 3, 5, TimeUnit.SECONDS)
            
            waitForAckBeganOn = null
            
            io.connectVehicle()

            if (cmdProcessingExecutor == null) {
                cmdProcessingExecutor = Executors.newSingleThreadExecutor()
            }
            cmdProcessingExecutor!!.submit(this::processNextCommand)

            enabled = true
            processModel.isCommAdapterEnabled = true
            
            timeout = false
        }
    }
    
    override fun disable() {
        synchronized(lock) {
            logInfo("Disable", "now=${isEnabled()}")
            if (!isEnabled()) return
            
            enabled = false
            
            io.disconnectVehicle()
            
            cmdProcessingExecutor?.shutdown()
            cmdProcessingExecutor = null

            // Update the vehicle's state for the rest of the system.
            processModel.isCommAdapterEnabled = false
            processModel.state = Vehicle.State.UNKNOWN
            
            timeoutChecker?.cancel(true)
            timeoutChecker = null
            adapterTimer?.shutdown()
            adapterTimer = null
        }
    }
    
    private fun isEnabled(): Boolean {
        synchronized(lock) {
            return enabled
        }
    }
    
    override fun setVehiclePaused(value: Boolean) {
        synchronized(lock) {
            paused = value
        }
        try {
            io.setVehiclePaused(paused)
            checkToResend()
        }
        catch (ex: Exception) {
            logError("FailedSetVehiclePaused", "", ex)
        }
    }
    
    /**
     * 向此适配器的命令队列追加一条命令。返回该命令是否成功添加到队列。添加失败的主要因素是超过队列长度。
     */
    override fun appendToCommandQueue(newCommand: MovementCommand): Boolean {
        synchronized(lock) {
            if (commandQueue.size >= commandQueueCapacity) return false
            logInfo("AppendToCommandQueue", newCommand.toString())
            commandQueue.add(newCommand)
            processModel.commandEnqueued(newCommand)
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
        command.execute(this)
    }
    
    private fun processNextCommand() {
        while (initialized) {
            deviceXXX();
            val cmd = commandQueue.poll()
            if (cmd != null) {
                synchronized(lock) {
                    try {
                        sendCommand(cmd)
                        if (!sentQueue.add(cmd)) logError("FailedAppendSentCommand", cmd.toString(), null)
                        // Notify listeners that this command was sent.
                        processModel.commandSent(cmd)
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
    private fun sendCommand(cmd: MovementCommand) {
        logInfo("SendToVehicle", cmd.toString())
        val cmdQueue: Queue<MovementCommand> = ConcurrentLinkedQueue()
        synchronized(lock) {
            waitForAckBeganOn = System.currentTimeMillis()
//            logInfo("ConfirmConfig","sendCommandArrayToRbk=$sendCommandArrayToRbk")
            // 需要发送整条命令队列，并且不是仿真时
            if (sendCommandArrayToRbk && routeConfig.vehicleSimulation == VehicleSimulation.None) {
                cmdQueue.addAll(sentQueue)
            }
        }

        cmdQueue.add(cmd)
        io.sendMovementCommands(cmdQueue.toList())
        
        synchronized(lock) {
            // Do synchronizer operation
            requestAtSendState.set(deviceSynchronizer.requestAtSend(processModel.getName(), cmd).value)
        }
    }
    
    private fun resendMovementCommands() {
        synchronized(lock) {
            val emptySent = sentQueue.isEmpty()
            logInfo("ResendToVehicle", "emptySentQueue=${emptySent}")
            if (emptySent) return
        }
        
        io.sendClearAllMovementCommands()
        
        synchronized(lock) {
            waitForAckBeganOn = System.currentTimeMillis()
        }
        
        // resend
        io.sendMovementCommands(sentQueue.toList())
        
        synchronized(lock) {
            // Do synchronizer operation
            val cmd = sentQueue.peek()
            if (cmd != null) {
                synchronized(lock) {
                    // Do synchronizer operation
                    requestAtSendState.set(deviceSynchronizer.requestAtSend(processModel.getName(), cmd).value)
                }
            }
        }
    }
    
    /**
     * 告诉 process model 此命令已执行。sent queue 出队。返回该命令执行是否成功。
     */
    private fun onCommandExecuted(executedCommand: MovementCommand): Boolean {
        synchronized(lock) {
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
    
    // 根据车的位置判断队列头上哪些命令已执行
    private fun searchCommandsExecuted(isFromFault: Boolean) {
        synchronized(lock) {
            if (sentQueue.isEmpty()) return
            
            val currentPosition = processModel.vehiclePosition
            // 如果当前位置没有在命令里，说明还在原点到第一个点的路上
            if (!sentQueue.any { it.step.destinationPoint == currentPosition }) return
            
            val state = processModel.state
            val isVehicleInIdleState = state == Vehicle.State.IDLE || state == Vehicle.State.CHARGING
            
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
                        if (!onCommandExecuted(cmd)) return
                    } else if (isVehicleInIdleState && waitForAckBeganOn == null) { // 命令已ACK且车处于空闲
                        logInfo("", "Vehicle state: $state, isFromFault: $isFromFault, " +
                            "last executing state: $lastVehicleExecutingState")
                        // last state is in error
                        if (!isFromFault || lastVehicleExecutingState == VehicleExecutingState.OPERATING) {
                            logInfo("Execute final command", cmd.toString())
                            if (!onCommandExecuted(cmd)) return
                        }
                    }
                    // todo yy if-else 不完备
                    return
                }
            }
        }
    }
    
    /**
     * 清除此适配器的命令队列。命令队列中的所有尚未送到车的命令会被清除。车当前可能正在执行的命令将仍会完成。
     */
    override fun clearCommandQueue() {
        logInfo("ClearCommandQueue", "")
        synchronized(lock) {
            try {
                io.sendClearAllMovementCommands()
            } catch (e: Exception) {
                logError("FailedSendClearAllCommands", "", e)
            }
            
            waitForAckBeganOn = null
            
            commandQueue.clear()
            sentQueue.clear()
        }
    }

    override fun safeClearCommandQueue() {
        logInfo("SafeClearCommandQueue", "")
        synchronized(lock) {
            try {
                var movementId = when {
                    sentQueue.size > 1 -> {
                        sentQueue.elementAt(1).id
                    }
                    sentQueue.size == 1 -> {
                        sentQueue.peek().id
                    }
                    else -> ""
                }
                if (!movementId.isNullOrBlank()) {
                    logInfo("SafeClearCommandId", movementId)
                    io.sendSafeClearAllMovementCommands(movementId)
                }
            } catch (e: Exception) {
                logError("FailedSendSafeClearAllCommands", "", e)
            }
            waitForAckBeganOn = null
            commandQueue.clear()
        }
    }
    
    /**
     * 处理一条到通讯适配器的一般命令。此方法提供到通讯适配器的单向信道。
     * 消息可以是任何东西，包括 null。如果消息无意义，忽略就行，而不是抛出异常。
     * 如果此通讯适配性不支持处理消息，就全忽略他们。
     * 期待此方法快速返回，不要执行太长时间。
     */
    override fun processMessage(message: Any) {
        logger.debug("process message $message")
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
            if (waitForAckBeganOn != null) {
                logger.debug("${vehicle.name} adapter cannot send, waitForAckBegan is ${waitForAckBeganOn.toString()}")
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
                    return false
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
    
    private fun checkTimeout() {
        val lastStatusReceivedOn = this.lastStatusReceivedOn
        val waitForAckBeganOn = this.waitForAckBeganOn
        val error = if (timeout) {
            false
        } else if (lastStatusReceivedOn != null && (System.currentTimeMillis() - lastStatusReceivedOn) / 1000 > routeConfig.rbkStatusTimeout) {
            logError("ReportTimeout", "", null)
            true
        } else if (waitForAckBeganOn != null && (System.currentTimeMillis() - waitForAckBeganOn) / 1000 > routeConfig.rbkAckTimeout) {
            logError("AckTimeout", "", null)
            true
        } else {
            false
        }
        
        if (error) {
            timeout = true
            this.waitForAckBeganOn = null
            processModel.state = Vehicle.State.UNAVAILABLE
        }
    }
    
    private fun deviceXXX() {
        try {
            // Wait until we're terminated or we can send the next command.
            while (!canSendNextCommand() && initialized) {
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
                // detemine how much time to sleep
                Thread.sleep(
                    if (routeConfig.vehicleSimulation == VehicleSimulation.Tcp) {
                        (100.0/(routeConfig.simulationTimeFactor)).toLong()
                    } else {
                        1000
                    }
                )
            }
        } catch (e: InterruptedException) {
            return
        }
    }
    
    override fun onVehicleStatus(vs: VehicleStatus) {
        synchronized(lock) {
            if (latestVehicleStatus == null || latestVehicleStatus != vs) {
                logInfo("OnVehicleStatusChangedTo", vs.toString())
            }
            latestVehicleStatus = vs
        }
        
        // 不管过滤 VehicleStatus 的处理，因为可能之前上报状态时下面用的条件已变化
        // todo 过滤连续相同的 VehicleStatus
        synchronized(lock) {
            lastStatusReceivedOn = System.currentTimeMillis()
            timeout = false
            processModel.energyLevel = vs.energyLevel
            processModel.loadHandlingDevices = vs.loadHandingDevices
            processModel.errorInfos = vs.errorInfos ?: emptyList()
            if (!vs.position.isNullOrBlank()) {
                processModel.vehiclePosition = vs.position
            } else {
                processModel.vehiclePosition = null
                logError("NullPosition", "The vehicle position is null.", null)
            }
            // compare vehicle state
            val lastState = processModel.state
            var newState = Vehicle.State.valueOf(vs.state)

            // if robot is moving (robotStop == false), change IDLE to EXECUTING
            if (!robotStop && (newState == Vehicle.State.IDLE || newState == Vehicle.State.CHARGING))
            {
                newState = Vehicle.State.EXECUTING
                logInfo("VehicleState", "Manually change state to EXECUTING as vehicle is not stop.")
            }

            processModel.state = newState
            val reportVehicleState = VehicleExecutingState.valueOf(vs.exeState)
            if (isVehicleInNormalState(newState)) {
                // process the command normally
                if (isVehicleInNormalState(lastState)) {
                    searchCommandsExecuted(false)
                } else {
                    logger.info("The Last and new Executing State is: ${lastVehicleExecutingState}, ${reportVehicleState.name}")
                    searchCommandsExecuted(true)
                    checkToResend()
                }
            }
            if (isVehicleInNormalState(lastState) && !isVehicleInNormalState(newState)) { //???????????
                lastVehicleExecutingState = reportVehicleState
            }
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
                it.step.sourcePoint == currentPosition || it.step.destinationPoint == currentPosition }) {
            true
        } else {
            logError("VehicleGetOutOfPath", "Please take it to ${sentQueue.peek()?.step?.destinationPoint}", null)
            false
        }
    }
    
    private fun checkToResend() {
        val canResendToVehicle = canResend(processModel.vehiclePosition)
        
        if (canResendToVehicle) {
            // 如果当前不是暂停的，才重发。否则等到收到 resume 指令时，再重发。
            if (!paused) {
                resendMovementCommands()
            } else {
                logError("CannotResend", "The vehicle is paused.", null)
            }
        }
    }
    
    private fun onAckMovementCommand() {
        logInfo("Ack", "onAckMovementCommand")
        waitForAckBeganOn = null
    }
    
    override fun onAckMovementCommandOfFlowNo() {
        logInfo("Ack", "onAckMovementCommandOfFlowNo")
        waitForAckBeganOn = null
    }
    
    override fun acquireVehicle(nickName: String): Boolean {
        throw NotImplementedError("Older version does not support this operation")
    }
    
    override fun disownVehicle(): Boolean {
        throw NotImplementedError("Older version does not support this operation")
    }
    
    override fun clearAllErrors(): Boolean {
        throw NotImplementedError("Older version does not support this operation")
    }
    
    override fun onVehicleDetails(details: String) {
        processModel.details = details
        // checkout if robot is stop.
        try {
            val infoNode = mapper.readTree(details)
            val vx = infoNode["vx"]?.asDouble() ?: 0.0
            val vy = infoNode["vy"]?.asDouble() ?: 0.0
            val w = infoNode["w"]?.asDouble() ?: 0.0
            val isStop = vx < 0.001 && vy < 0.001 && w < 0.001
            if (isStop) {
                if (robotStopTimeStamp == null) {
                    robotStopTimeStamp = System.currentTimeMillis()
                }
            } else {
                robotStopTimeStamp = null
            }
            // 连续 stop robotStoppingDelayMs ms 以上，才认为是 stop 了
            robotStop = if (robotStopTimeStamp == null) false else {
                System.currentTimeMillis() - robotStopTimeStamp!! > robotStoppingDelayMs
            }
        }
        catch (ex: Exception) {
            logError("FailedToResolveVehicleDetails", "", ex)
        }
    }
    
    private fun isVehicleInNormalState(state: Vehicle.State): Boolean {
        return state == Vehicle.State.IDLE || state == Vehicle.State.EXECUTING || state == Vehicle.State.CHARGING
    }
    
    private fun hasDeviceProp(command: MovementCommand): Boolean {
        // device:leaveMutexZone 属性的线路
        return command.properties.entries.any {
            entry -> (entry.key.startsWith("device:") && entry.key != "device:leaveMutexZone") || entry.key == "robot:switchMap"
        }
    }
    
    private fun logInfo(type: String, details: String) {
        logger.info("[${vehicle.name}][$type] $details")
    }
    
    private fun logError(type: String, details: String, e: Throwable?) {
        logger.error("[${vehicle.name}][$type] $details", e)
    }
    
//    companion object {
//        // 所有车的适配器共用
//        private val adapterTimer = Executors.newSingleThreadScheduledExecutor()
//    }
    
}

enum class VehicleExecutingState {
    NONE, MOVING, OPERATING, UNRECOGNIZED
}