package com.seer.srd.lps

import com.seer.srd.http.WebSocketManager

object WebSockets {
    
    // 识别错误
    fun onOcrError(msg: OcrErrorMessage) {
        WebSocketManager.broadcastByWebSocket("OcrError", msg)
    }
    
    // 上料车某列空，提醒上料
    fun onUpCarEmpty(msg: UpCarEmptyMessage) {
        WebSocketManager.broadcastByWebSocket("UpCarEmpty", msg)
    }
    
    // 下料车某列已满，需要清
    fun onDownCarFill(msg: DownCarFillMessage) {
        WebSocketManager.broadcastByWebSocket("DownCarFill", msg)
    }
    
    // 产线下料失败
    fun onLineDownFail(msg: LineDownFailMessage) {
        WebSocketManager.broadcastByWebSocket("LineDownFail", msg)
    }
    
    // 产线上料失败
    fun onLineUpButNotAllEmpty(msg: LineUpButNotAllEmptyMessage) {
        WebSocketManager.broadcastByWebSocket("LineUpButNotAllEmpty", msg)
    }
    
    // 上线上料，但是料车上没有原料
    fun onLineUpNoMat(msg: LineUpNoMatMessage) {
        WebSocketManager.broadcastByWebSocket("LineUpNoMat", msg)
    }
}

data class OcrErrorMessage(
    val carNum: String, // 车号
    val column: String, // 列号
    val errorSites: List<String> // 错误库位
)

data class UpCarEmptyMessage(
    val carNum: String, // 车号
    val column: String // 列号
)

data class DownCarFillMessage(
    val carNum: String, // 车号
    val column: String // 列号
)

data class LineDownFailMessage(
    val line: String
)

data class LineUpNoMatMessage(
    val line: String,
    val carNum: String,
    var column: String,
    val productType: String
)

data class LineUpButNotAllEmptyMessage(
    val line: String
)