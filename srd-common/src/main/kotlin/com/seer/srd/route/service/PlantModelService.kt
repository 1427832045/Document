@file:Suppress("CAST_NEVER_SUCCEEDS")

package com.seer.srd.route.service

import com.mongodb.client.model.ReplaceOptions
import com.seer.srd.Error400
import com.seer.srd.SystemError
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.model.Block
import com.seer.srd.model.Path
import com.seer.srd.model.Point
import com.seer.srd.route.dg.DGMaskManager
import com.seer.srd.route.dg.plantModelMaskFactory
import com.seer.srd.route.globalSyncObject
import com.seer.srd.route.model.PlantModel
import com.seer.srd.route.model.PlantModelDO
import com.seer.srd.route.model.PlantModelReader
import com.seer.srd.route.model.PlantModelWriter
import org.apache.commons.io.FileUtils
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.opentcs.access.Kernel
import org.opentcs.access.LocalKernel
import org.opentcs.data.ObjectUnknownException
import org.opentcs.kernel.getInjector
import org.opentcs.util.Assertions
import org.opentcs.util.FileSystems
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.HashSet

object PlantModelService {
    
    private const val MODEL_FILE_NAME = "model.xml"
    
    private const val DEFAULT_PLANT_MODEL_DO_ID = "DefaultPlantModel"
    
    private val dataDirectory = File(System.getProperty("user.dir"), "data")
    
    private var plantModel: PlantModel = PlantModel(Kernel.DEFAULT_MODEL_NAME)
    
    private val lock = ReentrantLock()
    
    fun getPlantModel(): PlantModel {
        synchronized(globalSyncObject) {
            return plantModel
        }
    }
    
    fun loadPlantModel() {
        synchronized(globalSyncObject) {
            if (!modelFileExists()) return
            plantModel = PlantModelReader.loadPlantModel(File(dataDirectory, MODEL_FILE_NAME))
            loadPlantModelDO()
            // create dg
            DGMaskManager.maskDG(listOf(plantModelMaskFactory(plantModel)))
        }
    }
    
    fun getModelXml(): String {
        if (!lock.tryLock()) throw Error400("ModelLocking", "模型正在被处理")
        try {
            val modelFile = File(dataDirectory, MODEL_FILE_NAME)
            return FileUtils.readFileToString(modelFile, StandardCharsets.UTF_8)
        } finally {
            lock.unlock()
        }
    }
    
    fun updatePlantModel(data: String) {
        if (!lock.tryLock()) throw Error400("ModelLocking", "模型正在被处理")
        try {
            val injector = getInjector() ?: throw SystemError("No Injector")
            val kernel = injector.getInstance(LocalKernel::class.java)
            
            val kernelInOperating = kernel.state == Kernel.State.OPERATING
            // If we are in state operating, change the kernel state before creating the plant model
            if (kernelInOperating) kernel.state = Kernel.State.MODELLING
            // Back up current plant model.
            replaceModelFile(data)
    
            VehicleService.clear() // 临时修复内核状态未切换结束导致报车的名字重复
            VehicleService.clearDB()
            
            loadPlantModel()
            // Change the kernel state back to operating.
            kernel.state = Kernel.State.OPERATING
        } finally {
            lock.unlock()
        }
    }
    
    fun saveModel() {
        ensureDataDirectory()
        val modelFile = File(dataDirectory, MODEL_FILE_NAME)
        // Check if writing the model is possible.
        Assertions.checkState(
                dataDirectory.isDirectory || dataDirectory.mkdirs(),
                "%s is not an existing directory and could not be created, either.",
                dataDirectory.path
        )
        Assertions.checkState(
                !modelFile.exists() || modelFile.isFile,
                "%s exists, but is not a regular file",
                modelFile.path
        )
        if (modelFile.exists()) createBackup()
        PlantModelWriter.persistPlantModel(modelFile, plantModel)
    }
    
    // todo rename
    private fun replaceModelFile(data: String) {
        ensureDataDirectory()
        val modelFile = File(dataDirectory, MODEL_FILE_NAME)
        if (!modelFile.exists()) {
            modelFile.createNewFile()
        }
        FileWriter(modelFile, StandardCharsets.UTF_8,false).use { writer ->
            writer.write(data)
            writer.flush()
        }
    }
    
    fun removeModel() {
        ensureDataDirectory()
        val modelFile = File(dataDirectory, MODEL_FILE_NAME)
        // If the model file does not exist, don't do anything
        if (!modelFileExists()) return
        createBackup()
        if (!FileSystems.deleteRecursively(modelFile)) {
            throw IOException("Cannot delete " + modelFile.path)
        }
    }
    
    private fun ensureDataDirectory() {
        if (!dataDirectory.exists()) dataDirectory.mkdirs()
    }
    
    private fun modelFileExists(): Boolean {
        val modelFile = File(dataDirectory, MODEL_FILE_NAME)
        return if (!modelFile.exists()) false else !modelFile.exists() || modelFile.isFile
    }
    
    private fun createBackup() {
        // Generate backup file name
        val sdf = SimpleDateFormat("yyyyMMdd-HHmmss-SSS")
        val time = sdf.format(Calendar.getInstance().time)
        val modelBackupName = MODEL_FILE_NAME + "_backup_" + time
        // Make sure backup directory exists
        val modelBackupDirectory = File(dataDirectory, "backups")
        if (modelBackupDirectory.exists()) {
            if (!modelBackupDirectory.isDirectory) {
                throw IOException(modelBackupDirectory.path + " exists, but is not a directory")
            }
        } else {
            if (!modelBackupDirectory.mkdir()) {
                throw IOException("Could not create model directory " + modelBackupDirectory.path)
            }
        }
        // Backup the model file
        Files.copy(
                File(dataDirectory, MODEL_FILE_NAME).toPath(),
                File(modelBackupDirectory, modelBackupName).toPath()
        )
    }
    
    fun setPathLocked(pathName: String, newLocked: Boolean) {
        val path = plantModel.paths[pathName] ?: throw ObjectUnknownException("Unknown path '$pathName'.")
        plantModel.paths[pathName] = path.copy(isLocked = newLocked)
        
        persistPlantModelDO()
    }

    fun setProperties(name: String, properties: Map<String, String>) {
        synchronized(globalSyncObject) {
            when {
                plantModel.points.containsKey(name) -> {
                    val point = plantModel.points[name]
                    point!!.properties = point.properties.toMutableMap().putAll(properties) as Map<String, String>
                }
                plantModel.paths.containsKey(name) -> {
                    val path = plantModel.paths[name]
                    path!!.properties = path.properties.toMutableMap().putAll(properties) as Map<String, String>
                }
                plantModel.locations.containsKey(name) -> {
                    val location = plantModel.locations[name]
                    location!!.properties = location.properties.toMutableMap().putAll(properties) as Map<String, String>
                }
                plantModel.locationTypes.containsKey(name) -> {
                    val locationType = plantModel.locationTypes[name]
                    locationType!!.properties = locationType.properties.toMutableMap().putAll(properties) as Map<String, String>
                }
                plantModel.blocks.containsKey(name) -> {
                    val block = plantModel.blocks[name]
                    block!!.properties = block.properties.toMutableMap().putAll(properties) as Map<String, String>
                }
                else -> throw ObjectUnknownException("Unknown plant model object: '$name'")
            }
        }
    }

    fun removeProperty(name: String, key: String) {
        synchronized(globalSyncObject) {
            when {
                plantModel.points.containsKey(name) -> {
                    val point = plantModel.points[name]
                    val properties = point!!.properties.toMutableMap()
                    properties.remove(key)
                    point.properties = properties
                }
                plantModel.paths.containsKey(name) -> {
                    val path = plantModel.paths[name]
                    val properties = path!!.properties.toMutableMap()
                    properties.remove(key)
                    path.properties = properties
                }
                plantModel.locations.containsKey(name) -> {
                    val location = plantModel.locations[name]
                    val properties = location!!.properties.toMutableMap()
                    properties.remove(key)
                    location.properties = properties
                }
                plantModel.locationTypes.containsKey(name) -> {
                    val locationType = plantModel.locationTypes[name]
                    val properties = locationType!!.properties.toMutableMap()
                    properties.remove(key)
                    locationType.properties = properties
                }
                plantModel.blocks.containsKey(name) -> {
                    val block = plantModel.blocks[name]
                    val properties = block!!.properties.toMutableMap()
                    properties.remove(key)
                    block.properties = properties
                }
                else -> throw ObjectUnknownException("Unknown plant model object: '$name'")
            }
        }
    }



    fun getProperties(name: String): Map<String, String> {
        when {
            plantModel.points.containsKey(name) -> {
                return plantModel.points[name]!!.properties
            }
            plantModel.paths.containsKey(name) -> {
                return plantModel.paths[name]!!.properties
            }
            plantModel.locations.containsKey(name) -> {
                return plantModel.locations[name]!!.properties
            }
            plantModel.locationTypes.containsKey(name) -> {
                return plantModel.locationTypes[name]!!.properties
            }
            plantModel.blocks.containsKey(name) -> {
                return plantModel.blocks[name]!!.properties
            }
            else -> throw ObjectUnknownException("Unknown plant model object: '$name'")
        }
    }

    fun setDynamicPathProperty(name: String, key: String, property: String?) {
        synchronized(globalSyncObject) {
            val path = plantModel.paths[name]
            if (path != null) {
                val properties = path.properties.toMutableMap()
                path.properties = if (property == null) {
                    // remove the property
                    properties.remove(key)
                    properties
                } else {
                    //set the property
                    properties[key] = property
                    properties
                }
            } else {
                throw ObjectUnknownException("Unknown path name: '$name'")
            }
        }
    }
    
    fun isNameOfPoint(name: String): Boolean {
        return plantModel.points.containsKey(name)
    }

    fun isNameOfPath(name: String): Boolean {
        return plantModel.points.containsKey(name)
    }
    
    fun getPathIfNameIs(name: String): Path? {
        return plantModel.paths[name]
    }
    
    fun getPointIfNameIs(name: String): Point? {
        return plantModel.points[name]
    }
    
    /**
     * Expands a set of resources *A* to a set of resources *B*.
     * *B* contains the resources in *A* with blocks expanded to
     * their actual members.
     */
    fun expandResources(resources: Set<String>): Set<String> {
        synchronized(globalSyncObject) {
            val result: MutableSet<String> = HashSet()
            val blocks: Collection<Block> = plantModel.blocks.values
            for (resName in resources) {
                result.add(resName)
                for (block in blocks) {
                    // If the current block contains the resource, add all of the block's
                    // members to the result.
                    if (block.members.contains(resName)) result.addAll(block.members)
                }
            }
            return result
        }
    }
    
    private fun loadPlantModelDO() {
        val c = collection<PlantModelDO>()
        val plantModelDO = c.findOne(PlantModelDO::id eq DEFAULT_PLANT_MODEL_DO_ID) ?: return
        if (plantModelDO.lockedPaths.isNotEmpty()) {
            for (pathName in plantModelDO.lockedPaths) {
                val path = plantModel.paths[pathName] ?: continue
                plantModel.paths[pathName] = path.copy(isLocked = true)
            }
        }
    }

    // TODO 这个地方不用 globalSyncObject 是否会有问题？
    @Synchronized
    private fun persistPlantModelDO() {
        val c = collection<PlantModelDO>()
        val plantModelDO = c.findOne(PlantModelDO::id eq DEFAULT_PLANT_MODEL_DO_ID)
                ?: PlantModelDO(DEFAULT_PLANT_MODEL_DO_ID)
        val lockedPaths = HashSet<String>()
        plantModel.paths.values.forEach { if (it.isLocked) lockedPaths.add(it.name) }
        
        val newPlantModelDO = plantModelDO.copy(lockedPaths = lockedPaths)
        c.replaceOne(PlantModelDO::id eq DEFAULT_PLANT_MODEL_DO_ID, newPlantModelDO, ReplaceOptions().upsert(true))
    }
    
}