package com.seer.srd.flex.directive

import com.seer.srd.flex.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.time.Instant

class ParallelExecution : FlexDirective() {

    private val logger = LoggerFactory.getLogger(ParallelExecution::class.java)

    override suspend fun execute(
        de: FlexDirectiveExecution,
        dc: FlexDirectiveConfig,
        procedureId: String,
        def: FlexProcedureDef
    ) {
        logger.debug("Directive::ParallelExecution $procedureId/${de.configId} start")
        val newDe = de.copy(state = FlexDirectiveState.Executing, startedOn = Instant.now())
        FlexProcedureManager.updateFlexDirectiveExecution(newDe, procedureId)

        coroutineScope {
            dc.children.map { childConfig ->
                val childDe = FlexProcedureManager.getFlexDirectiveExecution(procedureId, childConfig.id)
                async { FlexProcedureManager.runFlexDirective(childDe, procedureId, def) }
            }
        }
        logger.debug("Directive::ParallelExecution $procedureId/${de.configId} end")
    }
}