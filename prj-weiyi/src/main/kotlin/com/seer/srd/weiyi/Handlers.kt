package com.seer.srd.weiyi

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.*
import com.seer.srd.route.service.VehicleService
import com.seer.srd.scheduler.backgroundCacheExecutor
import com.seer.srd.storesite.*
import com.seer.srd.util.HttpClient.buildHttpClient
import com.seer.srd.util.loadConfig
import com.seer.srd.util.mapper
import com.seer.srd.vehicle.Vehicle
import io.javalin.http.Context
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.collections.toList

const val SPP_DEFAULT_ID = "default"
val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

object ExtHandlers {
    private val logger = LoggerFactory.getLogger(ExtHandlers::class.java)

    private val erpHttpClient = buildHttpClient(CUSTOM_CONFIG.erpUrl, ErpHttpClient::class.java)

    private var start: Instant? = null

    private var end: Instant? = null

    private val siteCountsOfE = CUSTOM_CONFIG.siteCountsOfE          // default is 20

    private val maxColumnCountsOfD = CUSTOM_CONFIG.maxColumnCountsOfD   // default is 60; max of rowCounts is 18(A ~ R)

    private val columnIdAndCountsOfD: MutableMap<String, Int> = mutableMapOf()

    private var totalSitesOfD = 0     // D区 所有库位总数

    // 库位排序时，每一列的库位基数，在系统初始化时计算，没必要在调用的时候再计算
    private val baseNumberOfColumns: MutableMap<String, Int> = mutableMapOf()

    // 目前只配置到 R 列， 即 A ~ R
    private val atozList: List<String> = listOf(
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R"
    )

    fun init() {
        initSysPersistParamsIfEmpty()

        // 初始化自动理库的起止时间
        start = Instant.parse("2020-05-01T${CUSTOM_CONFIG.startTime}.000Z")
        val endTime = CUSTOM_CONFIG.endTime
        val etStr = if (endTime.nextDay) "2020-05-02T${endTime.time}.000Z" else "2020-05-01T${endTime.time}.000Z"
        end = Instant.parse(etStr)
        logger.info("startTime: $start; endTime: $end")
        if (start!!.isAfter(end))
            throw BusinessError("理库的开始时间不能晚于结束时间，请按要求设置配置文件的 startTime 和 endTime ！！！")
        if (start!!.plus(1, ChronoUnit.DAYS).isBefore(end))
            throw BusinessError("理库的开始时间和结束时间的差值不能超过24小时，请按要求设置配置文件的 startTime 和 endTime ！！！")

        // 校验 E区 库位总数
        if (siteCountsOfE < 0) throw BusinessError("E区的库位数量不能是负数，请检查配置文件 “srd-config.yaml” ")

        // 初始化 D区 库位每行的库位数量
        if (maxColumnCountsOfD < 0) throw BusinessError("D区 每行的库位数量不能是负数，请检查配置文件 “srd-config.yaml” ")

        val dcc = CUSTOM_CONFIG.columnIdAndCountsOfD
        for (column in atozList) {
            logger.info("--------------- $column ")
            baseNumberOfColumns[column] = totalSitesOfD     // A行 的基数为 0； B行 的基数为 A行 的库位总数； 依次类推

            val cc = dcc[column]
            if (cc == null) {
                columnIdAndCountsOfD[column] = maxColumnCountsOfD
                totalSitesOfD += maxColumnCountsOfD
                continue
            }

            val total = cc.total
            val checkedTotal = total - (cc.unusedIndexList?.size ?: 0)
            if (checkedTotal < 0) throw BusinessError("D区【$column】行的无效库位数量不能大于其总数，请检查配置文件 “srd-config.yaml” ")
            if (total < 0) throw BusinessError("D区【$column】行的库位数量不能是负数，请检查配置文件 “srd-config.yaml” ")
            if (total > maxColumnCountsOfD)
                throw BusinessError("D区【$column】行的库位数量超过最大值 $maxColumnCountsOfD，请检查配置文件 “srd-config.yaml” ")
            columnIdAndCountsOfD[column] = checkedTotal
            totalSitesOfD += checkedTotal
        }
        logger.info("init StoreSiteAreaD config: $columnIdAndCountsOfD")
        logger.info("base site count of each column in area-D: $baseNumberOfColumns")
    }

    fun getBaseNumberOfColumn(columnId: String, number: Int, siteId: String): Int {
        if (!atozList.contains(columnId)) throw BusinessError("库区D 中没有【$columnId】列！")
        val count = columnIdAndCountsOfD[columnId] ?: -1
        if (count < 0) throw  BusinessError("未记录 库区D【$columnId】列的库位数量，请检查配置文件！")
        val unusedIndexCount = CUSTOM_CONFIG.columnIdAndCountsOfD[columnId]?.unusedIndexList?.size ?: 0
        if ((count + unusedIndexCount) < number) throw BusinessError("库位【$siteId】的编号超过【$columnId】行的库位数量上限！")
        val result = baseNumberOfColumns[columnId] ?: throw BusinessError("未记录 库区D【$columnId】列的库位基数，请检查配置文件！")
        return result - unusedIndexCount    // 返回值需要除去当前行被移除的库位数量，否则会导致数组越界，(这里处理比较直接，但是可能不容易理解）
    }

    fun getProductInfoStr(code: String?): String {
        try {
            if (code.isNullOrEmpty()) throw BusinessError("请输入产品码！")
            logger.debug("[查询产品信息] to formatted string， code: $code")
            val body = erpHttpClient.productInfoStr(code).execute().body()
            val raw = mapper.readTree(body)
            logger.info("product info from erp is: $raw")
            return """<div>
            <p>订字: ${raw["FContractNo"]}</p>
            <p>工单号: ${raw["FMONO"]}</p>
            <p>业务员名称: ${raw["FEmpName"]}</p>
            <p>客户名称: ${raw["FCustName"]}</p>
            <p>匹数: ${raw["FQtyPiece"]}</p>
            <p>米数: ${raw["FQty"]}</p>
            <div> """.trimIndent()
        } catch (e: Exception) {
            logger.error("productInfo error", e)
            throw BusinessError(e.message)
        }
    }

    fun cancelProductOutTask(ctx: Context) {
        logger.info("取消未创建的出库任务[-]")
        logger.info(ctx.body())
        val body = mapper.readTree(ctx.body())
        if (body["params"]["pwd"].asText() != CUSTOM_CONFIG.authPwd)
            throw BusinessError("请输入正确的授权码")

        val wt = body["workType"].asText()
        val ws = body["workStation"].asText()
        if (wt.isNullOrBlank() || wt != "PS") throw BusinessError("请选择正确的工位进行操作")
        if (ws.isNullOrBlank() || ws != "F") throw BusinessError("请选择正确的岗位进行操作")

        val params = mapper.readTree(body["params"].toString())
        val productInfo = mapper.readTree(params["productInfo"].asText())
        val fbillNo = productInfo["fbillNo"].asText()
        logger.info("$ws - $wt - $fbillNo")

        MongoDBManager.collection<TaskOutInfo>()
            .updateOne(TaskOutInfo::fbillNo eq fbillNo, set(TaskOutInfo::executed setTo true))
        logger.info("取消未创建的出库任务[成功]")

        ctx.status(201)
        ctx.json("OK")
    }

    fun getProductInfo(ctx: Context) {
        val code = ctx.queryParam("code") ?: throw BusinessError("code is required")
        logger.debug(code)
        try {
            val r = getProductInfoStr(code)
            ctx.json(r)
        } catch (e: Exception) {
            logger.error(e.message)
        }
    }

    fun getProductStoreTask(ctx: Context) {
        val code = ctx.queryParam("code") ?: throw BusinessError("code is required")
        logger.debug(code)
        try {
            val r = erpHttpClient.productOutTaskInfo(code).execute().code()
            val msg = "get product-out-task-$code, res from erp: $r"
            logger.debug(msg)
            ctx.json(msg)
        } catch (e: Exception) {
            logger.error(e.message)
        }
    }

    /**
     * @param info 数据源
     * @param raw false: info 是否是从 TaskOutInfo.json 获取的. true: info 是从 ERP 获取的第一手数据
     */
    fun parseTaskOutInfoFromString(info: String, raw: Boolean): TaskOutInfo {
        try {
            val jsonNode = mapper.readTree(info)
            logger.debug("raw: $jsonNode")
            val orders = jsonNode["orders"]
            logger.debug("orders is: $orders")
            val orderObjs: MutableList<Order> = mutableListOf()
            val fmonoList: MutableList<String> = mutableListOf()
            orders.forEach {
                val fmono = if (raw) it["FMONO"].asText() else it["fmono"].asText()
                val fqty = if (raw) it["FQty"].asText() else it["fqty"].asText()
                if (!fmonoList.contains(fmono)) {
                    fmonoList += fmono
                    orderObjs += Order(fmono, fqty)
                }
            }
            logger.debug("orderObjs is: $orderObjs")

            return if (raw) TaskOutInfo(
                jsonNode["FBillNo"].asText(),
                jsonNode["FEmpName"].asText(),
                jsonNode["FCustName"].asText(),
                orderObjs
            )
            else TaskOutInfo(
                jsonNode["fbillNo"].asText(),
                jsonNode["fempName"].asText(),
                jsonNode["fcustName"].asText(),
                orderObjs
            )

        } catch (e: Exception) {
            throw BusinessError(e.message)
        }
    }

    fun productStoreOutFromErp(ctx: Context) {
        val newInfo = parseTaskOutInfoFromString(ctx.body(), true)
        // 检测重复性
        val r = MongoDBManager.collection<TaskOutInfo>().findOne(TaskOutInfo::fbillNo eq newInfo.fbillNo)
        if (r == null) {
            // 持久化任务数据
            MongoDBManager.collection<TaskOutInfo>().insertOne(newInfo)
            logger.debug("新任务: ${newInfo.json}")
        } else {
            logger.error("出库单号[${newInfo.fbillNo}]已存在，details: ${newInfo.json}")
        }
        ctx.status(201)
        ctx.json("ok")
    }

    fun parseProductInfoFromString(info: String): ProductInfo {
        try {
            if (info.isEmpty()) throw BusinessError("请输入产品码！")
            val body = erpHttpClient.productInfoStr(info).execute().body()
            val jsonNode = mapper.readTree(body)
            logger.debug("raw json: $jsonNode")
            return ProductInfo(
                jsonNode["FContractNo"].asText(),
                jsonNode["FMONO"].asText(),
                jsonNode["FEmpName"].asText(),
                jsonNode["FCustName"].asText(),
                jsonNode["FQtyPiece"].asText(),
                jsonNode["FQty"].asText()
            )
        } catch (e: Exception) {
            throw BusinessError(e.message)
        }
    }

    fun setSiteFEmpty(ctx: Context) {
        StoreSiteService.setEmptyIfFilledAndUnlocked("F", "from PAD")
        ctx.json("OK")
        ctx.status(201)
    }

    fun sortProduct(ctx: Context) {
        val sites = doSortProductAutomatically(listOf("E", "D"))
        ctx.json(sites.json)
    }

    // 每 10000 ms 执行一次
    fun sortProductAutomatically() {
        // 自动理库的时间区间 夜间22：00至第二天凌晨4：0
        try {
            val now = Calendar.getInstance().time
            val b = "2020-05-01${now.toInstant().atOffset(ZoneOffset.ofHours(8)).toString().substring(10).split("+")[0]}z"
            val current = Instant.parse(b)

            if (current.isAfter(start) && current.isBefore(end)) {
                // 如果存在未完成的出库或者入库任务，则不创建理库任务
                val unfinishedTasks = MongoDBManager.collection<RobotTask>()
                    .find(RobotTask::def `in` listOf("TaskDefProductIn", "TaskDefProductOut"), RobotTask::state eq RobotTaskState.Created)
                    .toList()
                if (unfinishedTasks.isNotEmpty()) throw BusinessError("存在未完成的入库任务或者出库任务，无法创建理库任务！")

                doSortProductAutomatically(listOf("E", "D"))
            }
        } catch (e: Exception) {
            logger.error("$e")
        }
    }

    @Synchronized
    fun doSortProductAutomatically(siteAreas: List<String>): SitesForSort {
        if (getDefaultSPP() == null || !getDefaultSPP()!!.enableAutoSort) return SitesForSort()

        val sites = getFromSiteAndToSiteOfSort(siteAreas)
        if (sites.fromSiteId != "") {
            val taskDef = getRobotTaskDef("TaskDefSortProduct")
                ?: throw BusinessError("未找到任务定义[TaskDefSortProduct]")
            val newTask = buildTaskInstanceByDef(taskDef)
            newTask.persistedVariables["fromSiteId"] = sites.fromSiteId
            newTask.persistedVariables["toSiteId"] = sites.toSiteId
            RobotTaskService.saveNewRobotTask(newTask)
        }
        return sites
    }

    private fun getFromSiteAndToSiteOfSort(areas: List<String>): SitesForSort {
        for (area in areas) {
            val siteCollection = MongoDBManager.collection<StoreSite>()

            // 获取区域中的所有库位
            val sites = MongoDBManager.collection<StoreSite>()
                .find(Filters.and(StoreSite::type eq area))
                .sort(Sorts.orderBy(Sorts.ascending("_id"))).reversed()
            val count = sites.count()
            logger.info("成品堆放位置【$area】的库位总数为：$count。")
            if (count == 0) continue

            // 计划被整理的库位
            val filledSites = MongoDBManager.collection<StoreSite>()
                .find(Filters.and(StoreSite::type eq area, StoreSite::filled eq true, StoreSite::locked eq false))
                .sort(Sorts.orderBy(Sorts.ascending("_id"))).reversed()
            if (filledSites.count() == 0) {
                logger.info("成品堆放位置【$area】全空，没有需要整理的库位。")
                continue
            }

            // 可用的空库位
            val emptySites = siteCollection
                .find(Filters.and(StoreSite::type eq area, StoreSite::filled eq false, StoreSite::locked eq false))
                .sort(Sorts.orderBy(Sorts.descending("_id"))).reversed()
            if (emptySites.count() == 0) {
                logger.info("成品堆放位置【$area】全满，没有可整理的库位。")
                continue
            }

            val sitesList = sortStoreSiteBySiteId(area, sites)
            logger.debug("sitesList:$sitesList")
            val filledSitesList = sortStoreSiteBySiteId(area, filledSites).reversed()
            logger.debug("filledSitesList:$filledSitesList")
            val emptySitesList = sortStoreSiteBySiteId(area, emptySites)
            logger.debug("emptySitesList:$emptySitesList")

            for (seF in filledSitesList) {
                for (seE in emptySitesList) {
                    val indexF = sitesList.indexOf(seF)
                    val indexE = sitesList.indexOf(seE)
                    if ((seF != seE) && (indexF > indexE)) {
                        // 再次确认两个库位的库位状态，如果其中一个库位状态改变，则直接退出
                        val siteF = siteCollection
                            .findOne(StoreSite::id eq seF, StoreSite::locked eq false, StoreSite::filled eq true)
                            ?: return SitesForSort()
                        val siteE = siteCollection
                            .findOne(StoreSite::id eq seE, StoreSite::locked eq false, StoreSite::filled eq false)
                            ?: return SitesForSort()
                        if (siteF.content.isBlank() || !siteE.content.isBlank()) return SitesForSort()

                        // 如果没有空闲车辆，则直接退出
                        return if (existAvailableVehicle()) SitesForSort(seF, seE) else SitesForSort()
                    }
                }
            }
        }
        return SitesForSort()
    }

    // 只对D区和E区的库位进行操作。
    fun sortStoreSiteBySiteId(type: String, storeSites: List<StoreSite>): List<String> {
        // 1 -> 60; D区(A -> R) & E区
        logger.info("before sort site by id: ${storeSites.map { it.id }}")
        if (type !in listOf("D", "E")) return emptyList()

        val max = if (type == "D") totalSitesOfD else siteCountsOfE
        val list: MutableList<String> = (1..max).map { "" }.toMutableList()
        storeSites.forEach {
            val siteId = it.id
            when (it.type) {
                "D" -> {
                    val parts = siteId.split("-") // parts.len = 3
                    val col = parts[1]    // A ~ R
                    val row = parts[2].trim().toInt()       // number
                    val index = getBaseNumberOfColumn(col, row, siteId) + row
                    list[index - 1] = siteId
                }
                "E" -> {
                    val parts = siteId.split("-") // parts.len = 2
                    val index = parts[1].trim().toInt()
                    list[index - 1] = siteId
                }
                else -> {
                }
            }
        }

        val result = list.filter { it != "" }
        logger.info("after sort site by id: $result")

        return result
    }

    fun existAvailableVehicle(): Boolean {
        val vehicles = VehicleService.listVehicles()
        var availableVehicleCount = vehicles.size

        if (availableVehicleCount > 0) {
            vehicles.map {
                logger.info("$it")
                val transportOrder = it.transportOrder ?: "Park-"
                val prefix = transportOrder.split("-")[0]
                val state = it.state
                /*
                    1、自动理库的时候不会有出库任务，不用考虑正在执行出库任务（出库任务为运单序列）的机器人。
                    2、执行送回空料架任务时，不会影响 D区、E区 的库位状态。
                 */
                if (listOf("Charge", "Park").indexOf(prefix) < 0 ||
                    state == Vehicle.State.ERROR ||
                    state == Vehicle.State.UNKNOWN ||
                    state == Vehicle.State.UNAVAILABLE
                ) availableVehicleCount--
            }
        }

        return if (availableVehicleCount < 1) {
            logger.info("没有可用于执行理库任务的机器人！")
            false
        } else true
    }


    fun submitInFinished(ctx: Context) {
        try {
            val body = ctx.body()
            val info = mapper.readTree(body)
            logger.debug("report [入库完成] manually， Res code: ${erpHttpClient.productStoreIn(info).execute().code()}")
        } catch (e: Exception) {
            logger.error("report [入库完成] manually occurred ERROR ", e.message)
        }
    }

    fun submitOutFinished(ctx: Context) {
        try {
            val body = ctx.body()
            val info = mapper.readTree(body)
            logger.debug("report [出库完成] manually， Res code: ${erpHttpClient.productStoreOut(info).execute().code()}")
        } catch (e: Exception) {
            logger.error("report [出库完成] manually occurred ERROR ", e.message)
        }
    }

    fun submitSortFinished(ctx: Context) {
        try {
            val body = ctx.body()
            val info = mapper.readTree(body)
            logger.debug("report [理库完成] manually， Res code: ${erpHttpClient.productSortFinished(info).execute().code()}")
        } catch (e: Exception) {
            logger.error("report [理库完成] manually occurred ERROR ", e.message)
        }
    }

    fun submit(func: String, info: String, remark: String) {
        backgroundCacheExecutor.submit {
            try {
                val data = mapper.readTree(info)
                when (func) {
                    "product-store-in" -> logger.debug("$remark，data: $data, Res code: ${erpHttpClient.productStoreIn(data).execute().code()}")
                    "product-store-out" -> logger.debug("$remark，data: $data, Res code: ${erpHttpClient.productStoreOut(data).execute().code()}")
                    "product-sort" -> logger.debug("$remark，data: $data, Res code: ${erpHttpClient.productSortFinished(data).execute().code()}")
                    else ->
                        logger.debug("$remark，未知的操作[$func]")
                }
            } catch (e: Exception) {
                logger.error(remark, e)
            }
        }
    }

    fun mockProductInfo(ctx: Context) {
//        ctx.res.contentType = "application/json; charset=UTF-8"
        val code = ctx.queryParam("code")
        logger.debug("mockProductInfo - $code")
        if (code != "null")
            ctx.json(mapper.readTree(
                """{ 
                    |"FContractNo": "订字-$code", 
                    |"FMONO": "$code", 
                    |"FEmpName": "业务员名称1", 
                    |"FCustName": "客户名称1", 
                    |"FQtyPiece": "匹数1", 
                    |"FQty": "米数1" 
                    |}""".trimMargin()
            ))
    }

    fun mockProductInFinished(ctx: Context) {
        val msg = "mock-接收到入库完成信号, received: ${ctx.body()}"
        logger.debug(msg)
        ctx.json(msg)
    }

    fun mockProductOutFinished(ctx: Context) {
        val msg = "mock-接收到出库完成信号, received: ${ctx.body()}"
        logger.debug(msg)
        ctx.json(msg)
    }

    fun mockProductSortFinshed(ctx: Context) {
        val msg = "mock-接收到理库完成信号, received: ${ctx.body()}"
        logger.debug(msg)
        ctx.json(msg)
    }

    fun enableAutoSort(ctx: Context) {
        logger.info("启用/禁用自动理库[-]")
        logger.info(ctx.body())
        val body = mapper.readTree(ctx.body())
        val wt = body["workType"].asText()
        val ws = body["workStation"].asText()
        if (wt.isNullOrBlank() || wt != "ADMIN") throw BusinessError("请选择【管理员】工位进行操作")
        if (ws.isNullOrBlank() || ws != "ADMIN") throw BusinessError("请选择【管理员】岗位进行操作")

        val params = mapper.readTree(body["params"].toString())
        val pwd = params["pwd"].asText()
        logger.info("wt$wt - ws:$ws - pwd:$pwd")
        if (pwd == null || pwd != CUSTOM_CONFIG.authPwd) throw BusinessError("操作失败，密码错误！")

        val enabled = params["enabled"].asBoolean()
        logger.info("wt$wt - ws:$ws - pwd:$pwd - enabled:$enabled")

        logger.info("enable auto sort from ${!enabled} to $enabled.")
        if (getDefaultSPP() == null) {
            MongoDBManager.collection<SysPersistParams>()
                .insertOne(SysPersistParams(SPP_DEFAULT_ID, enabled))
        } else {
            MongoDBManager.collection<SysPersistParams>()
                .updateOne(SysPersistParams::id eq SPP_DEFAULT_ID, set(SysPersistParams::enableAutoSort setTo enabled))
        }

        logger.info("启用/禁用自动理库[成功]")

        ctx.status(201)
        ctx.json("${parseLabel(enabled)}成功！")
    }

    /** 启用/禁用自动理库的文本提示 */
    fun parseLabel(enabled: Boolean): String {
        return if (enabled) "启用" else "禁用"
    }

    fun initSysPersistParamsIfEmpty() {
        if (getDefaultSPP() == null) {
            MongoDBManager.collection<SysPersistParams>().insertOne(SysPersistParams(SPP_DEFAULT_ID, true))
        }
    }

    fun getDefaultSPP(): SysPersistParams? {
        return MongoDBManager.collection<SysPersistParams>().findOne(SysPersistParams::id eq SPP_DEFAULT_ID)
    }

    fun siteInfosToExcel(ctx: Context) {
        val storeSites = MongoDBManager.collection<StoreSite>().find().toList()

        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Stats Reports")
        val firstRow = sheet.createRow(0)

        // "库位名称", "库位类型", "库位描述", "产品码"
        firstRow.createCell(0).setCellValue("库位名称")
        firstRow.createCell(1).setCellValue("库位类型")
        firstRow.createCell(2).setCellValue("库位描述")
        firstRow.createCell(3).setCellValue("产品码")

        storeSites.forEachIndexed { i, ss ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(ss.id)
            row.createCell(1).setCellValue(ss.type)
            row.createCell(2).setCellValue(ss.label)
            row.createCell(3).setCellValue(ss.content)
        }

        ctx.header("Content-Type", "application/octet-stream")
        ctx.header("Content-Disposition", """attachment; filename="report.xlsx"""")

        BufferedOutputStream(ctx.res.outputStream, 1024).use {
            workbook.write(it)
            it.flush()
        }
    }

    @Synchronized
    fun createProductOutTasks(taskInfos: TaskOutInfo) {
        val c = MongoDBManager.collection<TaskOutInfo>()
        // 验证当前请求中的任务任务信息是否是未使用过的
        val fbillNo = taskInfos.fbillNo
        val executed = c.findOne(TaskOutInfo::fbillNo eq fbillNo, TaskOutInfo::executed eq false)
        if (executed == null) {
            val errMsg = "fbillNo=$fbillNo 已经生成相关任务，请刷新列表之后再下单，或者下发后续的任务！"
            logger.error(errMsg)
            throw BusinessError(errMsg)
        }

        val taskDef = getRobotTaskDef("TaskDefProductOut")
            ?: throw BusinessError("未找到任务定义[TaskDefProductOut]")
        // 如果请求中的某个 工单号 未被服务器记录，则整个任务请求报错
        // 工单号未被记录的原因：
        //  - 1、员工操作库位时，将库位上记录的 工单号 清空了。
        val newTasks: MutableList<RobotTask> = mutableListOf()
        taskInfos.orders.forEach { info ->
            val fmono = info.fmono
            val sites = MongoDBManager.collection<StoreSite>()
                .find(Filters.and(StoreSite::content eq fmono, StoreSite::type `in` listOf("D", "E")))

            if (sites.count() == 0)
                throw BusinessError("请求失败：任务信息有误，成品堆放处无此工单号[$fmono]，请检查库位信息")

            sites.forEach { site ->
                val newTask = buildTaskInstanceByDef(taskDef)
                val pv = newTask.persistedVariables
                pv["fmono"] = fmono
                pv["fromSiteId"] = site.id
                logger.info("${newTask.persistedVariables}")
                newTask.priority = 20
                newTasks += newTask
            }
        }

        newTasks.forEach { RobotTaskService.saveNewRobotTask(it) }

        // 更改对应的 TaskOutInfo.executed 为 true
        c.updateOne(TaskOutInfo::fbillNo eq fbillNo, set(TaskOutInfo::executed setTo true))
    }

    fun getToSiteTypeByFromSite(fromSiteId: String): String {
        return if (fromSiteId in listOf("A-A-1", "A-B-1", "B-1")) "E" else "D"
    }
}
