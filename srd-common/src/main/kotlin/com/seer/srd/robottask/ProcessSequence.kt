package com.seer.srd.robottask

import com.seer.srd.route.service.CreateOrderSequenceReq
import com.seer.srd.route.service.OrderSequenceIOService.createOrderSequence
import com.seer.srd.route.service.OrderSequenceIOService.markOrderSequenceComplete
import org.slf4j.LoggerFactory
import java.time.Instant

private val LOG = LoggerFactory.getLogger("com.seer.srd.robottask")

/** If the transport is the first one of a sequence, send start */
fun sendSeqStartIfNeed(task: RobotTask, transportIndex: Int) {
    val taskDef = getRobotTaskDef(task.def) ?: return // 没有任务定义的，现在都不需要发送序列
    val transport = task.transports[transportIndex]
    val transportDef = taskDef.transports[transportIndex]
    
    if (transportDef.seqGroup.isBlank()) return
    
    // 下面都是运单在序列中的，分为：1、序列头，未发送；2、序列头，已发送；3、非序列头
    
    val seqId = getMySeqIdOfSameGroup(task, taskDef, transportIndex, transportDef.seqGroup)
    if (seqId.isNullOrBlank()) {
        // I'm seq head, no seq(not sent), so create one seq and send it
        if (transport.seqId.isNullOrBlank()) {
            doSendSeqStart(saveSeqId(transport, task, null), transport, transportIndex, task)
        }
    } else {
        // I'm not seq head
        saveSeqId(transport, task, seqId)
    }
}


private fun getMySeqIdOfSameGroup(task: RobotTask, def: RobotTaskDef, transportIndex: Int, seqGroup: String): String? {
    if (transportIndex == 0) return null
    for (i in transportIndex - 1 downTo 0) {
        val preTransport = task.transports[i]
        val preTransportDef = def.transports[i]
        if (preTransportDef.seqGroup != seqGroup) continue
        if (preTransport.seqId.isNullOrBlank()) continue
        return preTransport.seqId
    }
    return null
}

private fun saveSeqId(transport: RobotTransport, task: RobotTask, seqId: String?): String {
    return RobotTaskService.updateTransportSeqId(seqId, transport, task)
}

private fun doSendSeqStart(seqId: String, transport: RobotTransport, transportIndex: Int, task: RobotTask) {
    LOG.info("Sending Seq start $seqId")

    val req = CreateOrderSequenceReq()
    req.failureFatal = true

    val category = getSeqCategory(task, transportIndex)
    if (!category.isNullOrBlank()) req.category = category

    val intendedVehicle = getSeqIntendedVehicle(task, transportIndex)
    if (!intendedVehicle.isNullOrBlank()) req.intendedVehicle = intendedVehicle

    try {
        createOrderSequence(seqId, req)
        LOG.info("Send Seq start $seqId successfully")
        RobotTaskService.updateTransportSeqStartSentOn(Instant.now(), transport, task)
        return
    } catch (e: Exception) {
        LOG.error("doSendSeqStart", e)
        throw e
    }
}

fun sendSeqEndsIfNeed(task: RobotTask) {
    try {
        doSendSeqEndsIfNeed(task)
    } catch (e: Exception) {
        LOG.error("sendSeqEndsIfNeed", e)
    }
}

private fun doSendSeqEndsIfNeed(task: RobotTask) {
    for (transport in task.transports) {
        val seqId = transport.seqId
        if (seqId.isNullOrBlank()) continue
        try {
            markOrderSequenceComplete(seqId)
        } catch (err: Exception) {
            LOG.error("doSendSeqEndsIfNeed $seqId", err)
        }
    }
}

private fun getSeqCategory(task: RobotTask, transportIndex: Int): String? {
    val taskDef = getRobotTaskDef(task.def) ?: return null
    val group = taskDef.transports[transportIndex].seqGroup
    for (i in transportIndex until task.transports.size) {
        val transport = task.transports[i]
        val transportDef = taskDef.transports[i]
        if (group != transportDef.seqGroup) continue
        if (!transport.category.isNullOrBlank()) return transport.category
        if (!transportDef.category.isBlank()) return transport.category
    }
    return null
}

private fun getSeqIntendedVehicle(task: RobotTask, transportIndex: Int): String? {
    val taskDef = getRobotTaskDef(task.def) ?: return null
    val group = taskDef.transports[transportIndex].seqGroup
    for (i in transportIndex until task.transports.size) {
        val transport = task.transports[i]
        val transportDef = taskDef.transports[i]
        if (group != transportDef.seqGroup) continue
        val intendedRobot = transport.intendedRobot
        if (!intendedRobot.isNullOrBlank()) return intendedRobot
    }
    return null
}
