package com.seer.srd.siemensCd_1

import com.seer.srd.Application
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.setVersion
import com.seer.srd.siemensCd_1.ExtraComponent.extraComponents

fun main() {
    setVersion("西门子成都-地牛", "1.0.2")

    Application.initialize()

    registerRobotTaskComponents(extraComponents)

    Services.init()
    Application.start()
}

object SiemensCd1App {

    fun init() {

    }
}