package com.seer.srd.lpsU.ur

import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.lpsU.CUSTOM_CONFIG
import com.seer.srd.lpsU.SITE_NUM_ON_COLUMN_CAR

object UrModbusService {
    
    val helper: ModbusTcpMasterHelper = ModbusTcpMasterHelper(CUSTOM_CONFIG.urModbusHost, CUSTOM_CONFIG.urModbusPort)

    init {
        helper.connect()
    }
    
    fun siteToModbusAddress(siteId: String): Int {
        val base = if (siteId[siteId.length - 3] == 'A') 0 else SITE_NUM_ON_COLUMN_CAR
        return base + Character.getNumericValue(siteId[siteId.length- 1] )
    }

    fun modbusAddressToSite(addr: Int): Int {
        return if (addr < 7) addr else addr - 6
    }
    
}