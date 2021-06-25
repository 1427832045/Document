package com.seer.srd.vehicle.driver.io.http

import com.seer.srd.BusinessError
import com.seer.srd.vehicle.driver.io.AdapterIO
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.opentcs.drivers.vehicle.MovementCommand
import org.slf4j.LoggerFactory
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.util.concurrent.ConcurrentHashMap

private val LOG = LoggerFactory.getLogger("com.seer.srd.vehicle.driver.io")

private val vehiclesEndpointsMap: MutableMap<String, VehicleHttpClient> = ConcurrentHashMap()

fun updateVehicleEndpoint(vehicleName: String, baseUrl: String) {
    vehiclesEndpointsMap[vehicleName] =
        buildRemote(
            baseUrl,
            VehicleHttpClient::class.java
        )
}

private fun getVehicleHttpClient(vehicleName: String): VehicleHttpClient {
    return vehiclesEndpointsMap[vehicleName]
        ?: throw BusinessError("The Http endpoint of vehicle $vehicleName is unknown")
}

private fun <T> buildRemote(baseUrl: String, remoteInterface: Class<T>): T {
    val interceptor = HttpLoggingInterceptor()
    interceptor.level = HttpLoggingInterceptor.Level.NONE
    val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
    val retrofit = Retrofit.Builder()
        .client(client)
        .baseUrl(baseUrl)
        .addConverterFactory(JacksonConverterFactory.create())
        .build()
    return retrofit.create(remoteInterface)
}

class HttpAdapterIO(
    private val vehicleName: String,
    private val onAckMovementCommand: () -> Unit
) : AdapterIO() {

    override fun connectVehicle() {
        // do nothing
    }

    override fun disconnectVehicle() {
        // do nothing
    }

    override fun setVehiclePaused(paused: Boolean) {
        val client = getVehicleHttpClient(vehicleName)
        try {
            if (paused) client.pauseVehicle(vehicleName).execute()
            else client.resumeVehicle(vehicleName).execute()
        } catch (e: Exception) {
            LOG.error("setVehiclePaused", e)
        }
    }

    override fun sendMovementCommand(cmd: MovementCommand) {
        val req = SendMovementCommandReq(listOf(MovementCommandReq().toReq(cmd)))
        try {
            getVehicleHttpClient(vehicleName).sendMovementCommands(vehicleName, req).execute()
        } catch (e: Exception) {
            LOG.error("sendMovementCommand", e)
            return
        }
        onAckMovementCommand() // 立即ACK
    }

    override fun sendMovementCommands(cmds: List<MovementCommand>) {
        val req = SendMovementCommandReq(cmds.map {
            MovementCommandReq().toReq(it)
        })
        try {
            getVehicleHttpClient(vehicleName).sendMovementCommands(vehicleName, req).execute()
        } catch (e: Exception) {
            LOG.error("sendMovementCommands", e)
            return
        }
        onAckMovementCommand() // 立即ACK
    }

    override fun sendClearAllMovementCommands() {
        try {
            getVehicleHttpClient(vehicleName).clearAllMovementCommands(vehicleName).execute()
        } catch (e: Exception) {
            LOG.error("sendClearAllMovementCommands", e)
        }
    }

    override fun sendSafeClearAllMovementCommands(movementId: String?) {
        try {
            getVehicleHttpClient(vehicleName).safeClearAllMovementCommands(vehicleName).execute()
        } catch (e: Exception) {
            LOG.error("sendSafeClearAllMovementCommands", e)
        }
    }
}

interface VehicleHttpClient {

    @POST("{name}/movements")
    fun sendMovementCommands(@Path("name") name: String, @Body req: SendMovementCommandReq): Call<Void>

    @PUT("{name}/pause")
    fun pauseVehicle(@Path("name") name: String): Call<Void>

    @PUT("{name}/resume")
    fun resumeVehicle(@Path("name") name: String): Call<Void>

    @POST("{name}/clear-movements")
    fun clearAllMovementCommands(@Path("name") name: String): Call<Void>

    @POST("{name}/safe-clear-movements")
    fun safeClearAllMovementCommands(@Path("name") name: String): Call<Void>
}

class SendMovementCommandReq(
    var commands: List<MovementCommandReq> = emptyList()
)

class MovementCommandReq {

    var id: String = ""
    var sourcePoint: String = ""
    var step: String = ""
    var isFinalMovement = false
    var operation: String? = null
    var properties: MutableList<Map<String, String>> = ArrayList()

    fun toReq(cmd: MovementCommand): MovementCommandReq {
        id = cmd.id
        step = cmd.step.destinationPoint
        sourcePoint = cmd.step.sourcePoint ?: step
        isFinalMovement = cmd.isFinalMovement
        operation = cmd.operation
        cmd.properties
            .filter { entry -> !entry.key.startsWith("device:") }  // todo 为啥要过滤这个
            .forEach { entry -> properties.add(mapOf("key" to entry.key, "value" to entry.value)) }
        return this
    }

    override fun toString(): String {
        return "{id=$id, step=$step, sourcePoint=$sourcePoint, operation=$operation, properties=${properties.toString()}"
    }

}