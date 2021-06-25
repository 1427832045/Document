package com.seer.srd.flex.directive

import com.seer.srd.BusinessError
import com.seer.srd.flex.FlexDirectiveConfig
import com.seer.srd.flex.FlexDirectiveExecution
import com.seer.srd.flex.FlexProcedureDef
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

class DelayExecution : FlexDirective() {

    private val logger = LoggerFactory.getLogger(DelayExecution::class.java)

    override suspend fun execute(
        de: FlexDirectiveExecution,
        dc: FlexDirectiveConfig,
        procedureId: String,
        def: FlexProcedureDef
    ) {
        logger.debug("Directive::DelayExecution $procedureId/${de.configId} start")
        val timeMillis = dc.arguments["timeMillis"]?.toLong() ?: throw BusinessError("MissingArgument timeMillis")
        delay(timeMillis)
        logger.debug("Directive::DelayExecution $procedureId/${de.configId} end")
    }

}