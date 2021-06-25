package com.seer.srd.vehicle.driver.io.http

import com.seer.srd.CONFIG
import com.seer.srd.handler.route.driver.VehicleSignIn
import com.seer.srd.route.VehicleCommAdapterIOType
import com.seer.srd.route.routeConfig
import com.seer.srd.route.service.PlantModelService
import com.seer.srd.route.service.VehicleService
import com.seer.srd.vehicle.driver.VehicleExecutingState
import com.seer.srd.vehicle.driver.io.VehicleStatus
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.vehicle.VehiclePersistable
import org.slf4j.LoggerFactory
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.seer.srd.db.MongoDBManager.collection
import org.litote.kmongo.findOne
import org.litote.kmongo.eq


private val LOG = LoggerFactory.getLogger("com.seer.srd.vehicle.driver.io")

val simulations: MutableMap<String, VehicleHttpSimulation> = ConcurrentHashMap()

private val srdHttpClient = buildRemote()

fun initVehicleHttpSimulations() {
    if (routeConfig.commAdapterIO != VehicleCommAdapterIOType.Http) return

    // 如数据库里面有mockPosition，就用数据库里面的mockPosition，否则用ppPoints
    val c = collection<VehiclePersistable>()
    val ppPoints = PlantModelService.getPlantModel().points.values.filter { it.name.startsWith("PP") }.toList()

    val vehicles = VehicleService.listVehicles()
    if (CONFIG.startFromDB) {
        vehicles.forEachIndexed { i, v ->
            val mockPosition = c.findOne(VehiclePersistable::id eq v.name)?.mockPosition ?: ""
            val ppPosition = if (i < ppPoints.size) ppPoints[i].name else ""
            val initPosition = if (mockPosition.isBlank()) ppPosition else mockPosition
            simulations[v.name] = VehicleHttpSimulation(v.name, initPosition)
        }
    } else {
        vehicles.forEachIndexed { i, v ->
            val ppPosition = if (i < ppPoints.size) ppPoints[i].name else ""
            simulations[v.name] = VehicleHttpSimulation(v.name, ppPosition)
        }
    }
}

fun disposeVehicleHttpSimulations() {
    if (routeConfig.commAdapterIO != VehicleCommAdapterIOType.Http) return
    simulations.values.forEach { it.dispose() }
    simulations.clear()
}

private fun buildRemote(): SrdHttpClient {
    val interceptor = HttpLoggingInterceptor()
    interceptor.level = HttpLoggingInterceptor.Level.NONE
    val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
    val retrofit = Retrofit.Builder()
            .client(client)
            .baseUrl("http://localhost:${CONFIG.httpPort}/api/")
            .addConverterFactory(JacksonConverterFactory.create())
            .build()
    return retrofit.create(SrdHttpClient::class.java)
}

interface SrdHttpClient {

    @PUT("vehicle/driver/{name}/status")
    fun reportStatus(@Path("name") name: String, @Body req: VehicleStatus): Call<Void>

    @POST("vehicle/driver/{name}/sign-in")
    fun signIn(@Path("name") name: String, @Body req: VehicleSignIn): Call<Void>

}

class VehicleHttpSimulation(
        val vehicleName: String,
        var position: String? = null
) {

    private var paused = false

    private var moving = false

    private val movementCommands: Queue<MovementCommandReq> = ConcurrentLinkedQueue()

    private val cmdExecutor = Executors.newSingleThreadScheduledExecutor()

    private val reportExecutor = Executors.newSingleThreadScheduledExecutor()

    init {
        signIn()

        cmdExecutor.submit {
            while (true) {
                try {
                    loop()
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    return@submit
                } catch (e: Exception) {
                    LOG.error("cmd executor", e)
                }
            }
        }
        reportExecutor.scheduleAtFixedRate(this::report, 3000, 1000, TimeUnit.MILLISECONDS)
    }

    private fun signIn() {
        val req = VehicleSignIn("http://localhost:${CONFIG.httpPort}/api/vehicle/http-simulation/")
        try {
            srdHttpClient.signIn(vehicleName, req).execute()
        } catch (e: Exception) {
            LOG.error("sign in", e)
            throw e
        }
    }

    private fun loop() {
        val cmd = synchronized(this) {
            if (paused) return
            val c = movementCommands.poll() ?: return
            moving = true
            c
        }
        Thread.sleep(2000)

        synchronized(this) {
            moving = false
            position = cmd.step
        }
    }

    private fun report() {
        val req = synchronized(this) {
            val noCmd = movementCommands.isEmpty()
            val state = if (noCmd && !moving) Vehicle.State.IDLE else Vehicle.State.EXECUTING
            val execState = if (noCmd && !moving) VehicleExecutingState.NONE else VehicleExecutingState.MOVING
            VehicleStatus(99, emptyList(), null, position, state.name, execState.name)
        }
        try {
            srdHttpClient.reportStatus(vehicleName, req).execute()
        } catch (e: Exception) {
            LOG.info("simulation, report", e)
        }
    }

    @Synchronized
    fun dispose() {
        cmdExecutor.shutdown()
        reportExecutor.shutdown()
    }

    @Synchronized
    fun acceptMovementCommands(commands: List<MovementCommandReq>) {
        commands.forEach { movementCommands.add(it) }
    }

    @Synchronized
    fun pauseVehicle() {
        paused = true
    }

    @Synchronized
    fun resumeVehicle() {
        paused = false
    }

    @Synchronized
    fun clearAllMovementCommands() {
        movementCommands.clear()
    }

}

