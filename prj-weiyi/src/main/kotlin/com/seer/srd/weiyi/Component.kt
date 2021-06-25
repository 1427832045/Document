package com.seer.srd.weiyi

import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.seer.srd.BusinessError
import com.seer.srd.NoMatchSite
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.*
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.util.mapper
import com.seer.srd.weiyi.ExtHandlers.createProductOutTasks
import com.seer.srd.weiyi.ExtHandlers.getToSiteTypeByFromSite
import com.seer.srd.weiyi.ExtHandlers.parseProductInfoFromString
import com.seer.srd.weiyi.ExtHandlers.parseTaskOutInfoFromString
import com.seer.srd.weiyi.ExtHandlers.sortStoreSiteBySiteId
import com.seer.srd.weiyi.ExtHandlers.submit
import com.seer.srd.weiyi.OrderSendHandler.markExecutingTaskFinished
import com.seer.srd.weiyi.OrderSendHandler.sendTaskIfPriorityAndVehicleNumPermitted
import com.seer.srd.weiyi.OrderSendHandler.recordOrderNameIntoExecutingTask
import com.seer.srd.weiyi.SentTaskManager.insertNewSentTask
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.lang.Exception

object ExtraComponents {
    private val logger = LoggerFactory.getLogger(ExtraComponents::class.java)

    val extraComponents: List<TaskComponentDef> = listOf(
        TaskComponentDef(
            "extra", "getToSiteByFromSite", "根据起点获取未锁定的空库位为终点，并锁定", "",
            false, listOf(
            TaskComponentParam("fromSite", "起点", "string")
        ), true) { component, ctx ->
            logger.debug("根据起点获取终点")
            // 根据起点库位类型获取终点库区
            val fromSite = parseComponentParamValue("fromSite", component, ctx) as String
            val toSiteArea = getToSiteTypeByFromSite(fromSite)

            // 获取可用的终点库位集合
            val toSites = MongoDBManager.collection<StoreSite>()
                .find(Filters.and(StoreSite::type eq toSiteArea, StoreSite::filled eq false, StoreSite::locked eq false))
                .reversed()
            if (toSites.isEmpty()) throw BusinessError("【区域 [$toSiteArea] 无未锁定的空库位】")
            val toSiteList = sortStoreSiteBySiteId(toSiteArea, toSites)

            // 入库时的终点库位为竞争资源，如果可用，需要立即锁定
            val toSiteId = toSiteList.first()
            val toSite = MongoDBManager.collection<StoreSite>().findOne(StoreSite::id eq toSiteId)
                ?: throw BusinessError("不存在库位【$toSiteId】")
            val content = toSite.content
            if (content.isNotEmpty()) throw BusinessError("库位【$toSiteId】有货物【$content】，无法使用")
            logger.info("获取终点成功，任务【${ctx.task.id}】from【$fromSite】to【$toSiteId】")

            // 锁定终点库位
            try {
                StoreSiteService.lockSiteIfNotLockAndEmpty(toSiteId, ctx.task.id, "Build ProductInTask")
            } catch (err: Exception) {
                throw BusinessError("锁定终点库位【$toSiteId】失败，for ${err.message}")
            }

            val returnName = component.returnName
            if (!returnName.isNullOrBlank()) {
                ctx.setRuntimeVariable(returnName, toSite)
                logger.debug("终点库区是：$toSiteArea - ${ctx.runtimeVariables[returnName]}")
            }
        },

        TaskComponentDef(
            "extra", "BuildTaskProductOut", "创建出库任务", "",
            false, listOf(
            TaskComponentParam("taskInfos", "工单号", "string")
        ), false) { component, ctx ->
            logger.debug("创建出库任务")
            val taskInfoStr = parseComponentParamValue("taskInfos", component, ctx) as String

            // 平板上的数据是回显数据，这些数据已经被服务端处理过了。优化方案：直接将接受到的原始数据以字符串的形式记录到数据库中。
            val taskInfos = parseTaskOutInfoFromString(taskInfoStr, false)
            logger.debug("创建出库任务, obj to string: $taskInfos")

            // 同步方法，防止任务被重复创建
            createProductOutTasks(taskInfos)
        },

        TaskComponentDef(
            "extra", "LockFromSiteAndToSiteByCondition", "按条件锁定起点和终点",
            "", false, listOf(
            TaskComponentParam("fromSite", "起点库位", "string"),
            TaskComponentParam("toSite", "终点库位", "string")
        ), false) { component, ctx ->
            logger.debug("按条件锁定起点和终点")
            val fromSiteId = parseComponentParamValue("fromSite", component, ctx) as String
            val toSiteId = parseComponentParamValue("toSite", component, ctx) as String

            val filter = Filters.and(StoreSite::id eq toSiteId, StoreSite::filled eq false, StoreSite::locked eq false)
            val toSite = MongoDBManager.collection<StoreSite>().findOne(filter)
                ?: throw BusinessError("终点库位[$toSiteId]不满足条件")

            StoreSiteService.lockSiteIfNotLock(fromSiteId, ctx.task.id, "出库，锁定起点")
            StoreSiteService.lockSiteIfNotLock(toSite.id, ctx.task.id, "出库，锁定终点")
        },

        TaskComponentDef(
            "extra", "ClearContentOfStoreSite", "清空库位内容",
            "", false, listOf(
            TaskComponentParam("siteId", "库位", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            StoreSiteService.setSiteContent(siteId, "", "清空库位内容")
        },

        TaskComponentDef(
            "extra", "GetContentOfStoreSite", "获取库位的内容",
            "", false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), true) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            val filter = Filters.and(StoreSite::id eq siteId)
            val site = MongoDBManager.collection<StoreSite>().findOne(filter)
                ?: throw NoMatchSite("无此库位[$siteId]")

            val task = ctx.task
            val pv = task.persistedVariables
            pv[component.returnName ?: "content"] = site.content

            MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq task.id,
                set(RobotTask::persistedVariables setTo pv), UpdateOptions().upsert(true))
        },

        TaskComponentDef(
            "extra", "PersistProductInfo", "持久化货物信息", "", false, listOf(
            TaskComponentParam("productCode", "货物编码", "string")
        ), true) { component, ctx ->
            val code = parseComponentParamValue("productCode", component, ctx) as String
            val info = parseProductInfoFromString(code)
            ctx.task.persistedVariables[component.returnName ?: "productInfo"] = info.fmono
            logger.debug("persistedVariables: ${ctx.task.persistedVariables.json}")
            ctx.task.persistedVariables["productDetails"] = info.json
        },

        TaskComponentDef(
            "extra", "ProductStoreIn", "SRD 请求 ERP，告知产品入库完成", "",
            false, listOf(TaskComponentParam("site", "目标库位", "string")),
            false
        ) { component, ctx ->
            val toSiteId = parseComponentParamValue("site", component, ctx) as String
            val infoStr = ctx.task.persistedVariables["productDetails"] as String
            // infoStr 的数据源就是 ProductInfo 的对象, 是否还需要通过 parseProductInfoFromString() 反解
            val infoJson = mapper.readTree(infoStr)
            val info = """{
                |"FContractNo": ${infoJson["fcontractNo"]}, 
                |"FMONO": ${infoJson["fmono"]}, 
                |"FEmpName": ${infoJson["fempName"]}, 
                |"FCustName": ${infoJson["fcustName"]}, 
                |"FQtyPiece": ${infoJson["fqtyPiece"]}, 
                |"FQty": ${infoJson["fqty"]}, 
                |"Store": "$toSiteId"
                |}""".trimMargin()
            submit("product-store-in", info, "报告产品入库完成")
        },

        TaskComponentDef(
            "extra", "ProductStoreOut", "SRD 请求 ERP，报告出库完成", "",
            false, listOf(
            TaskComponentParam("fmono", "工单号", "string"),
            TaskComponentParam("fromSiteId", "起点库位号", "string")
        ), false) { component, ctx ->
            val fmono = parseComponentParamValue("fmono", component, ctx) as String
            val fromSiteId = parseComponentParamValue("fromSiteId", component, ctx) as String
            val info = """{"FMONO": "$fmono", "fromSite": "$fromSiteId"}""".trimMargin()
            submit("product-store-out", info, "报告出库完成")
        },

        TaskComponentDef(
            "extra", "ProductSortFinished", "SRD 请求 ERP，告知理库完成", "",
            false, listOf(
            TaskComponentParam("fmono", "工单号", "string"),
            TaskComponentParam("oldSite", "原库位", "string"),
            TaskComponentParam("newSite", "新库位", "string")
        ), false) { component, ctx ->
            val fmono = parseComponentParamValue("fmono", component, ctx) as String
            val oldSite = parseComponentParamValue("oldSite", component, ctx) as String
            val newSite = parseComponentParamValue("newSite", component, ctx) as String
            val info = """{"FMONO": "$fmono", "oldSite": "$oldSite", "newSite": "$newSite"}""".trimMargin()
            submit("product-sort", info, "告知理库完成")
        },

        TaskComponentDef(
            "extra", "SetPriority", "设置任务优先级", "",
            false, listOf(
            TaskComponentParam("priority", "优先级", "int")
        ), false) { component, ctx ->
            ctx.task.priority = parseComponentParamValue("priority", component, ctx) as Int
        },

        TaskComponentDef(
            "extra", "CheckBufferEmptyOrFEmpty", "检查缓存位置K和成品发货位置F是否至少有一个为空", "",
            false, listOf(
        ), true) { component, ctx ->
            val filterK = Filters.and(StoreSite::filled eq false, StoreSite::locked eq false, StoreSite::type eq "K")
            val k = MongoDBManager.collection<StoreSite>().find(filterK).count()

            val filterF = Filters.and(StoreSite::filled eq false, StoreSite::locked eq false, StoreSite::type eq "F")
            val f = MongoDBManager.collection<StoreSite>().find(filterF).count()

//            if (k == 0 && f == 0) throw BusinessError("缓存位置和成品发货位置都没有可用的空库位")
            val returnName = component.returnName
            if (!returnName.isNullOrBlank()) {
                ctx.task.persistedVariables[returnName] = !(k == 0 && f == 0)
            }
        },

        TaskComponentDef(
            "extra", "CheckTransportSkipped", "检查运单是否被跳过", "",
            false, listOf(
            TaskComponentParam("transportIndex", "运单索引", "int")
        ), true) { component, ctx ->
            val index = parseComponentParamValue("transportIndex", component, ctx) as Int
            val transport = ctx.task.transports[index]
            val returnName = component.returnName
            if (!returnName.isNullOrBlank()) {
                ctx.setRuntimeVariable(returnName, transport.state == RobotTransportState.Skipped)
            }
        },

        TaskComponentDef(
            "extra", "EmptySiteFSilently", "将库位F置空，用于测试", "",
            false, listOf(
            TaskComponentParam("enable", "启用", "string")
        ), false) { component, ctx ->
            val enable = parseComponentParamValue("enable", component, ctx) as String
            try {
                if (enable == "true") {
                    MongoDBManager.collection<StoreSite>().updateOneById("F", StoreSite::filled setTo false)
                    logger.info("empty site F silently success while ProductOut Finished.")
                } else {
                    logger.info("empty site F silently not enabled.")
                }
            } catch (e: Exception) {
                logger.info("empty site F silently failed while ProductOut Finished, ignore.")
            }
        },

        TaskComponentDef(
            "extra", "BufferExistProduct", "判断缓存区是否存在未处理的料架", "",
            false, listOf(
        ), true) { component, ctx ->
            val count = MongoDBManager.collection<StoreSite>()
                .find(StoreSite::locked eq true, StoreSite::type eq "K").count()

            logger.debug("缓存区存在未处理的料架： $count.")

            val returnName = component.returnName
            if (!returnName.isNullOrBlank()) {
                ctx.setRuntimeVariable(returnName, count > 0)
            }
        },

        TaskComponentDef(
            "extra", "FromSiteAvailable", "判断起点可用", "",
            false, listOf(
            TaskComponentParam("fromSiteId", "起点", "string"),
            TaskComponentParam("code", "产品码", "string")
        ), true) { component, ctx ->
            val fromSiteId = parseComponentParamValue("fromSiteId", component, ctx) as String
            val code = parseComponentParamValue("code", component, ctx) as String
            val fromSite = MongoDBManager.collection<StoreSite>()
                .findOne(StoreSite::id eq fromSiteId) ?: throw BusinessError("不存在库位【$fromSiteId】")
            if (fromSite.locked) throw BusinessError("库位【$fromSiteId】被锁定，无法使用")
            if (fromSite.filled) throw BusinessError("库位【$fromSiteId】被占用，无法使用")
            val content = fromSite.content
            if (content.isNotBlank())
                if (content == code) throw BusinessError("库位【$fromSiteId】存在相同货物【$content】")
                else throw BusinessError("库位【$fromSiteId】被其他货物【$content】占用")

            val returnName = component.returnName
            if (!returnName.isNullOrBlank()) {
                ctx.setRuntimeVariable(returnName, fromSite)
            }
        },

        TaskComponentDef(
            "extra", "CheckToSiteAvailableByFromSite", "根据起点判断终点是否可用", "",
            false, listOf(
            TaskComponentParam("fromSiteId", "起点", "string")
        ), false) { component, ctx ->
            val fromSiteId = parseComponentParamValue("fromSiteId", component, ctx) as String
            val toSiteType = getToSiteTypeByFromSite(fromSiteId)

            MongoDBManager.collection<StoreSite>()
                .findOne(StoreSite::type eq toSiteType)
                ?: throw BusinessError("库区【$toSiteType】没有库位，请扫描其他扫描库位！")
        },

        TaskComponentDef(
            "extra", "ExistUnfinishedReturnEmptyTrayTask", "判断是否存在未完成的回收空托盘任务", "",
            false, listOf(
        ), false) { _, _ ->
            val task = MongoDBManager.collection<RobotTask>()
                .findOne(RobotTask::def eq "TaskDefReturnEmptyTray", RobotTask::state eq RobotTaskState.Created)
            if (task != null)
                throw BusinessError("创建任务失败：存在未完成的【回收空料架】任务【${task.id}】")
        },

        TaskComponentDef(
            "extra", "RecordExecutingSequence", "记录正在执行的任务信息", "",
            false, listOf(
        ), false) { _, ctx ->
            insertNewSentTask(ctx.task)
        },

        TaskComponentDef(
            "extra", "RecordOrderIdIntoExecutingSequence", "记录正在执行的运单ID", "",
            false, listOf(
            TaskComponentParam("transportIndex", "当前运单索引", "Int")
        ), false) { component, ctx ->
            val transportIndex = parseComponentParamValue("transportIndex", component, ctx) as Int
            val task = ctx.task
            if (task.transports.size < transportIndex || transportIndex < 0)
                throw BusinessError("index=$transportIndex 越界，请输入正确的运单索引")
            recordOrderNameIntoExecutingTask(task.id, task.transports[transportIndex].routeOrderName, "by component")
        },

        TaskComponentDef(
            "extra", "MarkExecutingSequenceFinished", "将正在执行的任务标记为结束", "",
            false, listOf(
        ), false) { _, ctx ->
            markExecutingTaskFinished(ctx.task.id, "by component")
        },

        TaskComponentDef(
            "extra", "CheckCurrentTransportSendable", "判断当前运单是否能够下发", "",
            false, listOf(
        ), false) { _, ctx ->
            try {
                val task = ctx.task
                sendTaskIfPriorityAndVehicleNumPermitted(task)
            } catch (e: Exception) {
                throw BusinessError(e.message)
            }
        }
    )
}