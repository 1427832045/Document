package com.seer.srd.lps

import com.mongodb.client.model.Sorts
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.lps.Services.checkDownCarFull
import com.seer.srd.lps.Services.checkUpCarEmptyOrNoNeededMag
import com.seer.srd.robottask.*
import com.seer.srd.storesite.StoreSite
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = LoggerFactory.getLogger(ProductLineService::class.java)

object ProductLineService {

    private val lines: Map<String, ProductLine> = mapOf(
        "line11" to ProductLine("line11"),
        "line12" to ProductLine("line12")
    )

    fun init() {
        logger.debug("init ProductLineService")
    }
    
    fun dispose() {
        lines.values.forEach { it.dispose() }
    }
    
    fun getLineById(id: String) = lines[id] ?: throw IllegalArgumentException("No such line $id")
    
}

//确认下modbus通讯。两条产线，每条产线三组IP和端口
//每条产线包括：
//1. 上料口1组6个新信号，3个弹匣放置位信号，2个抢占信号，1个推板动作信号
//2. 下料口1组5个信号，2个弹匣放置信号，2个抢占信号，1个推板动作信号
//3. 声光提醒灯1组1个信号，1个提醒信号
class ProductLine(private val lineId: String) {
    
    // 此产品上料口的 Modbus
    val upModbusHelper: ModbusTcpMasterHelper
    
    // 此产品下料口的 Modbus
    val downModbusHelper: ModbusTcpMasterHelper
    
    // 此产品报警的 Modbus
    val alertModbusHelper: ModbusTcpMasterHelper

    val warningAlert: MutableMap<String, Boolean> = mutableMapOf()


    // 正在检查上料口
    private var upChecking = false
    
    // 正在检查下料口
    private var downChecking = false
    
    // 产线上料口空检测
    private val lineUpEmptyCheckTimer = Executors.newScheduledThreadPool(1)
    
    // 产线下料口空检测
    private val lineDownEmptyCheckTimer = Executors.newScheduledThreadPool(1)
    
    init {
        val modbusLine = CUSTOM_CONFIG.modbusLines[lineId] ?: throw IllegalArgumentException("产线 $lineId 的 Modbus 未配置")
        
        upModbusHelper = ModbusTcpMasterHelper(modbusLine.upHost, modbusLine.upPort)
        upModbusHelper.connect()
        
        downModbusHelper = ModbusTcpMasterHelper(modbusLine.downHost, modbusLine.downPort)
        downModbusHelper.connect()
        
        alertModbusHelper = ModbusTcpMasterHelper(modbusLine.alertHost, modbusLine.alertPort)
        alertModbusHelper.connect()
        
        lineUpEmptyCheckTimer.scheduleAtFixedRate(this::checkLineUpEmpty, 5, 3, TimeUnit.SECONDS)
        lineDownEmptyCheckTimer.scheduleAtFixedRate(this::checkLineDownFill, 5, 3, TimeUnit.SECONDS)

    }
    
    fun dispose() {
        upModbusHelper.disconnect()
        downModbusHelper.disconnect()
        alertModbusHelper.disconnect()
        
        lineUpEmptyCheckTimer.shutdown()
    }
    
    // 抢占上下料口
    fun lockUpOrDown(up: Boolean) {
        val modbusAddress = CUSTOM_CONFIG.modbusAddress
        val a = if (up) modbusAddress.upA else modbusAddress.downA
        val b = if (up) modbusAddress.upB else modbusAddress.downB
        val helper = if (up) upModbusHelper else downModbusHelper
        helper.write05SingleCoil(a, true, 1, "Write A")
        while (true) {
            if (helper.read02DiscreteInputs(b, 1, 1, "Read B")?.getByte(0)?.toInt() == 1) break
        }
    }

    fun unLockUpOrDown(up: Boolean) {
        val modbusAddress = CUSTOM_CONFIG.modbusAddress
        val a = if (up) modbusAddress.upA else modbusAddress.downA
//        val b = if (up) modbusAddress.upB else modbusAddress.downB
        val helper = if (up) upModbusHelper else downModbusHelper
        helper.write05SingleCoil(a, false, 1, "unlock A")
    }
    
    // 翻板，C 写 1 再写 0
    fun flipUpOrDown(up: Boolean) {
        val modbusAddress = CUSTOM_CONFIG.modbusAddress
        val c = if (up) modbusAddress.upC else modbusAddress.downC
        val helper = if (up) upModbusHelper else downModbusHelper
        helper.write05SingleCoil(c, true, 1, "Write C 1")
        Thread.sleep(CUSTOM_CONFIG.flipCInterval)
        helper.write05SingleCoil(c, false, 1, "Write C 0")
    }

    fun switchLineAlert(on: Boolean) {
        val modbusAddress = CUSTOM_CONFIG.modbusAddress
        alertModbusHelper.write05SingleCoil(modbusAddress.alert, on, 1, "Alert $on")
    }
    
    private fun checkLineUpEmpty() {
        synchronized(upModbusHelper) {
            if (upChecking) return
            upChecking = true
            try {
                checkUpCarEmptyOrNoNeededMag(lineId)
                val modbusAddress = CUSTOM_CONFIG.modbusAddress
                var bin1 = upModbusHelper.read02DiscreteInputs(modbusAddress.upBin1, 1, 1, "${lineId}上料光电1")?.getByte(0)?.toInt()
                var bin2 = upModbusHelper.read02DiscreteInputs(modbusAddress.upBin2, 1, 1, "${lineId}上料光电2")?.getByte(0)?.toInt()
                var bin3 = upModbusHelper.read02DiscreteInputs(modbusAddress.upBin3, 1, 1, "${lineId}上料光电3")?.getByte(0)?.toInt()
                if (!(bin1 == 0 && bin2 == 0 && bin3 == 0)) return

                Thread.sleep(3000)
                bin1 = upModbusHelper.read02DiscreteInputs(modbusAddress.upBin1, 1, 1, "${lineId}上料光电1")?.getByte(0)?.toInt()
                bin2 = upModbusHelper.read02DiscreteInputs(modbusAddress.upBin2, 1, 1, "${lineId}上料光电2")?.getByte(0)?.toInt()
                bin3 = upModbusHelper.read02DiscreteInputs(modbusAddress.upBin3, 1, 1, "${lineId}上料光电3")?.getByte(0)?.toInt()
                if (!(bin1 == 0 && bin2 == 0 && bin3 == 0)) {
                    logger.debug("bin1=$bin1, bin2=$bin2, bin3=$bin3")
                    return
                }
                val existed = MongoDBManager.collection<RobotTask>().findOne(and(
                    RobotTask::state lt RobotTaskState.Success,
                    RobotTask::def eq "UpTask"
                ))
                if (existed != null && existed.workStations.contains(lineId)) return
                createUpTask() // 为防止重复重建，数据库存储完成后再改 upChecking
            } catch (e: Exception) {
                logger.error("error $lineId up", e)
            }
            finally {
                upChecking = false
            }
        }
    }

    private fun checkLineDownFill() {
        synchronized(downModbusHelper) {
            if (downChecking) return
            downChecking = true
            try {

                checkDownCarFull(lineId)
                val modbusAddress = CUSTOM_CONFIG.modbusAddress
                var bin1 = downModbusHelper.read02DiscreteInputs(modbusAddress.downBin1, 1, 1, "${lineId}下料光电1")?.getByte(0)?.toInt()
                var bin2 = downModbusHelper.read02DiscreteInputs(modbusAddress.downBin2, 1, 1, "${lineId}下料光电2")?.getByte(0)?.toInt()
                if (bin1 == 0 || bin2 == 0) return

                Thread.sleep(2000)
                bin1 = downModbusHelper.read02DiscreteInputs(modbusAddress.downBin1, 1, 1, "${lineId}下料光电1")?.getByte(0)?.toInt()
                bin2 = downModbusHelper.read02DiscreteInputs(modbusAddress.downBin2, 1, 1, "${lineId}下料光电2")?.getByte(0)?.toInt()
//                if (!(bin1 != 0 && bin2 != 0)) return
                if (bin1 == 0 || bin2 == 0) {
                    logger.debug("bin1=$bin1, bin2=$bin2")
                    return
                }

                val existed = MongoDBManager.collection<RobotTask>().findOne(and(
                    RobotTask::state lt RobotTaskState.Success,
                    RobotTask::def eq "DownTask"
                ))
                if (existed != null && existed.workStations.contains(lineId)) return
                createDownTask() // 为防止重复重建，数据库存储完成后再改 upChecking
            }catch (e: Exception) {
                logger.error("error $lineId down", e)
            } finally {
                downChecking = false
            }
        }
    }
    
    private fun createUpTask() {
        Thread.sleep(3000)
        val def = getRobotTaskDef("UpTask") ?: throw BusinessError("NoSuchTaskDef: 'UpTask'")
        val task = buildTaskInstanceByDef(def)
        // 产线生产类型
        val productType = Services.getProductTypeOfLine(lineId)
        if (productType.isNullOrBlank()) return
        // 料车生产类型
        val carProductTypes = MongoDBManager.collection<UpCarProductType>().find(
            UpCarProductType::productType eq productType).toMutableList()
        if (carProductTypes.isNullOrEmpty()) return
        for (carProductType in carProductTypes) {
            // 料车有没有物料
            val carNum = carProductType.carNum
            val column = carProductType.column
            val ocrError = Services.getOcrError(carNum, column)
            if (ocrError != null && ocrError.error && ocrError.errorSites.isNotEmpty()) {
                logger.debug("upTask: ocr error existed, $carNum $column")
                continue
            }

            // 先把这列库位拿出来
            val sites = MongoDBManager.collection<StoreSite>().find(
                StoreSite::label eq "CART-UP-$carNum-$column").toMutableList()

            // 没有设置这辆车
            if (sites.isNullOrEmpty()) {
                logger.debug("there is no car $carNum")
                continue
            }

            // 这个列已占用弹匣数量
            val availableSites = sites.filter { it.filled }

            if(availableSites.isNullOrEmpty()) continue

            if (availableSites.filter {
                val type = Services.getProductTypeOfMatId(it.content)
                type == productType
                }.isNullOrEmpty()) continue

            // 把lineId保存
            task.workStations.add(lineId)
            task.persistedVariables["lineId"] = lineId
            task.persistedVariables["carNum"] = carNum
            task.persistedVariables["column"] = column
            task.persistedVariables["canUp"] = false
            // 检索最多三个库位
            val neededSites = checkAndGetSites(carNum, column, true)
            task.persistedVariables["neededSites"] = neededSites.map { it.id }
            task.priority = 10
            RobotTaskService.saveNewRobotTask(task)
            logger.debug("create up task success, to CART-UP-$carNum-$column, get $neededSites")
        }
    }

    private fun createDownTask() {
        Thread.sleep(3000)
        val def = getRobotTaskDef("DownTask") ?: throw BusinessError("NoSuchTaskDef: 'DownTask'")
        val task = buildTaskInstanceByDef(def)
        val column = if (lineId == "line11") "A" else "B"

        for (carNum in 4..5) {
            val sites = MongoDBManager.collection<StoreSite>().find(
                StoreSite::label eq "CART-DOWN-$carNum-$column").toMutableList()
            if (sites.isNullOrEmpty()) {
                logger.warn("there is no car $carNum column $column")
                continue
            }
            val availableSites = sites.filter { !it.filled }
            if (availableSites.size < 2) logger.debug("下料车${carNum}满 告警")
        }

        task.workStations.add(lineId)
        task.persistedVariables["lineId"] = lineId
        task.persistedVariables["column"] = column
        task.persistedVariables["carNum"] = "0"
        task.persistedVariables["canDown"] = 0
//        task.persistedVariables["error"] = ""

        task.persistedVariables["neededSites"] = mutableListOf<String>()

        task.priority = 30
        RobotTaskService.saveNewRobotTask(task)
    }

    // 上3下2
    private fun checkAndGetSites(carNum: String, column: String, up: Boolean): List<StoreSite> {
        val id = if (up) "CART-UP-$carNum-$column" else "CART-DOWN-$carNum-$column"
        val limit = if (up) 3 else 2
        return MongoDBManager.collection<StoreSite>().find(
            StoreSite::id gt id, StoreSite::filled eq true)
            .limit(limit).sort(Sorts.ascending("_id")).toMutableList()
    }
}