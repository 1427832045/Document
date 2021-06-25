package com.seer.srd.nanruijibao

import com.seer.srd.Application
import com.seer.srd.nanruijibao.ComComponent.comComponents
import com.seer.srd.nanruijibao.CustomUtil.checkWorkTypesUnique
import com.seer.srd.nanruijibao.handlers.registerDefaultHttpHandlers
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.setVersion
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(NanRuiJiBaoApp::class.java)

object NanRuiJiBaoApp {

    fun init() {
        setVersion("南瑞继保", "1.0.7")

        registerRobotTaskComponents(comComponents)

        registerDefaultHttpHandlers()

        Application.initialize()

        checkWorkTypesUnique()
    }
}

fun main() {
    try {
        // logger.debug("updated on 2021-01-22 17:00:00 .")
        NanRuiJiBaoApp.init()
        Application.start()
    } catch (e: Exception) {
        logger.error(
            "\n>>>>>>>>>>>>>>>> ERROR OCCURRED!!! <<<<<<<<<<<<<<<<< " +
                "\n${e.message} " +
                "\n>>>>>>>>>>>>>>>> ERROR OCCURRED!!! <<<<<<<<<<<<<<<<<"
        )
        throw e
    }
}