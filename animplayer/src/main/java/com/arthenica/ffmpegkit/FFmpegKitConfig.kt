package com.arthenica.ffmpegkit

import android.content.Context
import android.system.Os
import com.tencent.qgame.animplayer.util.ALog
import java.io.File

object FFmpegKitConfig {

    private const val TAG = "ffmpeg-kit-lite.Config"

    fun registerNewFFmpegPipe(context: Context): String {
        val pipe = File(context.cacheDir, "ffmpeg_lite_pipe_${System.nanoTime()}.raw")
        if (pipe.exists()) {
            pipe.delete()
        }
        return try {
            Os.mkfifo(pipe.absolutePath, 384)
            pipe.absolutePath
        } catch (e: Throwable) {
            ALog.e(TAG, "mkfifo failed, fallback to plain file", e)
            pipe.createNewFile()
            pipe.absolutePath
        }
    }

    fun closeFFmpegPipe(path: String) {
        try {
            File(path).delete()
        } catch (e: Throwable) {
            ALog.e(TAG, "close pipe failed", e)
        }
    }
}
