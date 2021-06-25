package com.seer.srd.nanruijibao.handlers

import com.seer.srd.BusinessError
import com.seer.srd.eventlog.UserOperationLog
import com.seer.srd.eventlog.recordUserOperationLog
import com.seer.srd.handler.UpdateStoreSites
import com.seer.srd.nanruijibao.ApplyForVehicleService
import com.seer.srd.nanruijibao.ApplyForVehicleService.vehicleOnSiteAndAvailable
import com.seer.srd.nanruijibao.CustomUtil.getPassword
import com.seer.srd.nanruijibao.CustomUtil.recordOperatorOperationLog
import com.seer.srd.nanruijibao.workTypeMaps
import com.seer.srd.robottask.RobotTaskService
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.storesite.StoreSiteService.setSiteContent
import io.javalin.http.Context
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory

data class RequestBody(
    val menuId: String = "",
    val workType: String = "",
    val workStation: String = "",
    val params: Params = Params()
)

data class Params(
    val siteId: String? = null,
    val vehicleName: String? = null,
    val tag: Boolean? = null            // true: 已安装工装； false: 未安装工装
)

const val ABORT_TASK = "RobotTask::Abort"
const val CHANGE_STORE_SITE = "StoreSite::Change"

private val logger = LoggerFactory.getLogger("com.seer.srd.nanruijibao.handlers")

val SPECIAL_SITES = listOf("AGV-TAG-01", "AGV-TAG-02", "Load-Finished", "Unload-Finished")

const val OPT_EXT_DEVICE_STORESITE = "2F-I-1"

fun emptyStoreSite(ctx: Context) {
    val reqBody = ctx.bodyAsClass(RequestBody::class.java)
    val remoteAddr = ctx.req.remoteAddr

    val siteId = reqBody.params.siteId ?: throw BusinessError("请输入库位名称！")
    if (SPECIAL_SITES.contains(siteId)) throw BusinessError("请输入有效的库位名称！")

    val site = StoreSiteService.getStoreSiteById(siteId) ?: throw BusinessError("不存在库位【$siteId】！")
    if (!site.filled) throw BusinessError("库位【$siteId】未被占用，操作失败！")
    if (site.locked) throw BusinessError("库位【$siteId】被锁定，操作失败！")

    StoreSiteService.setEmptyIfFilledAndUnlocked(siteId, "empty site from $remoteAddr .")

    ctx.status(201)
}

fun fillStoreSite(ctx: Context) {
    val reqBody = ctx.bodyAsClass(RequestBody::class.java)
    val remoteAddr = ctx.req.remoteAddr

    val siteId = reqBody.params.siteId ?: throw BusinessError("请输入库位名称！")
    if (SPECIAL_SITES.contains(siteId)) throw BusinessError("请输入有效的库位名称！")

    val site = StoreSiteService.getStoreSiteById(siteId) ?: throw BusinessError("不存在库位【$siteId】！")
    if (site.filled) throw BusinessError("库位【$siteId】被占用，操作失败！")

    StoreSiteService.changeSiteFilled(siteId, true, "[$remoteAddr]:fill site.")

    ctx.status(201)
}

fun optExtDeviceFinished(ctx: Context) {
    val reqBody = ctx.bodyAsClass(RequestBody::class.java)
    val remoteAddr = ctx.req.remoteAddr

    val vehicleName = reqBody.params.vehicleName ?: throw BusinessError("请输入AGV名称！")
    val tag = reqBody.params.tag ?: throw BusinessError("请选择机器人当前标签！")

    // 如果工装操作库位上不是有且仅有一辆在线接单的机器人，则会报错。
    vehicleOnSiteAndAvailable(OPT_EXT_DEVICE_STORESITE)

    val site = StoreSiteService.getStoreSiteById(OPT_EXT_DEVICE_STORESITE)
        ?: throw BusinessError("缺少工装操作库位【$OPT_EXT_DEVICE_STORESITE】，请在库位定义中添加！")
    if (!site.locked) throw BusinessError("请求失败，工装操作库位【$OPT_EXT_DEVICE_STORESITE】没有被任务锁定！")

    // 更新机器人标签
    doUpdateVehicleTag(vehicleName, tag, remoteAddr)

    // 更新完成标志
    doOptExtDeviceFinished(tag, "[$remoteAddr]:opt ext device finished.")

    ctx.status(201)
}

fun updateVehicleTag(ctx: Context) {
    val reqBody = ctx.bodyAsClass(RequestBody::class.java)
    val remoteAddr = ctx.req.remoteAddr

    val vehicleName = reqBody.params.vehicleName ?: throw BusinessError("请输入AGV名称！")
    val tag = reqBody.params.tag ?: throw BusinessError("请选择机器人当前标签！")

    doUpdateVehicleTag(vehicleName, tag, remoteAddr)

    ctx.status(201)
}

@Synchronized
fun doUpdateVehicleTag(vehicleName: String, tag: Boolean, remoteAddr: String) {
    val siteId = ApplyForVehicleService.getTagIdByVehicleName(vehicleName)
    val site = StoreSiteService.getStoreSiteById(siteId)
        ?: throw BusinessError("请添加库位【$siteId】，以记录AGV是否已经安装工装！")

    if (site.filled && tag)
        throw BusinessError("操作所失败：AGV${vehicleName}已安装工装，无法重复设置，请确认AGV实际状态后再操作！")
    if (!site.filled && !tag)
        throw BusinessError("操作所失败：AGV${vehicleName}未安装工装，无法重复设置，请确认AGV实际状态后再操作！")

    StoreSiteService.changeSiteFilled(siteId, tag, "[$remoteAddr]:update agv tag.")
    setSiteContent(siteId, if (tag) "工装" else "", "[$remoteAddr]:update agv tag.")
}

@Synchronized
fun doOptExtDeviceFinished(load: Boolean, remark: String) {
    val siteId = if (load) "Load-Finished" else "Unload-Finished"
    val txt = if (load) "安装" else "拆卸"
    StoreSiteService.getStoreSiteById(siteId)
        ?: throw BusinessError("请添加库位【$siteId】，以记录${txt}工装是否完成！")

    StoreSiteService.changeSiteFilled(siteId, true, remark)
}

fun checkPassword(ctx: Context) {
    val remoteAddr = ctx.req.remoteAddr
    val oldWt = ctx.queryParam("oldWt")
    val oldWs = ctx.queryParam("oldWs")
    val newWt = ctx.queryParam("newWt")
    val newWs = ctx.queryParam("newWs")
    val change = "from (wt=$oldWt, ws=$oldWs) to (wt=$newWt, ws=$newWs)"

    when (ctx.queryParam("pwd")?.trim()) {
        getPassword() -> logger.info("[$remoteAddr]切换岗位和工位[成功]，$change.")
        null -> {
            logger.error("[$remoteAddr]切换岗位和工位[失败]，$change，确认密码不能为空，请重新输入！！！")
            throw BusinessError("确认密码不能为空，请重新输入！！！")
        }
        else -> {
            logger.error("[$remoteAddr]切换岗位和工位[失败]，$change，密码错误，请确认之后再输入！！！")
            throw BusinessError("密码错误，请确认之后再输入！！！")
        }
    }
    ctx.status(201)
}

fun abortRobotTask(ctx: Context) {
    val taskId = ctx.pathParam("id")
    val wt = ctx.queryParam("workType")
        ?: throw BusinessError("请绑定账号（工位&岗位）之后再操作！")
    recordOperatorOperationLog(wt, ctx.req.remoteAddr, taskId)

    RobotTaskService.abortTask(taskId)

    ctx.status(204)
}

fun updateStoreSiteInBatch(ctx: Context) {
    val req = ctx.bodyAsClass(UpdateStoreSites::class.java)

    val wt = ctx.queryParam("workType")
        ?: throw BusinessError("请绑定账号（工位&岗位）之后再操作！")
    recordOperatorOperationLog(wt, ctx.req.remoteAddr, "${req.siteIds}:${req.update}")


    if (req.update.containsKey("filled")) {
        StoreSiteService.changeSitesFilledByIds(
            req.siteIds, req.update["filled"] == 1 || req.update["filled"] == true, "FromUI-PAD"
        )
    }
    if (req.update.containsKey("locked")) {
        StoreSiteService.changeSitesLockedByIds(
            req.siteIds, req.update["locked"] == 1 || req.update["locked"] == true, "",
            "FromUI-PAD"
        )
    }
    if (req.update.containsKey("content")) {
        val content = req.update["content"] as String
        for (id in req.siteIds) setSiteContent(id, content, "FromUI-PAD")
    }
    ctx.status(204)
}

