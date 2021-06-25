package com.seer.srd.gelanfu

import com.seer.srd.BusinessError
import org.apache.commons.io.IOUtils
import org.apache.commons.net.PrintCommandListener
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets

val outFtp = FtpHelper(
    CUSTOM_CONFIG.outFtpHostname,
    CUSTOM_CONFIG.outFtpPort,
    CUSTOM_CONFIG.outFtpUsername,
    CUSTOM_CONFIG.outFtpPassword
)

val inFtp = FtpHelper(
    CUSTOM_CONFIG.inFtpHostname,
    CUSTOM_CONFIG.inFtpPort,
    CUSTOM_CONFIG.inFtpUsername,
    CUSTOM_CONFIG.inFtpPassword
)


class FtpHelper(
    private val hostname: String,
    private val port: Int,
    private val username: String,
    private val password: String
) {

    private val logger = LoggerFactory.getLogger(FtpHelper::class.java)

    private var ftpClient: FTPClient? = null

    @Synchronized
    private fun getFtp(): FTPClient {
        val client = ftpClient
        if (client != null && client.isConnected) return client
        return connect()
    }

    @Synchronized
    fun listFiles(path: String): Array<out FTPFile> {
        val client = getFtp()
        val files = client.listFiles(path)
        onError(client)
        return files
    }

    @Synchronized
    fun retrieveFileStream(file: String): InputStream {
        val client = getFtp()
        return client.retrieveFileStream(file) ?: throw BusinessError("No input stream for ftp file")
    }

    @Synchronized
    fun deleteFile(file: String) {
        val client = getFtp()
        if (!client.deleteFile(file)) {
            logger.error("Failed to delete $file")
        }
        onError(client)

    }

    @Synchronized
    fun storeFile(file: String, str: String) {
        val client = getFtp()
        client.setSendBufferSize(1024)
        IOUtils.toInputStream(str, StandardCharsets.UTF_8).use { inputStream ->
            client.storeFile(file, inputStream)
            //val outputStream = client.storeFileStream(file)
            //if (outputStream == null) {
            //    logger.error("store file stream return null ${client.replyCode} ${client.replyString}")
            //    return
            //}
            //try {
            //    IOUtils.copy(inputStream, outputStream, 1024)
            //} catch (e: Exception) {
            //    logger.error("copy stream", e)
            //    throw e
            //}
        }

        onError(client)
    }

    @Synchronized
    fun changeWorkingDirectory(dir: String) {
        val client = getFtp()
        if (!client.changeWorkingDirectory(dir)) {
            logger.error("Failed to change working directory $dir")
            return
        }

        onError(client)
    }

    @Synchronized
    fun connect(): FTPClient {
        disconnect()

        val client = FTPClient()
        // client.setFileTransferMode(FTP.BLOCK_TRANSFER_MODE)

        client.addProtocolCommandListener(PrintCommandListener(PrintWriter(System.out)))

        client.connect(hostname, port)
        onError(client)

        client.login(username, password)
        client.setFileType(FTP.ASCII_FILE_TYPE)
        //client.enterLocalPassiveMode()

        ftpClient = client
        return client
    }

    @Synchronized
    fun disconnect() {
        try {
            ftpClient?.logout()
        } catch (e: Exception) {
            logger.error("logout", e)
        }
        try {
            ftpClient?.disconnect()
        } catch (e: Exception) {
            logger.error("disconnect", e)
        }
        ftpClient = null
    }

    private fun onError(client: FTPClient) {
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            this.disconnect()
            throw BusinessError("Exception from FTP Server ${client.replyString}")
        }
    }
}