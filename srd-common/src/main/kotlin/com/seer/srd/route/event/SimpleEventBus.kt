package com.seer.srd.route.event

import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.CopyOnWriteArraySet

private val LOG = LoggerFactory.getLogger(SimpleEventBus::class.java)

class SimpleEventBus : EventBus {

    private val listeners: MutableSet<EventHandler> = CopyOnWriteArraySet()

    override fun onEvent(event: Any?) {
        try {
            for (listener in listeners) listener.onEvent(event)
        } catch (exc: Exception) {
            LOG.warn("Exception thrown by event handler", exc)
        }
    }

    override fun subscribe(listener: EventHandler) {
        Objects.requireNonNull(listener, "listener")
        listeners.add(listener)
    }

    override fun unsubscribe(listener: EventHandler) {
        Objects.requireNonNull(listener, "listener")
        listeners.remove(listener)
    }
}