package com.seer.srd.handler.device

import com.seer.srd.device.pager.PagerService
import io.javalin.http.Context

object PagerHandler {

    fun handleResetPager(ctx: Context) {
        val name = ctx.pathParam("name")
        PagerService.resetPagerByName(name)
    }

    fun handleListPagerDetails(ctx: Context) {
        ctx.json(PagerService.listPagerDetails())
    }

    fun handleGetPagerDetails(ctx: Context) {
        val name = ctx.pathParam("name")
        ctx.json(PagerService.listPagerDetails().filter { name == it.config.name })
    }

}