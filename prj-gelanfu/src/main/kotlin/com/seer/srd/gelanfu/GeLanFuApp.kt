package com.seer.srd.gelanfu

import com.seer.srd.Application
import com.seer.srd.eventbus.EventBus.robotTaskFinishedEventBus
import com.seer.srd.setVersion

fun main() {
    setVersion("l2", "3.0.0")

    Application.initialize()

    Application.start()

    QueryFtp.start()

    robotTaskFinishedEventBus.add(::onRobotTaskFinished)
}