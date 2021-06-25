package com.seer.srd.lps3

import com.seer.srd.Error400
import io.javalin.http.Context
import org.slf4j.LoggerFactory

object LiftHandler {

  private val logger = LoggerFactory.getLogger(MyLiftService::class.java)

  fun handleControlLift(ctx: Context) {
    val lift = ctx.pathParam("name")
    // go 的优先级高于 call。电梯在 isOccupy == true 时，将不响应 call 指令
    val manager = MyLiftService.managers[lift] ?: throw Error400("NoSuchLift", "No such lift $lift")

    when (val action = ctx.queryParam("action")) {
      "enable", "disable" ->
        logger.warn("No support to enable/disable lift")
      "call" ->
        manager.call(ctx.queryParam("floor") ?: throw Error400("MissingFloor", "Missing Floor"), "from api")
      "go" ->
        manager.go(ctx.queryParam("floor") ?: throw Error400("MissingFloor", "Missing Floor"), "from api")
      "occupy" ->
        // 设置电梯装维为占用，并且关上电梯门。
        manager.setOccupied(true, "from api")
      "unoccupy" ->
        // 解除电梯的占用状态，并且关上电梯门。
        manager.setOccupied(false, "from api")
      else ->
        throw Error400("UnsupportedAction", "Unsupported action: $action")

    }

    ctx.status(200)
  }

  /** 获取所有电梯状态 */
  fun handleListLiftsModels(ctx: Context) {
    val models = MyLiftService.listLiftsModels()
    ctx.json(models)
  }

  /** 获取指定的电梯状态 */
  fun handleGetLiftModel(ctx: Context) {
    val lift = ctx.pathParam("name")
    val manager = MyLiftService.managers[lift] ?: throw Error400("NoSuchLift", "No such lift $lift")
    val model = manager.getLiftModel()
    ctx.json(
        mapOf(
            "name" to model.name,
            "currentFloor" to model.currentFloor,
            "isOccupy" to model.isOccupy,
            "status" to model.status // todo case changed
        )
    )
  }

}