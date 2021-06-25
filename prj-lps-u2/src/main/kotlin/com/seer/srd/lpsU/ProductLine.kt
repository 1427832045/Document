package com.seer.srd.lpsU

import com.mongodb.client.model.Sorts
import com.seer.srd.BusinessError
import com.seer.srd.TaskAbortedError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventlog.EventLogLevel
import com.seer.srd.eventlog.SystemEvent
import com.seer.srd.eventlog.recordSystemEventLog
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.lpsU.Services.checkDownCarFull
import com.seer.srd.lpsU.Services.checkUpCarEmptyOrNoNeededMag
import com.seer.srd.lpsU.Services.checkUpCarEmptyOrNoNeededMag2
import com.seer.srd.lpsU.Services.getMsgOfBufferErrorCode
import com.seer.srd.lpsU.Services.getProductTypeOfLine
import com.seer.srd.robottask.*
import com.seer.srd.robottask.RobotTaskService.throwIfTaskAborted
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import io.netty.buffer.ByteBufUtil
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
//1. 上料口1组6个新信号，3个弹匣放置位信号，2个抢占信号，1个推板动作信号 => 更新：
//2. 下料口1组5个信号，2个弹匣放置信号，2个抢占信号，1个推板动作信号 => 更新：
//3. 声光提醒灯1组1个信号，1个提醒信号
class ProductLine(private val lineId: String) {
    
    // 此产品上料口的 Modbus
    val upModbusHelper: ModbusTcpMasterHelper
    
    // 此产品下料口的 Modbus
    val downModbusHelper: ModbusTcpMasterHelper
    
    // 此产品报警的 Modbus
    val alertModbusHelper: ModbusTcpMasterHelper

    val warningAlert: MutableMap<String, Boolean> = mutableMapOf()

    var lastColumn = ""

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
        
        lineUpEmptyCheckTimer.scheduleAtFixedRate(this::checkLineUpEmpty2, 5, 3, TimeUnit.SECONDS)
        lineDownEmptyCheckTimer.scheduleAtFixedRate(this::checkLineDownFull, 5, 3, TimeUnit.SECONDS)

    }
    
    fun dispose() {
        upModbusHelper.disconnect()
        downModbusHelper.disconnect()
        alertModbusHelper.disconnect()
        
        lineUpEmptyCheckTimer.shutdown()
    }
    
    /**
     * 抢占上下料口，此方法结束标志为：
     * 1. 上料：buffer不报警，buffer没有动作，buffer1号位置无货物
     * 2. 下料：buffer不报警，buffer没有动作，buffer1号位置有货物或者超过1分钟buffer1号位置还是没货物
     */
    fun lockUpOrDown(up: Boolean, task: RobotTask) {
//        val modbusAddress = CUSTOM_CONFIG.modbusAddress
//        val a = if (up) modbusAddress.upA else modbusAddress.downA
//        val b = if (up) modbusAddress.upB else modbusAddress.downB
//        val helper = if (up) upModbusHelper else downModbusHelper
//        helper.write05SingleCoil(a, true, 1, "Write A")
//        while (true) {
//            if (helper.read02DiscreteInputs(b, 1, 1, "Read B")?.getByte(0)?.toInt() == 1) break
//        }
        val bufferAddress = CUSTOM_CONFIG.bufferAddress
        val operationAddr = if (up) bufferAddress.urUpOperationAddr else bufferAddress.urDownOperationAddr
        val bufferOperationAddr = if (up) bufferAddress.bufferUpOperationAddr else bufferAddress.bufferDownOperationAddr
        val errorCodeAddr = bufferAddress.errorCodeAddr
        val bufferOneAddr = bufferAddress.bufferOneAddr
        val numAddr = bufferAddress.matNumAddr

        val helper = if (up) upModbusHelper else downModbusHelper
        var curTime = System.currentTimeMillis()
        var canBreak = false

        while (true) {
            try {
                var dataString: String
                var data: List<Int>
                while (true) {
                    throwIfTaskAborted(task.id)
                    dataString = ByteBufUtil.hexDump(helper.read03HoldingRegisters(0, 10, 1, "$lineId ${if (up) "上料" else "下料"}buffer数据"))
                    data =  dataString.map { Integer.valueOf(it.toString(), 16) }

//                    val errorCode = helper.read03HoldingRegisters(errorCodeAddr, 1, 1, "lock: buffer报警码")?.getShort(0)?.toInt()
                    val errorCode = data[23]
                    if (errorCode != 0) {
                        if (warningAlert["BufferError"] != true) {
                            warningAlert["BufferError"] = true
                            WebSockets.onBufferError(BufferErrorMessage(lineId, if (up) "up" else "down", getMsgOfBufferErrorCode(errorCode)))
                            switchLineAlert(true)
                        }
                        val time = System.currentTimeMillis()
                        if (time - curTime > CUSTOM_CONFIG.bufferCheckInterval) {
                            logger.error("$lineId,${if (up) "up" else "down"},${getMsgOfBufferErrorCode(errorCode)}")
                            curTime = System.currentTimeMillis()
                        }
                        Thread.sleep(2000)
                        continue
                    }
                    val bufferOne = data[27]
//                    val bufferOne = helper.read03HoldingRegisters(bufferOneAddr, 1, 1, "lock:buffer第一个位置")?.getShort(0)?.toInt()
                    if (up) {
                        if (bufferOne != 0) {
                            val time = System.currentTimeMillis()
                            if (time - curTime > CUSTOM_CONFIG.bufferCheckInterval) {
                                logger.error("${lineId}上料口buffer一号位置有物料")
                                curTime = System.currentTimeMillis()
                                recordSystemEventLog("buffer", EventLogLevel.Error, SystemEvent.Extra, "$lineId buffer已有料，无法放货!!")
                            }
                        } else if (bufferOne == 0) break
                    } else {
                        if (bufferOne == 0) {
                            val nums = data[11]
//                            val nums = helper.read03HoldingRegisters(numAddr, 1, 1, "数量: buffer报警码")?.getShort(0)?.toInt()
                            val time = System.currentTimeMillis()
                            if (time - curTime > CUSTOM_CONFIG.bufferCheckInterval && nums == 0) {
                                logger.warn("${lineId}下料口buffer一号位置${CUSTOM_CONFIG.bufferCheckInterval}ms没有检查到货物！！")
                                break
                            }
                        } else if (bufferOne == 1) break
                    }
                }

                helper.write06SingleRegister(operationAddr, 1, 1, "lock:手臂准备工作")
                Thread.sleep(200)
                helper.write06SingleRegister(bufferAddress.checkAddr, 1, 1, "lock:检查buffer通讯")

                var time = System.currentTimeMillis()
                while (true) {
                    throwIfTaskAborted(task.id)
                    dataString = ByteBufUtil.hexDump(helper.read03HoldingRegisters(0, 10, 1, "$lineId ${if (up) "上料" else "下料"}buffer数据"))
                    data =  dataString.map { Integer.valueOf(it.toString(), 16) }

                    val checkOK = data[35]
                    val bufferOperating = if (up) data[15] else data[19]
//                    val checkOK = helper.read03HoldingRegisters(bufferAddress.checkOKAddr, 1, 1, "lock:buffer通讯反馈")?.getShort(0)?.toInt()
//                    val bufferOperating = helper.read03HoldingRegisters(bufferOperationAddr, 1, 1, "lock:buffer动作信号")?.getShort(0)?.toInt()
                    if (checkOK == 1 && bufferOperating == 0) {
                        helper.write06SingleRegister(bufferAddress.checkAddr, 0, 1, "lock:检查buffer通讯复位")
                        canBreak = true
                        break
                    } else {
                        if (System.currentTimeMillis() - time > 1200) {
                            time = System.currentTimeMillis()
                            logger.debug("task paused because of checkOK=$checkOK, bufferOperating=$bufferOperating")
                        }
                    }
                }
            } catch (e: TaskAbortedError) {
                logger.debug("task ${task.id} aborted")
                return
            } catch (e: Exception) {
                logger.error("lock ${if (up) "up" else "down"} buffer error, ${e.message}", e)
                Thread.sleep(1000)
            }
            if (canBreak) break
        }

//        while (true) {
//            val errorCode = helper.read03HoldingRegisters(errorCodeAddr, 1, 1, "lock: buffer报警码")?.getShort(0)?.toInt()
//            if (errorCode != 0) {
//                if (errorCode is Int && warningAlert["BufferError"] != true) {
//                    warningAlert["BufferError"] = true
//                    WebSockets.onBufferError(BufferErrorMessage(lineId, if (up) "up" else "down", getMsgOfBufferErrorCode(errorCode)))
//                    switchLineAlert(true)
//                }
//                val time = System.currentTimeMillis()
//                if (time - curTime > CUSTOM_CONFIG.bufferCheckInterval) {
//                    logger.error("$lineId,${if (up) "up" else "down"},${getMsgOfBufferErrorCode(errorCode as Int)}")
//                    curTime = System.currentTimeMillis()
//                }
//                continue
//            }
//            val bufferOne = helper.read03HoldingRegisters(bufferOneAddr, 1, 1, "lock:buffer第一个位置")?.getShort(0)?.toInt()
//            if (up) {
//                if (bufferOne != 0) {
//                    val time = System.currentTimeMillis()
//                    if (time - curTime > CUSTOM_CONFIG.bufferCheckInterval) {
//                        logger.error("${lineId}上料口buffer一号位置有物料")
//                        curTime = System.currentTimeMillis()
//                        recordSystemEventLog("buffer", EventLogLevel.Error, SystemEvent.Extra, "$lineId buffer已有料，无法放货!!")
//                    }
//                } else if (bufferOne == 0) break
//            } else {
//                if (bufferOne == 0) {
//                    val time = System.currentTimeMillis()
//                    if (time - curTime > CUSTOM_CONFIG.bufferCheckInterval) {
//                        logger.warn("${lineId}下料口buffer一号位置1分钟内没有检查到货物！！")
//                        break
//                    }
//                } else if (bufferOne == 1) break
//            }
//        }
//
//        helper.write06SingleRegister(operationAddr, 1, 1, "lock:手臂准备工作")
//        helper.write06SingleRegister(bufferAddress.checkAddr, 1, 1, "lock:检查buffer通讯")
//
//        while (true) {
//            val checkOK = helper.read03HoldingRegisters(bufferAddress.checkOKAddr, 1, 1, "lock:buffer通讯反馈")?.getShort(0)?.toInt()
//            val bufferOperating = helper.read03HoldingRegisters(bufferOperationAddr, 1, 1, "lock:buffer动作信号")?.getShort(0)?.toInt()
//            if (checkOK == 1 && bufferOperating == 0) {
//                helper.write06SingleRegister(bufferAddress.checkAddr, 0, 1, "lock:检查buffer通讯复位")
//                break
//            } else {
//                logger.debug("task paused because of checkOK=$checkOK, bufferOperating=$bufferOperating")
//            }
//        }
    }

    fun unLockUpOrDown(up: Boolean, task: RobotTask) {
        val bufferAddress = CUSTOM_CONFIG.bufferAddress
        val operationAddr = if (up) bufferAddress.urUpOperationAddr else bufferAddress.urDownOperationAddr
        val helper = if (up) upModbusHelper else downModbusHelper
        var canBreak = false
        while (true) {
            try {
                throwIfTaskAborted(task.id)
                helper.write06SingleRegister(operationAddr, 0, 1, if (up) "unlock:${lineId}上料口" else "unlock:${lineId}下料口")
                canBreak = true
            } catch (e: TaskAbortedError) {
                logger.error("unlock up=$up task ${task.id} aborted")
                return
            } catch (e: Exception) {
                logger.error("unlock ${if (up) "up" else "down"} buffer error, ${e.message}", e)
                Thread.sleep(2000)
            }
            if (canBreak) break
        }

    }
    
    // 翻板，C 写 1 再写 0
    fun flipUpOrDown(up: Boolean) {
//        val modbusAddress = CUSTOM_CONFIG.modbusAddress
//        val c = if (up) modbusAddress.upC else modbusAddress.downC
//        val helper = if (up) upModbusHelper else downModbusHelper
//        helper.write05SingleCoil(c, true, 1, "Write C 1")
//        Thread.sleep(CUSTOM_CONFIG.flipCInterval)
//        helper.write05SingleCoil(c, false, 1, "Write C 0")
    }

    fun switchLineAlert(on: Boolean) {
        val bufferAddress = CUSTOM_CONFIG.bufferAddress
        alertModbusHelper.write05SingleCoil(bufferAddress.lightAddr, on, 1, "$lineId Alert $on")
    }

    fun resetErrorCode(up: Boolean) {
        val bufferAddress = CUSTOM_CONFIG.bufferAddress
        if (up) upModbusHelper.write06SingleRegister(bufferAddress.errorCodeAddr, 0, 1, "reset:${lineId}上料errorCode")
        else downModbusHelper.write06SingleRegister(bufferAddress.errorCodeAddr, 0, 1, "reset:${lineId}下料errorCode")
    }

    // 上料车每辆都是1条产线的所需物料，不分类型
    // 拿了A列下次拿B列
    private fun checkLineUpEmpty2() {
        synchronized(upModbusHelper) {
            if (upChecking) return
            upChecking = true
            try {
                checkUpCarEmptyOrNoNeededMag2(lineId)

                val existed = MongoDBManager.collection<RobotTask>().findOne(and(
                    RobotTask::state lt RobotTaskState.Success,
                    RobotTask::def eq "UpTask"
                ))
                if (existed != null) return

                val bufferAddress = CUSTOM_CONFIG.bufferAddress

                var dataString = ByteBufUtil.hexDump(upModbusHelper.read03HoldingRegisters(bufferAddress.urUpOperationAddr, 10, 1, "${lineId}上料buffer数据"))
                if (dataString.isEmpty()) throw BusinessError("read buffer data error")
                var data =  dataString.map { Integer.valueOf(it.toString(), 16) }
                var matNum = data[11]

                if (matNum != 0) return
                var bufferOne = data[27]
                if (bufferOne != 0) return

                Thread.sleep(3000)
                dataString = ByteBufUtil.hexDump(upModbusHelper.read03HoldingRegisters(0, 10, 1, "${lineId}上料buffer数据"))
                if (dataString.isEmpty()) throw BusinessError("read buffer data error")
                data =  dataString.map { Integer.valueOf(it.toString(), 16) }

                matNum = data[11]
                if (matNum != 0) {
                    logger.debug("${lineId}上料buffer数量: $matNum，跳过此次上料任务创建")
                    return
                }
                bufferOne = data[27]
                if (bufferOne != 0) return

                val errorCode = data[23]
                if (errorCode != 0) {
                    if (errorCode is Int && warningAlert["BufferError"] != true) {
                        warningAlert["BufferError"] = true
                        WebSockets.onBufferError(BufferErrorMessage(lineId, "up", getMsgOfBufferErrorCode(errorCode)))
                        switchLineAlert(true)
                    }
                    return
                }

//                val existed = MongoDBManager.collection<RobotTask>().findOne(and(
//                    RobotTask::state lt RobotTaskState.Success,
//                    RobotTask::def eq "UpTask"
//                ))
//                if (existed != null) return
                createUpTask2() // 为防止重复重建，数据库存储完成后再改 upChecking
            } catch (e: Exception) {
                logger.error("error $lineId up", e)
            }
            finally {
                upChecking = false
            }
        }
    }

    private fun checkLineUpEmpty() {
        synchronized(upModbusHelper) {
            if (upChecking) return
            upChecking = true
            try {
                checkUpCarEmptyOrNoNeededMag(lineId)
                val bufferAddress = CUSTOM_CONFIG.bufferAddress
                var matNum = upModbusHelper.read03HoldingRegisters(bufferAddress.matNumAddr, 1, 1, "${lineId}上料buffer数量")?.getShort(0)?.toInt()
                if (matNum != 0) return
                var bufferOne = upModbusHelper.read03HoldingRegisters(bufferAddress.bufferOneAddr, 1, 1, "上料口1号位置")?.getShort(0)?.toInt()
                if (bufferOne != 0) return

                Thread.sleep(3000)
                matNum = upModbusHelper.read03HoldingRegisters(bufferAddress.matNumAddr, 1, 1, "${lineId}上料buffer数量")?.getShort(0)?.toInt()
                if (matNum != 0) {
                    logger.debug("${lineId}上料buffer数量: $matNum，跳过此次上料任务创建")
                    return
                }
                bufferOne = upModbusHelper.read03HoldingRegisters(bufferAddress.bufferOneAddr, 1, 1, "上料口1号位置")?.getShort(0)?.toInt()
                if (bufferOne != 0) return

                val errorCode = upModbusHelper.read03HoldingRegisters(bufferAddress.errorCodeAddr, 1, 1, "$lineId up buffer error code")?.getShort(0)?.toInt()
                if (errorCode != 0) {
                    if (errorCode is Int && warningAlert["BufferError"] != true) {
                        warningAlert["BufferError"] = true
                        WebSockets.onBufferError(BufferErrorMessage(lineId, "up", getMsgOfBufferErrorCode(errorCode)))
                        switchLineAlert(true)
                    }
                    return
                }

                val existed = MongoDBManager.collection<RobotTask>().findOne(and(
                    RobotTask::state lt RobotTaskState.Success,
                    RobotTask::def eq "UpTask"
                ))
                if (existed != null) return
                createUpTask() // 为防止重复重建，数据库存储完成后再改 upChecking
            } catch (e: Exception) {
                logger.error("error $lineId up", e)
            }
            finally {
                upChecking = false
            }
        }
    }

    private fun checkLineDownFull() {
        synchronized(downModbusHelper) {
            if (downChecking) return
            downChecking = true
            try {

                checkDownCarFull(lineId)

                val availableAgvSites = StoreSiteService.listStoreSites().filter { it.type == "AGV" && !it.filled }
                if (availableAgvSites.size < CUSTOM_CONFIG.checkDownSize) return

                val existed = MongoDBManager.collection<RobotTask>().findOne(and(
                    RobotTask::state lt RobotTaskState.Success,
                    RobotTask::def eq "DownTask"
                ))
                if (existed != null) return

                val bufferAddress = CUSTOM_CONFIG.bufferAddress

                var dataString = ByteBufUtil.hexDump(downModbusHelper.read03HoldingRegisters(bufferAddress.urUpOperationAddr, 10, 1, "${lineId}下料buffer数据"))
                if (dataString.isEmpty()) throw BusinessError("read buffer data error")
                var data =  dataString.map { Integer.valueOf(it.toString(), 16) }
                var matNum = data[11]

                if (matNum is Int && matNum < 5) return
                var bufferOne = data[27]
                if (bufferOne == 0) return

                Thread.sleep(2000)
                dataString = ByteBufUtil.hexDump(downModbusHelper.read03HoldingRegisters(bufferAddress.urUpOperationAddr, 10, 1, "${lineId}下料buffer数据"))
                if (dataString.isEmpty()) throw BusinessError("read buffer data error")
                data =  dataString.map { Integer.valueOf(it.toString(), 16) }
                matNum = data[11]
                if (matNum is Int && matNum < 5) {
                    logger.debug("${lineId}下料buffer数量: $matNum，跳过此次下料任务创建")
                    return
                }
                bufferOne = data[27]
                if (bufferOne == 0) return

                val errorCode = data[23]
                if (errorCode != 0) {
                    if (errorCode is Int && warningAlert["BufferError"] != true) {
                        warningAlert["BufferError"] = true
                        WebSockets.onBufferError(BufferErrorMessage(lineId, "down", getMsgOfBufferErrorCode(errorCode)))
                        switchLineAlert(true)
                    }
                    return
                }

//                val existed = MongoDBManager.collection<RobotTask>().findOne(and(
//                    RobotTask::state lt RobotTaskState.Success,
//                    RobotTask::def eq "DownTask"
//                ))
//                if (existed != null) return
                if (CUSTOM_CONFIG.multiCar) createDownTask2()
                else createDownTask()
                // 为防止重复重建，数据库存储完成后再改 downChecking
            }catch (e: Exception) {
                logger.error("error $lineId down", e)
            } finally {
                downChecking = false
            }
        }
    }

    private fun createUpTask2() {
        Thread.sleep(3000)
        val def = getRobotTaskDef("UpTask") ?: throw BusinessError("NoSuchTaskDef: 'UpTask'")

        // 料车生产类型
        val carProductTypes = MongoDBManager.collection<UpCarProductType>().find(
            UpCarProductType::productType eq lineId).toMutableList().apply {
            if (lastColumn == "A") this.sortByDescending { it.column }
            else this.sortBy { it.column }
        }
        if (carProductTypes.isNullOrEmpty()) return

        for (carProductType in carProductTypes) {

            // 料车有没有物料
            val carNum = carProductType.carNum
            val column = carProductType.column

            // 先把这列库位拿出来
            val sites =StoreSiteService.listStoreSites().filter { it.id.contains("CART-UP-$carNum-$column") }
            if (sites.isNullOrEmpty()) continue
            // 这个列已占用弹匣数量
            val availableSites = sites.filter { it.filled }

            if(availableSites.isNullOrEmpty()) continue

            val task = buildTaskInstanceByDef(def)
            // 把lineId保存
            task.workStations.add(lineId)
            task.persistedVariables["lineId"] = lineId
            task.persistedVariables["carNum"] = carNum
            task.persistedVariables["column"] = column
            task.persistedVariables["canUp"] = false
            // 检索最多六个库位
            val neededSites = checkAndGetSites(carNum, column, true)
//            if (neededSites.size < CUSTOM_CONFIG.upSize) return

            task.persistedVariables["upType"] = getProductTypeOfLine(lineId) ?: ""
            task.persistedVariables["neededSites"] = neededSites.map { it.id }
//            MongoDBManager.collection<TaskExtraParam>().insertOne(
//                TaskExtraParam(taskId = task.id, lineId = lineId, type = getProductTypeOfLine(lineId) ?: "", matNum = neededSites.size.toString())
//            )
//            task.persistedVariables["upMatNum"] = neededSites.size

            task.priority = 10
            RobotTaskService.saveNewRobotTask(task)
            logger.debug("create up task success, to CART-UP-$carNum-$column, get $neededSites")
            lastColumn = column
            break
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
//            val ocrError = Services.getOcrError(carNum, column)
//            if (ocrError != null && ocrError.error && ocrError.errorSites.isNotEmpty()) {
//                logger.debug("upTask: ocr error existed, $carNum $column")
//                continue
//            }

            // 先把这列库位拿出来
            val sites = StoreSiteService.listStoreSites().filter { it.id.contains("CART-UP-$carNum-$column") }
//            val sites = MongoDBManager.collection<StoreSite>().find(
//                StoreSite::label eq "CART-UP-$carNum-$column").toMutableList()

            // 没有设置这辆车
            if (sites.isNullOrEmpty()) {
                logger.debug("there is no car $carNum")
                continue
            }

            // 这个列已占用弹匣数量
            val availableSites = sites.filter { it.filled }

            if(availableSites.isNullOrEmpty()) continue

            // 把lineId保存
            task.workStations.add(lineId)
            task.persistedVariables["lineId"] = lineId
            task.persistedVariables["carNum"] = carNum
            task.persistedVariables["column"] = column
            task.persistedVariables["canUp"] = false
            // 检索最多六个库位
            val neededSites = checkAndGetSites(carNum, column, true)
            if (neededSites.size < CUSTOM_CONFIG.upSize) return
            task.persistedVariables["neededSites"] = neededSites.map { it.id }

            MongoDBManager.collection<TaskExtraParam>().insertOne(
                TaskExtraParam(taskId = task.id, lineId = lineId, type = getProductTypeOfLine(lineId) ?: "", matNum = neededSites.size.toString())
            )

            task.persistedVariables["upType"] = getProductTypeOfLine(lineId) ?: ""
            task.persistedVariables["upMatNum"] = neededSites.size
            task.priority = 10
            RobotTaskService.saveNewRobotTask(task)
            logger.debug("create up task success, to CART-UP-$carNum-$column, get $neededSites")
        }
    }

    // 下料车有2辆
    private fun createDownTask2() {
        Thread.sleep(3000)
        val def = getRobotTaskDef("DownTask") ?: throw BusinessError("NoSuchTaskDef: 'DownTask'")
        val task = buildTaskInstanceByDef(def)
        val carNum = if (lineId == "line11") "4" else "5"

        for (column in listOf("A", "B")) {

            val sites = StoreSiteService.listStoreSites().filter { it.id.contains("CART-DOWN-$carNum-$column") }

            if (sites.isNullOrEmpty()) continue

            val availableSites = sites.filter { !it.filled }
            if (availableSites.size < 5) {}
//            logger.warn("下料车${carNum}的${column}列满警告")
            else {
                task.persistedVariables["column"] = column
                break
            }
        }

        // 如果两列都满了置为""
        if (task.persistedVariables["column"] == null) task.persistedVariables["column"] = ""

        task.workStations.add(lineId)
        task.persistedVariables["lineId"] = lineId

        // 有2辆下料车，已经确定产线和料车的对应关系
        task.persistedVariables["carNum"] = carNum
        task.persistedVariables["canDown"] = 0

        task.persistedVariables["neededSites"] = mutableListOf<String>()

        task.priority = 30
        RobotTaskService.saveNewRobotTask(task)
    }

    // 下料车有1辆
    private fun createDownTask() {
        Thread.sleep(3000)
        val def = getRobotTaskDef("DownTaskOneCar") ?: throw BusinessError("NoSuchTaskDef: 'DownTaskOneCar'")
        val task = buildTaskInstanceByDef(def)
        val column = if (lineId == "line11") "A" else "B"

        for (carNum in 4..5) {
//            val sites = MongoDBManager.collection<StoreSite>().find(
//                StoreSite::label eq "CART-DOWN-$carNum-$column").toMutableList()
            val sites = StoreSiteService.listStoreSites().filter { it.id.contains("CART-DOWN-$carNum-$column") }

            if (sites.isNullOrEmpty()) {
//                logger.warn("there is no car $carNum column $column")
                continue
            }
            val availableSites = sites.filter { !it.filled }
            if (availableSites.size < 3) logger.debug("下料车${carNum}满 告警")
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

    // 上6下6
    private fun checkAndGetSites(carNum: String, column: String, up: Boolean): List<StoreSite> {
        val id = if (up) "CART-UP-$carNum-$column" else "CART-DOWN-$carNum-$column"
        val limit = if (up) 6 else 6
        return StoreSiteService.listStoreSites().filter { it.label.contains(id) }.sortedBy { it.id }
//        return MongoDBManager.collection<StoreSite>().find(
//            StoreSite::label eq id, StoreSite::filled eq true)
//            .limit(limit).sort(Sorts.ascending("_id")).toMutableList()
    }
}