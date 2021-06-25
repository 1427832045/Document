package com.seer.srd.lps.ur

import com.seer.srd.io.tcp.ByteArrayBuffer
import com.seer.srd.util.splitTrim
import org.slf4j.LoggerFactory
import java.net.Socket
import java.nio.charset.StandardCharsets

class UrTcpProcessor(var socket: Socket) {

    private val logger = LoggerFactory.getLogger(UrTcpProcessor::class.java)

//    private val tcpPkgHead = byteArrayOf("head".toByte())
    private val tcpPkgHead = "head".toByteArray()
    private val tcpPkgTail = "tail".toByteArray()
    
    private val pkgExtractor =
        MyPkgExtractor(this.tcpPkgHead, this.tcpPkgTail, this::onPkg)
    private val inputStreamToPkg = MyInputStreamToPkg(socket.getInputStream(), 1024, this.pkgExtractor, this::onError)
    
    init {
        logger.debug("init tcp server")
        inputStreamToPkg.start()
    }
    
    fun dispose() {
        inputStreamToPkg.stop()
        socket.close()
    }

//  private fun parsePkgLen(buffer: ByteArrayBuffer): Int {
//    if (buffer.validEndIndex - buffer.validStartIndex < tcpPkgHead.size + pkgLenWidth) return -1
//    val byteBuffer = ByteBuffer.wrap(buffer.buffer, buffer.validStartIndex + tcpPkgHead.size, pkgLenWidth)
//    byteBuffer.order(ByteOrder.BIG_ENDIAN)
//    return byteBuffer.short.toInt() + tcpPkgHead.size + pkgLenWidth // 要包含头
//  }
    
    private fun onPkg(buffer: ByteArrayBuffer, len: Int) {
        val str = String(
            buffer.buffer,
            buffer.validStartIndex + tcpPkgHead.size,
            len - tcpPkgHead.size - tcpPkgTail.size,
            StandardCharsets.UTF_8
        )
        logger.debug("拿到数据：$str")
        val siteToMagArr = splitTrim(str, "-")
        if (siteToMagArr.isNullOrEmpty()) logger.error("处理ur数据发生错误，$siteToMagArr", null)
//        val siteId = if (siteToMagArr[0].length > 1) siteToMagArr[0] else "0" + siteToMagArr[0]
        val siteId = siteToMagArr[0]
        val magId = siteToMagArr[1]
        UrTcpServer.siteToMagMap[siteId] = magId
//        println(UrTcpServer.siteToMagMap)
    }
    
    private fun onError(e: Exception) {
        logger.error("ur tcp processor error", e)
    }
}