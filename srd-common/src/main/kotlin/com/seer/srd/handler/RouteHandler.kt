package com.seer.srd.handler

import com.seer.srd.http.getPageNo
import com.seer.srd.http.getPageSize
import com.seer.srd.route.service.ListOrderSequencesQuery
import com.seer.srd.route.service.ListTransportOrdersQuery
import com.seer.srd.route.service.OrderSequenceIOService.listOrderSequenceOutputs
import com.seer.srd.route.service.PlantModelService
import com.seer.srd.route.service.TransportOrderIOService.listTransportOrderOutputs
import com.seer.srd.route.service.TransportOrderIOService.withdrawTransportOrder
import com.seer.srd.util.splitTrim
import io.javalin.http.Context

object RouteHandler {
    
    // 测试调度是否起来了
    fun pingRoute(ctx: Context) {
        val ok = try { // todo
            PlantModelService.getModelXml()
            true
        } catch (err: Exception) {
            false
        }
        
        ctx.json(mapOf("ping" to ok))
    }
    
    fun handleListRouteOrders(ctx: Context) {
        val pageNo = getPageNo(ctx)
        val pageSize = getPageSize(ctx)
        
        val intendedVehicle = ctx.queryParam("intendedVehicle")
        val processingVehicle = ctx.queryParam("processingVehicle")
        val category = ctx.queryParam("category")
        val states = splitTrim(ctx.queryParam("state"), ",")
        
        val name = ctx.queryParam("name")
        val regexp = if (!name.isNullOrBlank()) ".*$name.*" else null
        
        val query = ListTransportOrdersQuery(pageNo, pageSize, intendedVehicle, processingVehicle, category, states, regexp)
        val r = listTransportOrderOutputs(query)
        ctx.json(r)
    }
    
    fun handleListRouteSequences(ctx: Context) {
        val completeStr = ctx.queryParam("complete")
        val finishedStr = ctx.queryParam("finished")
        val failureFatalStr = ctx.queryParam("failureFatal")
        val query = ListOrderSequencesQuery(
            getPageNo(ctx), getPageSize(ctx), ctx.queryParam("namePrefix"),
            if (completeStr == null) null else completeStr == "true",
            if (finishedStr == null) null else finishedStr == "true",
            if (failureFatalStr == null) null else failureFatalStr == "true",
            ctx.queryParam("orderNamePrefix"),
            ctx.queryParam("category"),
            ctx.queryParam("intendedVehicle"),
            ctx.queryParam("processingVehicle")
        )
        val r = listOrderSequenceOutputs(query)
        ctx.json(r)
    }
    
    fun handleWithdrawRouteOrders(ctx: Context) {
        val req = ctx.bodyAsClass(WithdrawRouteOrders::class.java)
        
        val immediate = req.immediate == "true"
        val disableVehicle = req.disableVehicle == "true"
        
        for (order in req.orders) {
            withdrawTransportOrder(order, immediate, disableVehicle)
        }
        ctx.status(204)
    }
    
}

class WithdrawRouteOrders(
    var orders: List<String> = emptyList(),
    var disableVehicle: String = "",
    var immediate: String = ""
)


//export async function receiveRoboRouteReport(ctx: Context) {
//    const req = ctx.request.body
//            if (!(req && size(req))) throw new UserError("EmptyReport", locale("EmptyReport"))
//    if (!(req.createdOn)) throw new UserError("NoCreationTime", "No createdOn")
//    if (!(req.message)) throw new UserError("NoMessage", "No Message")
//    if (!(req.subject)) throw new UserError("NoSubject", "No Subject")
//    if (req.subject.length > 100) throw new UserError("SubjectTooLong", "Subject too long")
//    const report: RoboRouteReport = {
//        id: uidString(),
//        receivedOn: new Date(),
//        createdOn: moment(req.createdOn, "yyyy-MM-ddTHH:mm:ss.SSSZ").toDate(),
//        subject: req.subject,
//        level: req.level,
//        message: req.message
//    }
//    getSystemLogger().info("route report " + JSON.stringify(report))
//    await withTx(ss => insertMany(ss, "RoboRouteReport", [report]))
//    ctx.status = 204
//
//    onRoboRouteReportArrived(report)
//}
//
//export async function listRoboRouteReport(ctx: Context) {
//    const pageNo = stringToInt(ctx.query.pageNo, 1)
//    const pageSize = stringToInt(ctx.query.pageSize, 100)
//    const sort: FindSort[] = [{ column: "createdOn", order: "desc" }]
//    const r = await withTx(ss => findWithTotal(ss, "RoboRouteReport", { pageNo, pageSize, sort }))
//    ctx.body = r
//}
//


