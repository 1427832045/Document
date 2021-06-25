package com.seer.srd.beizisuo.plc

import com.seer.srd.beizisuo.CUSTOM_CONFIG
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object MyTcpServer {

  private val logger = LoggerFactory.getLogger(MyTcpServer::class.java)

  val socketMap: MutableMap<String, MyTcpProcessor> = ConcurrentHashMap()

  @Volatile
  private var initialized = false

  private lateinit var server: ServerSocket

  private lateinit var executor: ExecutorService

  fun init() {
    if (initialized) return
    socketMap.clear()
    executor = Executors.newCachedThreadPool()
    server = ServerSocket(CUSTOM_CONFIG.tcpPort)

    initialized = true

    executor.submit {
      while (initialized) {
        val connection = server.accept()
        logger.debug("got connection")
        executor.submit { MyTcpProcessor(connection) }
      }
    }

  }

  fun dispose() {
    if (!initialized) return
    initialized = false

    executor.shutdown()
    server.close()

    socketMap.values.forEach { it.dispose() }
    socketMap.clear()
  }

  fun write(addr: String, bytes: ByteArray, flush: Boolean = false) {
    val processor = socketMap[addr] ?: return
    val os = processor.socket.getOutputStream()
    os.write(bytes)
    if (flush) os.flush()
  }
}