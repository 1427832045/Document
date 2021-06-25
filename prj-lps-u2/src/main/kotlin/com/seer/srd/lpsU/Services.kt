package com.seer.srd.lpsU

import com.mongodb.client.model.Indexes
import com.mongodb.client.model.UpdateOptions
import com.seer.srd.BusinessError
import com.seer.srd.TaskAbortedError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventlog.ModbusReadLog
import com.seer.srd.eventlog.ModbusWriteLog
import com.seer.srd.eventlog.StoreSiteChange
import com.seer.srd.lpsU.ur.UrModbusService
import com.seer.srd.lpsU.ur.UrListener
import com.seer.srd.lpsU.ur.UrListener.srdModBusMap
import com.seer.srd.lpsU.ur.UrListener.urModBusMap
import com.seer.srd.lpsU.ur.UrListener.writeCount
import com.seer.srd.robottask.*
import com.seer.srd.robottask.RobotTaskService.saveNewRobotTask
import com.seer.srd.robottask.RobotTaskService.throwIfTaskAborted
import com.seer.srd.scheduler.GlobalTimer
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.util.HttpClient.buildHttpClient
import com.seer.srd.util.mapper
import io.netty.buffer.ByteBufUtil
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import kotlin.Exception
import kotlin.collections.toList


object Services {

    private val logger = LoggerFactory.getLogger(Services::class.java)

    private val httpClient = buildHttpClient(CUSTOM_CONFIG.versionHost, VersionHttpClient::class.java)

    private val executor = Executors.newScheduledThreadPool(1)

    @Volatile
    var checking = false

    @Volatile
    var operating = false

    fun init() {

        GlobalTimer.executor.scheduleAtFixedRate(::deleteSiteChangeLog, 0, 1, TimeUnit.HOURS)

        MongoDBManager.collection<ProductLineProductType>().updateMany(ProductLineProductType::id exists true, setValue(ProductLineProductType::productType, ""))

        executor.scheduleAtFixedRate(::checkMags, 1, CUSTOM_CONFIG.upCarCheckInterval.toLong(), TimeUnit.SECONDS)
    }

    private fun deleteSiteChangeLog() {
        MongoDBManager.collection<StoreSiteChange>()
            .apply { this.createIndex(Indexes.descending("timestamp")) }
            .deleteMany(StoreSiteChange::timestamp lt Instant.now().minus(1, ChronoUnit.DAYS))
        Thread.sleep(5000)
        MongoDBManager.collection<ModbusReadLog>()
            .apply { this.createIndex(Indexes.descending("createdOn")) }
            .deleteMany(ModbusReadLog::createdOn lt Instant.now().minus(1, ChronoUnit.DAYS))
        Thread.sleep(5000)
        MongoDBManager.collection<ModbusWriteLog>()
            .apply { this.createIndex(Indexes.descending("createdOn")) }
            .deleteMany(ModbusWriteLog::createdOn lt Instant.now().minus(1, ChronoUnit.DAYS))
    }

    private fun checkMags() {
        if (operating) {
            logger.debug("UR is operating, skip Vision ...")
            return
        }
        synchronized(httpClient) {
            try {
                if (checking) return
                checking = true
                Thread.sleep(1000)
                changeStoreSiteByVision("line11")
                Thread.sleep(1000)
                changeStoreSiteByVision("line11", false)
                Thread.sleep(1000)
                changeStoreSiteByVision("line12")
                Thread.sleep(1000)
                changeStoreSiteByVision("line12", false)

            } catch (e: Exception) {
                logger.error(e.message)
            } finally {
              checking = false
            }
        }
    }

    private fun getBooleanByChar(c: Char): Boolean {
        val i = Integer.valueOf(c.toString())
        if (i > 2 || i < 0) throw BusinessError("MagazineInfo格式错误")
        return i == 1
    }

    private fun changeStoreSiteByVision(lineId: String, load: Boolean = true) {
        val body =
            when (lineId) {
              "line11" -> {
                  if (load) httpClient.getLine11Mag().execute().body() ?: ""
                  else httpClient.getLine11MagUn().execute().body() ?: ""
              }
              "line12" -> {
                  if (load) httpClient.getLine12Mag().execute().body() ?: ""
                  else httpClient.getLine12MagUn().execute().body() ?: ""
              }
              else -> ""
            }
        val data = mapper.readTree(body)
        logger.debug("data: $data")
        if (data.isObject) {
            val line = data["Line"].asText() ?: throw BusinessError("Line字段获取失败")
            val carriage = data["Carriage"].asText() ?: throw BusinessError("Carriage字段获取失败")
            val carriageNoTemp = data["CarriageNo"].asText() ?: throw BusinessError("CarriageNo字段获取失败")
            val carriageNo = if (line == "11" && load) "1" else if (line == "11" && !load) "4" else if (line == "12" && load) "2" else if (line == "12" && !load) "5" else throw BusinessError("更新料车失败")
            val magazineInfo = data["MagazineInfo"].asText() ?: throw BusinessError("MagazineInfo字段获取失败")
            if (line != lineId.substring(4)) throw BusinessError("Line异常: $line")
            if (carriage !in listOf("loader", "unloader")) throw BusinessError("carriage异常: $carriage")
            if (magazineInfo.length != 12) throw BusinessError("magazineInfo长度异常: $magazineInfo")
            val type = if (load) "UP" else "DOWN"
            if (magazineInfo.contains("2")) {
                val ids = StoreSiteService.listStoreSites().filter { it.id.contains("CART-$type-$carriageNo") }.map { it.id }
                StoreSiteService.changeSitesFilledByIds(ids, !load, "异常:$magazineInfo")
                logger.warn("carriageNo=${carriageNoTemp}存在视觉识别错误的位置,不更新位置信息到系统")
                return
            }
            val columnA = magazineInfo.substring(0, 6)
            val columnB = magazineInfo.substring(6)

            if (load) {
                MongoDBManager.collection<UpCarProductType>().updateOne(
                    and(UpCarProductType::carNum eq carriageNo, UpCarProductType::column eq "A"),
                    set(UpCarProductType::productType setTo lineId),
                    UpdateOptions().upsert(true)
                )

                MongoDBManager.collection<UpCarProductType>().updateOne(
                    and(UpCarProductType::carNum eq carriageNo, UpCarProductType::column eq "B"),
                    set(UpCarProductType::productType setTo lineId),
                    UpdateOptions().upsert(true)
                )
            }

            val siteAs = StoreSiteService.listStoreSites().filter { it.label == "CART-$type-$carriageNo-A" }.map { it.id }.sorted()
            val siteBs = StoreSiteService.listStoreSites().filter { it.label == "CART-$type-$carriageNo-B" }.map { it.id }.sorted()

            for (i in 0..5) {
                StoreSiteService.changeSiteFilled(siteAs[i], getBooleanByChar(columnA[i]), "change from vision")
                StoreSiteService.changeSiteFilled(siteBs[i], getBooleanByChar(columnB[i]), "change from vision")
            }
        }
    }
    fun updateProductLineProductType(line: String, productType: String) {
        MongoDBManager.collection<ProductLineProductType>().updateOne(ProductLineProductType::line eq line,
            set(ProductLineProductType::productType setTo productType),
            UpdateOptions().upsert(true))
    }
    
    fun getProductTypeOfLine(line: String): String? {
        val r = MongoDBManager.collection<ProductLineProductType>().findOne(ProductLineProductType::line eq line)
        return r?.productType
    }

    fun listProductLinesProductTypes(): List<ProductLineProductType> {
        return MongoDBManager.collection<ProductLineProductType>().find().toList()
    }

    fun listUpCarProductType(): List<UpCarProductType> {
        return MongoDBManager.collection<UpCarProductType>().find().toList()
    }

    fun getProductTypeOfCar(carNum: String, column: String): UpCarProductType? {
        return MongoDBManager.collection<UpCarProductType>().findOne(
            UpCarProductType::carNum eq carNum, UpCarProductType::column eq column)
    }
    /**
     * 返回true表示此时列是空的
     */
    fun isUpCarClear(carNum: String, column: String): Boolean {
        for (i in 1..SITE_NUM_ON_COLUMN_CAR) {
            val siteId = "CART-UP-$carNum-$column-$i"
            val site = StoreSiteService.getExistedStoreSiteById(siteId)
            if (site.filled) return false
        }
        return true
    }

    fun fillUpCarProductType(carNum: String, column: String) {

        val sites = StoreSiteService.listStoreSites().filter { it.label == "CART-UP-$carNum-$column" }
        sites.forEach {
            StoreSiteService.changeSiteFilled(it.id, true, "更新上料车$carNum $column")
        }

        for (line in listOf("line11", "line12")) {
            val productLine = ProductLineService.getLineById(line)
            productLine.warningAlert["onDownCarFill"] = false
            productLine.warningAlert["onUpCarEmpty"] = false
            productLine.warningAlert["onLineUpNoMat"] = false
        }

    }

    fun setUpCarProductType(carNum: String, column: String, productType: String) {
        MongoDBManager.collection<UpCarProductType>().updateOne(
            and(UpCarProductType::carNum eq carNum, UpCarProductType::column eq column),
            set(UpCarProductType::productType setTo productType),
            UpdateOptions().upsert(true)
        )
    }
    
    fun upTaskOnCar(carNum: String, column: String): Boolean {
        val task = MongoDBManager.collection<RobotTask>().findOne(
            RobotTask::state lt RobotTaskState.Success,
            RobotTask::def eq "UpTask"
        ) ?: return false
        val curCarNum = task.persistedVariables["carNum"]
        val curColumn = task.persistedVariables["column"]
        if (carNum == curCarNum && column == curColumn) return true
        return false
    }

    fun ocrTaskOnCar(carNum: String, column: String): Boolean {
        val task = MongoDBManager.collection<RobotTask>().findOne(
            RobotTask::state lt RobotTaskState.Success,
            RobotTask::def eq "recognize"
        ) ?: return false
        val curCarNum = task.persistedVariables["carNum"]
        val categoryA = task.persistedVariables["categoryA"]
        val categoryB = task.persistedVariables["categoryB"]
        if (carNum == curCarNum && column == "A" && categoryA != null) return true
        if (carNum == curCarNum && column == "B" && categoryB != null) return true
        return false
    }

    fun clearCar(carNum: String, location: String) {
        for (i in 1..SITE_NUM_ON_COLUMN_CAR) {
            val siteId = if (carNum.toInt() < 4)
                "CART-UP-$carNum-$location-$i" else "CART-DOWN-$carNum-$location-$i"
            StoreSiteService.changeSiteFilled(siteId, false, "Clear Car")
        }
        for (line in listOf("line11", "line12")) {
            val productLine = ProductLineService.getLineById(line)
            productLine.warningAlert["onDownCarFill"] = false
        }
    }
    
    fun setPathToLine(lineId: String, upOrDown: Int) {
        var value = 0
        if (lineId == "line11") {
            value = if (upOrDown == 1) 2 else 3
            logger.debug("路线：$value")
        }
        if (lineId == "line12") {
            value = if (upOrDown == 1) 6 else 7
            logger.debug("路线：$value")
        }

//        var reset = UrModbusService.helper.read03HoldingRegisters(159, 1, 1, "reset信号")?.getShort(0)?.toInt()
        var reset = urModBusMap["reset"]

        while (reset != 0) {
//            reset = UrModbusService.helper.read03HoldingRegisters(159, 1, 1, "reset信号")?.getShort(0)?.toInt()
            reset = urModBusMap["reset"]
            Thread.sleep(200)
        }

//        UrModbusService.helper.write06SingleRegister(167, value, 1, "路线")
        synchronized(UrListener.lock) {
            srdModBusMap["path"] = value
            writeCount++

        }
    }

    fun setPathToCar(carNum: String, column: String) {
        val columnNum = if (column == "A") 1 else if (column == "B") 2 else throw java.lang.IllegalArgumentException("No such column $column")
        val value =
            when (carNum) {
                "1" -> 1
                "2" -> 5
                "3" -> 9
                "4" -> 4
                "5" -> 8
                else -> throw IllegalArgumentException("No such car $carNum")
            }
        // 等待ur复位,否则写的数据会清空
//        var reset = UrModbusService.helper.read03HoldingRegisters(159, 1, 1, "reset信号")?.getShort(0)?.toInt()
        var reset = urModBusMap["reset"]
        while (reset != 0) {
//            reset = UrModbusService.helper.read03HoldingRegisters(159, 1, 1, "reset信号")?.getShort(0)?.toInt()
            reset = urModBusMap["reset"]
            Thread.sleep(200)
        }
//        UrModbusService.helper.write06SingleRegister(167, value, 1, "路线")
//        UrModbusService.helper.write06SingleRegister(168, columnNum, 1, "位置")
        synchronized(UrListener.lock) {
            srdModBusMap["path"] = value
            srdModBusMap["side"] = columnNum
            writeCount++
        }

    }

    fun takeFromCar2(sites: List<String>, carNum: String, task: RobotTask) {
        var i = 1
        logger.debug("take from car up carNum: $carNum")
        val agvSiteIds = getAgvSiteIds()
        sites.forEach {
            if (i != 1) {
                while (true) {
                    throwIfTaskAborted(task.id)
//                    val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                    val timeout = urModBusMap["timeout"]
                    if (timeout == 1) {
//                        val takeComplete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                        val takeComplete = urModBusMap["takeComplete"]
                        if (takeComplete == 1) {
//                            val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                            val checkOK = srdModBusMap["checkOK"]
//                            while (writeCount > 0) {
//                                Thread.sleep(150)
//                                checkOK = srdModBusMap["checkOK"]
//                            }
                            if (checkOK is Int && checkOK != 0) {
                                synchronized(UrListener.lock) {
                                    srdModBusMap["checkOK"] = 0
                                    writeCount++
                                }
//                                UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                                logger.debug("takeComplete=1, checkOK置为0")
                            }
                        }
                    }
                    if (timeout == 0) {
//                        val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
//                        val takeComplete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                        val checkOK = srdModBusMap["checkOK"]
                        val takeComplete = urModBusMap["takeComplete"]
                        if (checkOK == 0 && takeComplete == 0) {
                            logger.debug("checkOK=0, takeComplete=0, break")
                            break
                        }
                    }
                }
//                val clipNo = UrModbusService.helper.read03HoldingRegisters(164, 1, 1, "弹匣信号")?.getShort(0)?.toInt()
//                val agvBufferNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
                val clipNo = srdModBusMap["clipNo"]
                val agvBufferNo = srdModBusMap["agvBufferNo"]
                // 更新料车库位
                updateSite(carNum, "UP", clipNo as Int)
                // 更新AGV库位
                StoreSiteService.changeSiteFilled("AGV-01-$agvBufferNo", true, "取料完成")
                logger.debug("AGV-01-$agvBufferNo 取料完成")
            }

//            val buffer = MongoDBManager.collection<StoreSite>()
//                .findOne(StoreSite::filled eq false, StoreSite::id `in` agvSiteIds)
                val buffer = StoreSiteService.listStoreSites().filter { s -> !s.filled && agvSiteIds.contains(s.id) }.sortedBy { it.id }[0]
//                ?: throw BusinessError("AGV没有缓存位置了")
            val clipNo = UrModbusService.siteToModbusAddress(it)
            logger.debug("当前要取的位置是：$clipNo")

//            UrModbusService.helper.write06SingleRegister(164, clipNo, 1, "弹匣信号")
//            logger.debug("放到AGV的：${buffer.id[buffer.id.length - 1]}")
//            UrModbusService.helper.write06SingleRegister(165, Character.getNumericValue(buffer.id[buffer.id.length - 1]), 1, "缓存信号")
//            UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
            synchronized(UrListener.lock) {
                srdModBusMap["clipNo"] = clipNo
                logger.debug("放到AGV的：${buffer.id[buffer.id.length - 1]}")
                srdModBusMap["agvBufferNo"] = Character.getNumericValue(buffer.id[buffer.id.length - 1])
                srdModBusMap["checkOK"] = 1
                writeCount++
            }
            i++
        }
        while (true) {
            throwIfTaskAborted(task.id)
//            val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
            val timeout = urModBusMap["timeout"]
            if (timeout == 1) {
//                val takeComplete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                val takeComplete = urModBusMap["takeComplete"]
                if (takeComplete == 1) {
//                    val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                    val checkOK = srdModBusMap["checkOK"]

                    if (checkOK is Int && checkOK != 0) {
                        synchronized(UrListener.lock) {
                            srdModBusMap["checkOK"] = 0
                            logger.debug("takeComplete=1, checkOK置为0")
                            writeCount++
                        }
//                        UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
//                        logger.debug("takeComplete=1, checkOK置为0")
                    }
                }
            }
            if (timeout == 0) {
//                val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
//                val takeComplete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                val checkOK = srdModBusMap["checkOK"]
                val takeComplete = urModBusMap["takeComplete"]
                if (checkOK == 0 && takeComplete == 0) {
                    logger.debug("checkOK=0, takeComplete=0, break")
                    break
                }
            }
        }

        logger.debug("从上料车取完本次需要的所有料${sites.size}个")
//        val endClipNo = UrModbusService.helper.read03HoldingRegisters(164, 1, 1, "弹匣信号")?.getShort(0)?.toInt()
//        val endAGVBufferNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGV缓存信号")?.getShort(0)?.toInt()
        val endClipNo = srdModBusMap["clipNo"]
        val endAGVBufferNo = srdModBusMap["agvBufferNo"]
        updateSite(carNum, "UP", endClipNo as Int)
        StoreSiteService.changeSiteFilled("AGV-01-$endAGVBufferNo", true, "更新AGV缓存")
        logger.debug("AGV-01-$endAGVBufferNo 置满")

        synchronized(UrListener.lock) {
            srdModBusMap["clipNo"] = 0
            srdModBusMap["agvBufferNo"] = 0
            writeCount++
        }
//        UrModbusService.helper.write06SingleRegister(164, 0, 1, "弹匣信号清空")
//        UrModbusService.helper.write06SingleRegister(165, 0, 1, "缓存信号清空")

        i = 1
    }

    fun putOnLine2(lineId: String, task: RobotTask) {
        while (true) {
            try {
                throwIfTaskAborted(task.id)
                val line = ProductLineService.getLineById(lineId)
                line.upModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.standByAddr, 1, 1, "到达${lineId}上料口")
                break
            } catch (e: TaskAbortedError) {
                logger.error("${task.id} aborted")
                return
            } catch (e: Exception) {
                logger.error("line error", e)
                Thread.sleep(2000)
            }
        }

//        val agvSites = MongoDBManager.collection<StoreSite>()
//            .find(StoreSite::filled eq true).filter {
//                it.id.contains("AGV")
//            }.sortedByDescending { it.id }
        val agvSites = StoreSiteService.listStoreSites().filter { it.type == "AGV" && it.filled }.sortedByDescending { it.id }

        if (agvSites.isEmpty()) {
            logger.error("AGV没有料!!")
        } else {
            var i = 1
            val productLine = ProductLineService.getLineById(lineId)
            agvSites.forEach {
                if (i != 1) {

                    // 占用机台资源2
                    productLine.lockUpOrDown(true, task)

                    // 记录释放buffer的时机，避免重复释放引起不必要的modbus交互
                    var urLeaveBuffer = false

                    while (true) {
                        throwIfTaskAborted(task.id)

                        if (!urLeaveBuffer && srdModBusMap["bufferOK"] != 1) {
                            synchronized(UrListener.lock) {
                                srdModBusMap["bufferOK"] = 1
                                writeCount++
                            }
                        }

                        if (!urLeaveBuffer && urModBusMap["urLeaveBuffer"] == 1) {
                            // 释放机台资源2
                            productLine.unLockUpOrDown(true, task)
                            urLeaveBuffer = true
                        }

//                        val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                        val timeout = urModBusMap["timeout"]
                        if (timeout == 1) {
//                            val putComplete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                            val putComplete = urModBusMap["putComplete"]
                            if (putComplete == 1) {
//                                val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                                val checkOK = srdModBusMap["checkOK"]
                                if (checkOK is Int && checkOK != 0) {
                                    synchronized(UrListener.lock) {
                                        srdModBusMap["checkOK"] = 0
                                        srdModBusMap["bufferOK"] = 0
                                        writeCount++
                                    }
//                                    UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                                    logger.debug("putComplete=1, checkOK置为0")
                                }
                            }
                        }
                        if (timeout == 0) {
//                            val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
                            val checkOK = srdModBusMap["checkOK"]
//                            val putComplete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                            val putComplete = urModBusMap["putComplete"]
                            if (checkOK == 0 && putComplete == 0) {
//                                synchronized(UrListener.lock) {
//                                  srdModBusMap["bufferOK"] = 0
//                                  writeCount++
//                                }
                                logger.debug("checkOK=0, putComplete=0, break")
                                break
                            }
                        }
                    }
                    logger.debug("放料完成")
                    MongoDBManager.collection<MatRecord>().insertOne(MatRecord(
                        lineId = lineId,
                        type = getProductTypeOfLine(lineId) ?: "",
                        up = true,
                        recordOn = Instant.now()
                    ))
//                    val lastAGVBuffNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
                    val lastAGVBuffNo = srdModBusMap["agvBufferNo"]
                    StoreSiteService.changeSiteFilled("AGV-01-$lastAGVBuffNo", false, "从AGV位置:${lastAGVBuffNo}上取料")
                    logger.debug("AGV-01-$lastAGVBuffNo 置空")
                    // 释放资源1
//                    productLine.unLockUpOrDown(true, task)
                }
                val agvBufferNo = Character.getNumericValue(it.id[it.id.length - 1])
//                UrModbusService.helper.write06SingleRegister(165, agvBufferNo, 1, "把AGV位置：agvBufferNo 放到机台上")
//                UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
                synchronized(UrListener.lock) {
                    srdModBusMap["agvBufferNo"] = agvBufferNo
                    srdModBusMap["checkOK"] = 1
                    logger.debug("把AGV位置：$agvBufferNo 放到机台上")
                    writeCount++
                }
//                if (i != 1) Thread.sleep(1000)
                // 锁定资源1
//                productLine.lockUpOrDown(true, task)      // 与buffer通讯正常，buffer无报警，1号位置无货物，并占用机台
                i++
            }
            i = 1

            // 占用机台资源2
            productLine.lockUpOrDown(true, task)

            // 记录释放buffer的时机，避免重复释放引起不必要的modbus交互
            var urLeaveBuffer = false

            while (true) {
                throwIfTaskAborted(task.id)

                if (!urLeaveBuffer && srdModBusMap["bufferOK"] != 1) {
                  synchronized(UrListener.lock) {
                    srdModBusMap["bufferOK"] = 1
                    writeCount++
                  }
                }

                if (!urLeaveBuffer && urModBusMap["urLeaveBuffer"] == 1) {
                    // 释放机台资源2
                    productLine.unLockUpOrDown(true, task)
                    urLeaveBuffer = true
                }

//                val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                val timeout = urModBusMap["timeout"]
                if (timeout == 1) {
//                    val takeComplete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                    val putComplete = urModBusMap["putComplete"]
                    if (putComplete == 1) {
//                        val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                        val checkOK = srdModBusMap["checkOK"]
                        if (checkOK is Int && checkOK != 0) {
                            synchronized(UrListener.lock) {
                                srdModBusMap["checkOK"] = 0
                                srdModBusMap["bufferOK"] = 0
                                writeCount++
                            }
//                            UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                            logger.debug("putComplete=1, checkOK置为0")
                        }
                    }
                }
                if (timeout == 0) {
//                    val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
//                    val complete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                    val checkOK = srdModBusMap["checkOK"]
                    val putComplete = urModBusMap["putComplete"]
                    if (checkOK == 0 && putComplete == 0) {
//                        synchronized(UrListener.lock) {
//                          srdModBusMap["bufferOK"] = 0
//                          writeCount++
//                        }
                        logger.debug("checkOK=0, putComplete=0, break")
                        break
                    }
                }
            }
            logger.debug("放料完成")

            MongoDBManager.collection<MatRecord>().insertOne(MatRecord(
                lineId = lineId,
                type = getProductTypeOfLine(lineId) ?: "",
                up = true,
                recordOn = Instant.now()
            ))

//            val endAGVBufferNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
            val endAGVBufferNo = srdModBusMap["agvBufferNo"]
            StoreSiteService.changeSiteFilled("AGV-01-$endAGVBufferNo", false, "AGV最后一个料放完")
            logger.debug("AGV-01-$endAGVBufferNo 最后一个料放完")

            // 释放机台资源1
//            productLine.unLockUpOrDown(true, task)

//            UrModbusService.helper.write06SingleRegister(164, 0, 1, "弹匣信号清空")
//            UrModbusService.helper.write06SingleRegister(165, 0, 1, "缓存信号清空")
            synchronized(UrListener.lock) {
                srdModBusMap["clipNo"] = 0
                srdModBusMap["agvBufferNo"] = 0
                logger.debug("复位AGV的缓存buffer信号")
                writeCount++
            }

            productLine.warningAlert["onUpCarEmpty"] = false
            productLine.warningAlert["onLineUpNoMat"] = false
        }
        while (true) {
            try {
                throwIfTaskAborted(task.id)
                val line = ProductLineService.getLineById(lineId)
                line.upModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.standByAddr, 0, 1, "离开${lineId}上料口")
                break
            } catch (e: TaskAbortedError) {
                logger.error("${task.id} aborted")
                return
            } catch (e: Exception) {
                logger.error("line error", e)
                Thread.sleep(2000)
            }
        }
    }

    fun takeFromLine(task: RobotTask, lineId: String) {
//        val agvSites = MongoDBManager.collection<StoreSite>()
//            .findOne(StoreSite::type eq "AGV" ,StoreSite::filled eq true)
        val agvSites = StoreSiteService.listStoreSites().findLast { it.type == "AGV" && it.filled }

        try {
            val line = ProductLineService.getLineById(lineId)
            line.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.standByAddr, 1, 1, "到达${lineId}下料口")
        }catch (e: Exception) {
            logger.error("line error", e)
            throw BusinessError("到达信号写入失败,${e.message}")
        }


        var filledSites = 0
        var lastIndex = 0   // 记录上次第几个没拿到机台上的物料
        var retryTimes = 0

        if (agvSites != null) {
            logger.error("agv缓存没清空!!")
        } else {
            var i = 1

            val productLine = ProductLineService.getLineById(lineId)

            var dataString: String
            var data: List<Int>

            while (true) {
                if (i > 6) break
                throwIfTaskAborted(task.id)
                if (i != 1) {

                    // 占用机台资源2
                    if (lastIndex != i) productLine.lockUpOrDown(false, task)

                    // 记录释放buffer的时机，避免重复释放引起不必要的modbus交互
                    var urLeaveBuffer = false

                    while (true) {
                        throwIfTaskAborted(task.id)
                        if (lastIndex == i) break

                        if (!urLeaveBuffer && srdModBusMap["bufferOK"] != 1) {
                          synchronized(UrListener.lock) {
                            srdModBusMap["bufferOK"] = 1
                            writeCount++
                          }
                        }

                        if (!urLeaveBuffer && urModBusMap["urLeaveBuffer"] == 1) {
                            // 释放机台资源2
                            productLine.unLockUpOrDown(false, task)
                            urLeaveBuffer = true
                        }

//                        val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                        val timeout = urModBusMap["timeout"]
                        if (timeout == 1) {
//                            UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
//                            val takeComplete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                            val takeComplete = urModBusMap["takeComplete"]
                            if (takeComplete == 1) {
//                                val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                                val checkOK = srdModBusMap["checkOK"]
                                if (checkOK is Int && checkOK != 0) {
                                    synchronized(UrListener.lock) {
                                        srdModBusMap["checkOK"] = 0
                                        srdModBusMap["bufferOK"] = 0
                                        writeCount++
                                    }
//                                    UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                                    logger.debug("takeComplete=1, checkOK置为0")
                                }
                            }
                        }

                        if (timeout == 0) {
//                            val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
//                            val complete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                            val checkOK = srdModBusMap["checkOK"]
                            val takeComplete = urModBusMap["takeComplete"]
                            if (checkOK == 0 && takeComplete == 0) {
//                                synchronized(UrListener.lock) {
//                                  srdModBusMap["bufferOK"] = 0
//                                  writeCount++
//                                }
                                logger.debug("checkOK=0, takeComplete=0, break")
                                filledSites++
                                break
                            }
                        }

                    }

                    if (lastIndex != i) {
                        logger.debug("取料完成")
                        MongoDBManager.collection<MatRecord>().insertOne(MatRecord(
                            lineId = lineId,
                            type = getProductTypeOfLine(lineId) ?: "",
                            up = false,
                            recordOn = Instant.now()
                        ))
//                        val bufferOldNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
                        val bufferOldNo = srdModBusMap["agvBufferNo"]
                        StoreSiteService.changeSiteFilled("AGV-01-$bufferOldNo", true, "往AGV车上放料")
                        logger.debug("向 AGV-01-$bufferOldNo 放料")

                        // 释放资源1
//                        productLine.unLockUpOrDown(false, task)
//                        Thread.sleep(500)
                    }
                }
                logger.debug("准备取走机台物料: $i")
//                val agvSite = MongoDBManager.collection<StoreSite>()
//                    .findOne(StoreSite::filled eq false, StoreSite::type eq "AGV")
                val agvSite = StoreSiteService.listStoreSites().filter { it.type == "AGV" && !it.filled }.sortedBy { it.id }

                if (agvSite.isNotEmpty()) {
                    val bufferNo = Character.getNumericValue(agvSite[0].id[agvSite[0].id.length - 1])

                    dataString = ByteBufUtil.hexDump(productLine.downModbusHelper.read03HoldingRegisters(CUSTOM_CONFIG.bufferAddress.urUpOperationAddr, 10, 1, "${lineId}下料buffer数据"))
                    if (dataString.isEmpty()) throw BusinessError("read buffer data error")
                    data =  dataString.map { Integer.valueOf(it.toString(), 16) }

                    val matNum = data[11]
                    val bufferOne = data[27]
//                    val bufferOneAddr = CUSTOM_CONFIG.bufferAddress.bufferOneAddr
//                    val bufferOne = productLine.downModbusHelper.read03HoldingRegisters(bufferOneAddr, 1, 1, "${lineId}下料buffer1号位置")?.getShort(0)?.toInt()
                    if (bufferOne != 1) {
                        logger.debug("${lineId}机台下料口buffer的1号位置没有物料")
                        val agvFilledSites = StoreSiteService.listStoreSites().filter { it.type == "AGV" && it.filled }
//                            MongoDBManager.collection<StoreSite>().find(
//                            StoreSite::type eq "AGV", StoreSite::filled eq true
//                        ).toList()
                        if (matNum == 0 && agvFilledSites.size >= CUSTOM_CONFIG.leastDownSize) {
//                            logger.debug("AGV上的货物大于等于${CUSTOM_CONFIG.leastDownSize},并且机台上MAG总数量为0,不用等待剩余物料,直接去送料...")
                            logger.debug("机台上剩余物料总数量为0,直接去送料...")
                            lastIndex = i
                            break
                        }
                        else {
                            if (matNum != 0) logger.debug("机台上还有物料...")
                            else logger.debug("AGV上的货物小于${CUSTOM_CONFIG.leastDownSize},继续等待...")
                            lastIndex = i
                            Thread.sleep(1000)
                            retryTimes++
                            if (retryTimes > CUSTOM_CONFIG.retryTimes) {
                                lastIndex = i
                                logger.warn("请求超过${CUSTOM_CONFIG.retryTimes}次, 不再请求机台物料")
                                break
                            }
                            continue
                        }
                    }
//                    UrModbusService.helper.write06SingleRegister(165, bufferNo, 1, "从机台放到AGV位置：$bufferNo 上")
//                    UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
                    synchronized(UrListener.lock) {
                        srdModBusMap["agvBufferNo"] = bufferNo
                        srdModBusMap["checkOK"] = 1
                        writeCount++
                    }
                    logger.debug("准备放到AGV的位置：$bufferNo, checkOK=1")

//                    // 占用机台资源1
//                    productLine.lockUpOrDown(false, task)     // 与buffer通讯正常；buffer无报警；1号位置有货物或者1分钟内没有查到有货物；并占用机台
                    i++
                } else {
                    logger.error("AGV没有可用的位置")
                }
            }

            // 占用机台资源2
            if (lastIndex != i) productLine.lockUpOrDown(false, task)

            // 记录释放buffer的时机，避免重复释放引起不必要的modbus交互
            var urLeaveBuffer = false

            while (true) {

                if (lastIndex == i) break
                throwIfTaskAborted(task.id)

                if (!urLeaveBuffer && srdModBusMap["bufferOK"] != 1) {
                  synchronized(UrListener.lock) {
                    srdModBusMap["bufferOK"] = 1
                    writeCount++
                  }
                }

                if (!urLeaveBuffer && urModBusMap["urLeaveBuffer"] == 1) {
                    // 释放机台资源2
                    productLine.unLockUpOrDown(false, task)
                    urLeaveBuffer = true
                }

//                val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                val timeout = urModBusMap["timeout"]
                if (timeout == 1) {
//                    val takeComplete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                    val takeComplete = urModBusMap["takeComplete"]
                    if (takeComplete == 1) {
//                        val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                        val checkOK = srdModBusMap["checkOK"]
                        if (checkOK is Int && checkOK != 0) {
                            synchronized(UrListener.lock) {
                                srdModBusMap["checkOK"] = 0
                                srdModBusMap["bufferOK"] = 0
                                writeCount++
                            }
//                            UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                            logger.debug("takeComplete=1, checkOK置为0")
                        }
                    }
                }
                if (timeout == 0) {
//                    val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
                    val checkOK = srdModBusMap["checkOK"]
//                    val complete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                    val takeComplete = urModBusMap["takeComplete"]
                    if (checkOK == 0 && takeComplete == 0) {
//                        synchronized(UrListener.lock) {
//                          srdModBusMap["bufferOK"] = 0
//                          writeCount++
//                        }
                        logger.debug("checkOK=0, takeComplete=0, break")
                        filledSites++
                        break
                    }
                }
            }

            if (lastIndex != i) {
                logger.debug("取料完成")

                // 释放资源1
//                productLine.unLockUpOrDown(false, task)

                MongoDBManager.collection<MatRecord>().insertOne(MatRecord(
                    lineId = lineId,
                    type = getProductTypeOfLine(lineId) ?: "",
                    up = false,
                    recordOn = Instant.now()
                ))
//                val endBufferNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
                val endBufferNo = srdModBusMap["agvBufferNo"]
                StoreSiteService.changeSiteFilled("AGV-01-$endBufferNo", true, "产线: $lineId 下料口最后一个取完")
                logger.debug("AGV-01-$endBufferNo 置满")
            }

            logger.debug("下料口${filledSites}个料取完")

            synchronized(UrListener.lock) {
                srdModBusMap["clipNo"] = 0
                srdModBusMap["agvBufferNo"] = 0
                logger.debug("复位AGV缓存信号")
                writeCount++
            }
//            UrModbusService.helper.write06SingleRegister(164, 0, 1, "弹匣信号清空")
//            UrModbusService.helper.write06SingleRegister(165, 0, 1, "缓存信号清空")

            MongoDBManager.collection<TaskExtraParam>().insertOne(
                TaskExtraParam(taskId = task.id, lineId = lineId, type = getProductTypeOfLine(lineId) ?: "", matNum = filledSites.toString())
            )

            task.persistedVariables["downType"] = getProductTypeOfLine(lineId) ?: ""
            task.persistedVariables["downMatNum"] = filledSites
            val pv = task.persistedVariables
            MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq task.id, set(
                RobotTask::persistedVariables setTo pv
            ))
        }
        while (true){
            try {
                throwIfTaskAborted(task.id)
                val line = ProductLineService.getLineById(lineId)
                line.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.standByAddr, 0, 1, "离开${lineId}下料口")
                break
            }catch (e: TaskAbortedError) {
                logger.error("${task.id} aborted")
                return
            } catch (e: Exception) {
                logger.error("line error", e)
                Thread.sleep(2000)
            }
        }
    }

    fun takeFromLine2(lineId: String, num: Int, task: RobotTask) {
        val agvSites = MongoDBManager.collection<StoreSite>()
            .findOne(StoreSite::type eq "AGV" ,StoreSite::filled eq true)

        var filledSites = 0
        var lastIndex = 0   // 记录上次第几个没拿到机台上的物料

        if (agvSites != null) {
            logger.error("agv缓存没清空!!")
        } else {
            var i = 1

            val productLine = ProductLineService.getLineById(lineId)
            while (true) {
                if (i > 6) break
                throwIfTaskAborted(task.id)
                if (i != 1) {
                    while (true) {

                        if (lastIndex == i) break
                        throwIfTaskAborted(task.id)
//                        val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                        val timeout = urModBusMap["timeout"]
                        if (timeout == 1) {
//                            val takeComplete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                            val takeComplete = urModBusMap["takeComplete"]
                            if (takeComplete == 1) {
                                synchronized(UrListener.lock) {
                                    srdModBusMap["checkOK"] = 0
                                    writeCount++
                                }
//                                UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                                logger.debug("takeComplete=1, checkOK置为0")
                                filledSites++
                            }
                        }

                        if (timeout == 0) {
//                            val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
//                            val complete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                            val checkOK = srdModBusMap["checkOK"]
                            val takeComplete = urModBusMap["takeComplete"]
                            if (checkOK == 0 && takeComplete == 0) {
                                logger.debug("checkOK=0, takeComplete=0, break")
                                break
                            }
                        }

                    }

                    productLine.unLockUpOrDown(false, task)
                    if (lastIndex != i) {
                        logger.debug("取料完成")
//                        val bufferOldNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
                        val bufferOldNo = srdModBusMap["agvBufferNo"]
                        StoreSiteService.changeSiteFilled("AGV-01-$bufferOldNo", true, "往AGV车上放料")
                        logger.debug("向 AGV-01-$bufferOldNo 放料")
                        Thread.sleep(1000)
                    }
                }
                logger.debug("取走机台物料: $i")
                val agvSite = MongoDBManager.collection<StoreSite>()
                    .findOne(StoreSite::filled eq false, StoreSite::type eq "AGV")
                if (agvSite != null) {
                    val bufferNo = Character.getNumericValue(agvSite.id[agvSite.id.length - 1])
                    productLine.lockUpOrDown(false, task)     // 与buffer通讯正常；buffer无报警；1号位置有货物或者1分钟内没有查到有货物；并占用机台

                    val bufferOneAddr = CUSTOM_CONFIG.bufferAddress.bufferOneAddr
                    val bufferOne = productLine.downModbusHelper.read03HoldingRegisters(bufferOneAddr, 1, 1, "${lineId}下料buffer1号位置")?.getShort(0)?.toInt()
                    if (bufferOne != 1) {
                        logger.debug("${lineId}下料口buffer1号位置没有物料, 查看AGV上的货物是否大于3...")
                        val agvFilledSites = MongoDBManager.collection<StoreSite>().find(
                            StoreSite::type eq "AGV", StoreSite::filled eq true
                        ).toList()
                        if (agvFilledSites.size > 3) {
                            logger.debug("AGV上的货物大于3，不用等待剩余物料，直接去送料...")
                            lastIndex = i
                            break
                        }
                        else {
                            logger.debug("AGV上的货物小于3，继续等待...")
                            lastIndex = i
                            Thread.sleep(200)
                            continue
                        }
                    }
                    synchronized(UrListener.lock) {
                        srdModBusMap["agvBufferNo"] = bufferNo
                        srdModBusMap["checkOK"] = 1
                        writeCount++
                    }
//                    UrModbusService.helper.write06SingleRegister(165, bufferNo, 1, "从机台放到AGV位置：$bufferNo 上")
//                    UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
                    logger.debug("放到AGV的位置：$bufferNo, checkOK=1")
                    i++
                } else {
                    logger.error("AGV没有可用的位置")
                }
            }
            while (true) {

                if (lastIndex == i) break
                throwIfTaskAborted(task.id)
//                val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                val timeout = urModBusMap["timeout"]
                if (timeout == 1) {
//                    val takeComplete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                    val takeComplete = urModBusMap["takeComplete"]
                    if (takeComplete == 1) {
                        synchronized(UrListener.lock) {
                            srdModBusMap["checkOK"] = 0
                            writeCount++
                        }
//                        UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                        logger.debug("takeComplete=1, checkOK置为0")
                        filledSites++
                    }
                }
                if (timeout == 0) {
//                    val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
//                    val complete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                    val checkOK = srdModBusMap["checkOK"]
                    val takeComplete = urModBusMap["takeComplete"]
                    if (checkOK == 0 && takeComplete == 0) {
                        logger.debug("checkOK=0, takeComplete=0, break")
                        break
                    }
                }
            }
            // 释放资源
            productLine.unLockUpOrDown(false, task)

            if (lastIndex != i) {
                logger.debug("取料完成")
//                val endBufferNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
                val endBufferNo = srdModBusMap["agvBufferNo"]
                StoreSiteService.changeSiteFilled("AGV-01-$endBufferNo", true, "产线: $lineId 下料口最后一个取完")
                logger.debug("AGV-01-$endBufferNo 置满")
            }

            logger.debug("下料口${filledSites}个料取完")

        }
    }

    fun putOnCar2(lineId: String, carNum: String, column: String, neededSites: List<String>, task: RobotTask) {
        val productLine = ProductLineService.getLineById(lineId)
//        val agvSites = MongoDBManager.collection<StoreSite>()
//            .find(StoreSite::filled eq true).filter {
//                it.id.contains("AGV")
//            }.sortedByDescending { it.id }
        val agvSites = StoreSiteService.listStoreSites().filter { it.type == "AGV" && it.filled }.sortedByDescending { it.id }

        if (neededSites.size != agvSites.size) {
            logger.error("agv上的物料数量与将要放到空料车的数量不符!! agv size:${agvSites.size}，need: ${neededSites.size}")
        } else {
            if (agvSites.isEmpty()) {
                logger.error("AGV没有料!!")
            } else {
                var i = 1
                agvSites.forEach {
                    if (i != 1) {
                        while (true) {
                            throwIfTaskAborted(task.id)
//                            val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                            val timeout = urModBusMap["timeout"]
                            if (timeout == 1) {
//                                val putComplete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                                val putComplete = urModBusMap["putComplete"]
                                if (putComplete == 1) {
//                                    val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                                    val checkOK = srdModBusMap["checkOK"]
                                    if (checkOK is Int && checkOK != 0) {
                                        synchronized(UrListener.lock) {
                                            srdModBusMap["checkOK"] = 0
                                            writeCount++
                                        }
//                                        UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                                        logger.debug("放料完成, checkOK置为0")
                                    }
                                }
                            }
                            if (timeout == 0) {
//                                val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
//                                val complete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                                val checkOK = srdModBusMap["checkOK"]
                                val putComplete = urModBusMap["putComplete"]
                                if (checkOK == 0 && putComplete == 0) {
                                    logger.debug("checkOK=0, putComplete=0, break")
                                    break
                                }
                            }
                        }
//                        val agvBufferNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
                        val agvBufferNo = srdModBusMap["agvBufferNo"]
                        StoreSiteService.changeSiteFilled("AGV-01-$agvBufferNo", false, "AGVBuffer $agvBufferNo 置空")

//                        val clipNo = UrModbusService.helper.read03HoldingRegisters(164, 1, 1, "空料车弹匣信号")?.getShort(0)?.toInt()
                        val clipNo = srdModBusMap["clipNo"]
                        val id = UrModbusService.modbusAddressToSite(clipNo as Int)
                        StoreSiteService.changeSiteFilled("CART-DOWN-$carNum-$column-$id", true, "空料车$carNum $column $id 放料完成")
                        logger.debug("空料车$carNum $column 列 $id 号放料完成")
                    }
                    val site = neededSites[i - 1]
                    val bufferNo = Character.getNumericValue(it.id[it.id.length - 1])
                    logger.debug("从$bufferNo 取")
                    val clipNo = UrModbusService.siteToModbusAddress(site)

                    synchronized(UrListener.lock) {
                        srdModBusMap["clipNo"] = clipNo
                        srdModBusMap["agvBufferNo"] = bufferNo
                        srdModBusMap["checkOK"] = 1
                        writeCount++
                    }
//                    UrModbusService.helper.write06SingleRegister(164, clipNo, 1 , "空料车弹匣信号")
//                    UrModbusService.helper.write06SingleRegister(165, bufferNo, 1 , "AGVBuffer信号")
//                    UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
                    logger.debug("往空料车$carNum 的$column $clipNo 放置, checkOK=1")
                    i++
                }
                while (true) {
                    throwIfTaskAborted(task.id)
//                    val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                    val timeout = urModBusMap["timeout"]
                    if (timeout == 1) {
//                        val putComplete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                        val putComplete = urModBusMap["putComplete"]
                        if (putComplete == 1) {
//                            val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                            val checkOK = srdModBusMap["checkOK"]
                            if (checkOK is Int && checkOK != 0) {
                                synchronized(UrListener.lock) {
                                    srdModBusMap["checkOK"] = 0
                                    writeCount++
                                }
//                                UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                                logger.debug("放料完成, checkOK置为0")
                            }
                        }
                    }
                    if (timeout == 0) {
//                        val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
//                        val complete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                        val checkOK = srdModBusMap["checkOK"]
                        val putComplete = urModBusMap["putComplete"]
                        if (checkOK == 0 && putComplete == 0) {
                            logger.debug("checkOK=0, putComplete=0, break")
                            break
                        }
                    }
                }
                logger.debug("放料完成")
                val site = neededSites[i - 2]
//                val endClipNo = UrModbusService.helper.read03HoldingRegisters(164, 1, 1, "空料车弹匣信号")?.getShort(0)?.toInt()
                val endClipNo = srdModBusMap["clipNo"]
                val id = UrModbusService.modbusAddressToSite(endClipNo as Int)
                StoreSiteService.changeSiteFilled(site, true, "空料车$carNum $column $id 放料完成")
                logger.debug("空料车$carNum $column $id 放料完成")

//                val endBufferNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
                val endBufferNo = srdModBusMap["agvBufferNo"]
                StoreSiteService.changeSiteFilled("AGV-01-$endBufferNo", false, "AGVBuffer $endBufferNo 置空")
                logger.debug("AGVBuffer $endBufferNo 置空")

                synchronized(UrListener.lock) {
                    srdModBusMap["agvBufferNo"] = 0
                    srdModBusMap["clipNo"] = 0
                    logger.debug("复位agvBufferNo和clipNo")
                    writeCount++
                }
//                UrModbusService.helper.write06SingleRegister(164, 0, 1, "弹匣信号清空")
//                UrModbusService.helper.write06SingleRegister(165, 0, 1, "缓存信号清空")

                i = 1
                productLine.warningAlert["onDownCarFill"] = false
            }
        }
    }

    fun checkLineUp(ctx: ProcessingContext) {
        val lineId = ctx.task.workStations[0]
        val productLine = ProductLineService.getLineById(lineId)
        val bufferAddress = CUSTOM_CONFIG.bufferAddress
        try {
            var dataString = ByteBufUtil.hexDump(productLine.upModbusHelper.read03HoldingRegisters(0, 10, 1, "${lineId}上料buffer数据"))
            if (dataString.isEmpty()) throw BusinessError("read buffer data error")
            var data =  dataString.map { Integer.valueOf(it.toString(), 16) }
            var matNum = data[11]
            var errorCode = data[23]

//            var matNum = productLine.upModbusHelper.read03HoldingRegisters(bufferAddress.matNumAddr, 1, 1, "${lineId}上料buffer物料数量")?.getShort(0)?.toInt()
//            var errorCode = productLine.upModbusHelper.read03HoldingRegisters(bufferAddress.errorCodeAddr, 1, 1, "${lineId}buffer错误码")?.getShort(0)?.toInt()
            if (matNum == 0 && errorCode == 0) ctx.task.persistedVariables["canUp"] = true
            else {
                logger.debug("等待机台可用")
                while (true) {
                    throwIfTaskAborted(ctx.task.id)
                    Thread.sleep(2000)
                    dataString = ByteBufUtil.hexDump(productLine.upModbusHelper.read03HoldingRegisters(0, 10, 1, "${lineId}上料buffer数据"))
                    data =  dataString.map { Integer.valueOf(it.toString(), 16) }
                    matNum = data[11]
                    errorCode = data[23]
//                    matNum = productLine.upModbusHelper.read03HoldingRegisters(bufferAddress.matNumAddr, 1, 1, "${lineId}上料buffer物料数量")?.getShort(0)?.toInt()
//                    errorCode = productLine.upModbusHelper.read03HoldingRegisters(bufferAddress.errorCodeAddr, 1, 1, "${lineId}buffer错误码")?.getShort(0)?.toInt()

                    if (matNum != 0 && productLine.warningAlert["onLineUpButNotAllEmpty"] != true) {
                        productLine.warningAlert["onLineUpButNotAllEmpty"] = true
                        WebSockets.onLineUpButNotAllEmpty(LineUpButNotAllEmptyMessage(lineId))
                        productLine.switchLineAlert(true)
                        logger.debug("上料异常, $lineId 上料口有料")
                    }
                    if (errorCode is Int && errorCode != 0 && productLine.warningAlert["BufferError"] != true) {
                        productLine.warningAlert["BufferError"] = true
                        WebSockets.onBufferError(BufferErrorMessage(lineId, "up", getMsgOfBufferErrorCode(errorCode)))
                        productLine.switchLineAlert(true)
                        logger.debug("$lineId ${getMsgOfBufferErrorCode(errorCode)}")
//                        productLine.warningAlert["BufferError"] = false
                    }
                    if (matNum == 0 && errorCode == 0) {
                        logger.debug("$lineId 可以上料")
                        productLine.warningAlert["onLineUpButNotAllEmpty"] = false
                        ctx.task.persistedVariables["canUp"] = true
                        break
                    }
                }
            }
        } catch (e: TaskAbortedError) {
            logger.error("task ${ctx.task.id} aborted")
            return
        } catch (e: Exception) {
            logger.error("check $lineId error", "$e")
        }
    }

    fun checkLineDown(ctx: ProcessingContext) {
        val lineId = ctx.task.workStations[0]
        val productLine = ProductLineService.getLineById(lineId)
        val bufferAddress = CUSTOM_CONFIG.bufferAddress

        val dataString = ByteBufUtil.hexDump(productLine.downModbusHelper.read03HoldingRegisters(0, 10, 1, "${lineId}下料buffer数据"))
        if (dataString.isEmpty()) throw BusinessError("read buffer data error")
        val data =  dataString.map { Integer.valueOf(it.toString(), 16) }
        val matNum = data[11]
        val errorCode = data[23]
        val one = data[27]

//        val matNum = productLine.downModbusHelper.read03HoldingRegisters(bufferAddress.matNumAddr, 1, 1, "${lineId}下料buffer物料数量")?.getShort(0)?.toInt()
//        val one = productLine.downModbusHelper.read03HoldingRegisters(bufferAddress.bufferOneAddr, 1, 1, "${lineId}buffer的取料位")?.getShort(0)?.toInt()
//        val errorCode = productLine.downModbusHelper.read03HoldingRegisters(bufferAddress.errorCodeAddr, 1, 1, "${lineId}buffer错误码")?.getShort(0)?.toInt()
        if (matNum is Int && one is Int && errorCode is Int && one == 1 && matNum >= CUSTOM_CONFIG.leastDownSize ) {
            if (errorCode == 0) ctx.task.persistedVariables["canDown"] = 1
            else throw BusinessError(getMsgOfBufferErrorCode(errorCode))
        }
        else {
            if (productLine.warningAlert["onLineDownFail"] != true) {
                productLine.warningAlert["onLineDownFail"] = true
                WebSockets.onLineDownFail(LineDownFailMessage(lineId))
                productLine.switchLineAlert(true)
                logger.debug("$lineId 下料异常,少料")
                productLine.warningAlert["onLineDownFail"] = false
            }
            if (errorCode is Int && errorCode != 0 && productLine.warningAlert["BufferError"] != true) {
                productLine.warningAlert["BufferError"] = true
                WebSockets.onBufferError(BufferErrorMessage(lineId, "down", getMsgOfBufferErrorCode(errorCode)))
                productLine.switchLineAlert(true)
                logger.debug("$lineId ${getMsgOfBufferErrorCode(errorCode)}")
            }
        }
    }

    // 下料车有2辆
    fun checkDownCar2(ctx: ProcessingContext) {
//        val column = ctx.task.persistedVariables["column"] as String
        val downSize = StoreSiteService.listStoreSites().filter { it.type == "AGV" && it.filled }.size
        val carNo = ctx.task.persistedVariables["carNum"] as String
        for (column in listOf("A", "B")) {
            val sites = StoreSiteService.listStoreSites().filter { it.id.contains("CART-DOWN-$carNo-$column") && !it.filled }.sortedByDescending { it.id }
            if (sites.size == 6) {
                ctx.task.persistedVariables["neededSites"] = sites.stream().limit(downSize.toLong()).collect(Collectors.toList()).map { it.id }
                var ids = ""
                (ctx.task.persistedVariables["neededSites"] as List<String>).forEach{
                    ids += "$it,"
                }
                ctx.task.persistedVariables["column"] = column
                MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
                logger.debug("找到可以放的位置：${ids.substring(0, ids.length - 1)}")
                break
            }
            if (column == "B" && ctx.transport?.stages!![0].location.contains("WAIT") || ctx.transportDef?.stages!![0].location.contains("WAIT")) {
                throw BusinessError("等待可用空料车...")
            }
        }
    }

    fun checkDownCar(ctx: ProcessingContext) {
        val column = ctx.task.persistedVariables["column"] as String
        val downSize = StoreSiteService.listStoreSites().filter { it.type == "AGV" && it.filled }.size
        for (i in 4..5) {
//            val sites = MongoDBManager.collection<StoreSite>().find(
//                StoreSite::type eq "DOWN-SITE-$i").sort(Sorts.descending("_id")).filter {
//                it.id.contains("-$column-") && !it.filled
//            }
            val sites = StoreSiteService.listStoreSites().filter { it.id.contains("CART-DOWN-$i-$column") && !it.filled }

            if (sites.size >= downSize) {
                ctx.task.persistedVariables["carNum"] = i.toString()
                ctx.task.persistedVariables["neededSites"] = sites.stream().limit(downSize.toLong()).collect(Collectors.toList()).map { it.id }
                var ids = ""
                (ctx.task.persistedVariables["neededSites"] as List<String>).forEach{
                    ids += "$it,"
                }
                logger.debug("找到可以放的位置：${ids.substring(0, ids.length - 1)}")
                break
            }
            if (i == 5) {
                if (ctx.transport?.stages!![0].location.contains("WAIT") || ctx.transportDef?.stages!![0].location.contains("WAIT")) {
                    throw BusinessError("等待可用空料车...")
                }
            }
        }
    }

    fun getOcrData() {
//        UrModbusService.helper.write06SingleRegister(169, 1, 1, "识别")
        synchronized(UrListener.lock) {
            srdModBusMap["ocr"] = 1
            writeCount++
        }
        logger.debug("开始识别...")
//        var flag = UrModbusService.helper.read03HoldingRegisters(162, 1, 1, "已获取一列数据信号")?.getShort(0)?.toInt()
        var flag = urModBusMap["getORC"]
        while (flag != 1)  {
//            flag = UrModbusService.helper.read03HoldingRegisters(162, 1, 1, "已获取一列数据信号")?.getShort(0)?.toInt()
            flag = urModBusMap["getORC"]
        }
        logger.debug("OCR识别结束")
    }

    fun resetAll() {
        logger.debug("驱使UR复位")
        synchronized(UrListener.lock) {
            srdModBusMap["driveReset"] = 1
            writeCount++
        }
//        UrModbusService.helper.write06SingleRegister(163, 1, 1, "驱使复位信号")
//        var flag = UrModbusService.helper.read03HoldingRegisters(159, 1, 1, "ur复位信号")?.getShort(0)?.toInt()
        var flag = urModBusMap["reset"]

//        var i = 1
        while(flag != 1) {
//            i++
//            if (i%3000 == 0) logger.debug("等待UR复位...")
//            flag = UrModbusService.helper.read03HoldingRegisters(159, 1, 1, "ur复位信号")?.getShort(0)?.toInt()
            Thread.sleep(200)
            flag = urModBusMap["reset"]
        }
        logger.debug("UR复位成功")

        UrListener.flag = true

        synchronized(UrListener.lock) {
            srdModBusMap["clipNo"] = 0
            srdModBusMap["agvBufferNo"] = 0
            srdModBusMap["path"] = 0
            srdModBusMap["side"] = 0
            srdModBusMap["ocr"] = 0
            srdModBusMap["bufferClipNo"] = 0
            srdModBusMap["checkOK"] = 0
            srdModBusMap["bufferOK"] = 0
            srdModBusMap["driveReset"] = 0
            writeCount++
        }
//        UrModbusService.helper.write06SingleRegister(164, 0, 1, "料车弹匣复位信号")
//        logger.debug("弹匣信号复位成功")
//
//        UrModbusService.helper.write06SingleRegister(165, 0, 1, "AGV缓存复位信号")
//        logger.debug("AGV缓存信号成功")
//
//        UrModbusService.helper.write06SingleRegister(170, 0, 1, "机台弹匣复位信号")
//        logger.debug("机台弹匣信号复位成功")
//
//        UrModbusService.helper.write06SingleRegister(167, 0, 1, "动作复位信号")
//        logger.debug("动作信号复位成功")
//
//        UrModbusService.helper.write06SingleRegister(168, 0, 1, "料车位置复位信号")
//        logger.debug("料车位置信号复位成功")
//
//        UrModbusService.helper.write06SingleRegister(169, 0, 1, "OCR识别复位信号")
//        logger.debug("OCR识别信号复位成功")
//
//        UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK复位信号")
//        logger.debug("checkOK复位成功")
//
//        UrModbusService.helper.write06SingleRegister(163, 0, 1, "驱使复位信号")
//        logger.debug("驱使信号复位成功")
    }
    @Synchronized
    fun createOCRTask(carNum: String, categoryA: String?, categoryB: String?) {
        // 格式： CART-UP-$carNum-$location-$i
        val def = getRobotTaskDef("recognize") ?: throw BusinessError("NoSuchTaskDef: 'recognize'")
        val task = buildTaskInstanceByDef(def)
        task.persistedVariables["carNum"] = carNum
        task.persistedVariables["categoryA"] = categoryA
        task.persistedVariables["categoryB"] = categoryB
        task.priority = 20
        saveNewRobotTask(task)
    }

    fun updateSite(carNum: String, type: String, id: Int) {
        val site = UrModbusService.modbusAddressToSite(id)
        val column = if (id > SITE_NUM_ON_COLUMN_CAR) "B" else "A"
        val siteId = "CART-$type-$carNum-$column-$site"
        val value = type == "DOWN"
        val remark = if (type == "DOWN") "放料完成" else "取料完成"
        StoreSiteService.changeSiteFilled(siteId, value, remark)
    }

    fun updateCategoryByCarNum(carNum: String, categoryA: String?, categoryB: String?) {
        if (carNum !in listOf("1", "2")) throw BusinessError("无效的料车编号: $carNum")
        if (!categoryA.isNullOrBlank()) {
            MongoDBManager.collection<StoreSite>()
                .updateMany(StoreSite::label eq "CART-UP-$carNum-A", StoreSite::filled setTo true)
        }
        if (!categoryB.isNullOrBlank()) {
            MongoDBManager.collection<StoreSite>()
                .updateMany(StoreSite::label eq "CART-UP-$carNum-B", StoreSite::filled setTo true)
        }
    }

    fun checkUpCarEmptyOrNoNeededMag2(lineId: String) {

        val productLine = ProductLineService.getLineById(lineId)

        for (carNo in 1..2) {
            val carNum = carNo.toString()
            val type = getProductTypeOfCar(carNum, "A")
            if (type?.productType != lineId) continue
            val sites = StoreSiteService.listStoreSites().filter { it.id.contains("CART-UP-$carNum") }

            val availableSites = sites.filter { it.filled }
            if (availableSites.isEmpty()) {
                if (productLine.warningAlert["onUpCarEmpty"] == true) continue
                productLine.warningAlert["onUpCarEmpty"] = true
                WebSockets.onUpCarEmpty(UpCarEmptyMessage(carNum, ""))
                productLine.switchLineAlert(true)
                logger.debug("告警, 产线${lineId}的上料车${carNum}空, 请及时补充")
                continue
            }
        }
    }

    fun checkUpCarEmptyOrNoNeededMag(lineId: String) {

        val productLine = ProductLineService.getLineById(lineId)
        val productType = Services.getProductTypeOfLine(lineId) ?: ""
        // 产线没设置生产类型，不需要上料

        for (carNo in 1..2) {
            for (carColumn in "AB") {
                val carNum = carNo.toString()
                val column = carColumn.toString()

                // 先把这列库位拿出来
//                val sites = MongoDBManager.collection<StoreSite>().find(
//                    StoreSite::label eq "CART-UP-$carNum-$column").toList()
                val sites = StoreSiteService.listStoreSites().filter { it.id.contains("CART-UP-$carNum-$column") }

                // 没有设置这辆车
                if (sites.isNullOrEmpty()) continue

                // 这个列已占用弹匣数量
                val availableSites = sites.filter { it.filled }

                // 先找和产线相同type的列
                val type = getProductTypeOfCar(carNum, column)
                if (type?.productType == productType) {
                    // 找到空列，告警
                    if (availableSites.isNullOrEmpty()) {
                        if (productLine.warningAlert["onUpCarEmpty"] == true) continue
                        productLine.warningAlert["onUpCarEmpty"] = true
                        WebSockets.onUpCarEmpty(UpCarEmptyMessage(carNum, column))
                        productLine.switchLineAlert(true)
                        logger.debug("异常告警, 上料车$carNum${column}列需要的料${lineId}已用完, 请及时补充")
                        continue
                    }
                }
            }
        }
        if (productType != "") {
            for (carNo in 1..2) {
                for (carColumn in "AB") {
                    val carNum = carNo.toString()
                    val column = carColumn.toString()
                    // 先找和产线相同type的列
                    val type = getProductTypeOfCar(carNum, column)
                    if (type?.productType == productType) return
                }
            }
            // onUpCarEmpty检查过，就不需要进行onLineUpNoMat检查，属于互斥
            if (productLine.warningAlert["onUpCarEmpty"] == true) return
            if (productLine.warningAlert["onLineUpNoMat"] == true) return
            productLine.warningAlert["onLineUpNoMat"] = true
            WebSockets.onLineUpNoMat(LineUpNoMatMessage(lineId, "", "", productType))
            productLine.switchLineAlert(true)
            logger.debug("异常告警, 上料车没有${lineId}需要的料$productType, 请及时补充")
        }
    }

    // 下料车有2辆，1条产线1辆
    fun checkDownCarFull(lineId: String) {
        val productLine = ProductLineService.getLineById(lineId)
        for (carNum in 4..5) {
            for (column in listOf("A", "B")) {
                val sites = StoreSiteService.listStoreSites().filter { it.id.contains("CART-DOWN-$carNum-$column") }
                // 没有设置这辆车
                if (sites.isNullOrEmpty()) continue
                // 这个列可用的空位置数量
                val availableSites = sites.filter { !it.filled }

                if (availableSites.size < CUSTOM_CONFIG.checkDownSize) {
                    val existed = MongoDBManager.collection<RobotTask>().findOne(and(
                        RobotTask::state lt RobotTaskState.Success,
                        RobotTask::def eq "DownTask"
                    ))
                    if (existed?.persistedVariables?.get("lineId") == lineId) return
                    if (productLine.warningAlert["onDownCarFill"] != true) {
                        productLine.warningAlert["onDownCarFill"] = true
                        WebSockets.onDownCarFill(DownCarFillMessage(carNum.toString(), column))
                        productLine.switchLineAlert(true)
                    }
//                    logger.debug("下料车${carNum}满 告警")
                }
            }
        }
    }

    fun checkDownCarFull2(lineId: String) {
        val productLine = ProductLineService.getLineById(lineId)
        val column = if (lineId == "line11") "A" else "B"

        for (carNum in 4..5) {

            // 先把这列库位拿出来
//            val sites = MongoDBManager.collection<StoreSite>().find(
//                StoreSite::label eq "CART-DOWN-$carNum-$column").toList()
            val sites = StoreSiteService.listStoreSites().filter { it.id.contains("CART-DOWN-$carNum-$column") }

            // 没有设置这辆车
            if (sites.isNullOrEmpty()) continue

            // 这个列可用的空位置数量
            val availableSites = sites.filter { !it.filled }

            if (availableSites.size < CUSTOM_CONFIG.checkDownSize) {
                val existed = MongoDBManager.collection<RobotTask>().findOne(and(
                    RobotTask::state lt RobotTaskState.Success,
                    RobotTask::def eq "DownTask"
                ))
                if (existed?.persistedVariables?.get("lineId") == lineId) return
                if (productLine.warningAlert["onDownCarFill"] != true) {
                    productLine.warningAlert["onDownCarFill"] = true
                    WebSockets.onDownCarFill(DownCarFillMessage(carNum.toString(), column))
                    productLine.switchLineAlert(true)
                }
//                logger.debug("下料车${carNum}满 告警")
            }
        }
    }
    private fun getAgvSiteIds(): List<String> {
        return StoreSiteService.listStoreSites().filter { it.type == "AGV" }.map { it.id }.sorted()
//        return MongoDBManager.collection<StoreSite>().find(StoreSite::type eq "AGV").toList().map { it.id }
    }

    fun getMsgOfBufferErrorCode(code: Int): String {
        return when (code) {
            1 -> "气缸动作超时!!"
            2 -> "电机报错!!"
            else -> "Buffer异常!!"
        }
    }
}

data class ProductLineProductType(
    @BsonId val id: ObjectId = ObjectId(),
    val line: String = "",
    val productType: String = ""
)

data class UpCarProductType(
    @BsonId val id: ObjectId,
    val carNum: String,
    val column: String, // A B
    val productType: String
)

data class MatRecord(
    @BsonId val id: ObjectId = ObjectId(),
    val lineId: String,
    val type: String,
    val up: Boolean,
    val recordOn: Instant
)

data class TaskExtraParam(
    @BsonId val id: ObjectId = ObjectId(),
    val taskId: String = "",
    val lineId: String = "",
    val type: String = "",
    val matNum: String = ""
)

interface VersionHttpClient {

    // SRD 请求 version，查询产品信息
    @GET("11:loader")
    fun getLine11Mag(): Call<String>

    @GET("11:unloader")
    fun getLine11MagUn(): Call<String>

    @GET("12:loader")
    fun getLine12Mag(): Call<String>

    @GET("12:unloader")
    fun getLine12MagUn(): Call<String>

}
