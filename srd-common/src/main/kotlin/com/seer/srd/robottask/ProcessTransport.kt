package com.seer.srd.robottask

import com.seer.srd.*
import com.seer.srd.route.service.TransportOrderIOService.changeOrderDeadline
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("com.seer.srd.robottask")

fun processTransport(task: RobotTask, transportIndex: Int, transport: RobotTransport) {
    blockIfPausedProcessing()

    LOG.info("Processing transport task=${task.id}, transport.index=$transportIndex")

    try {
        // 检查当前运单是否属于一个订单序列的开始
        sendSeqStartIfNeed(task, transportIndex)

        val taskDef = getRobotTaskDef(task.def)
        val transportDef = taskDef?.transports?.get(transportIndex)

        val stageNum = transport.stages.size
        for (i in 0 until stageNum) {
            RobotTaskService.throwIfTaskAborted(task.id)

            val stage = transport.stages[i]
            if (stage.state == RobotStageState.Failed) {
                // 正常不会出现这种情况，可能由于发生了系统宕机
                throw Error400("StageFailed", "阶段${i}已失败。" + stage.blockReason)
            } else if (stage.state < RobotStageState.Success) {
                // 后处理器只处理特定的运单状态。例如只处理成功完成的运单。
                processStage(task, transportIndex, i)
            }
            // 同步运单的状态
            if (transport.routeOrderName.isNotEmpty() && isLastDestinationStage(i, transportDef, transport)) {
                syncTransportState(task, transport, transportIndex)
            }
        }

        LOG.info("transport END with state ${transport.state}. task=${task.id}, transport.index=$transportIndex")
    } catch (err: TaskAbortedError) {
        throw err
    } catch (err: RouteOrderFailedError) {
        RobotTaskService.updateTransportStateToFinal(RobotTransportState.Failed, err.message ?: "", transport, task)
        throw err
    } catch (err: SyncRouteOrderError) {
        RobotTaskService.updateTransportStateToFinal(RobotTransportState.Failed, err.message ?: "", transport, task)
        throw err
    } catch (err: SkipCurrentTransport) {
        LOG.info("skip transport, task=${task.id}, transport.index=${transportIndex}")
        RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, err.message ?: "", transport, task)
    } catch (err: Exception) {
        LOG.error("process transport, task=${task.id}, transport.index=${transportIndex}", err)
        RobotTaskService.updateTransportStateToFinal(RobotTransportState.Failed, err.message ?: "", transport, task)
        throw err
    }
}


/** 这个阶段是否对应运单的最后一个 destination */
private fun isLastDestinationStage(stageIndex: Int, def: RobotTransportDef?, transport: RobotTransport): Boolean {
    if (def == null) return stageIndex == transport.stages.size - 1 // 没有任务定义时，每个阶段都对应
    for (i in stageIndex + 1 until def.stages.size) {
        if (def.stages[i].forRoute == true) return false
    }
    return true

}

fun adjustSentTransportDeadlineForPriority(task: RobotTask, priority: Int) {
    val deadline = taskPriorityToDeadline(priority)
    for (transport in task.transports) {
        if (transport.state >= RobotTransportState.Sent) {
            changeOrderDeadline(transport.routeOrderName, deadline)
        }
    }
}

/** 反复读取调度系统直到运单达到终态 */
private fun syncTransportState(task: RobotTask, transport: RobotTransport, transportIndex: Int) {
    val logTag = "task=${task.id}, transport.index=$transportIndex"
    LOG.info("sync transport state. $logTag")

    if (transport.state >= RobotTransportState.Success) return

    val order = waitTransportOrderFinish(task, transport)

    if (isRouteOrderSuccess(order)) {
        LOG.info("transport order SUCCESS. $logTag")
        RobotTaskService.updateTransportStateToFinal(RobotTransportState.Success, "", transport, task)
        return
    } else {
        LOG.info("transport order FAILED.(${order.state}) $logTag")
        throw RouteOrderFailedError()
    }
}