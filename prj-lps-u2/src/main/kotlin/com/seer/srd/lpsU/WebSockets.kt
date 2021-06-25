package com.seer.srd.lpsU

import com.seer.srd.eventlog.EventLogLevel
import com.seer.srd.eventlog.SystemEvent
import com.seer.srd.eventlog.recordSystemEventLog
import com.seer.srd.http.WebSocketManager

object WebSockets {
    
    // 识别错误
    fun onOcrError(msg: OcrErrorMessage) {
        WebSocketManager.broadcastByWebSocket("OcrError", msg)
    }
    
    // 上料车某列物料不足，提醒上料
    fun onUpCarEmpty(msg: UpCarEmptyMessage) {
        WebSocketManager.broadcastByWebSocket("UpCarEmpty", msg)
        recordSystemEventLog("UpCarEmpty", EventLogLevel.Info, SystemEvent.Extra, "料车:${msg.carNum} 列:${msg.column}")
    }
    
    // 下料车某列已满，需要清
    fun onDownCarFill(msg: DownCarFillMessage) {
        WebSocketManager.broadcastByWebSocket("DownCarFill", msg)
        recordSystemEventLog("DownCarFill", EventLogLevel.Info, SystemEvent.Extra, "列:${msg.column}")
    }

    // 产线下料失败
    fun onLineDownFail(msg: LineDownFailMessage) {
        WebSocketManager.broadcastByWebSocket("LineDownFail", msg)
        recordSystemEventLog("LineDownFail", EventLogLevel.Info, SystemEvent.Extra, msg.line)
    }

    // 产线上料失败
    fun onLineUpButNotAllEmpty(msg: LineUpButNotAllEmptyMessage) {
        WebSocketManager.broadcastByWebSocket("LineUpButNotAllEmpty", msg)
        recordSystemEventLog("LineUpButNotAllEmpty", EventLogLevel.Info, SystemEvent.Extra, msg.line)
    }

    fun onBufferError(msg: BufferErrorMessage) {
        WebSocketManager.broadcastByWebSocket("BufferError", msg)
        recordSystemEventLog("BufferError", EventLogLevel.Info, SystemEvent.Extra, "${msg.line}:${msg.type}料口,${msg.errorMsg}")
    }

    // 产线上料，但是料车上没有原料
    fun onLineUpNoMat(msg: LineUpNoMatMessage) {
        WebSocketManager.broadcastByWebSocket("LineUpNoMat", msg)
        recordSystemEventLog("LineUpNoMat", EventLogLevel.Info, SystemEvent.Extra, "${msg.carNum} ${msg.column}")
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

data class BufferErrorMessage(
    val line: String,
    val type: String,   // up , down
    val errorMsg: String
)