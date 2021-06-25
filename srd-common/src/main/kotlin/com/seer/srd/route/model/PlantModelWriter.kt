package com.seer.srd.route.model

import java.io.File
import java.io.FileWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult


object PlantModelWriter {

    fun persistPlantModel(file: File, plantModel: PlantModel) {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isIgnoringElementContentWhitespace = true
        factory.isIgnoringComments = true
        factory.isValidating = false
        val builder = factory.newDocumentBuilder()
        val doc = builder.newDocument()

        val root = doc.createElement("model")
        doc.appendChild(root)
        root.setAttribute("name", plantModel.name)
        root.setAttribute("version", "0.0.2")

        for (point in plantModel.points.values) {
            val node = doc.createElement("point")
            root.appendChild(node)
            node.setAttribute("name", point.name)
            node.setAttribute("xPosition", point.position.x.toString())
            node.setAttribute("yPosition", point.position.y.toString())
            node.setAttribute("zPosition", point.position.z.toString())
            node.setAttribute("vehicleOrientationAngle", point.vehicleOrientationAngle.toString())
            node.setAttribute("type", point.type.name)
        }

        for (path in plantModel.paths.values) {
            val node = doc.createElement("path")
            root.appendChild(node)
            node.setAttribute("name", path.name)
            node.setAttribute("sourcePoint", path.sourcePoint)
            node.setAttribute("destinationPoint", path.destinationPoint)
            node.setAttribute("length", path.length.toString())
            node.setAttribute("routingCost", path.routingCost.toString())
            node.setAttribute("maxVelocity", path.maxVelocity.toString())
            node.setAttribute("maxReverseVelocity", path.maxReverseVelocity.toString())
            node.setAttribute("locked", path.isLocked.toString())
        }

        for (locationType in plantModel.locationTypes.values) {
            val node = doc.createElement("locationType")
            root.appendChild(node)
            node.setAttribute("name", locationType.name)

            for (allowedOperation in locationType.allowedOperations) {
                val n2 = doc.createElement("allowedOperation")
                node.appendChild(n2)
                n2.setAttribute("name", allowedOperation)
            }

            for (p in locationType.properties.entries) {
                val n2 = doc.createElement("property")
                node.appendChild(n2)
                n2.setAttribute("name", p.key)
                n2.setAttribute("value", p.value)
            }
        }

        for (location in plantModel.locations.values) {
            val node = doc.createElement("location")
            root.appendChild(node)
            node.setAttribute("name", location.name)
            node.setAttribute("xPosition", location.position.x.toString())
            node.setAttribute("yPosition", location.position.y.toString())
            node.setAttribute("zPosition", location.position.z.toString())
            node.setAttribute("type", location.type)

            for (link in location.attachedLinks) {
                val n2 = doc.createElement("link")
                node.appendChild(n2)
                n2.setAttribute("point", link.point)
            }
        }

        for (block in plantModel.blocks.values) {
            val node = doc.createElement("block")
            root.appendChild(node)
            node.setAttribute("name", block.name)
            node.setAttribute("type", block.type.name)

            for (member in block.members) {
                val n2 = doc.createElement("member")
                node.appendChild(n2)
                n2.setAttribute("name", member)
            }
        }

        for (vl in plantModel.visualLayouts) {
            root.appendChild(vl)
        }

        for (p in plantModel.properties.entries) {
            val node = doc.createElement("property")
            root.appendChild(node)
            node.setAttribute("name", p.key)
            node.setAttribute("value", p.value)
        }

        val source = DOMSource(doc)
        val result = StreamResult(FileWriter(file))

        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        transformer.transform(source, result)
    }

}