package com.seer.srd.pager

import com.seer.srd.BusinessError
import com.seer.srd.io.tcp.ByteArrayBuffer
import com.seer.srd.pager.PagerTcpServer.pagerToSocketMap
import com.seer.srd.util.splitTrim
import org.slf4j.LoggerFactory
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PagerTcpProcessor(var socket: Socket) {

  private val logger = LoggerFactory.getLogger(PagerTcpProcessor::class.java)

  // LoRa集中器(USR-LG220)的ID
  private var concentratorId: String? = null

  private val tcpPkgHead = byteArrayOf(
      Integer.parseInt(CUSTOM_CONFIG.version, 16).toByte(),
      Integer.parseInt(CUSTOM_CONFIG.command, 16).toByte())
  private val tcpPkgTail = byteArrayOf(Integer.parseInt(CUSTOM_CONFIG.frameTail, 16).toByte())

  private val pkgExtractor =
      PagerPkgExtractor(this.tcpPkgHead, this.tcpPkgTail, this::onPkg)
  private val inputStreamToPkg = PagerInputStreamToPkg(socket.getInputStream(), 1024, this.pkgExtractor, this::onError)

  init {
    logger.debug("init tcp server")
    inputStreamToPkg.start()
  }

  fun dispose() {
    inputStreamToPkg.stop()
    socket.close()
  }

  private fun onPkg(buffer: ByteArrayBuffer, len: Int) {
    val buf = ByteBuffer.wrap(
        buffer.buffer,
        buffer.validStartIndex + tcpPkgHead.size,
        len - tcpPkgHead.size - tcpPkgTail.size
    ).order(ByteOrder.BIG_ENDIAN)

    concentratorId = Integer.toHexString(buf.int).padStart(8, '0')
    pagerToSocketMap[concentratorId as String] = this

    var nodeId = Integer.toHexString(buf.int)
    if (nodeId.isNullOrBlank()) return
    nodeId = nodeId.padStart(8, '0')
    val shortId = buf.short.toInt()
    val channel = buf.get().toInt()
    val snr = buf.get().toInt()
    val rssi0 = buf.get().toInt()
    val rssi1 = buf.get().toInt()
    val nc1 = buf.get().toInt()
    val nc2 = buf.get().toInt()
    val timeStamp = buf.int
    val online  = buf.get().toInt()
    val inNet  = buf.short.toInt()
    val dataLength = buf.short.toInt()

    // 有效数据
    val frameHead = buf.get().toInt()
    val frameId = buf.get().toInt()
    val functionCode = buf.get().toInt()
    val fromAddr = buf.get().toInt()
    val registerNum = buf.get().toInt()

    val mode = checkMode(fromAddr, registerNum)
    if (mode != "呼叫") return
    logger.debug("呼叫模式启动, concentratorId: $concentratorId, nodeId: $nodeId...")

    val registerValue = buf.get().toInt()         // 这里只写了呼叫模式的一个字节
    val checkSum = buf.short.toInt()

//    val aa = buf.array()

    val data = PagerData(
        frameId = Integer.toHexString(frameId),
        functionCode = Integer.toHexString(functionCode),
        fromAddr = Integer.toHexString(fromAddr),
        registerNum = Integer.toHexString(registerNum),
        registerValue = Integer.toHexString(registerValue),
        check = Integer.toHexString(checkSum)
    )

//    val uploadData =  Upload(
////        concentratorId = Integer.toHexString(concentratorId),
//        nodeId = Integer.toHexString(nodeId),
//        shortId = Integer.toHexString(shortId),
//        channel = Integer.toHexString(channel),
//        snr = Integer.toHexString(snr),
//        rssi0 = Integer.toHexString(rssi0),
//        rssi1 = Integer.toHexString(rssi1),
//        nc1 = Integer.toHexString(nc1),
//        nc2 = Integer.toHexString(nc2),
//        timeStamp = Integer.toHexString(timeStamp),
//        online = Integer.toHexString(online),
//        inNet = Integer.toHexString(inNet),
//        dataLength = Integer.toHexString(dataLength),
//        data = data)

//    val a =  Integer.parseInt("ca", 16

    val taskId = PagerHelper.createTask(concentratorId + nodeId)
    if (taskId.isNullOrBlank()) {
      logger.debug("有正在处理的呼叫任务，node: $nodeId")
      return
    }

    var frameIdStr = Integer.toHexString(frameId)
    if (frameIdStr.length > 2) frameIdStr = frameIdStr.substring(frameIdStr.length - 2, frameIdStr.length)

    var checkStr = Integer.toHexString(frameId + 14)
    if (checkStr.length > 2) checkStr = Integer.toHexString(frameId + 14).substring(checkStr.length - 2, checkStr.length)

    val downloadData = Download(
        dataLength = Integer.toHexString(9),
        taskId = taskId,
        data = PagerData(
        frameId = frameIdStr,
        functionCode = Integer.toHexString(1),
        fromAddr = Integer.toHexString(2),
        registerNum = Integer.toHexString(1),
        registerValue = Integer.toHexString(10),
        check = checkStr
      )
    )

    PagerHelper.calledDataMap[concentratorId + nodeId] = downloadData




//    logger.debug("拿到数据：$str")
//    val siteToMagArr = splitTrim(str, "-")
//    if (siteToMagArr.isNullOrEmpty()) logger.error("处理ur数据发生错误，$siteToMagArr", null)
//    val siteId = siteToMagArr[0]
//    val magId = siteToMagArr[1]
//    UrTcpServer.siteToMagMap[siteId] = magId
  }

  private fun onError(e: Exception) {
    logger.error("pager tcp processor error", e)
  }

  fun write(bytes: ByteArray, flush: Boolean = false) {
    val processor = pagerToSocketMap[concentratorId] ?: return
    val os = processor.socket.getOutputStream()
    os.write(bytes)
    if (flush) os.flush()
  }

  private fun checkMode(fromAddr: Int, registerNum: Int): String {
    if (fromAddr != 4 && registerNum != 1) {
      logger.debug("不是呼叫模式，寄存器起始地址: $fromAddr，寄存器数量：$registerNum，丢弃数据。")
      return ""
    }
    return "呼叫"
  }
}