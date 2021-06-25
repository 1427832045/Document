package com.seer.srd.robottask

import com.seer.srd.*
import com.seer.srd.domain.Property
import com.seer.srd.robottask.component.processComponents
import com.seer.srd.route.service.CreateDestinationReq
import com.seer.srd.route.service.CreateTransportOrderReq
import com.seer.srd.route.service.TransportOrderIOService.createTransportOrder
import com.seer.srd.util.mapper
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("com.seer.srd.robottask")

/** 处理一个阶段 */
fun processStage(task: RobotTask, transportIndex: Int, stageIndex: Int) {
    blockIfPausedProcessing()

    val logTag = "task=${task.id}, transport.index=${transportIndex}, stage.index=${stageIndex}"
    val processStart = System.currentTimeMillis()

    LOG.info("Processing stage $logTag")

    val transport = task.transports[transportIndex]
    val stage = transport.stages[stageIndex]
    val taskDef = getRobotTaskDef(task.def)
    val stageDef = taskDef?.transports?.get(transportIndex)?.stages?.get(stageIndex)

    val ctx = ProcessingContext(task, transportIndex, stageIndex)

    try {
        // 当遇到运单的第一个 destination 时发送
        // 发送运单给 RoboRoute，反复发送直到成功或失败
        if (transport.state < RobotTransportState.Sent && (stageDef == null || stageDef.forRoute == true)) {
            sendOrderToRoute(ctx)
            LOG.info("send transport DONE ${transport.routeOrderName} $logTag")
        }

        RobotTaskService.throwIfTaskAborted(task.id)

        // 这是一个对应运单 destination 的阶段，因此要先向调度查询其达到终态
        if ((stageDef == null || stageDef.forRoute == true) && !isRouteDestinationEnd(stage.routeState)) {
            syncDestinationStage(ctx)
        }

        RobotTaskService.throwIfTaskAborted(task.id)

        // TODO 如果一个 destination fail

        // 处理组件。只有有定义的阶段才有组件
        if (stageDef != null) processStageComponents(ctx, stageDef)
        LOG.info("process stage components SUCCESS $logTag")

        RobotTaskService.throwIfTaskAborted(task.id)

        // 标记处理成功，并持久化
        val timeCost = System.currentTimeMillis() - processStart
        RobotTaskService.markStageSuccess(timeCost, stage, ctx.task)
    } catch (err: Exception) {
        if (err is SkipCurrentTransport || err is TaskAbortedError || err is SyncRouteOrderError) {
            throw err
        } else {
            LOG.error("processStage $logTag", err)
            throw err
        }
    }
}

/** 发送运单给调度 */
private fun sendOrderToRoute(ctx: ProcessingContext) {
    // 如果未分配调度单号，分配一个
    val transport = ctx.task.transports[ctx.transportIndex]

    val logTag = "${ctx.task.id} - ${ctx.transportIndex}"

    RobotTaskService.ensureRouteOrderName(transport, ctx.task)

    val destinations: MutableList<CreateDestinationReq> = ArrayList()
    val transportDef = ctx.taskDef?.transports?.get(ctx.transportIndex)
    for (i in transport.stages.indices) {
        val stage = transport.stages[i]
        val stageDef = transportDef?.stages?.get(i)

        if (stageDef != null && stageDef.forRoute != true) continue // 不发给调度的阶段

        val operation = StringUtils.firstNonBlank(stage.operation, stageDef?.operation)
        val locationName = StringUtils.firstNonBlank(stage.location, stageDef?.location)
        val properties = StringUtils.firstNonBlank(stage.properties, stageDef?.properties)
        if (locationName.isNullOrBlank()) throw Error400("MissingLocation", "阶段${i}未指定location")
        if (operation.isNullOrBlank()) throw Error400("MissingOperation", "阶段${i}未指定operation")

        val propertyList = stringToRoutePropertyList(properties)
        destinations.add(CreateDestinationReq(locationName, operation, propertyList))
    }

    val order = CreateTransportOrderReq(destinations)

    val deadline = transport.deadline
    if (deadline != null) order.deadline = deadline
    else order.deadline = taskPriorityToDeadline(ctx.task.priority)

    val seqId = transport.seqId
    if (!seqId.isNullOrBlank()) order.wrappingSequence = seqId // 订单序列

    val intendedRobot = transport.intendedRobot
    if (!intendedRobot.isNullOrBlank()) order.intendedVehicle = intendedRobot

    val category = transport.category
    if (!category.isNullOrBlank()) order.category = category

    LOG.info("send route order $logTag")

    try {
        createTransportOrder(transport.routeOrderName, order)
        // 标记发送成功，并持久化
        RobotTaskService.updateTransportState(RobotTransportState.Sent, transport, ctx.task)
        return /// 结束，退出方法
    } catch (e: Exception) {
        LOG.error("send route order $logTag", e)
        throw SendOrderToRouteError(e.cause?.message ?: e.message ?: "")
    }
}

private fun processStageComponents(ctx: ProcessingContext, stageDef: RobotStageDef) {
    val transport = ctx.task.transports[ctx.transportIndex]
    val stage = transport.stages[ctx.stageIndex]

    // 默认，反复执行，直到成功
    val maxRetries = stageDef.maxRetries ?: -1
    val retryDelay = stageDef.retryDelay ?: CONFIG.processorRetryDelay

    var retryIndex = 0
    while (maxRetries < 0 || retryIndex < maxRetries) {
        RobotTaskService.throwIfTaskAborted(ctx.task.id)

        retryIndex++
        try {
            processComponents(stageDef.components, ctx)
            return // 退出方法，防止执行后续 maxRetries 检查
        } catch (err: Exception) {
            if (err is SkipCurrentTransport || err is TaskAbortedError) {
                throw err
                //} else if (isFailTaskError(err)) {
                //    stage.state = RobotStageState.Failed
                //    persistTransportChanged(transport, ctx.task)
                //    throw err // 无法恢复的错误，这里抛出异常，终止整个任务运行
            } else {
                val blockReason = err.message ?: ""
                if (blockReason != stage.blockReason) {
                    if (err !is BusinessError) {
                        LOG.error("process stage components " + stageDef.description, err)
                    }
                    // 记录错误，继续下一次尝试
                    RobotTaskService.updateStageBlockReason(blockReason, stage, ctx.task)
                }
            }
        }
        Thread.sleep(retryDelay)
    }

    // 是否因重试次数过多失败
    if (maxRetries in 1..retryIndex) {
        RobotTaskService.updateStageState(RobotStageState.Failed, stage, ctx.task)
        throw RetryMaxError("重试次数超过限制 ${retryIndex}>=${maxRetries}")
    }
}

private fun stringToRoutePropertyList(str: String?): List<Property> {
    if (str.isNullOrBlank()) return emptyList()
    return mapper.readValue(
        str, mapper.typeFactory.constructCollectionType(
            MutableList::class.java,
            Property::class.java
        )
    )
}

/** 与调度同步一个 destination 的状态 */
private fun syncDestinationStage(ctx: ProcessingContext) {
    val routeDestIndex = ctx.stageIndexToDestinationIndex()

    val logTag = "task=${ctx.task.id}, transport.index=${ctx.transportIndex}, stage:.index=${ctx.stageIndex}" +
            " , routeIndex=" + routeDestIndex
    LOG.info("sync destination stage. $logTag")

    val order = waitTransportDestinationFinish(ctx.task, ctx.transport!!, routeDestIndex)
    val dest = order.destinations[routeDestIndex]
//    // 顺便查其他字段
//    if (ctx.transport.processingRobot.isNullOrBlank() && !order.processingVehicle.isNullOrBlank()) {
//        RobotTaskService.updateTransportProcessingRobot(order.processingVehicle, ctx.transport, ctx.task)
//    }

    if (isRouteOrderFailed(order)) throw RouteOrderFailedError()

    LOG.info("sync destination: " + dest.state + logTag)

    RobotTaskService.updateStageRouteState(dest.state.name, ctx.stage!!, ctx.task)
}