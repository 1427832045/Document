package org.opentcs.util.logging

import org.slf4j.LoggerFactory
import java.lang.Thread.UncaughtExceptionHandler
import kotlin.system.exitProcess

private val LOG = LoggerFactory.getLogger(UncaughtExceptionLogger::class.java)

/**
 * An UncaughtExceptionHandler that logs everything not caught and then exits.
 */
class UncaughtExceptionLogger(
    // A flag indicating whether to exit on uncaught exceptions or not.
    private val doExit: Boolean
) : UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        // Log the exception, and then get out of here.
        LOG.error("Unhandled exception", e)
        if (doExit) {
            exitProcess(1)
        }
    }

}