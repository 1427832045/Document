package com.seer.srd.vehicle.driver.io.tcp

import com.fasterxml.jackson.databind.JsonNode
import com.seer.srd.CONFIG
import com.seer.srd.db.MongoDBManager
import com.seer.srd.io.tcp.ByteArrayBuffer
import com.seer.srd.io.tcp.KeepReceivingTcpClient
import com.seer.srd.io.tcp.PkgExtractor
import com.seer.srd.route.VehicleCommAdapterIOType
import com.seer.srd.route.routeConfig
import com.seer.srd.route.service.PlantModelService
import com.seer.srd.route.service.VehicleService
import com.seer.srd.util.mapper
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.vehicle.VehiclePersistable
import com.seer.srd.vehicle.driver.VehicleExecutingState
import com.seer.srd.vehicle.driver.io.VehicleStatus
import com.seer.srd.vehicle.driver.io.http.MovementCommandReq
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.slf4j.LoggerFactory
import java.io.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.sqrt

object VehicleTcpSimulationManager {
    
    private val logger = LoggerFactory.getLogger(VehicleTcpSimulationManager::class.java)
    
    private val simulations: MutableMap<String, VehicleTcpSimulation> = ConcurrentHashMap()
    
    fun init() {
        if (routeConfig.commAdapterIO != VehicleCommAdapterIOType.Tcp) return
        
        logger.info("init vehicle tcp simulations")
        
        // 如数据库里面有mockPosition，就用数据库里面的mockPosition，否则用ppPoints
        val c = MongoDBManager.collection<VehiclePersistable>()
        val ppPoints = PlantModelService.getPlantModel().points.values.filter { it.name.startsWith("PP") }.toList()
        
        val vehicles = VehicleService.listVehicles()
        if (CONFIG.startFromDB) {
            vehicles.forEachIndexed { i, v ->
                val mockPosition = c.findOne(VehiclePersistable::id eq v.name)?.mockPosition ?: ""
                val ppPosition = if (i < ppPoints.size) ppPoints[i].name else ""
                val initPosition = if (mockPosition.isBlank()) ppPosition else mockPosition
                simulations[v.name] = VehicleTcpSimulation(v.name, initPosition)
            }
        } else {
            vehicles.forEachIndexed { i, v ->
                val ppPosition = if (i < ppPoints.size) ppPoints[i].name else ""
                simulations[v.name] = VehicleTcpSimulation(v.name, ppPosition)
            }
        }
    }
    
    fun dispose() {
        if (routeConfig.commAdapterIO != VehicleCommAdapterIOType.Http) return
        
        logger.info("dispose vehicle tcp simulations")
        
        simulations.values.forEach { it.dispose() }
        simulations.clear()
    }
    
}

class VehicleTcpSimulation(
    val vehicleName: String,
    var position: String? = null
) {
    
    private val logger = LoggerFactory.getLogger(VehicleTcpSimulation::class.java)

    @Volatile
    private var paused = false

    @Volatile
    private var moving = false
    
    private val movementCommands: Queue<MovementCommandReq> = ConcurrentLinkedQueue()
    
    private val cmdExecutor = Executors.newSingleThreadScheduledExecutor()
    
    private val reportExecutor = Executors.newSingleThreadScheduledExecutor()

    private val detailsExecutor = Executors.newSingleThreadScheduledExecutor()
    
    private val pkgExtractor = PkgExtractor(tcpPkgHead, this::parsePkgLen, this::onPkg)
    
    private val tcpClient = KeepReceivingTcpClient("127.0.0.1", routeConfig.vehicleAdapterTcpServerPort, pkgExtractor)
    private val mockVehicleDetails = routeConfig.mockVehicleDetails
    
    private val operationTime: MutableMap<String, Long> = mutableMapOf()

    private var state = Vehicle.State.UNKNOWN
    private var energyLevel = 99

    // 模拟顶升车
    @Volatile
    private var jackState: Int = 3 // 0x00 = 上升中，0x01 = 上升到位，0x02 = 下降中，0x03 = 下降到位
    
    init {
        signIn()
        
        cmdExecutor.submit {
            while (true) {
                try {
                    loop()
                } catch (e: InterruptedException) {
                    return@submit
                } catch (e: Exception) {
                    logger.error("cmd executor", e)
                }
            }
        }
        reportExecutor.scheduleAtFixedRate(this::report, 3000, 2000, TimeUnit.MILLISECONDS)
        if (mockVehicleDetails) {
            detailsExecutor.scheduleAtFixedRate(this::details, 1000, 1000, TimeUnit.MILLISECONDS)
        }
    }
    
    private fun signIn() {
        synchronized(this) {
            write(Message("SignIn", arguments = mapOf("vehicle" to vehicleName)), tcpClient::write)
        }
    }
    
    private fun loop() {
        val cmd: MovementCommandReq?
        if (paused) {
            logger.info("$vehicleName is paused, sim sleep 100.")
            Thread.sleep(100)
            return
        }
        synchronized(this) {
            cmd = movementCommands.poll()
        }
        if (null == cmd) {
//            logger.info("$vehicleName has no command, sleep 10.")
            Thread.sleep(10)
            return
        }
        synchronized(this) {
            moving = true
        }
        val walkTime = (getWalkTime(cmd) / routeConfig.simulationTimeFactor).toLong()
        val operationTime = (getOperationTime(cmd) / routeConfig.simulationTimeFactor).toLong()
        logger.debug("$vehicleName sim walk ${cmd.sourcePoint} --- ${cmd.step}: $walkTime ms")
        Thread.sleep(walkTime)
        synchronized(this) {
            position = cmd.step
        }
        logger.debug("$vehicleName sim operation: $operationTime ms")
        // 对部分 operation 进行模拟
        simulateOperation(cmd)
        // 如果 operation 不花时间
        if (operationTime == 0L) {
            synchronized(this) {
                moving = false
            }
            report()
            return
        }
        // 位置到达，先 report 一次
        report()
        // 模拟操作，后再 report 一次
        Thread.sleep(operationTime)
        synchronized(this) {
            moving = false
        }
        report()
    }

    private fun simulateOperation(cmd: MovementCommandReq) {
        when (cmd.operation) {
            "JackLoad" -> {
                jackState = 1
            }
            "JackUnload" -> {
                jackState = 3
            }
            else -> {
                // wait for more operations
            }
        }
    }
    
    private fun getWalkTime(c: MovementCommandReq): Long {
        // acc
        val vehicleAcc = VehicleService.getVehicle(vehicleName).properties["robot:acc"]?.toDoubleOrNull()
            ?: DEFAULT_ACC
        val pathAcc = getStepProperty(c, "sim:maxacc")?.toDoubleOrNull()
        val acc = if (null == pathAcc) vehicleAcc else min(vehicleAcc, pathAcc * 1000)

        // dec
        val vehicleDec = VehicleService.getVehicle(vehicleName).properties["robot:dec"]?.toDoubleOrNull()
            ?: DEFAULT_DEC
        val pathDec = getStepProperty(c, "sim:maxdec")?.toDoubleOrNull()
        val dec = if (null == pathDec) vehicleDec else min(vehicleDec, pathDec * 1000)

        // speed
        val vehicleSpeed = VehicleService.getVehicle(vehicleName).properties["robot:speed"]?.toDoubleOrNull()
            ?: DEFAULT_SPEED
        val pathSpeed = getStepProperty(c, "sim:maxspeed")?.toDoubleOrNull()
        val speed = if (null == pathSpeed) vehicleSpeed else min(vehicleSpeed, pathSpeed * 1000)

        val l = getStepLength(c)
        val walkTime = calWalkTime(l, acc, dec, speed)
        return walkTime.toLong()
    }

    private fun getStepProperty(c: MovementCommandReq, key: String): String? {
        val destination = c.step
        val start = c.sourcePoint
        if (start == destination) {
            return null
        }
        val pathName = "$start --- $destination"
        val path = PlantModelService.getPathIfNameIs(pathName)
        return path?.properties?.getOrDefault(key, null)
    }
    
    private fun getStepLength(c: MovementCommandReq): Double {
        val destination = c.step
        val start = c.sourcePoint
        if (start == destination) {
            return 0.0
        }
        val pathName = "$start --- $destination"
        val path = PlantModelService.getPathIfNameIs(pathName)
        return path?.length?.toDouble() ?: 0.0
    }
    
    private fun calWalkTime(l: Double, acc: Double, dec: Double, speed: Double): Double {
        logger.debug("l: $l, acc: $acc, dec: $dec, speed: $speed")
        
        val minLength = (speed / acc + speed / dec) * speed / 2
        logger.debug("minLength: $minLength mm")
        return 1000 * if (l <= minLength) {
            val maxSpeed = sqrt((2 * l / (1 / acc + 1 / dec)))
            logger.debug("maxSpeed: $maxSpeed mm/s")
            maxSpeed / acc + maxSpeed / dec
        } else {
            val speedTime = (l - minLength) / speed
            val notSpeedTime = minLength / speed * 2
            logger.debug("time at max speed: $speedTime s")
            logger.debug("acc time + dec time: $notSpeedTime s")
            speedTime + notSpeedTime
        }
    }
    
    // TODO 默认的操作时间该设为多少？
    private fun getOperationTime(c: MovementCommandReq) = VehicleService.getVehicle(vehicleName).properties
        .filterKeys { it == "robot:operation:${c.operation}" }
        .values.firstOrNull()?.toLong() ?: DEFAULT_OPERATION_TIME
    
    private fun report() {
        synchronized(this) {
            val noCmd = movementCommands.isEmpty()
            val state = if (noCmd && !moving) (if (position?.startsWith("CP") == true) Vehicle.State.CHARGING else Vehicle.State.IDLE) else Vehicle.State.EXECUTING
            this.state = state
            val execState = if (noCmd && !moving) VehicleExecutingState.NONE else VehicleExecutingState.MOVING
            val energyLevel = VehicleService.getVehicle(vehicleName).properties["robot:energy"]?.toIntOrNull()
                ?: 80
            this.energyLevel = energyLevel
            val req = VehicleStatus(energyLevel, emptyList(), null, position, state.name, execState.name)
            write(Message("StatusReport", arguments = mapOf("report" to req, "vehicle" to vehicleName)), tcpClient::write)
        }
    }

    private fun details() {
        val x: Long
        val y: Long
        if (position != null) {
            val point = PlantModelService.getPointIfNameIs(position!!)
            x = point?.position?.x ?: 0
            y = point?.position?.y ?: 0
        } else {
            x = 0
            y = 0
        }

        synchronized(this) {
            val req = mutableMapOf(
                "DI" to arrayOf(mapOf("id" to 0, "status" to true), mapOf("id" to 1, "status" to false)),
                "DO" to arrayOf(mapOf("id" to 0, "status" to false), mapOf("id" to 1, "status" to true)),
                "angle" to 1.5429,
                "battery_level" to this.energyLevel,
                "battery_temp" to 24.0,
                "blocked" to false,
                "pause" to paused,
                "brake" to false,
                "charging" to (state == Vehicle.State.CHARGING),
                "confidence" to 0.9792,
                "current_map" to "TEST-01",
                "current_station" to (position ?: ""),
                "dispatch_mode" to 1,
                "dispatch_state" to 3,
                "emergency" to false,
                "map_version" to "v1.0.6",
                "model" to "AMB-300",
                "model_version" to "v3.0.2",
                "odo" to 25875.977,
                "time" to 830713,
                "today_odo" to 323.682,
                "total_time" to 366105601,
                "vehicle_id" to vehicleName,
                "version" to "v3.2.3",
                "voltage" to 26.103,
                "vx" to -0.0,
                "vy" to -0.0,
                "w" to -0.0,
                "x" to (x / 1000.0),
                "y" to (y / 1000.0)
            )
            if (routeConfig.reportConfig.includedFields.contains("jack")) {
                val jack = mapOf("jack_state" to jackState)
                req["jack"] = jack as Serializable
            }
            write(Message("Details", arguments = req), tcpClient::write)
        }
    }
    
    @Synchronized
    fun dispose() {
        cmdExecutor.shutdown()
        reportExecutor.shutdown()
        if (mockVehicleDetails) {
            detailsExecutor.shutdown()
        }
    }
    
    private fun parsePkgLen(buffer: ByteArrayBuffer): Int {
        if (buffer.validEndIndex - buffer.validStartIndex < tcpPkgHead.size + pkgLenWidth) return -1
        val byteBuffer = ByteBuffer.wrap(buffer.buffer, buffer.validStartIndex + tcpPkgHead.size, pkgLenWidth)
        byteBuffer.order(ByteOrder.BIG_ENDIAN)
        return byteBuffer.short.toInt() + tcpPkgHead.size + pkgLenWidth // 要包含头
    }
    
    private fun onPkg(buffer: ByteArrayBuffer, len: Int) {
        val str = String(
            buffer.buffer,
            buffer.validStartIndex + tcpPkgHead.size + pkgLenWidth,
            len - tcpPkgHead.size - pkgLenWidth,
            StandardCharsets.UTF_8
        )
        val json = mapper.readTree(str)
        // LOG.debug("$type: $str")
        when (json.get("type").asText()) {
            "Pause" -> pauseVehicle(json)
            "Movements" -> movements(json)
            "ClearMovements" -> clearAllMovementCommands()
        }
    }
    
    private fun movements(json: JsonNode) {
        synchronized(this) {
            val node = json["arguments"]["cmds"]
            val cmd = mapper.treeToValue(node, MovementCmdGroup::class.java)
            logger.info("Sim $vehicleName get new movements ${cmd.commands}.")
            val cmds = cmd.commands
            cmds.forEach { movementCommands.add(it) }

            val arguments = mapOf("ackFlowNo" to json["flowNo"], "vehicle" to vehicleName)
            val ackMsg = Message("AckMovement", arguments = arguments)
            write(ackMsg, tcpClient::write)
        }
    }
    
    @Synchronized
    fun pauseVehicle(json: JsonNode) {
        paused = json["arguments"]["pause"].asBoolean()
    }
    
    @Synchronized
    fun clearAllMovementCommands() {
        synchronized(this) {
            logger.info("Sim $vehicleName clear movements.")
            movementCommands.clear()
        }
    }
    
    companion object {
        const val DEFAULT_ACC = 1000.0
        const val DEFAULT_DEC = 1000.0
        const val DEFAULT_SPEED = 1000.0
        const val DEFAULT_OPERATION_TIME = 0L
    }
    
}