package com.seer.srd.festo.phase4

import com.seer.srd.BusinessError
import com.seer.srd.Error400
import com.seer.srd.festo.ComplexTaskParams
import com.seer.srd.festo.FillEmptyTrayParams
import com.seer.srd.festo.PdaRequestBody
import com.seer.srd.festo.UpdateSiteParams
import com.seer.srd.festo.phase4.ComplexTaskService.modifyComplexTaskByTypeBeforeSave
import com.seer.srd.robottask.buildTaskInstanceByDef
import com.seer.srd.robottask.getRobotTaskDef
import com.seer.srd.storesite.StoreSiteService.changeSiteFilled
import com.seer.srd.storesite.StoreSiteService.listStoreSites
import com.seer.srd.util.mapper
import io.javalin.http.Context
import org.litote.kmongo.json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.seer.srd.festo.parse4.Handlers")

fun handleChangeSiteFilled(ctx: Context) {
    val requestBody = ctx.bodyAsClass(PdaRequestBody::class.java)
    logger.debug(requestBody.toString())
    val rawParams = requestBody.params ?: throw BusinessError("未填写任务参数！")
    val params = mapper.readValue(rawParams.json, UpdateSiteParams::class.java)
    val siteId = params.siteId ?: throw BusinessError("操作失败：缺少库位名称！")
    val filled = params.changeToFilled ?: throw BusinessError("操作失败：请选择库位的目标状态！")
    try {
        changeSiteFilled(siteId, filled, "From PDA[${ctx.req.remoteAddr}]")
    } catch (e: Exception) {
        if ((e as Error400).code == "NoSuchStoreSite") throw BusinessError("不存在库位【$siteId】!!!")
        throw BusinessError("将库位【$siteId】设置为${if (filled) "满" else "空"}失败！！！")
    }
}

fun handleFillSitesByType(ctx: Context) {
    val requestBody = ctx.bodyAsClass(PdaRequestBody::class.java)
    logger.debug(requestBody.toString())
    val rawParams = requestBody.params ?: throw BusinessError("未填写任务参数！")
    val params = mapper.readValue(rawParams.json, FillEmptyTrayParams::class.java)
    val fillByType = params.fillByType ?: throw BusinessError("操作失败：请选择目标库区！")
    val sites = listStoreSites().filter { it.type == fillByType }
    if (sites.isEmpty()) throw BusinessError("操作失败：系统未记录库区【$fillByType】！")
    sites.forEach {
        if (!it.locked && !it.filled) changeSiteFilled(it.id, true, "From PDA[${ctx.req.remoteAddr}]")
    }
}

fun handleCreateComplexTask(ctx: Context) {
    val requestBody = ctx.bodyAsClass(PdaRequestBody::class.java)
    logger.debug(requestBody.toString())
    val rawParams = requestBody.params ?: throw BusinessError("未填写任务参数！")
    val params = mapper.readValue(rawParams.json, ComplexTaskParams::class.java)
    val type = params.type ?: throw BusinessError("未指定任务类型！")
    val taskDef = getRobotTaskDef(TASK_DEF_COMPLEX)
        ?: throw BusinessError("缺少柔性任务【${TASK_DEF_COMPLEX}】！")
    val task = buildTaskInstanceByDef(taskDef)
    val pv = task.persistedVariables
    pv[TYPE] = type
    // 根据type创建/修改Complex任务
    modifyComplexTaskByTypeBeforeSave(type, params, task)
    ctx.status(201)
}
