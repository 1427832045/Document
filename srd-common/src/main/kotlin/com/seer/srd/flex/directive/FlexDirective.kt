package com.seer.srd.flex.directive

import com.seer.srd.flex.*

abstract class FlexDirective {

    abstract suspend fun execute(
        de: FlexDirectiveExecution,
        dc: FlexDirectiveConfig,
        procedureId: String,
        def: FlexProcedureDef
    )

}

