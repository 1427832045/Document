package com.seer.srd.vehicle.driver.io.tcp

import com.seer.srd.util.mapper
import com.seer.srd.vehicle.driver.io.http.MovementCommandReq
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.Instant

val tcpPkgHead = byteArrayOf(0x1, 0x2, 0x5f)

const val pkgLenWidth = 2

private val LOG = LoggerFactory.getLogger("com.seer.srd.vehicle.driver.io")

data class Message(
    val type: String,
    val flowNo: String = ObjectId().toHexString(),
    val createdOn: Instant = Instant.now(),
    val arguments: Map<String, Any> = emptyMap()
)

fun write(message: Message, writer: (bytes: ByteArray, flush: Boolean) -> Unit) {
    val content = mapper.writeValueAsString(message).toByteArray(StandardCharsets.UTF_8)
    val contentSize = content.size
    val lenPart = ByteBuffer.allocate(pkgLenWidth).order(ByteOrder.BIG_ENDIAN).putShort(contentSize.toShort()).array()
    try {
        writer(tcpPkgHead, false)
        writer(lenPart, false)
        writer(content, true)
    } catch (e: Exception) {
        LOG.error("write tcp ${e.message}")
    }
}

class MovementCmdGroup(val commands: List<MovementCommandReq>)