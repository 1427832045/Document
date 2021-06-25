package com.seer.srd.aibo

import com.seer.srd.Application
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.setVersion

fun main() {
    Application.initialize()
    AiBoApp.init()
    Application.start()
}

//对象声明
object AiBoApp{

    fun init() {
        setVersion("AiBo", "1.0.0")
        registerRobotTaskComponents(ExtraComponent.extraComponent)
    }
}