package com.seer.srd.baoligen

import com.seer.srd.Application
import com.seer.srd.baoligen.CustomComponent.customComponent
import com.seer.srd.baoligen.handler.registerDefaultHttpHandlers
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.setVersion
import com.seer.srd.util.loadConfig
import org.slf4j.LoggerFactory

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

private val logger = LoggerFactory.getLogger(BaoLiGenApp::class.java)

object BaoLiGenApp {

    fun init() {
        setVersion("l2", "1.0.1")

        registerRobotTaskComponents(customComponent)

        registerDefaultHttpHandlers()

        Application.initialize()
    }
}

fun main() {
    logger.debug("released on 2021-03-12.")
    BaoLiGenApp.init()
    Application.start()
}