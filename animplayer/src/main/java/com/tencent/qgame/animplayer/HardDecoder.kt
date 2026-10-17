/*
 * Tencent is pleased to support the open source community by making vap available.
 *
 * Copyright (C) 2020 Tencent.  All rights reserved.
 *
 * Licensed under the MIT License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/MIT
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.qgame.animplayer

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.MediaUtil

class HardDecoder(player: AnimPlayer) : Decoder(player), SurfaceTexture.OnFrameAvailableListener {


    companion object {
        private const val TAG = "${Constant.TAG}.HardDecoder"
    }

    private var surface: Surface? = null
    private var glTexture: SurfaceTexture? = null
    private val bufferInfo by lazy { MediaCodec.BufferInfo() }
    private var needDestroy = false

    // 动画的原始尺寸
    private var videoWidth = 0
    private var videoHeight = 0

    override fun start(fileContainer: IFileContainer) {
        isStopReq = false
        needDestroy = false
        isRunning = true
        renderThread.handler?.post {
            startPlay(fileContainer)
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        if (isStopReq) return
        if (render == null) return
        val handler = renderThread.handler
        if (handler != null && android.os.Looper.myLooper() == handler.looper) {
            drawAvailableFrame()
        } else {
            handler?.post { drawAvailableFrame() }
        }
    }

    private fun drawAvailableFrame() {
        try {
            if (render == null || isStopReq) return
            glTexture?.apply {
                updateTexImage()
                render?.renderFrame()
                player.pluginManager.onRendering()
                render?.swapBuffers()
            }
        } catch (e: Throwable) {
            ALog.e(TAG, "render exception=$e", e)
        }
    }

    private fun startPlay(fileContainer: IFileContainer) {

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var format: MediaFormat? = null
        var trackIndex = 0

        try {
            extractor = MediaUtil.getExtractor(fileContainer)
            trackIndex = MediaUtil.selectVideoTrack(extractor)
            if (trackIndex < 0) {
                throw RuntimeException("No video track found")
            }
            extractor.selectTrack(trackIndex)
            format = extractor.getTrackFormat(trackIndex)
            if (format == null) throw RuntimeException("format is null")

            // 是否支持h265
            if (MediaUtil.checkIsHevc(format)) {
                if (Build.VERSION.SDK_INT  < Build.VERSION_CODES.LOLLIPOP
                    || !MediaUtil.checkSupportCodec(MediaUtil.MIME_HEVC)) {

                    onFailed(Constant.REPORT_ERROR_TYPE_HEVC_NOT_SUPPORT,
                        "${Constant.ERROR_MSG_HEVC_NOT_SUPPORT} " +
                                "sdk:${Build.VERSION.SDK_INT}" +
                                ",support hevc:" + MediaUtil.checkSupportCodec(MediaUtil.MIME_HEVC))
                    release(null, null)
                    return
                }
            }

            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            ALog.i(TAG, "Video size is $videoWidth x $videoHeight")

            try {
                if (!prepareRender(false)) {
                    throw RuntimeException("render create fail")
                }
            } catch (t: Throwable) {
                onFailed(Constant.REPORT_ERROR_TYPE_CREATE_RENDER, "${Constant.ERROR_MSG_CREATE_RENDER} e=$t")
                release(null, null)
                return
            }

            preparePlay(videoWidth, videoHeight)

            render?.apply {
                ALog.i(TAG, "Creating SurfaceTexture with external texture: ${getExternalTexture()}")
                glTexture = SurfaceTexture(getExternalTexture()).apply {
                    setOnFrameAvailableListener(this@HardDecoder, renderThread.handler)
                    setDefaultBufferSize(videoWidth, videoHeight)
                    ALog.i(TAG, "SurfaceTexture created and configured")
                }
                clearFrame()
            }

        } catch (e: Throwable) {
            ALog.e(TAG, "MediaExtractor exception e=$e", e)
            onFailed(Constant.REPORT_ERROR_TYPE_EXTRACTOR_EXC, "${Constant.ERROR_MSG_EXTRACTOR_EXC} e=$e")
            release(decoder, extractor)
            return
        }

        try {
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            ALog.i(TAG, "Video MIME is $mime")
            surface = Surface(glTexture)
            // 先赋值再 start：start() 抛异常时 catch 里的 release 才能释放已 configure 的 codec
            val codec = configureSurfaceDecoder(mime, format)
            decoder = codec
            codec.start()
            decodeThread.handler?.post {
                try {
                    startDecode(extractor, codec)
                } catch (e: Throwable) {
                    ALog.e(TAG, "MediaCodec exception e=$e", e)
                    onFailed(Constant.REPORT_ERROR_TYPE_DECODE_EXC, "${Constant.ERROR_MSG_DECODE_EXC} e=$e")
                    release(codec, extractor)
                }
            }
        } catch (e: Throwable) {
            ALog.e(TAG, "MediaCodec configure exception e=$e", e)
            onFailed(Constant.REPORT_ERROR_TYPE_DECODE_EXC, "${Constant.ERROR_MSG_DECODE_EXC} e=$e")
            release(decoder, extractor)
            return
        }
    }

    private fun startDecode(extractor: MediaExtractor ,decoder: MediaCodec) {
        val TIMEOUT_USEC = 10000L
        var inputChunk = 0
        var outputDone = false
        var inputDone = false
        var frameIndex = 0
        var isLoop = false

        while (!outputDone) {
            if (isStopReq) {
                ALog.i(TAG, "stop decode")
                release(decoder, extractor)
                return
            }

            // 尽可能把解码器的输入缓冲一次喂满，而不是每轮只喂一帧：
            // 解码器有 B 帧重排/流水线深度，逐帧喂会让首帧以及每次循环重启后的首帧多等若干个超时周期。
            // 输入侧不阻塞（仅解码器刚 start 时等一次），阻塞等待统一放在下面的 dequeueOutputBuffer。
            while (!inputDone) {
                val inputBufIndex = decoder.dequeueInputBuffer(if (inputChunk == 0) TIMEOUT_USEC else 0L)
                if (inputBufIndex < 0) {
                    if (ALog.isDebug) ALog.d(TAG, "input buffer not available")
                    break
                }
                val inputBuf = decoder.getInputBuffer(inputBufIndex)
                if (inputBuf == null) {
                    decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, 0)
                    break
                }
                val chunkSize = extractor.readSampleData(inputBuf, 0)
                if (chunkSize < 0) {
                    decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                    ALog.d(TAG, "decode EOS")
                } else {
                    val presentationTimeUs = extractor.sampleTime
                    decoder.queueInputBuffer(inputBufIndex, 0, chunkSize, presentationTimeUs, 0)
                    if (ALog.isDebug) {
                        ALog.d(TAG, "submitted frame $inputChunk to dec, size=$chunkSize")
                    }
                    inputChunk++
                    extractor.advance()
                }
            }

            if (!outputDone) {
                val decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                when {
                    decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (ALog.isDebug) ALog.d(TAG, "no output from decoder available")
                    }
                    decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> ALog.d(TAG, "decoder output buffers changed")
                    decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        ALog.i(TAG, "decoder output format changed: ${decoder.outputFormat}")
                    }
                    decoderStatus < 0 -> {
                        throw RuntimeException("unexpected result from decoder.dequeueOutputBuffer: $decoderStatus")
                    }
                    else -> {
                        var loop = 0
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            loop = --playLoop
                            player.playLoop = playLoop // 消耗loop次数 自动恢复后能有正确的loop次数
                            outputDone = playLoop <= 0
                        }
                        val doRender = !outputDone
                        if (doRender) {
                            speedControlUtil.preRender(bufferInfo.presentationTimeUs)
                        }

                        decoder.releaseOutputBuffer(decoderStatus, doRender)

                        if (frameIndex == 0 && !isLoop) {
                            onVideoStart()
                        }
                        player.pluginManager.onDecoding(frameIndex)
                        onVideoRender(frameIndex, player.configManager.config)

                        frameIndex++
                        if (ALog.isDebug) {
                            ALog.d(TAG, "decode frameIndex=$frameIndex")
                        }
                        if (loop > 0) {
                            ALog.d(TAG, "Reached EOD, looping")
                            player.pluginManager.onLoopStart()
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            inputDone = false
                            decoder.flush()
                            speedControlUtil.reset()
                            frameIndex = 0
                            isLoop = true
                        }
                        if (outputDone) {
                            release(decoder, extractor)
                        }
                    }
                }
            }
        }

    }

    private fun configureSurfaceDecoder(mime: String, format: MediaFormat): MediaCodec {
        val outputSurface = surface ?: throw IllegalStateException("surface is null")
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val tuned = copyVideoFormat(format)
        applyDecoderMemoryHints(tuned)
        val decoder = openDecoder(mime, width, height)
        if (MediaUtil.supportsLowLatency(decoder.name, mime)) {
            tuned.setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                tuned.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
        return try {
            decoder.configure(tuned, outputSurface, null, 0)
            ALog.i(TAG, "decoder configured codec=${decoder.name} ${width}x$height max=${tuned.getInteger(MediaFormat.KEY_MAX_WIDTH)}x${tuned.getInteger(MediaFormat.KEY_MAX_HEIGHT)}")
            decoder
        } catch (t: Throwable) {
            ALog.e(TAG, "memory-tuned configure failed, fallback", t)
            releaseCodecQuietly(decoder)
            val plain = openDecoder(mime, width, height)
            try {
                plain.configure(format, outputSurface, null, 0)
                plain
            } catch (t2: Throwable) {
                // 第二次 configure 也失败：释放刚创建的 codec，避免泄漏硬件解码器实例
                releaseCodecQuietly(plain)
                throw t2
            }
        }
    }

    private fun openDecoder(mime: String, width: Int, height: Int): MediaCodec {
        val hardwareName = MediaUtil.findHardwareDecoderName(mime, width, height)
        if (!hardwareName.isNullOrEmpty()) {
            try {
                ALog.i(TAG, "use hardware decoder $hardwareName")
                return MediaCodec.createByCodecName(hardwareName)
            } catch (t: Throwable) {
                ALog.e(TAG, "createByCodecName $hardwareName failed", t)
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun releaseCodecQuietly(codec: MediaCodec?) {
        try {
            codec?.release()
        } catch (t: Throwable) {
            ALog.e(TAG, "release codec failed", t)
        }
    }

    private fun applyDecoderMemoryHints(format: MediaFormat) {
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        val suggested = suggestedMaxInputSize(mime, width, height)
        val current = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            Int.MAX_VALUE
        }
        if (suggested in 1 until current) {
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, suggested)
        }
        // 不写 max-width/max-height 时，部分解码器按能力上限（常见 4K）分配输出缓冲，小视频也会占满那块显存。
        val alignedWidth = (width + 15) / 16 * 16
        val alignedHeight = (height + 15) / 16 * 16
        format.setInteger(MediaFormat.KEY_MAX_WIDTH, alignedWidth)
        format.setInteger(MediaFormat.KEY_MAX_HEIGHT, alignedHeight)
    }

    private fun suggestedMaxInputSize(mime: String, width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 0
        val alignedWidth = (width + 15) / 16 * 16
        val alignedHeight = (height + 15) / 16 * 16
        val pixels = alignedWidth.toLong() * alignedHeight
        // HEVC 单帧通常更小，输入缓冲按 4 倍压缩估算；H.264 仍按 2 倍，避免关键帧放不下。
        val compressionRatio = if (mime.contains("hevc", ignoreCase = true)) 4 else 2
        val size = pixels * 3 / (2 * compressionRatio)
        return size.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun copyVideoFormat(src: MediaFormat): MediaFormat {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaFormat(src)
        }
        val dst = MediaFormat()
        copyString(src, dst, MediaFormat.KEY_MIME)
        copyInt(src, dst, MediaFormat.KEY_WIDTH)
        copyInt(src, dst, MediaFormat.KEY_HEIGHT)
        copyLong(src, dst, MediaFormat.KEY_DURATION)
        copyInt(src, dst, MediaFormat.KEY_FRAME_RATE)
        copyInt(src, dst, MediaFormat.KEY_MAX_INPUT_SIZE)
        copyInt(src, dst, MediaFormat.KEY_BIT_RATE)
        copyInt(src, dst, MediaFormat.KEY_PROFILE)
        copyInt(src, dst, MediaFormat.KEY_LEVEL)
        copyInt(src, dst, MediaFormat.KEY_ROTATION)
        copyBuffer(src, dst, "csd-0")
        copyBuffer(src, dst, "csd-1")
        copyBuffer(src, dst, "csd-2")
        return dst
    }

    private fun copyString(src: MediaFormat, dst: MediaFormat, key: String) {
        if (!src.containsKey(key)) return
        dst.setString(key, src.getString(key))
    }

    private fun copyInt(src: MediaFormat, dst: MediaFormat, key: String) {
        if (!src.containsKey(key)) return
        try {
            dst.setInteger(key, src.getInteger(key))
        } catch (t: Throwable) {
            ALog.e(TAG, "copy int $key failed", t)
        }
    }

    private fun copyLong(src: MediaFormat, dst: MediaFormat, key: String) {
        if (!src.containsKey(key)) return
        try {
            dst.setLong(key, src.getLong(key))
        } catch (t: Throwable) {
            ALog.e(TAG, "copy long $key failed", t)
        }
    }

    private fun copyBuffer(src: MediaFormat, dst: MediaFormat, key: String) {
        if (!src.containsKey(key)) return
        val buffer = src.getByteBuffer(key) ?: return
        dst.setByteBuffer(key, buffer.duplicate())
    }

    private fun release(decoder: MediaCodec?, extractor: MediaExtractor?) {
        renderThread.handler?.post {
            ALog.i(TAG, "release")
            // 每一步独立 try：任意一步抛异常（例如 codec 处于 Error 状态时 stop() 会抛
            // IllegalStateException）都不能跳过后面的释放，否则 MediaCodec/Extractor/Surface 全部泄漏
            releaseStep("clearFrame") { render?.clearFrame() }
            if (decoder != null) {
                releaseStep("codec.stop") { decoder.stop() }
                releaseStep("codec.release") { decoder.release() }
            }
            releaseStep("extractor.release") { extractor?.release() }
            releaseStep("glTexture.release") { glTexture?.release() }
            glTexture = null
            releaseStep("speedControl.reset") { speedControlUtil.reset() }
            releaseStep("plugin.onRelease") { player.pluginManager.onRelease() }
            releaseStep("releaseTexture") { render?.releaseTexture() }
            releaseStep("surface.release") { surface?.release() }
            surface = null
            isRunning = false
            // 降级软解时不要触发 complete，否则 AnimView.hide() 会销毁 Surface
            if (!player.isDegrading) {
                onVideoComplete()
            }
            if (needDestroy && !player.isDegrading) {
                destroyInner()
            }
        }
    }

    private inline fun releaseStep(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            ALog.e(TAG, "release: $name error: $e", e)
        }
    }

    override fun destroy() {
        if (isRunning) {
            needDestroy = true
            stop()
        } else {
            destroyInner()
        }
    }

    private fun destroyInner() {
        ALog.i(TAG, "destroyInner")
        renderThread.handler?.post {
            try {
                player.pluginManager.onDestroy()
            } catch (e: Throwable) {
                ALog.e(TAG, "destroyInner: onDestroy error: $e", e)
            }
            
            try {
                render?.destroyRender()
            } catch (e: Throwable) {
                ALog.e(TAG, "destroyInner: destroyRender error: $e", e)
            }
            
            render = null
            onVideoDestroy()
            destroyThread()
        }
    }
}