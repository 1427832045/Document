package com.seer.srd.siemensSH.phase1

import com.seer.srd.BusinessError
import com.seer.srd.Error400
import com.seer.srd.siemensSH.MappingReqBody
import com.seer.srd.siemensSH.MaterialProductMappingService
import com.seer.srd.siemensSH.RemoveParams
import com.seer.srd.siemensSH.common.ComHandlers
import com.seer.srd.siemensSH.phase1.Phase1Utils.clearSiteContentIfUnlocked
import com.seer.srd.siemensSH.phase1.Phase1Utils.createEToStationTasksByWorkStationAndCode
import com.seer.srd.siemensSH.phase1.Phase1Utils.createTakeMatFromAE1ToAE3TasksByCode
import com.seer.srd.util.mapper
import io.javalin.http.Context
import org.slf4j.LoggerFactory
import java.time.Instant

object Phase1Handlers {
    private val logger = LoggerFactory.getLogger(Phase1Handlers::class.java)

    fun clearLockedSiteContentIfFilledWithMat(ctx: Context) {
        val bodyStr = ctx.body()
        logger.info("库位取货-清空货物码 body=$bodyStr")

        val body = mapper.readTree(ctx.body())

        val ws: String  // expected value like station2 or station3 or station4
        try {
            ws = body["workStation"].asText()
        } catch (e: Exception) {
            throw BusinessError("请选择绑定工位和岗位后再操作！")
        }
        val stations = listOf("station1", "station2", "station3", "station4", "AE-2", "AE-3", "CA-3")
        if (ws.isBlank() || !stations.contains(ws)) throw BusinessError("请选择正确的岗位进行操作")

        val params = mapper.readTree(body["params"].toString())

        val siteId: String  // expected value like CA-1-n or AE-2-n
        try {
            siteId = params["siteId"].asText()
        } catch (e: Exception) {
            throw BusinessError("请求中缺少库位信息，请输入目标库位！ ")
        }
        if (!listOf("M0-1", "M1-1", "M2-1", "M3-1", "AE-1", "AE-2", "AE-3", "CA-3").contains(siteId.substring(0, 4)))
            throw BusinessError("库位【$siteId】不支持此功能，请重新选择库位！")

        val remoteAddr = ctx.req.remoteAddr
        logger.info("clear content of site=$siteId from [$remoteAddr].")
        clearSiteContentIfUnlocked(siteId)

        ctx.json("OK")
        ctx.status(201)
    }

    fun createEToStation(ctx: Context) {
        val bodyStr = ctx.body()
        logger.info("create e-to-station tasks, body=$bodyStr")
        try {
            val body = mapper.readTree(ctx.body())

            val ws: String  // expected value is station2 or station3 or station4
            try {
                ws = body["workStation"].asText()
            } catch (e: Exception) {
                throw BusinessError("请求中缺少岗位信息，请选择工位和岗位后再下单！")
            }
            if (ws.isBlank()) throw BusinessError("请选择工位和岗位后再下单！")

            val params = mapper.readTree(body["params"].toString())

            val code: String        // this code is code_Z8
            try {
                code = params["code"].asText()
            } catch (e: Exception) {
                // 请求体格式不对就会抛异常
                throw BusinessError("请求中缺少整机码，请检查请求体！")
            }
            if (code.isBlank()) throw BusinessError("请输入整机码！")

            createEToStationTasksByWorkStationAndCode(ws, code)

            ctx.status(201)

        } catch (e: Exception) {
            throw BusinessError("create E to station tasks occurred error: ${e.message}")
        }
    }

    fun createTakeMatFromAE1ToAE3Tasks(ctx: Context) {
        val bodyStr = ctx.body()
        logger.info("create take-mat-from-ae1-to-ae3 tasks, body=$bodyStr")
        try {
            val body = mapper.readTree(ctx.body())

            val ws: String  // expected value is station2 or station3 or station4
            try {
                ws = body["workStation"].asText()
            } catch (e: Exception) {
                throw BusinessError("请求中缺少岗位信息，请选择工位和岗位后再下单！ ")
            }
            if (ws.isBlank()) throw BusinessError("请选择工位和岗位后再下单！")

            val params = mapper.readTree(body["params"].toString())

            val code: String        // this code is code_800
            try {
                code = params["code"].asText()
            } catch (e: Exception) {
                // 请求体格式不对就会抛异常
                throw BusinessError("请求中缺少货物编码，请检查请求体！")
            }
            if (code.isBlank()) throw BusinessError("请输入货物编码！")

            createTakeMatFromAE1ToAE3TasksByCode(code)

            ctx.status(201)

        } catch (e: Exception) {
            throw BusinessError("create E to station tasks occurred error: ${e.message}")
        }
    }

    fun listMappings(ctx: Context) {
        ctx.json(MaterialProductMappingService.listMappings())
        ctx.status(200)
    }

    fun materialProductMapping(ctx: Context) {
        try {
            logger.info("[${ctx.req.remoteAddr}] upload material-product-mappings ...")
            val mappingReqBody = mapper.readValue(ctx.body(), MappingReqBody::class.java)
            val mappings = mappingReqBody.mappings

            if (mappings.isEmpty())
                throw Error400("NoMaterialProductMapping", "No request body!")

            mappings.forEach {
                val material = it.material
                val product = it.product
                if (material == null || product == null)
                    throw Error400("MaterialCode Or ProductCode Post Error!",
                        "Bad data from remote: {material=$material, product=$product}!")
                if (material.isBlank() || product.isBlank())
                    throw Error400("MaterialCode Or ProductCode Post Error!",
                        "Bad data from remote: {material=$material, product=$product}!")
            }

            MaterialProductMappingService.recordNewMappings(mappings)

            ctx.status(201)
        } catch (e: Exception) {
            logger.error("upload material-product-mappings occurred error: $e")
            throw e
        }
    }

    fun removeMaterialProductMappings(ctx: Context) {
        logger.info("[${ctx.req.remoteAddr}] remove material-product-mappings ...")
        val params = ctx.bodyAsClass(RemoveParams::class.java)
        val start = params.start
        val end = params.end

        try {
            params.startInstant = if (start.isNullOrBlank()) null else Instant.parse("${start}T08:00:00.000z")
        } catch (e: Exception) {
            throw Error400("Invalid Parameter", "Bad parameter start=$start, format should be YYYY-MM-DD!")
        }

        try {
            params.endInstant = if (end.isNullOrBlank()) null else Instant.parse("${end}T08:00:00.000z")
        } catch (e: Exception) {
            throw Error400("Invalid Parameter", "Bad parameter end=$end, format should be YYYY-MM-DD!")
        }

        MaterialProductMappingService.removeMappings(params)
        ctx.status(200)
    }
}