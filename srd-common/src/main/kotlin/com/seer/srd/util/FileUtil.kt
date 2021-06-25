package com.seer.srd.util

import org.slf4j.LoggerFactory
import java.io.File

private val LOG = LoggerFactory.getLogger("com.seer.srd")

fun searchFileFromWorkDirUp(filename: String): File? {
    var dir: File? = File(System.getProperty("user.dir"))
    LOG.info("Search file $filename up from $dir")

    while (dir != null) {
        //LOG.debug("Search config file $configFilename in $dir")
        val file = File(dir, filename)
        if (file.exists()) return file
        dir = file.parentFile?.parentFile
    }
    return null
}

fun searchDirectoryFromWorkDirUp(dirName: String): File? {
    var dir: File? = File(System.getProperty("user.dir"))
    LOG.info("Search directory $dirName up from $dir")

    while (dir != null) {
        //LOG.debug("Search config file $configFilename in $dir")
        val file = File(dir, dirName)
        if (file.exists() && file.isDirectory) return file
        dir = file.parentFile?.parentFile
    }
    return null
}