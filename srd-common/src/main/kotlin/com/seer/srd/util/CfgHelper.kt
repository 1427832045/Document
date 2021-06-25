package com.seer.srd.util

import com.mongodb.client.model.ReplaceOptions
import com.seer.srd.BusinessError
import com.seer.srd.SystemError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.db.VersionedFileIndex
import com.seer.srd.handler.CfgHandler.logger
import org.apache.commons.io.FileUtils
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.Exception
import java.nio.charset.Charset
import java.nio.file.*
import java.time.Instant
import java.util.*
import kotlin.system.exitProcess

const val LAST_CFG_FILENAME = "srd-config-last.yaml"
const val CFG_FILENAME = "srd-config.yaml"
//const val CFG_FILENAME_TMP = "srd-config.yaml.tmp"
const val CFG_HISTORY_DIRNAME = "historyCfg"

object CfgHelper {

  @Volatile
  var enabled = false

  fun enableCfg() {
    if (enabled) return
    enabled = true
    val cfgDir = File(System.getProperty("user.dir"), CFG_HISTORY_DIRNAME)
    if (!cfgDir.exists()) cfgDir.mkdirs()

    var curCfgFile = File(System.getProperty("user.dir"), CFG_FILENAME)
    while (!curCfgFile.exists() && curCfgFile.parentFile.parentFile.exists()) {
      logger.debug("search $CFG_FILENAME from ${curCfgFile.parentFile.parentFile.path}")
      curCfgFile = File(curCfgFile.parentFile.parentFile, CFG_FILENAME)
    }
//    val tempFile = File(System.getProperty("user.dir"), CFG_FILENAME_TMP)
//    if (tempFile.exists()) {
//      try {
//        logger.debug("save temp file as srd-config.yaml")
//        Files.copy(tempFile.toPath(), curCfgFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
//        logger.debug("save temp file as srd-config.yaml successfully, delete temp file")
//        tempFile.delete()
//        logger.debug("delete temp file successfully")
//      } catch (e: Exception) {
//        logger.error("temp file error", e)
//      }
//    }
    val lastCfgFile = File(cfgDir, LAST_CFG_FILENAME)

    if (!lastCfgFile.exists()) {
      logger.debug("search srd-config-last.yaml failed")
      // srd-config-last.yaml不存在
      if (!curCfgFile.exists()) {
        logger.error("search srd-config.yaml failed, try to exit process...")
        exitProcess(0)
      } else {
        try {
          logger.debug("try to load srd-config.yaml...")
          val curDataString = String(FileUtils.readFileToByteArray(curCfgFile), Charset.forName("UTF-8"))
          Yaml().load<Any>(curDataString)
          // 写到last文件
          logger.debug("load srd-config.yaml successfully, try to save srd-config.yaml as srd-config-last.yaml...")
          Files.copy(curCfgFile.toPath(), lastCfgFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
//        yaml.dump(obj, FileWriter(lastCfgFile))
          logger.debug("save srd-config.yaml as srd-config-last.yaml successfully")
        } catch (e: Exception) {
          logger.error("load srd-config.yaml error, exit process", e)
          exitProcess(0)
        }
      }
    } else {
      // srd-config-last.yaml存在
      logger.debug("search srd-config-last.yaml successfully")
      val lastDataString: String
      try {
        logger.debug("try to load srd-config-last.yaml ...")
        lastDataString = String(FileUtils.readFileToByteArray(lastCfgFile), Charset.forName("UTF-8"))
        Yaml().load<Any>(lastDataString)
      } catch (e: Exception) {
        logger.error("attention!! load srd-config-last.yaml error, search srd-config.yaml...", e)
        if (!curCfgFile.exists()) {
          logger.debug("srd-config.yaml does not exists, exit process...")
          exitProcess(0)
        } else {
          try {
            logger.debug("search srd-config.yaml successfully, try yo load...")
            val curDataString = String(FileUtils.readFileToByteArray(curCfgFile), Charset.forName("UTF-8"))
            Yaml().load<Any>(curDataString)
          } catch (e: Exception) {
            // 加载srd-config.yaml异常，退出
            logger.error("load srd-config.yaml error, exit process", e)
            exitProcess(0)
          }
          // 加载srd-config.yaml成功，写到last文件
          logger.debug("load srd-config.yaml successfully, try to save config as srd-config-last.yaml...")
          Files.copy(curCfgFile.toPath(), lastCfgFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
//        yaml.dump(obj, FileWriter(lastCfgFile))
          logger.debug("save config as srd-config-last.yaml successfully")
          return
        }
      }
      // 加载srd-config-last.yaml正常, srd-config-last.yaml == srd-config.yaml ?
      logger.debug("load srd-config-last.yaml successfully, try to search srd-config.yaml...")
      if (!curCfgFile.exists()) {
        logger.debug("srd-config.yaml does not exists, save srd-config-last.yaml as srd-config.yaml")
//      yaml.dump(obj, FileWriter(curCfgFile))    // 使用文件工具生成,yaml生成会改变格式
        Files.copy(lastCfgFile.toPath(), curCfgFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger.debug("save srd-config-last.yaml as srd-config.yaml successfully")
        return
      }
      logger.debug("search srd-config.yaml successfully")
      val curDataString = String(FileUtils.readFileToByteArray(curCfgFile), Charset.forName("UTF-8"))
      if (curDataString == lastDataString) {
        logger.debug("srd-config-last.yaml == srd-config.yaml")
        return
      } else {    // curDataString != lastDataString
        logger.debug("srd-config-last.yaml != srd-config.yaml, try to load srd-config.yaml...")
        try {
          Yaml().load<Any>(curDataString)
        } catch (e: Exception) {
          logger.error("attention!! load srd-config.yaml error", e)
          logger.debug("try to save srd-config-last.yaml as srd-config.yaml")
          Files.copy(lastCfgFile.toPath(), curCfgFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
          logger.debug("save srd-config-last.yaml as srd-config.yaml successfully")
          return
        }
        logger.debug("load srd-config.yaml successfully")
        logger.debug("try to save srd-config-last.yaml as a timestamp yaml file...")
        val time = Instant.now().toEpochMilli()
        Files.copy(lastCfgFile.toPath(), File(cfgDir, "srd-config-$time.yaml").toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger.debug("save srd-config-last.yaml as srd-config-$time.yaml successfully, try to save srd-config.yaml as srd-config-last.yaml...")
        Files.copy(curCfgFile.toPath(), lastCfgFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger.debug("save srd-config.yaml as srd-config-last.yaml successfully")

      }
    }
  }
}

fun listCfgFileNames(): List<String>{
  val cfgDir = File(System.getProperty("user.dir"), CFG_HISTORY_DIRNAME)
  var curFile = File(System.getProperty("user.dir"), CFG_FILENAME)
  while (!curFile.exists() && curFile.parentFile.parentFile.exists()) {
    curFile = File(curFile.parentFile.parentFile.path, CFG_FILENAME)
  }
  return if (cfgDir.exists()) {
    logger.debug("list cfg files: ${cfgDir.listFiles().map { it.name }}")
    mutableListOf(curFile.name) + cfgDir.listFiles().sortedByDescending { it.name }.map { it.name }
  } else emptyList()
}

fun loadCfgFileByName(name: String): String {
  try {
    val cfgDir =
        if (Objects.equals(name, CFG_FILENAME)) File(System.getProperty("user.dir"))
        else File(System.getProperty("user.dir"), CFG_HISTORY_DIRNAME)
    var cfgFile = File(cfgDir, name)
    if (Objects.equals(name, CFG_FILENAME)) {
      while (!cfgFile.exists() && cfgFile.parentFile.parentFile.exists()) {
        cfgFile = File(cfgFile.parentFile.parentFile.path, name)
      }
    }

    return String(FileUtils.readFileToByteArray(cfgFile), Charset.forName("UTF-8"))
  } catch (e: Exception) {
    logger.error("load file $name error", e)
    throw BusinessError("load file $name error")
  }
}