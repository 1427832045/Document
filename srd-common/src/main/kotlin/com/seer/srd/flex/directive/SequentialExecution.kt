package com.seer.srd.flex.directive

import com.seer.srd.flex.*
import org.slf4j.LoggerFactory
import java.time.Instant

class SequentialExecution : FlexDirective() {

    private val logger = LoggerFactory.getLogger(SequentialExecution::class.java)

    override suspend fun execute(
        de: FlexDirectiveExecution,
        dc: FlexDirectiveConfig,
        procedureId: String,
        def: FlexProcedureDef
    ) {
        logger.debug("Directive::SequentialExecution $procedureId/${de.configId} start")
        val newDe = de.copy(state = FlexDirectiveState.Executing, startedOn = Instant.now())
        FlexProcedureManager.updateFlexDirectiveExecution(newDe, procedureId)
        for (childConfig in dc.children) {
            val childDe = FlexProcedureManager.getFlexDirectiveExecution(procedureId, childConfig.id)
            FlexProcedureManager.runFlexDirective(childDe, procedureId, def)
        }
        logger.debug("Directive::SequentialExecution $procedureId/${de.configId} end")
    }

}