package com.seer.srd.tomi

import com.seer.srd.Application
import com.seer.srd.eventbus.EventBus
import com.seer.srd.setVersion

fun main() {
    Application.initialize()
    TomiApp.init()
    Application.start()
}

object TomiApp {
    fun init() {
        setVersion("Tomi", "1.0.2")
        Handlers.registerHandlers()

        EventBus.robotTaskUpdatedEventBus.add(EventHandlers::onRobotTaskDispatched)
    }
}