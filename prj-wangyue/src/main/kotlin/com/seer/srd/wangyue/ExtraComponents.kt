package com.seer.srd.wangyue

import com.seer.srd.BusinessError
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.wangyue.WangYueApp.unfinishedTasks
import org.slf4j.LoggerFactory

object ExtraComponents {
  private val logger = LoggerFactory.getLogger(ExtraComponents::class.java)

  val extraComponent = listOf(
      TaskComponentDef(
          "extra", "WangYue:checkWait2", "检查WAIT-2", "", false, listOf(
      ), false) { _, ctx ->
        val site = StoreSiteService.getExistedStoreSiteById("WAIT-2")
        if (site.locked && site.lockedBy.isNotBlank()) {
          val task = unfinishedTasks[site.lockedBy]
          if (task != null && task.transports[3].state < RobotTaskState.Success) {
            throw BusinessError("等待${task.transports[0].processingRobot}到达WAIT-2...")
          }
        } else if(!site.locked) {
          StoreSiteService.lockSiteIfNotLock(site.id, ctx.task.id, "from location WAIT-3")
        }
      }
  )
}