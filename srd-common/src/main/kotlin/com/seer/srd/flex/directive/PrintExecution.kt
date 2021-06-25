package com.seer.srd.flex.directive

import com.seer.srd.BusinessError
import com.seer.srd.flex.FlexDirectiveConfig
import com.seer.srd.flex.FlexDirectiveExecution
import com.seer.srd.flex.FlexProcedureDef
import org.slf4j.LoggerFactory

class PrintExecution : FlexDirective() {

    private val logger = LoggerFactory.getLogger(PrintExecution::class.java)

    override suspend fun execute(
        de: FlexDirectiveExecution,
        dc: FlexDirectiveConfig,
        procedureId: String,
        def: FlexProcedureDef
    ) {
        logger.debug("Directive::PrintExecution $procedureId/${de.configId} start")
        val message = dc.arguments["message"] ?: throw BusinessError("MissingArgument message")
        logger.info(message)
        logger.debug("Directive::PrintExecution $procedureId/${de.configId} end")
    }

}