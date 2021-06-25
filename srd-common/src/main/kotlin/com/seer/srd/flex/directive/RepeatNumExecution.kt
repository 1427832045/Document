package com.seer.srd.flex.directive

import com.seer.srd.BusinessError
import com.seer.srd.flex.FlexDirectiveConfig
import com.seer.srd.flex.FlexDirectiveExecution
import com.seer.srd.flex.FlexProcedureDef
import com.seer.srd.flex.FlexProcedureManager
import org.slf4j.LoggerFactory

class RepeatNumExecution : FlexDirective() {

    private val logger = LoggerFactory.getLogger(RepeatNumExecution::class.java)

    override suspend fun execute(
        de: FlexDirectiveExecution,
        dc: FlexDirectiveConfig,
        procedureId: String,
        def: FlexProcedureDef
    ) {
        logger.debug("Directive::RepeatNumExecution $procedureId/${de.configId} start")
        val num = dc.arguments["num"]?.toInt() ?: throw BusinessError("MissingArgument num")
        repeat(num) { i ->
            logger.debug("Directive::RepeatNumExecution $procedureId/${de.configId} $i/$num step")
            for (childConfig in dc.children) {
                val childDe = FlexProcedureManager.getFlexDirectiveExecution(procedureId, childConfig.id)
                FlexProcedureManager.runFlexDirective(childDe, procedureId, def)
            }
        }
        logger.debug("Directive::RepeatNumExecution $procedureId/${de.configId} end")
    }

}