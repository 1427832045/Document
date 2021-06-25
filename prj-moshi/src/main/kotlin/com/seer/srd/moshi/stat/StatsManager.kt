package com.seer.srd.moshi.stat

import com.seer.srd.scheduler.scheduler
import org.quartz.CronScheduleBuilder.cronSchedule
import org.quartz.Job
import org.quartz.JobBuilder.newJob
import org.quartz.JobExecutionContext
import org.quartz.TriggerBuilder.newTrigger

fun initStats() {
    initJobFrequently()
    initJobDaily()
}

private fun initJobFrequently() {
    val job = newJob(ByHourJob::class.java)
        .withIdentity("JobFrequently", "stats")
        .build()
    val trigger = newTrigger().withIdentity("JobFrequently", "stats")
        .startNow()
        .withSchedule(cronSchedule("0 5/10 * ? * * *"))  // 每10分
        .build()
    scheduler.scheduleJob(job, trigger)
}

class ByHourJob : Job {
    override fun execute(context: JobExecutionContext) {
        doStatFrequently()
    }
}

private fun initJobDaily() {
    val job = newJob(ByDayJob::class.java)
        .withIdentity("JobDaily", "stats")
        .build()
    val trigger = newTrigger().withIdentity("JobDaily", "stats")
        .startNow()
        .withSchedule(cronSchedule("0 0 2 ? * * *"))  // 每天2点
        .build()
    scheduler.scheduleJob(job, trigger)
}

class ByDayJob : Job {
    override fun execute(context: JobExecutionContext) {
        doStatDaily()
    }
}