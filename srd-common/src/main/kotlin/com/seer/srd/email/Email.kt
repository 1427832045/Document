package com.seer.srd.email

import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import org.slf4j.LoggerFactory
import java.util.*
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

private val LOG = LoggerFactory.getLogger("com.seer.srd.email")

fun sendMail(title: String, content: String, address: List<String>?) {

    val user = CONFIG.mailUser
    val password = CONFIG.mailPassword
    val host = CONFIG.mailHost
    val port = CONFIG.mailHostPort
    val mark = CONFIG.mailMark
    val enableSSL = CONFIG.enableSSL
    val defaultRecipients = CONFIG.defaultRecipients
    val text = "$mark\n\n$content"

    val props = Properties()
    props["mail.smtp.auth"] = "true"
    props["mail.transport.protocol"] = "smtp"
    props["mail.host"] = host
    props["mail.smtp.ssl.enable"] = enableSSL

    try {
        val session = Session.getDefaultInstance(props, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(user, password)
            }
        })
        session.debug = true

        val message = MimeMessage(session)
        message.subject = title
        message.setText(text)
        // 现在这里发送人必须与认证人一致，否则报错
        message.setFrom(InternetAddress(user))
        defaultRecipients.forEach { message.addRecipients(Message.RecipientType.TO, InternetAddress.parse(it, false)) }
        address?.forEach { message.addRecipients(Message.RecipientType.TO, InternetAddress.parse(it, false)) }
        if (message.allRecipients.isEmpty()) {
            message.addRecipients(Message.RecipientType.TO, InternetAddress.parse(user, false))
        }
        message.sentDate = Date()

        LOG.info("$user send email to ${address.toString()}, the content is: \n $content")
        val transport = session.getTransport("smtp")
        transport.connect(host, port, user, password)
        transport.sendMessage(message, message.allRecipients)
        transport.close()
    } catch (e: Exception) {
        LOG.error(e.message, e)
        throw BusinessError(e.message)
    }

}