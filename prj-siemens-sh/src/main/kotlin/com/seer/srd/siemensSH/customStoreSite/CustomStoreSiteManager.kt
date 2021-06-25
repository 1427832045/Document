package com.seer.srd.siemensSH.customStoreSite

import com.seer.srd.BusinessError
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.storesite.StoreSiteService
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread

data class PlcConfig(
    var host: String = "127.0.0.1",
    var port: Int = 502,
    var unitId: Int = 0,
    var baseAddr: Int = 0,
    var qty: Int = 10,
    var effectiveCount: Long = 30,   // 信号防抖，防止误触发；光电状态持续 30s 之后，判定此信号有效
    var siteAddrMapping: Map<Int, SiteDefalutStaus> = mapOf()
)

data class SiteDefalutStaus(
    var siteId: String = "",
    var defaultStatus: DefaultStatus = DefaultStatus.Filled
)

// 跟库位检测的电控柜断开连接之，后期望的库位状态
enum class DefaultStatus {
    Keep,           // 断开连接之后，保持库位的当前状态
    Filled,         // 断开连接之后，将库位设置为占用状态
    Unfilled        // 断开连接之后，将库位设置为未占用状态
}

data class SiteSensorState(
    val siteId: String = "",
    val effectiveCount: Long = 30,
    val address: Int = 0,
    val defaultStatus: DefaultStatus = DefaultStatus.Filled,
    var value: Int = -1,
    var tempValue: Int = -1,
    var reset: Boolean = false,
    var modifiedOn: Instant = Instant.now()
) {
    fun resetToDefault() {
        if (reset) return
        val lastValue = value
        when (defaultStatus) {
            DefaultStatus.Filled -> if (value != 1) value = 1
            DefaultStatus.Unfilled -> if (value != 0) value = 0
            else -> {
                // do nothing
            }
        }
        logger.debug("Reset site=$siteId from $lastValue to $value.")
        tempValue = -1
        modifiedOn = Instant.now()
        reset = true
    }

    private fun replaceValue(modifiedOn: Instant) {
        value = tempValue
        this.modifiedOn = modifiedOn
    }

    fun updateTempValue(newValue: Int) {
        if (newValue == 0 || newValue == 1) {
            if (!reset) reset = false
            val now = Instant.now()
            if (-1 == value) {
                tempValue = newValue
                replaceValue(now)  // 第一次赋值
            } else if (newValue != tempValue) tempValue = newValue

            if (Duration.between(modifiedOn, now).toSeconds() > effectiveCount) replaceValue(now)

        } else logger.error("SiteSensorState[$siteId] invalid newValue=${newValue}!")
    }
}

private val logger = LoggerFactory.getLogger(CustomStoreSiteManager::class.java)

class CustomStoreSiteManager(private val name: String, private val config: PlcConfig) {

    private val helper = ModbusTcpMasterHelper(config.host, config.port)

    private var times: Int = 0

    private val status: MutableMap<Int, Int> = mutableMapOf()

    private val baseAddr = config.baseAddr

    @Volatile
    private var historyStatus = status.toString()

    private val siteSensorStateMap: MutableMap<Int, SiteSensorState> = mutableMapOf()

    init {
        logger.debug("init custom store site manager.")

        val qty = config.qty
        val sam = config.siteAddrMapping
        val sdsList = sam.map { it.value }
        logger.debug("CustomStoreSiteManager[$name] siteAddrs is ${sdsList}.")

        val samSize = sam.size
        if (qty < samSize)
            throw BusinessError("CustomStoreSiteManager[$name] too few qty " +
                "because qty($qty) < siteAddrMapping.size($samSize)!!! ")

        val maxIndex = sam.keys.max()
        if (samSize > 0 && qty < maxIndex!!)
            throw BusinessError("CustomStoreSiteManager[$name] siteAddrMapping out of bound " +
                "because its maxIndex($maxIndex) > qty($qty)!!! ")

        times = qty / 8
        logger.info("CustomStoreSiteManager[$name] times is $times .")

        (0 until qty).forEach {
            val address = it + baseAddr
            status[address] = -1
            val samIndexed = sam[it]
            if (samIndexed != null) siteSensorStateMap[address] =
                SiteSensorState(samIndexed.siteId, config.effectiveCount, address, samIndexed.defaultStatus)
        }
        logger.debug("CustomStoreSiteManager[$name] init status is $status .")

        syncStoreSiteStatus()
    }

    private fun syncStoreSiteStatus() {
        thread(name = "sync-custom-store-site-status") {
            val qty = config.qty
            while (true) {
                // 读取PLC数据失败之后，此线程就不会再继续更新库位状态，否则人无法修改这些库位状态。
                try {
                    val bb =
                        helper.read02DiscreteInputs(baseAddr, qty, config.unitId, "syncCustomStoreSiteStatus")
                    loop1@ for (i in (0 until times)) {
                        var value = bb?.getUnsignedByte(i)?.toInt() ?: continue@loop1

                        loop2@ for (j in (0..7)) {
                            val index = 8 * i + j
                            if (index >= qty) break@loop2

                            val valueIndexed = value % 2
                            val address = index + baseAddr
                            status[address] = valueIndexed
                            siteSensorStateMap[index + baseAddr]?.updateTempValue(valueIndexed)
                            value /= 2
                        }
                    }

                    if (status.toString() != historyStatus) {
                        logger.debug("CustomStoreSiteManager[$name] status changed to ${status.map { it.value }} .")
                        historyStatus = status.toString()
                    }

                    // 更新库位状态
                    updateStoreSiteStatus("sensor status changed.")

                } catch (e: Exception) {
                    logger.error("CustomStoreSiteManager[$name] occurred error: $e")
                    // todo: 捕获异常之后，将放货库位全部被占用
                    updateStoreSiteStatusToDefaultValue()
                } finally {
                    Thread.sleep(1000L)
                }
            }
        }
    }

    private fun updateStoreSiteStatusToDefaultValue() {
        try {
            siteSensorStateMap.map { it.value.resetToDefault() }
            updateStoreSiteStatus("reset to default value.")
        } catch (e: Exception) {
            logger.error("update storeSite to default value failed! $e")
        }
    }

    private fun updateStoreSiteStatus(remark: String) {
        if (StoreSiteService.listStoreSites().isEmpty()) {
            logger.error("CustomStoreSiteManager[$name] no store-sites in system memory!")
            return
        }

        loop3@ for (sss in siteSensorStateMap.values) {
            var siteId = ""
            try {
                if (-1 == sss.value) continue@loop3 // 初始状态值，还未同步PLC数据

                siteId = sss.siteId
                val site = StoreSiteService.getStoreSiteById(siteId)
                if (site == null) {
                    logger.error("no such store-site=${siteId}!")
                    continue@loop3
                }
                // 如果不注释掉，在终点前置点的二次检查，一定程度上是无效的
//            if (site.locked) {
//                logger.error("change store-site=$siteId failed because it`s locked!")
//                continue@loop3
//            }
                val fillIt = 1 == sss.value
                if (fillIt && (!site.filled)) {
                    try {
                        StoreSiteService.changeSiteFilled(siteId, true, remark)
                        logger.debug("set site=$siteId filled success.")
                    } catch (e: Exception) {
                        logger.error("set site=$siteId filled failed: $e")
                    }
                } else if (!fillIt && site.filled) {
                    try {
                        StoreSiteService.changeSiteEmptyAndRetainContent(siteId, remark)
                        logger.debug("set site=$siteId empty success.")
                    } catch (e: Exception) {
                        logger.error("set site=$siteId empty failed: $e")
                    }
                }
            } catch (e: Exception) {
                logger.error("Update site=$siteId failed: $e")
            }
        }
    }
}