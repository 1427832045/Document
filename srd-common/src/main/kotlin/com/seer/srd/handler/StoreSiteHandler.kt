package com.seer.srd.handler

import com.seer.srd.storesite.*
import com.seer.srd.storesite.StoreSiteService.listStoreSites
import io.javalin.http.Context

fun handleListStoreSite(ctx: Context) {
    ctx.json(listStoreSites())
}

class UpdateStoreSites(
    var siteIds: List<String> = emptyList(),
    var update: MutableMap<String, Any> = HashMap()
)

fun handleUpdateInBatch(ctx: Context) {
    val req = ctx.bodyAsClass(UpdateStoreSites::class.java)

    if (req.update.containsKey("filled")) {
        StoreSiteService.changeSitesFilledByIds(
            req.siteIds, req.update["filled"] == 1 || req.update["filled"] == true, "FromUI"
        )
    }
    if (req.update.containsKey("locked")) {
        StoreSiteService.changeSitesLockedByIds(
            req.siteIds, req.update["locked"] == 1 || req.update["locked"] == true, "",
            "FromUI"
        )
    }
    if (req.update.containsKey("content")) {
        val content = req.update["content"] as String
        for (id in req.siteIds) StoreSiteService.setSiteContent(id, content, "FromUI")
    }
    ctx.status(204)
}

fun handleGetStoreSiteConfig(ctx: Context) {
    ctx.json(getStoreConfig())
}

fun handleResetStoreSiteConfig(ctx: Context) {
    val def = ctx.bodyAsClass(StoreConfig::class.java)
    changeStoreConfig(def)
    ctx.status(204)
}
