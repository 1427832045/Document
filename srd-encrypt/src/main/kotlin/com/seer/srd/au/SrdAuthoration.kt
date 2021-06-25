package com.seer.srd.au

import org.apache.commons.codec.binary.Base64
import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import java.io.*
import java.nio.charset.StandardCharsets
import java.time.Instant

private val logger = LoggerFactory.getLogger("com.seer.srd")

class AccessControlList(
    var vehicleNum: Int = -1,
    var expiration: Long = -1
) {
    fun log() {
        logger.info("Expiration: " + (if (expiration > 0) Instant.ofEpochMilli(expiration) else "NEVER"))
    }
}

private const val secret = "59dfa5c6495bae95883d3d774d413443413141514d413443417741514d414547141774741684241644" +
        "14d4641504277594145474174425158415547417941674f4417542516149474135416"

fun generateAuFile(client: String, acl: AccessControlList, fingerPrint: String) {
    val aclStr = aclToString(acl)
    val signature = sign(aclStr + fingerPrint + secret)
    val content = client + "\n" + aclStr + "\n" + signature
    FileUtils.write(File("srd.gp"), content, StandardCharsets.UTF_8)
}

fun parseAuFile(file: File, fp: String): AccessControlList {
    val lines = FileUtils.readFileToString(file, StandardCharsets.UTF_8).split("\n")
    val signature1 = lines[2]
    val byteArray = Base64.decodeBase64(lines[1])
    val acl = DataInputStream(ByteArrayInputStream(byteArray)).use {
        AccessControlList(
            it.readInt(),
            it.readLong()
        )
    }
    val aclStr = aclToString(acl)
    val signature2 = sign(aclStr + fp + secret)
    logger.info("srd.gp file includes signature1 is "
            + signature1.toString())
    logger.info("srd-k system creates signature2 is "
            + signature2.toString())
    if (signature1 != signature2) throw IllegalArgumentException("NE")
    return acl
}

private fun sign(str: String): String {
    return Hex.encodeHexString(DigestUtils.getSha256Digest().digest(str.toByteArray(StandardCharsets.ISO_8859_1)))
}

private fun aclToString(acl: AccessControlList): String {
    val byteStream = ByteArrayOutputStream(10000)
    DataOutputStream(byteStream).use {
        it.writeInt(acl.vehicleNum)
        it.writeLong(acl.expiration)
        it.flush()
    }
    val byteArray = byteStream.toByteArray()
    return Base64.encodeBase64String(byteArray)
}