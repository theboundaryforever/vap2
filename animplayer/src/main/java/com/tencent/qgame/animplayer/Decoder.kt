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
import android.os.Build
import android.os.HandlerThread
import android.os.Handler
import android.os.Looper
import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.inter.IAnimListener
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.SpeedControlUtil
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


abstract class Decoder(val player: AnimPlayer) : IAnimListener {

    companion object {
        private const val TAG = "${Constant.TAG}.Decoder"

        // 标记是否已经进入降级状态，防止重复触发
        private var isDegrading = false

        fun createThread(handlerHolder: HandlerHolder, name: String): Boolean {
            try {
                if (handlerHolder.thread == null || handlerHolder.thread?.isAlive == false) {
                    handlerHolder.thread = HandlerThread(name).apply {
                        start()
                        handlerHolder.handler = Handler(looper)
                    }
                }
                return true
            } catch (e: OutOfMemoryError) {
                ALog.e(TAG, "createThread OOM", e)
            }
            return false
        }

        fun quitSafely(thread: HandlerThread?): HandlerThread? {
            thread?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    thread.quitSafely()
                } else {
                    thread.quit()
                }
            }
            return null
        }
    }

    var render: IRenderListener? = null
    val renderThread = HandlerHolder(null, null)
    val decodeThread = HandlerHolder(null, null)
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    var fps: Int = 0
        set(value) {
            speedControlUtil.setFixedPlaybackRate(value)
            field = value
        }
    var playLoop = 0 // 循环播放次数
    var isRunning = false // 是否正在运行
    var isStopReq = false // 是否需要停止
    val speedControlUtil by lazy { SpeedControlUtil() }

    abstract fun start(fileContainer: IFileContainer)

    fun stop() {
        isStopReq = true
    }

    abstract fun destroy()

    fun prepareThread(): Boolean {
        return createThread(renderThread, "anim_render_thread") && createThread(
            decodeThread,
            "anim_decode_thread"
        )
    }

    /**
     * 硬解降级软解时复用已有线程，避免多 EGL 上下文竞争同一 SurfaceTexture
     */
    fun adoptThreadsFrom(source: Decoder) {
        renderThread.thread = source.renderThread.thread
        renderThread.handler = source.renderThread.handler
        decodeThread.thread = source.decodeThread.thread
        decodeThread.handler = source.decodeThread.handler
    }

    // 在 Decoder 类中增加一个锁
    private val renderLock = Any()

    // 当前 render 的 EGL 窗口表面所绑定的 SurfaceTexture（弱引用，避免持有已销毁的 Surface）
    private var renderSurfaceRef: WeakReference<SurfaceTexture>? = null

    /**
     * render 只有在类型一致、且仍绑定当前 SurfaceTexture 时才能复用。
     * 每次播放结束 AnimView 都会重建 TextureView，旧 render 的 EGL 表面指向已销毁的 Surface，
     * 复用它会导致二次播放画面空白。
     */
    private fun isRenderReusable(needYUV: Boolean, surfaceTexture: SurfaceTexture?): Boolean {
        return surfaceTexture != null &&
                render != null &&
                (render is YUVRender) == needYUV &&
                renderSurfaceRef?.get() === surfaceTexture
    }

    /**
     * 使用双重检查机制，确保渲染器生命周期单例化
     * 修复：使用CountDownLatch同步等待YUVRender的initGL()完成
     */
    fun prepareRender(needYUV: Boolean): Boolean {
        if (isRenderReusable(needYUV, player.animView.getSurfaceTexture())) {
            ALog.i(TAG, "Render already matches type and surface, skip.")
            return true
        }

        synchronized(this) {
            val surfaceTexture = player.animView.getSurfaceTexture() ?: return false

            if (isRenderReusable(needYUV, surfaceTexture)) {
                return true
            }

            ALog.i(TAG, "prepareRender synchronized executing, needYUV=$needYUV")

            // 必须在创建 EGL 窗口表面之前设定缓冲尺寸，否则会按 TextureView 全屏尺寸分配。
            player.applyOutputSurfaceSizeFromConfig()

            if (render != null) {
                ALog.i(TAG, "Destroying existing render instance.")
                render?.destroyRender()
                render = null
            }

            try {
                render = if (needYUV) {
                    val yuvRender = YUVRender(surfaceTexture, isSoftRender = true)
                    val renderHandler = renderThread.handler
                    if (renderHandler != null && Looper.myLooper() == renderHandler.looper) {
                        // 已在 renderThread 上，直接初始化，避免 CountDownLatch 死锁
                        ALog.i(TAG, "Triggering YUVRender initGL on renderThread")
                        yuvRender.initGL()
                    } else {
                        val initLatch = CountDownLatch(1)
                        renderHandler?.post {
                            try {
                                ALog.i(TAG, "Triggering YUVRender initGL from renderThread")
                                yuvRender.initGL()
                            } catch (e: Exception) {
                                ALog.e(TAG, "YUVRender initGL failed: $e", e)
                            } finally {
                                initLatch.countDown()
                            }
                        } ?: return false

                        if (!initLatch.await(10, TimeUnit.SECONDS)) {
                            ALog.e(TAG, "YUVRender initGL timeout!")
                            return false
                        }
                    }

                    if (!(yuvRender.isEglReady())) {
                        ALog.e(TAG, "YUVRender initGL finished but EGL not ready")
                        return false
                    }

                    yuvRender
                } else {
                    Render(surfaceTexture, isSoftRender = false).apply {
                        applyOutputViewPort()
                    }
                }

                player.configManager.config?.let { render?.setAnimConfig(it) }
                renderSurfaceRef = WeakReference(surfaceTexture)

            } catch (e: Exception) {
                ALog.e(TAG, "Render creation failed: $e")
                return false
            }
            return render != null
        }
    }

    fun preparePlay(videoWidth: Int, videoHeight: Int): Boolean {
        val renderHandler = renderThread.handler
        if (renderHandler != null && Looper.myLooper() != renderHandler.looper) {
            val latch = CountDownLatch(1)
            var success = false
            renderHandler.post {
                try {
                    success = preparePlayInternal(videoWidth, videoHeight)
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(10, TimeUnit.SECONDS)) {
                ALog.e(TAG, "preparePlay timeout")
                return false
            }
            return success
        }
        return preparePlayInternal(videoWidth, videoHeight)
    }

    private fun preparePlayInternal(videoWidth: Int, videoHeight: Int): Boolean {
        if (render is YUVRender) {
            if (!(render as YUVRender).bindGlContext()) {
                ALog.e(TAG, "preparePlayInternal: bindGlContext failed")
                return false
            }
        }
        player.configManager.defaultConfig(videoWidth, videoHeight)
        player.configManager.config?.apply {
            render?.setAnimConfig(this)
        }
        player.applyOutputSurfaceSizeFromConfig()
        applyOutputViewPort()

        val outputW = player.outputSurfaceWidth
        val outputH = player.outputSurfaceHeight
        ALog.i(TAG, "preparePlayInternal: output=${outputW}x${outputH}, video=${videoWidth}x${videoHeight}")

        return try {
            player.pluginManager.onRenderCreate()
            true
        } catch (e: Exception) {
            ALog.e(TAG, "preparePlayInternal: onRenderCreate failed", e)
            false
        }
    }

    /**
     * decode过程中视频尺寸变化
     * 主要是没有16进制对齐的老视频
     */
    fun videoSizeChange(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0) return
        val config = player.configManager.config ?: return
        if (config.videoWidth != newWidth || config.videoHeight != newHeight) {
            ALog.i(
                TAG,
                "videoSizeChange old=(${config.videoWidth},${config.videoHeight}), new=($newWidth,$newHeight)"
            )
            config.videoWidth = newWidth
            config.videoHeight = newHeight
            render?.setAnimConfig(config)
        }
    }


    fun destroyThread() {
        if (player.isDetachedFromWindow) {
            ALog.i(TAG, "destroyThread")
            renderThread.handler?.removeCallbacksAndMessages(null)
            decodeThread.handler?.removeCallbacksAndMessages(null)
            renderThread.thread = quitSafely(renderThread.thread)
            decodeThread.thread = quitSafely(decodeThread.thread)
            renderThread.handler = null
            decodeThread.handler = null
        }
    }

    fun onSurfaceSizeChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        val handler = renderThread.handler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { applyOutputViewPort() }
        } else {
            applyOutputViewPort()
        }
    }

    private fun applyOutputViewPort() {
        val outputW = player.outputSurfaceWidth
        val outputH = player.outputSurfaceHeight
        if (outputW > 0 && outputH > 0) {
            render?.updateViewPort(outputW, outputH)
            return
        }
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            render?.updateViewPort(surfaceWidth, surfaceHeight)
        }
    }

    override fun onVideoStart() {
        ALog.i(TAG, "onVideoStart")
        player.animListener?.onVideoStart()
    }

    override fun onVideoRender(frameIndex: Int, config: AnimConfig?) {
        ALog.d(TAG, "onVideoRender")
        player.animListener?.onVideoRender(frameIndex, config)
    }

    override fun onVideoComplete() {
        ALog.i(TAG, "onVideoComplete")
        player.animListener?.onVideoComplete()
    }

    override fun onVideoDestroy() {
        ALog.i(TAG, "onVideoDestroy")
        player.animListener?.onVideoDestroy()
    }


    override fun onFailed(errorType: Int, errorMsg: String?) {
        ALog.e(TAG, "onFailed errorType=$errorType, errorMsg=$errorMsg")
        // 如果是 SoftDecoder 报错，说明这已经是最后的降级防线了，无需再尝试降级，必须立刻回调通知上层
        if (this is SoftDecoder) {
            ALog.e(TAG, "SoftDecoder totally failed, bypass check and notice listener directly.")
            player.animListener?.onFailed(errorType, errorMsg)
            return
        }


        val wasDegrading = player.isDegrading
        player.handleDecoderFailed(errorType, errorMsg ?: "")

        // 说明成功开启了软解降级，此时 fallbackStarted 为 true，会拦截并默默吞掉这次硬解的错误
        val fallbackStarted = !wasDegrading && player.isDegrading && player.decoder is SoftDecoder
        if (!fallbackStarted) {
            player.animListener?.onFailed(errorType, errorMsg)
        }
    }


}

data class HandlerHolder(var thread: HandlerThread?, var handler: Handler?)