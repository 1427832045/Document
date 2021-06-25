package com.seer.srd.route.dg

import com.seer.srd.route.domain.SimpleDG
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("com.seer.srd.route.dg.DGMaskManager")

typealias ChainableFunction<T> = (input: T) -> T
typealias DGMask = ChainableFunction<SimpleDG>

fun <T> chainFunctions(functions: List<ChainableFunction<T>>): ChainableFunction<T> {
    return { input: T ->
        var output = input
        for (f in functions) {
            output = f(output)
        }
        output
    }
}

object DGMaskManager {
    private val maskedDGs: MutableMap<Int, SimpleDG> = mutableMapOf(0 to SimpleDG())
    private var maskedDGVersionNumber: Int = 0

    val currentDG
        get() = maskedDGs[maskedDGVersionNumber] ?: error("Wrong dg version number '$maskedDGVersionNumber'.")

    val originalDG
        get() = maskedDGs[0] ?: error("No original dg")
    
    fun maskDG(masks: List<DGMask>) {
        LOG.debug("Applying masks to dg.")
        val previousDG: SimpleDG = currentDG
        maskedDGVersionNumber += 1
        maskedDGs[maskedDGVersionNumber] = chainFunctions(masks)(previousDG)
    }

    fun undoLastSetOfMasks() {
        LOG.debug("Undoing last set of masks.")
        if (maskedDGVersionNumber == 0) {
            LOG.debug("DG version number is already 0, cannot undo.")
            return
        } else {
            maskedDGs.remove(maskedDGVersionNumber)
            maskedDGVersionNumber -= 1
        }
    }

    fun undoAllMasks() {
        LOG.debug("Undoing all dg masks.")
        if (maskedDGVersionNumber == 0) {
            return
        }
        maskedDGs.keys.removeAll(1..maskedDGVersionNumber)
        maskedDGVersionNumber = 0
    }
}