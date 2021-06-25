package com.seer.srd.io.modbus

import com.digitalpetri.modbus.master.ModbusTcpMaster
import com.digitalpetri.modbus.master.ModbusTcpMasterConfig
import com.digitalpetri.modbus.requests.*
import com.digitalpetri.modbus.responses.*
import com.seer.srd.eventlog.ModbusReadLog
import com.seer.srd.eventlog.ModbusWriteLog
import com.seer.srd.eventlog.recordModbusReadLog
import com.seer.srd.eventlog.recordModbusWriteLog
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ModbusTcpMasterHelper(
    private val host: String,
    private val port: Int
) {
    
    private val logger = LoggerFactory.getLogger(ModbusTcpMasterHelper::class.java)
    
    private var master: ModbusTcpMaster
    
    private val lastReadValues: MutableMap<String, String> = ConcurrentHashMap()
    
    init {
        val config = ModbusTcpMasterConfig
            .Builder(host).setPort(port)
            .setTimeout(Duration.ofSeconds(60))
            .build()
        master = ModbusTcpMaster(config)
    }
    
    fun connect() {
        master.connect()
    }
    
    fun disconnect() {
        try {
            master.disconnect()
        } catch (e: Exception) {
            logger.error("disconnect", e)
        }
    }
    
    fun read01Coils(address: Int, qty: Int, unitId: Int, remark: String): ByteBuf? {
        val req = ReadCoilsRequest(address, qty)
        val r = master.sendRequest<ReadCoilsResponse>(req, unitId)
        val data = r.get()?.coilStatus
        
        logReadIfChanged("01", data, address, qty, unitId, remark)
        
        return data
    }
    
    fun read02DiscreteInputs(address: Int, qty: Int, unitId: Int, remark: String): ByteBuf? {
        val req = ReadDiscreteInputsRequest(address, qty)
        val r = master.sendRequest<ReadDiscreteInputsResponse>(req, unitId)
        val data = r.get()?.inputStatus
        
        logReadIfChanged("02", data, address, qty, unitId, remark)
        
        return data
    }
    
    fun read03HoldingRegisters(address: Int, qty: Int, unitId: Int, remark: String): ByteBuf? {
        val req = ReadHoldingRegistersRequest(address, qty)
        val r = master.sendRequest<ReadHoldingRegistersResponse>(req, unitId)
        val data = r.get()?.registers
        
        logReadIfChanged("03", data, address, qty, unitId, remark)
        
        return data
    }
    
    fun read04InputRegisters(address: Int, qty: Int, unitId: Int, remark: String): ByteBuf? {
        val req = ReadInputRegistersRequest(address, qty)
        val r = master.sendRequest<ReadInputRegistersResponse>(req, unitId)
        val data = r.get()?.registers
        
        logReadIfChanged("04", data, address, qty, unitId, remark)
        
        return data
    }
    
    fun write05SingleCoil(address: Int, value: Boolean, unitId: Int, remark: String) {
        val req = WriteSingleCoilRequest(address, value)
        val r = master.sendRequest<WriteSingleCoilResponse>(req, unitId)
        r.get()
        
        logWrite("05", byteArrayOf(if (value) 1 else 0), address, 1, unitId, remark)
    }
    
    fun write06SingleRegister(address: Int, value: Int, unitId: Int, remark: String) {
        val req = WriteSingleRegisterRequest(address, value)
        val r = master.sendRequest<WriteSingleRegisterResponse>(req, unitId)
        r.get()
        
        logWrite("06", byteArrayOf(value.toByte()), address, 1, unitId, remark)
    }
    
    fun write0FMultipleCoils(address: Int, qty: Int, value: ByteArray, unitId: Int, remark: String) {
        val req = WriteMultipleCoilsRequest(address, qty, value)
        val r = master.sendRequest<WriteMultipleCoilsResponse>(req, unitId)
        r.get()
        
        logWrite("0F", value, address, qty, unitId, remark)
    }
    
    fun write10MultipleRegisters(address: Int, qty: Int, value: ByteArray, unitId: Int, remark: String) {
        val req = WriteMultipleRegistersRequest(address, qty, value)
        val r = master.sendRequest<WriteMultipleRegistersResponse>(req, unitId)
        r.get()
        
        logWrite("10", value, address, qty, unitId, remark)
    }
    
    private fun logReadIfChanged(cmd: String, data: ByteBuf?, address: Int, qty: Int, unitId: Int, remark: String) {
        val hexStr = if (data != null) ByteBufUtil.hexDump(data) else ""
        val target = "$unitId:$address+$qty"
        val old = lastReadValues[target] ?: ""
        if (old != hexStr) {
            lastReadValues[target] = hexStr
            recordModbusReadLog(ModbusReadLog(ObjectId(), Instant.now(), host, port, cmd, target, old, hexStr, remark))
        }
    }
    
    private fun logWrite(cmd: String, data: ByteArray, address: Int, qty: Int, unitId: Int, remark: String) {
        val hexStr = ByteBufUtil.hexDump(data)
        val target = "$unitId:$address+$qty"
        recordModbusWriteLog(ModbusWriteLog(ObjectId(), Instant.now(), host, port, cmd, target, hexStr, remark))
    }
}

//fun testModbusTcpMasterHelper() {
//    val executor = Executors.newSingleThreadScheduledExecutor()
//    val helper = ModbusTcpMasterHelper("localhost", 1502)
//    helper.connect()
//    executor.scheduleAtFixedRate({
//        helper.read01Coils(10, 3, 0, "test")
//    }, 1L, 1L, TimeUnit.SECONDS)
//}