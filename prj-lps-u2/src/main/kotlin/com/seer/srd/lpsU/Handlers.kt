package com.seer.srd.lpsU

import com.google.gson.Gson
import com.google.gson.internal.LinkedTreeMap
import com.seer.srd.BusinessError
import com.seer.srd.Error400
import com.seer.srd.lpsU.ProductLineService.getLineById
import com.seer.srd.lpsU.Services.fillUpCarProductType
import com.seer.srd.lpsU.Services.getProductTypeOfLine
import io.javalin.http.Context
import org.slf4j.LoggerFactory

object Handlers {

    private val logger = LoggerFactory.getLogger(Handlers::class.java)

    fun listMags11(ctx: Context) {
        ctx.json(mapOf(
            "Line" to "11",
            "Carriage" to "loader",
            "CarriageNo" to "1",
            "MagazineInfo" to "111101001111"
        ))
        ctx.status(200)
    }

    fun listMags11un(ctx: Context) {
        ctx.json(mapOf(
            "Line" to "11",
            "Carriage" to "unloader",
            "CarriageNo" to "4",
            "MagazineInfo" to "000001000000"
        ))
        ctx.status(200)
    }

    fun listMags12(ctx: Context) {
        ctx.json(mapOf(
            "Line" to "12",
            "Carriage" to "loader",
            "CarriageNo" to "2",
            "MagazineInfo" to "111101001111"
        ))
        ctx.status(200)
    }

    fun listMags12un(ctx: Context) {
        ctx.json(mapOf(
            "Line" to "12",
            "Carriage" to "unloader",
            "CarriageNo" to "5",
            "MagazineInfo" to "000000000000"
        ))
        ctx.status(200)
    }

    fun handleAlterProductLineProductType(ctx: Context) {
        ctx.status(400)
        throw Error400("Error", "<strong>不可用</strong>")
//        val req = ctx.bodyAsClass(AlterProductLineProductTypeReq::class.java)
//        Services.updateProductLineProductType(req.line, req.category)
//
//        val productLine = getLineById(req.line)
//        productLine.warningAlert["onLineUpNoMat"] = false
//        productLine.warningAlert["onUpCarEmpty"] = false

//        ctx.status(204)
    }

    fun handleFillUpCar(ctx: Context) {
        ctx.status(400)
        throw Error400("Error", "<strong>不可用</strong>")
//        val req = ctx.bodyAsClass(AlterUpCarProductTypeReq::class.java)
//        val carNum = req.carNum
//        val categoryA = req.categoryA
//        val categoryB = req.categoryB
//        if (!categoryA.isNullOrBlank()) fillUpCarProductType(carNum, "A")
//        if (!categoryB.isNullOrBlank()) fillUpCarProductType(carNum, "B")
//        ctx.json(mapOf("carNum" to req.carNum, "categoryA" to categoryA, "categoryB" to categoryB))
    }

    fun handleAlterUpCarProductType(ctx: Context) {
        ctx.status(400)
        throw Error400("Error", "<strong>不可用</strong>")
//        val req = ctx.bodyAsClass(AlterUpCarProductTypeReq::class.java)
//
//        val categoryA = if (!req.categoryA.isNullOrBlank()) {
//            val clear = Services.isUpCarClear(req.carNum, "A")
//            if (clear) Services.setUpCarProductType(req.carNum, "A", req.categoryA)
//            clear
//        } else true
//
//        val categoryB = if (!req.categoryB.isNullOrBlank()) {
//            val clear = Services.isUpCarClear(req.carNum, "B")
//            if (clear) Services.setUpCarProductType(req.carNum, "B", req.categoryB)
//            clear
//        } else true
//
//        ctx.json(mapOf("carNum" to req.carNum, "categoryA" to categoryA, "categoryB" to categoryB))
    }

    fun handleLight(ctx: Context) {
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
            if (msg["carNum"] == "4") {
                productLine11.switchLineAlert(false)
                logger.debug("off light line11 of car 4")
                return
            } else if (msg["carNum"] == "5") {
                productLine12.switchLineAlert(false)
                logger.debug("off light line12 of car 5")
                return
            }
//            if (msg["carNum"] == "4" || msg["carNum"] == "5") {
//                if (msg["column"] == "A") {
//                    productLine11.switchLineAlert(false)
//                    logger.debug("off light line11 of column A")
//                }
//                else {
//                    productLine12.switchLineAlert(false)
//                    logger.debug("off light line12 of column B")
//                }
//                return
//            }
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
        ctx.status(400)
        throw Error400("Error", "<strong>不可用</strong>")
//        val req = ctx.bodyAsClass(ClearCarReq::class.java)
//        if (req.carNum.contains("DOWN", true)) {
//            Services.clearCar(req.carNum, req.location)
//        } else {
//            if (Services.upTaskOnCar(req.carNum, req.location) && !req.force) {
//                throw Error400("UpTask", "<strong>${req.carNum} ${req.location} </strong>有上料任务，不能清空")
//            }
//            else if (Services.ocrTaskOnCar(req.carNum, req.location) && !req.force) {
//                throw Error400("OcrTask", "<strong>${req.carNum} ${req.location} </strong>有OCR任务，不能清空")
//            } else {
//                if (req.carNum in listOf("1", "2")) {
//                    ctx.status(400)
//                    throw Error400("Error", "<strong>不可用</strong>")
//                }
//                else Services.clearCar(req.carNum, req.location)
//            }
//        }
//        ctx.status(204)
    }
    
    fun handleListProductTypes(ctx: Context) {
        ctx.json(CUSTOM_CONFIG.productTypes)
    }

    fun handleListUpCarProductTypes(ctx: Context) {
        val list = Services.listUpCarProductType()
        ctx.json(list)
    }

    fun handleUpCarProductTypeByCar(ctx: Context) {
        val carNum = ctx.pathParam("carNum")

    }

    fun handleListProductLinesProductTypes(ctx: Context) {
        val list = Services.listProductLinesProductTypes()
        ctx.json(list)
    }
    
    fun handleOcr(ctx: Context) {
        val req = ctx.bodyAsClass(AlterUpCarProductTypeReq::class.java)
        Services.updateCategoryByCarNum(req.carNum, req.categoryA, req.categoryB)
        ctx.status(200)
    }

    fun handleBufferErrorCode(ctx: Context) {
        val msg = Gson().fromJson(ctx.body(), LightMsg::class.java).msg as LinkedTreeMap<String, String>
        val productLine11 = getLineById("line11")
        val productLine12 = getLineById("line12")
        if (msg["line"] != null && msg["type"] != null) {
            if (msg["line"] == "line11") {

                when {
                    msg["type"] == "up" -> productLine11.resetErrorCode(true)
                    msg["type"] == "down" -> productLine11.resetErrorCode(false)
                    else -> throw BusinessError("line11 wrong type: ${msg["type"]}")
                }

                productLine11.switchLineAlert(false)
                productLine11.warningAlert["BufferError"] = false
                logger.debug("off light line11")

            }
            if (msg["line"] == "line12") {

                when {
                  msg["type"] == "up" -> productLine12.resetErrorCode(true)
                  msg["type"] == "down" -> productLine12.resetErrorCode(false)
                  else -> throw BusinessError("line12 wrong type: ${msg["type"]}")
                }

                productLine12.switchLineAlert(false)
                productLine12.warningAlert["BufferError"] = false
                logger.debug("off light line12")
            }
        }
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

data class LightMsg(
    val event: String?,
    val msg: Any?
)
