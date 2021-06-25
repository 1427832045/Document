package com.seer.srd.lps

import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOptions
import com.seer.srd.BusinessError
import com.seer.srd.TaskAbortedError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.lps.ur.UrModbusService
import com.seer.srd.lps.ur.UrReset
import com.seer.srd.robottask.*
import com.seer.srd.robottask.RobotTaskService.saveNewRobotTask
import com.seer.srd.robottask.RobotTaskService.throwIfTaskAborted
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.time.Instant
import java.util.stream.Collectors
import kotlin.collections.toList

private val logger = LoggerFactory.getLogger("com.seer.srd.lps.Services")

object Services {

//    fun updateOpenedError(taskId: String) {
//        val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq taskId)
//        if (task != null) {
//            task.persistedVariables["hasOpenedError"] = false
//        }
//    }

//    fun setMatIdToProductType(mappings: List<MatIdToProductType>) {
//        for (mapping in mappings) {
//            MongoDBManager.collection<MatIdToProductType>().updateOne(MatIdToProductType::magId eq mapping.magId,
//                set(MatIdToProductType::productType setTo mapping.productType), UpdateOptions().upsert(true))
//        }
//    }
    
//    fun getProductTypeOfMatId(magId: String): String? {
//        val r = MongoDBManager.collection<MatIdToProductType>().findOne(MatIdToProductType::magId eq magId)
//        return r?.productType
//    }
    
    fun updateProductLineProductType(line: String, productType: String) {
        MongoDBManager.collection<ProductLineProductType>().updateOne(ProductLineProductType::line eq line,
            set(ProductLineProductType::productType setTo productType),
            UpdateOptions().upsert(true))
    }
    
    fun getProductTypeOfLine(line: String): String? {
        val r = MongoDBManager.collection<ProductLineProductType>().findOne(ProductLineProductType::line eq line)
        return r?.productType
    }

//    fun listMagInfo(): List<MatIdToProductType> {
//        return MongoDBManager.collection<MatIdToProductType>().find().toList()
//    }

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
//            productLine.warningAlert["onUpCarEmpty"] = false
//            productLine.warningAlert["onLineUpNoMat"] = false
        }
    }
    
//    fun setOcrError(carNum: String, column: String, errorSites: List<String>) {
//        MongoDBManager.collection<UpCarOcrResult>().updateOne(
//            and(UpCarOcrResult::carNum eq carNum, UpCarOcrResult::column eq column),
//            set(UpCarOcrResult::error setTo true, UpCarOcrResult::errorSites setTo errorSites),
//            UpdateOptions().upsert(true)
//        )
//        WebSockets.onOcrError(OcrErrorMessage(carNum, column, errorSites))
//        val type1 = getProductTypeOfLine("line11")
//        val type2 = getProductTypeOfLine("line12")
//        val carType = getProductTypeOfCar(carNum, column)?.productType
//        if (type1 == carType) ProductLineService.getLineById("line11").switchLineAlert(true)
//        if (type2 == carType) ProductLineService.getLineById("line12").switchLineAlert(true)
//    }
    
//    fun getOcrError(carNum: String, column: String): UpCarOcrResult? {
//        val error = MongoDBManager.collection<UpCarOcrResult>()
//            .findOne(and(UpCarOcrResult::carNum eq carNum, UpCarOcrResult::column eq column))
//        return if (error?.error == true) error else null
//    }
    
//    fun clearOcrError(carNum: String, column: String) {
//        val errors = MongoDBManager.collection<UpCarOcrResult>().find(
//            and(UpCarOcrResult::carNum eq carNum, UpCarOcrResult::column eq column)
//        ).toList()
//        for (error in errors) {
//            if (!error.error) continue
//            error.errorSites.forEach { StoreSiteService.changeSiteFilled(it, false, "Clear Ocr Error") }
//            MongoDBManager.collection<UpCarOcrResult>().deleteOne(UpCarOcrResult::id eq error.id)
//        }
//        val type1 = getProductTypeOfLine("line11")
//        val type2 = getProductTypeOfLine("line12")
//        val carType = getProductTypeOfCar(carNum, column)?.productType
//        if (type1 == carType) {
//            ProductLineService.getLineById("line11").warningAlert["onOcrError"] = false
//            ProductLineService.getLineById("line11").warningAlert["onUpCarEmpty"] = false
//            ProductLineService.getLineById("line11").warningAlert["onLineUpNoMat"] = false
//
//        }
//        if (type2 == carType) {
//            ProductLineService.getLineById("line12").warningAlert["onOcrError"] = false
//            ProductLineService.getLineById("line12").warningAlert["onUpCarEmpty"] = false
//            ProductLineService.getLineById("line12").warningAlert["onLineUpNoMat"] = false
//        }
//    }

//    fun saveOcrDataAndSetError(carNum: String, curColumn: String) {
//        val errorSitesA = mutableListOf<String>()
//        val errorSitesB = mutableListOf<String>()
//        val carType = getProductTypeOfCar(carNum, curColumn)?.productType
//        UrTcpServer.siteToMagMap.forEach {
//            val siteNum = it.key
//            val magId = it.value
//            if (magId != "404") { // 404 代表此位置空
//                val column = if (siteNum.length > 1) "B" else "A"
//                val id = UrModbusService.modbusAddressToSite(siteNum.toInt())
//                val siteId = "CART-UP-$carNum-$column-$id"
//                // 把OCR识别结果，magId信息插入content
//                StoreSiteService.changeSiteFilled(siteId, true, "OCR识别保存")
//                StoreSiteService.setSiteContent(siteId, magId, "OCR识别保存")
////                collection<StoreSite>().updateOne(
////                    StoreSite::id eq siteId, set(StoreSite::content setTo magId, StoreSite::filled setTo true))
////                val info = collection<MatIdToProductType>().findOne(MatIdToProductType::matId eq magId)
//                val magProductType = getProductTypeOfMatId(magId)
//                if (column == "A" && magProductType != carType) errorSitesA.add(siteId)
//                if (column == "B" && magProductType != carType) errorSitesB.add(siteId)
//            }
//        }
//        if (errorSitesA.isNotEmpty()) setOcrError(carNum, "A", errorSitesA)
//        if (errorSitesB.isNotEmpty()) setOcrError(carNum, "B", errorSitesB)
//
//
//        val carProductType = getProductTypeOfCar(carNum, curColumn)
//        val productLine11 = ProductLineService.getLineById("line11")
//        val productLine12 = ProductLineService.getLineById("line12")
//        val type1 = getProductTypeOfLine("line11")
//        val type2 = getProductTypeOfLine("line12")
//        if (carProductType?.productType == type1) {
//            productLine11.warningAlert["onLineUpNoMat"] = false
//            productLine11.warningAlert["onUpCarEmpty"] = false
//        }
//        if (carProductType?.productType == type2) {
//            productLine12.warningAlert["onLineUpNoMat"] = false
//            productLine12.warningAlert["onUpCarEmpty"] = false
//        }
//        UrTcpServer.siteToMagMap.clear()
//    }

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
        var reset = UrModbusService.helper.read03HoldingRegisters(159, 1, 1, "reset信号")?.getShort(0)?.toInt()
        while (reset != 0) {
            reset = UrModbusService.helper.read03HoldingRegisters(159, 1, 1, "reset信号")?.getShort(0)?.toInt()
        }
        UrModbusService.helper.write06SingleRegister(167, value, 1, "路线")
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
        var reset = UrModbusService.helper.read03HoldingRegisters(159, 1, 1, "reset信号")?.getShort(0)?.toInt()
        while (reset != 0) {
            reset = UrModbusService.helper.read03HoldingRegisters(159, 1, 1, "reset信号")?.getShort(0)?.toInt()
        }
        UrModbusService.helper.write06SingleRegister(167, value, 1, "路线")
        UrModbusService.helper.write06SingleRegister(168, columnNum, 1, "位置")

    }

    fun takeFromCar2(sites: List<String>, carNum: String, task: RobotTask) {
        var i = 1
        logger.debug("take from car up carNum: $carNum")
        val agvSiteIds = getAgvSiteIds()
        sites.forEach {
            if (i != 1) {
                while (true) {
                    throwIfTaskAborted(task.id)
                    val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                    if (timeout == 1) {
//                        UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
                        val takeComplete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                        if (takeComplete == 1) {
                            val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                            if (checkOK is Int && checkOK != 0) {
                                UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                                logger.debug("takeComplete=1, checkOK置为0")
                            }
//                            UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
//                            logger.debug("takeComplete=1, checkOK置为0")
                        }
                    }
                    if (timeout == 0) {
                        val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
                        val complete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                        if (checkOK == 0 && complete == 0) {
                            logger.debug("checkOK=0, takeComplete=0, break")
                            break
                        }
                    }
                }
                val clipNo = UrModbusService.helper.read03HoldingRegisters(164, 1, 1, "弹匣信号")?.getShort(0)?.toInt()
                val bufferNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
                // 更新料车库位
                updateSite(carNum, "UP", clipNo as Int)
                // 更新AGV库位
                StoreSiteService.changeSiteFilled("AGV-01-$bufferNo", true, "取料完成")
                logger.debug("AGV-01-$bufferNo 取料完成")
            }

            val buffer = MongoDBManager.collection<StoreSite>()
                .findOne(StoreSite::filled eq false, StoreSite::id `in` agvSiteIds)
                ?: throw BusinessError("AGV没有缓存位置了")
            val clipNum = UrModbusService.siteToModbusAddress(it)
            logger.debug("当前要取的位置是：$clipNum")

            UrModbusService.helper.write06SingleRegister(164, clipNum, 1, "弹匣信号")
            logger.debug("放到AGV的：${buffer.id[buffer.id.length - 1]}")
            UrModbusService.helper.write06SingleRegister(165, Character.getNumericValue(buffer.id[buffer.id.length - 1]), 1, "缓存信号")
            UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
            i++
        }
        while (true) {
            throwIfTaskAborted(task.id)
            val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
            if (timeout == 1) {
//                UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
                val takeComplete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                if (takeComplete == 1) {
                    val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                    if (checkOK is Int && checkOK != 0) {
                        UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                        logger.debug("takeComplete=1, checkOK置为0")
                    }
//                    UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
//                    logger.debug("takeComplete=1, checkOK置为0")
                }
            }
            if (timeout == 0) {
                val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
                val complete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                if (checkOK == 0 && complete == 0) {
                    logger.debug("checkOK=0, takeComplete=0, break")
                    break
                }
            }
        }

        logger.debug("从上料车取完本次需要的所有料${sites.size}个")
        val endClipNo = UrModbusService.helper.read03HoldingRegisters(164, 1, 1, "弹匣信号")?.getShort(0)?.toInt()
        val endAGVBufferNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGV缓存信号")?.getShort(0)?.toInt()
        updateSite(carNum, "UP", endClipNo as Int)
        StoreSiteService.changeSiteFilled("AGV-01-$endAGVBufferNo", true, "更新AGV缓存")
        logger.debug("AGV-01-$endAGVBufferNo 置满")

        UrModbusService.helper.write06SingleRegister(164, 0, 1, "弹匣信号清空")
        UrModbusService.helper.write06SingleRegister(165, 0, 1, "缓存信号清空")

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
//                throw BusinessError("到达信号写入失败,${e.message}")
                Thread.sleep(2000)
            }
        }

        val agvSites = MongoDBManager.collection<StoreSite>()
            .find(StoreSite::filled eq true).filter {
                it.id.contains("AGV")
            }.sortedByDescending { it.id }
        if (agvSites.isEmpty()) {
            logger.error("AGV没有料!!")
        } else {
            var i = 1
            val productLine = ProductLineService.getLineById(lineId)
            agvSites.forEach {
                if (i != 1) {
                    while (true) {
                        throwIfTaskAborted(task.id)
                        val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                        if (timeout == 1) {
                            val takeComplete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                            if (takeComplete == 1) {
                                val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                                if (checkOK is Int && checkOK != 0) {
                                    UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                                    logger.debug("putComplete=1, checkOK置为0")
                                }
//                                UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
//                                logger.debug("putComplete=1, checkOK置为0")
                            }
                        }
                        if (timeout == 0) {
                            val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
                            val complete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                            if (checkOK == 0 && complete == 0) {
                                logger.debug("checkOK=0, complete=0, break")
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
                    val lastAGVBuffNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
                    StoreSiteService.changeSiteFilled("AGV-01-$lastAGVBuffNo", false, "从AGV位置:${lastAGVBuffNo}上取料")
                    logger.debug("AGV-01-$lastAGVBuffNo 置空")
                    productLine.unLockUpOrDown(true, task)
                }
                Thread.sleep(1000)
                productLine.lockUpOrDown(true, task)      // 与buffer通讯正常，buffer无报警，1号位置无货物，并占用机台
                val bufferNum = Character.getNumericValue(it.id[it.id.length - 1])
                UrModbusService.helper.write06SingleRegister(165, bufferNum, 1, "把AGV位置：$bufferNum 放到机台上")
                UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
                i++
            }
            i = 1
            while (true) {
                throwIfTaskAborted(task.id)
                val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                if (timeout == 1) {
//                    UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
                    val takeComplete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                    if (takeComplete == 1) {
                        val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                        if (checkOK is Int && checkOK != 0) {
                            UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                            logger.debug("putComplete=1, checkOK置为0")
                        }
//                        UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
//                        logger.debug("putComplete=1, checkOK置为0")
                    }
                }
                if (timeout == 0) {
                    val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
                    val complete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                    if (checkOK == 0 && complete == 0) {
                        logger.debug("checkOK=0, complete=0, break")
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

            val endAGVBufferNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
            StoreSiteService.changeSiteFilled("AGV-01-$endAGVBufferNo", false, "AGV最后一个料放完")
            logger.debug("AGV-01-$endAGVBufferNo 最后一个料放完")

            productLine.unLockUpOrDown(true, task)

            UrModbusService.helper.write06SingleRegister(164, 0, 1, "弹匣信号清空")
            UrModbusService.helper.write06SingleRegister(165, 0, 1, "缓存信号清空")

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
        val agvSites = MongoDBManager.collection<StoreSite>()
            .findOne(StoreSite::type eq "AGV" ,StoreSite::filled eq true)

        try {
            val line = ProductLineService.getLineById(lineId)
            line.downModbusHelper.write06SingleRegister(CUSTOM_CONFIG.bufferAddress.standByAddr, 1, 1, "到达${lineId}下料口")
        }catch (e: Exception) {
            logger.error("line error", e)
            throw BusinessError("到达信号写入失败,${e.message}")
        }


        var filledSites = 0
        var lastIndex = 0   // 记录上次第几个没拿到机台上的物料

        if (agvSites != null) {
            logger.error("agv缓存没清空!!")
        } else {
            var i = 1

            val productLine = ProductLineService.getLineById(lineId)
//            logger.debug("共$num 个物料")

//            for (j in 1..num) {
            while (true) {
                if (i > 6) break
                throwIfTaskAborted(task.id)
                if (i != 1) {
                    while (true) {
                        throwIfTaskAborted(task.id)
                        if (lastIndex == i) break

                        val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                        if (timeout == 1) {
//                            UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
                            val takeComplete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                            if (takeComplete == 1) {
                                val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                                if (checkOK is Int && checkOK != 0) {
                                    UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                                    logger.debug("takeComplete=1, checkOK置为0")
                                }
//                                UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
//                                logger.debug("takeComplete=1, checkOK置为0")
                            }
                        }

                        if (timeout == 0) {
                            val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
                            val complete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                            if (checkOK == 0 && complete == 0) {
                                logger.debug("checkOK=0, complete=0, break")
                                filledSites++
                                break
                            }
                        }

                    }

//                    productLine.unLockUpOrDown(false)
                    if (lastIndex != i) {
                        logger.debug("取料完成")
                        MongoDBManager.collection<MatRecord>().insertOne(MatRecord(
                            lineId = lineId,
                            type = getProductTypeOfLine(lineId) ?: "",
                            up = false,
                            recordOn = Instant.now()
                        ))
                        val bufferOldNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
                        StoreSiteService.changeSiteFilled("AGV-01-$bufferOldNo", true, "往AGV车上放料")
                        logger.debug("向 AGV-01-$bufferOldNo 放料")
                    }
                    productLine.unLockUpOrDown(false, task)
                    Thread.sleep(1000)
                }
                logger.debug("准备取走机台物料: $i")
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
                            logger.debug("AGV上的货物小于等于3，继续等待...")
                            lastIndex = i
                            continue
                        }
                    }
                    UrModbusService.helper.write06SingleRegister(165, bufferNo, 1, "从机台放到AGV位置：$bufferNo 上")
                    UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
                    logger.debug("放到AGV的位置：$bufferNo, checkOK=1")
                    i++
                } else {
                    logger.error("AGV没有可用的位置")
                }
            }
            while (true) {

                if (lastIndex == i) break
                throwIfTaskAborted(task.id)
                val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                if (timeout == 1) {
                    val takeComplete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                    if (takeComplete == 1) {
                        val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                        if (checkOK is Int && checkOK != 0) {
                            UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                            logger.debug("takeComplete=1, checkOK置为0")
                        }
//                        UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
//                        logger.debug("takeComplete=1, checkOK置为0")
                    }
                }
                if (timeout == 0) {
                    val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
                    val complete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                    if (checkOK == 0 && complete == 0) {
                        logger.debug("checkOK=0, complete=0, break")
                        filledSites++
                        break
                    }
                }
            }
//            // 释放资源
//            productLine.unLockUpOrDown(false)

            if (lastIndex != i) {
                logger.debug("取料完成")
                MongoDBManager.collection<MatRecord>().insertOne(MatRecord(
                    lineId = lineId,
                    type = getProductTypeOfLine(lineId) ?: "",
                    up = false,
                    recordOn = Instant.now()
                ))
                val endBufferNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
                StoreSiteService.changeSiteFilled("AGV-01-$endBufferNo", true, "产线: $lineId 下料口最后一个取完")
                logger.debug("AGV-01-$endBufferNo 置满")
            }

            logger.debug("下料口${filledSites}个料取完")

            UrModbusService.helper.write06SingleRegister(164, 0, 1, "弹匣信号清空")
            UrModbusService.helper.write06SingleRegister(165, 0, 1, "缓存信号清空")

            // 释放资源
            productLine.unLockUpOrDown(false, task)

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
//            logger.debug("共$num 个物料")

//            for (j in 1..num) {
            while (true) {
                if (i > 6) break
                throwIfTaskAborted(task.id)
                if (i != 1) {
                    while (true) {

                        if (lastIndex == i) break
                        throwIfTaskAborted(task.id)
                        val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                        if (timeout == 1) {
//                            UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
                            val takeComplete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                            if (takeComplete == 1) {
                                UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                                logger.debug("takeComplete=1, checkOK置为0")
                                filledSites++
                            }
                        }

                        if (timeout == 0) {
                            val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
                            val complete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                            if (checkOK == 0 && complete == 0) {
                                logger.debug("checkOK=0, complete=0, break")
                                break
                            }
                        }

                    }

                    productLine.unLockUpOrDown(false, task)
                    if (lastIndex != i) {
                        logger.debug("取料完成")
                        val bufferOldNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
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
                            continue
                        }
                    }
                    UrModbusService.helper.write06SingleRegister(165, bufferNo, 1, "从机台放到AGV位置：$bufferNo 上")
                    UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
                    logger.debug("放到AGV的位置：$bufferNo, checkOK=1")
                    i++
                } else {
                    logger.error("AGV没有可用的位置")
                }
            }
            while (true) {

                if (lastIndex == i) break
                throwIfTaskAborted(task.id)
                val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                if (timeout == 1) {
                    val takeComplete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                    if (takeComplete == 1) {
                        UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                        logger.debug("takeComplete=1, checkOK置为0")
                        filledSites++
                    }
                }
                if (timeout == 0) {
                    val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
                    val complete = UrModbusService.helper.read03HoldingRegisters(160, 1, 1, "取料信号")?.getShort(0)?.toInt()
                    if (checkOK == 0 && complete == 0) {
                        logger.debug("checkOK=0, complete=0, break")
                        break
                    }
                }
            }
            // 释放资源
            productLine.unLockUpOrDown(false, task)

            if (lastIndex != i) {
                logger.debug("取料完成")
                val endBufferNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
                StoreSiteService.changeSiteFilled("AGV-01-$endBufferNo", true, "产线: $lineId 下料口最后一个取完")
                logger.debug("AGV-01-$endBufferNo 置满")
            }

            logger.debug("下料口${filledSites}个料取完")

        }
    }

    fun putOnCar2(lineId: String, carNum: String, column: String, neededSites: List<String>, task: RobotTask) {
        val productLine = ProductLineService.getLineById(lineId)
        val agvSites = MongoDBManager.collection<StoreSite>()
            .find(StoreSite::filled eq true).filter {
                it.id.contains("AGV")
            }.sortedByDescending { it.id }
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
                            val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                            if (timeout == 1) {
//                                UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
                                val takeComplete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                                if (takeComplete == 1) {
                                    val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                                    if (checkOK is Int && checkOK != 0) {
                                        UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                                        logger.debug("放料完成, checkOK置为0")
                                    }
                                }
                            }
                            if (timeout == 0) {
                                val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
                                val complete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                                if (checkOK == 0 && complete == 0) {
                                    logger.debug("checkOK=0, complete=0, break")
                                    break
                                }
                            }
                        }
                        val agvBufferNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
//                if (it.filled) StoreSiteService.changeSiteFilled(it.id, false, "AGVBuffer $agvBufferNo 置空")
                        StoreSiteService.changeSiteFilled("AGV-01-$agvBufferNo", false, "AGVBuffer $agvBufferNo 置空")
//                else logger.error("agv 缓存位置$agvBufferNo 空!!")
                        val clipNo = UrModbusService.helper.read03HoldingRegisters(164, 1, 1, "空料车弹匣信号")?.getShort(0)?.toInt()
                        val id = UrModbusService.modbusAddressToSite(clipNo as Int)
                        StoreSiteService.changeSiteFilled("CART-DOWN-$carNum-$column-$id", true, "空料车$carNum $column $id 放料完成")
                        logger.debug("空料车$carNum $column 列 $id 号放料完成")
                    }
                    val site = neededSites[i - 1]
                    val bufferNo = Character.getNumericValue(it.id[it.id.length - 1])
                    logger.debug("从$bufferNo 取")
                    val clipNo = UrModbusService.siteToModbusAddress(site)
                    UrModbusService.helper.write06SingleRegister(164, clipNo, 1 , "空料车弹匣信号")
                    UrModbusService.helper.write06SingleRegister(165, bufferNo, 1 , "AGVBuffer信号")
                    UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
                    logger.debug("往空料车$carNum 的$column $clipNo 放置, checkOK=1")
                    i++
                }
                while (true) {
                    throwIfTaskAborted(task.id)
                    val timeout = UrModbusService.helper.read03HoldingRegisters(171, 1, 1, "timeout信号")?.getShort(0)?.toInt()
                    if (timeout == 1) {
//                        UrModbusService.helper.write06SingleRegister(172, 1, 1, "checkOK信号")
                        val takeComplete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                        if (takeComplete == 1) {
                            val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "read CheckOK")?.getShort(0)?.toInt()
                            if (checkOK is Int && checkOK != 0) {
                                UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
                                logger.debug("放料完成, checkOK置为0")
                            }
//                            UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK信号")
//                            logger.debug("放料完成, checkOK置为0")
                        }
                    }
                    if (timeout == 0) {
                        val checkOK = UrModbusService.helper.read03HoldingRegisters(172, 1, 1, "checkOK")?.getShort(0)?.toInt()
                        val complete = UrModbusService.helper.read03HoldingRegisters(161, 1, 1, "放料信号")?.getShort(0)?.toInt()
                        if (checkOK == 0 && complete == 0) {
                            logger.debug("checkOK=0, complete=0, break")
                            break
                        }
                    }
                }
                logger.debug("放料完成")
                val site = neededSites[i - 2]
                val endClipNo = UrModbusService.helper.read03HoldingRegisters(164, 1, 1, "空料车弹匣信号")?.getShort(0)?.toInt()
                val id = UrModbusService.modbusAddressToSite(endClipNo as Int)
                StoreSiteService.changeSiteFilled(site, true, "空料车$carNum $column $id 放料完成")
                logger.debug("空料车$carNum $column $id 放料完成")

                val endBufferNo = UrModbusService.helper.read03HoldingRegisters(165, 1, 1, "AGVBuffer信号")?.getShort(0)?.toInt()
                StoreSiteService.changeSiteFilled("AGV-01-$endBufferNo", false, "AGVBuffer $endBufferNo 置空")
                logger.debug("AGVBuffer $endBufferNo 置空")

                UrModbusService.helper.write06SingleRegister(164, 0, 1, "弹匣信号清空")
                UrModbusService.helper.write06SingleRegister(165, 0, 1, "缓存信号清空")

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
            var matNum = productLine.upModbusHelper.read03HoldingRegisters(bufferAddress.matNumAddr, 1, 1, "${lineId}上料buffer物料数量")?.getShort(0)?.toInt()
            var errorCode = productLine.upModbusHelper.read03HoldingRegisters(bufferAddress.errorCodeAddr, 1, 1, "${lineId}buffer错误码")?.getShort(0)?.toInt()
            if (matNum == 0 && errorCode == 0) ctx.task.persistedVariables["canUp"] = true
            else {
                logger.debug("等待机台可用")
                while (true) {
                    throwIfTaskAborted(ctx.task.id)
                    Thread.sleep(2000)
                    matNum = productLine.upModbusHelper.read03HoldingRegisters(bufferAddress.matNumAddr, 1, 1, "${lineId}上料buffer物料数量")?.getShort(0)?.toInt()
                    errorCode = productLine.upModbusHelper.read03HoldingRegisters(bufferAddress.errorCodeAddr, 1, 1, "${lineId}buffer错误码")?.getShort(0)?.toInt()
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
        val matNum = productLine.downModbusHelper.read03HoldingRegisters(bufferAddress.matNumAddr, 1, 1, "${lineId}下料buffer物料数量")?.getShort(0)?.toInt()
        val one = productLine.downModbusHelper.read03HoldingRegisters(bufferAddress.bufferOneAddr, 1, 1, "${lineId}buffer的取料位")?.getShort(0)?.toInt()
        val errorCode = productLine.downModbusHelper.read03HoldingRegisters(bufferAddress.errorCodeAddr, 1, 1, "${lineId}buffer错误码")?.getShort(0)?.toInt()
        if (matNum is Int && one is Int && errorCode is Int && one == 1 && matNum > 3) {
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
//                productLine.warningAlert["BufferError"] = false
            }
        }
    }

    fun checkDownCar(ctx: ProcessingContext) {
        val column = ctx.task.persistedVariables["column"] as String
        val downSize = StoreSiteService.listStoreSites().filter { it.type == "AGV" && it.filled }.size
        for (i in 4..5) {
            val sites = MongoDBManager.collection<StoreSite>().find(
                StoreSite::type eq "DOWN-SITE-$i").sort(Sorts.descending("_id")).filter {
                it.id.contains("-$column-") && !it.filled
            }
            if (sites.size >= downSize) {
                ctx.task.persistedVariables["carNum"] = i.toString()
                ctx.task.persistedVariables["neededSites"] = sites.stream().limit(downSize.toLong()).collect(Collectors.toList()).map { it.id }
                var ids = ""
                (ctx.task.persistedVariables["neededSites"] as List<String>).forEach{
                    ids += it
                }
                logger.debug("找到可以放的位置：$ids")
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
        UrModbusService.helper.write06SingleRegister(169, 1, 1, "识别")
        logger.debug("开始识别...")
        var flag = UrModbusService.helper.read03HoldingRegisters(162, 1, 1, "已获取一列数据信号")?.getShort(0)?.toInt()
        while (flag != 1)  {
            flag = UrModbusService.helper.read03HoldingRegisters(162, 1, 1, "已获取一列数据信号")?.getShort(0)?.toInt()
        }
        logger.debug("OCR识别结束")
    }

    fun resetAll() {
        logger.debug("驱使UR复位")
        UrModbusService.helper.write06SingleRegister(163, 1, 1, "驱使复位信号")
        var flag = UrModbusService.helper.read03HoldingRegisters(159, 1, 1, "ur复位信号")?.getShort(0)?.toInt()

        var i = 1
        while(flag != 1) {
            i++
            if (i%3000 == 0) logger.debug("等待UR复位...")
            flag = UrModbusService.helper.read03HoldingRegisters(159, 1, 1, "ur复位信号")?.getShort(0)?.toInt()
        }
        logger.debug("UR复位成功")

        UrReset.flag = true

        UrModbusService.helper.write06SingleRegister(164, 0, 1, "料车弹匣复位信号")
        logger.debug("弹匣信号复位成功")

        UrModbusService.helper.write06SingleRegister(165, 0, 1, "AGV缓存复位信号")
        logger.debug("AGV缓存信号成功")

        UrModbusService.helper.write06SingleRegister(170, 0, 1, "机台弹匣复位信号")
        logger.debug("机台弹匣信号复位成功")

        UrModbusService.helper.write06SingleRegister(167, 0, 1, "动作复位信号")
        logger.debug("动作信号复位成功")

        UrModbusService.helper.write06SingleRegister(168, 0, 1, "料车位置复位信号")
        logger.debug("料车位置信号复位成功")

        UrModbusService.helper.write06SingleRegister(169, 0, 1, "OCR识别复位信号")
        logger.debug("OCR识别信号复位成功")

        UrModbusService.helper.write06SingleRegister(172, 0, 1, "checkOK复位信号")
        logger.debug("checkOK复位成功")

        UrModbusService.helper.write06SingleRegister(163, 0, 1, "驱使复位信号")
        logger.debug("驱使信号复位成功")
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

    fun checkUpCarEmptyOrNoNeededMag(lineId: String) {

        val productLine = ProductLineService.getLineById(lineId)
        val productType = Services.getProductTypeOfLine(lineId) ?: ""
        // 产线没设置生产类型，不需要上料

        for (carNo in 1..2) {
            for (carColumn in "AB") {
                val carNum = carNo.toString()
                val column = carColumn.toString()

//                val ocrError = Services.getOcrError(carNum, column)
//                if (ocrError != null && ocrError.error && ocrError.errorSites.isNotEmpty())  {
//                    if (productLine.warningAlert["onOcrError"] == true) {
//                        logger.debug("ocr物料异常未消除，上料车$carNum ${column}列")
//                        continue
//                    }
//                    productLine.warningAlert["onOcrError"] = true
//                    WebSockets.onOcrError(OcrErrorMessage(carNum, column, ocrError.errorSites))
//                    productLine.switchLineAlert(true)
//                    logger.debug("OCR识别异常告警,上料车：$carNum ${column}列")
//                    continue
//                }

                // 先把这列库位拿出来
                val sites = MongoDBManager.collection<StoreSite>().find(
                    StoreSite::label eq "CART-UP-$carNum-$column").toList()

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

    fun checkDownCarFull(lineId: String) {
        val productLine = ProductLineService.getLineById(lineId)
        val column = if (lineId == "line11") "A" else "B"

        for (carNum in 4..5) {

            // 先把这列库位拿出来
            val sites = MongoDBManager.collection<StoreSite>().find(
                StoreSite::label eq "CART-DOWN-$carNum-$column").toList()

            // 没有设置这辆车
            if (sites.isNullOrEmpty()) continue

            // 这个列可用的空位置数量
            val availableSites = sites.filter { !it.filled }

            if (availableSites.size < CUSTOM_CONFIG.leastDownSize) {
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
                logger.debug("下料车${carNum}满 告警")
            }
        }
    }
    private fun getAgvSiteIds(): List<String> {
        return MongoDBManager.collection<StoreSite>().find(StoreSite::type eq "AGV").toList().map { it.id }
    }

    fun getMsgOfBufferErrorCode(code: Int): String {
        return when (code) {
            1 -> "气缸动作超时!!"
            2 -> "电机报错!!"
            else -> "Buffer异常!!"
        }
    }
}

//data class MatIdToProductType(
//    @BsonId val id: ObjectId = ObjectId(),
//    val magId: String = "",
//    val productType: String = ""
//)

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

//data class UpCarOcrResult(
//    @BsonId val id: ObjectId,
//    val carNum: String,
//    val column: String, // A B
//    val error: Boolean,
//    val errorSites: List<String>
//)
