package com.seer.srd.runhua

import com.seer.srd.BusinessError
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.runhua.RunHuaApp.helpers
import com.seer.srd.scheduler.backgroundCacheExecutor
import org.slf4j.LoggerFactory

object ExtraComponent {

  private val logger = LoggerFactory.getLogger(ExtraComponent::class.java)

  val extraComponent = listOf(
      TaskComponentDef(
          "extra", "runHua:onPositionFrom", "到达起点", "", false, listOf(

      ), false) {_, ctx ->
        val from = ctx.task.persistedVariables["from"] as String
        logger.debug("onPositionFrom $from")

        val plc = ctx.task.persistedVariables["plc"] as String
        val helper = helpers[plc] ?: throw BusinessError("no such config $plc")
        when (from) {
          "A" -> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.onPositionFromA, true, 1, "arrive A")
          "B" -> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.onPositionFromB, true, 1, "arrive B")
          "C" -> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.onPositionFromC, true, 1, "arrive C")
          "D" -> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.onPositionFromD, true, 1, "arrive D")
          else -> throw BusinessError("No such site $from")
        }
      },
      TaskComponentDef(
          "extra", "runHua:canLeave", "起点：检查是否可放行", "", false, listOf(
      ), false) {_, ctx ->
        val from = ctx.task.persistedVariables["from"] as String
        logger.debug("check can leave: $from")

        val plc = ctx.task.persistedVariables["plc"] as String
        val helper = helpers[plc] ?: throw BusinessError("no such config $plc")
        val canLeave = when (from) {
          "A" -> helper.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.canLeaveA, 1, 1, "read leave A")?.getByte(0)?.toInt()
          "B" -> helper.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.canLeaveB, 1, 1, "read leave B")?.getByte(0)?.toInt()
          "C" -> helper.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.canLeaveC, 1, 1, "read leave C")?.getByte(0)?.toInt()
          "D" -> helper.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.canLeaveD, 1, 1, "read leave D")?.getByte(0)?.toInt()
          else -> throw BusinessError("No such site $from")
        }
        if (canLeave != 1) throw BusinessError("from=${from}不可放行")
        Thread.sleep(200)
        when (from) {
          "A" -> helper.write0FMultipleCoils(CUSTOM_CONFIG.plcAddress.calledA, 2, byteArrayOf(0), 1, "reset from A")
          "B" -> helper.write0FMultipleCoils(CUSTOM_CONFIG.plcAddress.calledB, 2, byteArrayOf(0), 1, "reset from A")
          "C" -> helper.write0FMultipleCoils(CUSTOM_CONFIG.plcAddress.calledC, 2, byteArrayOf(0), 1, "reset from A")
          "D" -> helper.write0FMultipleCoils(CUSTOM_CONFIG.plcAddress.calledD, 2, byteArrayOf(0), 1, "reset from A")
        }
      },
      TaskComponentDef(
          "extra", "runHua:onPositionTo", "到达终点", "", false, listOf(
      ), false) {_, ctx ->
        val from = ctx.task.persistedVariables["from"] as String
        logger.debug("from=$from, on position to the terminal site")

        val plc = ctx.task.persistedVariables["plc"] as String
        val helper = helpers[plc] ?: throw BusinessError("no such config $plc")
        when (from) {
          "A" ,"B", "C"-> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.onPositionToA, true, 1, "arrive terminal A")
          "D" -> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.onPositionToD, true, 1, "arrive terminal D")
          else -> throw BusinessError("No such site $from")
        }
      },
      TaskComponentDef(
          "extra", "runHua:canEnd", "终点：检查是否可放行", "", false, listOf(
      ), false) {_, ctx ->
        val from = ctx.task.persistedVariables["from"] as String
        logger.debug("check can end, from=$from")

        val plc = ctx.task.persistedVariables["plc"] as String
        val helper = helpers[plc] ?: throw BusinessError("no such config $plc")
        val canLeave = when (from) {
          "A", "B", "C" -> helper.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.canEndA, 1, 1, "read end A")?.getByte(0)?.toInt()
          "D" -> helper.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.canEndD, 1, 1, "read end D")?.getByte(0)?.toInt()
          else -> throw BusinessError("No such site $from")
        }
        if (canLeave != 1) throw BusinessError("from=${from}不可放行")
        Thread.sleep(200)
        when (from) {
          "A", "B", "C" -> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.onPositionToA, false, 1, "reset to A")
          "D" -> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.onPositionToD, false, 1, "reset to D")
        }
      }
//      TaskComponentDef(
//          "extra", "runHua:canLoad", "检查是否可装载", "", false, listOf(
//      ), false) {_, ctx ->
//        val from = ctx.task.persistedVariables["from"] as String
//        logger.debug("check can load: $from")
//
//        val plc = ctx.task.persistedVariables["plc"] as String
//        val helper = helpers[plc] ?: throw BusinessError("no such config $plc")
//        val canLoad = when (from) {
//          "A" -> helper.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.canLoadA, 1, 1, "read load A")?.getByte(0)?.toInt()
//          "B" -> helper.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.canLoadB, 1, 1, "read load B")?.getByte(0)?.toInt()
//          "C" -> helper.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.canLoadC, 1, 1, "read load C")?.getByte(0)?.toInt()
//          "D" -> helper.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.canLoadD, 1, 1, "read load D")?.getByte(0)?.toInt()
//          else -> throw BusinessError("No such site $from")
//        }
//        if (canLoad != 1) throw BusinessError("from=${from}不可装载")
//      },
//      TaskComponentDef(
//          "extra", "runHua:load", "开始装载", "", false, listOf(
//      ), false) {_, ctx ->
//        val from = ctx.task.persistedVariables["from"] as String
//        logger.debug("start loading, from=$from")
//
//        val plc = ctx.task.persistedVariables["plc"] as String
//        val helper = helpers[plc] ?: throw BusinessError("no such config $plc")
//        when (from) {
//          "A" -> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.loadA, true, 1, "write load A")
//          "B" -> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.loadB, true, 1, "write load B")
//          "C" -> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.loadC, true, 1, "write load C")
//          "D" -> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.loadD, true, 1, "write load D")
//          else -> throw BusinessError("No such site $from")
//        }
//      },
//      TaskComponentDef(
//          "extra", "runHua:loaded", "装载完成", "", false, listOf(
//      ), false) { _, ctx ->
//        val from = ctx.task.persistedVariables["from"] as String
//        logger.debug("loaded, from=$from")
//
//        val plc = ctx.task.persistedVariables["plc"] as String
//        val helper = helpers[plc] ?: throw BusinessError("no such config $plc")
//        when (from) {
//          "A" -> {
//            helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.loadedA, true, 1, "write loaded A")
//            // 把呼叫信号复位
////            Thread.sleep(200)
////            helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.calledA, false, 1, "reset called A")
//          }
//          "B" -> {
//            helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.loadedB, true, 1, "write loaded B")
//            // 把呼叫信号复位
////            Thread.sleep(200)
////            helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.calledB, false, 1, "reset called B")
//          }
//          "C" -> {
//            helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.loadedC, true, 1, "write loaded C")
//            // 把呼叫信号复位
////            Thread.sleep(200)
////            helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.calledC, false, 1, "reset called C")
//          }
//          "D" -> {
//            helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.loadedD, true, 1, "write loaded D")
//            // 把呼叫信号复位
////            Thread.sleep(200)
////            helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.calledD, false, 1, "reset called D")
//          }
//          else -> throw BusinessError("No such site $from")
//        }
//      },
//      TaskComponentDef(
//          "extra", "runHua:canUnload", "检查是否可卸载", "", false, listOf(
//      ), false) {_, ctx ->
//        val from = ctx.task.persistedVariables["from"] as String
//        logger.debug("check can unload, from=$from")
//
//        val plc = ctx.task.persistedVariables["plc"] as String
//        val helper = helpers[plc] ?: throw BusinessError("no such config $plc")
//        val canUnload = when (from) {
//          "A", "B", "C" -> helper.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.canUnloadA, 1, 1, "read unload A")?.getByte(0)?.toInt()
////          "B" -> helper.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.canUnloadA, 1, 1, "read unload A")?.getByte(0)?.toInt()
////          "C" -> helper.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.canUnloadA, 1, 1, "read unload A")?.getByte(0)?.toInt()
//          "D" -> helper.read02DiscreteInputs(CUSTOM_CONFIG.plcAddress.canUnloadD, 1, 1, "read unload D")?.getByte(0)?.toInt()
//          else -> throw BusinessError("No such site $from")
//        }
//        if (canUnload != 1) throw BusinessError("from=${from}不可卸载")
//      },
//      TaskComponentDef(
//          "extra", "runHua:unloaded", "卸载完成", "", false, listOf(
//      ), false) { _, ctx ->
//        val from = ctx.task.persistedVariables["from"] as String
//        logger.debug("unloaded, from=$from")
//
//        val plc = ctx.task.persistedVariables["plc"] as String
//        val helper = helpers[plc] ?: throw BusinessError("no such config $plc")
//        when (from) {
//          "A", "B", "C" -> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.unloadedA, true, 1, "write unloaded A")
////          "B" -> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.unloadedA, true, 1, "write unloaded A")
////          "C" -> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.unloadedA, true, 1, "write unloaded A")
//          "D" -> helper.write05SingleCoil(CUSTOM_CONFIG.plcAddress.unloadedD, true, 1, "write unloaded D")
//          else -> throw BusinessError("No such site $from")
//        }
//      }
  )
}