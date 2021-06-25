package com.seer.srd.handler.route

import com.seer.srd.Error400
import com.seer.srd.SystemError
import com.seer.srd.route.kernelExecutor
import com.seer.srd.route.service.PlantModelService
import io.javalin.http.Context
import org.opentcs.components.kernel.services.RouterService
import org.opentcs.kernel.getInjector
import java.lang.IllegalArgumentException

fun handleRouteGetPlantModel(ctx: Context) {
    val modelXml = PlantModelService.getModelXml()
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.json(modelXml)
}

fun handleRoutePostPlantModel(ctx: Context) {
    val data = ctx.body()
    if (data.isEmpty()) throw Error400("EmptyReqBody", "请求内容为空")
    PlantModelService.updatePlantModel(data)
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.status(200)
}

fun handleLockPath(ctx: Context) {
    val name = ctx.pathParam("name")
    val value = ctx.queryParam("newValue")
    if (value.isNullOrBlank()) {
        throw IllegalArgumentException("Param 'newValue' is missing.")
    }
    val lowerValue = value.toLowerCase()
    if (lowerValue != "true" && lowerValue != "false") {
        throw IllegalArgumentException("Param 'newValue' is illegal: $value.")
    }
    val newValue = lowerValue == "true"

    val injector = getInjector() ?: throw SystemError("No Injector")
    val routerService = injector.getInstance(RouterService::class.java)
    kernelExecutor.submit { routerService.updatePathLock(name, newValue) }.get()
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.header("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, DELETE")
    ctx.status(200)
}