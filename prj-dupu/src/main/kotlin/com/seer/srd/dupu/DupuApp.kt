package com.seer.srd.dupu

import com.seer.srd.Application
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.setVersion

object DupuApp {

    @Volatile
    var autoCharge = true

    fun init() {
        setVersion("dupu", "1.0.4")

        registerRobotTaskComponents(extraComponents)

        registerExtraHandler()

        Application.initialize()
    }

}


fun main() {

    DupuApp.init()

    Application.start()

}




