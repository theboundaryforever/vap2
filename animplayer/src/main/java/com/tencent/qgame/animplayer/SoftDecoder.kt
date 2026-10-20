package com.tencent.qgame.animplayer

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.LiteFFmpegFrameCallback
import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.PlaybackSize
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SoftDecoder(player: AnimPlayer) : Decoder(player) {

    companion object {
        private const val TAG = "${Constant.TAG}.SoftDecoder"
        private const val RENDER_TIMEOUT_SEC = 5L
        // 只留一块 YUV。渲染还没把这块交还之前，解码在原地等，不再同时留两帧。
        private const val BUFFERED_FRAME_COUNT = 1

        // 对应电脑端电脑压缩素材设定的帧率（例如 10 帧或 12 帧）
        // 如果你电脑端脚本改成了 12，这里也请同步改成 12
        private const val TARGET_SOFT_FPS = 10
    }

    private class FrameSlot(size: Int) {
        val data: ByteBuffer = ByteBuffer.allocateDirect(size)
    }

    @Volatile
    private var running = false

    @Volatile
    private var destroyed = false

    // 【核心防御】失败闭锁标记。确保一有错误只上报一次，后续 cancel 触发的回调直接拦截，彻底堵死死循环
    @Volatile
    private var isFailureReported = false

    private var decodeThreadRef: Thread? = null
    private var currentSession: FFmpegSession? = null
    private var tempVideoFile: File? = null
    private var renderReleased = false

    override fun start(fileContainer: IFileContainer) {
        val surface = player.animView.getSurfaceTexture()
        if (surface == null) {
            ALog.e(TAG, "Cannot start SoftDecoder: SurfaceTexture is null")
            onFailed(Constant.REPORT_ERROR_TYPE_CREATE_RENDER, "SurfaceTexture is null")
            return
        }

        destroyed = false
        renderReleased = false
        isStopReq = false
        isRunning = true
        isFailureReported = false // 👈 每次全新播放前重置错误上报锁
        playLoop = player.playLoop
        fps = player.fps

        ALog.i(TAG, "SoftDecoder start, playLoop=$playLoop, fps=$fps")

        decodeThread.handler?.post {
            startDecode(fileContainer)
        } ?: run {
            decodeThreadRef = Thread({ startDecode(fileContainer) }, "VAP-SoftDecode").apply { start() }
        }
    }

    private fun startDecode(fileContainer: IFileContainer) {
        val config = player.configManager.config
        var videoWidth = config?.videoWidth ?: 0
        var videoHeight = config?.videoHeight ?: 0

        val videoPath = resolveVideoPath(fileContainer)
        if (videoPath == null) {
            finishWithError(Constant.REPORT_ERROR_TYPE_FILE_ERROR, "Unable to resolve video path")
            return
        }

        if (videoWidth <= 0 || videoHeight <= 0) {
            probeVideoSize(videoPath)?.let { (w, h) ->
                videoWidth = w
                videoHeight = h
            }
        }
        if (videoWidth <= 0 || videoHeight <= 0) {
            finishWithError(Constant.REPORT_ERROR_TYPE_PARSE_CONFIG, "Invalid video size")
            return
        }

        if (!prepareRender(needYUV = true)) {
            finishWithError(Constant.REPORT_ERROR_TYPE_CREATE_RENDER, "Prepare render failed")
            return
        }

        if (!preparePlay(videoWidth, videoHeight)) {
            finishWithError(Constant.REPORT_ERROR_TYPE_CREATE_RENDER, "preparePlay failed")
            return
        }
        val (decodeWidth, decodeHeight) = PlaybackSize.scaledVideoSize(
            videoWidth,
            videoHeight,
            config?.width ?: 0,
            config?.height ?: 0,
            player.outputSurfaceWidth,
            player.outputSurfaceHeight,
            if (player.lowMemoryMode) {
                player.lowMemoryMaxVideoPixels
            } else {
                Int.MAX_VALUE
            }
        )
        if (decodeWidth != videoWidth || decodeHeight != videoHeight) {
            ALog.i(TAG, "scale decode ${videoWidth}x$videoHeight -> ${decodeWidth}x$decodeHeight")
        }
        running = true
        decodeYuvAndRender(videoPath, decodeWidth, decodeHeight)
    }

    private fun decodeYuvAndRender(videoPath: String, width: Int, height: Int) {
        var frameIndex = 0
        val ySize = width * height
        val uvSize = width * height / 4
        val frameSize = ySize + uvSize * 2

        val freeFrameSlots = ArrayBlockingQueue<FrameSlot>(BUFFERED_FRAME_COUNT).apply {
            repeat(BUFFERED_FRAME_COUNT) {
                offer(FrameSlot(frameSize))
            }
        }
        var borrowedSlot: FrameSlot? = null
        val playbackFps = if (fps > 0) fps else TARGET_SOFT_FPS

        try {
            // 帧线程会按线程数再留几份解码帧。软解只在硬解失败后使用，这里固定单线程，少占 native 内存。
            val cmd = "-threads 1 " +
                    "-flags2 +fast " +             // 开启快速不标准解码允许
                    "-skip_loop_filter all " +     // 【绝对杀手锏】跳过环路滤波，节省大量CPU
                    "-vcodec h264 " +              // 显式指定解码器
                    "-i \"$videoPath\" " +
                    "-vap_width $width -vap_height $height " +
                    "-an -f rawvideo -pix_fmt yuv420p -"
            ALog.i(TAG, "FFmpeg direct frame cmd: $cmd")

            var isFirstFrame = true
            val decodeDoneLatch = CountDownLatch(1)

            // 异步执行 FFmpeg 命令
            currentSession = FFmpegKit.executeFramesAsync(cmd, object : LiteFFmpegFrameCallback {
                override fun acquireFrame(requestedSize: Int): ByteBuffer? {
                    if (!running || isStopReq || destroyed || requestedSize != frameSize) {
                        return null
                    }
                    val slot = try {
                        freeFrameSlots.poll(RENDER_TIMEOUT_SEC, TimeUnit.SECONDS)
                    } catch (e: InterruptedException) {
                        ALog.w(TAG, "Interrupted while waiting for free frame buffer.")
                        return null
                    } ?: run {
                        ALog.e(TAG, "No free frame buffer, render may be blocked at frame $frameIndex")
                        return null
                    }
                    borrowedSlot = slot
                    slot.data.clear()
                    return slot.data
                }

                override fun releaseFrame(buffer: ByteBuffer) {
                    val slot = borrowedSlot
                    borrowedSlot = null
                    if (slot != null) {
                        freeFrameSlots.offer(slot)
                    }
                }

                override fun onFrame(buffer: ByteBuffer, filledSize: Int): Boolean {
                    val slot = borrowedSlot
                    borrowedSlot = null
                    if (slot == null) return false

                    var queued = false
                    try {
                        if (!running || isStopReq || destroyed || filledSize != frameSize) {
                            return false
                        }

                        val presentationTimeUs = frameIndex * 1_000_000L / playbackFps
                        val yBuffer = slicePlane(slot.data, 0, ySize)
                        val uBuffer = slicePlane(slot.data, ySize, uvSize)
                        val vBuffer = slicePlane(slot.data, ySize + uvSize, uvSize)
                        val currentFrameIndex = frameIndex
                        val notifyFirstFrame = isFirstFrame
                        if (isFirstFrame) {
                            isFirstFrame = false
                        }

                        queued = renderThread.handler?.post {
                            try {
                                if (isStopReq || destroyed || render == null) return@post

                                speedControlUtil.preRender(presentationTimeUs)
                                render?.setYUVData(width, height, yBuffer, uBuffer, vBuffer)
                                render?.renderFrame()
                                player.pluginManager.onRendering()
                                if (notifyFirstFrame) {
                                    onVideoStart()
                                    ALog.i(TAG, ">>> FIRST FRAME RENDERED at index: $currentFrameIndex <<<")
                                }
                                player.pluginManager.onDecoding(currentFrameIndex)
                                onVideoRender(currentFrameIndex, player.configManager.config)
                            } catch (e: Exception) {
                                ALog.e(TAG, "render frame error", e)
                            } catch (e: OutOfMemoryError) {
                                ALog.e(TAG, "render frame OOM", e)
                            } finally {
                                freeFrameSlots.offer(slot)
                            }
                        } == true
                        if (queued) {
                            frameIndex++
                        }
                        return queued
                    } finally {
                        if (!queued) {
                            freeFrameSlots.offer(slot)
                        }
                    }
                }
            }) { session ->
                val returnCode = session.returnCode
                ALog.i(TAG, "FFmpeg session completed with return code: $returnCode")

                // 【核心防御拦截】
                // 如果 FFmpeg 异常崩溃或退出（returnCode != 0），且当前正在播放，并且从来没有上报过失败
                if (returnCode != null && !returnCode.isValueSuccess() && running && !isFailureReported && !destroyed) {
                    isFailureReported = true // 立刻锁死，防止 destroy 里的 cancel() 导致二次回调触发死循环
                    ALog.e(TAG, "Detect FFmpeg internal error! Triggering onFailed once.")

                    renderThread.handler?.post {
                        finishWithError(
                            Constant.REPORT_ERROR_TYPE_DECODE_EXC,
                            "FFmpeg internal error with code: $returnCode"
                        )
                    }
                }
                decodeDoneLatch.countDown()
            }

            while (running && !isStopReq && !destroyed && currentSession?.returnCode == null) {
                if (decodeDoneLatch.await(100, TimeUnit.MILLISECONDS)) {
                    break
                }
            }
        } catch (e: Exception) {
            ALog.e(TAG, "decode error", e)
            finishWithError(Constant.REPORT_ERROR_TYPE_DECODE_EXC, "Soft decode error: ${e.message}")
            return
        } finally {
            running = false
            tempVideoFile?.delete()
            tempVideoFile = null
            releaseRenderOnce()
        }
    }

    private fun releaseRenderOnce() {
        if (renderReleased) return
        renderReleased = true
        releaseRender()
    }

    private fun releaseRender() {
        val latch = CountDownLatch(1)
        renderThread.handler?.post {
            try {
                // 先删融合图和遮罩纹理，再销毁 EGL。上下文没了之后 glDeleteTextures 不起作用。
                (render as? YUVRender)?.bindGlContext()
                player.pluginManager.onRelease()
                render?.destroyRender()
                render = null
            } catch (e: Exception) {
                ALog.e(TAG, "releaseRender error", e)
            } finally {
                isRunning = false
                player.isDegrading = false
                onVideoComplete()
                latch.countDown()
            }
        } ?: run {
            isRunning = false
            player.isDegrading = false
            onVideoComplete()
            latch.countDown()
        }
        latch.await(RENDER_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    private fun finishWithError(errorType: Int, errorMsg: String) {
        // 双重安全拦截：如果已经处理并触发过失败，不重复执行
        if (isFailureReported && !running) return
        isFailureReported = true

        running = false
        isRunning = false
        tempVideoFile?.delete()
        tempVideoFile = null
        onFailed(errorType, errorMsg) // 👈 此时配合上一步修改后的基类 Decoder.kt，会 100% 回调给外层监听
        releaseRenderOnce()
    }

    private fun slicePlane(data: ByteBuffer, offset: Int, size: Int): ByteBuffer {
        val view = data.duplicate()
        view.position(offset)
        view.limit(offset + size)
        return view.slice()
    }

    private fun resolveVideoPath(fileContainer: IFileContainer): String? {
        fileContainer.filePath?.let { path ->
            val file = File(path)
            if (file.exists() && file.isFile) {
                return path
            }
        }
        return copyToTempFile(fileContainer)
    }

    private fun copyToTempFile(fileContainer: IFileContainer): String? {
        val temp = File(player.context.cacheDir, "vap_soft_src_${System.currentTimeMillis()}.mp4")
        return try {
            fileContainer.startRandomRead()
            temp.outputStream().use { out ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = fileContainer.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
            }
            fileContainer.closeRandomRead()
            tempVideoFile = temp
            temp.absolutePath
        } catch (e: Exception) {
            ALog.e(TAG, "copyToTempFile failed", e)
            temp.delete()
            null
        }
    }

    private fun probeVideoSize(videoPath: String): Pair<Int, Int>? {
        return try {
            val session = FFprobeKit.getMediaInformation(videoPath)
            val information = session.mediaInformation ?: return null
            for (stream in information.streams) {
                if (stream.type != "video") continue
                val w = stream.width.toInt()
                val h = stream.height.toInt()
                if (w > 0 && h > 0) {
                    ALog.i(TAG, "Probed video size: ${w}x$h")
                    return Pair(w, h)
                }
            }
            null
        } catch (e: Exception) {
            ALog.e(TAG, "probeVideoSize failed", e)
            null
        }
    }

    override fun destroy() {
        ALog.d(TAG, "destroy() sessionId=${currentSession?.sessionId}")
        destroyed = true
        running = false
        isStopReq = true
        try {
            currentSession?.cancel() // 👈 调用此方法会触发异步回调，但已被状态锁拦截，绝不死循环
        } catch (e: Exception) {
            ALog.e(TAG, "cancel session error", e)
        }
        decodeThreadRef?.interrupt()
        decodeThreadRef = null
    }
}