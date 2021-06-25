package com.seer.srd.chinapost

import com.seer.srd.Application
import com.seer.srd.BusinessError
import com.seer.srd.chinapost.plc.CommandParams
import com.seer.srd.chinapost.plc.PlcService
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer.handle
import com.seer.srd.http.ReqMeta
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.robottask.RobotTaskService
import com.seer.srd.robottask.buildTaskInstanceByDef
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.robottask.getRobotTaskDef
import com.seer.srd.setVersion
import com.seer.srd.storesite.StoreSiteService
import io.javalin.http.Context
import io.javalin.http.HandlerType
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main() {
    setVersion("l2", "3.0.14.2")

    Application.initialize()

    ChinaPostApp.init()

    Application.start()
}

object ChinaPostApp {


    private val logger = LoggerFactory.getLogger(ChinaPostApp::class.java)

    private val taskCheckTimer = Executors.newScheduledThreadPool(1)

    private val siteTimer = Executors.newScheduledThreadPool(1)

    val modBusHelpers: HashMap<String, ModbusTcpMasterHelper> = HashMap()

    private var checking = false

    private var updating = false

    @Volatile
    private var looping = true

    fun init() {

        if (CUSTOM_CONFIG.plcEnabled) PlcService.init()

        CUSTOM_CONFIG.area.forEach {
            val areaName = it.key
            val modBusSite = it.value
            modBusHelpers[areaName] = ModbusTcpMasterHelper(modBusSite.host, modBusSite.port)
            modBusHelpers[areaName]?.connect()
        }

        registerRobotTaskComponents(ExtraComponent.extraComponents)

        handle(
            HttpRequestMapping(HandlerType.GET, "ext/siteList", ::listSites, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.GET, "ext/WMSTaskList", ::listWMSTask, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.GET, "ext/WMSTaskById/:id", ::getWMSTaskById, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.POST, "ext/occupy", ::occupy, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.POST, "ext/release", ::release, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.POST, "ext/loop/:value", ::loop, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.GET, "ext/plc/request-pass", ::requestPass, ReqMeta(auth = false, test = true)),
            HttpRequestMapping(HandlerType.POST, "ext/plc/on-position", ::onPosition, ReqMeta(auth = false, test = true))
        )

        siteTimer.scheduleAtFixedRate(this::updateSite, 5, 5, TimeUnit.SECONDS)
        taskCheckTimer.scheduleAtFixedRate(this::check, 5, 15, TimeUnit.SECONDS)

    }

    private fun loop(ctx: Context) {
        val v = ctx.pathParam("value")
        looping = v.toBoolean()
        ctx.status(200)
    }

    private fun updateSite() {
        synchronized(siteTimer) {
            if (updating) return
            updating = true
            try {
                val areas = CUSTOM_CONFIG.area
                areas.keys.forEach { Services.updateSiteByArea(it, CUSTOM_CONFIG.enabled) }
            } catch (e: Exception) {
                logger.error("update site error", e)
            } finally {
                updating = false
            }
        }
    }

    private fun check() {
        synchronized(taskCheckTimer) {

            if (!looping) return
            if (checking) return
            checking = true
            try {
                logger.debug("检查是否能生成空托盘地堆区到码托库位的任务...")
                val fromSites = StoreSiteService.listStoreSites().filter { it.type == "空托盘缓存区" && it.filled && !it.locked }
                if (fromSites.isEmpty()) {
                    logger.debug("空托盘地堆区没有可用空托盘，跳过本次任务")
                    return
                }
                val from = fromSites[0]
                val sites1 = Services.updateSiteByArea("matuo", CUSTOM_CONFIG.enabled).filter { !it.filled && !it.locked }
                if (sites1.isEmpty()) {
                    logger.debug("码托库位没有可用空位置，跳过本次任务")
                    return
                }
                logger.debug("自动任务检查1次，码托可用位置：${sites1.map { it.id }}")
                Thread.sleep(CUSTOM_CONFIG.checkInterval)
                val sites2 = Services.updateSiteByArea("matuo", CUSTOM_CONFIG.enabled).filter { !it.filled && !it.locked}
                if (sites2.isEmpty()) {
                    logger.debug("自动任务的码托可用位置2次检查：码托库位没有可用空位置，跳过本次任务")
                    return
                }
                val to = sites1.findLast { site ->
                    sites2.map { it.id }.contains(site.id)
                } ?: return
                logger.debug("自动任务检查2次，码托可用位置：${sites2.map { it.id }}")
                createTask(from.id, to.id)
            } catch (e: Exception) {
                logger.error("check task error", e)
            } finally {

                checking = false
            }
        }
    }

    private fun createTask(from: String, to: String) {
        val def = getRobotTaskDef("emptyTrayToProduct") ?: throw BusinessError("no such def: emptyTrayToProduct")
        val task = buildTaskInstanceByDef(def)
        task.transports[0].stages[2].location = from
        task.transports[1].stages[2].location = to
        task.persistedVariables["from"] = from
        task.persistedVariables["to"] =  to
        RobotTaskService.saveNewRobotTask(task)
    }

    private fun listSites(ctx: Context) {
        ctx.json(Services.getSiteList())
        ctx.status(201)
    }

    private fun listWMSTask(ctx: Context) {
        ctx.json(Services.getWMSTaskList())
        ctx.status(201)
    }

    private fun getWMSTaskById(ctx: Context) {
        val id = ctx.pathParam("id")
        ctx.json(Services.getWMSTaskById(id))
        ctx.status(201)
    }

    private fun occupy(ctx: Context) {
        val req = ctx.bodyAsClass(UpdateSiteReq::class.java)
        val sites = req.params
        Services.occupy(sites)
        ctx.status(201)
    }

    private fun release(ctx: Context) {
        val req = ctx.bodyAsClass(UpdateSiteReq::class.java)
        val sites = req.params
        Services.release(sites)
        ctx.status(201)
    }

    private fun requestPass(ctx: Context) {
        PlcService.getPlcDeviceByName("plc1").sendCommand(CommandParams(1000))
        ctx.status(200)
    }

    private fun onPosition(ctx: Context) {
        PlcService.getPlcDeviceByName("plc1").sendCommand(CommandParams(1001, true))
        ctx.status(200)
    }

}