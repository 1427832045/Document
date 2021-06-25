package com.seer.srd.handler

import com.seer.srd.http.getReqLang
import com.seer.srd.vehicle.VehicleUpgradeManager
import io.javalin.http.Context

object VehiclesUpgradeHandler {
    
    fun handleGetLatestTask(ctx: Context) {
        val task = VehicleUpgradeManager.getLatestTask()
        ctx.json(mapOf("task" to task))
    }
    
    fun handleStartNewTask(ctx: Context) {
        val lang = getReqLang(ctx)
        val req = ctx.bodyAsClass(VehicleUpgradeReq::class.java)
        VehicleUpgradeManager.start("/api/file/" + req.filePath, req.vehicles, lang)
        ctx.status(201)
    }
    
    fun handleAbortTask(ctx: Context) {
        val lang = getReqLang(ctx)
        VehicleUpgradeManager.abort()
        ctx.status(201)
    }
    
}

data class VehicleUpgradeReq(
    val filePath: String,
    val vehicles: List<String>
)