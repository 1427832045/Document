package com.seer.srd.handler

import com.seer.srd.CONFIG
import com.seer.srd.Error400
import com.seer.srd.operator.OperatorInputDetails
import com.seer.srd.operator.OperatorOptionsSource
import io.javalin.http.Context

object OperatorHandler {
    
    fun handleOperatorConfig(ctx: Context) {
        val operator = CONFIG.operator
        if (operator != null) {
            ctx.json(operator)
        } else {
            ctx.json(mapOf("noConfig" to true))
        }
    }
    
    fun handleOperatorInputDetails(ctx: Context) {
        val name = ctx.pathParam("name")
        val input = ctx.queryParam("input") ?: throw Error400("NoInput", "No input")
        try {
            val detail = OperatorInputDetails.process(name, input)
            ctx.json(mapOf("detail" to detail))
        } catch (e: Exception) {
            throw Error400("FailedInputDetails", e.message)
        }
    }
    
    fun handleOperatorOptionsSource(ctx: Context) {
        val name = ctx.pathParam("name")
        try {
            val options = OperatorOptionsSource.read(name)
            ctx.json(options)
        } catch (e: Exception) {
            throw Error400("FailedReadOptions", e.message)
        }
    }
    
}