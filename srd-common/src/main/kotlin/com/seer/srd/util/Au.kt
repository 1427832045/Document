package com.seer.srd.util

import com.seer.srd.au.AccessControlList
import com.seer.srd.au.ME
import com.seer.srd.au.ME2
import com.seer.srd.au.parseAuFile
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

val ACL = au()

private fun au(): AccessControlList {
    val logger = LoggerFactory.getLogger("com.seer.srd")
    logger.info("AU start")
    val fp = ME2
    try {
        val file = searchFileFromWorkDirUp("srd.gp") ?: throw IllegalStateException("NO")
        return parseAuFile(file, fp)
    } catch (e: Exception) {
        logger.error("Illegal: ${e.message}")
        logger.error("Your machine ID is:\n$fp")
        logger.error(ME)
        exitProcess(-1)
    }
}