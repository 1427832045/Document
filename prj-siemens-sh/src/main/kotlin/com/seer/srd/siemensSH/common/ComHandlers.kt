package com.seer.srd.siemensSH.common

import com.seer.srd.BusinessError
import com.seer.srd.siemensSH.CodeMapService.persistIntraDayUniqueCodeMap
import com.seer.srd.siemensSH.ExtHttpClient
import com.seer.srd.siemensSH.common.ComUtils.doResetResource
import com.seer.srd.util.mapper
import io.javalin.http.Context
import org.slf4j.LoggerFactory
import com.seer.srd.siemensSH.common.ComUtils.setSiteContentIfEmptyAndUnlocked
import com.seer.srd.util.HttpClient
import com.seer.srd.util.loadConfig

object ComHandlers {

    private val customConfig = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

    private val extHttpClient = HttpClient.buildHttpClient(customConfig.extUrl, ExtHttpClient::class.java)
    private val logger = LoggerFactory.getLogger(ComHandlers::class.java)

    fun closeWarningDialog(ctx: Context) {
        try {
            val body = mapper.readTree(ctx.body())
            val str = "close-warning-dialog[${ctx.req.remoteAddr}]"

            val message: String
            try {
                message = body["code"].asText()
                logger.info("$str message=$message.")
            } catch (e: Exception) {
                logger.error("bad request of $str cause message=null.")
            }
        } catch (e: Exception) {
            logger.error("bad request of close-warning-dialog cause ${e.message}.")
        }
    }

    fun setSiteContentIfEmptyAndUnlocked(ctx: Context) {
        val bodyStr = ctx.body()
        logger.info("更新库位-记录货物码 body=$bodyStr")

        val body = mapper.readTree(ctx.body())

        val params = mapper.readTree(body["params"].toString())

        val code: String
        try {
            code = params["code"].asText()
        } catch (e: Exception) {
            throw BusinessError("请求中缺少货物编码！")
        }

        val siteId: String  // expected value like CA-1-n or AE-1-n or AE-2-n
        try {
            siteId = params["siteId"].asText()
        } catch (e: Exception) {
            throw BusinessError("请求中缺少库位信息，请输入目标库位！ ")
        }
        if (!listOf("CA-1", "AE-1", "AE-2").contains(siteId.substring(0, 4)))
            throw BusinessError("库位【$siteId】不支持此功能，请重新选择库位！")

        val remoteAddr = ctx.req.remoteAddr
        logger.info("record code=$code from [$remoteAddr] into filled and unlocked site=$siteId")
        setSiteContentIfEmptyAndUnlocked(siteId, code)

        ctx.json("OK")
        ctx.status(201)
    }

    fun getCodeMap(ctx: Context) {
        logger.info("获取 Z8码 和 800码 的映射关系。")
        val res = extHttpClient.getCodeMapFromSiemens().execute()
        val body = res.body()
        logger.debug("body: $body")
        if (body == null) throw BusinessError("没有获取到 Z8码 和 800码 的映射关系！")

        if (body.error) throw BusinessError("获取 Z8码 和 800码 的映射关系失败： ${body.message}")

        logger.debug("获取 Z8码 和 800码 的映射关系, Res code: ${res.code()}")
        persistIntraDayUniqueCodeMap(body)

        ctx.status(201)
    }

    fun codeMapSim(ctx: Context) {
        logger.info("模拟 Siemens 上位机返回 Z8码 & 800码 的对应关系。")
        ctx.json(mapper.readTree(
            """{
                |"note": "此接口已弃用！！！",
                |"error": false,
                |"message": "no error.",
                |"codeMap": [{"codeZ8":"1111111111", "code800":"1111"}]
                |}""".trimMargin()
        ))
        ctx.status(201)
    }

    fun resetResource(ctx: Context) {
        logger.info("[${ctx.req.remoteAddr}] reset resource ...")
        doResetResource()
    }
}
