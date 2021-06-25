package com.seer.srd.beizisuo

import com.seer.srd.Application
import com.seer.srd.BusinessError
import com.seer.srd.beizisuo.plc.MyTcpServer
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer.handle
import com.seer.srd.http.ReqMeta
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.robottask.RobotTaskService
import com.seer.srd.robottask.buildTaskInstanceByDef
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.robottask.getRobotTaskDef
import com.seer.srd.setVersion
import com.seer.srd.storesite.StoreSiteService
import io.javalin.http.Context
import io.javalin.http.HandlerType
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main() {
    setVersion("BZS", "1.0.2")

    Application.initialize()

    BeiZiSuoApp.init()

    Application.start()
}

object BeiZiSuoApp {

    fun init() {
        registerRobotTaskComponents(ExtraComponent.extraComponents)
        MyTcpServer.init()
        Services.init()
        registerOnExit()
    }

    private fun registerOnExit() {
        Runtime.getRuntime().addShutdownHook(Thread {
            MyTcpServer.dispose()
        })
    }

}