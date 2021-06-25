package com.seer.srd.io.tcp

class ByteArrayBuffer(
    val buffer: ByteArray,
    var validStartIndex: Int,
    var validEndIndex: Int
)