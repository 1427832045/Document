package com.seer.srd.io.aioTcp

import com.seer.srd.vehicle.driver.io.tcp.AioTcpServerConnection
import java.io.EOFException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

// 异步读写封装
object AioTcpHelper {
    suspend fun connect(
        channel: AsynchronousSocketChannel,
        hostname: String,
        port: Int
    ) {
        suspendCoroutine<Void?> {
            channel.connect(InetSocketAddress(hostname, port), "", object : CompletionHandler<Void?, String> {
                override fun completed(result: Void?, attachment: String) {
                    it.resume(result)
                }
    
                override fun failed(e: Throwable, attachment: String) {
                    it.resumeWithException(e)
                }
            })
        }
    }
    
    suspend fun write(channel: AsynchronousSocketChannel, buffer: ByteBuffer): Int {
        val writeAttachment: Any = {}
        return suspendCoroutine {
            channel.write(buffer, writeAttachment, object : CompletionHandler<Int, Any> {
                override fun completed(result: Int, attachment: Any) {
                    it.resume(result)
                }
    
                override fun failed(e: Throwable, attachment: Any) {
                    it.resumeWithException(e)
                }
            })
        }
    }
    
    suspend fun writeAll(channel: AsynchronousSocketChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            write(channel, buffer)
        }
    }
    
    suspend fun read(channel: AsynchronousSocketChannel, buffer: ByteBuffer, timeLimit: Long, timeUnit: TimeUnit): Int {
        val readAttachment: Any = {}
        return suspendCoroutine {
            channel.read(buffer, timeLimit, timeUnit, readAttachment, object : CompletionHandler<Int, Any> {
                override fun completed(result: Int, attachment: Any) {
                    it.resume(result)
                }
    
                override fun failed(e: Throwable, attachment: Any) {
                    it.resumeWithException(e)
                }
            })
        }
    }
    
    suspend fun readAll(channel: AsynchronousSocketChannel, buffer: ByteBuffer, timeLimit: Long, timeUnit: TimeUnit) {
        while (buffer.hasRemaining()) {
            val readCount = read(channel, buffer, timeLimit, timeUnit)
            if (readCount < 0) throw EOFException() // end of stream
        }
    }
    
    // server socket methods
    suspend fun accept(channel: AsynchronousServerSocketChannel): AioTcpServerConnection {
        return suspendCoroutine {
            channel.accept({}, object : CompletionHandler<AsynchronousSocketChannel, Any> {
                override fun completed(clientChannel: AsynchronousSocketChannel, attachment: Any) {
                    val conn = AioTcpServerConnection(clientChannel)
                    it.resume(conn)
                }
    
                override fun failed(e: Throwable, attachment: Any) {
                    it.resumeWithException(e)
                }
    
            })
        }
    }
}
