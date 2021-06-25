package com.seer.srd.wangao

import com.mongodb.client.model.UpdateOptions
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.wangao.WanGaoApp.lastAgvSignal
import com.seer.srd.wangao.WanGaoApp.modBusHelper
import org.litote.kmongo.exists
import org.litote.kmongo.set
import org.litote.kmongo.setTo
import org.slf4j.LoggerFactory
import java.lang.Exception

object ExtraComponents {

  private val logger = LoggerFactory.getLogger(this::class.java)

  val extraComponents: List<TaskComponentDef> = listOf(
      TaskComponentDef(
          "extra","wangao:wait", "等待下料口空", "", false, listOf(
      ), false) {_, _ ->
        val v = modBusHelper.read03HoldingRegisters(CUSTOM_CONFIG.deviceAddr, 1, 1, "读取设备状态")?.getShort(0)?.toInt()
        val error = modBusHelper.read03HoldingRegisters(CUSTOM_CONFIG.deviceErrorAddr, 1, 1, "读取设备异常状态")?.getShort(0)?.toInt()
        if (error == 1) throw BusinessError("设备报错")
        if (v != 1)throw BusinessError("等待平台无料信号")
        if (error != 0 || v != 1) throw BusinessError("设备状态%MW0=$v,%MW1=$error")
      },
      TaskComponentDef(
          "extra","wangao:go", "开往下料点", "", false, listOf(
      ), false) {_, _ ->
        try {
          modBusHelper.write10MultipleRegisters(CUSTOM_CONFIG.agvAddr, 1, byteArrayOf(0, 1), 1, "前往下料点")

          // 持久化lastAgvSignal
          lastAgvSignal = 1
          MongoDBManager.collection<AgvInfo>().updateOne(
              AgvInfo::id exists true, set(AgvInfo::lastSignal setTo lastAgvSignal),
              UpdateOptions().upsert(true))
          logger.debug("set lastAgvSignal=$lastAgvSignal")
        } catch (e: Exception) {
          logger.error("AGV写信号失败", e)
          throw BusinessError("AGV写信号失败", e)
        }
      },
      TaskComponentDef(
          "extra","wangao:arrived", "到达下料点", "", false, listOf(
      ), false) {_, _ ->
        try {
          modBusHelper.write10MultipleRegisters(CUSTOM_CONFIG.agvAddr, 1, byteArrayOf(0, 2), 1, "到达下料点")
          // 持久化lastAgvSignal
          lastAgvSignal = 2
          MongoDBManager.collection<AgvInfo>().updateOne(
              AgvInfo::id exists true, set(AgvInfo::lastSignal setTo lastAgvSignal),
              UpdateOptions().upsert(true))
          logger.debug("set lastAgvSignal=$lastAgvSignal")
        } catch (e: Exception) {
          logger.error("AGV写信号失败", e)
          throw BusinessError("AGV写信号失败", e)
        }
      },
      TaskComponentDef(
          "extra","wangao:waitUnload", "等待下料命令", "", false, listOf(
      ), false) {_, _ ->
        val v = modBusHelper.read03HoldingRegisters(CUSTOM_CONFIG.deviceAddr, 1, 1, "读取设备状态")?.getShort(0)?.toInt()
        val error = modBusHelper.read03HoldingRegisters(CUSTOM_CONFIG.deviceErrorAddr, 1, 1, "读取设备异常状态")?.getShort(0)?.toInt()
        if (error == 1) throw BusinessError("设备报错")
        if (v != 2)throw BusinessError("等待平台下料信号")
        if (error != 0 || v != 2) throw BusinessError("设备状态%MW0=$v,%MW1=$error")
      },
      TaskComponentDef(
          "extra","wangao:unload", "下料", "", false, listOf(
      ), false) {_, _ ->
        try {
          modBusHelper.write10MultipleRegisters(CUSTOM_CONFIG.agvAddr, 1, byteArrayOf(0, 3), 1, "开始下料")
          // 持久化lastAgvSignal
          lastAgvSignal = 3
          MongoDBManager.collection<AgvInfo>().updateOne(
              AgvInfo::id exists true, set(AgvInfo::lastSignal setTo lastAgvSignal),
              UpdateOptions().upsert(true))
          logger.debug("set lastAgvSignal=$lastAgvSignal")
        } catch (e: Exception) {
          logger.error("AGV写信号失败", e)
          throw BusinessError("AGV写信号失败", e)
        }
      },
      TaskComponentDef(
          "extra","wangao:unloaded", "下料完成", "", false, listOf(
      ), false) {_, _ ->
        try {
          modBusHelper.write10MultipleRegisters(CUSTOM_CONFIG.agvAddr, 1, byteArrayOf(0, 4), 1, "下料完成")
          // 持久化lastAgvSignal
          lastAgvSignal = 4
          MongoDBManager.collection<AgvInfo>().updateOne(
              AgvInfo::id exists true, set(AgvInfo::lastSignal setTo lastAgvSignal),
              UpdateOptions().upsert(true))
          logger.debug("set lastAgvSignal=$lastAgvSignal")
        } catch (e: Exception) {
          logger.error("AGV写信号失败", e)
          throw BusinessError("AGV写信号失败", e)
        }
      },
      TaskComponentDef(
          "extra","wangao:waitDeviceFinished", "等待接料完成", "", false, listOf(
      ), false) {_, _ ->
        val v = modBusHelper.read03HoldingRegisters(CUSTOM_CONFIG.deviceAddr, 1, 1, "读取设备状态")?.getShort(0)?.toInt()
        val error = modBusHelper.read03HoldingRegisters(CUSTOM_CONFIG.deviceErrorAddr, 1, 1, "读取设备异常状态")?.getShort(0)?.toInt()
        if (error == 1) throw BusinessError("设备报错")
        if (v != 3)throw BusinessError("等待平台接收完成信号")
        if (error != 0 || v != 3) throw BusinessError("设备状态%MW0=$v,%MW1=$error")
      },
      TaskComponentDef(
          "extra","wangao:leave", "", "", false, listOf(
      ), false) {_, _ ->
        try {
          modBusHelper.write10MultipleRegisters(CUSTOM_CONFIG.agvAddr, 1, byteArrayOf(0, 5), 1, "任务完成")
          // 持久化lastAgvSignal
          lastAgvSignal = 5
          MongoDBManager.collection<AgvInfo>().updateOne(
              AgvInfo::id exists true, set(AgvInfo::lastSignal setTo lastAgvSignal),
              UpdateOptions().upsert(true))
          logger.debug("set lastAgvSignal=$lastAgvSignal")
        } catch (e: Exception) {
          logger.error("AGV写信号失败", e)
          throw BusinessError("AGV写信号失败", e)
        }
      }
  )
}