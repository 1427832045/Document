package com.seer.srd.gelanfu

import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskService
import com.seer.srd.scheduler.backgroundFixedExecutor
import com.seer.srd.util.getDocumentBuilder
import com.seer.srd.util.getXmlNodeByPath
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.slf4j.LoggerFactory
import java.io.InputStream

private val logger = LoggerFactory.getLogger("com.seer.srd.gelanfu")

fun processCancel(fileName: String, inputStream: InputStream) {
    logger.info("Processing cancel $fileName")

    val builder = getDocumentBuilder()
    val doc = builder.parse(inputStream)
    val rootNode = doc.firstChild

    try {
        val transferOrderNode = getXmlNodeByPath(rootNode, listOf("IDOC", "E1LTCAH", "TANUM"))
            ?: throw IllegalArgumentException("No TANUM")
        val transferOrderNo = transferOrderNode.textContent

        cancelOrder(transferOrderNo)

        logger.info("Removing cancel xml $fileName")
        outFtp.deleteFile("$ordersPath/$fileName")
    } catch (e: Exception) {
        logger.error("process order $fileName", e)
    }
}

@Synchronized
fun cancelOrder(transferOrderNo: String) {
    try {
        val old = collection<RobotTask>().findOne(RobotTask::outOrderNo eq transferOrderNo)
        if (old == null) {
            logger.error("No order to cancel $transferOrderNo")
        } else {
            backgroundFixedExecutor.submit { RobotTaskService.abortTask(old.id) }
        }
    } catch (e: Exception) {
        logger.error("cancel order", e)
    }
}
