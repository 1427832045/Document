package com.seer.srd.vehicle.driver.io.tcp

import com.seer.srd.route.routeConfig
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object VehicleAdapterTcpServer {

    private val logger = LoggerFactory.getLogger("com.seer.srd.vehicle.driver.io.tcp")

     val vehicleToSocketMap: MutableMap<String, VehicleTcpProcessor> = ConcurrentHashMap()

    @Volatile
    private var initialized = false

    private lateinit var server: ServerSocket

    private lateinit var executor: ExecutorService

    fun init() {
        if (initialized) return
        vehicleToSocketMap.clear()
        executor = Executors.newCachedThreadPool()
        server = ServerSocket(routeConfig.vehicleAdapterTcpServerPort)

        initialized = true

        executor.submit {
            while (initialized) {
                val connection = server.accept()
                logger.debug("got connection")
                executor.submit { VehicleTcpProcessor(connection) }
            }
        }

    }

    fun dispose() {
        if (!initialized) return
        initialized = false

        executor.shutdown()
        server.close()

        vehicleToSocketMap.values.forEach { it.dispose() }
        vehicleToSocketMap.clear()
    }

}