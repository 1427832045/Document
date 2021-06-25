package com.seer.srd.handler

import com.seer.srd.CONFIG
import com.seer.srd.http.getReqLang
import com.seer.srd.robottask.getTaskPriorities
import com.seer.srd.route.routeConfig
import io.javalin.http.Context

fun handleGetUiConfig(ctx: Context) {
    val lang = getReqLang(ctx)
    val cc = CONFIG
    val rcc = routeConfig
    ctx.json(
        mapOf(
            "webSocket" to "direct",
            "robotTaskListExtraColumns" to cc.robotTaskListExtraColumns,
            "robotTaskListExtraFilters" to cc.robotTaskListExtraFilters,
            "taskPriorities" to getTaskPriorities(lang),
            "vehicleModels" to cc.vehicleModels,
            "newCommAdapter" to rcc.newCommAdapter,
            "commAdapterIO" to rcc.commAdapterIO,
            "vehicleSimulation" to rcc.vehicleSimulation,
            "synchronizer" to rcc.synchronizer,
            "httpPort" to cc.httpPort,
            "zoomInByScrollUp" to cc.zoomInByScrollUp,
            "menus" to emptyList<String>() // TODO menu visible
        )
    )
}