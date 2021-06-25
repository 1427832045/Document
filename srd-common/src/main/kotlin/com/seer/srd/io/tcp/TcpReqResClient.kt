package com.seer.srd.io.tcp

import java.util.concurrent.ConcurrentHashMap

// 将 TCP 客户端封装为请求响应的风格

typealias FlowNoGenerator = () -> Int

typealias ReqWriter = (flowNo: Int) -> Unit

typealias Callback = (data: Any) -> Unit

class TcpReqResClient(private val flowNoGenerator: FlowNoGenerator) {
    
    private val callbackByFlowNo: MutableMap<Int, Callback> = ConcurrentHashMap()
    
    fun request(reqWriter: ReqWriter) {
        val flowNo = flowNoGenerator()
        callbackByFlowNo[flowNo] = { callbackByFlowNo.remove(flowNo) }
        reqWriter(flowNo)
    }
    
    fun onResponse(flowNo: Int, data: Any) {
        this.callbackByFlowNo[flowNo]?.invoke(data)
    }
    
    companion object {
        fun defaultFlowNoGenerator(max: Int): FlowNoGenerator {
            var flowNoCounter = 0
            return {
                flowNoCounter = (flowNoCounter + 1) % max
                flowNoCounter
            }
        }
        
    }
}

