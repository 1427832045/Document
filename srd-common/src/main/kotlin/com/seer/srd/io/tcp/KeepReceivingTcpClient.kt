package com.seer.srd.io.tcp

import com.seer.srd.BusinessError
import com.seer.srd.scheduler.backgroundCacheExecutor
import org.slf4j.LoggerFactory
import java.net.Socket

class KeepReceivingTcpClient(
    private val host: String,
    private val port: Int,
    private val pkgExtractor: PkgExtractor,
    private val reConnectingDelay: Long = 2000L
) {

    private var socket: Socket? = null

    private var inputStreamToPkg: InputStreamToPkg? = null

    private var closed = false

    private var error = false

    private val logger = LoggerFactory.getLogger(KeepReceivingTcpClient::class.java)


    init {
        backgroundCacheExecutor.submit { doInit() }
    }

    @Synchronized
    fun close() {
        closed = true
        inputStreamToPkg?.stop()
        socket?.close()
    }

    @Synchronized
    fun write(bytes: ByteArray, flush: Boolean = false) {
        val socket = doInit() ?: throw BusinessError("Failed to build a tcp client $host:$port")
        try {
            socket.getOutputStream().write(bytes)
            if (flush) socket.getOutputStream().flush()
        } catch (e: Exception) {
            onError(e)
        }
    }

    @Synchronized
    private fun doInit(): Socket? {
        if (socket != null) return socket
        return try {
            val socket = Socket(host, port)
            this.socket = socket
            socket.keepAlive = true
            val inputStreamToPkg =
                InputStreamToPkg(socket.getInputStream(), 1024 * 10, this.pkgExtractor, this::onError)
            inputStreamToPkg.start()
            this.inputStreamToPkg = inputStreamToPkg
            error = false
            socket
        } catch (e: Exception) {
            onError(e)
            null
        }
    }

    @Synchronized
    private fun onError(e: Exception) {
        if (!error) logger.error("tcp client error", e) // 不会重复打错误日志
        error = true
        try {
            socket?.close()
        } catch (e: Exception) {
            //
        }
        socket = null
        inputStreamToPkg?.stop()

        if (closed) return

        logger.info("reconnect tcp $host:$port")
        Thread.sleep(reConnectingDelay)
        doInit()
    }

    fun isError(): Boolean {
        return error
    }

}