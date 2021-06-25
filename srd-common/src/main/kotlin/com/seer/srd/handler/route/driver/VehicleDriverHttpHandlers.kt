package com.seer.srd.handler.route.driver

import com.seer.srd.http.Handlers
import com.seer.srd.http.HttpServer.noAuth

fun registerVehicleDriverHttpHandlers() {
    val r = Handlers("vehicle/driver")
    r.post(":name/sign-in", ::handleVehicleSignIn, noAuth())
    r.put(":name/status", ::handleVehicleReportStatus, noAuth())
    r.put(":name/details", ::handleVehicleReportDetails, noAuth())

    val s = Handlers("vehicle/http-simulation")
    s.post(":name/movements", ::handleMovementCommands, noAuth())
    s.put(":name/pause", ::handlePauseVehicle, noAuth())
    s.put(":name/resume", ::handleResumeVehicle, noAuth())
    s.post(":name/clear-movements", ::handleClearAllMovementCommands, noAuth())
}