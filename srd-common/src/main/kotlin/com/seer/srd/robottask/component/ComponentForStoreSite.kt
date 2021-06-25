package com.seer.srd.robottask.component

import com.seer.srd.BusinessError
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.storesite.StoreSiteService.changeSiteFilled
import com.seer.srd.storesite.StoreSiteService.getExistedStoreSiteById
import com.seer.srd.storesite.StoreSiteService.getSiteContentOfType
import com.seer.srd.storesite.StoreSiteService.lockEmptySiteOfTypeAndUnlocked
import com.seer.srd.storesite.StoreSiteService.lockFirstSiteWithEmptyTray
import com.seer.srd.storesite.StoreSiteService.lockSiteIfNotLock
import com.seer.srd.storesite.StoreSiteService.setSiteContent
import com.seer.srd.storesite.StoreSiteService.unlockSiteIfLocked
import com.seer.srd.util.splitTrim
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("com.seer.srd.robottask")

val storeSiteComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
        "site", "CheckExistedStoreSiteById", "检查库位ID存在", "",
        false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), true
    ) { component, ctx ->
        val siteId = parseComponentParamValue("siteId", component, ctx) as String
        val site = getExistedStoreSiteById(siteId)
        ctx.setRuntimeVariable(component.returnName, site)
    },
    TaskComponentDef(
        "site", "LockSiteOnlyIfNotLock", "锁定未锁定的库位", "",
        false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), false
    ) { component, ctx ->
        val siteId = parseComponentParamValue("siteId", component, ctx) as String
        lockSiteIfNotLock(siteId, ctx.task.id, "From task ${ctx.taskDef?.name}")
    },
    TaskComponentDef(
        "site", "LockIdleSiteOfType", "按库位类型锁定一个空库位", "",
        false, listOf(
            TaskComponentParam("type", "类型", "string")
        ), true
    ) { component, ctx ->
        val type = parseComponentParamValue("type", component, ctx) as String
        val site = lockEmptySiteOfTypeAndUnlocked(type, ctx.task.id, "From task ${ctx.taskDef?.name}")
        ctx.setRuntimeVariable(component.returnName, site)
    },
    TaskComponentDef(
        "site", "UnlockSiteIfLocked", "释放已锁定的库位", "",
        false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), false
    ) { component, ctx ->
        val siteId = parseComponentParamValue("siteId", component, ctx) as String
        unlockSiteIfLocked(siteId, "From task ${ctx.taskDef?.name}")
    },
    TaskComponentDef(
        "site", "MarkSiteNotIdle", "将库位修改为非空", "",
        false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), false
    ) { component, ctx ->
        val siteId = parseComponentParamValue("siteId", component, ctx) as String
        changeSiteFilled(siteId, true, "From task ${ctx.taskDef?.name}")
    },
    TaskComponentDef(
        "site", "MarkSiteIdle", "将库位修改为空", "",
        false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), false
    ) { component, ctx ->
        val siteId = parseComponentParamValue("siteId", component, ctx) as String
        changeSiteFilled(siteId, false, "From task ${ctx.taskDef?.name}")
    },
    TaskComponentDef(
        "site", "GetSiteContentOfType", "获取某类型的库位中的货物", "假设该类型的库位的货物必须相同，如果库位都为空，返回 null",
        false, listOf(
            TaskComponentParam("type", "库位类型", "string")
        ), true
    ) { component, ctx ->
        val type = parseComponentParamValue("type", component, ctx) as String
        val content = getSiteContentOfType(type)
        LOG.debug("GetSiteContentOfType $content")
        ctx.setRuntimeVariable(component.returnName, content)
    },
    TaskComponentDef(
        "site", "SetSiteContent", "设置库位内容", "",
        false, listOf(
            TaskComponentParam("siteId", "库位ID", "string"),
            TaskComponentParam("content", "内容", "string")
        ), false
    ) { component, ctx ->
        val siteId = parseComponentParamValue("siteId", component, ctx) as String
        val content = parseComponentParamValue("content", component, ctx) as String
        setSiteContent(siteId, content, "From task ${ctx.taskDef?.name}")
    },
    TaskComponentDef(
        "site", "getSiteByContent", "获取一个指定内容的库位", "没有则返回null",
        false, listOf(
        TaskComponentParam("content", "content", "string")
    ), true
    ) { component, ctx ->
        val content = parseComponentParamValue("content", component, ctx) as String
        val sites = StoreSiteService.listStoreSites().filter { it.content == content }
        if (sites.isEmpty()) ctx.setRuntimeVariable(component.returnName, null)
        else ctx.setRuntimeVariable(component.returnName, sites[0])
    },
    TaskComponentDef(
        "site", "CheckStoreSiteFilled", "检查库位非空", "",
        false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), true
    ) { component, ctx ->
        val siteId = parseComponentParamValue("siteId", component, ctx) as String
        val site = getExistedStoreSiteById(siteId)
        if (!site.filled) throw BusinessError("库位${siteId}不是非空的")
        ctx.setRuntimeVariable(component.returnName, site)
    },
    TaskComponentDef(
        "site", "CheckStoreSiteEmpty", "检查库位空", "",
        false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), true
    ) { component, ctx ->
        val siteId = parseComponentParamValue("siteId", component, ctx) as String
        val site = getExistedStoreSiteById(siteId)
        if (site.filled) throw BusinessError("库位${siteId}不是空的")
        ctx.setRuntimeVariable(component.returnName, site)
    },
    TaskComponentDef(
        "site", "CheckStoreSiteContentIs", "检查库位为指定内容", "",
        false, listOf(
            TaskComponentParam("siteId", "库位ID", "string"),
            TaskComponentParam("content", "库位内容", "string")
        ), true
    ) { component, ctx ->
        val siteId = parseComponentParamValue("siteId", component, ctx) as String
        val content = parseComponentParamValue("content", component, ctx) as String
        val site = getExistedStoreSiteById(siteId)
        if (!StringUtils.equals(site.content, content))
            throw BusinessError("期望库位${siteId}里是${content}实际是${site.content}")
        ctx.setRuntimeVariable(component.returnName, site)
    },
    TaskComponentDef(
        "site", "LockFirstSiteWithEmptyTray", "锁定第一个未锁定的带有空托盘的库位", "",
        false, listOf(
            TaskComponentParam("sites", "库位ID列表", "string")
        ), true
    ) { component, ctx ->
        val sitesStr = parseComponentParamValue("sites", component, ctx) as String
        val sites = splitTrim(sitesStr, ",")
        val site = lockFirstSiteWithEmptyTray(sites, ctx.task.id, "From task ${ctx.taskDef?.name}")
        ctx.setRuntimeVariable(component.returnName, site)
    },
    TaskComponentDef(
        "site", "getFilledSiteByType", "获取一个指定类型的非空库位", "没有则返回null",
        false, listOf(
            TaskComponentParam("type", "库位类型", "string")
        ), true
    ) { component, ctx ->
        val type = parseComponentParamValue("type", component, ctx) as String
        val sites = StoreSiteService.listStoreSites().filter { it.type == type && it.filled }
        if (sites.isEmpty()) ctx.setRuntimeVariable(component.returnName, null)
        else ctx.setRuntimeVariable(component.returnName, sites[0])
    },
    TaskComponentDef(
        "site", "getNotFilledSiteByType", "获取一个指定类型的空库位", "没有则返回null",
        false, listOf(
        TaskComponentParam("type", "库位类型", "string")
    ), true
    ) { component, ctx ->
        val type = parseComponentParamValue("type", component, ctx) as String
        val sites = StoreSiteService.listStoreSites().filter { it.type == type && !it.filled }
        if (sites.isEmpty()) ctx.setRuntimeVariable(component.returnName, null)
        else ctx.setRuntimeVariable(component.returnName, sites[0])
    }
    //TaskComponentDef(
    //    "site", "LockEmptySiteOfAreaByLoadType", "锁定区域中的一个空库位，如果货物相同", "",
    //    false, true, listOf(
    //        TaskComponentParam("areaId", "区域ID", "string"),
    //        TaskComponentParam("loadType", "货物类型", "string")
    //    )
    //) { component, ctx ->
    //    val areaId = parseComponentParamValue("areaId", component, ctx) as String
    //    val loadType = parseComponentParamValue("loadType", component, ctx) as String
    //    val site = lockEmptySiteOfAreaByLoadType(ss, areaId, loadType)
    //    if (!component.returnName.isNullOrBlank()) ctx.runtimeVariables[component.returnName] = site
    //},
    //TaskComponentDef(
    //    "site", "LockEmptySiteOfArea", "锁定区域中的一个空库位", "",
    //    false, true, listOf(
    //        TaskComponentParam("area", "区域ID", "string")
    //    )
    //) { component, ctx ->
    //    val area = parseComponentParamValue("area", component, ctx) as String
    //    val site = lockEmptySiteOfArea(area)
    //    if (!component.returnName.isNullOrBlank()) ctx.runtimeVariables[component.returnName] = site
    //}
)