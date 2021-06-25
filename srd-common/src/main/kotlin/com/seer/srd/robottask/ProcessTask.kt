package com.seer.srd.robottask

import com.seer.srd.*
import com.seer.srd.scheduler.backgroundCacheExecutor
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("com.seer.srd.robottask")

/** 捕获所有异常 */
fun processTaskSilent(task: RobotTask) {
    backgroundCacheExecutor.submit {
        try {
            processTask(task)
        } catch (e: Exception) {
            LOG.error("processTaskSilent", e)
        }
    }
}

private fun processTask(task: RobotTask) {
    while (true) {
        if (!isTaskCreatingPaused()) break
        Thread.sleep(500L)
    }

    LOG.info("Processing task - ${task.id} [${task.def}]")

    // getSystemLogger().debug("task persisted variables ", task.persistedVariables)

    val taskDef = getRobotTaskDef(task.def)
    try {
        RobotTaskService.throwIfTaskAborted(task.id)
        // 严格串行运单或动态可并行的运单
        if (taskDef?.parallel == true) {
            processParallelTransports(task)
        } else {
            processSimpleTransports(task)
        }

        RobotTaskService.markTaskSuccess(task)
    } catch (err: Exception) {
        // log by processTransport
        if (err is TaskAbortedError) return // 已经标记中止了
        RobotTaskService.markTaskFailed(task)
    } finally {
        // 发送订单序列结束
        sendSeqEndsIfNeed(task)
    }
}

// 串行运单：运单一个一个执行
fun processSimpleTransports(task: RobotTask) {
    for (i in task.transports.indices) {
        doProcessTransport(task, i)
    }
}

// 任意顺序执行的运单
fun processParallelTransports(task: RobotTask) {
    val futures = task.transports.mapIndexed { transportIndex, transport ->
        backgroundCacheExecutor.submit {
            // 默认会反复发送一定次数，直到达到终态
            while (true) {
                RobotTaskService.throwIfTaskAborted(task.id)
                if (transport.state == RobotTransportState.Enabled) {
                    doProcessTransport(task, transportIndex)
                }
                Thread.sleep(CONFIG.transportRetryDelay)
            }
        }
    }
    for (f in futures) f.get()
}

fun doProcessTransport(task: RobotTask, transportIndex: Int) {
    RobotTaskService.throwIfTaskAborted(task.id) // 任务被外部中断（放弃）
    val transport = task.transports[transportIndex]
    when {
        transport.state == RobotTransportState.Failed -> {
            // 如果有一个运单失败，但整个任务未失败，可能是由于系统宕机
            throw BusinessError("运单${transportIndex}失败")
        }
        transport.state == RobotTransportState.Skipped -> {
            LOG.info("transport skipped task=${task.id}, transport.index=${transportIndex}")
        }
        transport.state < RobotTransportState.Success -> {
            processTransport(task, transportIndex, transport)
        }
    }
}

fun blockIfPausedProcessing() {
    while (true) {
        if (!isTaskProcessingPaused()) break
        Thread.sleep(1000L)
    }
}