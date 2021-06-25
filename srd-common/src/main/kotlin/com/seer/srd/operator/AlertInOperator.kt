package com.seer.srd.operator

import com.seer.srd.http.WebSocketManager.broadcastByWebSocket

data class AlertInOperator(
    var message: String,
    var toWorkStations: List<String>? = null, // 仅列出的工位可见
    var toWorkTypes: List<String>? = null, // 仅列出的岗位可见
    var toAll: Boolean? = false // 所有人可见
) {
    companion object {
        fun alertInOperator(alert: AlertInOperator) {
            broadcastByWebSocket("Operator::Alert", alert)
        }
    }
}