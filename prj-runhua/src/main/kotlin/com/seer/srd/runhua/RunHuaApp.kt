package com.seer.srd.runhua

import com.seer.srd.Application
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventbus.EventBus
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.robottask.*
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.setVersion
import org.litote.kmongo.lt
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main() {
    Application.initialize()
    RunHuaApp.init()

    EventBus.robotTaskFinishedEventBus.add(RunHuaApp::onRobotTaskFinished)

    Application.start()
}

object RunHuaApp{

    private val logger = LoggerFactory.getLogger(RunHuaApp::class.java)

    val helpers: MutableMap<String, ModbusTcpMasterHelper> = mutableMapOf()

    private val executor = Executors.newScheduledThreadPool(1)

    init {
        CUSTOM_CONFIG.plcConfig.forEach { plc ->
            helpers[plc.key] = ModbusTcpMasterHelper(plc.value.host, plc.value.port)
        }
        helpers.values.forEach { it.connect() }
        executor.scheduleAtFixedRate(this::createTask, 1000, CUSTOM_CONFIG.interval.toLong(), TimeUnit.MILLISECONDS)
    }

    fun init() {
        setVersion("润华", "1.0.1")
        registerRobotTaskComponents(ExtraComponent.extraComponent)
    }

    private fun createTask() {
        helpers.forEach { helper ->
            try {
//                val unfinishedTasks = MongoDBManager.collection<RobotTask>().find(RobotTask::state lt RobotTaskState.Success).toList()
                val callA = helper.value.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.callA, 1, 1, "create task: read call A")?.getByte(0)?.toInt()
                if (callA == 1) {
                    logger.debug("read callA = 1")
                    val calledA = helper.value.read01Coils(CUSTOM_CONFIG.plcAddress.calledA, 1, 1, "create task: read called A")?.getByte(0)?.toInt()
                    if (calledA == 0) {
                        Thread.sleep(200)
                        logger.debug("read called A = 0, try to create task from A")
                        val def = getRobotTaskDef("transfer") ?: throw BusinessError("No such task def [transfer]")
                        val task = buildTaskInstanceByDef(def)
                        task.persistedVariables["from"] = "A"
                        task.persistedVariables["to"] = CUSTOM_CONFIG.fromToMap["A"] ?: throw BusinessError("起点A没有对应的终点")
                        task.persistedVariables["plc"] = helper.key
                        task.transports[0].stages[2].location = "A"
                        task.transports[1].stages[2].location = CUSTOM_CONFIG.fromToMap["A"] ?: throw BusinessError("起点A没有对应的终点")
                        task.transports[2].stages[2].location = CUSTOM_CONFIG.fromToMap["A"] ?: throw BusinessError("起点A没有对应的终点")
                        RobotTaskService.saveNewRobotTask(task)
                        helper.value.write05SingleCoil(CUSTOM_CONFIG.plcAddress.calledA, true, 1, "create task: write calledA")
                    } else if (calledA == 1) {
                        logger.debug("read calledA = 1, skip over creating task")
                    }
                }
                Thread.sleep(200)
                val callB = helper.value.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.callB, 1, 1, "create task: read call B")?.getByte(0)?.toInt()
                if (callB == 1) {
                    logger.debug("read callB = 1")
                    val calledB = helper.value.read01Coils(CUSTOM_CONFIG.plcAddress.calledB, 1, 1, "create task: read called B")?.getByte(0)?.toInt()
                    if (calledB == 0) {
                        Thread.sleep(200)
                        logger.debug("read called B = 0, try to create task from B")
                        val def = getRobotTaskDef("transfer") ?: throw BusinessError("No such task def [transfer]")
                        val task = buildTaskInstanceByDef(def)
                        task.persistedVariables["from"] = "B"
                        task.persistedVariables["to"] = CUSTOM_CONFIG.fromToMap["B"] ?: throw BusinessError("起点B没有对应的终点")
                        task.persistedVariables["plc"] = helper.key
                        task.transports[0].stages[2].location = "B"
                        task.transports[1].stages[2].location = CUSTOM_CONFIG.fromToMap["B"] ?: throw BusinessError("起点B没有对应的终点")
                        task.transports[2].stages[2].location = CUSTOM_CONFIG.fromToMap["B"] ?: throw BusinessError("起点B没有对应的终点")
                        RobotTaskService.saveNewRobotTask(task)
                        helper.value.write05SingleCoil(CUSTOM_CONFIG.plcAddress.calledB, true, 1, "create task: write calledB")
                    } else if (calledB == 1) {
                        logger.debug("read calledB = 1, skip over creating task")
                    }
                }
                Thread.sleep(200)
                val callC = helper.value.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.callC, 1, 1, "create task: read call C")?.getByte(0)?.toInt()
                if (callC == 1) {
                    Thread.sleep(200)
                    logger.debug("read callC = 1")
                    val calledC = helper.value.read01Coils(CUSTOM_CONFIG.plcAddress.calledC, 1, 1, "create task: read called C")?.getByte(0)?.toInt()
                    if (calledC == 0) {
                        logger.debug("read called C = 0, try to create task from C")
                        val def = getRobotTaskDef("transfer") ?: throw BusinessError("No such task def [transfer]")
                        val task = buildTaskInstanceByDef(def)
                        task.persistedVariables["from"] = "C"
                        task.persistedVariables["to"] = CUSTOM_CONFIG.fromToMap["C"] ?: throw BusinessError("起点C没有对应的终点")
                        task.persistedVariables["plc"] = helper.key
                        task.transports[0].stages[2].location = "C"
                        task.transports[1].stages[2].location = CUSTOM_CONFIG.fromToMap["C"] ?: throw BusinessError("起点C没有对应的终点")
                        task.transports[2].stages[2].location = CUSTOM_CONFIG.fromToMap["C"] ?: throw BusinessError("起点C没有对应的终点")
                        RobotTaskService.saveNewRobotTask(task)
                        helper.value.write05SingleCoil(CUSTOM_CONFIG.plcAddress.calledC, true, 1, "create task: write calledC")
                    } else if (calledC == 1) {
                        logger.debug("read calledC = 1, skip over creating task")
                    }
                }
                Thread.sleep(200)
                val callD = helper.value.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.callD, 1, 1, "create task: read call D")?.getByte(0)?.toInt()
                if (callD == 1) {
                    Thread.sleep(200)
                    logger.debug("read callD = 1")
                    val calledD = helper.value.read01Coils(CUSTOM_CONFIG.plcAddress.calledD, 1, 1, "create task: read called D")?.getByte(0)?.toInt()
                    if (calledD == 0) {
                        logger.debug("read called D = 0, try to create task from D")
                        val def = getRobotTaskDef("transfer") ?: throw BusinessError("No such task def [transfer]")
                        val task = buildTaskInstanceByDef(def)
                        task.persistedVariables["from"] = "D"
                        task.persistedVariables["to"] = CUSTOM_CONFIG.fromToMap["D"] ?: throw BusinessError("起点D没有对应的终点")
                        task.persistedVariables["plc"] = helper.key
                        task.transports[0].stages[2].location = "D"
                        task.transports[1].stages[2].location = CUSTOM_CONFIG.fromToMap["D"] ?: throw BusinessError("起点D没有对应的终点")
                        task.transports[2].stages[2].location = CUSTOM_CONFIG.fromToMap["D"] ?: throw BusinessError("起点D没有对应的终点")
                        RobotTaskService.saveNewRobotTask(task)
                        helper.value.write05SingleCoil(CUSTOM_CONFIG.plcAddress.calledD, true, 1, "create task: write calledD")
                    } else if (calledD == 1) {
                        logger.debug("read calledD = 1, skip over creating task")
                    }
                }
            } catch (e: Exception) {
                logger.error("task trigger error", e)
            }

        }
    }

    fun onRobotTaskFinished(task: RobotTask) {
        if (task.def == "transfer" && task.state > RobotTaskState.Success) {
            try {
                // 信号复位
                val from = task.persistedVariables["from"] as String
                logger.debug("reset signal, from=$from")

                val plc = task.persistedVariables["plc"] as String
                val helper = helpers[plc] ?: throw BusinessError("no such config $plc")
                when (from) {
                    "A" -> {
                        helper.write0FMultipleCoils(CUSTOM_CONFIG.plcAddress.calledA, 3, byteArrayOf(0), 1, "reset A")
                    }
                    "B" -> {
                        helper.write0FMultipleCoils(CUSTOM_CONFIG.plcAddress.calledB, 2, byteArrayOf(0), 1, "reset B")
                        Thread.sleep(200)
                        helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.onPositionToA, false, 1, "reset on position to A")
                    }
                    "C" -> {
                        helper.write0FMultipleCoils(CUSTOM_CONFIG.plcAddress.calledC, 2, byteArrayOf(0), 1, "reset C")
                        Thread.sleep(200)
                        helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.onPositionToA, false, 1, "reset on position to A")
                    }
                    "D" -> {
                        helper.write0FMultipleCoils(CUSTOM_CONFIG.plcAddress.calledD, 3, byteArrayOf(0), 1, "reset D")
                    }
                }
            } catch (e: Exception) {
                logger.error("onRobotTaskFinished error", e)
            }
        }
    }
}