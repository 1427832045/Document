package com.seer.srd.eventlog

import com.mongodb.client.model.Indexes
import com.seer.srd.RbkError.getAlarmInfoOrNullByCode
import com.seer.srd.db.MongoDBManager
import com.seer.srd.route.service.VehicleService
import com.seer.srd.vehicle.driver.VehicleDriverManager
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.opentcs.drivers.vehicle.VehicleProcessModel
import org.opentcs.kernel.vehicles.DefaultVehicleController
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.*

object ErrorInfoLogger {

  private val logger = LoggerFactory.getLogger(ErrorInfoLogger::class.java)

  private val lastErrorInfos: MutableMap<String, List<ErrorInfoTrace>> = ConcurrentHashMap()

  @Synchronized
  fun statOnVehicleErrorInfo() {
    try {
      val c = MongoDBManager.collection<ErrorInfoTrace>().apply {
        this.createIndex(Indexes.descending("startOn"))
      }

      var controller: DefaultVehicleController?
      var errorInfo: List<VehicleProcessModel.ErrorInfo>
      val warningCodesAsError = "[54004], [54013], [54025]"

      VehicleService.listVehicles().forEach { v ->
        if (lastErrorInfos[v.name] == null) lastErrorInfos[v.name] = c.find(and(
            ErrorInfoTrace::vehicle eq v.name,
            ErrorInfoTrace::endOn eq null
        )).toList()

        controller = VehicleDriverManager.getVehicleControllerOrNull(v.name) as DefaultVehicleController?

        // error code exists in message(message = "[${code}] ${msg}", e.g. "[54004] Controller Emergency Stop!")
        if (controller != null) {
          errorInfo = controller!!.errorInfos.filter {
            it.level in listOf("fatals", "errors") || warningCodesAsError.contains(it.message.substring(0, 7))
          }
          if (errorInfo.isEmpty()) {    // errorInfo is empty
            if (lastErrorInfos[v.name]!!.isNotEmpty()) {
              // update error items (set field `endOn` to `Instant.now()`) exist in lastErrorInfos
              c.updateMany(
                  ErrorInfoTrace::code `in` lastErrorInfos[v.name]!!.map { it.code },
                  setValue(ErrorInfoTrace::endOn, Instant.now())
              )
              // update lastErrorInfos
              lastErrorInfos[v.name] = emptyList()
            }
          } else {

            // 1. insert error items into table `ErrorInfoTrace` which exist in errorInfo but not lastErrorInfos
            // 2. update error items (set field `endOn` to `Instant.now()`) which exist in lastErrorInfos but not errorInfo
            // 3. leave the items alone which exist in both lastErrorInfos and errorInfo

            if (lastErrorInfos[v.name]!!.isEmpty()) {         // lastErrorInfos is empty，insert all errorInfo items into table `ErrorInfoTrace`
              // update lastErrorInfos
              lastErrorInfos[v.name] = errorInfo.map { info ->
                ErrorInfoTrace(
                    vehicle = v.name,
                    code = info.message.substring(1, 6),
                    level = info.level,
                    message = getAlarmInfoOrNullByCode(info.message.substring(1, 6)) ?: info.message.substring(8),
                    startOn = Instant.now()
                )
              }
              //update table `ErrorInfoTrace`
              c.insertMany(lastErrorInfos[v.name]!!)
            } else {

              // get error code in lastErrorInfos
              val lastCodes = lastErrorInfos[v.name]!!.map { it.code }

              // insert error items into table `ErrorInfoTrace` which exist in errorInfo but not lastErrorInfos.
              val newErrorInfos = errorInfo.filter { !lastCodes.contains(it.message.substring(1, 6)) }.map { info ->
                ErrorInfoTrace(
                    vehicle = v.name,
                    code = info.message.substring(1, 6),
                    level = info.level,
                    message = getAlarmInfoOrNullByCode(info.message.substring(1, 6)) ?: info.message.substring(8),
                    startOn = Instant.now()
                )
              }

              // update error items (set field `endOn` to `Instant.now()`) which exist in lastErrorInfos but not errorInfo.
              val endErrorInfos = lastErrorInfos[v.name]!!.filter {
                !errorInfo.map { info -> info.message.substring(1, 6) }.contains(it.code)
              }

              // update lastErrorInfos
              lastErrorInfos[v.name] = lastErrorInfos[v.name]!!.minus(endErrorInfos).plus(newErrorInfos)

              //update table `ErrorInfoTrace`
              if (newErrorInfos.isNotEmpty()) c.insertMany(newErrorInfos)

              //update table `ErrorInfoTrace`
              if (endErrorInfos.isNotEmpty()) c.updateMany(ErrorInfoTrace::id `in` endErrorInfos.map { it.id }, setValue(ErrorInfoTrace::endOn, Instant.now()))

            }
          }
        }
      }
    } catch (e: Exception) {
      logger.error("stat agv alarm info error", e)
    }
  }
}

data class ErrorInfoTrace(
    @BsonId var id: ObjectId = ObjectId(),
    var vehicle: String,
    var code: String,
    var level: String,
    var message: String,
    var startOn: Instant,
    var endOn: Instant? = null
)
//{
//  override fun equals(other: Any?): Boolean {
//    return super.equals(other)
//  }
//
//  override fun hashCode(): Int {
//    var result = vehicle.hashCode()
//    result = 31 * result + code.hashCode()
//    result = 31 * result + level.hashCode()
//    result = 31 * result + message.hashCode()
//    return result
//  }
//}