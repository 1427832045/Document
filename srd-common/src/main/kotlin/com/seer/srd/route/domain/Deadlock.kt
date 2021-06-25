package com.seer.srd.route.domain

import com.seer.srd.vehicle.Vehicle
import java.time.Instant

data class Deadlock(
    val foundTime: Instant,
    val lockedVehicles: List<Vehicle>, // TODO Vehicle对象和String哪个更好？
    val edges: List<DGEdge>
)