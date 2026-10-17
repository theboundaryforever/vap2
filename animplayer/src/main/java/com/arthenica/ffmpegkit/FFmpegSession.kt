package com.arthenica.ffmpegkit

import java.util.concurrent.atomic.AtomicLong

class FFmpegSession internal constructor(
    private val command: String
) {

    val sessionId: Long = NEXT_ID.incrementAndGet()

    @Volatile
    var returnCode: ReturnCode? = null
        internal set

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun isCancelled(): Boolean = cancelled

    fun getCommand(): String = command

    companion object {
        private val NEXT_ID = AtomicLong(0)
    }
}
