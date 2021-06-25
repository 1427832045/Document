package com.seer.srd.vehicle.driver.io.redis

import com.seer.srd.SystemError
import com.seer.srd.outside.MessageTcsComm
import com.seer.srd.scheduler.backgroundCacheExecutor
import com.seer.srd.vehicle.driver.VehicleDriverManager
import com.seer.srd.vehicle.driver.io.AdapterIO
import com.seer.srd.vehicle.driver.io.VehicleStatus
import org.opentcs.drivers.vehicle.LoadHandlingDevice
import org.opentcs.drivers.vehicle.MovementCommand
import org.opentcs.drivers.vehicle.VehicleProcessModel
import org.opentcs.kernel.getInjector
import org.slf4j.LoggerFactory
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPubSub
import java.nio.charset.StandardCharsets
import java.time.Instant

class RedisAdapterIO(
    private val vehicleName: String,
    private val onAckMovementCommand: () -> Unit
) : AdapterIO() {
    
    private val logger = LoggerFactory.getLogger(RedisAdapterIO::class.java)
    
    // The redis normal command publisher for sending protobuf message to vehicle
    private val normalCommandPublisher: Publisher
    
    // The redis advanced command publisher for sending protobuf message to vehicle
    private val advancedCommandPublisher: Publisher
    
    // The channel name of vehicle response status.
    private val vehicleResponseStatusChannel: String
    
    // The channel name of vehicle response task.
    private val vehicleResponseAckChannel: String
    
    private val vehicleDetailChannel: String
    
    private val subscriber = Subscriber()
    
    private val jedis: Jedis
    
    init {
        val injector = getInjector() ?: throw SystemError("No Injector")
        val jedisPool = injector.getInstance(JedisPool::class.java)
        
        val name = vehicleName
        
        normalCommandPublisher =
            Publisher(jedisPool, "$name:cmd:normal")
        advancedCommandPublisher =
            Publisher(jedisPool, "$name:cmd:advanced")
        vehicleResponseStatusChannel = "$name:res:status"
        vehicleResponseAckChannel = "$name:res:ack"
        vehicleDetailChannel = "SW:VehicleStatus:$name"
        
        jedis = jedisPool.resource
        backgroundCacheExecutor.submit {
            try {
                jedis.subscribe(
                    subscriber,
                    vehicleResponseStatusChannel, vehicleResponseAckChannel, vehicleDetailChannel
                )
            } catch (e: Exception) {
                logger.info("subscribe channel error, ", e)
                if (jedis.isConnected) {
                    jedis.close()
                    logger.info("$vehicleName close jedis when catch exception")
                }
            }
        }
    }
    
    override fun connectVehicle() {
        //
    }
    
    override fun disconnectVehicle() {
        subscriber.unsubscribe()
        if (jedis.isConnected) {
            jedis.close()
            logger.info("$vehicleName close jedis when unsubscribe")
        }
    }
    
    override fun setVehiclePaused(paused: Boolean) {
        logger.info("pause vehicle $paused")
        val cmdBuilder = MessageTcsComm.Message_Command_Advanced.newBuilder()
        cmdBuilder.command = if (paused) "pause" else "resume"
        advancedCommandPublisher.publish(String(cmdBuilder.build().toByteArray(), Charsets.UTF_8))
    }
    
    override fun sendMovementCommand(cmd: MovementCommand) {
        val commandsBuilder = MessageTcsComm.Message_Command_Normal.newBuilder()
        commandsBuilder.addCommands(encodeCommand(cmd))
        normalCommandPublisher.publish(String(commandsBuilder.build().toByteArray(), Charsets.UTF_8))
    }
    
    override fun sendMovementCommands(cmds: List<MovementCommand>) {
        val snapshot = ArrayList(cmds)
        val commandsBuilder = MessageTcsComm.Message_Command_Normal.newBuilder()
        snapshot.forEach { cmd -> commandsBuilder.addCommands(encodeCommand(cmd)) }
        normalCommandPublisher.publish(String(commandsBuilder.build().toByteArray(), Charsets.UTF_8))
    }
    
    override fun sendClearAllMovementCommands() {
        val cmdBuilder = MessageTcsComm.Message_Command_Advanced.newBuilder()
        cmdBuilder.command = "clearAllCommands"
        advancedCommandPublisher.publish(String(cmdBuilder.build().toByteArray(), Charsets.UTF_8))
    }

    override fun sendSafeClearAllMovementCommands(movementId: String?) {
        val cmdBuilder = MessageTcsComm.Message_Command_Advanced.newBuilder()
        cmdBuilder.command = "safeClearAllCommands"
        advancedCommandPublisher.publish(String(cmdBuilder.build().toByteArray(), Charsets.UTF_8))
    }
    
    private fun encodeCommand(cmd: MovementCommand): MessageTcsComm.Message_Command {
        logger.info("encoding command: {}.", cmd.toString())
        val cmdBuilder = MessageTcsComm.Message_Command.newBuilder()
        cmdBuilder.id = cmd.id // id
        cmdBuilder.step = cmd.step.toString() // step (nonnull)
        cmdBuilder.sourcePoint = cmd.step.sourcePoint ?: cmdBuilder.step
        cmdBuilder.isFinalMovement = cmd.isFinalMovement // is final movement
        if (!cmd.isWithoutOperation) cmdBuilder.operation = cmd.operation // operation of location
        val properties = cmd.properties // properties (nonnull)
        properties.entries
            .filter { entry -> !entry.key.startsWith("device:") } // todo 为啥要过滤这个
            .forEach { entry ->
                val propertyBuilder = MessageTcsComm.Message_Properties.newBuilder()
                propertyBuilder.key = entry.key
                propertyBuilder.value = entry.value
                cmdBuilder.addProperties(propertyBuilder.build())
            }
        return cmdBuilder.build()
    }
    
    private inner class Subscriber : JedisPubSub() {
        
        override fun onMessage(channel: String, message: String) {
            try {
                when (channel) {
                    vehicleResponseStatusChannel -> {
                        val adapter = VehicleDriverManager.getVehicleCommAdapterOrNull(vehicleName) ?: return
                        
                        val protoBytes = message.toByteArray(StandardCharsets.UTF_8)
                        val res = MessageTcsComm.Message_Response_Status.parseFrom(protoBytes)
                        logger.debug("vehicleName: " + vehicleName + " Receive vehicle status: pos=" + res.position + ", state=" + res.state.name + ", energy=" + res.energyLevel)
                        
                        val vehicleLoadHandlingDevices = res.loadHandlingDevicesList
                            .map { msg -> LoadHandlingDevice(msg.label, msg.full) }.toList()
                        
                        val errorInfos = res.errorInfosList.map { info ->
                            VehicleProcessModel.ErrorInfo(
                                Instant.parse(info.timestamp), info.count.toInt(), info.level, info.message
                            )
                        }.toList()
                        
                        adapter.onVehicleStatus(
                            VehicleStatus(
                                res.energyLevel, vehicleLoadHandlingDevices, errorInfos,
                                res.position, res.state.name, res.exeState.name
                            )
                        )
                    }
                    vehicleResponseAckChannel -> {
                        logger.info("receive ACK")
                        onAckMovementCommand()
                    }
                    vehicleDetailChannel -> {
                        val adapter = VehicleDriverManager.getVehicleCommAdapterOrNull(vehicleName) ?: return
                        adapter.onVehicleDetails(message)
                    }
                }
            } catch (e: Exception) {
                logger.error("onMessage: " + "${e.printStackTrace()}")
            }
        }
        
        override fun onSubscribe(channel: String, subscribedChannels: Int) {
            logger.info("subscribe redis channel success, channel ${channel}, subscribedChannels $subscribedChannels")
        }
        
        override fun onUnsubscribe(channel: String, subscribedChannels: Int) {
            logger.info("unsubscribe redis channel, channel $channel, subscribedChannels $subscribedChannels")
        }
    }
    
}