package com.seer.srd.handler

import com.seer.srd.db.MongoDBManager.getDatabase
import com.seer.srd.domain.PagingResult
import com.seer.srd.http.getPageNo
import com.seer.srd.http.getPageSize
import io.javalin.http.Context

object CustomDataHandler {

    fun handleListCustomData(ctx: Context) {
        val pageNo = getPageNo(ctx)
        val pageSize = getPageSize(ctx)
        val type = ctx.queryParam("type")
        val db = getDatabase()
        val c = db.getCollection("Ext$type")
        val total = c.countDocuments()
        val page = c.find()
            .limit(pageSize).skip((pageNo - 1) * pageSize)
            .toList()
        ctx.json(PagingResult(total, page, pageNo, pageSize))
    }

}