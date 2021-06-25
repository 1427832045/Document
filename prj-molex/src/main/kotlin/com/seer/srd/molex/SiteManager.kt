package com.seer.srd.molex

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import io.netty.buffer.ByteBufUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bson.types.ObjectId
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.pull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.collections.set
import kotlin.concurrent.read
import kotlin.concurrent.write

object SiteAreaService {

  val fireTask: MutableMap<String, MutableList<String>> = ConcurrentHashMap()

  val yellowTwinkle: MutableMap<String, Boolean> = ConcurrentHashMap()

  val greenTwinkle: MutableMap<String, Boolean> = ConcurrentHashMap()

  val redTwinkle: MutableMap<String, Boolean> = ConcurrentHashMap()

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
      SiteAreaService.redTwinkle[it.id] = false
      SiteAreaService.greenTwinkle[it.id] = false
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

  fun getAreaModBusById(areId: String) = modbusAreas[areId] ?: throw BusinessError("No such config $areId")

  fun getAreaModBusBySiteId(siteId: String): SiteAreaModBus? {
    CUSTOM_CONFIG.areaToCabinet.forEach {
      if (it.value.siteIdToAddress.containsKey(siteId)) {
        return getAreaModBusById(it.key)
      }
    }
    return null
  }

}

class SiteAreaModBus(val areaId: String) {

  private val logger = LoggerFactory.getLogger(SiteAreaModBus::class.java)

  private var readCount = ConcurrentHashMap<String, Int>()
  // 库位检测
  private val siteCheckTimer = Executors.newScheduledThreadPool(1)
//  private val twinkleExecutors = mutableListOf<ExecutorService>()
  private val twinkleExecutors2 = Executors.newCachedThreadPool()
  private val yellowTwinkleExecutor = Executors.newCachedThreadPool()
//  private val twinkling = ConcurrentHashMap<String, String?>()
  private val redTwinkleExecutor = Executors.newCachedThreadPool()

  private var checking = false

  private var siteModBusHelper: ModbusTcpMasterHelper

  private val lock = ReentrantReadWriteLock()

//  val siteStateMap = ConcurrentHashMap<String, LIGHT_STATE>()
  val siteStateMap = mutableMapOf<String, LIGHT_STATE>()

  private val lightTimer = Executors.newScheduledThreadPool(1)

  @Volatile
  var error = false

  @Volatile
  private var lighting = false

  init {
    val area = CUSTOM_CONFIG.areaToCabinet[areaId] ?: throw BusinessError("No such ModBus area config $areaId")
    siteModBusHelper = ModbusTcpMasterHelper(area.host, area.port)
    siteModBusHelper.connect()
//    for (index in 1..area.siteIdToAddress.size) {
//      twinkleExecutors.add(Executors.newFixedThreadPool(1))
//    }
    for (site in area.siteIdToAddress.keys) {
//      twinkling[site] = ""
      readCount[site] = 0
    }
//    logger.debug("${areaId} manage ${twinkleExecutors.size} sites")

    siteCheckTimer.scheduleAtFixedRate(this::checkSite, 1, CUSTOM_CONFIG.interval.toLong(), TimeUnit.SECONDS)

    if (CUSTOM_CONFIG.enableMultiControl) lightTimer.scheduleAtFixedRate(this::light, 1000, 1000, TimeUnit.MILLISECONDS)

//    lightExecutor.submit { light() }

  }

  private fun switchGreen2(siteId: String, twinkle: Boolean = false) {
    // 再检查一次
    val fire = SiteAreaService.fireTask[siteId] ?: throw BusinessError("init fire task error $siteId")
    if (fire.isNotEmpty()) return
    if (twinkle) {
      if (SiteAreaService.yellowTwinkle[siteId] == true) {
        logger.debug("$siteId yellowTwinkle, skip green twinkle")
      }
      twinkleExecutors2.submit {
        while (true) {
          try {
            val site = StoreSiteService.getExistedStoreSiteById(siteId)
            if (SiteAreaService.redTwinkle[siteId] == true) {
              break
            }
            if (!site.filled || site.locked) break
            siteStateMap[siteId] = LIGHT_STATE.GREEN_TWINKLE
            if (SiteAreaService.greenTwinkle[siteId] == false) SiteAreaService.greenTwinkle[siteId] = true
            Thread.sleep(CUSTOM_CONFIG.twinkle * 2)
          } catch (e: Exception) {
            logger.error("$siteId twinkle green error, ${e.message}")
            Thread.sleep(2000)
          }
        }
      }
    } else {
      SiteAreaService.greenTwinkle[siteId] = false
      if (SiteAreaService.redTwinkle[siteId] == true) {
        logger.debug("$siteId skip green because of red twinkle")
        return
      }
      siteStateMap[siteId] = LIGHT_STATE.GREEN
    }
  }

  fun switchGreen(siteId: String, twinkle: Boolean = false) {
    if (CUSTOM_CONFIG.enableMultiControl) {
      switchGreen2(siteId, twinkle)
      return
    }
    // 再检查一次
    val fire = SiteAreaService.fireTask[siteId] ?: throw BusinessError("init fire task error $siteId")
    if (fire.isNotEmpty()) return
    val cabinet = CUSTOM_CONFIG.areaToCabinet[areaId] ?: throw BusinessError("no such modbus config $areaId")
    val addr = cabinet.siteIdToAddress[siteId] ?: throw BusinessError("no such site config $siteId")

    if (twinkle) {
      if (SiteAreaService.yellowTwinkle[siteId] == true) {
        logger.debug("$siteId yellowTwinkle, skip green twinkle")
      }
//      siteModBusHelper.write05SingleCoil(addr.red, false, 1, "$siteId light red off", false)
//      siteModBusHelper.write05SingleCoil(addr.yellow, false, 1, "$siteId light yellow off", false)
//      twinkleExecutors[addr.index].submit {
      twinkleExecutors2.submit {

        var temp = 0
        var log = false
        while (true) {
          try {
//            val site = getSiteInfo(siteId) ?: throw BusinessError("no such modbus config $siteId")
            val site = StoreSiteService.getExistedStoreSiteById(siteId)
//            val site = getSiteInfos().first { it.id == siteId }
            if (SiteAreaService.redTwinkle[siteId] == true) {
              break
            }
            if (!site.filled || site.locked) break
            if (temp < 0) temp = 0    // 超出界限重新计数
            if (temp % 10 == 0) log = true    // twinkle × 2 × 30毫秒记一次，防止log记录过于频繁
            lock.read {
              siteModBusHelper.write0FMultipleCoils(addr.red, 3, byteArrayOf(4), 1, "$siteId twinkle green on", log)
              Thread.sleep(CUSTOM_CONFIG.twinkle)
              siteModBusHelper.write0FMultipleCoils(addr.red, 3, byteArrayOf(0), 1, "$siteId twinkle green off", log)
              Thread.sleep(CUSTOM_CONFIG.twinkle)
            }
            if (SiteAreaService.greenTwinkle[siteId] == false) SiteAreaService.greenTwinkle[siteId] = true
            temp++
            log = false
          } catch (e: Exception) {
            logger.error("$siteId twinkle green error, ${e.message}")
            Thread.sleep(2000)
          }
        }
      }
    } else {
      SiteAreaService.greenTwinkle[siteId] = false
      if (SiteAreaService.redTwinkle[siteId] == true) {
        logger.debug("$siteId skip green because of red twinkle")
        return
      }
      var log = false
      var count = readCount[siteId]
      if (count != null) {
        if (count % CUSTOM_CONFIG.readInterval == 0) log = true
        readCount[siteId] = ++count
      }
      if (readCount[siteId]!! > 1000000) {
      }
      readCount[siteId] = 0
//      val green = siteModBusHelper.read01Coils(addr.green, 1, 1, "read green addr ${siteId}", log)?.getByte(0)?.toInt()
//      val yellow = siteModBusHelper.read01Coils(addr.yellow, 1, 1, "read yellow addr ${siteId}", log)?.getByte(0)?.toInt()
//      val red = siteModBusHelper.read01Coils(addr.red, 1, 1, "read red addr ${siteId}", log)?.getByte(0)?.toInt()
//      if (green is Int && green % 2 == 0) siteModBusHelper.write05SingleCoil(addr.green, true, 1, "$siteId light on green")
//      if (yellow is Int && yellow % 2 > 0) siteModBusHelper.write05SingleCoil(addr.yellow, false, 1, "$siteId light off yellow")
//      if (red is Int && red % 2 > 0) siteModBusHelper.write05SingleCoil(addr.red, false, 1, "$siteId light off red")

      // 批量操作线圈量时，数量对应bit。如数量3，对应1字节(数量不足8个就补0)，"0 0 0 0 0 1 0 0"后三个bit就是对应三个值。
      lock.read {
        siteModBusHelper.write0FMultipleCoils(addr.red, 3, byteArrayOf(4), 1, "$siteId green yellow red: 1 0 0", log)
      }
    }
  }

  @Synchronized
  fun switchYellow2(siteId: String, twinkle: Boolean = false) {
    if (twinkle) {
      yellowTwinkleExecutor.submit {
        while (true) {
          try {
            if (SiteAreaService.greenTwinkle[siteId] == true) SiteAreaService.greenTwinkle[siteId] = false
            val site = StoreSiteService.getExistedStoreSiteById(siteId)
            if (SiteAreaService.redTwinkle[site.id] == true) {
              Thread.sleep(2000)
              continue
            }
            if (SiteAreaService.yellowTwinkle[site.id] == false) break
            siteStateMap[siteId] = LIGHT_STATE.YELLOW_TWINKLE
            Thread.sleep(CUSTOM_CONFIG.twinkle * 2)
          } catch (e: Exception) {
            logger.error("$siteId twinkle yellow error ${e.message}")
            Thread.sleep(2000)
          }
        }
      }
    } else {
      SiteAreaService.yellowTwinkle[siteId] = false
      SiteAreaService.greenTwinkle[siteId] = false
      siteStateMap[siteId] = LIGHT_STATE.YELLOW
    }
  }

  @Synchronized
  fun switchYellow(siteId: String, twinkle: Boolean = false, remark: String = "") {
    if (CUSTOM_CONFIG.enableMultiControl) {
      switchYellow2(siteId, twinkle)
      return
    }
    val cabinet = CUSTOM_CONFIG.areaToCabinet[areaId] ?: throw BusinessError("no such modbus config $areaId")
    val addr = cabinet.siteIdToAddress[siteId] ?: throw BusinessError("no such site config $siteId")
    if (twinkle) {
//      siteModBusHelper.write05SingleCoil(addr.red, false, 1, "$siteId light red off", false)
//      siteModBusHelper.write05SingleCoil(addr.green, false, 1, "$siteId light green off", false)
      yellowTwinkleExecutor.submit {
        var temp = 0
        var log = false
        while (true) {
          try {
            if (SiteAreaService.greenTwinkle[siteId] == true) SiteAreaService.greenTwinkle[siteId] = false
//            val site = getSiteInfo(siteId) ?: throw BusinessError("site modbus config error $siteId")
            val site = StoreSiteService.getExistedStoreSiteById(siteId)
            if (SiteAreaService.redTwinkle[site.id] == true) {
              Thread.sleep(2000)
              continue
            }
            if (SiteAreaService.yellowTwinkle[site.id] == false) break
            if (temp < 0) temp = 0    // 超出界限重新计数
            if (temp % 10 == 0) log = true    // twinkle × 2 × 10毫秒记一次，防止log记录过于频繁
            lock.read {
              siteModBusHelper.write0FMultipleCoils(addr.red, 3, byteArrayOf(2), 1, if (remark.isBlank())"$siteId twinkle yellow on" else siteId + remark, log)
              Thread.sleep(CUSTOM_CONFIG.twinkle)
              siteModBusHelper.write0FMultipleCoils(addr.red, 3, byteArrayOf(0), 1, if (remark.isBlank())"$siteId twinkle yellow off" else siteId + remark, log)
              Thread.sleep(CUSTOM_CONFIG.twinkle)
            }
            temp++
            log = false
          } catch (e: Exception) {
            logger.error("$siteId twinkle yellow error ${e.message}")
            Thread.sleep(2000)
          }
        }
      }
    } else {
      SiteAreaService.yellowTwinkle[siteId] = false
      SiteAreaService.greenTwinkle[siteId] = false
      var log = false
      var count = readCount[siteId]
      if (count != null) {
        if (count % CUSTOM_CONFIG.readInterval == 0) log = true
        readCount[siteId] = ++count
      }
      if (readCount[siteId]!! > 1000000)
        readCount[siteId] = 0
//      val green = siteModBusHelper.read01Coils(addr.green, 1, 1, "read green addr $siteId", log)?.getByte(0)?.toInt()
//      val yellow = siteModBusHelper.read01Coils(addr.yellow, 1, 1, "read yellow addr ${siteId}", log)?.getByte(0)?.toInt()
//      val red = siteModBusHelper.read01Coils(addr.red, 1, 1, "read red addr${siteId}", log)?.getByte(0)?.toInt()
//      if (yellow is Int && yellow % 2 == 0) siteModBusHelper.write05SingleCoil(addr.yellow, true, 1, "$siteId light on yellow")
//      if (green is Int && green % 2 > 0) siteModBusHelper.write05SingleCoil(addr.green, false, 1, "$siteId light off green")
//      if (red is Int && red % 2 > 0) siteModBusHelper.write05SingleCoil(addr.red, false, 1, "$siteId light off red")
      lock.read {
        siteModBusHelper.write0FMultipleCoils(addr.red, 3, byteArrayOf(2), 1, "$siteId green yellow red: 0 1 0", log)
      }
    }
  }

  @Synchronized
  fun switchRed2(siteId: String, twinkle: Boolean = false) {
    if (twinkle) {
      redTwinkleExecutor.submit {
        while (true) {
          try {
            val site = StoreSiteService.getExistedStoreSiteById(siteId)
            if (SiteAreaService.greenTwinkle[site.id] == true) SiteAreaService.greenTwinkle[site.id] = false
            if (SiteAreaService.redTwinkle[site.id] == false) break
            siteStateMap[siteId] = LIGHT_STATE.RED_TWINKLE
            Thread.sleep(CUSTOM_CONFIG.twinkle * 2)
          } catch (e: Exception) {
            logger.error("$siteId twinkle red error, ${e.message}")
            Thread.sleep(2000)
          }
        }
      }
    } else {
      SiteAreaService.redTwinkle[siteId] = false
      SiteAreaService.greenTwinkle[siteId] = false
      siteStateMap[siteId] = LIGHT_STATE.RED
    }
  }

  @Synchronized
  fun switchRed(siteId: String, twinkle: Boolean = false, remark: String = "") {
    if (CUSTOM_CONFIG.enableMultiControl) {
      switchRed2(siteId, twinkle)
      return
    }
    val cabinet = CUSTOM_CONFIG.areaToCabinet[areaId] ?: throw BusinessError("no such modbus config $areaId")
    val addr = cabinet.siteIdToAddress[siteId] ?: throw BusinessError("no such site config $siteId")
    if (twinkle) {
//      siteModBusHelper.write05SingleCoil(addr.yellow, false, 1, "$siteId light yellow off", false)
//      siteModBusHelper.write05SingleCoil(addr.green, false, 1, "$siteId light green off", false)
      redTwinkleExecutor.submit {
        var temp = 0
        var log = false
        while (true) {
          try {
//            val site = getSiteInfo(siteId) ?: throw BusinessError("site modbus config error $siteId")
            val site = StoreSiteService.getExistedStoreSiteById(siteId)
            if (SiteAreaService.greenTwinkle[site.id] == true) SiteAreaService.greenTwinkle[site.id] = false
            if (SiteAreaService.redTwinkle[site.id] == false) break
            if (temp < 0) temp = 0    // 超出界限重新计数
            if (temp % 10 == 0) log = true    // twinkle × 2 × 10毫秒记一次，防止log记录过于频繁
            lock.read {
              siteModBusHelper.write0FMultipleCoils(addr.red, 3, byteArrayOf(1), 1, if (remark.isBlank())"$siteId twinkle red on" else siteId + remark, log)
              Thread.sleep(CUSTOM_CONFIG.twinkle)
              siteModBusHelper.write0FMultipleCoils(addr.red, 3, byteArrayOf(0), 1, if (remark.isBlank())"$siteId twinkle red off" else siteId + remark, log)
              Thread.sleep(CUSTOM_CONFIG.twinkle)
            }
            temp++
            log = false
          } catch (e: Exception) {
            logger.error("$siteId twinkle red error, ${e.message}")
            Thread.sleep(2000)
          }
        }
      }
    } else {
      SiteAreaService.redTwinkle[siteId] = false
      SiteAreaService.greenTwinkle[siteId] = false
      var log = false
      var count = readCount[siteId]
      if (count != null) {
        if (count % CUSTOM_CONFIG.readInterval == 0) log = true
        readCount[siteId] = ++count
      }
      if (readCount[siteId]!! > 1000000)
        readCount[siteId] = 0
//      val green = siteModBusHelper.read01Coils(addr.green, 1, 1, "read green addr $siteId", log)?.getByte(0)?.toInt()
//      val yellow = siteModBusHelper.read01Coils(addr.yellow, 1, 1, "read yellow addr $siteId", log)?.getByte(0)?.toInt()
//      val red = siteModBusHelper.read01Coils(addr.red, 1, 1, "read red addr$siteId", log)?.getByte(0)?.toInt()
//      if (green is Int && green % 2 > 0) siteModBusHelper.write05SingleCoil(addr.green, false, 1, "${siteId}off green")
//      if (yellow is Int && yellow % 2 > 0) siteModBusHelper.write05SingleCoil(addr.yellow, false, 1, "${siteId}off yellow")
//      if (red is Int && red % 2 == 0) siteModBusHelper.write05SingleCoil(addr.red, true, 1, "${siteId}light on red and warning")
      lock.read {
        siteModBusHelper.write0FMultipleCoils(addr.red, 3, byteArrayOf(1), 1, "$siteId green yellow red: 0 0 1", log)
      }
    }
  }


  fun checkSiteInfos(): List<StoreSite> {
    val siteMap = mutableMapOf<String, Int>()
    var startAddress = Integer.MAX_VALUE
    CUSTOM_CONFIG.areaToCabinet[areaId]?.siteIdToAddress?.forEach {
      // 以下赋值必须与配置文件严格匹配
      siteMap[it.key] = if (it.value.sensor1 < it.value.sensor2) it.value.sensor1 else it.value.sensor2
      if ((siteMap[it.key] as Int) < startAddress) startAddress = siteMap[it.key] as Int
    }
    val sites = siteMap.keys
    val dataArr = ByteBufUtil.hexDump(siteModBusHelper.read02DiscreteInputs(startAddress, sites.size * 2, 1, "read area $areaId")).toCharArray()
    val newDataArr = CharArray(dataArr.size)
    for (index in 1 until dataArr.size step 2) {
      newDataArr[index] = dataArr[dataArr.size - index]
      newDataArr[index - 1] = dataArr[dataArr.size - index - 1]
    }
    var str = ""
    newDataArr.forEach {
      str += it
    }
    if (str.isNotBlank()) {
      val data = str.toInt(16)
      for (index in 0 until sites.size) {
        val sensor1 = data.and(Integer.valueOf(1).shl(index * 2))
        val sensor2 = data.and(Integer.valueOf(1).shl(index * 2 + 1))
        siteMap[sites.elementAt(index)] = if (sensor1 > 0 || sensor2 > 0) 1 else 0
      }
    }
    // 分别找到满库位和空库位的
    val filled = siteMap.filter { (_, v) -> v > 0 }
    val notFilled = siteMap.filter { (_, v) -> v == 0 }
    StoreSiteService.changeSitesFilledByIds(filled.map { it.key }, true, "change to filled from SRD")
    StoreSiteService.changeSitesFilledByIds(notFilled.map { it.key }, false, "change to unfilled from SRD")
    return StoreSiteService.listStoreSites().filter { siteMap.keys.contains(it.id) }

  }

  @Synchronized
  fun getSiteInfo(siteId: String): StoreSite? {
    lock.read {
      val cabinet = CUSTOM_CONFIG.areaToCabinet[areaId] ?: throw BusinessError("no such modbus config $areaId")
      val addr = cabinet.siteIdToAddress[siteId] ?: throw BusinessError("no such site config $siteId")
      val v = siteModBusHelper.read02DiscreteInputs(addr.sensor1, 2, 1, "$siteId sensor")?.getByte(0)?.toInt()
      val v1 = v?.and(Integer.valueOf(1).shl(0))
      val v2 = v?.and(Integer.valueOf(1).shl(1))
//    val v2 = siteModBusHelper.read02DiscreteInputs(addr.sensor2, 1, 1, "${siteId}sensor2")?.getByte(0)?.toInt()
      if (v1 != null && v2 != null) {
        if (v1 % 2 > 0 || v2 % 2 > 0) {
          val site = StoreSiteService.getExistedStoreSiteById(siteId)
          if (!site.filled) StoreSiteService.changeSiteFilled(siteId, true, "set filled")
        } else if (v1 % 2 == 0 && v2 % 2 == 0) {
          val site = StoreSiteService.getExistedStoreSiteById(siteId)
          if (site.filled) StoreSiteService.changeSiteFilled(siteId, false, "set empty")
        }
      }else {
        logger.error("${siteId}库位光电异常")
      }
    }
    return StoreSiteService.getStoreSiteById(siteId)
  }

  private fun light() {
    try {
      if (lighting) return
      lighting = true

      val cabinet = CUSTOM_CONFIG.areaToCabinet[areaId] ?: throw BusinessError("No such area $areaId")
      val fromAddr = cabinet.fromAddr
      val qty = when (areaId) {
        "area1", "area4" -> 27
        "area2" -> 11
        "area3", "area5" -> 23
        "area6" -> 7
        else -> 8
      }
      val byteArrList = LightHelper.getByteArrayByAreaId(areaId)

      if (byteArrList.isNotEmpty()) {
//        lock.read {
          checkSiteInfos()
          Thread.sleep(CUSTOM_CONFIG.twinkle)
          siteModBusHelper.write0FMultipleCoils(fromAddr, qty, byteArrList[1], 1, "write area $areaId")
          Thread.sleep(CUSTOM_CONFIG.twinkle)
          siteModBusHelper.write0FMultipleCoils(fromAddr, qty, byteArrList[0], 1, "write area $areaId")
          Thread.sleep(CUSTOM_CONFIG.twinkle)
          error = false
//        }
      }
    } catch (e: Exception) {
      logger.error("light area $areaId error, ${e.message}")
      error = true
    } finally {
      lighting = false
    }
  }

  private fun checkSite2() {
    synchronized(siteModBusHelper) {
      if (checking) return
      checking = true
      try {
        val cabinet = CUSTOM_CONFIG.areaToCabinet[areaId] ?: throw BusinessError("No such area $areaId")
        var sites: List<StoreSite> = emptyList()
        lock.write { sites = checkSiteInfos() }
        if (sites.isEmpty()) return
        cabinet.siteIdToAddress.forEach { (siteId,_) ->
          val site = sites.first { s -> siteId == s.id }
          val fire = SiteAreaService.fireTask[site.id] ?: throw BusinessError("init fire task error ${site.id}")

          if (fire.isEmpty()) {    // 非任务引起的变化
            if (site.locked) {    // 该库位被锁定
              logger.warn("site [${site.id}] locked manually!!!")
              siteStateMap[siteId] = LIGHT_STATE.YELLOW
            } else {      // 该库位未被锁定
              if (!site.filled){        // 无货
                SiteAreaService.yellowTwinkle[site.id] = false    // 置为false，以免人为放货的时候黄灯闪烁
                siteStateMap[siteId] = LIGHT_STATE.GREEN
              }
              else {
                //  有货,任务引起的库位变化 ==> 下面这行逻辑放到了任务定义组件里边
//                    if (SiteAreaService.yellowTwinkle[site.id] == true) siteStateMap[siteId] = LIGHT_STATE.YELLOW_TWINKLE
                //  有货,非任务引起的库位变化
                if (SiteAreaService.yellowTwinkle[site.id] == false) {
                  siteStateMap[siteId] = LIGHT_STATE.GREEN_TWINKLE
                }
              }
            }
          } else{        // 任务引起的变化
            val taskId = fire[0]
            logger.debug("task [$taskId] caused site [${site.id}] fireTask not empty")

            val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq taskId)
            if (task == null || task.state >= RobotTaskState.Success) {
              MongoDBManager.collection<SiteFireTask>().updateOne(SiteFireTask::siteId eq site.id, pull(SiteFireTask::fireTask, taskId))
              if (SiteAreaService.fireTask[site.id]?.contains(taskId) == true) SiteAreaService.fireTask[site.id]?.remove(taskId)
              logger.warn("invalid task [$taskId], removed")
            }

            if (site.locked) {    // 确定任务引起的警示灯是亮的
              logger.debug("task [$taskId] caused site [${site.id}] locked")
              if (SiteAreaService.yellowTwinkle[site.id] == false) {
                if (siteStateMap[siteId] !in listOf(LIGHT_STATE.RED, LIGHT_STATE.YELLOW))
                  siteStateMap[siteId] = LIGHT_STATE.YELLOW
              }
            }
            // fire == true，在任务定义中处理
          }
        }
      } catch (e: Exception) {
        logger.error(e.message)
      } finally {
        checking = false
      }
    }
  }

  private fun checkSite() {
    synchronized(siteModBusHelper) {
      if (checking) return
      checking = true
      try {
        CUSTOM_CONFIG.areaToCabinet.forEach {
          if (it.key == areaId) {
            val cabinet = it.value
            var sites = if (CUSTOM_CONFIG.enableMultiControl)
              StoreSiteService.listStoreSites()
            else emptyList()
            if (!CUSTOM_CONFIG.enableMultiControl) lock.write { sites = checkSiteInfos() }
            if (sites.isEmpty()) return
            cabinet.siteIdToAddress.forEach { idToAddr ->
//              val site = getSiteInfo(idToAddr.key) ?: return
              val site = sites.first { s -> idToAddr.key == s.id }
              val fire = SiteAreaService.fireTask[site.id] ?: throw BusinessError("init fire task error ${site.id}")
//              val tasks = MongoDBManager.collection<RobotTask>().find(RobotTask::id `in` fire).toMutableList()
//              val tasksOnSent = tasks.filter { task -> task.state < RobotTaskState.Success && task.transports[0].state > RobotTransportState.Created }
//              if (fire.isEmpty() || tasksOnSent.isNullOrEmpty()) {    // 非任务引起的变化
              if (fire.isEmpty()) {    // 非任务引起的变化
                if (site.locked) {    // 该库位被锁定
                  logger.warn("site [${site.id}] locked manually!!!")
                  switchYellow(idToAddr.key, remark = "manually [${site.id}] locked")
                } else {      // 该库位未被锁定
                  if (!site.filled){        // 无货
                    SiteAreaService.yellowTwinkle[site.id] = false    // 置为false，以免人为放货的时候黄灯闪烁
                    switchGreen(site.id)
                  }
                  else {
                    //  有货,任务引起的库位变化 ==> 下面这行逻辑放到了任务定义组件里边
//                    if (SiteAreaService.yellowTwinkle[site.id] == true) switchYellow(site.id, true)
                    //  有货,非任务引起的库位变化
                    if (SiteAreaService.yellowTwinkle[site.id] == false) {
                      // 某种情况下出现黄灯常亮，但是greenTwinkle=true,还未找到原因
                      val yellow = siteModBusHelper.read01Coils(idToAddr.value.yellow, 1, 1, "read ${site.id} yellow")?.getByte(0)?.toInt()
                      if(SiteAreaService.greenTwinkle[site.id] == false || yellow is Int && yellow % 2 > 0) switchGreen(site.id, true)
                    }
                  }
                }
              } else{        // 任务引起的变化
                val taskId = fire[0]
                logger.debug("task [$taskId] caused site [${site.id}] fireTask not empty")

                val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq taskId)
                if (task == null || task.state >= RobotTaskState.Success) {
                  MongoDBManager.collection<SiteFireTask>().updateOne(SiteFireTask::siteId eq site.id, pull(SiteFireTask::fireTask, taskId))
                  if (SiteAreaService.fireTask[site.id]?.contains(taskId) == true) SiteAreaService.fireTask[site.id]?.remove(taskId)
                  logger.warn("invalid task [$taskId], removed")
                }

                if (site.locked) {    // 确定任务引起的警示灯是亮的
                  logger.debug("task [$taskId] caused site [${site.id}] locked")
                  if (SiteAreaService.yellowTwinkle[site.id] == false) {
                    switchYellow(site.id)
//                    val addr = cabinet.siteIdToAddress[site.id] ?: throw BusinessError("no such site config ${site.id}")
//                    val red = siteModBusHelper.read01Coils(addr.red, 1, 1, "read ${site.id} red")?.getByte(0)?.toInt()
//                    if (red is Int && red % 2 == 0) {
//                      val yellow = siteModBusHelper.read01Coils(addr.yellow, 1, 1, "read ${site.id} yellow")?.getByte(0)?.toInt()
//                      if (yellow is Int && yellow % 2 == 0) siteModBusHelper.write05SingleCoil(addr.yellow, true, 1, "${site.id}light on yellow")
//                    }
                  }
                }
                // fire == true，在任务定义中处理
              }
            }
          }
        }
      }
      catch (e: Exception) {
        logger.error(e.message)
        val map = SiteAreaService.greenTwinkle.toMutableMap()
        map.forEach { SiteAreaService.greenTwinkle[it.key] = false }
      } finally {
        checking = false
        if (lock.isWriteLocked) lock.writeLock().unlock()
      }
    }
  }

  fun dispose() {
    siteModBusHelper.disconnect()
    siteCheckTimer.shutdown()
  }
}

enum class LIGHT_STATE {
  GREEN, GREEN_TWINKLE,
  YELLOW, YELLOW_TWINKLE,
  RED, RED_TWINKLE
}