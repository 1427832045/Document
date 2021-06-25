package com.seer.srd.pager

import com.seer.srd.scheduler.backgroundCacheExecutor
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object PagerTcpServer {

  private val logger = LoggerFactory.getLogger(PagerTcpServer::class.java)

  val pagerToSocketMap: MutableMap<String, PagerTcpProcessor> = ConcurrentHashMap()

  @Volatile
  private var initialized = false

  private lateinit var server: ServerSocket

  private lateinit var executor: ExecutorService

  fun init() {
    if (initialized) return
    pagerToSocketMap.clear()
    initialized = true
    server = ServerSocket(CUSTOM_CONFIG.tcpPort)
    executor = Executors.newCachedThreadPool()
    executor.submit {
      while (initialized) {
        try {
          val client = server.accept()
          logger.debug("got pager connection")
          PagerTcpProcessor(client)
        } catch (e: Exception) {
          logger.error("${e.printStackTrace()}")
        }
      }
    }
  }

  @Synchronized
  fun dispose() {
    logger.debug("try to dispose pager server...")
    if (!initialized) return
    initialized = false
    try {
      executor.shutdown()
      server.close()
      pagerToSocketMap.values.forEach { it.dispose() }
    } catch (e: Exception) {
      logger.error("dispose pager server error", e)
    } finally {
      logger.debug("dispose pager server success...")
    }
  }

}
