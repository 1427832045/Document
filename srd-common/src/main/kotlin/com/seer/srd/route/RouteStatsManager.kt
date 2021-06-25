package com.seer.srd.route

import com.seer.srd.domain.PagingResult
import org.bson.types.ObjectId
import java.time.Instant

class RouteStatsQuery(
    val pageNo: Int = 1,
    val pageSize: Int = 10,
    val startTime: Instant? = null,
    val event: String? = null,
    val label: String? = null
)

fun getRouteStats(query: RouteStatsQuery): PagingResult<RouteStats> {
    //Instant startTimeFilter = startTime == null ? Instant.ofEpochMilli(0) : startTime;
    //String labelFilter = label == null ? "%" : label+"%";
    //String eventFilter = event == null ? "%" : event+"%";
    //String jpqlQuery = "select t from "+
    //"StatisticalRecordEntity t " +
    //        "where t.timestamp > :startTime " +
    //        "and t.label like :labelFilter " +
    //        "and t.event like :eventFilter " +
    //        "order by t.timestamp"; // TODO: should we use desc?
    //EntityManager em = persistingWriter . createEntityManager ();
    //List<StatisticalRecordEntity> resultList = em . createQuery (jpqlQuery, StatisticalRecordEntity.class)
    //.setFirstResult(actualPageSize * (actualPageNo - 1)).setMaxResults(actualPageSize)
    //    .setParameter("startTime", startTimeFilter)
    //    .setParameter("labelFilter", labelFilter)
    //    .setParameter("eventFilter", eventFilter)
    //    .getResultList();
    //em.close();
    //StatisticsPage resultPage = new StatisticsPage();
    //resultPage.setPage(
    //    resultList.stream()
    //        .map(StatisticsDetail::new).collect(Collectors.toList())
    //);
    //resultPage.setPageNo(actualPageNo);
    //resultPage.setPageSize(actualPageSize);
    //resultPage.setTotal(resultList.size());
    //return resultPage;
    return PagingResult(0, emptyList(), query.pageNo, query.pageSize)
}

class RouteStats {
    var id: ObjectId = ObjectId()
    var event: String = ""
    var label: String = ""
    var timestamp: Instant = Instant.now()
}
