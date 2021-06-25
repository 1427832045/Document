package com.seer.srd.proface

import com.seer.srd.BusinessError
import com.seer.srd.device.ZoneService
import com.seer.srd.omron.fins.core.FinsIoAddress
import com.seer.srd.omron.fins.core.FinsIoMemoryArea
import com.seer.srd.omron.fins.core.FinsNodeAddress
import com.seer.srd.omron.fins.udp.master.FinsNettyUdpMaster
import com.seer.srd.scheduler.ThreadFactoryHelper
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


object OkClientService {

  private val logger = LoggerFactory.getLogger(OkClientService::class.java)

  private val okVehicles: MutableMap<String, OkFinsUdpClient> = mutableMapOf()

  init {
      CUSTOM_CONFIG.finsClients.forEach {
          try {
            val srcNode = FinsNodeAddress(CUSTOM_CONFIG.srcNetAddr, CUSTOM_CONFIG.srcNodeAddr, CUSTOM_CONFIG.srcUnitAddr)
            val name = it.key
            val client = it.value
            val srcAddr = InetSocketAddress(CUSTOM_CONFIG.srcHost, client.srcPort)
            val desAddr = InetSocketAddress(client.desHost, client.desPort)
            val desNode = FinsNodeAddress(client.desNetAddr, client.desNodeAddr, client.desUnitAddr)
            okVehicles[name] = OkFinsUdpClient(name, desAddr, srcAddr, srcNode, desNode, client.info)
          } catch (e: Exception) {
            logger.error("=====================")
          }
      }
  }
  fun init() {
    logger.debug("init OkClientService")
  }
}

class OkFinsUdpClient(
    private val name: String,
    desAddr: InetSocketAddress,
    srcAddr: InetSocketAddress,
    srcNode: FinsNodeAddress,
    private val desNode: FinsNodeAddress,
    private val clientInfo: ClientInfo) {

  private val logger = LoggerFactory.getLogger(OkFinsUdpClient::class.java)

  private val finsMasterHelper: FinsNettyUdpMaster = FinsNettyUdpMaster(desAddr, srcAddr, srcNode)

  @Volatile
  private var checking = false

  private var lock = Object()

  private val executor = Executors.newScheduledThreadPool(1, ThreadFactoryHelper.buildNamedThreadFactory("srd-proface-cache"))

  init {
    try {
      finsMasterHelper.connect()
      executor.scheduleAtFixedRate(this::checkMutex, 1, 1, TimeUnit.SECONDS)
    } catch (e: IOException) {
      logger.error("io error $e.message")
    } catch (e: Exception) {
      logger.error("error $e.message")
    }
  }

  fun close() {
    try {
      finsMasterHelper.close()
      logger.debug("close fins client=[desAddr=${desNode.address}, desNode=${desNode.node}, desUnit=${desNode.unit}]")
    } catch (e: Exception) {
      logger.error("close error client=[desAddr=${desNode.address}, desNode=${desNode.node}, desUnit=${desNode.unit}]", e)
    }
  }

  private fun checkMutex() {
    if (checking) return
    checking = true
    synchronized(lock) {
      try {

        // 获取当前欧凯AGV的位置
        val area = FinsIoMemoryArea.valueOf(clientInfo.area.toByte()).get()
        val loc: Int
        when (area) {
          FinsIoMemoryArea.DM_BIT,
          FinsIoMemoryArea.AR_BIT,
          FinsIoMemoryArea.HR_BIT,
          FinsIoMemoryArea.WR_BIT,
          FinsIoMemoryArea.CIO_BIT,
          FinsIoMemoryArea.TASK_BIT,
          FinsIoMemoryArea.TASK_STATUS,
          FinsIoMemoryArea.TIMER_COUNTER_COMPLETION_FLAG,
          FinsIoMemoryArea.CLOCK_PULSES_CONDITION_FLAGS_BIT -> {
            throw BusinessError("invalid memory area $area")
//              val bits = finsMasterHelper.readBits(desNode, FinsIoAddress(area, clientInfo.readAddr, clientInfo.readOffset), clientInfo.count)
//              loc = bits[0].bitData.toInt()
          }
          FinsIoMemoryArea.AR_WORD,
          FinsIoMemoryArea.DM_WORD,
          FinsIoMemoryArea.HR_WORD,
          FinsIoMemoryArea.WR_WORD,
          FinsIoMemoryArea.CIO_WORD,
          FinsIoMemoryArea.TIMER_COUNTER_PV,
          FinsIoMemoryArea.DATA_REGISTER_PV -> {
            loc = finsMasterHelper.readWord(desNode, FinsIoAddress(area, clientInfo.readAddr, clientInfo.readOffset)).toInt()
          }
          FinsIoMemoryArea.INDEX_REGISTER_PV -> throw BusinessError("不可用")
          else -> throw BusinessError("unknown area code: ${clientInfo.area}")
        }

        // 获取管制区
        val mzToLocations = CUSTOM_CONFIG.mzToLoc
        mzToLocations.forEach {

          val zoneName = it.key
          val locations = it.value

          // SRD配置的管制区
          val zone = ZoneService.getZoneByName(zoneName)
          val zoneStatusDo = zone.getStatusDO("OK")
          val zoneMoreStatusDO = zone.getZoneMoreStatusDO()
          val robots = zoneMoreStatusDO.details["OK"]?.robots

//          // 获取当前欧凯AGV的位置
//          val area = FinsIoMemoryArea.valueOf(clientInfo.area.toByte()).get()
//          val loc: Int
//          when (area) {
//            FinsIoMemoryArea.DM_BIT,
//            FinsIoMemoryArea.AR_BIT,
//            FinsIoMemoryArea.HR_BIT,
//            FinsIoMemoryArea.WR_BIT,
//            FinsIoMemoryArea.CIO_BIT,
//            FinsIoMemoryArea.TASK_BIT,
//            FinsIoMemoryArea.TASK_STATUS,
//            FinsIoMemoryArea.TIMER_COUNTER_COMPLETION_FLAG,
//            FinsIoMemoryArea.CLOCK_PULSES_CONDITION_FLAGS_BIT -> {
//              throw BusinessError("invalid memory area $area")
////              val bits = finsMasterHelper.readBits(desNode, FinsIoAddress(area, clientInfo.readAddr, clientInfo.readOffset), clientInfo.count)
////              loc = bits[0].bitData.toInt()
//            }
//            FinsIoMemoryArea.AR_WORD,
//            FinsIoMemoryArea.DM_WORD,
//            FinsIoMemoryArea.HR_WORD,
//            FinsIoMemoryArea.WR_WORD,
//            FinsIoMemoryArea.CIO_WORD,
//            FinsIoMemoryArea.TIMER_COUNTER_PV,
//            FinsIoMemoryArea.DATA_REGISTER_PV -> {
//              loc = finsMasterHelper.readWord(desNode, FinsIoAddress(area, clientInfo.readAddr, clientInfo.readOffset)).toInt()
//            }
//            FinsIoMemoryArea.INDEX_REGISTER_PV -> throw BusinessError("不可用")
//            else -> throw BusinessError("unknown area code: ${clientInfo.area}")
//          }

          if (loc < 0) throw BusinessError("数据异常, $loc")
          if (locations.contains(loc)) {    // 欧凯的AGV正在管制区内
            when {
              zoneStatusDo.status > 0 -> {// 管制区被欧凯AGV占用
                if (robots != null && !robots.contains(name))
                  zone.enter(name, "OK", "$name enter $zoneName")
                logger.debug("OK AGV $robots is in $zoneName")
              }
              //              // 让AGV运行（不管它有没有停下来）
              //              finsMasterHelper.writeWord(desNode, FinsIoAddress(area, clientInfo.writeAddr, clientInfo.writeOffset), 0)
              zoneStatusDo.status < 0 -> // 我们的AGV在区域内
                logger.debug("Seer AGV is in $zoneName")
              //              // 让欧凯AGV停
              //              logger.debug("try to stop OK AGV name=$name")
              //              finsMasterHelper.writeWord(desNode, FinsIoAddress(area, clientInfo.writeAddr, clientInfo.writeOffset), 1)
              //              logger.debug("stop command has been sent")
              else -> {    // 管制区没有被占用
                logger.debug("OK AGV $name enter mutex ${zone.name}")
                zone.enter(name, "OK", "$name enter mutex")

                //              // 让AGV运行（不管它有没有停下来）
                //              logger.debug("try to start OK AGV name=$name")
                //              finsMasterHelper.writeWord(desNode, FinsIoAddress(area, clientInfo.writeAddr, clientInfo.writeOffset), 0)
                //              logger.debug("start command has been sent")
              }
            }
          } else {    // 欧凯的AGV不在管制区内
            if (zoneStatusDo.status > 0) {    // 管制区被欧凯AGV占用
              // 释放管制区
              if (robots != null && robots.contains(name)){
                logger.debug("OK AGV $name leave mutex ${zone.name}")
                zone.leave(name, "OK", "$name leave mutex")
              }
            } else {    // 不管

            }
          }
        }
      } catch (e: Exception) {
        logger.error("check mutex error desAddr=${this.desNode.node}", e)
      } finally {
        checking = false
      }
    }
  }

  fun dispose() {
    finsMasterHelper.disconnect()
  }
}