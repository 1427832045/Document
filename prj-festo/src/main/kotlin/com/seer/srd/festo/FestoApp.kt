package com.seer.srd.festo

import com.seer.srd.Application
import com.seer.srd.BusinessError
import com.seer.srd.festo.phase4.Component4
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.setVersion
import com.seer.srd.util.loadConfig
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(FestoApp::class.java)

val festoConfig = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

object FestoApp {
    fun init() {
        setVersion("Festo", "1.0.0")
        registerDefaultHttpHandlers()
        registerRobotTaskComponents(Component4.components)
        Application.initialize()
        if (festoConfig.limitOfSentTasks4 <= 0)
            throw BusinessError("limit of sent tasks must bigger than 0 ...")
    }
}

fun main() {
    try {
        FestoApp.init()
        Application.start()
    } catch (e: Exception) {
        logger.error(
            "\n>>>>>>>>>>>>>>>> ERROR OCCURRED!!! <<<<<<<<<<<<<<<<< " +
                "\n启动服务器失败！！！" +
                "\n>>>>>>>>>>>>>>>> ERROR OCCURRED!!! <<<<<<<<<<<<<<<<<"
        )
        throw e
    }
}