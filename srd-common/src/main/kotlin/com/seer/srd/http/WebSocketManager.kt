package com.seer.srd.http

import com.seer.srd.util.mapper
import com.seer.srd.vehicle.VehicleUpgradeManager
import com.seer.srd.vehicle.VehicleWebSocketManager
import io.javalin.websocket.WsContext
import io.javalin.websocket.WsHandler
import io.javalin.websocket.WsMessageContext
import org.eclipse.jetty.websocket.api.CloseException
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

object WebSocketManager {
    
    private val logger = LoggerFactory.getLogger(WebSocketManager::class.java)
    
    private val contexts = CopyOnWriteArrayList<WsContext>()
    
    // 广播，单线程排队执行，（尽量）保证时序，后面加流水号
    private val executor = Executors.newSingleThreadExecutor()
    
    fun onWebSocket(ws: WsHandler) {
        ws.onConnect { ctx ->
            // ctx.session.idleTimeout = 10000
            logger.debug("WebSocket got new client ${contexts.size} ${ctx.sessionId}")
            contexts.add(ctx)
        }
        ws.onClose { ctx ->
            logger.debug("WebSocket lost a client ${contexts.size} ${ctx.sessionId}")
            contexts.remove(ctx)
            VehicleWebSocketManager.onSocketClose(ctx.sessionId)
        }
        ws.onError { ctx ->
            val error = ctx.error()
            if (error is CloseException || error is TimeoutException) {
                logger.debug("WebSocket client timeout ")
            } else {
                logger.error("WebSocket got error", error)
            }
        }
        ws.onMessage(this::onMessage)
    }
    
    fun broadcastByWebSocket(event: String, content: Any? = null) {
        executor.submit {
            contexts.filter { it.session.isOpen }.forEach { session ->
                session.send(mapOf("event" to event, "content" to content))
            }
        }
    }
    
    private fun onMessage(ctx: WsMessageContext) {
        try {
            val messageStr = ctx.message()
            val messageTree = mapper.readTree(messageStr)
            when (messageTree["event"].asText()) {
                "VehicleSignIn" -> VehicleWebSocketManager.onSignIn(messageTree["content"], ctx.sessionId)
                "VehicleUpgradingProgress" -> VehicleUpgradeManager.onUpgrading(messageTree["content"])
            }
        } catch (e: Exception) {
            logger.error("on web socket message", e)
        }
    }
}

