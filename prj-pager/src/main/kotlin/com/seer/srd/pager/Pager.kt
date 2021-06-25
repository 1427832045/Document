package com.seer.srd.pager

import com.seer.srd.Application
import com.seer.srd.BusinessError
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.setVersion
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
object App {

    private val logger = LoggerFactory.getLogger(App::class.java)

    private val extraComponents: List<TaskComponentDef> = listOf(
        TaskComponentDef(
            "extra", "off", "熄灭", "", false, listOf(
            TaskComponentParam("cId", "集中器Id", "string"),
            TaskComponentParam("nId", "节点Id", "string")
        ), false) { component, ctx ->
            val concentratorId = parseComponentParamValue("cId", component, ctx) as String
            val nodeId = parseComponentParamValue("nId", component, ctx) as String
            val socket = PagerTcpServer.pagerToSocketMap[concentratorId] ?: throw BusinessError("no such concentrator id: $concentratorId")

            val data = PagerHelper.calledDataMap[concentratorId + nodeId] ?: throw BusinessError("no such download data cid: $concentratorId, nid: $nodeId")
            val cId = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(Integer.parseInt(concentratorId, 16)).array()
            val nId = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(Integer.parseInt(nodeId, 16)).array()
            val len = byteArrayOf(Integer.parseInt("0c", 16).toByte())
            val head = byteArrayOf(Integer.parseInt(data.data.frameHead, 16).toByte())
            val frameIdNum = Integer.parseInt(data.data.frameId, 16).toByte()
            val frameId = byteArrayOf(frameIdNum)
            val functionCodeNum = Integer.parseInt(data.data.functionCode, 16).toByte()
            val functionCode = byteArrayOf(functionCodeNum)
            val fromAddrNum = Integer.parseInt("00", 16).toByte()
            val fromAddr = byteArrayOf(fromAddrNum)
            val registerNum = Integer.parseInt("04", 16).toByte()
            val registerByte = byteArrayOf(registerNum)
            val registerValueNum = Integer.parseInt("00000000", 16)
            val registerValue = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(registerValueNum).array()
            val check = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((
                frameIdNum + functionCodeNum + fromAddrNum + registerNum + registerValueNum).toShort())
                .array()
            if (check[0].toInt() == -1) check[0] = 0
            val tail = byteArrayOf(Integer.parseInt(data.data.frameTail, 16).toByte())
            logger.debug("将呼叫灯熄灭")
            socket.write(cId + nId + len + head + frameId + functionCode + fromAddr + registerByte + registerValue + check + tail, true)
//            PagerHelper.calledDataMap[concentratorId + nodeId] = PagerHelper.calledDataMap[concentratorId + nodeId]!!.copy(taskId = null)
        }
    )

    fun init() {
        registerRobotTaskComponents(extraComponents)
    }
}


fun main() {
    App.init()
    setVersion("l2", "3.0.2")

    Application.initialize()

    PagerTcpServer.init()
    PagerHelper.init()
    Application.start()

//    Runtime.getRuntime().addShutdownHook(Thread {
//        PagerHelper.dispose()
//        PagerTcpServer.dispose()
//    })

}


