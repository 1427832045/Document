package com.seer.srd.lps.ur

import com.seer.srd.lps.CUSTOM_CONFIG
import com.seer.srd.scheduler.backgroundCacheExecutor
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.net.ServerSocket
import java.util.concurrent.*

object UrTcpServer {
    
    private val logger = LoggerFactory.getLogger(UrTcpServer::class.java)
    
    // 料车上存储位对应mag编号
    val siteToMagMap: MutableMap<String, String> = ConcurrentHashMap()
    
    @Volatile
    private var initialized = false
    
    private lateinit var server: ServerSocket
    
    fun init() {
        if (initialized) return
        initialized = true
        siteToMagMap.clear()
        server = ServerSocket(CUSTOM_CONFIG.tcpPort)
        backgroundCacheExecutor.submit {
            logger.debug("submit")
            while (initialized) {
                try {
                    val urClient = server.accept()
                    logger.debug("got ur connection")
                    UrTcpProcessor(urClient)
                } catch (e: Exception) {
                    logger.error("${e.printStackTrace()}")
                }
            }
        }
    }
    
    fun dispose() {
        logger.debug("try to dispose ur server...")
        if (!initialized) return
        initialized = false
        server.close()
        logger.debug("dispose ur server success...")
    }
    
}