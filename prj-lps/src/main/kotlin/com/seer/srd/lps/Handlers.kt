package com.seer.srd.lps

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import com.google.gson.Gson
import com.google.gson.internal.LinkedTreeMap
import com.seer.srd.Error400
import com.seer.srd.lps.ProductLineService.getLineById
import com.seer.srd.lps.Services.createOCRTask
import com.seer.srd.lps.Services.getProductTypeOfLine
import com.seer.srd.util.mapper
import io.javalin.http.Context
import org.slf4j.LoggerFactory

object Handlers {

    private val logger = LoggerFactory.getLogger(Handlers::class.java)


    fun handleUpdateOpenedError(ctx: Context) {
        val taskId = ctx.pathParam("taskId")
        Services.updateOpenedError(taskId)
    }

    fun handleAlterProductLineProductType(ctx: Context) {
        val req = ctx.bodyAsClass(AlterProductLineProductTypeReq::class.java)
        Services.updateProductLineProductType(req.line, req.category)

        val productLine = getLineById(req.line)
        productLine.warningAlert["onLineUpNoMat"] = false
        productLine.warningAlert["onUpCarEmpty"] = false

        ctx.status(204)
    }
    
    fun handleAlterUpCarProductType(ctx: Context) {
        val req = ctx.bodyAsClass(AlterUpCarProductTypeReq::class.java)
        
        val categoryA = if (!req.categoryA.isNullOrBlank()) {
            val clear = Services.isUpCarClear(req.carNum, "A")
            if (clear) Services.setUpCarProductType(req.carNum, "A", req.categoryA)
            clear
        } else true
        
        val categoryB = if (!req.categoryB.isNullOrBlank()) {
            val clear = Services.isUpCarClear(req.carNum, "B")
            if (clear) Services.setUpCarProductType(req.carNum, "B", req.categoryB)
            clear
        } else true

        ctx.json(mapOf("carNum" to req.carNum, "categoryA" to categoryA, "categoryB" to categoryB))
    }

    fun handleOCRTask(ctx: Context) {
        val req = ctx.bodyAsClass(AlterUpCarProductTypeReq::class.java)
        val carNum = req.carNum
        val categoryA = req.categoryA
        val categoryB = req.categoryB
        createOCRTask(carNum, categoryA, categoryB)
    }

    fun handleLight(ctx: Context) {
//        val req = ctx.bodyAsClass(LightMsg::class.java)
        val msg = Gson().fromJson(ctx.body(), LightMsg::class.java).msg as LinkedTreeMap<String, String>
        val productLine11 = getLineById("line11")
        val productLine12 = getLineById("line12")
        val type1 = getProductTypeOfLine("line11")
        val type2 = getProductTypeOfLine("line12")
        if (msg["line"] != null) {
            if (msg["line"] == "line11") {
                productLine11.switchLineAlert(false)
                logger.debug("off light line11")
            }
            if (msg["line"] == "line12") {
                productLine12.switchLineAlert(false)
                logger.debug("off light line12")
            }
        }
        if (msg["carNum"] != null && msg["column"] != null) {
            val productType = Services.getProductTypeOfCar(msg["carNum"] as String, msg["column"] as String)
            if (msg["carNum"] == "4" || msg["carNum"] == "5") {
                if (msg["column"] == "A") {
                    productLine11.switchLineAlert(false)
                    logger.debug("off light line11 of column A")
                }
                else {
                    productLine12.switchLineAlert(false)
                    logger.debug("off light line12 of column B")
                }
                return
            }
            if (productType?.productType == type1)  {
                productLine11.switchLineAlert(false)
                logger.debug("off light line11 of type=$type1")
            }
            if (productType?.productType == type2)  {
                productLine12.switchLineAlert(false)
                logger.debug("off light line12 of type=$type2")
            }
        }
    }

    fun handleClearCar(ctx: Context) {
        val req = ctx.bodyAsClass(ClearCarReq::class.java)
        if (req.carNum.contains("DOWN", true)) {
            Services.clearCar(req.carNum, req.location)
        } else {
            if (Services.upTaskOnCar(req.carNum, req.location) && !req.force) {
                throw Error400("UpTask", "<strong>${req.carNum} ${req.location} </strong>有上料任务，不能清空")
            }
            else if (Services.ocrTaskOnCar(req.carNum, req.location) && !req.force) {
                throw Error400("OcrTask", "<strong>${req.carNum} ${req.location} </strong>有OCR任务，不能清空")
            } else {
                Services.clearCar(req.carNum, req.location)
            }
        }
        ctx.status(204)
    }
    
    fun handleListProductTypes(ctx: Context) {
        ctx.json(CUSTOM_CONFIG.productTypes)
    }

    fun handleListUpCarProductTypes(ctx: Context) {
        val list = Services.listUpCarProductType()
        ctx.json(list)
    }

    fun handleMatIdToProductTypeMappings(ctx: Context) {
        val reqBody = ctx.body()
        val mappings: List<MatIdToProductType> = mapper.readValue(reqBody, jacksonTypeRef())
        Services.setMatIdToProductType(mappings)
    }

    fun handleListMagInfo(ctx: Context) {
        val list = Services.listMagInfo()
        ctx.json(list)
    }
    
    fun handleListProductLinesProductTypes(ctx: Context) {
        val list = Services.listProductLinesProductTypes()
        ctx.json(list)
    }
    
    fun handleClearOCRError(ctx: Context) {
        val req = ctx.bodyAsClass(ClearOCRErrorReq::class.java)
        Services.clearOcrError(req.carNum, req.column)
        ctx.status(204)
    }
    
}

data class AlterProductLineProductTypeReq(
    val line: String,
    val category: String
)

data class AlterUpCarProductTypeReq(
    val carNum: String,
    val categoryA: String?, // 类别1：料车左侧类别
    val categoryB: String? // 类别2：料车右侧类别
)

data class ClearCarReq(
    val carNum: String,
    val location: String,
    val force: Boolean
)

data class ClearOCRErrorReq(
    val carNum: String,
    val column: String
)

data class LightMsg(
    val event: String?,
    val msg: Any?
)
