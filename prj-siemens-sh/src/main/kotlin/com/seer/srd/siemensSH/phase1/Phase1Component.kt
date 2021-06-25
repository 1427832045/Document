package com.seer.srd.siemensSH.phase1

import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.siemensSH.common.ComUtils.getSiteTypeByMenuId
import com.seer.srd.siemensSH.phase1.Phase1Utils.lockOneSiteFilledWithEmptyTrayByType
import org.slf4j.LoggerFactory

object Phase1Component {
    private val logger = LoggerFactory.getLogger(Phase1Component::class.java)

    val phase1Components: List<TaskComponentDef> = listOf(
        TaskComponentDef(
            "extra", "GetToSiteByWorkStation", "根据岗位获取终点库位信息", "",
            false, listOf(
            TaskComponentParam("ws", "岗位ID", "string")
        ), true) { component, ctx ->
            val ws = parseComponentParamValue("ws", component, ctx) as String
            logger.info("get tosite by workstation=$ws")
            val type = getSiteTypeByMenuId(ws)
            val returnName = component.returnName ?: "ws"
            ctx.task.persistedVariables[returnName] = type
        },

        TaskComponentDef(
            "extra", "LockOneSiteFilledWithEmptyTrayByType", "按类型锁定一个被空托盘占用的库位",
            "", false, listOf(
            TaskComponentParam("type", "库位类型", "string")
        ), true) { component, ctx ->
            val type = parseComponentParamValue("type", component, ctx) as String
            logger.info("lock a site filled with EmptyTray by type=$type.")

            val site = lockOneSiteFilledWithEmptyTrayByType(ctx.task.id, type)
            val returnName = component.returnName ?: "site"
            ctx.task.persistedVariables[returnName] = site
        }
    )
}