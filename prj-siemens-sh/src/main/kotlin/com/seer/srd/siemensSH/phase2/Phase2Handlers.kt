package com.seer.srd.siemensSH.phase2

import com.seer.srd.BusinessError
import com.seer.srd.Error400
import com.seer.srd.siemensSH.phase2.Phase2Utils.appendRecognizeProperty
import com.seer.srd.siemensSH.phase2.Phase2Utils.createTakeMatToPsTasksByCodeAndPsName
import com.seer.srd.siemensSH.phase2.Phase2Utils.forkLoadRecognizeOrNot
import com.seer.srd.siemensSH.phase2.Phase2Utils.getCreatedTaskByConditions
import io.javalin.http.Context
import org.slf4j.LoggerFactory
import com.seer.srd.util.mapper

object Phase2Handlers {
    private val logger = LoggerFactory.getLogger(Phase2Handlers::class.java)

    fun createTakeMatToPsTasks(ctx: Context) {
        val bodyStr = ctx.body()
        logger.info("create take-mat-to-ps tasks, body=$bodyStr")
        try {
            val body = mapper.readTree(ctx.body())

            val wt: String
            try {
                wt = body["workType"].asText()
            } catch (e: Exception) {
                // 请求体格式不对就会抛异常
                throw BusinessError("请求中缺少货工位信息，请先绑定工位！")
            }

            val params = mapper.readTree(body["params"].toString())

            val code: String
            try {
                code = params["code"].asText()
            } catch (e: Exception) {
                // 请求体格式不对就会抛异常
                throw BusinessError("请求中缺少货物编码，请检查请求体！")
            }
            if (code.isBlank()) throw BusinessError("请输入货物编码！")

            val ps: String  // default value from PDA is "unselected"; expected value is ps1 or ps2
            try {
                ps = params["ps"].asText()
            } catch (e: Exception) {
                throw BusinessError("请求中缺少产线信息，请选择产线后再下单！")
            }
            if (ps == "unselected") throw BusinessError("请选择产下后再下单！")

            createTakeMatToPsTasksByCodeAndPsName(code, ps, wt)

            ctx.status(201)

        } catch (e: Exception) {
            val message = e.message ?: "创建任务失败"
            logger.error("create take mat to ps tasks occurred error: $message")
            throw BusinessError(message)
        }
    }

    fun forkLoadRecognizeOrNotTest(ctx: Context) {
        val siteId = ctx.pathParam("siteId")
        val recognize = forkLoadRecognizeOrNot(siteId)
        ctx.status(201)
        ctx.json(recognize.toString())
    }

    fun appendRecognizePropertyTest(ctx: Context) {
        val newProperties = appendRecognizeProperty("""[{"key":"end_height","value":"0....."}]""")
        ctx.status(201)
        ctx.json(newProperties)
    }

    fun test(ctx: Context) {
        val taskDef = ctx.queryParam("taskDef") ?: throw BusinessError("请输入任务ID")
        val fromSiteId =  ctx.queryParam("fromSiteId") ?: throw BusinessError("请输入任务ID")
        val content =  ctx.queryParam("content") ?: throw BusinessError("请输入货物码")

        val tasks = getCreatedTaskByConditions(taskDef, fromSiteId, content).map { it.id }

        ctx.json(tasks)
    }

}