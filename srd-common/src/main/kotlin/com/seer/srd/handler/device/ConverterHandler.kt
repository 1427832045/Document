package com.seer.srd.handler.device

import com.seer.srd.device.converter.*
import com.seer.srd.device.converter.HttpToTcpSocketService.request
import io.javalin.http.Context
import org.slf4j.LoggerFactory

object ConverterHandler {
    private val logger = LoggerFactory.getLogger(ConverterHandler::class.java)

    fun handlerReadOrWriteModbusTcp(ctx: Context) {
        val requestBody = ctx.bodyAsClass(HttpToModbusTcpRequestBody::class.java)
        logger.debug("W&R ModbusTcp from [${ctx.req.remoteAddr}]: $requestBody")
        ctx.json(when (val funcNo = requestBody.functionNum) {
            "01", "02", "03", "04" -> HttpToModbusTcpService.read(requestBody)
            "05", "06", "0F", "10" -> HttpToModbusTcpService.write(requestBody)
            else -> unRecognizedFuncNo(funcNo)
        })
    }

    fun handlerListModbusTcpMasters(ctx: Context) {
        ctx.json(HttpToModbusTcpService.listManagers())
    }

    fun handlerReadOrWriteTcpSocket(ctx: Context) {
        val requestBody = ctx.bodyAsClass(HttpToTcpRequestBody::class.java)
        val remoteAddr = ctx.req.remoteAddr
        logger.debug("Tcp request from [${remoteAddr}]: $requestBody")
        ctx.json(request(requestBody, remoteAddr))
    }

    fun handlerListTcpSockets(ctx: Context) {
        ctx.json(HttpToTcpSocketService.listManagers())
    }
}