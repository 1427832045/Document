package com.seer.srd.handler

import com.seer.srd.BusinessError
import com.seer.srd.Error400
import com.seer.srd.I18N.locale
import com.seer.srd.SystemError
import com.seer.srd.db.*
import com.seer.srd.http.ensureRequestUserRolePermission
import com.seer.srd.http.getReqLang
import com.seer.srd.route.kernelExecutor
import com.seer.srd.route.service.PlantModelService
import io.javalin.http.Context
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.opentcs.components.kernel.services.RouterService
import org.opentcs.data.order.TransportOrder
import org.opentcs.data.order.TransportOrderState
import org.opentcs.kernel.getInjector
import org.slf4j.LoggerFactory
import java.time.Instant

object PlantModelHandler {
    
    private val logger = LoggerFactory.getLogger(PlantModelHandler::class.java)
    
    fun handleGetModel(ctx: Context) {
        ctx.result(PlantModelService.getModelXml())
    }
    
    fun handleChangeModel(ctx: Context) {
        logger.info("change plant model!!!")
        // srd-k has unfiniehed tasks cant allowed upload plant model
        val tasks = MongoDBManager.collection<TransportOrder>().find(TransportOrder::state eq TransportOrderState.BEING_PROCESSED).toList()
        if (tasks.isNotEmpty()) {
            throw BusinessError(locale("UploadFailForTask", getReqLang(ctx)))
        }

        val urp = ensureRequestUserRolePermission(ctx)
        val req = ctx.bodyAsClass(ChangeModelReq::class.java)

        val reqRemark = req.remark
        var metas: MutableMap<String, Map<String, Any?>> = HashMap()
        val vfi = MongoDBManager.collection<VersionedFileIndex>().findOne(VersionedFileIndex::name eq "PlantModel")
        if (vfi != null) metas= vfi.meta
        for ((key, value) in metas) {
//            logger.debug("plant model meta number is : $key, value is : $value ")
            for ((key1, value1) in value) {
                logger.debug("meta $key  contains object : $key1, value is : $value1 ")
                if (value1 == reqRemark) throw BusinessError(locale("UploadFailForName", getReqLang(ctx)))
            }
        }

        sendToRoute(req.dataString, getReqLang(ctx))
        
        val meta = mapOf(
            "remark" to req.remark,
            "createdBy" to urp.user.id,
            "createdByUser" to urp.user.username,
            "createdOn" to Instant.now()
        )
        addVersionedFile("PlantModel", req.dataString, meta)
        
        ctx.status(204)
    }
    
    fun handleActivatePlantModelByNo(ctx: Context) {
        val no = ctx.pathParam("no").toInt()
        if (no < 0) throw Error400("BadNo", "Bad No")
        // srd-k has unfinished tasks cant allowed upload plant model
        val tasks = MongoDBManager.collection<TransportOrder>().find(TransportOrder::state eq TransportOrderState.BEING_PROCESSED).toList()
        if (tasks.isNotEmpty()) {
            throw BusinessError("系统有正在执行的运单，激活失败!!!")
        }

        logger.info("activate plant model!!!")
        
        val xml = loadVersionedFile("PlantModel", no)
        if (xml.isBlank()) throw Error400("NoPlantModel", "No plant model with version $no")
        
        sendToRoute(xml, getReqLang(ctx))
        
        activateVersion("PlantModel", no)
        
        ctx.status(204)
    }

    fun handleRemovePlantModelByNo(ctx: Context) {
        val no = ctx.pathParam("no").toInt()
        if (no < 0) throw Error400("BadNo", "Bad No")

        logger.info("remove plant model!!!")

        val xml = loadVersionedFile("PlantModel", no)
        if (xml.isBlank()) throw Error400("NoPlantModel", "No plant model with version $no")

        remove("PlantModel", no)

        ctx.status(204)
    }
    
    fun handleListPlantModelRecord(ctx: Context) {
        val index = readIndex("PlantModel")
        ctx.json(index)
    }
    
    fun handleGetPlantModelByVersion(ctx: Context) {
        val no = ctx.pathParam("no").toInt()
        if (no < 0) throw Error400("BadNo", "Bad No")
        val model = loadVersionedFile("PlantModel", no)
        ctx.result(model)
    }
    
    fun downloadModelFileByNo(ctx: Context) {
        val no = ctx.queryParam("no")?.toInt() ?: -1
        if (no < 0) throw Error400("BadNo", "Bad No")
        val model = loadVersionedFile("PlantModel", no)
        ctx.result(model)
        ctx.contentType("text/xml")
    }
    
    private fun sendToRoute(xml: String, lang: String) {
        try {
            PlantModelService.updatePlantModel(xml)
        } catch (e: Exception) {
            logger.error("send plant model to route", e)
            throw Error400("RouteError", locale("RouteError", lang) + " " + e.message)
        }
        logger.info("New plant model sent to Route successfully")
    }
    
    
    fun handleLockPath(ctx: Context) {
        val req = ctx.bodyAsClass(LockPath::class.java)
        val name = ctx.pathParam("name")
        
        val injector = getInjector() ?: throw SystemError("No Injector")
        val routerService = injector.getInstance(RouterService::class.java)
        kernelExecutor.submit { routerService.updatePathLock(name, req.locked) }.get()
        
        ctx.status(204)
    }
    
    fun handleListLockedPaths(ctx: Context) {
        val locked = PlantModelService.getPlantModel().paths.values.filter { it.isLocked }.map { it.name }.toList()
        ctx.json(mapOf("locked" to locked))
    }
    
}

class ChangeModelReq(
    var dataString: String = "",
    var remark: String = ""
)

class LockPath(
    var locked: Boolean = false
)
