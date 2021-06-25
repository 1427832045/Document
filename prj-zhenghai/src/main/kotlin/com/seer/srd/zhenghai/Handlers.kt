package com.seer.srd.zhenghai

import com.seer.srd.BusinessError
import com.seer.srd.http.Handlers
import com.seer.srd.http.ReqMeta
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.util.mapper
import io.javalin.http.Context
import org.litote.kmongo.json
import org.slf4j.LoggerFactory

data class RequestBody(
    val menuId: String,
    val workType: String,
    val workStation: String,
    val params: Any
)

data class SiteOperation(
    val siteId: String,
    val fill: Int
)

data class ComplexTransportDest(
    val matInSiteId: String,
    val dest: String
)

private val logger = LoggerFactory.getLogger("com.seer.srd.zhenghai.handler")

fun registerCustomHttpHandlers() {
    val operator = Handlers("task-by-operator")
    operator.post("change-site-filled", ::handleChangeSiteFilled, ReqMeta(test = true, auth = false))
    operator.post("set-complex-transport-dest", ::handleSetDestOfComplexTransport, ReqMeta(test = true, auth = false))
}

private fun handleChangeSiteFilled(ctx: Context) {
    val reqBody = ctx.bodyAsClass(RequestBody::class.java)
    logger.info(reqBody.toString())
    val params = mapper.readValue(reqBody.params.json, SiteOperation::class.java)
    val siteId = params.siteId
    val fill = when (params.fill) {
        -1 -> throw BusinessError("请选择操作方式后再下单")
        0 -> false
        1 -> true
        else -> throw BusinessError("请选择有效的操作方式")
    }
    val site = StoreSiteService.getStoreSiteById(siteId)
        ?: throw BusinessError("操作失败：不存在库位【$siteId】")
    if (site.locked) throw BusinessError("操作失败：库位【$siteId】还未解锁！")
    if (site.filled && fill) throw BusinessError("操作失败：库位【$siteId】已经被占用，无法重复操作！")
    if (!site.filled && !fill) throw BusinessError("操作失败：库位【$siteId】已经解除占用，无法重复操作！")
    StoreSiteService.changeSiteFilled(siteId, fill, "from PAD[${ctx.req.remoteAddr}]")
}

private fun handleSetDestOfComplexTransport(ctx: Context) {
    val reqBody = ctx.bodyAsClass(RequestBody::class.java)
    logger.info(reqBody.toString())
    val params = mapper.readValue(reqBody.params.json, ComplexTransportDest::class.java)
    val siteId = params.matInSiteId
    val destStr = params.dest
    val site = StoreSiteService.getStoreSiteById(siteId)
        ?: throw BusinessError("操作失败：不存在库位【$siteId】")
    if (!site.locked) throw BusinessError("操作失败：库位【$siteId】未被锁定！")
    // 叉车执行完任务之后，进料区额库位是被占用的
    if (site.filled) throw BusinessError("操作失败：库位【$siteId】已经被占用！")
    val dest = StoreSiteService.getStoreSiteById(destStr)
        ?: throw BusinessError("操作失败：不存在库位【$siteId】")
    if (dest.locked) throw BusinessError("操作失败：库位【$destStr】已被锁定！")
    if (dest.filled) throw BusinessError("操作失败：库位【$destStr】已经被占用！")
    StoreSiteService.setSiteContent(siteId, destStr, "放行： PAD[${ctx.req.remoteAddr}]")
}
