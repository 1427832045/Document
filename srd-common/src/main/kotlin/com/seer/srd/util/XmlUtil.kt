package com.seer.srd.util

import org.w3c.dom.Node
import org.w3c.dom.NodeList
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

fun findXmlNodeByNodeName(from: Node, nodeName: String): Node? {
    if (from.nodeName == nodeName) return from
    val children = from.childNodes
    val childrenCount = children.length
    for (i in 0 until childrenCount) {
        val child = children.item(i)
        val found = findXmlNodeByNodeName(child, nodeName)
        if (found != null) return found
    }
    return null
}

fun getXmlNodeByPath(from: Node, path: List<String>): Node? {
    var next: Node? = from
    for (part in path) {
        next = if (next != null) getXmlNodeByNodeNameFromChildren(next.childNodes, part) ?: return null
        else return null
    }
    return next
}

fun getXmlNodeByNodeNameFromChildren(children: NodeList, nodeName: String): Node? {
    val childrenCount = children.length
    for (i in 0 until childrenCount) {
        val child = children.item(i)
        if (child.nodeName == nodeName) return child
    }
    return null
}

fun getDocumentBuilder(): DocumentBuilder {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isIgnoringElementContentWhitespace = true
    factory.isIgnoringComments = true
    factory.isValidating = false
    return factory.newDocumentBuilder()
}