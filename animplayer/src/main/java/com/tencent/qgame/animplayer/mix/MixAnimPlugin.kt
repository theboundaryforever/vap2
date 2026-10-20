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
package com.tencent.qgame.animplayer.mix

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.AnimPlayer
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.inter.IFetchResource
import com.tencent.qgame.animplayer.inter.OnResourceClickListener
import com.tencent.qgame.animplayer.plugin.IAnimPlugin
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.BitmapUtil

class MixAnimPlugin(val player: AnimPlayer): IAnimPlugin {

    companion object {
        private const val TAG = "${Constant.TAG}.MixAnimPlugin"
        // 开头这几帧的素材没到齐就先不播，避免第一帧是空的。
        private const val SYNC_FRAMES = 1
        // 本地图提前约一秒，网络图再早一点。窗口之外的不进内存。
        private const val LOCAL_AHEAD = 15
        private const val NET_AHEAD = 45
    }
    var resourceRequest: IFetchResource? = null
    var resourceClickListener: OnResourceClickListener? = null
    var srcMap: SrcMap? = null
    private var frames: MixFrames? = null
    var curFrameIndex = -1 // 当前帧
    private var mixRender:MixRender? = null
    private val mixTouch by lazy { MixTouch(this) }
    var autoTxtColorFill = true // 是否启动自动文字填充 默认开启

    // 同步锁
    private val lock = Object()
    private var forceStopLock = false
    private var cpuBitmapsReleased = false
    private var streamParsed = false
    // 按槽位缩小后的图是库自己创建的，回收时不能再交给调用方。
    private val scaledBitmaps = HashSet<Bitmap>()
    // 每次请求一个票据。素材被换出窗口后旧回调不能再把图放回来。
    private val tickets = HashMap<String, Int>()
    private var nextTicket = 1
    private var residentIds = emptySet<String>()

    /**
     * 配置在流式解析时已经拆成 SrcMap 和帧索引。
     * 帧几何按播放进度再读，这里不接收整段帧对象。
     */
    internal fun setStreamData(src: SrcMap?, mixFrames: MixFrames?) {
        frames?.close()
        srcMap = src
        frames = mixFrames
        streamParsed = true
    }

    fun framesAt(index: Int): List<Frame>? = frames?.frameSet(index)?.list

    fun maxSlot(src: Src): Pair<Int, Int> {
        val slot = frames?.maxSlot(src.srcId)
        if (slot != null && slot.first > 0 && slot.second > 0) return slot
        return src.w.coerceAtLeast(1) to src.h.coerceAtLeast(1)
    }

    override fun onConfigCreate(config: AnimConfig): Int {
        cpuBitmapsReleased = false
        mixRender = null
        if (!config.isMix) {
            clearMixData()
            return Constant.OK
        }
        if (resourceRequest == null) {
            ALog.e(TAG, "IFetchResource is empty")
            clearMixData()
            // 没有设置IFetchResource 当成普通视频播放
            return Constant.OK
        }
        if (!streamParsed) {
            // step 1 parse src
            parseSrc(config)
            // step 2 parse frame
            parseFrame(config)
        }
        streamParsed = false
        // frame 数组已在解析时丢掉。这里再去掉一次，避免树形兜底把整段帧 JSON 留到加载图片时。
        config.jsonConfig?.remove("frame")

        synchronized(lock) {
            forceStopLock = false
            tickets.clear()
        }
        residentIds = emptySet()
        // 只等开头几帧要用的素材。其余的等播放走到附近再取。
        val immediate = sourcesUsed(0, SYNC_FRAMES)
        val upcoming = neededAt(0)
        ALog.i(TAG, "load resource immediate=${immediate.size} upcoming=${upcoming.size} total=${srcMap?.map?.size ?: 0}")
        upcoming.forEach { request(it) }
        waitFor(immediate)
        if (forceStopLock) return Constant.OK
        for (src in immediate) {
            if (src.bitmap == null && src.srcTextureId == 0) {
                ALog.e(TAG, "missing src $src")
                return Constant.REPORT_ERROR_TYPE_CONFIG_PLUGIN_MIX
            }
            if (src.bitmap?.config == Bitmap.Config.ALPHA_8) {
                ALog.e(TAG, "src $src bitmap must not be ALPHA_8")
                return Constant.REPORT_ERROR_TYPE_CONFIG_PLUGIN_MIX
            }
        }
        residentIds = upcoming.map { it.srcId }.toSet()
        return Constant.OK
    }

    override fun onRenderCreate() {
        if (player.configManager.config?.isMix == false) return
        if (mixRender == null) {
            ALog.i(TAG, "mix render init")
            mixRender = MixRender(this)
            mixRender?.init()
        } else {
            // 重建 GL 上下文后，旧纹理 id 不能再用。窗口里的素材下一帧重新上传。
            ALog.i(TAG, "mix render gl reset")
            srcMap?.map?.values?.forEach { it.srcTextureId = 0 }
        }
        uploadReady()
    }

    override fun onRendering(frameIndex: Int) {
        val config = player.configManager.config ?: return
        if (!config.isMix) return
        curFrameIndex = frameIndex
        syncWindow(frameIndex)
        uploadReady()
        val list = framesAt(frameIndex) ?: return
        list.forEach { frame ->
            val src = srcMap?.map?.get(frame.srcId) ?: return@forEach
            if (src.srcTextureId == 0) return@forEach
            mixRender?.renderFrame(config, frame, src)
        }
    }


    override fun onRelease() {
        destroy()
    }

    override fun onDestroy() {
        destroy()
    }

    override fun onDispatchTouchEvent(ev: MotionEvent): Boolean {
        if (player.configManager.config?.isMix == false || resourceClickListener == null) {
            return super.onDispatchTouchEvent(ev)
        }
        mixTouch.onTouchEvent(ev)?.let {resource ->
            Handler(Looper.getMainLooper()).post {
                resourceClickListener?.onClick(resource)
            }
        }
        // 只要注册监听则拦截所有事件
        return true
    }

    private fun destroy() {
        // 强制结束等待
        forceStopLockThread()
        if (player.configManager.config?.isMix == false) return
        val resources = ArrayList<Resource>()
        srcMap?.map?.values?.forEach { src ->
            mixRender?.release(src.srcTextureId)
            src.srcTextureId = 0
            if (!cpuBitmapsReleased || src.srcType != Src.SrcType.IMG) {
                releaseHeldBitmap(src)?.let { resources.add(it) }
            }
        }
        if (resources.isNotEmpty()) {
            resourceRequest?.releaseResource(resources)
        }
        cpuBitmapsReleased = true
        mixRender = null

        // 清理
        curFrameIndex = -1
        srcMap?.map?.clear()
        frames?.close()
        frames = null
        residentIds = emptySet()
        synchronized(lock) { tickets.clear() }
    }

    private fun parseSrc(config: AnimConfig) {
        config.jsonConfig?.apply {
            srcMap = SrcMap(this)
        }
    }


    private fun clearMixData() {
        streamParsed = false
        srcMap = null
        frames?.close()
        frames = null
        residentIds = emptySet()
    }

    private fun parseFrame(config: AnimConfig) {
        config.jsonConfig?.apply {
            frames = ResidentFrames(FrameAll(this))
            // 帧对象已经建好。丢掉原始帧数组，避免和后面加载的图片叠在一起。
            remove("frame")
        }
    }

    /**
     * 纹理已经拷走像素。立刻丢掉这张 CPU 位图，不要等全部贴图都上传完再一起放。
     */
    fun releaseSrcAfterUpload(src: Src) {
        releaseHeldBitmap(src)?.let { resourceRequest?.releaseResource(listOf(it)) }
    }

    private fun neededAt(frameIndex: Int): List<Src> {
        val map = srcMap?.map ?: return emptyList()
        val total = player.configManager.config?.totalFrames ?: 0
        val looping = player.playLoop > 1 && total > 0
        val result = ArrayList<Src>()
        for (src in map.values) {
            if (src.srcType != Src.SrcType.IMG && src.srcType != Src.SrcType.TXT) continue
            val ahead = if (src.loadType == Src.LoadType.NET) NET_AHEAD else LOCAL_AHEAD
            val end = frameIndex + ahead
            var hit = frames?.isUsedBetween(src.srcId, frameIndex, end) == true
            if (!hit && looping && end >= total) {
                hit = frames?.isUsedBetween(src.srcId, 0, end - total) == true
            }
            if (hit) result.add(src)
        }
        return result
    }

    private fun sourcesUsed(from: Int, to: Int): List<Src> {
        val map = srcMap?.map ?: return emptyList()
        val result = ArrayList<Src>()
        for (src in map.values) {
            if (src.srcType != Src.SrcType.IMG && src.srcType != Src.SrcType.TXT) continue
            if (frames?.isUsedBetween(src.srcId, from, to) == true) result.add(src)
        }
        return result
    }

    /**
     * 窗口里的素材留下，已经离开窗口的纹理和位图马上释放。
     */
    private fun syncWindow(frameIndex: Int) {
        val needed = neededAt(frameIndex)
        val ids = HashSet<String>(needed.size)
        for (src in needed) ids.add(src.srcId)
        if (ids != residentIds) {
            evictExcept(ids)
            residentIds = ids
        }
        for (src in needed) request(src)
    }

    private fun evictExcept(keep: Set<String>) {
        val srcs = srcMap?.map?.values ?: return
        for (src in srcs) {
            if (keep.contains(src.srcId)) continue
            val hadTexture = src.srcTextureId != 0
            val hadBitmap = src.bitmap != null
            if (hadTexture) {
                mixRender?.release(src.srcTextureId)
                src.srcTextureId = 0
            }
            synchronized(lock) { tickets.remove(src.srcId) }
            val handed = releaseHeldBitmap(src)
            if (handed != null) resourceRequest?.releaseResource(listOf(handed))
            if (hadTexture || hadBitmap) {
                ALog.i(TAG, "release src ${src.srcId}")
            }
        }
    }

    private fun request(src: Src) {
        val ticket: Int
        synchronized(lock) {
            if (forceStopLock) return
            if (src.srcTextureId != 0 || (src.bitmap != null && src.bitmap?.isRecycled == false)) return
            if (tickets.containsKey(src.srcId)) return
            ticket = nextTicket++
            tickets[src.srcId] = ticket
        }
        val config = player.configManager.config
        val (outputW, outputH) = SrcBitmapScaler.outputSize(player)
        val (slotW, slotH) = maxSlot(src)
        val (targetW, targetH) = SrcBitmapScaler.targetSize(src, config, outputW, outputH, slotW, slotH)
        try {
            if (src.srcType == Src.SrcType.IMG) {
                ALog.i(TAG, "fetch image ${src.srcId} target=${targetW}x$targetH")
                val resource = Resource(src).apply {
                    targetWidth = targetW
                    targetHeight = targetH
                }
                resourceRequest?.fetchImage(resource) { bitmap ->
                    onImage(src, ticket, bitmap, targetW, targetH)
                } ?: abandon(src.srcId, ticket)
            } else if (src.srcType == Src.SrcType.TXT) {
                ALog.i(TAG, "fetch txt ${src.srcId}")
                resourceRequest?.fetchText(Resource(src)) { text ->
                    onText(src, ticket, text)
                } ?: abandon(src.srcId, ticket)
            } else {
                abandon(src.srcId, ticket)
            }
        } catch (t: Throwable) {
            ALog.e(TAG, "fetch src ${src.srcId} failed", t)
            abandon(src.srcId, ticket)
        }
    }

    private fun onImage(src: Src, ticket: Int, bitmap: Bitmap?, targetW: Int, targetH: Int) {
        if (!holdsTicket(src.srcId, ticket)) {
            dropBitmap(src, bitmap)
            return
        }
        val scaled = try {
            acceptImage(src, bitmap, targetW, targetH)
        } catch (t: Throwable) {
            ALog.e(TAG, "scale src ${src.srcId} failed", t)
            dropBitmap(src, bitmap)
            abandon(src.srcId, ticket)
            return
        }
        if (!commitBitmap(src, ticket, scaled)) {
            dropBitmap(src, scaled)
            return
        }
        ALog.i(TAG, "fetch image ${src.srcId} finish bitmap is ${scaled.width}x${scaled.height}")
    }

    private fun onText(src: Src, ticket: Int, text: String?) {
        if (!holdsTicket(src.srcId, ticket)) return
        src.txt = text ?: ""
        val bitmap = try {
            BitmapUtil.createTxtBitmap(src)
        } catch (e: OutOfMemoryError) {
            ALog.e(TAG, "draw text OOM ${src.srcId} $e", e)
            null
        }
        if (bitmap != null) ownBitmap(bitmap)
        if (!commitBitmap(src, ticket, bitmap)) {
            dropBitmap(src, bitmap)
        } else {
            ALog.i(TAG, "fetch text ${src.srcId} finish txt is $text")
        }
    }

    private fun waitFor(srcs: List<Src>) {
        if (srcs.isEmpty()) return
        synchronized(lock) {
            while (!forceStopLock && srcs.any { tickets.containsKey(it.srcId) }) {
                try {
                    lock.wait()
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun holdsTicket(srcId: String, ticket: Int): Boolean {
        return synchronized(lock) { tickets[srcId] == ticket }
    }

    private fun commitBitmap(src: Src, ticket: Int, bitmap: Bitmap?): Boolean {
        synchronized(lock) {
            if (tickets[src.srcId] != ticket) return false
            tickets.remove(src.srcId)
            src.bitmap = bitmap
            lock.notifyAll()
            return true
        }
    }

    private fun abandon(srcId: String, ticket: Int) {
        synchronized(lock) {
            if (tickets[srcId] == ticket) tickets.remove(srcId)
            lock.notifyAll()
        }
    }

    private fun forceStopLockThread() {
        synchronized(lock) {
            forceStopLock = true
            tickets.clear()
            lock.notifyAll()
        }
    }

    /**
     * 回调一回来就缩到槽位像素，不等全部图片到齐。
     * 缩小图是库自己创建的；原图立刻交回调用方回收。
     */
    private fun acceptImage(src: Src, bitmap: Bitmap?, targetW: Int, targetH: Int): Bitmap {
        val source = bitmap ?: run {
            ALog.e(TAG, "fetch image ${src.srcId} bitmap return null")
            val empty = BitmapUtil.createEmptyBitmap()
            ownBitmap(empty)
            return empty
        }
        val scaled = SrcBitmapScaler.scale(source, targetW, targetH, src.srcId)
        if (scaled !== source) {
            ownBitmap(scaled)
            val original = Resource(src)
            original.bitmap = source
            resourceRequest?.releaseResource(listOf(original))
        }
        return scaled
    }

    private fun ownBitmap(bitmap: Bitmap) {
        synchronized(lock) { scaledBitmaps.add(bitmap) }
    }

    /**
     * 库自己的图直接 recycle。调用方的原图交回 [IFetchResource.releaseResource]。
     * 返回非空表示还要交给调用方。
     */
    private fun releaseHeldBitmap(src: Src): Resource? {
        val bitmap = synchronized(lock) {
            val held = src.bitmap
            src.bitmap = null
            held
        } ?: return null
        return discardBitmap(src, bitmap)
    }

    private fun uploadReady() {
        val render = mixRender ?: return
        srcMap?.map?.values?.forEach { src ->
            if (src.srcTextureId != 0) return@forEach
            val bitmap = src.bitmap ?: return@forEach
            if (bitmap.isRecycled) return@forEach
            render.upload(src)
        }
    }

    private fun dropBitmap(src: Src, bitmap: Bitmap?) {
        val handed = discardBitmap(src, bitmap) ?: return
        resourceRequest?.releaseResource(listOf(handed))
    }

    private fun discardBitmap(src: Src, bitmap: Bitmap?): Resource? {
        if (bitmap == null || bitmap.isRecycled) return null
        val owned = synchronized(lock) { scaledBitmaps.remove(bitmap) }
        if (owned || src.srcType == Src.SrcType.TXT) {
            bitmap.recycle()
            return null
        }
        val resource = Resource(src)
        resource.bitmap = bitmap
        return resource
    }
}