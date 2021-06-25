package com.seer.srd.device.converter

import com.seer.srd.CONFIG
import com.seer.srd.device.charger.toPositive
import com.seer.srd.scheduler.GlobalTimer
import com.seer.srd.scheduler.backgroundFixedExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.Exception
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class HttpToTcpRequestBody(
    val ip: String,

    val port: Int,

    val timeOut: Long,

    val socketWord: String
)

data class HttpToTcpResponseBody(
    // 0: 请求成功
    // 1: 请求失败
    val success: Int,

    // 请求成功时，message的内容为“请求成功”
    // 请求失败时，message的内容为失败的原因
    val message: String,

    // 请求成功时，返回相机数据；请求失败时，内容为空
    val cameraResponse: String
)

class HttpToTcpSocketConverterManager(private val reqBody: HttpToTcpRequestBody) {

    private val logger = LoggerFactory.getLogger(HttpToTcpSocketConverterManager::class.java)

    private val mark = "HttpToTcpSocket[${reqBody.ip}:${reqBody.port}]"

    private val delayForForceDisconnect = CONFIG.httpToTcpConverterConfig.delayForForceDisconnect

    private var client: ConverterAioTcpClient? = null

    private var rebuilding = false

    private fun logInfo(message: String) {
        logger.info("$mark: $message")
    }

    private var latestRequestOn: Instant? = null

    private fun updateLatestRequestOn() {
        latestRequestOn = Instant.now()
    }

    private val timer: ScheduledFuture<*>

    init {
        timer = GlobalTimer.executor.scheduleAtFixedRate(
            this::disconnectIfTimedOut,
            2000,
            CONFIG.httpToTcpConverterConfig.pollingPeriod,
            TimeUnit.MILLISECONDS
        )
    }

    private fun disconnectIfTimedOut() {
        // 规定时间内，如果没有接收到当前 helper 的读写请求，就断开连接
        if (latestRequestOn == null) return
        if (toPositive(Duration.between(latestRequestOn, Instant.now()).toSeconds()) > delayForForceDisconnect) {
            logInfo("断开连接：长时间未收到新的请求... ")
            close()
            latestRequestOn = null
        }
    }

    private fun rebuild(reason: String) {
        if (rebuilding) {
            logger.debug("$mark has been rebuilding, so return.")
            return
        }
        rebuilding = true

        backgroundFixedExecutor.submit {
            try {
                // 重连之前，一定要先 close
                close()
                runBlocking {
                    var rebuildCount = 0
                    while (true) {
                        logInfo("rebuilding tcp clients, reason: $reason, rebuildCount=$rebuildCount, rebuilding=$rebuilding")
                        rebuildCount++
                        if (null == latestRequestOn) {
                            logInfo("stop rebuild because no more request beyond ${delayForForceDisconnect}ms")
                            break
                        }
                        try {
                            client = ConverterAioTcpClient(reqBody.ip, reqBody.port)  // 如果连接失败就会一直连接
                            return@runBlocking
                        } catch (e: IOException) {
                            logger.error("IOException while rebuilding clients for $mark", e)
                            close()
                            delay(1000)
                        } catch (e: Exception) {
                            logger.error("Error rebuilding", e)
                            close()
                            delay(1000)
                        }
                    }
                }
            } catch (e: InterruptedException) {
                logger.error("Rebuilding for $mark, but interrupted")
            } finally {
                rebuilding = false
            }
        }
    }

    private fun close() {
        client?.close()
    }

    private fun getClient(): ConverterAioTcpClient {
        val client1 = client
        if (client1 != null) return client1
        rebuild("$mark get client failed!")
        throw Error("正在连接服务端，请稍后再尝试...")
    }

    fun request(socketWord: String, timeOut: Long, remoteAddr: String): HttpToTcpResponseBody {
        return try {
            updateLatestRequestOn()
            responseForSuccess1(getClient().requestBlocking(socketWord, timeOut))
        } catch (e: Exception) {
            logger.error("$mark: request from $remoteAddr failed; ", e)
            rebuild("request failed!")
            responseWithErrorMessage1(e.toString())
        }
    }
}

fun responseForSuccess1(cameraResponse: String): HttpToTcpResponseBody {
    return HttpToTcpResponseBody(0, "请求成功", cameraResponse)
}

fun responseWithErrorMessage1(reason: String): HttpToTcpResponseBody {
    return HttpToTcpResponseBody(1, "请求失败： $reason", "")
}