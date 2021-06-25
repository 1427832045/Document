package com.seer.srd.handler

import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import com.seer.srd.Error400
import io.javalin.http.Context
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.io.*
import java.lang.Exception
import java.nio.charset.Charset
import java.nio.file.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.collections.HashMap

object FileHandler {
    
    private const val uploadTmpDirName = "upload-tmp"

    private const val srdLogDirName = "logs"

    private const val dataDirName = "data"

    private const val customTaskDirName = "robot-tasks"

    private val logger = LoggerFactory.getLogger(FileHandler::class.java)

    private var downloading: MutableMap<String, Boolean> = ConcurrentHashMap()

    fun handleUploadTmp(ctx: Context) {
        val file = ctx.uploadedFile("file") ?: throw Error400("NoFile", "No File")
        val newFilename = ObjectId().toHexString() + file.extension
        val dir = getUploadTmpDir()
        val toFile = File(dir, newFilename)
        FileOutputStream(toFile).use { os ->
            IOUtils.copy(file.content, os)
            os.flush()
        }
        ctx.json(mapOf("path" to "$uploadTmpDirName/$newFilename"))
    }
    
    fun handleDownloadTmp(ctx: Context) {
        val fileName = ctx.pathParam("file")
        val dir = getUploadTmpDir()
        val file = File(dir, fileName)
        if (!file.exists()) {
            ctx.status(404)
        } else {
            val fileSize = file.length()
            ctx.header("Content-Length", fileSize.toString())
            val contentType = Files.probeContentType(Path.of(file.absolutePath))
            ctx.header("Content-Type", contentType)
            IOUtils.copy(FileInputStream(file), ctx.res.outputStream)
        }
    }

    fun handleDownloadCustomTaskFiles(ctx: Context) {
        val fileName = ctx.queryParam("file") ?: throw BusinessError("file not found")
        val dataDir = File(System.getProperty("user.dir"), dataDirName)
        if (!dataDir.exists()) throw BusinessError("file not found")
        val taskDir = File(dataDir, customTaskDirName)
        if (!taskDir.exists()) throw BusinessError("file not found")
        val str = String(FileUtils.readFileToByteArray(File(taskDir, fileName)), Charset.forName("UTF-8"))
        ctx.result(str)
        ctx.contentType("text/plain")
    }

    fun handleDownloadSrdLogFiles(ctx: Context) {
        val dirName = ctx.queryParam("dir") ?: throw BusinessError("dir not found")
        val fileName = ctx.queryParam("file") ?: throw BusinessError("file not found")
        val dir =  getLogDir(dirName)
        val file = File(dir, fileName)
        if (!file.exists()) {
            ctx.status(404)
        } else {
            if (downloading[fileName] == true) {
                ctx.header("Content-Type", "application/json")
                ctx.json(mapOf("code" to "Download Error", "message" to "$fileName downloading..."))
                return
            }
            ctx.header("Content-Type", "application/zip")
            downloading[fileName] = true

            val zipFile = File(dir, "${file.name}.zip")
            try {
                if (Objects.equals(fileName, "srd.log") || !zipFile.exists()) compressFile(file)
                IOUtils.copy(ByteArrayInputStream(FileUtils.readFileToByteArray(zipFile)), ctx.res.outputStream)
            } catch (e: Exception) {
                logger.error("download error", e)
                throw BusinessError("download error, ${e.message}")
            } finally {
                downloading[fileName] = false
            }
        }
    }

    // 下载源文件
    fun handleDownloadSrdLogFilesSrc(ctx: Context) {
        val dirName = ctx.queryParam("dir") ?: throw BusinessError("dir not found")
        val fileName = ctx.queryParam("file") ?: throw BusinessError("file not found")
        val dir =  getLogDir(dirName)
        val file = File(dir, fileName)
        if (!file.exists()) {
            ctx.status(404)
        } else {
            val fileSize = file.length()
            ctx.header("Content-Length", fileSize.toString())
            ctx.header("Content-Type", "application/octet-stream")

            IOUtils.copy(FileInputStream(file), ctx.res.outputStream)
        }
    }

    fun handleCustomTaskDir(ctx: Context) {
        ctx.json(listOf(dataDirName, customTaskDirName))
    }

    fun handleCustomTaskFiles(ctx: Context) {
        val dataDir = File(System.getProperty("user.dir"), dataDirName)
        if (!dataDir.exists()) ctx.json(emptyList<HashMap<String, String>>())
        else {
            val taskDir = File(dataDir, customTaskDirName)
            if (!taskDir.exists()) ctx.json(emptyList<HashMap<String, String>>())
            else {
                val fileMTimes =
                    taskDir.listFiles().filter { it.name.matches(Regex("^(robot-tasks-)\\d{17,}(.ss)$")) }
                        .sortedByDescending { it.name }
                        .map {
                            val timeString = it.name.substring(12, 29)
                            val year = timeString.substring(0, 4)
                            val month = timeString.substring(4, 6)
                            val day = timeString.substring(6, 8)
                            val hour = timeString.substring(8, 10)
                            val min = timeString.substring(10, 12)
                            val sec = timeString.substring(12, 14)
                            val milli = timeString.substring(14, 17)
                                mapOf(
                                    "file" to it.name,
                                    "mtime" to "$year-$month-$day $hour:$min:$sec.$milli"
                                )
                        }
                ctx.json(fileMTimes)
            }
        }
    }

    fun handleSrdLogDir(ctx: Context) {
        ctx.json(listOf(srdLogDirName))
    }

    fun handleSrdLogFiles(ctx: Context) {
        val dir = ctx.queryParam("dir")
        val cfgDir = File(System.getProperty("user.dir"), dir)
        if (!cfgDir.exists()) cfgDir.mkdirs()

        val fileMTimes =
            cfgDir.listFiles().filter { it.name.matches(Regex("^(srd)(.\\d{4}-\\d{1,2}-\\d{1,2})?(.log)$")) }
            .sortedByDescending { it.name }
            .map {
                mapOf(
                    "file" to it.name,
                    "mtime" to if (!it.name.matches(Regex("^(srd.)(-[a-zA-Z]+)?\\d{4}-\\d{1,2}-\\d{1,2}(.log)$")))
                        SimpleDateFormat("yyyy-MM-dd").format(Calendar.getInstance().time)
                    else
                        it.name.substring(it.name.indexOf('.') + 1, it.name.lastIndexOf('.'))
                )
        }
        ctx.json(fileMTimes)
    }
    
    private fun getUploadTmpDir(): File {
        val uploadRoot = CONFIG.getFinalUploadDir()
        val dir = Paths.get(uploadRoot, uploadTmpDirName).toFile()
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getLogDir(dirName: String): File {
        val dir = File(System.getProperty("user.dir"), dirName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun copyFile(file: File, cpFileName: String) {
        try {
            Files.copy(file.toPath(), File("${file.parent}/$cpFileName").toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            logger.error("copy file error", e)
            throw BusinessError("copy file error")
        }
    }

    private fun compressFile(file: File) {
        val fos = FileOutputStream("${file.absolutePath}.zip")
        val zos = ZipOutputStream(fos)
        val fis = FileInputStream(file.absoluteFile)
        try {
            zos.putNextEntry(ZipEntry(file.name))
            val byteArray = ByteArray(1024)

            var len = fis.read(byteArray)
            while (len > 0) {
                zos.write(byteArray, 0, len)
                len = fis.read(byteArray)
            }
        } catch (e: Exception) {
            logger.error("compress file error", e)
            throw BusinessError("compress file error")
        } finally {
            fis.close()
            zos.closeEntry()
            zos.close()
            fos.close()
        }
    }
    
}