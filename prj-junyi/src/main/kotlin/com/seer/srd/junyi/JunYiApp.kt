package com.seer.srd.junyi

import com.seer.srd.Application
import com.seer.srd.junyi.handlers.registerDefaultHttpHandlers
import com.seer.srd.robottask.component.*
import com.seer.srd.setVersion
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger(JyApp::class.java)

object JyApp {

    private val extraComponents = ExtraComponents.extraComponents

    fun init() {
        setVersion("JunYi", "1.1.0")

        registerRobotTaskComponents(extraComponents)

        registerDefaultHttpHandlers()

        Application.initialize()
    }
}

fun main() {
    try {
        JyApp.init()
        Application.start()
    } catch (e: Exception) {
        logger.error(
            "\n>>>>>>>>>>>>>>>> ERROR OCCURRED!!! <<<<<<<<<<<<<<<<< " +
                "\n${e.message} " +
                "\n>>>>>>>>>>>>>>>> ERROR OCCURRED!!! <<<<<<<<<<<<<<<<<"
        )
        exitProcess(0)
    }
}