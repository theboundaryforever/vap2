package com.tencent.qgame.animplayer.mix

import android.util.SparseArray
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.util.ALog
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.charset.Charset

/**
 * 播放时要查的帧。只生成当前窗口里的帧对象，不把整段帧留在内存里。
 */
internal interface MixFrames {
    fun frameSet(index: Int): FrameSet?
    fun isUsedBetween(srcId: String, from: Int, to: Int): Boolean
    fun maxSlot(srcId: String): Pair<Int, Int>?
    fun close()
}

internal class IndexedVapc(
    val info: JSONObject,
    val src: JSONArray?,
    val frames: FrameSource?,
)

/**
 * 帧几何按文件偏移现读。这里只留每个素材出现在哪些帧、以及它的最大槽位。
 */
internal class FrameSource(
    private val container: IFileContainer,
    private val payloadOffset: Long,
    private val frameIndices: IntArray,
    private val starts: IntArray,
    private val ends: IntArray,
    private val srcUse: Map<String, SrcUse>,
) : MixFrames {

    private val cache = SparseArray<FrameSet>()
    private val cacheLock = Any()
    private val ioLock = Any()
    private var opened = false

    override fun frameSet(index: Int): FrameSet? {
        synchronized(cacheLock) {
            cache.get(index)?.let { return it }
        }
        val set = loadWindow(index)
        retain(index - 1, index + CACHE_FRAMES)
        return set
    }

    override fun isUsedBetween(srcId: String, from: Int, to: Int): Boolean {
        val frames = srcUse[srcId]?.frames ?: return false
        return containsRange(frames, from, to)
    }

    override fun maxSlot(srcId: String): Pair<Int, Int>? {
        val use = srcUse[srcId] ?: return null
        if (use.maxW <= 0 || use.maxH <= 0) return null
        return use.maxW to use.maxH
    }

    override fun close() {
        synchronized(cacheLock) { cache.clear() }
        synchronized(ioLock) {
            if (!opened) return
            try {
                container.closeRandomRead()
            } catch (t: Throwable) {
                ALog.e(TAG, "close frame source", t)
            } finally {
                opened = false
            }
        }
    }

    private fun retain(from: Int, to: Int) {
        synchronized(cacheLock) {
            for (i in cache.size() - 1 downTo 0) {
                val key = cache.keyAt(i)
                if (key < from || key > to) cache.removeAt(i)
            }
        }
    }

    private fun loadWindow(index: Int): FrameSet? {
        val pos = findPos(index)
        if (pos < 0) return null
        var last = minOf(frameIndices.size - 1, pos + CACHE_FRAMES - 1)
        if (ends[last] - starts[pos] > MAX_WINDOW_BYTES) last = pos
        val bytes = readBytes(starts[pos], ends[last]) ?: return null
        val base = starts[pos]
        var found: FrameSet? = null
        for (p in pos..last) {
            val start = starts[p] - base
            val end = ends[p] - base
            if (start < 0 || end > bytes.size || start >= end) continue
            val set = try {
                FrameSet(JSONObject(String(bytes, start, end - start, UTF_8)))
            } catch (t: Throwable) {
                ALog.e(TAG, "parse frame ${frameIndices[p]} failed", t)
                continue
            }
            synchronized(cacheLock) { cache.put(set.index, set) }
            if (set.index == index) found = set
        }
        return found
    }

    private fun findPos(frameIndex: Int): Int {
        var lo = 0
        var hi = frameIndices.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val value = frameIndices[mid]
            when {
                value == frameIndex -> return mid
                value < frameIndex -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return -1
    }

    private fun readBytes(start: Int, end: Int): ByteArray? {
        val len = end - start
        if (len <= 0 || len > MAX_WINDOW_BYTES) return null
        val buf = ByteArray(len)
        synchronized(ioLock) {
            if (!opened) {
                try {
                    container.startRandomRead()
                    opened = true
                } catch (t: Throwable) {
                    ALog.e(TAG, "open frame source failed", t)
                    return null
                }
            }
            if (!container.seekAbsolute(payloadOffset + start)) return null
            var done = 0
            while (done < len) {
                val read = container.read(buf, done, len - done)
                if (read <= 0) return null
                done += read
            }
        }
        return buf
    }

    companion object {
        private const val TAG = "${Constant.TAG}.FrameSource"
        private const val CACHE_FRAMES = 6
        private const val MAX_WINDOW_BYTES = 1024 * 1024
        private val UTF_8: Charset = Charset.forName("UTF-8")

        fun index(bytes: ByteArray, container: IFileContainer, payloadOffset: Long): IndexedVapc? {
            return try {
                Cursor(bytes).read(container, payloadOffset)
            } catch (t: Throwable) {
                ALog.e(TAG, "index vapc failed", t)
                null
            }
        }

        fun containsRange(frames: IntArray, from: Int, to: Int): Boolean {
            if (frames.isEmpty() || from > to) return false
            var lo = 0
            var hi = frames.size - 1
            var ans = frames.size
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (frames[mid] >= from) {
                    ans = mid
                    hi = mid - 1
                } else {
                    lo = mid + 1
                }
            }
            return ans < frames.size && frames[ans] <= to
        }
    }
}

internal class SrcUse(
    val frames: IntArray,
    val maxW: Int,
    val maxH: Int,
)

/**
 * 树形兜底已经把帧对象建出来了。查询接口和流式索引一致，图片仍按窗口加载。
 */
internal class ResidentFrames(all: FrameAll) : MixFrames {
    private val sets = all.map
    private val use = HashMap<String, SrcUse>()

    init {
        val builders = HashMap<String, SrcUseBuilder>()
        for (i in 0 until sets.size()) {
            val set = sets.valueAt(i) ?: continue
            for (frame in set.list) {
                builders.getOrPut(frame.srcId) { SrcUseBuilder() }
                    .add(set.index, frame.frame.w, frame.frame.h)
            }
        }
        builders.forEach { (id, builder) -> use[id] = builder.build() }
    }

    override fun frameSet(index: Int): FrameSet? = sets.get(index)

    override fun isUsedBetween(srcId: String, from: Int, to: Int): Boolean {
        val frames = use[srcId]?.frames ?: return false
        return FrameSource.containsRange(frames, from, to)
    }

    override fun maxSlot(srcId: String): Pair<Int, Int>? {
        val src = use[srcId] ?: return null
        if (src.maxW <= 0 || src.maxH <= 0) return null
        return src.maxW to src.maxH
    }

    override fun close() {
        sets.clear()
    }
}

private class SrcUseBuilder {
    private val frames = ArrayList<Int>()
    private var maxW = 0
    private var maxH = 0

    fun add(frame: Int, w: Int, h: Int) {
        if (frames.isEmpty() || frames.last() != frame) frames.add(frame)
        if (w > maxW) maxW = w
        if (h > maxH) maxH = h
    }

    fun build(): SrcUse {
        val array = frames.toIntArray()
        array.sort()
        if (array.size <= 1) return SrcUse(array, maxW, maxH)
        var count = 1
        for (i in 1 until array.size) {
            if (array[i] != array[count - 1]) {
                array[count] = array[i]
                count++
            }
        }
        val packed = if (count == array.size) array else array.copyOf(count)
        return SrcUse(packed, maxW, maxH)
    }
}

private class Cursor(private val bytes: ByteArray) {
    private var i = 0
    private val size = bytes.size

    fun read(container: IFileContainer, payloadOffset: Long): IndexedVapc? {
        skipWs()
        if (i >= size || at(i) != '{'.code) return null
        i++
        var info: JSONObject? = null
        var src: JSONArray? = null
        var frames: FrameSource? = null
        while (true) {
            skipWs()
            if (i < size && at(i) == '}'.code) {
                i++
                break
            }
            val key = readKey() ?: return null
            skipWs()
            if (i >= size || at(i) != ':'.code) return null
            i++
            when (key) {
                "info" -> {
                    val slice = sliceValue() ?: return null
                    info = JSONObject(slice)
                }
                "src" -> {
                    val slice = sliceValue() ?: return null
                    src = JSONArray(slice)
                }
                "frame" -> frames = readFrames(container, payloadOffset) ?: return null
                else -> if (parseSpan() < 0) return null
            }
            skipWs()
            if (i < size && at(i) == ','.code) {
                i++
                continue
            }
            if (i < size && at(i) == '}'.code) {
                i++
                break
            }
            return null
        }
        val infoObj = info ?: return null
        return IndexedVapc(infoObj, src, frames)
    }

    private fun readFrames(container: IFileContainer, payloadOffset: Long): FrameSource? {
        skipWs()
        if (i >= size || at(i) != '['.code) return null
        i++
        val indices = ArrayList<Int>()
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        val slot = HashMap<Int, Int>()
        val builders = HashMap<String, SrcUseBuilder>()
        skipWs()
        if (i < size && at(i) == ']'.code) {
            i++
            return FrameSource(container, payloadOffset, IntArray(0), IntArray(0), IntArray(0), emptyMap())
        }
        while (true) {
            skipWs()
            val start = i
            val end = parseSpan()
            if (end < 0 || end <= start) return null
            val set = FrameSet(JSONObject(String(bytes, start, end - start, UTF_8)))
            val existing = slot[set.index]
            if (existing != null) {
                starts[existing] = start
                ends[existing] = end
            } else {
                slot[set.index] = indices.size
                indices.add(set.index)
                starts.add(start)
                ends.add(end)
            }
            for (frame in set.list) {
                builders.getOrPut(frame.srcId) { SrcUseBuilder() }
                    .add(set.index, frame.frame.w, frame.frame.h)
            }
            skipWs()
            if (i < size && at(i) == ','.code) {
                i++
                continue
            }
            if (i < size && at(i) == ']'.code) {
                i++
                break
            }
            return null
        }
        val order = indices.indices.sortedBy { indices[it] }
        val frameIndices = IntArray(order.size)
        val startArr = IntArray(order.size)
        val endArr = IntArray(order.size)
        for (n in order.indices) {
            val src = order[n]
            frameIndices[n] = indices[src]
            startArr[n] = starts[src]
            endArr[n] = ends[src]
        }
        val use = HashMap<String, SrcUse>(builders.size)
        builders.forEach { (id, builder) -> use[id] = builder.build() }
        ALog.i(TAG, "frame index count=${frameIndices.size} src=${use.size}")
        return FrameSource(container, payloadOffset, frameIndices, startArr, endArr, use)
    }

    private fun sliceValue(): String? {
        skipWs()
        val start = i
        val end = parseSpan()
        if (end < 0 || end <= start) return null
        return String(bytes, start, end - start, UTF_8)
    }

    private fun readKey(): String? {
        skipWs()
        if (i >= size || at(i) != '"'.code) return null
        val start = i
        if (!skipString()) return null
        var escaped = false
        for (p in start until i) {
            if (at(p) == '\\'.code) {
                escaped = true
                break
            }
        }
        if (!escaped) {
            val length = i - start - 2
            if (length < 0) return null
            return String(bytes, start + 1, length, UTF_8)
        }
        return JSONTokener(String(bytes, start, i - start, UTF_8)).nextValue() as? String
    }

    private fun parseSpan(): Int {
        skipWs()
        if (i >= size) return -1
        val start = i
        val ok = when (at(i)) {
            '{'.code -> skipContainer('}'.code)
            '['.code -> skipContainer(']'.code)
            '"'.code -> skipString()
            else -> {
                while (i < size) {
                    val c = at(i)
                    if (c == ','.code || c == '}'.code || c == ']'.code || c <= 32) break
                    i++
                }
                i > start
            }
        }
        return if (ok) i else -1
    }

    private fun skipContainer(close: Int): Boolean {
        var depth = 0
        while (i < size) {
            val c = at(i)
            if (c == '"'.code) {
                if (!skipString()) return false
                continue
            }
            if (c == '{'.code || c == '['.code) {
                depth++
                i++
                continue
            }
            if (c == '}'.code || c == ']'.code) {
                depth--
                i++
                if (depth == 0) return c == close
                if (depth < 0) return false
                continue
            }
            i++
        }
        return false
    }

    private fun skipString(): Boolean {
        if (i >= size || at(i) != '"'.code) return false
        i++
        while (i < size) {
            val c = at(i)
            if (c == '\\'.code) {
                i++
                if (i >= size) return false
                if (at(i) == 'u'.code) {
                    if (i + 4 >= size) return false
                    i += 5
                } else {
                    i++
                }
                continue
            }
            if (c == '"'.code) {
                i++
                return true
            }
            i++
        }
        return false
    }

    private fun skipWs() {
        while (i < size) {
            val c = at(i)
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) i++ else break
        }
    }

    private fun at(index: Int): Int = bytes[index].toInt() and 0xff

    companion object {
        private const val TAG = "${Constant.TAG}.FrameSource"
        private val UTF_8: Charset = Charset.forName("UTF-8")
    }
}
