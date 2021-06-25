package com.seer.srd.device.converter

import java.util.concurrent.ConcurrentHashMap

data class HttpToModbusTcpConverterConfig(
    // 每个200ms进行一次读或者写操作
    var pollingPeriod: Long = 200L,

    // 一段时间内为收到新的读\写请求，就主动断开和slave的连接
    var delayForForceDisconnect: Long = 5 * 60
)

object HttpToModbusTcpService {
    private val managers: MutableMap<String, HttpToModbusTcpConverterManager> = ConcurrentHashMap()

    private fun getManager(req: HttpToModbusTcpRequestBody): HttpToModbusTcpConverterManager {
        val key = "${req.ip}:${req.port}"
        var manager = managers[key]
        if (manager == null) {
            manager = HttpToModbusTcpConverterManager(req)
            managers[key] = manager
        }
        return manager
    }

    fun listManagers(): List<String> {
        return managers.keys.toList()
    }

    fun read(req: HttpToModbusTcpRequestBody): HttpToModbusTcpResponseBody {
        return getManager(req).doRead(req)
    }

    fun write(req: HttpToModbusTcpRequestBody): HttpToModbusTcpResponseBody {
        return getManager(req).write(req)
    }
}