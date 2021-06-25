package com.seer.srd.handler.route

import com.seer.srd.route.service.PlantModelService
import io.javalin.http.Context
import java.lang.IllegalArgumentException

fun handleSetObjectProperty(ctx: Context) {
    val name = ctx.pathParam("name")
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.header("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, DELETE")
    val key = ctx.queryParam("key") ?: throw IllegalArgumentException("Param key is missing")
    val value = ctx.queryParam("value")
    if (value == null) {
        PlantModelService.removeProperty(name, key)
    }
    else {
        PlantModelService.setProperties(name, mapOf(key to value))
    }
    ctx.status(200)
}

fun handleGetObjectProperties(ctx: Context) {
    val name = ctx.pathParam("name")
    ctx.header("Access-Control-Allow-Origin", "*")
    ctx.header("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, DELETE")
    ctx.json(PlantModelService.getProperties(name))
    ctx.status(200)
}