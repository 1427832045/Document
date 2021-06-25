package com.seer.srd.huicang

import com.seer.srd.Application
import com.seer.srd.huicang.CustomComponent.customComponent
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.robottask.registerHttpResponseDecorator
import com.seer.srd.setVersion
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(HcApp::class.java)

object HcApp {

    fun init() {
        setVersion("l2", "3.0.15.1")

        registerHttpResponseDecorator("custom", customHttpResDecorator)

        registerRobotTaskComponents(httpClientComponents)
        registerRobotTaskComponents(customComponent)

        registerDefaultHttpHandlers()

        Application.initialize()
    }
}

fun main() {
    logger.debug("released on 2020-10-21.")
    HcApp.init()
    Application.start()
}