package com.seer.srd.gelanfu

import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.robottask.RobotStage
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskService.saveNewRobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.robottask.RobotTransport
import com.seer.srd.scheduler.backgroundCacheExecutor
import com.seer.srd.util.getDocumentBuilder
import com.seer.srd.util.getXmlNodeByPath
import org.apache.commons.lang3.time.DateFormatUtils
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.slf4j.LoggerFactory
import java.io.InputStream

private val logger = LoggerFactory.getLogger("com.seer.srd.gelanfu")

fun processOrder(fileName: String, inputStream: InputStream) {
    logger.info("Processing order $fileName")

    val builder = getDocumentBuilder()
    val doc = builder.parse(inputStream)
    val rootNode = doc.firstChild

    try {
        val transferOrderNode = getXmlNodeByPath(rootNode, listOf("IDOC", "E1LTORH", "TANUM"))
            ?: throw IllegalArgumentException("No TANUM")
        val transferOrderNo = transferOrderNode.textContent

        createOrder(transferOrderNo)

        logger.info("Removing order xml $fileName")
        outFtp.deleteFile("$ordersPath/$fileName")
    } catch (e: Exception) {
        logger.error("process order $fileName", e)
    }
}

@Synchronized
private fun createOrder(transferOrderNo: String) {
    if (collection<RobotTask>().findOne(RobotTask::outOrderNo eq transferOrderNo) != null) {
        logger.error("Duplicated order $transferOrderNo")
        return
    }

    val task = RobotTask(outOrderNo = transferOrderNo)
    val transport = RobotTransport(
        taskId = task.id,
        stages = listOf(
            RobotStage(location = "site-1", operation = "Wait"),
            RobotStage(location = "site-2", operation = "Wait")
        )
    )
    task.transports = listOf(transport)
    saveNewRobotTask(task)
}

fun onRobotTaskFinished(task: RobotTask) {
    backgroundCacheExecutor.submit {
        confirmOrCancelTask(task)
    }
}

@Synchronized
private fun confirmOrCancelTask(task: RobotTask) {
    var retries = 0
    while (retries < 5) {
        try {
            if (task.state == RobotTaskState.Success) {
                confirmOrder(task.outOrderNo!!)
            } else {
                confirmCancel(task.outOrderNo!!)
            }
            return
        } catch (e: Exception) {
            logger.error("confirm order", e)
        }
        Thread.sleep(2000)
        retries++
    }
}

private fun confirmOrder(transferOrderNo: String) {
    try {
        val xmlStr = """<?xml version="1.0" encoding="UTF-8"?>
               <WMTCID01>
                  <IDOC BEGIN="1">
                     <EDI_DC40 SEGMENT="1">
                        <TABNAM>EDI_DC40</TABNAM>
                        <MANDT>123</MANDT>
                        <DOCREL>750</DOCREL>
                        <STATUS>03</STATUS>
                        <DIRECT>2</DIRECT>
                        <OUTMOD> </OUTMOD>
                        <IDOCTYP>WMTCID01</IDOCTYP>
                        <MESTYP>WMTOCO</MESTYP>
                        <SNDPOR>SAPP11_AEX</SNDPOR>
                        <SNDPRT>LS</SNDPRT>
                        <SNDPRN>AGV</SNDPRN>
                        <RCVPOR>SAPQ20</RCVPOR>
                        <RCVPRT>LS</RCVPRT>
                        <RCVPRN>Q20LOG123</RCVPRN>
                        <CREDAT>20200218</CREDAT>
                        <CRETIM>125740</CRETIM>
                        <SERIAL>20200218125740</SERIAL>
                     </EDI_DC40>
                     <E1LTCOH SEGMENT="1">
                        <LGNUM>055</LGNUM>
                        <TANUM>$transferOrderNo</TANUM>
                        <QNAME>Seer</QNAME>
                        <SQUIT/>
                        <E1LTCOI SEGMENT="1">
                           <TAPOS>0001</TAPOS>
                           <SQUIT>X</SQUIT>
                        </E1LTCOI>
                     </E1LTCOH>
                  </IDOC>
               </WMTCID01>
            """.trimIndent()

        val filename = "WMTOCO_" + DateFormatUtils.format(System.currentTimeMillis(), "yyyyMMddHHmmssSSS") + ".xml"
        inFtp.changeWorkingDirectory("input/BGH0SFPMXA")
        inFtp.storeFile(filename, xmlStr)
    } catch (e: Exception) {
        logger.error("Failed to confirm, order no=$transferOrderNo")
        throw e
    }
}

private fun confirmCancel(transferOrderNo: String) {
    try {
        val xmlStr = """<?xml version="1.0" encoding="UTF-8"?>
               <WMCAID01>
              <IDOC>
                <EDI_DC40>
                  <TABNAM>EDI_DC40</TABNAM>
                  <MANDT>123</MANDT>
                  <DOCREL>750</DOCREL>
                  <STATUS>03</STATUS>
                  <DIRECT>2</DIRECT>
                  <OUTMOD> </OUTMOD>
                 <IDOCTYP>WMCAID01</IDOCTYP>
                  <MESTYP>WMCATO</MESTYP>
                  <SNDPOR>SAPP11_AEX</SNDPOR>
                  <SNDPRT>LS</SNDPRT>
                  <SNDPRN>AGV</SNDPRN>
                  <RCVPOR>SAPQ20</RCVPOR>
                  <RCVPRT>LS</RCVPRT>
                  <RCVPRN>Q20LOG123</RCVPRN>
                  <CREDAT>20200218</CREDAT>
                  <CRETIM>125740</CRETIM>
                  <SERIAL>20200218125740</SERIAL>
                </EDI_DC40>
                <E1LTCAH>
                  <LGNUM>055</LGNUM>
                  <TANUM>$transferOrderNo</TANUM>
                  <CNAME>Seer</CNAME>
                  <CANCL>X</CANCL> 
                  <E1LTCAI>
                    <TAPOS>0001</TAPOS>
                  </E1LTCAI>
                </E1LTCAH>
              </IDOC>
            </WMCAID01>
            """.trimIndent()

        val filename =
            "WMCATO_Cancel_" + DateFormatUtils.format(System.currentTimeMillis(), "yyyyMMddHHmmssSSS") + ".xml"
        inFtp.changeWorkingDirectory("input/BGH0SFPMXA")
        inFtp.storeFile(filename, xmlStr)
    } catch (e: Exception) {
        logger.error("Failed to confirm, order no=$transferOrderNo")
        throw e
    }
}