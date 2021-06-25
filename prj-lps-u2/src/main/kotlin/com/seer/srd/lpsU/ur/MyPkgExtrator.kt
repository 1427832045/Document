package com.seer.srd.lpsU.ur

import com.seer.srd.io.tcp.ByteArrayBuffer
import org.slf4j.LoggerFactory
import java.util.*

class MyPkgExtractor(
    private val head: ByteArray,
    private val tail: ByteArray,
//    private val parsePkgLen: (buffer: ByteArrayBuffer) -> Int,
    private val onPkg: (buffer: ByteArrayBuffer, pkgLen: Int) -> Unit
) {

  private val logger = LoggerFactory.getLogger(MyPkgExtractor::class.java)

  private var headStartIndex = -1
  private var tailEndIndex = -1
  private var pkgLen = -1

  fun onData(buffer: ByteArrayBuffer) {
    // 先找包头
    if (headStartIndex < 0) headStartIndex = searchPkgHead(buffer)
    if (headStartIndex < 0) return

    // 再找包尾, 有了包尾就有数据了
    if (tailEndIndex < 0) tailEndIndex = searchPkgTail(buffer)
    if (tailEndIndex < 0) return

    if (pkgLen < 0) pkgLen = tailEndIndex
    // 再读包的长度
//    if (pkgLen < 0) pkgLen = parsePkgLen(buffer, tailStartIndex)
//    if (pkgLen < 0) return // 没有拿到长度

//    if (buffer.validEndIndex - buffer.validStartIndex < pkgLen) return // 长度不够

    // 包完整了
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
    tailEndIndex = -1
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
    buffer.validStartIndex = buffer.validEndIndex - head.size
    return -1
  }

  private fun searchPkgTail(buffer: ByteArrayBuffer): Int {
    // 头长度不够
    if (buffer.validEndIndex - buffer.validStartIndex < head.size) {
      logger.debug("数据为：${String(buffer.buffer)}长度不够, 继续接收数据...")
      return -1
    }
    // 头+尾长度不够
    if (buffer.validEndIndex - buffer.validStartIndex - head.size < tail.size) {
      logger.debug("数据为：${String(buffer.buffer)}长度不够, 继续接收数据...")
      return -1
    }

    for (i in head.size..buffer.validEndIndex) {
      if (Arrays.equals(tail, 0, tail.size, buffer.buffer, i, i + tail.size)) {
        return i + tail.size
      }
    }
    // 还没到达尾部
    logger.debug("还没收到尾部,继续接收数据...")
    return -1
  }
}