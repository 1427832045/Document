package com.seer.srd.route.event

interface EventHandler {
    fun onEvent(event: Any?)
}