package com.seer.srd.handler.route

import com.seer.srd.handler.SystemHandler
import com.seer.srd.http.Handlers
import com.seer.srd.http.HttpServer.noAuth

fun registerRouteHttpHandlers() {
    val r = Handlers("route")
    r.get("version", SystemHandler::handleGetSystemVersions, noAuth())
    r.get("status", ::handleRouteGetStatus, noAuth())
    r.delete("kernel", ::handleRouteDeleteKernel, noAuth())

    r.get("events", ::handleRouteGetEvents, noAuth())

    r.get("plantModel", ::handleRouteGetPlantModel, noAuth())
    r.post("plantModel", ::handleRoutePostPlantModel, noAuth())

    r.put("paths/:name/lock", ::handleLockPath, noAuth())

    val v = Handlers("route/vehicles")
    v.get("", ::handleGetVehicles, noAuth())
    v.get(":name", ::handleGetVehicleStateByName, noAuth())
    v.post("pause", ::handlePauseAllVehicles, noAuth())
    v.post(":name/pause", ::handlePauseVehicleByName, noAuth())
    v.post(":name/withdrawal", ::handleWithdrawalByVehicle, noAuth())
    v.put("integrationLevel", ::handleSetAllVehiclesIntegrationLevel, noAuth())
    v.put(":name/integrationLevel", ::handleSetVehicleIntegrationLevel, noAuth())
    v.put(":name/processableCategories", ::handleSetProcessableCategories, noAuth())
    v.put(":name/energyLevelGood", ::handleSetEnergyLevelGood, noAuth())
    v.put(":name/energyLevelCritical", ::handleSetEnergyLevelCritical, noAuth())
    v.put(":name/energyLevelFullyRecharged", ::handleSetEnergyLevelFRC, noAuth())
    v.put(":name/energyLevelSufficientlyRecharged", ::handleSetEnergyLevelSRC, noAuth())

    val d = Handlers("route/vehicleDetails")
    d.get("", ::handleGetVehicleDetails, noAuth())
    d.get(":name", ::handleGetVehicleDetailsByName, noAuth())

    val t = Handlers("route/transportOrders")
    t.get("", ::handleListTransportOrders, noAuth())
    t.get(":name", ::handleGetTransportOrderByName, noAuth())
    t.post(":name", ::handleCreateTransportOrder, noAuth())
    t.post(":name/withdrawal", ::handleWithdrawTransportOrder, noAuth())
    t.put(":name/deadline", ::handleChangeOrderDeadline, noAuth())

    val s = Handlers("route/orderSequences")
    s.post(":name", ::handleCreateOrderSequence, noAuth())
    s.post(":name/markComplete", ::handleMarkOrderSequenceComplete, noAuth())
    s.post(":name/withdrawal", ::handleWithdrawalSequenceByName, noAuth())
    s.get(":name", ::handleGetOrderSequenceByName, noAuth())
    s.get("", ::handleListOrderSequences, noAuth())

    r.get("historyTransportOrders", ::handleListHistoryTransportOrders, noAuth())

    r.get("statistics", ::handleGetRouteStats, noAuth())

    r.put("objects/:name/property", ::handleSetObjectProperty, noAuth())
    r.get("objects/:name/properties", ::handleGetObjectProperties, noAuth())
}