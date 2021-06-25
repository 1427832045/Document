package com.seer.srd

import com.seer.srd.flex.FlexDirectiveConfig
import com.seer.srd.flex.FlexProcedure
import com.seer.srd.flex.FlexProcedureDef
import com.seer.srd.flex.FlexProcedureManager
import org.bson.types.ObjectId

fun test() {
    val def = FlexProcedureDef(
        ObjectId(), "TestProcedure", "测试用例程",
        FlexDirectiveConfig(
            "RepeatNumExecution", mapOf("num" to "5"), "", listOf(
                FlexDirectiveConfig(
                    "SequentialExecution", emptyMap(), "", listOf(
                        FlexDirectiveConfig("PrintExecution", mapOf("message" to "Good Day")),
                        FlexDirectiveConfig("DelayExecution", mapOf("timeMillis" to "2000"))
                    )
                )
            )
        )
    )
    FlexProcedureManager.defs[def.id] = def

    val fp = FlexProcedure(ObjectId().toHexString(), def.id, emptyMap(), emptySet())
    FlexProcedureManager.newFlexProcedure(fp)
}