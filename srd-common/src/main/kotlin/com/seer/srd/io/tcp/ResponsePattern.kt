package com.seer.srd.io.tcp

import java.util.*

interface ResponsePattern {

    fun read(buffer: ByteArray, size: Int): Int

}

class HeadResponsePattern(
    private val head: ByteArray,
    private val test: (buffer: ByteArray, size: Int) -> Int
) : ResponsePattern {
    override fun read(buffer: ByteArray, size: Int): Int {
        if (size < head.size) return -1
        if (!Arrays.equals(head, 0, head.size, buffer, 0, head.size)) return -1
        return test(buffer, size)
    }
}