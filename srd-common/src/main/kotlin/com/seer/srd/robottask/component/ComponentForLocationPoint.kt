package com.seer.srd.robottask.component

import com.seer.srd.BusinessError
import com.seer.srd.SystemError
import com.seer.srd.route.service.PlantModelService
import com.seer.srd.route.service.VehicleService
import org.opentcs.components.kernel.Router
import org.opentcs.kernel.getInjector

val locationPointComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
    "locationPoint", "getCostByLocations", "返回按资源消耗升序的工作站数组", "计算AGV到工作站的cost",
    false, listOf(
    TaskComponentParam("name", "AGV名称", "string"),
    TaskComponentParam("from", "起始工作站", "string"),
    TaskComponentParam("locations", "目标工作站(多个用`,`隔开)", "string")
    ), true
    ) { component, ctx ->
      val name = parseComponentParamValue("name", component, ctx) as String
      val from = parseComponentParamValue("from", component, ctx) as String
      val locations = parseComponentParamValue("locations", component, ctx) as String
      val locList = locations.split(',')
      val vehicle = VehicleService.getVehicle(name)
      val injector = getInjector() ?: throw SystemError("No Injector")
      val router = injector.getInstance(Router::class.java)
      val costs = mutableMapOf<String, Long>()
      locList.forEach {
        costs[it] = router.getCosts(vehicle, from, it)
      }
      val leastCost = costs.toList().sortedBy { it.second }.map { it.first }
      ctx.setRuntimeVariable(component.returnName, leastCost)
      ctx.task.persistedVariables[component.returnName!!] = leastCost
    },
    TaskComponentDef(
    "locationPoint", "getCostByPoints", "返回按资源消耗升序的普通点数组", "计算AGV到点位的cost",
    false, listOf(
    TaskComponentParam("name", "AGV名称", "string"),
    TaskComponentParam("from", "普通起点", "string"),
    TaskComponentParam("points", "普通目标终点(多个用`,`隔开)", "string")
    ), true
    ) { component, ctx ->
      val name = parseComponentParamValue("name", component, ctx) as String
      val from = parseComponentParamValue("from", component, ctx) as String
      val points = parseComponentParamValue("points", component, ctx) as String
      val pointList = points.split(',')
      val vehicle = VehicleService.getVehicle(name)
      val injector = getInjector() ?: throw SystemError("No Injector")
      val router = injector.getInstance(Router::class.java)
      val costs = mutableMapOf<String, Long>()
      pointList.forEach {
        costs[it] = router.getCostsByPointRef(vehicle, from, it)
      }
      val leastCost = costs.toList().sortedBy { it.second }.map { it.first }
      ctx.setRuntimeVariable(component.returnName, leastCost)
      ctx.task.persistedVariables[component.returnName!!] = leastCost
    },
    TaskComponentDef(
    "locationPoint", "getPointsByLocation", "返回指定工作站连接的普通点名称数组", "",
    false, listOf(
    TaskComponentParam("location", "工作站", "string")
    ), true
    ) { component, ctx ->
      val location = parseComponentParamValue("location", component, ctx) as String
      val pm = PlantModelService.getPlantModel()
      val srcLinks = pm.locations[location]?.attachedLinks ?: throw BusinessError("No Links On $location")
      val points = srcLinks.map { it.point }
      ctx.setRuntimeVariable(component.returnName, points)
    },
    TaskComponentDef(
    "locationPoint", "getLocationsByPoint", "返回指定普通点连接的工作站名称数组", "",
    false, listOf(
    TaskComponentParam("point", "普通点名称", "string")
    ), true
    ) { component, ctx ->
      val pointName = parseComponentParamValue("point", component, ctx) as String
      val pm = PlantModelService.getPlantModel()
      val point = pm.points[pointName] ?: throw BusinessError("No Such Point Name $pointName")
      val locations = point.attachedLinks.map { it.location }
      ctx.setRuntimeVariable(component.returnName, locations)
    }
)