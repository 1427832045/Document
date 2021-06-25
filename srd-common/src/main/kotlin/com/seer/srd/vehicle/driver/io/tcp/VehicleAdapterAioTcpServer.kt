package com.seer.srd.vehicle.driver.io.tcp

import com.seer.srd.io.aioTcp.AioTcpHelper
import com.seer.srd.route.routeConfig
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.EOFException
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.ClosedChannelException
import java.util.concurrent.*

object VehicleAdapterAioTcpServer {
    private val logger = LoggerFactory.getLogger(VehicleAdapterAioTcpServer::class.java)
    private lateinit var channel: AsynchronousServerSocketChannel
    private const val logHead = "[aio-tcp-server]"
    private val connectionsMap: MutableMap<String, AioTcpServerConnection> = ConcurrentHashMap()
    private val connectionsIPMap: MutableMap<String, AioTcpServerConnection> = ConcurrentHashMap()
    private val connections: MutableList<AioTcpServerConnection> = CopyOnWriteArrayList()
    private lateinit var connectionExecutors: ExecutorService
    
    fun init() {
        channel =
            AsynchronousServerSocketChannel.open().bind(InetSocketAddress(routeConfig.vehicleAdapterTcpServerPort))
        connectionExecutors = Executors.newCachedThreadPool()
        GlobalScope.launch {
            while (true) {
                try {
                    val conn = AioTcpHelper.accept(channel)
                    val ip = (conn.channel.remoteAddress as InetSocketAddress).hostString
                    
                    if (connectionsIPMap.containsKey(ip)) {
                        logger.error("{} already has one connection, {}", logHead, ip)
                        conn.close()
                        continue
                    } // 一台机器人只能建立一个连接
                    
                    connectionsIPMap[ip] = conn
                    connections.add(conn)
                    logger.info("{} new connection {}", logHead, conn.channel.remoteAddress)
                    
                    try {
                        connectionExecutors.submit {
                            GlobalScope.launch {
                                try {
                                    conn.listen()
                                } catch (e: Exception) {
                                    if (e is EOFException) {
                                        logger.info("{} end of stream", logHead)
                                    } else {
                                        logger.info("{} listen {}", logHead, e)
                                    }
                                    // TODO 不close channel直接remove 会有问题吗？
                                    logger.info("{} closing connection {}")
                                    conn.close() // close channel
                                    connections.remove(conn) // remove connection
                                    connectionsIPMap.remove(ip)
                                }
                            }
                        }
                    } catch (e: RejectedExecutionException) {
                        logger.debug("ThreadPoolExecutor.isTerminated() = {}", connectionExecutors.isTerminated)
                        return@launch
                    }
                } catch (e: ClosedChannelException) {
                    logger.info("{} server closed", logHead)
                    return@launch
                }
            }
        }
    }
    
    fun dispose() {
        // dispose connections
        connections.forEach {
            logger.info("closing {}", it.vehicleName)
            it.close()
        }
        connections.clear()
        // shutdown thread pool
        if (this::connectionExecutors.isInitialized)
            connectionExecutors.shutdown()
        // dispose server
        if (this::channel.isInitialized)
            channel.close()
        // clear maps
        connectionsMap.clear()
        connectionsIPMap.clear()
    }
    
    
    fun setVehicleConnection(vehicleName: String, conn: AioTcpServerConnection) {
        connectionsMap[vehicleName] = conn
    }
    
    fun getChannel(vehicleName: String): AsynchronousSocketChannel? {
        return connectionsMap[vehicleName]?.channel
    }
    
}