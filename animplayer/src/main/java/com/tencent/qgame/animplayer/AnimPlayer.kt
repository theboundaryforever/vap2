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

import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.inter.IAnimListener
import com.tencent.qgame.animplayer.mask.MaskConfig
import com.tencent.qgame.animplayer.plugin.AnimPluginManager
import com.tencent.qgame.animplayer.util.ALog

class AnimPlayer(val animView: IAnimView) {

    companion object {
        private const val TAG = "${Constant.TAG}.AnimPlayer"
    }

    val context: android.content.Context
        get() = animView.let {
            if (it is android.view.View) it.context
            else throw IllegalStateException("AnimView 必须是 View 类型")
        }
    var animListener: IAnimListener? = null
    var decoder: Decoder? = null
    var audioPlayer: AudioPlayer? = null
    var fps: Int = 0
        set(value) {
            decoder?.fps = value
            field = value
        }

    // 设置默认的fps <= 0 表示以vapc配置为准 > 0  表示以此设置为准
    var defaultFps: Int = 0
    var playLoop: Int = 0
        set(value) {
            decoder?.playLoop = value
            audioPlayer?.playLoop = value
            field = value
        }
    var supportMaskBoolean: Boolean = false
    var maskEdgeBlurBoolean: Boolean = false

    // 是否兼容老版本 默认不兼容
    var enableVersion1: Boolean = false

    // 视频模式
    var videoMode: Int = Constant.VIDEO_MODE_SPLIT_HORIZONTAL
    var isDetachedFromWindow = false
    var isSurfaceAvailable = false
    var startRunnable: Runnable? = null
    var isStartRunning = false // 启动时运行状态
    var isMute = false // 是否静音

    val configManager = AnimConfigManager(this)
    val pluginManager = AnimPluginManager(this)

    var currentFileContainer: IFileContainer? = null

    // 【新增】降级状态标记，防止重复触发
    var isDegrading = false

    // TextureView 的 EGL 窗口表面默认按 View 尺寸分配（全屏可达 30MB+）。
    // 改成按动画输出分辨率分配，放大交给 TextureView 自己做。
    var outputSurfaceWidth = 0
        private set
    var outputSurfaceHeight = 0
        private set

    fun onSurfaceTextureDestroyed() {
        isSurfaceAvailable = false
        isStartRunning = false
        // 硬解降级软解的交接期间 Surface 由软解接管，此时不能销毁 decoder。
        // 其余情况（View detach / 播放结束后 TextureView 被移除）必须销毁，否则
        // MediaCodec、MediaExtractor、EGL 和解码线程会一直持有到视频播完（循环播放则永不释放）。
        if (isDegrading) {
            ALog.i(TAG, "onSurfaceTextureDestroyed: degrading, skip destroy")
            return
        }
        decoder?.destroy()
        audioPlayer?.destroy()
    }

    fun onSurfaceTextureAvailable(width: Int, height: Int) {
        isSurfaceAvailable = true
        startRunnable?.run()
        startRunnable = null
    }


    fun onSurfaceTextureSizeChanged(width: Int, height: Int) {
        // TextureView 尺寸变化时会把 SurfaceTexture 缓冲改回 View 尺寸，立刻改回输出分辨率。
        if (outputSurfaceWidth > 0 && outputSurfaceHeight > 0) {
            applyOutputSurfaceSize(outputSurfaceWidth, outputSurfaceHeight)
            decoder?.onSurfaceSizeChanged(outputSurfaceWidth, outputSurfaceHeight)
        } else {
            decoder?.onSurfaceSizeChanged(width, height)
        }
    }

    /**
     * 把 TextureView 的图形缓冲限制在动画输出分辨率，避免按屏幕尺寸分配 RGBA 缓冲。
     * 显示尺寸大于输出时由 TextureView 缩放；输出大于屏幕时按屏幕像素封顶。
     */
    fun applyOutputSurfaceSizeFromConfig() {
        val config = configManager.config ?: return
        val configW = config.width
        val configH = config.height
        if (configW <= 0 || configH <= 0) return

        val viewSize = animView.getRealSize()
        val viewW = viewSize.first
        val viewH = viewSize.second
        val (width, height) = if (viewW > 0 && viewH > 0 &&
            viewW.toLong() * viewH < configW.toLong() * configH
        ) {
            viewW to viewH
        } else {
            configW to configH
        }
        applyOutputSurfaceSize(width, height)
    }

    fun applyOutputSurfaceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        outputSurfaceWidth = width
        outputSurfaceHeight = height
        try {
            animView.getSurfaceTexture()?.setDefaultBufferSize(width, height)
            ALog.i(TAG, "output surface buffer ${width}x$height")
        } catch (t: Throwable) {
            ALog.e(TAG, "setDefaultBufferSize failed", t)
        }
    }

    fun startPlay(fileContainer: IFileContainer) {
        this.currentFileContainer = fileContainer
        isDegrading = false
        outputSurfaceWidth = 0
        outputSurfaceHeight = 0

        isStartRunning = true
        prepareDecoder()
        if (decoder?.prepareThread() == false) {
            isStartRunning = false
            decoder?.onFailed(
                Constant.REPORT_ERROR_TYPE_CREATE_THREAD,
                Constant.ERROR_MSG_CREATE_THREAD
            )
            decoder?.onVideoComplete()
            return
        }
        // 在线程中解析配置
        decoder?.renderThread?.handler?.post {
            val result =
                configManager.parseConfig(fileContainer, enableVersion1, videoMode, defaultFps)
            if (result != Constant.OK) {
                isStartRunning = false
                decoder?.onFailed(result, Constant.getErrorMsg(result))
                decoder?.onVideoComplete()
                return@post
            }
            ALog.i(TAG, "parse ${configManager.config}")
            val config = configManager.config
            // 如果是默认配置，因为信息不完整onVideoConfigReady不会被调用
            if (config != null && (config.isDefaultConfig || animListener?.onVideoConfigReady(config) == true)) {
                // 播放只用已经解析好的字段和帧对象，原始 JSON 不再留着。
                config.jsonConfig = null
                innerStartPlay(fileContainer)
            } else {
                ALog.i(TAG, "onVideoConfigReady return false")
            }
        }
    }

    private fun innerStartPlay(fileContainer: IFileContainer) {
        synchronized(AnimPlayer::class.java) {
            if (isSurfaceAvailable) {
                isStartRunning = false
                decoder?.start(fileContainer)
                if (!isMute) {
                    audioPlayer?.start(fileContainer)
                }
            } else {
                startRunnable = Runnable {
                    innerStartPlay(fileContainer)
                }
                animView.prepareTextureView()
            }
        }
    }

    fun stopPlay() {
        decoder?.stop()
        audioPlayer?.stop()
    }

    fun isRunning(): Boolean {
        return isStartRunning // 启动过程运行状态
                || (decoder?.isRunning ?: false) // 解码过程运行状态

    }

    private fun prepareDecoder() {
        // 每次新播放都从硬解开始。上一轮硬解失败后留下的 SoftDecoder 不能沿用，
        // 否则后续普通素材也会一直走软解。线程复用，避免反复创建。
        val current = decoder
        if (current !is HardDecoder) {
            decoder = HardDecoder(this).apply {
                playLoop = this@AnimPlayer.playLoop
                fps = this@AnimPlayer.fps
                if (current != null) {
                    adoptThreadsFrom(current)
                }
            }
            if (current != null) {
                ALog.i(TAG, "Reset decoder to HardDecoder")
            }
        }
        if (audioPlayer == null) {
            audioPlayer = AudioPlayer(this).apply {
                playLoop = this@AnimPlayer.playLoop
            }
        }
    }

    fun updateMaskConfig(maskConfig: MaskConfig?) {
        configManager.config?.maskConfig = configManager.config?.maskConfig ?: MaskConfig()
        configManager.config?.maskConfig?.safeSetMaskBitmapAndReleasePre(maskConfig?.alphaMaskBitmap)
        configManager.config?.maskConfig?.maskPositionPair = maskConfig?.maskPositionPair
        configManager.config?.maskConfig?.maskTexPair = maskConfig?.maskTexPair
    }

    // AnimPlayer.kt
    fun handleDecoderFailed(errorType: Int, errorMsg: String?) {
        if (isDegrading) return
        if (errorType != Constant.REPORT_ERROR_TYPE_DECODE_EXC) return

        isDegrading = true
        ALog.i(TAG, "Hard decode failed, switching to SoftDecoder: $errorMsg")

        val oldDecoder = decoder
        oldDecoder?.stop()

        // 在旧 decoder 的 renderThread 上释放 EGL，避免与软解 YUVRender 冲突
        val cleanupLatch = java.util.concurrent.CountDownLatch(1)
        val renderHandler = oldDecoder?.renderThread?.handler
        if (renderHandler != null) {
            val cleanupOldRender = {
                try {
                    oldDecoder.render?.destroyRender()
                    oldDecoder.render = null
                } catch (e: Exception) {
                    ALog.e(TAG, "Cleanup old render failed", e)
                } finally {
                    cleanupLatch.countDown()
                }
            }
            if (android.os.Looper.myLooper() == renderHandler.looper) {
                cleanupOldRender()
            } else {
                renderHandler.post(cleanupOldRender)
                cleanupLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }

        decoder = null

        if (!isSurfaceAvailable) {
            ALog.d(TAG, "Surface unavailable, wait for onSurfaceTextureAvailable")
            return
        }

        startSoftDecoder(oldDecoder)
    }

    fun startSoftDecoder(oldDecoder: Decoder? = null, startAudio: Boolean = false) {
        val softDecoder = SoftDecoder(this).apply {
            playLoop = this@AnimPlayer.playLoop
            fps = this@AnimPlayer.fps
            if (oldDecoder != null) {
                adoptThreadsFrom(oldDecoder)
            }
        }

        if (softDecoder.renderThread.handler == null && !softDecoder.prepareThread()) {
            ALog.e(TAG, "SoftDecoder prepareThread failed")
            isDegrading = false
            return
        }

        decoder = softDecoder
        currentFileContainer?.let { container ->
            ALog.i(TAG, "Starting SoftDecoder playback")
            softDecoder.start(container)
            if (startAudio) {
                audioPlayer?.start(container)
            }
        }
    }

    @Volatile
    var isSwitchingDecoder = false

    fun onSoftDecoderReady() {
        isSwitchingDecoder = false

        ALog.i(
            TAG,
            """
        SoftDecoder Ready
        decoder=${decoder?.javaClass?.simpleName}
        """.trimIndent()
        )
    }

}