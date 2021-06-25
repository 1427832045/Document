package com.seer.srd.lps

import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue

val extraComponents = listOf(
    TaskComponentDef(
        "extra", "updateSites", "更新库位", "", false, listOf(
    ), false) {_, ctx ->

//        todo 使用OCR识别就务必删掉下面这行，这里是因为现在相机不能用，所以要暂时把识别的数据都删掉
//        UrTcpServer.siteToMagMap.clear()

        val carNum = ctx.task.persistedVariables["carNum"] as String
        val location = ctx.transport?.stages!![1].location
        val curColumn = location[location.length - 1].toString()

//        Services.saveOcrDataAndSetError(carNum, curColumn)
    },
    TaskComponentDef(
    "extra", "urge-reset", "一键复位", "", false, listOf(
    ), false) { _, _ ->
        Services.resetAll()
    },
    TaskComponentDef(
        "extra", "path", "到料车的路线和位置", "根据列推断路线", false, listOf(
        TaskComponentParam("column", "列", "string")
    ), false) { component, ctx ->
        val carNum = ctx.task.persistedVariables["carNum"] as String? ?: throw IllegalArgumentException("No such param carNum")
        val column = parseComponentParamValue("column", component, ctx) as String? ?: ""
        Services.setPathToCar(carNum, column)
    },
    TaskComponentDef(
        "extra", "path2", "到机台的路线", "", false, listOf(
        TaskComponentParam("value", "上料(1)/下料(2)", "int")
    ), false) { component, ctx ->
        val lineId = ctx.task.persistedVariables["lineId"] as String
        val upOrDown = parseComponentParamValue("value", component, ctx) as Int
        Services.setPathToLine(lineId, upOrDown)
    },

    TaskComponentDef(
        "extra", "takeFromUpCar", "从上料车取料", "", false, listOf(
    ), false) { _, ctx ->
      val sites = ctx.task.persistedVariables["neededSites"] as List<String>
      val carNum = ctx.task.persistedVariables["carNum"] as String
      Services.takeFromCar2(sites, carNum, ctx.task)
    },
    TaskComponentDef(
        "extra", "putOnline", "往机台放料", "", false, listOf(
    ), false) { _, ctx ->
      val lineId = ctx.task.workStations[0]
      Services.putOnLine2(lineId, ctx.task)
    },

    TaskComponentDef(
        "extra", "takeFromLine", "从机台取料", "", false, listOf(
        TaskComponentParam("num", "数量", "int")
    ), false) { component, ctx ->
      val lineId = ctx.task.persistedVariables["lineId"] as String
      val num = if (CUSTOM_CONFIG.downSize != 0) CUSTOM_CONFIG.downSize
      else parseComponentParamValue("num", component, ctx) as Int
      Services.takeFromLine2(lineId, num, ctx.task)
    },

    TaskComponentDef(
        "extra", "lp1:takeFromLine", "变更:从机台取料", "", false, listOf(
    ), false) { _, ctx ->
      val lineId = ctx.task.persistedVariables["lineId"] as String
      val task = ctx.task
      Services.takeFromLine(task, lineId)
    },

    TaskComponentDef(
        "extra", "putOnCar", "往料车放料", "", false, listOf(
    ), false) { _, ctx ->
        val lineId = ctx.task.persistedVariables["lineId"] as String
        val column = if (lineId == "line11") "A" else "B"
        val carNum = ctx.task.persistedVariables["carNum"] as String
        val neededSites = ctx.task.persistedVariables["neededSites"] as List<String>
        Services.putOnCar2(lineId, carNum, column, neededSites, ctx.task)
    },
    TaskComponentDef(
        "extra", "canPutOnMachine", "检查是否可以往机台放料", "", false, listOf(
    ), false) { _, ctx ->
      Services.checkLineUp(ctx)
    },

    TaskComponentDef(
        "extra", "canTakeFromLine", "检查是否可以从机台取料", "", false, listOf(
    ), false) { _, ctx ->
        Services.checkLineDown(ctx)
    },

    TaskComponentDef(
        "extra", "canPutOnCar", "检查是否可以往空料车放料", "", false, listOf(
    ), false) { _, ctx ->
        Services.checkDownCar(ctx)
    },

    TaskComponentDef(
        "extra", "getData", "OCR获取数据", "", false, listOf(
    ), false) { _, _ ->
        Services.getOcrData()
    }

    //    TaskComponentDef(
//        "extra", "location", "位置", "A侧/B侧(不用)", false, listOf(
//    ), false) { component, ctx ->
//        val value = parseComponentParamValue("value", component, ctx) as Int
//        helper.write06SingleRegister(168, value, 0, "位置")
//    },

//    TaskComponentDef(
//        "extra", "lockNeededSite", "锁定使用的库位", "上下料通用", false, listOf(
//    ), true) { _, ctx ->
//        ctx.setRuntimeVariable("neededSites", listOf("A1", "A2"))
//        val sites = ctx.task.persistedVariables["neededSites"] as List<StoreSite>
//        sites.forEach {
//            StoreSiteService.changeSiteLocked(it.id, true, "")
//        }
//    }
)