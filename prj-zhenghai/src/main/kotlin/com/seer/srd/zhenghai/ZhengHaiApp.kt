package com.seer.srd.zhenghai

import com.seer.srd.Application
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.setVersion
import com.seer.srd.zhenghai.CustomComponent.customComponents
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

object ZhengHaiApp {

    fun init() {
        setVersion("ZhengHai", "1.0.1")

        registerRobotTaskComponents(customComponents)
        registerCustomHttpHandlers()
        Application.initialize()
    }
}

fun main() {
    val logger = LoggerFactory.getLogger(ZhengHaiApp::class.java)
    try {
        ZhengHaiApp.init()
        Application.start()
    } catch (e: Exception) {
        logger.error(
            "\n>>>>>>>>>>>>>>>> ERROR OCCURRED!!! <<<<<<<<<<<<<<<<< " +
                "\n${e} " +
                "\n>>>>>>>>>>>>>>>> ERROR OCCURRED!!! <<<<<<<<<<<<<<<<<"
        )
        exitProcess(0)
    }
}
