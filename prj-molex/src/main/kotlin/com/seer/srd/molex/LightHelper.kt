package com.seer.srd.molex

import com.seer.srd.BusinessError
import org.slf4j.LoggerFactory

object LightHelper {

  private val logger = LoggerFactory.getLogger(LightHelper::class.java)

  fun getByteArrayByAreaId(areaId: String): List<ByteArray> {
    try {
      val area = SiteAreaService.getAreaModBusById(areaId)

      val byteArray1 = byteArrayOf(0, 0, 0, 0)

      val byteArray2 = byteArrayOf(0, 0, 0, 0)

      // 按照地址排序后的address
      val siteIdToAddress = CUSTOM_CONFIG.areaToCabinet[areaId]?.siteIdToAddress?.toList()?.sortedBy { (_, addr) ->
        addr.red
      } ?: throw BusinessError("No such config $areaId")

      when (areaId) {
        "area1" -> {
          val state1 = area.siteStateMap[siteIdToAddress[0].first]
          val state2 = area.siteStateMap[siteIdToAddress[1].first]
          val state3 = area.siteStateMap[siteIdToAddress[2].first]
          val state4 = area.siteStateMap[siteIdToAddress[3].first]
          if (state1 == LIGHT_STATE.RED && state2 == LIGHT_STATE.RED) {
            byteArray1[0] = 17
            byteArray2[0] = 17
          }
          else if (state1 == LIGHT_STATE.YELLOW && state2 == LIGHT_STATE.RED) {
            byteArray1[0] = 18
            byteArray2[0] = 18
          }
          else if (state1 == LIGHT_STATE.GREEN && state2 == LIGHT_STATE.RED) {
            byteArray1[0] = 20
            byteArray2[0] = 20
          }
          else if (state1 == LIGHT_STATE.RED && state2 == LIGHT_STATE.YELLOW) {
            byteArray1[0] = 33
            byteArray2[0] = 33
          }
          else if (state1 == LIGHT_STATE.YELLOW && state2 == LIGHT_STATE.YELLOW) {
            byteArray1[0] = 34
            byteArray2[0] = 34
          }
          else if (state1 == LIGHT_STATE.GREEN && state2 == LIGHT_STATE.YELLOW) {
            byteArray1[0] = 36
            byteArray2[0] = 36
          }
          else if (state1 == LIGHT_STATE.RED && state2 == LIGHT_STATE.GREEN) {
            byteArray1[0] = 65
            byteArray2[0] = 65
          }
          else if (state1 == LIGHT_STATE.YELLOW && state2 == LIGHT_STATE.GREEN) {
            byteArray1[0] = 66
            byteArray2[0] = 66
          }
          else if (state1 == LIGHT_STATE.GREEN && state2 == LIGHT_STATE.GREEN) {
            byteArray1[0] = 68
            byteArray2[0] = 68
          }
          else if (state1 == LIGHT_STATE.RED_TWINKLE && state2 == LIGHT_STATE.RED) {
            byteArray1[0] = 17
            byteArray2[0] = 16
          }
          else if (state1 == LIGHT_STATE.YELLOW_TWINKLE && state2 == LIGHT_STATE.RED) {
            byteArray1[0] = 18
            byteArray2[0] = 16
          }
          else if (state1 == LIGHT_STATE.GREEN_TWINKLE && state2 == LIGHT_STATE.RED) {
            byteArray1[0] = 20
            byteArray2[0] = 16
          }
          else if (state1 == LIGHT_STATE.RED_TWINKLE && state2 == LIGHT_STATE.YELLOW) {
            byteArray1[0] = 33
            byteArray2[0] = 32
          }
          else if (state1 == LIGHT_STATE.YELLOW_TWINKLE && state2 == LIGHT_STATE.YELLOW) {
            byteArray1[0] = 34
            byteArray2[0] = 32
          }
          else if (state1 == LIGHT_STATE.GREEN_TWINKLE && state2 == LIGHT_STATE.YELLOW) {
            byteArray1[0] = 36
            byteArray2[0] = 32
          }
          else if (state1 == LIGHT_STATE.RED_TWINKLE && state2 == LIGHT_STATE.GREEN) {
            byteArray1[0] = 65
            byteArray2[0] = 64
          }
          else if (state1 == LIGHT_STATE.YELLOW_TWINKLE && state2 == LIGHT_STATE.GREEN) {
            byteArray1[0] = 66
            byteArray2[0] = 64
          }
          else if (state1 == LIGHT_STATE.GREEN_TWINKLE && state2 == LIGHT_STATE.GREEN) {
            byteArray1[0] = 68
            byteArray2[0] = 64
          }
          else if (state1 == LIGHT_STATE.RED && state2 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[0] = 17
            byteArray2[0] = 1
          }
          else if (state1 == LIGHT_STATE.YELLOW && state2 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[0] = 18
            byteArray2[0] = 2
          }
          else if (state1 == LIGHT_STATE.GREEN && state2 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[0] = 20
            byteArray2[0] = 4
          }
          else if (state1 == LIGHT_STATE.RED && state2 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[0] = 33
            byteArray2[0] = 1
          }
          else if (state1 == LIGHT_STATE.YELLOW && state2 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[0] = 34
            byteArray2[0] = 2
          }
          else if (state1 == LIGHT_STATE.GREEN && state2 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[0] = 36
            byteArray2[0] = 4
          }
          else if (state1 == LIGHT_STATE.RED && state2 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[0] = 65
            byteArray2[0] = 1
          }
          else if (state1 == LIGHT_STATE.YELLOW && state2 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[0] = 66
            byteArray2[0] = 2
          }
          else if (state1 == LIGHT_STATE.GREEN && state2 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[0] = 68
            byteArray2[0] = 4
          }
          else if (state1 == LIGHT_STATE.RED_TWINKLE && state2 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[0] = 17
            byteArray2[0] = 0
          }
          else if (state1 == LIGHT_STATE.YELLOW_TWINKLE && state2 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[0] = 18
            byteArray2[0] = 0
          }
          else if (state1 == LIGHT_STATE.GREEN_TWINKLE && state2 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[0] = 20
            byteArray2[0] = 0
          }
          else if (state1 == LIGHT_STATE.RED_TWINKLE && state2 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[0] = 33
            byteArray2[0] = 0
          }
          else if (state1 == LIGHT_STATE.YELLOW_TWINKLE && state2 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[0] = 34
            byteArray2[0] = 0
          }
          else if (state1 == LIGHT_STATE.GREEN_TWINKLE && state2 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[0] = 36
            byteArray2[0] = 0
          }
          else if (state1 == LIGHT_STATE.RED_TWINKLE && state2 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[0] = 65
            byteArray2[0] = 0
          }
          else if (state1 == LIGHT_STATE.YELLOW_TWINKLE && state2 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[0] = 66
            byteArray2[0] = 2
          }
          else if (state1 == LIGHT_STATE.GREEN_TWINKLE && state2 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[0] = 68
            byteArray2[0] = 4
          }

          if (state3 == LIGHT_STATE.RED) {
            byteArray1[1] = 1
            byteArray2[1] = 1
          } else if (state3 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[1] = 1
            byteArray2[1] = 0
          } else if (state3 == LIGHT_STATE.YELLOW) {
            byteArray1[1] = 2
            byteArray2[1] = 2
          } else if (state3 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[1] = 2
            byteArray2[1] = 0
          } else if (state3 == LIGHT_STATE.GREEN) {
            byteArray1[1] = 4
            byteArray2[1] = 4
          } else if (state3 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[1] = 4
            byteArray2[1] = 0
          }

          if (state4 == LIGHT_STATE.RED) {
            byteArray1[2] = 1
            byteArray2[2] = 1
          } else if (state3 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[2] = 1
            byteArray2[2] = 0
          } else if (state3 == LIGHT_STATE.YELLOW) {
            byteArray1[2] = 2
            byteArray2[2] = 2
          } else if (state3 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[2] = 2
            byteArray2[2] = 0
          } else if (state3 == LIGHT_STATE.GREEN) {
            byteArray1[2] = 4
            byteArray2[2] = 4
          } else if (state3 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[2] = 4
            byteArray2[2] = 0
          }

          if (state4 == LIGHT_STATE.RED) {
            byteArray1[3] = 1
            byteArray2[3] = 1
          } else if (state3 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[3] = 1
            byteArray2[3] = 0
          } else if (state3 == LIGHT_STATE.YELLOW) {
            byteArray1[3] = 2
            byteArray2[3] = 2
          } else if (state3 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[3] = 2
            byteArray2[3] = 0
          } else if (state3 == LIGHT_STATE.GREEN) {
            byteArray1[3] = 4
            byteArray2[3] = 4
          } else if (state3 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[3] = 4
            byteArray2[3] = 0
          }

        }
        else -> {
          val state1 = if (siteIdToAddress.isNotEmpty()) area.siteStateMap[siteIdToAddress[0].first] else null
          val state2 = if (siteIdToAddress.size >= 2) area.siteStateMap[siteIdToAddress[1].first] else null
          val state3 = if (siteIdToAddress.size >= 3) area.siteStateMap[siteIdToAddress[2].first] else null
          val state4 = if (siteIdToAddress.size >= 4) area.siteStateMap[siteIdToAddress[3].first] else null
          val state5 = if (siteIdToAddress.size >= 5) area.siteStateMap[siteIdToAddress[4].first] else null
          val state6 = if (siteIdToAddress.size >= 6) area.siteStateMap[siteIdToAddress[5].first] else null
          if (state1 == LIGHT_STATE.RED && state2 == LIGHT_STATE.RED) {
            byteArray1[0] = 17
            byteArray2[0] = 17
          }
          else if (state1 == LIGHT_STATE.YELLOW && state2 == LIGHT_STATE.RED) {
            byteArray1[0] = 18
            byteArray2[0] = 18
          }
          else if (state1 == LIGHT_STATE.GREEN && state2 == LIGHT_STATE.RED) {
            byteArray1[0] = 20
            byteArray2[0] = 20
          }
          else if (state1 == LIGHT_STATE.RED && state2 == LIGHT_STATE.YELLOW) {
            byteArray1[0] = 33
            byteArray2[0] = 33
          }
          else if (state1 == LIGHT_STATE.YELLOW && state2 == LIGHT_STATE.YELLOW) {
            byteArray1[0] = 34
            byteArray2[0] = 34
          }
          else if (state1 == LIGHT_STATE.GREEN && state2 == LIGHT_STATE.YELLOW) {
            byteArray1[0] = 36
            byteArray2[0] = 36
          }
          else if (state1 == LIGHT_STATE.RED && state2 == LIGHT_STATE.GREEN) {
            byteArray1[0] = 65
            byteArray2[0] = 65
          }
          else if (state1 == LIGHT_STATE.YELLOW && state2 == LIGHT_STATE.GREEN) {
            byteArray1[0] = 66
            byteArray2[0] = 66
          }
          else if (state1 == LIGHT_STATE.GREEN && state2 == LIGHT_STATE.GREEN) {
            byteArray1[0] = 68
            byteArray2[0] = 68
          }
          else if (state1 == LIGHT_STATE.RED_TWINKLE && state2 == LIGHT_STATE.RED) {
            byteArray1[0] = 17
            byteArray2[0] = 16
          }
          else if (state1 == LIGHT_STATE.YELLOW_TWINKLE && state2 == LIGHT_STATE.RED) {
            byteArray1[0] = 18
            byteArray2[0] = 16
          }
          else if (state1 == LIGHT_STATE.GREEN_TWINKLE && state2 == LIGHT_STATE.RED) {
            byteArray1[0] = 20
            byteArray2[0] = 16
          }
          else if (state1 == LIGHT_STATE.RED_TWINKLE && state2 == LIGHT_STATE.YELLOW) {
            byteArray1[0] = 33
            byteArray2[0] = 32
          }
          else if (state1 == LIGHT_STATE.YELLOW_TWINKLE && state2 == LIGHT_STATE.YELLOW) {
            byteArray1[0] = 34
            byteArray2[0] = 32
          }
          else if (state1 == LIGHT_STATE.GREEN_TWINKLE && state2 == LIGHT_STATE.YELLOW) {
            byteArray1[0] = 36
            byteArray2[0] = 32
          }
          else if (state1 == LIGHT_STATE.RED_TWINKLE && state2 == LIGHT_STATE.GREEN) {
            byteArray1[0] = 65
            byteArray2[0] = 64
          }
          else if (state1 == LIGHT_STATE.YELLOW_TWINKLE && state2 == LIGHT_STATE.GREEN) {
            byteArray1[0] = 66
            byteArray2[0] = 64
          }
          else if (state1 == LIGHT_STATE.GREEN_TWINKLE && state2 == LIGHT_STATE.GREEN) {
            byteArray1[0] = 68
            byteArray2[0] = 64
          }
          else if (state1 == LIGHT_STATE.RED && state2 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[0] = 17
            byteArray2[0] = 1
          }
          else if (state1 == LIGHT_STATE.YELLOW && state2 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[0] = 18
            byteArray2[0] = 2
          }
          else if (state1 == LIGHT_STATE.GREEN && state2 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[0] = 20
            byteArray2[0] = 4
          }
          else if (state1 == LIGHT_STATE.RED && state2 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[0] = 33
            byteArray2[0] = 1
          }
          else if (state1 == LIGHT_STATE.YELLOW && state2 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[0] = 34
            byteArray2[0] = 2
          }
          else if (state1 == LIGHT_STATE.GREEN && state2 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[0] = 36
            byteArray2[0] = 4
          }
          else if (state1 == LIGHT_STATE.RED && state2 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[0] = 65
            byteArray2[0] = 1
          }
          else if (state1 == LIGHT_STATE.YELLOW && state2 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[0] = 66
            byteArray2[0] = 2
          }
          else if (state1 == LIGHT_STATE.GREEN && state2 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[0] = 68
            byteArray2[0] = 4
          }
          else if (state1 == LIGHT_STATE.RED_TWINKLE && state2 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[0] = 17
            byteArray2[0] = 0
          }
          else if (state1 == LIGHT_STATE.YELLOW_TWINKLE && state2 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[0] = 18
            byteArray2[0] = 0
          }
          else if (state1 == LIGHT_STATE.GREEN_TWINKLE && state2 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[0] = 20
            byteArray2[0] = 0
          }
          else if (state1 == LIGHT_STATE.RED_TWINKLE && state2 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[0] = 33
            byteArray2[0] = 0
          }
          else if (state1 == LIGHT_STATE.YELLOW_TWINKLE && state2 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[0] = 34
            byteArray2[0] = 0
          }
          else if (state1 == LIGHT_STATE.GREEN_TWINKLE && state2 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[0] = 36
            byteArray2[0] = 0
          }
          else if (state1 == LIGHT_STATE.RED_TWINKLE && state2 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[0] = 65
            byteArray2[0] = 0
          }
          else if (state1 == LIGHT_STATE.YELLOW_TWINKLE && state2 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[0] = 66
            byteArray2[0] = 0
          }
          else if (state1 == LIGHT_STATE.GREEN_TWINKLE && state2 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[0] = 68
            byteArray2[0] = 0
          }

          if (state3 == LIGHT_STATE.RED) {
            byteArray1[1] = 1
            byteArray2[1] = 1
          } else if (state3 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[1] = 1
            byteArray2[1] = 0
          } else if (state3 == LIGHT_STATE.YELLOW) {
            byteArray1[1] = 2
            byteArray2[1] = 2
          } else if (state3 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[1] = 2
            byteArray2[1] = 0
          } else if (state3 == LIGHT_STATE.GREEN) {
            byteArray1[1] = 4
            byteArray2[1] = 4
          } else if (state3 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[1] = 4
            byteArray2[1] = 0
          }

          if (state4 == LIGHT_STATE.RED && state5 == LIGHT_STATE.RED) {
            byteArray1[2] = 17
            byteArray2[2] = 17
          }
          else if (state4 == LIGHT_STATE.YELLOW && state5 == LIGHT_STATE.RED) {
            byteArray1[2] = 18
            byteArray2[2] = 18
          }
          else if (state4 == LIGHT_STATE.GREEN && state5 == LIGHT_STATE.RED) {
            byteArray1[2] = 20
            byteArray2[2] = 20
          }
          else if (state4 == LIGHT_STATE.RED && state5 == LIGHT_STATE.YELLOW) {
            byteArray1[2] = 33
            byteArray2[2] = 33
          }
          else if (state4 == LIGHT_STATE.YELLOW && state5 == LIGHT_STATE.YELLOW) {
            byteArray1[2] = 34
            byteArray2[2] = 34
          }
          else if (state4 == LIGHT_STATE.GREEN && state5 == LIGHT_STATE.YELLOW) {
            byteArray1[2] = 36
            byteArray2[2] = 36
          }
          else if (state4 == LIGHT_STATE.RED && state5 == LIGHT_STATE.GREEN) {
            byteArray1[2] = 65
            byteArray2[2] = 65
          }
          else if (state4 == LIGHT_STATE.YELLOW && state5 == LIGHT_STATE.GREEN) {
            byteArray1[2] = 66
            byteArray2[2] = 66
          }
          else if (state4 == LIGHT_STATE.GREEN && state5 == LIGHT_STATE.GREEN) {
            byteArray1[2] = 68
            byteArray2[2] = 68
          }
          else if (state4 == LIGHT_STATE.RED_TWINKLE && state5 == LIGHT_STATE.RED) {
            byteArray1[2] = 17
            byteArray2[2] = 16
          }
          else if (state4 == LIGHT_STATE.YELLOW_TWINKLE && state5 == LIGHT_STATE.RED) {
            byteArray1[2] = 18
            byteArray2[2] = 16
          }
          else if (state4 == LIGHT_STATE.GREEN_TWINKLE && state5 == LIGHT_STATE.RED) {
            byteArray1[2] = 20
            byteArray2[2] = 16
          }
          else if (state4 == LIGHT_STATE.RED_TWINKLE && state5 == LIGHT_STATE.YELLOW) {
            byteArray1[2] = 33
            byteArray2[2] = 32
          }
          else if (state4 == LIGHT_STATE.YELLOW_TWINKLE && state5 == LIGHT_STATE.YELLOW) {
            byteArray1[2] = 34
            byteArray2[2] = 32
          }
          else if (state4 == LIGHT_STATE.GREEN_TWINKLE && state5 == LIGHT_STATE.YELLOW) {
            byteArray1[2] = 36
            byteArray2[2] = 32
          }
          else if (state4 == LIGHT_STATE.RED_TWINKLE && state5 == LIGHT_STATE.GREEN) {
            byteArray1[2] = 65
            byteArray2[2] = 64
          }
          else if (state4 == LIGHT_STATE.YELLOW_TWINKLE && state5 == LIGHT_STATE.GREEN) {
            byteArray1[2] = 66
            byteArray2[2] = 64
          }
          else if (state4 == LIGHT_STATE.GREEN_TWINKLE && state5 == LIGHT_STATE.GREEN) {
            byteArray1[2] = 68
            byteArray2[2] = 64
          }
          else if (state4 == LIGHT_STATE.RED && state5 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[2] = 17
            byteArray2[2] = 1
          }
          else if (state4 == LIGHT_STATE.YELLOW && state5 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[2] = 18
            byteArray2[2] = 2
          }
          else if (state4 == LIGHT_STATE.GREEN && state5 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[2] = 20
            byteArray2[2] = 4
          }
          else if (state4 == LIGHT_STATE.RED && state5 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[2] = 33
            byteArray2[2] = 1
          }
          else if (state4 == LIGHT_STATE.YELLOW && state5 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[2] = 34
            byteArray2[2] = 2
          }
          else if (state4 == LIGHT_STATE.GREEN && state5 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[2] = 36
            byteArray2[2] = 4
          }
          else if (state4 == LIGHT_STATE.RED && state5 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[2] = 65
            byteArray2[2] = 1
          }
          else if (state4 == LIGHT_STATE.YELLOW && state5 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[2] = 66
            byteArray2[2] = 2
          }
          else if (state4 == LIGHT_STATE.GREEN && state5 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[2] = 68
            byteArray2[2] = 4
          }
          else if (state4 == LIGHT_STATE.RED_TWINKLE && state5 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[2] = 17
            byteArray2[2] = 0
          }
          else if (state4 == LIGHT_STATE.YELLOW_TWINKLE && state5 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[2] = 18
            byteArray2[2] = 0
          }
          else if (state4 == LIGHT_STATE.GREEN_TWINKLE && state5 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[2] = 20
            byteArray2[2] = 0
          }
          else if (state4 == LIGHT_STATE.RED_TWINKLE && state5 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[2] = 33
            byteArray2[2] = 0
          }
          else if (state4 == LIGHT_STATE.YELLOW_TWINKLE && state5 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[2] = 34
            byteArray2[2] = 0
          }
          else if (state4 == LIGHT_STATE.GREEN_TWINKLE && state5 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[2] = 36
            byteArray2[2] = 0
          }
          else if (state4 == LIGHT_STATE.RED_TWINKLE && state5 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[2] = 65
            byteArray2[2] = 0
          }
          else if (state4 == LIGHT_STATE.YELLOW_TWINKLE && state5 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[2] = 66
            byteArray2[2] = 0
          }
          else if (state4 == LIGHT_STATE.GREEN_TWINKLE && state5 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[2] = 68
            byteArray2[2] = 0
          }

          if (state6 == LIGHT_STATE.RED) {
            byteArray1[3] = 1
            byteArray2[3] = 1
          } else if (state6 == LIGHT_STATE.RED_TWINKLE) {
            byteArray1[3] = 1
            byteArray2[3] = 0
          } else if (state6 == LIGHT_STATE.YELLOW) {
            byteArray1[3] = 2
            byteArray2[3] = 2
          } else if (state6 == LIGHT_STATE.YELLOW_TWINKLE) {
            byteArray1[3] = 2
            byteArray2[3] = 0
          } else if (state6 == LIGHT_STATE.GREEN) {
            byteArray1[3] = 4
            byteArray2[3] = 4
          } else if (state6 == LIGHT_STATE.GREEN_TWINKLE) {
            byteArray1[3] = 4
            byteArray2[3] = 0
          }
        }
      }
      return listOf(byteArray1, byteArray2)
    } catch (e: Exception) {
      logger.error("init byte array error", e)
    }
    return emptyList()
  }
}