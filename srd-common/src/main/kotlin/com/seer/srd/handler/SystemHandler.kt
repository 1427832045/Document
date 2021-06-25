package com.seer.srd.handler

import com.seer.srd.*
import com.seer.srd.recover.RecoverAll
import io.javalin.http.Context

object SystemHandler {
    
    fun handleGetSystemVersions(ctx: Context) {
        ctx.json(getVersions())
    }
    
    fun handleGetSystemStatus(ctx: Context) {
        val status = mapOf(
            "taskCreatingPaused" to isTaskCreatingPaused(),
            "taskProcessingPaused" to isTaskProcessingPaused()
        )
        ctx.json(status)
    }
    
    fun handleSetSystemStatus(ctx: Context) {
        val req = ctx.bodyAsClass(SetSystemStatusReq::class.java)
        
        if (req.taskCreatingPaused != null) setTaskCreatingPaused(req.taskCreatingPaused)
        if (req.taskProcessingPaused != null) setTaskProcessingPaused(req.taskProcessingPaused)
        
        ctx.status(204)
    }
    
    fun handleRecoverAll(ctx: Context) {
        RecoverAll.recoverAll()
        ctx.status(204)
    }
}

class SetSystemStatusReq(
    val taskCreatingPaused: Boolean? = null,
    val taskProcessingPaused: Boolean? = null
)