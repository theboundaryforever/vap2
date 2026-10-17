package com.arthenica.ffmpegkit

internal object LiteFFmpegNative {

    init {
        System.loadLibrary("avutil")
        System.loadLibrary("avcodec")
        System.loadLibrary("avformat")
        System.loadLibrary("swscale")
        System.loadLibrary("ffmpeglite_jni")
    }

    external fun decodeToRawVideo(
        inputPath: String,
        outputPath: String,
        session: FFmpegSession,
        threads: Int,
        outputWidth: Int,
        outputHeight: Int
    ): Int

    external fun decodeToFrames(
        inputPath: String,
        session: FFmpegSession,
        threads: Int,
        outputWidth: Int,
        outputHeight: Int,
        callback: LiteFFmpegFrameCallback
    ): Int

    external fun probeVideoSize(inputPath: String): IntArray?
}
