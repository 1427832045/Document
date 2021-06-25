package com.seer.srd.route.model

import com.seer.srd.model.*
import com.seer.srd.route.service.VehicleService
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.vehicle.Vehicle.ProcState
import org.opentcs.data.order.OrderConstants
import org.slf4j.LoggerFactory
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.system.exitProcess

object PlantModelReader {
    private val logger = LoggerFactory.getLogger(PlantModelReader::class.java)
    private var vehicleLabels = 0
    // srd-k allowed vehicle quantities, labels/2 is vehicle numbers
    private val vehicleQuantity = 10000
    
    fun loadPlantModel(file: File): PlantModel {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isIgnoringElementContentWhitespace = true
        factory.isIgnoringComments = true
        factory.isValidating = false
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        
        val root = doc.firstChild
        
        val plantModel = PlantModel(root.attributes.getNamedItem("name").nodeValue)
        
        val childrenLen = root.childNodes.length
        for (i in 0 until childrenLen) {
            val child = root.childNodes.item(i)
            if (child.nodeName == "vehicle") vehicleLabels++
            when (child.nodeName) {
                "vehicle" -> parseVehicle(child)
                "point" -> parsePoint(child, plantModel)
                "path" -> parsePath(child, plantModel)
                "locationType" -> parseLocationType(child, plantModel)
                "location" -> parseLocation(child, plantModel)
                "block" -> parseBlock(child, plantModel)
                "visualLayout" -> plantModel.visualLayouts.add(child)
                "property" -> plantModel.properties[child.attributes.getNamedItem("name").nodeValue] =
                    child.attributes.getNamedItem("value").nodeValue
            }
        }

        if (vehicleLabels > vehicleQuantity) {
            logger.error("!!! XML PlantModel has error vehicle quantity :$vehicleLabels!!!")
            exitProcess(0)
        } else{ vehicleLabels = 0 }
        // 后处理
        for (path in plantModel.paths.values) {
            val p1 = plantModel.points[path.sourcePoint]
            if (p1 != null) {
                val paths = HashSet(p1.outgoingPaths)
                paths.add(path.name)
                plantModel.points[path.sourcePoint] = p1.copy(outgoingPaths = paths)
            }
            val p2 = plantModel.points[path.destinationPoint]
            if (p2 != null) {
                val paths = HashSet(p2.incomingPaths)
                paths.add(path.name)
                plantModel.points[path.destinationPoint] = p2.copy(incomingPaths = paths)
            }
        }
        for (location in plantModel.locations.values) {
            for (link in location.attachedLinks) {
                val p = plantModel.points[link.point]
                if (p != null) {
                    val links = HashSet(p.attachedLinks)
                    links.add(link)
                    plantModel.points[link.point] = p.copy(attachedLinks = links)
                }
            }
        }
        
        return plantModel
    }
    
    private fun parsePoint(node: Node, plantModel: PlantModel) {
        val name = node.attributes.getNamedItem("name").nodeValue
        val x = node.attributes.getNamedItem("xPosition").nodeValue.toLong()
        val y = node.attributes.getNamedItem("yPosition").nodeValue.toLong()
        val z = node.attributes.getNamedItem("zPosition").nodeValue.toLong()
        val angle = node.attributes.getNamedItem("vehicleOrientationAngle").nodeValue.toDoubleOrNull() ?: Double.NaN
        val type = node.attributes.getNamedItem("type").nodeValue
        
        val point = Point(
            name, HashMap(),
            Triple(x, y, z), PointType.valueOf(type), angle, HashSet(), HashSet(), HashSet(), null
        )
        plantModel.points[point.name] = point
    }
    
    private fun parsePath(node: Node, plantModel: PlantModel) {
        val name = node.attributes.getNamedItem("name").nodeValue
        val sourcePoint = node.attributes.getNamedItem("sourcePoint").nodeValue
        val destinationPoint = node.attributes.getNamedItem("destinationPoint").nodeValue
        val length = node.attributes.getNamedItem("length").nodeValue.toLong()
        val routingCost = node.attributes.getNamedItem("routingCost").nodeValue.toLong()
        val maxVelocity = node.attributes.getNamedItem("maxVelocity").nodeValue.toInt()
        val maxReverseVelocity = node.attributes.getNamedItem("maxReverseVelocity").nodeValue.toInt()
        val locked = node.attributes.getNamedItem("locked").nodeValue!!.toBoolean()

        val properties: MutableMap<String, String> = HashMap()
        val childrenLen = node.childNodes.length
        for (i in 0 until childrenLen) {
            val child = node.childNodes.item(i)
            when (child.nodeName) {
                "property" -> properties[child.attributes.getNamedItem("name").nodeValue] =
                    child.attributes.getNamedItem("value").nodeValue
            }
        }

        val path = Path(
            name, properties, sourcePoint, destinationPoint, length, routingCost, maxVelocity, maxReverseVelocity, locked
        )
        plantModel.paths[path.name] = path
    }
    
    private fun parseLocationType(node: Node, plantModel: PlantModel) {
        val name = node.attributes.getNamedItem("name").nodeValue
        
        val allowedOperations: MutableList<String> = ArrayList()
        val properties: MutableMap<String, String> = HashMap()
        
        val childrenLen = node.childNodes.length
        for (i in 0 until childrenLen) {
            val child = node.childNodes.item(i)
            when (child.nodeName) {
                "allowedOperation" -> allowedOperations.add(child.attributes.getNamedItem("name").nodeValue)
                "property" -> properties[child.attributes.getNamedItem("name").nodeValue] =
                    child.attributes.getNamedItem("value").nodeValue
            }
        }
        val locationType = LocationType(name, properties, allowedOperations)
        plantModel.locationTypes[locationType.name] = locationType
    }
    
    private fun parseLocation(node: Node, plantModel: PlantModel) {
        val name = node.attributes.getNamedItem("name").nodeValue
        val xPosition = node.attributes.getNamedItem("xPosition").nodeValue.toLong()
        val yPosition = node.attributes.getNamedItem("yPosition").nodeValue.toLong()
        val zPosition = node.attributes.getNamedItem("zPosition").nodeValue.toLong()
        val type = node.attributes.getNamedItem("type").nodeValue
        
        val links: MutableSet<Link> = HashSet()
        val properties: MutableMap<String, String> = HashMap()
        val childrenLen = node.childNodes.length
        for (i in 0 until childrenLen) {
            val child = node.childNodes.item(i)
            when (child.nodeName) {
                "property" -> properties[child.attributes.getNamedItem("name").nodeValue] =
                        child.attributes.getNamedItem("value").nodeValue
                "link" -> links.add(Link(name, child.attributes.getNamedItem("point").nodeValue))
            }
        }
        
        val location = Location(
            name,
            properties,
//          HashMap(),
            type,
            Triple(xPosition, yPosition, zPosition),
            links
        )
        plantModel.locations[location.name] = location
    }
    
    private fun parseBlock(node: Node, plantModel: PlantModel) {
        val name = node.attributes.getNamedItem("name").nodeValue
        val type = node.attributes.getNamedItem("type").nodeValue
        
        val members: MutableSet<String> = HashSet()
        val properties: MutableMap<String, String> = HashMap()
        val childrenLen = node.childNodes.length
        for (i in 0 until childrenLen) {
            val child = node.childNodes.item(i)
            when (child.nodeName) {
                "property" -> properties[child.attributes.getNamedItem("name").nodeValue] =
                        child.attributes.getNamedItem("value").nodeValue
                "member" -> members.add(child.attributes.getNamedItem("name").nodeValue)
            }
        }
        
        val block = Block(
            name,
            properties,
//          HashMap(),
            BlockType.valueOf(type),
            members
        )
        plantModel.blocks[block.name] = block
    }
    
    private fun parseVehicle(node: Node) {
        val name = node.attributes.getNamedItem("name").nodeValue
        val length = node.attributes.getNamedItem("length").nodeValue.toInt()
        val energyLevelCritical = node.attributes.getNamedItem("energyLevelCritical").nodeValue.toInt()
        val energyLevelGood = node.attributes.getNamedItem("energyLevelGood").nodeValue.toInt()
        val energyLevelFullyRecharged = node.attributes.getNamedItem("energyLevelFullyRecharged").nodeValue.toInt()
        val energyLevelSufficientlyRecharged =
            node.attributes.getNamedItem("energyLevelSufficientlyRecharged").nodeValue.toInt()
        val maxVelocity = node.attributes.getNamedItem("maxVelocity").nodeValue.toInt()
        val maxReverseVelocity = node.attributes.getNamedItem("maxReverseVelocity").nodeValue.toInt()
        val rechargeOperation = node.attributes.getNamedItem("rechargeOperation").nodeValue
        
        val processableCategories: MutableSet<String> = HashSet()
        val properties: MutableMap<String, String> = HashMap()
        
        val childrenLen = node.childNodes.length
        for (i in 0 until childrenLen) {
            val child = node.childNodes.item(i)
            when (child.nodeName) {
                "processableCategory" -> processableCategories.add(child.attributes.getNamedItem("name").nodeValue)
                "property" -> properties[child.attributes.getNamedItem("name").nodeValue] =
                    child.attributes.getNamedItem("value").nodeValue
            }
        }
        
        if (processableCategories.isEmpty()) processableCategories.add(OrderConstants.CATEGORY_ANY)
        
        val vehicle = Vehicle(
            name,
            properties,
            length,
            energyLevelGood,
            energyLevelCritical,
            energyLevelFullyRecharged,
            energyLevelSufficientlyRecharged,
            0,
            maxVelocity,
            maxReverseVelocity,
            rechargeOperation,
            emptyList(),
            Vehicle.State.UNKNOWN,
            ProcState.UNAVAILABLE,
            Vehicle.IntegrationLevel.TO_BE_RESPECTED,
            null,
            null,
            processableCategories,
            -1,
            null,
            null,
            null,
            Double.NaN
        )
        VehicleService.addNewVehicle(vehicle)
    }
}