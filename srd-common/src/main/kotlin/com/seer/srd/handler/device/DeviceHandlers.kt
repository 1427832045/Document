package com.seer.srd.handler.device

import com.seer.srd.http.Handlers
import com.seer.srd.http.HttpServer.noAuth

fun registerDeviceHttpHandlers() {
    val lift = Handlers("lifts")
    lift.post(":name", LiftHandler::handleControlLift, noAuth())
    lift.get("", LiftHandler::handleListLiftsModels, noAuth())
    lift.get(":name", LiftHandler::handleGetLiftModel, noAuth())

    val locationDevice = Handlers("locationDevices")
    locationDevice.get(":name", ChargerHandler::handleControlCharger, noAuth())

    val charger = Handlers("chargers")
    charger.post("cancel-charge-by-order", ChargerHandler::handlerCancelChargeByOrder, noAuth())
    charger.post("cancel-charge-by-vehicle", ChargerHandler::handlerCancelChargeByVehicle, noAuth())
    charger.get("", ChargerHandler::handleListChargers, noAuth())
    charger.get(":name", ChargerHandler::handleGetChargerStatus, noAuth())
    
    val doors = Handlers("doors")
    doors.post(":name", DoorHandler::handleControlDoor, noAuth())
    doors.get("", DoorHandler::handleListDoors, noAuth())
    doors.get(":name", DoorHandler::handleGetDoor, noAuth())
    
    val zones = Handlers("mutexZones")
    zones.post(":name", ZoneHandler::handleControlZone, noAuth())
    zones.get("", ZoneHandler::handleGetAllStatus, noAuth())
    zones.get("details", ZoneHandler::handleGetAllDetails, noAuth())
    zones.get(":name", ZoneHandler::handleGetOneStatus, noAuth())

    val converter = Handlers("converter")
    converter.post("modbus-operation", ConverterHandler::handlerReadOrWriteModbusTcp, noAuth())
    converter.get("list-modbus-masters", ConverterHandler::handlerListModbusTcpMasters, noAuth())
    converter.post("tcp-socket-operation", ConverterHandler::handlerReadOrWriteTcpSocket, noAuth())
    converter.get("list-tcp-sockets", ConverterHandler::handlerListTcpSockets, noAuth())

    val pager = Handlers("pagers")
    pager.get("details", PagerHandler::handleListPagerDetails, noAuth())
    pager.get("details/:name", PagerHandler::handleGetPagerDetails, noAuth())
    pager.post("reset/:name", PagerHandler::handleResetPager, noAuth())
}