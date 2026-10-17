package com.arthenica.ffmpegkit

import com.tencent.qgame.animplayer.util.ALog

object FFmpegKit {

    private const val TAG = "ffmpeg-kit-lite.FFmpegKit"

    fun executeAsync(
        command: String,
        completeCallback: ((FFmpegSession) -> Unit)? = null
    ): FFmpegSession {
        val session = FFmpegSession(command)
        val request = parseDecodeRequest(command)

        Thread({
            val code = try {
                if (request == null) {
                    ALog.e(TAG, "Unsupported command: $command")
                    1
                } else {
                    LiteFFmpegNative.decodeToRawVideo(
                        request.inputPath,
                        request.outputPath,
                        session,
                        request.threads,
                        request.outputWidth,
                        request.outputHeight
                    )
                }
            } catch (e: Throwable) {
                ALog.e(TAG, "executeAsync failed", e)
                1
            }
            session.returnCode = ReturnCode(code)
            completeCallback?.invoke(session)
        }, "ffmpeg-lite-${session.sessionId}").start()

        return session
    }

    fun executeFramesAsync(
        command: String,
        frameCallback: LiteFFmpegFrameCallback,
        completeCallback: ((FFmpegSession) -> Unit)? = null
    ): FFmpegSession {
        val session = FFmpegSession(command)
        val request = parseDecodeRequest(command)

        Thread({
            val code = try {
                if (request == null) {
                    ALog.e(TAG, "Unsupported frame command: $command")
                    1
                } else {
                    LiteFFmpegNative.decodeToFrames(
                        request.inputPath,
                        session,
                        request.threads,
                        request.outputWidth,
                        request.outputHeight,
                        frameCallback
                    )
                }
            } catch (e: Throwable) {
                ALog.e(TAG, "executeFramesAsync failed", e)
                1
            }
            session.returnCode = ReturnCode(code)
            completeCallback?.invoke(session)
        }, "ffmpeg-lite-frames-${session.sessionId}").start()

        return session
    }

    private fun parseDecodeRequest(command: String): DecodeRequest? {
        val tokens = splitCommand(command)
        val inputIndex = tokens.indexOf("-i")
        if (inputIndex < 0 || inputIndex + 1 >= tokens.size) {
            return null
        }

        val inputPath = tokens[inputIndex + 1]
        val outputPath = tokens.asReversed()
            .firstOrNull { it != "-y" && !it.startsWith("-") && it != inputPath }
            ?: return null
        val threads = tokens.indexOf("-threads")
            .takeIf { it >= 0 && it + 1 < tokens.size }
            ?.let { tokens[it + 1].toIntOrNull() }
            ?: 0
        val outputWidth = tokens.indexOf("-vap_width")
            .takeIf { it >= 0 && it + 1 < tokens.size }
            ?.let { tokens[it + 1].toIntOrNull() }
            ?: 0
        val outputHeight = tokens.indexOf("-vap_height")
            .takeIf { it >= 0 && it + 1 < tokens.size }
            ?.let { tokens[it + 1].toIntOrNull() }
            ?: 0

        return DecodeRequest(inputPath, outputPath, threads, outputWidth, outputHeight)
    }

    private fun splitCommand(command: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var inQuote = false

        command.forEach { char ->
            when {
                char == '"' -> inQuote = !inQuote
                char.isWhitespace() && !inQuote -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> current.append(char)
            }
        }

        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    private data class DecodeRequest(
        val inputPath: String,
        val outputPath: String,
        val threads: Int,
        val outputWidth: Int,
        val outputHeight: Int
    )
}
