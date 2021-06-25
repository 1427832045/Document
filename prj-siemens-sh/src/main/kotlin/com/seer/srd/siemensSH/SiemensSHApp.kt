package com.seer.srd.siemensSH

import com.seer.srd.Application
import com.seer.srd.eventbus.EventBus
import com.seer.srd.robottask.component.*
import com.seer.srd.setVersion
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess
import com.seer.srd.siemensSH.common.ComComponent
import com.seer.srd.siemensSH.customEventBus.CustomEventBus
import com.seer.srd.siemensSH.customStoreSite.CustomStoreSiteService
import com.seer.srd.siemensSH.phase1.Phase1Component
import com.seer.srd.siemensSH.phase2.Phase2Component

private val logger = LoggerFactory.getLogger(SiemensSHApp::class.java)

object SiemensSHApp {

    private val comComponents = ComComponent.comComponents
    private val phase1Components = Phase1Component.phase1Components
    private val phase2Components = Phase2Component.phase2Components

    fun init() {
        setVersion("siemens-sh", "1.0.18")

        registerRobotTaskComponents(comComponents)
        registerRobotTaskComponents(phase1Components)
        registerRobotTaskComponents(phase2Components)

        registerDefaultHttpHandlers()

        EventBus.robotTaskFinishedEventBus.add(CustomEventBus::onRobotTaskFinished)

        Application.initialize()

        CustomStoreSiteService.init()
    }
}

fun main() {
    try {
        SiemensSHApp.init()
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