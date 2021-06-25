package com.seer.srd.moshi

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.robottask.RobotTransportState
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import org.bson.types.ObjectId
import org.litote.kmongo.`in`
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.collections.set

object SiteAreaService {

  val fireTask: MutableMap<String, MutableList<String>> = ConcurrentHashMap()

  val yellowTwinkle: MutableMap<String, Boolean> = ConcurrentHashMap()

  val pass: MutableMap<String, Boolean> = ConcurrentHashMap()

  private val logger = LoggerFactory.getLogger(SiteAreaService::class.java)

  private var modbusAreas: MutableMap<String, SiteAreaModBus> = mutableMapOf()

  init {

    StoreSiteService.listStoreSites().forEach {
      val fire = MongoDBManager.collection<SiteFireTask>().findOne(
          SiteFireTask::siteId eq it.id
      )
      if (fire == null) {
        MongoDBManager.collection<SiteFireTask>().insertOne(SiteFireTask(ObjectId(), it.id, mutableListOf()))
        SiteAreaService.fireTask[it.id] = mutableListOf()
      } else {
        SiteAreaService.fireTask[it.id] = fire.fireTask
      }
      SiteAreaService.pass[it.id] = true
      SiteAreaService.yellowTwinkle[it.id] = false
    }

    val areas = CUSTOM_CONFIG.areaToCabinet
    areas.forEach { modbusAreas[it.key] = SiteAreaModBus(it.key) }

  }

  fun init() {
    logger.debug("init site area modbus")
  }

  fun dispose() {
    modbusAreas.values.forEach { it.dispose() }
  }

  private fun getAreaModBusById(areId: String) = modbusAreas[areId] ?: throw BusinessError("No such config $areId")

  fun getAreaModBusBySiteId(siteId: String): SiteAreaModBus? {
    CUSTOM_CONFIG.areaToCabinet.forEach {
      if (it.value.siteIdToAddress.containsKey(siteId)) {
        return getAreaModBusById(it.key)
      }
    }
    return null
  }

}

class SiteAreaModBus(private val areaId: String) {

  private val logger = LoggerFactory.getLogger(SiteAreaModBus::class.java)

  // 库位检测
  private val siteCheckTimer = Executors.newScheduledThreadPool(1)
  private val twinkleExecutors = mutableListOf<ExecutorService>()
//  private val twinkleExecutor = Executors.newFixedThreadPool(3)
  private val yellowTwinkleExecutor = Executors.newFixedThreadPool(1)
  private val twinkling = ConcurrentHashMap<String, String?>()

  private var checking = false

  private val siteModBusHelper: ModbusTcpMasterHelper

  init {
    val area = CUSTOM_CONFIG.areaToCabinet[areaId] ?: throw BusinessError("No such ModBus area config $areaId")
    siteModBusHelper = ModbusTcpMasterHelper(area.host, area.port)
    siteModBusHelper.connect()
    for (index in 1..area.siteIdToAddress.size) {
      twinkleExecutors.add(Executors.newFixedThreadPool(1))
    }
    for (site in area.siteIdToAddress.keys) {
      twinkling[site] = ""
    }
    logger.debug("${areaId}管理${twinkleExecutors.size}个库位")

    siteCheckTimer.scheduleAtFixedRate(this::checkSite, 5, CUSTOM_CONFIG.interval.toLong(), TimeUnit.SECONDS)

  }

  @Synchronized
  fun switchGreen(siteId: String, twinkle: Boolean = false) {
    // 再检查一次
    val fire = SiteAreaService.fireTask[siteId] ?: throw BusinessError("init fire task error $siteId")
    if (fire.isNotEmpty()) return
    val cabinet = CUSTOM_CONFIG.areaToCabinet[areaId] ?: throw BusinessError("no such modbus config $areaId")
    val addr = cabinet.siteIdToAddress[siteId] ?: throw BusinessError("no such site config $siteId")

    if (twinkle) {
      siteModBusHelper.write05SingleCoil(addr.red, false, 1, "${siteId}红灯灭")
      siteModBusHelper.write05SingleCoil(addr.yellow, false, 1, "${siteId}黄灯灭")
      twinkleExecutors[addr.index].submit {
        try {
          if (twinkling[siteId] != "green") {
            twinkling[siteId] = "green"
            var temp = 0
            var log = false
            while (true) {
              val site = getSiteInfo(siteId) ?: throw BusinessError("no such modbus config $siteId")
              if (!site.filled || site.locked) break
              if (temp < 0) temp = 0    // 超出界限重新计数
              if (temp % 30 == 0) log = true    // twinkle × 2 × 30毫秒记一次，防止log记录过于频繁
              siteModBusHelper.write05SingleCoil(addr.green, true, 1, "${siteId}闪烁绿灯亮")
              Thread.sleep(CUSTOM_CONFIG.twinkle)
              siteModBusHelper.write05SingleCoil(addr.green, false, 1, "${siteId}闪烁绿灯灭")
              Thread.sleep(CUSTOM_CONFIG.twinkle)
              temp++
              log = false
            }
          }
        } catch (e: Exception) {
          logger.error("$siteId twinkle green error", e)
        } finally {
          twinkling[siteId] = ""
        }
      }
    } else {
      val green = siteModBusHelper.read01Coils(addr.green, 1, 1, "读取${siteId}绿灯")?.getByte(0)?.toInt()
      val yellow = siteModBusHelper.read01Coils(addr.yellow, 1, 1, "读取${siteId}黄灯")?.getByte(0)?.toInt()
      val red = siteModBusHelper.read01Coils(addr.red, 1, 1, "读取${siteId}红灯")?.getByte(0)?.toInt()
      if (green is Int && green % 2 == 0) siteModBusHelper.write05SingleCoil(addr.green, true, 1, "${siteId}绿灯亮")
      if (yellow is Int && yellow % 2 > 0) siteModBusHelper.write05SingleCoil(addr.yellow, false, 1, "${siteId}黄灯灭")
      if (red is Int && red % 2 > 0) siteModBusHelper.write05SingleCoil(addr.red, false, 1, "${siteId}红灯灭")
    }
  }

  @Synchronized
  fun switchYellow(siteId: String, twinkle: Boolean = false, remark: String = "") {
    val cabinet = CUSTOM_CONFIG.areaToCabinet[areaId] ?: throw BusinessError("no such modbus config $areaId")
    val addr = cabinet.siteIdToAddress[siteId] ?: throw BusinessError("no such site config $siteId")
    if (twinkle) {
      siteModBusHelper.write05SingleCoil(addr.red, false, 1, "${siteId}红灯灭")
      siteModBusHelper.write05SingleCoil(addr.green, false, 1, "${siteId}绿灯灭")
      yellowTwinkleExecutor.submit {
//        synchronized(twinkling[siteId]) {}
        try {
          if (twinkling[siteId] != "yellow") {
            twinkling[siteId] = "yellow"
            var temp = 0
            var log = false
            while (true) {
              val site = SiteAreaService.getAreaModBusBySiteId(siteId)?.getSiteInfo(siteId) ?: throw BusinessError("site modbus config error $siteId")
//          if (!site.filled || site.locked) break
              if (SiteAreaService.yellowTwinkle[site.id] == false) break
              if (temp < 0) temp = 0    // 超出界限重新计数
              if (temp % 30 == 0) log = true    // twinkle × 2 × 30毫秒记一次，防止log记录过于频繁
              siteModBusHelper.write05SingleCoil(addr.yellow, true, 1, if (remark.isBlank())"${siteId}闪烁黄灯亮" else siteId + remark)
              Thread.sleep(CUSTOM_CONFIG.twinkle)
              siteModBusHelper.write05SingleCoil(addr.yellow, false, 1, if (remark.isBlank())"${siteId}闪烁黄灯灭" else siteId + remark)
              Thread.sleep(CUSTOM_CONFIG.twinkle)
              temp++
              log = false
            }
          }
        } catch (e: Exception) {
          logger.error("$siteId twinkle yellow error", e)
        } finally {
          twinkling[siteId] = ""
        }
      }
    } else {
      SiteAreaService.yellowTwinkle[siteId] = false
      val green = siteModBusHelper.read01Coils(addr.green, 1, 1, "读取${siteId}绿灯")?.getByte(0)?.toInt()
      val yellow = siteModBusHelper.read01Coils(addr.yellow, 1, 1, "读取${siteId}黄灯")?.getByte(0)?.toInt()
      val red = siteModBusHelper.read01Coils(addr.red, 1, 1, "读取${siteId}红灯")?.getByte(0)?.toInt()
      if (yellow is Int && yellow % 2 == 0) siteModBusHelper.write05SingleCoil(addr.yellow, true, 1, "${siteId}黄灯亮")
      if (green is Int && green % 2 > 0) siteModBusHelper.write05SingleCoil(addr.green, false, 1, "${siteId}绿灯灭")
      if (red is Int && red % 2 > 0) siteModBusHelper.write05SingleCoil(addr.red, false, 1, "${siteId}红灯灭")
    }
  }

  fun switchRed(siteId: String) {
    val cabinet = CUSTOM_CONFIG.areaToCabinet[areaId] ?: throw BusinessError("no such modbus config $areaId")
    val addr = cabinet.siteIdToAddress[siteId] ?: throw BusinessError("no such site config $siteId")
    siteModBusHelper.write05SingleCoil(addr.yellow, false, 1, "${siteId}黄灯灭")
    siteModBusHelper.write05SingleCoil(addr.green, false, 1, "${siteId}绿灯灭")
    siteModBusHelper.write05SingleCoil(addr.red, true, 1, "${siteId}红灯常亮并报警")
  }

  @Synchronized
  fun getSiteInfo(siteId: String): StoreSite? {
    val cabinet = CUSTOM_CONFIG.areaToCabinet[areaId] ?: throw BusinessError("no such modbus config $areaId")
    val addr = cabinet.siteIdToAddress[siteId] ?: throw BusinessError("no such site config $siteId")
    val v1 = siteModBusHelper.read02DiscreteInputs(addr.sensor1, 1, 1, "${siteId}光电1")?.getByte(0)?.toInt()
    val v2 = siteModBusHelper.read02DiscreteInputs(addr.sensor2, 1, 1, "${siteId}光电2")?.getByte(0)?.toInt()
    if (v1 != null && v2 != null) {
      if (v1 % 2 > 0 || v2 % 2 > 0) {
        val site = StoreSiteService.getExistedStoreSiteById(siteId)
        if (!site.filled) StoreSiteService.changeSiteFilled(siteId, true, "置满")
      } else if (v1 % 2 == 0 && v2 % 2 == 0) {
        val site = StoreSiteService.getExistedStoreSiteById(siteId)
        if (site.filled) StoreSiteService.changeSiteFilled(siteId, false, "置空")
      }
    }else {
      logger.error("${siteId}库位光电异常")
    }
    return StoreSiteService.getStoreSiteById(siteId)
  }

  private fun checkSite() {
    synchronized(siteModBusHelper) {
      if (checking) return
      checking = true
      try {
        CUSTOM_CONFIG.areaToCabinet.forEach {
          if (it.key == areaId) {
            val cabinet = it.value
            cabinet.siteIdToAddress.forEach { idToAddr ->
              val site = getSiteInfo(idToAddr.key) ?: return
              val fire = SiteAreaService.fireTask[site.id] ?: throw BusinessError("init fire task error ${site.id}")
//              val tasks = MongoDBManager.collection<RobotTask>().find(RobotTask::id `in` fire).toMutableList()
//              val tasksOnSent = tasks.filter { task -> task.state < RobotTaskState.Success && task.transports[0].state > RobotTransportState.Created }
//              if (fire.isEmpty() || tasksOnSent.isNullOrEmpty()) {    // 非任务引起的变化
              if (fire.isEmpty()) {    // 非任务引起的变化
                if (site.locked) {    // 该库位被锁定
                  logger.warn("site [${site.id}] locked manually!!!")
                  switchYellow(idToAddr.key, remark = "人为引起的库位[${site.id}]锁定")
                } else {      // 该库位未被锁定
                  if (!site.filled){        // 无货
                    SiteAreaService.yellowTwinkle[site.id] = false    // 置为false，以免人为放货的时候黄灯闪烁
                    switchGreen(site.id)
                  }
                  else {
                    //  有货,任务引起的库位变化 ==> 下面这行逻辑放到了任务定义组件里边
//                    if (SiteAreaService.yellowTwinkle[site.id] == true) switchYellow(site.id, true)
                    //  有货,非任务引起的库位变化
                    if (SiteAreaService.yellowTwinkle[site.id] == false) switchGreen(site.id, true)
                  }
                }
              } else{        // 任务引起的变化
                logger.debug("task [${fire[0]}] caused site [${site.id}] locked")
                if (site.locked) {    // 确定任务引起的警示灯是亮的
                  if (SiteAreaService.yellowTwinkle[site.id] == false) {
                    val addr = cabinet.siteIdToAddress[site.id] ?: throw BusinessError("no such site config ${site.id}")
                    val yellow = siteModBusHelper.read01Coils(addr.yellow, 1, 1, "读取${site.id}黄灯")?.getByte(0)?.toInt()
                    if (yellow is Int && yellow % 2 == 0) siteModBusHelper.write05SingleCoil(addr.yellow, true, 1, "${site.id}黄灯亮")
                  }
                }
                // fire == true，在任务定义中处理
              }
            }
          }
        }
      } catch (e: Exception) {
        logger.error(e.message, e)
      } finally {
        checking = false
      }
    }
  }

  fun dispose() {
    siteModBusHelper.disconnect()
    siteCheckTimer.shutdown()
  }
}