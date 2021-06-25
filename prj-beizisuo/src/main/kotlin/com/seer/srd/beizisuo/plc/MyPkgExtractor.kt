package com.seer.srd.beizisuo.plc

import com.seer.srd.io.tcp.ByteArrayBuffer
import org.slf4j.LoggerFactory
import java.util.*

class MyPkgExtractor(
    private val head: ByteArray,
    private val parsePkgLen: (buffer: ByteArrayBuffer) -> Int,
    private val onPkg: (buffer: ByteArrayBuffer, pkgLen: Int) -> Unit
) {

  private val logger = LoggerFactory.getLogger(MyPkgExtractor::class.java)

  private var headStartIndex =  -1
  private var pkgLen = -1

//  init {
//    pkgLen = CUSTOM_CONFIG.pkgLen
//  }
  fun onData(buffer: ByteArrayBuffer) {

    val rawBuffer = ByteArray(buffer.validEndIndex - buffer.validStartIndex)
    System.arraycopy(
        buffer.buffer,
        buffer.validStartIndex,
        rawBuffer,
        0,
        buffer.validEndIndex - buffer.validStartIndex)

    var str = ""
    for (index in 0 until rawBuffer.size) {
      str += rawBuffer[index].toString() + " "
    }

    logger.debug("got data raw: $str")

    if (headStartIndex < 0) headStartIndex = searchPkgHead(buffer)
    if (headStartIndex < 0) return

    // 再读包的长度

    if (pkgLen < 0) pkgLen = parsePkgLen(buffer)
    if (pkgLen < 0) return // 没有拿到长度

    //包完整
    try {
      onPkg(buffer, pkgLen)
    } catch (e: Exception) {
      logger.error("on pkg", e)
    }

    // 下一轮
    val remainingStart = headStartIndex + pkgLen
    val remainingLength = buffer.validEndIndex - remainingStart
    // 用 arraycopy 拷贝同一个数组仍然用到临时数组，不过觉得仍然比循环快
    System.arraycopy(buffer.buffer, remainingStart, buffer.buffer, 0, remainingLength)
    buffer.validStartIndex = 0
    buffer.validEndIndex = remainingLength

    headStartIndex = -1
    pkgLen = -1
  }

  private fun searchPkgHead(buffer: ByteArrayBuffer): Int {
    if (buffer.validEndIndex - buffer.validStartIndex < head.size) return -1

    for (i in buffer.validStartIndex..buffer.validEndIndex - head.size) {
      if (Arrays.equals(head, 0, head.size, buffer.buffer, i, i + head.size)) {
        return i
      }
    }
    // 丢弃前部数据
    buffer.validStartIndex = buffer.validEndIndex
    return -1
  }

}