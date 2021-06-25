package com.seer.srd.au

import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.LoggerFactory
import oshi.SystemInfo
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.*

val ME = me()
val ME2 = me2()

fun getHWInfo(): String {
        var cpuinfo: List<String> = emptyList()
        var csUUID: List<String> = emptyList()
        var diskinfo: List<String> = emptyList()
        val sb1 = StringBuilder()
        val logger = LoggerFactory.getLogger("com.seer.srd.au")
        val os = System.getProperty("os.name")
        logger.debug("os is : $os")
        if (os != null) {
            if (os.startsWith("Windows")) try {
                val cpu = execComm("wmic cpu get ProcessorId")
                val lines = cpu.readLines()
                for (line in lines) {
                    if (line.isNotEmpty()) cpuinfo = cpuinfo.plus(line)
                }
                if (cpuinfo.isNotEmpty()) sb1.appendln("CPU ProcessorId is : ").appendln(cpuinfo[1])

                val uuid = execComm("wmic csproduct get UUID")
                val lines1 = uuid.readLines()
                for (item in lines1) {
                    if (item.isNotEmpty()) csUUID = csUUID.plus(item)
                }
                if (csUUID.isNotEmpty()) sb1.appendln("csUUID is : ").appendln(csUUID[1])

                val disk = execComm("wmic diskdrive get serialnumber")
                val lines2 = disk.readLines()
                for (item in lines2) {
                    if (item.isNotEmpty()) diskinfo = diskinfo.plus(item)
                }
                if (diskinfo.isNotEmpty()) sb1.appendln("disk serial number is : ").appendln(diskinfo[1])

                return sb1.toString()
            } catch (ex: IOException) {
                logger.error(ex.toString())
            } else if(os.startsWith("Linux")) {
                // TODO
                return ""
            }
        }
        return ""
    }

private fun execComm(command: String): BufferedReader {
    val rt = Runtime.getRuntime().exec(command).inputStream
    val br = BufferedReader(InputStreamReader(rt))
    return br
}

private fun me2(): String {
    val a = Base64.getEncoder().encode(ME.toByteArray(StandardCharsets.UTF_16))
    a.reverse()
    val b = DigestUtils.getSha256Digest().digest(a)
    val c = Hex.encodeHexString(b)
    val d = Hex.encodeHexString(a)
    return c + d
}

private fun me(): String {
    val sb = StringBuilder()
    sb.appendln()
    val si = SystemInfo()

    val hardware = si.hardware

    val computerSystem = hardware.computerSystem
    sb.appendln(computerSystem.manufacturer)
    sb.appendln(computerSystem.model)
    sb.appendln(computerSystem.serialNumber)

    val baseboard = computerSystem.baseboard
    sb.appendln(baseboard.manufacturer ?: "")
    sb.appendln(baseboard.model ?: "")
    sb.appendln(baseboard.serialNumber ?: "")
    sb.appendln(baseboard.version ?: "")

    val firmware = computerSystem.firmware
    sb.appendln(firmware.name)
    sb.appendln(firmware.manufacturer)
    sb.appendln(firmware.version)

    val processor = hardware.processor
    sb.appendln(processor.logicalProcessorCount)
    sb.appendln(processor.physicalProcessorCount)

    val nets = hardware.networkIFs.filter {
        val ni = it.queryNetworkInterface()
        !ni.isLoopback && !ni.isVirtual
    }.map { it.name + ":" + Hex.encodeHexString(it.queryNetworkInterface().hardwareAddress) }
    sb.appendln(nets.sorted().joinToString(","))

    val os = si.operatingSystem
    sb.appendln(os.family)
    sb.appendln(os.manufacturer)

    val vi = os.versionInfo
    sb.appendln(vi.buildNumber)
    sb.appendln(vi.codeName)
    sb.appendln(vi.version)

    val hw = getHWInfo()
    sb.appendln(hw)

    return sb.toString()
}