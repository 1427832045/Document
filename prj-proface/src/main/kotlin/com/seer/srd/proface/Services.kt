package com.seer.srd.proface

import com.seer.srd.BusinessError
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import org.slf4j.LoggerFactory

object Services {

  private val logger = LoggerFactory.getLogger(Services::class.java)

  fun listSiteByType(type: String, filled: Boolean): List<StoreSite> {
    val sites = StoreSiteService.listStoreSites().filter { it.type == type }.sortedBy { it.id }
    if (sites.size <= CUSTOM_CONFIG.siteRange) throw BusinessError("仓库${type}的总库位不足:${sites.size}")
    return if (filled) sites.subList(0, CUSTOM_CONFIG.siteRange)
    else sites.subList(CUSTOM_CONFIG.siteRange, sites.size)
  }
}