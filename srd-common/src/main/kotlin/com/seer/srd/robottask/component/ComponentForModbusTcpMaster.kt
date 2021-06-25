@file:Suppress("BooleanLiteralArgument")

package com.seer.srd.robottask.component

import com.seer.srd.BusinessError
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val helpers: MutableMap<String, ModbusTcpMasterHelper> = ConcurrentHashMap()

private val LOG = LoggerFactory.getLogger("com.seer.srd.robottask")

val modbusTcpMasterComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
        "modbusTcp", "ReadSingleValue", "读取一个地址的数据", "",
        false, listOf(
        TaskComponentParam("addrType", "地址类型(0x, 1x, 3x, 4x)", "string"),
        TaskComponentParam("host", "Host", "string"),
        TaskComponentParam("port", "端口号", "int"),
        TaskComponentParam("addrNo", "地址编号", "int"),
        TaskComponentParam("unitId", "从站ID", "int"),
        TaskComponentParam("alias", "地址说明", "string")
    ), true) { component, ctx ->
        val addrType = parseComponentParamValue("addrType", component, ctx) as String
        val host = parseComponentParamValue("host", component, ctx) as String
        val port = parseComponentParamValue("port", component, ctx) as Int
        val addrNo = parseComponentParamValue("addrNo", component, ctx) as Int
        val unitId = parseComponentParamValue("unitId", component, ctx) as Int
        val alias = parseComponentParamValue("alias", component, ctx) as String
        val remark = "component[${ctx.task.id}] | $alias"
        val result = readSingleValue(addrType, host, port, unitId, addrNo, remark)
        ctx.setRuntimeVariable(component.returnName, result)
    },

    TaskComponentDef(
        "modbusTcp", "WriteSingleValue", "修改一个地址的数据", "",
        false, listOf(
        TaskComponentParam("addrType", "地址类型(0x, 4x)", "string"),
        TaskComponentParam("newValue", "写入值", "int"),
        TaskComponentParam("host", "Host", "string"),
        TaskComponentParam("port", "端口号", "int"),
        TaskComponentParam("addrNo", "地址编号", "int"),
        TaskComponentParam("unitId", "从站ID", "int"),
        TaskComponentParam("alias", "地址说明", "string")
    ), false) { component, ctx ->
        val addrType = parseComponentParamValue("addrType", component, ctx) as String
        val newValue = parseComponentParamValue("newValue", component, ctx) as Int
        val host = parseComponentParamValue("host", component, ctx) as String
        val port = parseComponentParamValue("port", component, ctx) as Int
        val addrNo = parseComponentParamValue("addrNo", component, ctx) as Int
        val unitId = parseComponentParamValue("unitId", component, ctx) as Int
        val alias = parseComponentParamValue("alias", component, ctx) as String
        val remark = "component[${ctx.task.id}] | $alias"
        writeSingleValue(addrType, newValue, host, port, unitId, addrNo, remark)
    }
)

fun getHelper(host: String, port: Int): ModbusTcpMasterHelper {
    val key = "$host:$port"
    val helper = helpers[key]
    return if (helper != null) helper else {
        val newHelper = ModbusTcpMasterHelper(host, port)
        helpers[key] = newHelper
        newHelper
    }
}

private fun readSingleValue(type: String, host: String, port: Int, unitId: Int, addrNo: Int, remark: String): Int {
    val optDetails = "addrType=$type, $host:$port, addrNo=$addrNo, ID=$unitId, remark=$remark"
    try {
        val helper = getHelper(host, port)
        val rawValue = when (type) {
            "0x" -> helper.read01Coils(addrNo, 1, unitId, remark)
            "1x" -> helper.read02DiscreteInputs(addrNo, 1, unitId, remark)
            "3x" -> helper.read04InputRegisters(addrNo, 1, unitId, remark)
            "4x" -> helper.read03HoldingRegisters(addrNo, 1, unitId, remark)
            else -> throw BusinessError("无法识别的地址类型($type)")
        } ?: throw BusinessError("读取数据失败【$optDetails】,结果不能为空！")

        val result =
            if (listOf("0x", "1x").contains(type)) rawValue.getByte(0).toInt() % 2
            else rawValue.getShort(0).toInt()
        LOG.debug("read single value=$result, $optDetails")
        return result
    } catch (e: Exception) {
        LOG.error("read single value Failed, $optDetails", e)
        throw e
    }
}

private fun writeSingleValue(type: String, newValue: Int, host: String, port: Int, unitId: Int, addrNo: Int, remark: String) {
    val optDetails = "addrType=$type, newValue=$newValue $host:$port, addrNo=$addrNo, ID=$unitId, remark=$remark"
    LOG.debug("write single value, $optDetails")
    try {
        val helper = getHelper(host, port)
        when (type) {
            "0x" -> {
                val value = when (newValue) {
                    0 -> false
                    1 -> true
                    else -> throw BusinessError("非法数据（$newValue），写入【0x】的值职能是 0 或 1！")
                }
                helper.write05SingleCoil(addrNo, value, unitId, remark)
            }
            "4x" -> helper.write06SingleRegister(addrNo, newValue, unitId, remark)
            "1x", "3x" -> throw BusinessError("类型为【$type】的地址只支持读操作")
            else -> throw BusinessError("无法识别的地址类型($type)")
        }
    } catch (e: Exception) {
        LOG.error("write single value Failed, $optDetails", e)
        throw e
    }
}