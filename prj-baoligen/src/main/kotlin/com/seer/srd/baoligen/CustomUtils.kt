package com.seer.srd.baoligen

import com.seer.srd.BusinessError
import com.seer.srd.route.service.PlantModelService

fun checkStoreSiteAvailable(siteId: String) {
    PlantModelService.getPlantModel().locations[siteId] ?: throw BusinessError("不存在库位【$siteId】")
}