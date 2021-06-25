package com.seer.srd.intelCD

import com.seer.srd.Application
import com.seer.srd.intelCD.customDoorHandler.registerCustomDoorHttpHandlers
import com.seer.srd.setVersion
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(IntelCDApp::class.java)

object IntelCDApp {

    fun init() {
        setVersion("l2", "3.0.18.0")
        registerCustomDoorHttpHandlers()

        Application.initialize()
    }
}

fun main() {
    logger.debug("released on 2020-12-29.")
    IntelCDApp.init()
    Application.start()
}