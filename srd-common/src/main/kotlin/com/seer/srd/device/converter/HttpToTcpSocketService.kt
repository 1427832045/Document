package com.seer.srd.device.converter

import java.util.concurrent.ConcurrentHashMap

data class HttpToTcpConverterConfig(
    // 每个1000ms进行一次读或者写操作
    var pollingPeriod: Long = 1000L,

    // 一段时间内为收到新的请求，就主动断开和服务端的连接
    var delayForForceDisconnect: Long = 5 * 60,

    // aioTcpSocket 读取数据的缓存
    var bufferSize: Int = 1024 * 10
)

object HttpToTcpSocketService {

    private val managers: MutableMap<String, HttpToTcpSocketConverterManager> = ConcurrentHashMap()

    private fun getManager(req: HttpToTcpRequestBody): HttpToTcpSocketConverterManager {
        val key = "${req.ip}:${req.port}"
        var manager = managers[key]
        if (manager == null) {
            manager = HttpToTcpSocketConverterManager(req)
            managers[key] = manager
        }
        return manager
    }

    fun listManagers(): List<String> {
        return managers.keys.toList()
    }

    fun request(req: HttpToTcpRequestBody, remoteAddr: String): HttpToTcpResponseBody {
        return getManager(req).request(req.socketWord, req.timeOut, remoteAddr)
    }
}