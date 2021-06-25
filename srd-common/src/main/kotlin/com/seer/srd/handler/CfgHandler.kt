package com.seer.srd.handler

import com.seer.srd.BusinessError
import com.seer.srd.Config
import com.seer.srd.Error400
import com.seer.srd.http.ensureRequestUserRolePermission
import com.seer.srd.util.*
import io.javalin.http.Context
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.Charset

object CfgHandler {

  val logger = LoggerFactory.getLogger("com.seer.srd.cfg")!!

  fun listCfgHistoryFiles(ctx: Context) {
    ctx.json(listCfgFileNames())
  }

  fun handleGetCfgFile(ctx: Context) {
    val config = loadConfig("srd-config.yaml", Config::class) ?: Config()
    ctx.json(config)
  }

  fun handleGetCfgByName(ctx: Context) {
    val name = ctx.pathParam("name")
    if (name.isBlank()) throw Error400("BadName", "Bad Name")
    val cfgStr = loadCfgFileByName(name)
    ctx.result(cfgStr)
  }

  fun handleDownloadFileByName(ctx: Context) {
    val name = ctx.queryParam("name") ?: ""
    if (name.isBlank()) throw BusinessError("Bad Name")
    val cfg = loadCfgFileByName(name)
    ctx.result(cfg)
    ctx.contentType("text/yaml")
  }

  fun handleChangeCfgFile(ctx: Context) {

    val urp = ensureRequestUserRolePermission(ctx)
    val req = ctx.bodyAsClass(ChangeCfgReq::class.java)
    logger.info("${urp.user.username} try to change config file!!!")
    try {
      val dataString = req.dataString
//      FileUtils.writeStringToFile(File(System.getProperty("user.dir"), CFG_FILENAME_TMP), dataString, Charset.forName("UTF-8"))
      FileUtils.writeStringToFile(File(System.getProperty("user.dir"), CFG_FILENAME), dataString, Charset.forName("UTF-8"))
    } catch (e: Exception) {
      logger.error("write file error", e)
      throw BusinessError("upload config file error")
    }
    logger.debug("${urp.user.username} uploads config file successfully!!")
  }
}

class ChangeCfgReq(
    var dataString: String = "",
    var remark: String = ""
)