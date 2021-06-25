package com.seer.srd.dupu

import com.seer.srd.BusinessError
import com.seer.srd.SystemError
import com.seer.srd.robottask.ManualTask
import com.seer.srd.route.kernelExecutor
import com.seer.srd.util.mapper
import io.javalin.http.Context
import org.opentcs.components.kernel.services.RouterService
import org.opentcs.kernel.getInjector
import org.slf4j.LoggerFactory
import java.time.Instant

object DupuService {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 启用/禁用自动理库的文本提示
     */
    fun parseLabel(enabled: Boolean): String {
        return if (enabled) "启用" else "禁用"
    }

    /**
     * 启用自动充电
     *
     * 通过锁前往CP点的路径实现
     */
    fun enableCharge(ctx: Context) {
        logger.info("改变自动充电状态")
        val body = mapper.readTree(ctx.body())
        val params = mapper.readTree(body["params"].toString())
        val pwd = params["pwd"].asText()
        if (pwd.isBlank() || CommonUtils.customConfig.adminPwd != pwd)
            throw BusinessError("操作失败，密码错误！")

        logger.info("密码校验通过")
        DupuApp.autoCharge = !DupuApp.autoCharge

        CommonUtils.customConfig.chargerRoutes.forEach {
            val injector = getInjector() ?: throw SystemError("No Injector")
            val routerService = injector.getInstance(RouterService::class.java)
            kernelExecutor.submit { routerService.updatePathLock(it, !DupuApp.autoCharge) }.get()
        }
    }

    // 判断是否特殊库位
    fun isSpecial(site: String): Boolean {
        if (site.isBlank()) return false
        return CommonUtils.customConfig.specialSites.contains(site)
    }

    // 根据库位派车
    fun dispatchVehicleBySite(workStation: String): String {
        return CommonUtils.customConfig.workStationConfigs[workStation]?.vehicle ?: ""
    }

    // 获取下一工作站
    fun getNextStationBySite(site: String): String {
        return CommonUtils.customConfig.workStationConfigs[site]?.next ?: ""
    }

    // 获取上一工作站
    fun getFrontStationBySite(site: String): String {
        return CommonUtils.customConfig.workStationConfigs[site]?.front ?: ""
    }

    // 获取停靠站点
    fun getParkStationBySite(site: String) : String {
        return CommonUtils.customConfig.workStationConfigs[site]?.park ?: ""
    }

    fun buildTime(dateStr: String?): Instant? {
        try {
            if (dateStr == null) return null
            return Instant.parse("${dateStr}T08:00:00.000Z")
        } catch (e: Exception) {
            throw BusinessError("Error date format $dateStr, supported format like YYYY-MM-DD")
        }
    }

    fun getProductInfoStr(qrCode: String?): String {
        try {
            if (qrCode.isNullOrEmpty()) throw BusinessError("二维码信息错误！")
            // val raw = mapper.readValue(code, MaterialInfo::class.java)
            val raw = mapper.readTree(qrCode)
            logger.info("product info from erp is: $raw")
            return """<div>
            <p>零件编号: ${raw["spareNo"]}</p>
            <p>名称: ${raw["name"]}</p>
            <p>规格: ${raw["spec"]}</p>
            <p>型号: ${raw["type"]}</p>
            <p>供应商: ${raw["vendor"]}</p>
            <p>出厂日期: ${raw["productionDate"]}</p>
            <div> """.trimIndent()
        } catch (e: Exception) {
            logger.error("productInfo error", e)
            throw BusinessError(e.message)
        }
    }
}