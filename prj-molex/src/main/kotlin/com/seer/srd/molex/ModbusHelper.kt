package com.seer.srd.molex

import com.digitalpetri.modbus.master.ModbusTcpMaster
import com.digitalpetri.modbus.master.ModbusTcpMasterConfig
import com.digitalpetri.modbus.requests.*
import com.digitalpetri.modbus.responses.*
import com.seer.srd.BaseError
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import org.slf4j.LoggerFactory
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
      throw ModBusError("disconnect", e)
    }
  }

  @Throws(ModBusError::class)
  fun read01Coils(address: Int, qty: Int, unitId: Int, remark: String, log: Boolean = true): ByteBuf? {
    val req = ReadCoilsRequest(address, qty)
    val r = master.sendRequest<ReadCoilsResponse>(req, unitId)
    val data = r.get()?.coilStatus

    if (log) logReadIfChanged("01", data, address, qty, unitId, remark)

    return data
  }

  @Throws(ModBusError::class)
  fun read02DiscreteInputs(address: Int, qty: Int, unitId: Int, remark: String): ByteBuf? {
    val req = ReadDiscreteInputsRequest(address, qty)
    val r = master.sendRequest<ReadDiscreteInputsResponse>(req, unitId)
    val data = r.get()?.inputStatus

    logReadIfChanged("02", data, address, qty, unitId, remark)

    return data
  }

  @Throws(ModBusError::class)
  fun read03HoldingRegisters(address: Int, qty: Int, unitId: Int, remark: String): ByteBuf? {
    val req = ReadHoldingRegistersRequest(address, qty)
    val r = master.sendRequest<ReadHoldingRegistersResponse>(req, unitId)
    val data = r.get()?.registers

    logReadIfChanged("03", data, address, qty, unitId, remark)

    return data
  }

  @Throws(ModBusError::class)
  fun read04InputRegisters(address: Int, qty: Int, unitId: Int, remark: String): ByteBuf? {
    val req = ReadInputRegistersRequest(address, qty)
    val r = master.sendRequest<ReadInputRegistersResponse>(req, unitId)
    val data = r.get()?.registers

    logReadIfChanged("04", data, address, qty, unitId, remark)

    return data
  }

  @Throws(ModBusError::class)
  fun write05SingleCoil(address: Int, value: Boolean, unitId: Int, remark: String, log: Boolean = true) {
    val req = WriteSingleCoilRequest(address, value)
    val r = master.sendRequest<WriteSingleCoilResponse>(req, unitId)
    r.get()

    if (log) logWrite("05", byteArrayOf(if (value) 1 else 0), address, 1, unitId, remark)
  }

  @Throws(ModBusError::class)
  fun write06SingleRegister(address: Int, value: Int, unitId: Int, remark: String) {
    val req = WriteSingleRegisterRequest(address, value)
    val r = master.sendRequest<WriteSingleRegisterResponse>(req, unitId)
    r.get()

    logWrite("06", byteArrayOf(value.toByte()), address, 1, unitId, remark)
  }

  @Throws(ModBusError::class)
  fun write0FMultipleCoils(address: Int, qty: Int, value: ByteArray, unitId: Int, remark: String,  log: Boolean = true) {
    val req = WriteMultipleCoilsRequest(address, qty, value)
    val r = master.sendRequest<WriteMultipleCoilsResponse>(req, unitId)
    r.get()

    if (log) logWrite("0F", value, address, qty, unitId, remark)
  }

  @Throws(ModBusError::class)
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
      logger.debug("read: $host:$port, cmd=$cmd, unitId:address+qty=$target, oldValue=$old, newValue=$hexStr, $remark")
//            recordModbusReadLog(ModbusReadLog(ObjectId(), Instant.now(), host, port, cmd, target, old, hexStr, remark))
    }
  }

  private fun logWrite(cmd: String, data: ByteArray, address: Int, qty: Int, unitId: Int, remark: String) {
    val hexStr = ByteBufUtil.hexDump(data)
    val target = "$unitId:$address+$qty"
    logger.debug("write: $host:$port, $cmd, $target, $hexStr, $remark")
//        recordModbusWriteLog(ModbusWriteLog(ObjectId(), Instant.now(), host, port, cmd, target, hexStr, remark))
  }
}

open class ModBusError(message: String?, cause: Throwable? = null) : BaseError(message, cause)