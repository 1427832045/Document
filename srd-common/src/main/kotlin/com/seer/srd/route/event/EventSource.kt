package com.seer.srd.route.event

interface EventSource {
    fun subscribe(listener: EventHandler)
    fun unsubscribe(listener: EventHandler)
}