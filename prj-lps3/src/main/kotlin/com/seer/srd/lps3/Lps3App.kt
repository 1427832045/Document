package com.seer.srd.lps3

import com.mongodb.client.model.Accumulators.first
import com.mongodb.client.model.Accumulators.last
import com.seer.srd.Application
import com.seer.srd.BusinessError
import com.seer.srd.I18N
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventbus.EventBus
import com.seer.srd.eventlog.VehicleStateTrace
import com.seer.srd.http.Handlers
import com.seer.srd.http.HttpServer.noAuth
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.route.getVehicleDetailsByName
import com.seer.srd.route.service.VehicleService
import com.seer.srd.setVersion
import com.seer.srd.stats.StatAccount
import com.seer.srd.stats.statAccounts
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

object Lps3App {

  private val logger = LoggerFactory.getLogger(Lps3App::class.java)

  private val executor = Executors.newSingleThreadExecutor()

  @Volatile
  private var lastFinishedTask: String? = null

  private val extraComponent = listOf(
      TaskComponentDef(
          "extra", "LPS3:calling", "呼叫电梯", "", false, listOf(
          TaskComponentParam("currentFloor", "AGV当前所在楼层", "int"),
          TaskComponentParam("liftName", "电梯名称", "string")
      ), false) { component, ctx ->
        val currentFloor = parseComponentParamValue("currentFloor", component, ctx) as Int
        val name = parseComponentParamValue("liftName", component, ctx) as String
        when (currentFloor) {
          1 -> {
            val lift = LiftService.getLiftByName(name)
            lift.up()
          }
          2 -> {
            val lift = LiftService.getLiftByName(name)
            lift.down()
          }
          else -> throw BusinessError("No such floor $currentFloor")
        }
      },
      TaskComponentDef(
          "extra", "LPS3:canEnter", "是否可以进电梯", "", false, listOf(
          TaskComponentParam("currentFloor", "AGV当前所在楼层", "int"),
          TaskComponentParam("liftName", "电梯名称", "string")
      ), false) { component, ctx ->
        val currentFloor = parseComponentParamValue("currentFloor", component, ctx) as Int
        val name = parseComponentParamValue("liftName", component, ctx) as String
        var time = 0
        while (true) {
          time++
          val lift = LiftService.getLiftByName(name)
          val pass = lift.canPass(currentFloor, "进:${currentFloor}楼开门到位信号")
          if (pass) break
          if (time > CUSTOM_CONFIG.readLiftTimes) {
            ctx.task.persistedVariables["toWait"] = true
            break
          }
          Thread.sleep(CUSTOM_CONFIG.liftStatusPollingPeriod)
        }
      },
//      TaskComponentDef(
//          "extra", "enter", "进电梯", "", false, listOf(
//          TaskComponentParam("currentFloor", "AGV当前所在楼层", "int"),
//          TaskComponentParam("liftName", "电梯名称", "string")
//      ), false) { component, ctx ->
//        val currentFloor = parseComponentParamValue("currentFloor", component, ctx) as Int
//        val name = parseComponentParamValue("liftName", component, ctx) as String
//      },
      TaskComponentDef(
          "extra", "LPS3:canExit", "等待可以出电梯", "", false, listOf(
          TaskComponentParam("currentFloor", "AGV当前所在楼层", "int"),
          TaskComponentParam("liftName", "电梯名称", "string")
      ), false) { component, ctx ->
        val currentFloor = parseComponentParamValue("currentFloor", component, ctx) as Int
        val name = parseComponentParamValue("liftName", component, ctx) as String
        val lift = LiftService.getLiftByName(name)
        val pass = lift.canPass(currentFloor, "出:${currentFloor}楼开门到位信号")
        if (!pass) throw BusinessError("出电梯:等待$name${currentFloor}开门到位信号")
      },
      TaskComponentDef(
          "extra", "LPS3:inPlace", "AGV进入电梯到位", "", false, listOf(
          TaskComponentParam("liftName", "电梯名称", "string")
      ), false) { component, ctx ->
        val name = parseComponentParamValue("liftName", component, ctx) as String
        val lift = LiftService.getLiftByName(name)
        lift.inPlace()
      },
      TaskComponentDef(
          "extra", "LPS3:resetLiftDO", "电梯DO信号复位", "", false, listOf(
          TaskComponentParam("liftName", "电梯名称", "string")
      ), false) { component, ctx ->
        val name = parseComponentParamValue("liftName", component, ctx) as String
        val lift = LiftService.getLiftByName(name)
        lift.onExit()
      }
  )

  fun init() {
    setVersion("LPS3", "3.0.19.1")
    I18N.loadDict("/mat.csv")
    statAccounts.addAll(Collections.synchronizedList(listOf(
        StatAccount(
            "Odo", "VehicleStateTrace", "startOn",
            listOf(first("fValue", "\$odo"), last("lValue", "\$odo"))
        )
    )))
    Application.initialize()
  }
}

fun main() {
  Lps3App.init()

  Runtime.getRuntime().addShutdownHook(Thread {
    MyLiftService.dispose()
  })
}

