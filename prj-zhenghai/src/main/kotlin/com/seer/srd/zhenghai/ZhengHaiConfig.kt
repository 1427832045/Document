package com.seer.srd.zhenghai

data class CustomConfig(
    // <siteId, heightStr>
    var jackHeightOfSites: Map<String, String> = mapOf(),

    var menuIdForJack: List<String> = listOf(
        "BoxPrepareToPrepare", "CheckToBoxPrepare", "OutToCheck", "FreeTransport", "CheckToPrepare",
        "ComplexTransport1", "ComplexTransport2"
    ),

    var menuIdForFork: List<String> = listOf("PrepareToIn", "InToOut")
)
