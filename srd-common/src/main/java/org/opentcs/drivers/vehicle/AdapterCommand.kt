package org.opentcs.drivers.vehicle

import com.seer.srd.vehicle.driver.AbstractVehicleCommAdapter
import java.io.Serializable

// 在哪里使用？
interface AdapterCommand : Serializable {
    fun execute(adapter: AbstractVehicleCommAdapter)
}