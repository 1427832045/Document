package com.seer.srd.siemensCd

import com.seer.srd.Application
import com.seer.srd.setVersion

fun main() {
    setVersion("l2", "3.0.13.1")

    Application.initialize()
    Application.start()
}