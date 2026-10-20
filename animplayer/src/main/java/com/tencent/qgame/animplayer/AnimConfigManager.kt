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

import android.os.SystemClock
import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.mix.FrameSource
import com.tencent.qgame.animplayer.mix.SrcMap
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.MediaUtil
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * 配置管理
 */
class AnimConfigManager(val player: AnimPlayer) {

    companion object {
        private const val TAG = "${Constant.TAG}.AnimConfigManager"
        private const val MAX_VAPC_PAYLOAD_SIZE = 1024 * 1024
        private const val TAIL_SEARCH_SIZE = 1024 * 1024L
        private const val TAIL_CHUNK_SIZE = 64 * 1024
    }

    var config: AnimConfig? = null
    var isParsingConfig = false // 是否正在读取配置

    /**
     * 解析配置
     * @return true 解析成功 false 解析失败
     */
    fun parseConfig(fileContainer: IFileContainer, enableVersion1: Boolean, defaultVideoMode: Int, defaultFps: Int): Int {
        try {
            isParsingConfig = true
            // 解析vapc
            val time = SystemClock.elapsedRealtime()
            val result = parse(fileContainer, defaultVideoMode, defaultFps)
            ALog.i(TAG, "parseConfig cost=${SystemClock.elapsedRealtime() - time}ms enableVersion1=$enableVersion1 result=$result")
            if (!result) {
                isParsingConfig = false
                return Constant.REPORT_ERROR_TYPE_PARSE_CONFIG
            }
            if (config?.isDefaultConfig == true && !enableVersion1) {
                isParsingConfig = false
                return Constant.REPORT_ERROR_TYPE_PARSE_CONFIG
            }
            // 插件解析配置
            val resultCode = config?.let {
                player.pluginManager.onConfigCreate(it)
            } ?: Constant.OK
            isParsingConfig = false
            return resultCode
        } catch (e : Throwable) {
            ALog.e(TAG, "parseConfig error $e", e)
            config?.jsonConfig = null
            isParsingConfig = false
            return Constant.REPORT_ERROR_TYPE_PARSE_CONFIG
        }
    }

    /**
     * 默认配置解析（兼容老视频格式）
     */
    fun defaultConfig(_videoWidth: Int, _videoHeight: Int) {
        if (config?.isDefaultConfig == false) return
        config?.apply {
            videoWidth = _videoWidth
            videoHeight = _videoHeight
            when (defaultVideoMode) {
                Constant.VIDEO_MODE_SPLIT_HORIZONTAL -> {
                    // 视频左右对齐（alpha左\rgb右）
                    width = _videoWidth / 2
                    height = _videoHeight
                    alphaPointRect = PointRect(0, 0, width, height)
                    rgbPointRect = PointRect(width, 0, width, height)
                }
                Constant.VIDEO_MODE_SPLIT_VERTICAL -> {
                    // 视频上下对齐（alpha上\rgb下）
                    width = _videoWidth
                    height = _videoHeight / 2
                    alphaPointRect = PointRect(0, 0, width, height)
                    rgbPointRect = PointRect(0, height, width, height)
                }
                Constant.VIDEO_MODE_SPLIT_HORIZONTAL_REVERSE -> {
                    // 视频左右对齐（rgb左\alpha右）
                    width = _videoWidth / 2
                    height = _videoHeight
                    rgbPointRect = PointRect(0, 0, width, height)
                    alphaPointRect = PointRect(width, 0, width, height)
                }
                Constant.VIDEO_MODE_SPLIT_VERTICAL_REVERSE -> {
                    // 视频上下对齐（rgb上\alpha下）
                    width = _videoWidth
                    height = _videoHeight / 2
                    rgbPointRect = PointRect(0, 0, width, height)
                    alphaPointRect = PointRect(0, height, width, height)
                }
                else -> {
                    // 默认视频左右对齐（alpha左\rgb右）
                    width = _videoWidth / 2
                    height = _videoHeight
                    alphaPointRect = PointRect(0, 0, width, height)
                    rgbPointRect = PointRect(width, 0, width, height)
                }
            }
        }
    }


    private fun parse(fileContainer: IFileContainer, defaultVideoMode: Int, defaultFps: Int): Boolean {
        val config = AnimConfig()
        this.config = config

        try {
            fileContainer.startRandomRead()
            val located = findVapcPayload(fileContainer)
            if (located != null) {
                val parsed = runCatching {
                    parseVapcConfig(config, located.bytes, located.offset, fileContainer, defaultFps)
                }.getOrElse {
                    ALog.e(TAG, "vapc parse failed $it", it)
                    false
                }
                if (parsed) {
                    return true
                }
                ALog.e(TAG, "vapc invalid, fallback to default config")
            } else {
                ALog.e(TAG, "vapc box head not found")
            }
            return applyDefaultConfig(config, fileContainer, defaultVideoMode, defaultFps)
        } finally {
            fileContainer.closeRandomRead()
        }
    }

    private class LocatedVapc(val bytes: ByteArray, val offset: Long)

    private fun findVapcPayload(fileContainer: IFileContainer): LocatedVapc? {
        var position = 0L
        while (true) {
            val initial = ByteArray(8)
            if (fileContainer.read(initial, 0, initial.size) != 8) break
            position += 8

            val header = parseBoxHeader(fileContainer, initial) ?: break
            position += header.extraRead
            val box = header.box
            if ("vapc" == box.type) {
                val payload = readBoxPayload(fileContainer, box) ?: return null
                return LocatedVapc(payload, position)
            }

            if (box.size == 0L) {
                ALog.i(TAG, "stop search vapc at box=${box.type}, extends to EOF")
                break
            }
            if (box.size < box.headerSize) {
                ALog.i(TAG, "stop search vapc at box=${box.type}, length=${box.size}")
                break
            }

            val payloadSize = box.payloadSize
            if (payloadSize > 0) {
                fileContainer.skip(payloadSize)
                position += payloadSize
            }
        }

        return findVapcPayloadInTail(fileContainer)
    }

    private fun applyDefaultConfig(
        config: AnimConfig,
        fileContainer: IFileContainer,
        defaultVideoMode: Int,
        defaultFps: Int
    ): Boolean {
        config.apply {
            isDefaultConfig = true
            this.defaultVideoMode = defaultVideoMode
            fps = defaultFps
        }

        val format = MediaUtil.getVideoFormat(fileContainer) ?: run {
            ALog.e(TAG, "default config probe failed")
            return false
        }
        val width = format.getInteger(android.media.MediaFormat.KEY_WIDTH)
        val height = format.getInteger(android.media.MediaFormat.KEY_HEIGHT)
        if (width <= 0 || height <= 0) {
            ALog.e(TAG, "default config invalid size ${width}x$height")
            return false
        }

        defaultConfig(width, height)
        if (config.fps <= 0 && format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
            config.fps = format.getInteger(android.media.MediaFormat.KEY_FRAME_RATE)
        }
        player.fps = config.fps
        return true
    }

    private data class Mp4Box(val type: String, val size: Long, val headerSize: Int) {
        val payloadSize: Long get() = size - headerSize
    }

    private class ParsedHeader(val box: Mp4Box, val extraRead: Int)

    private fun parseBoxHeader(fileContainer: IFileContainer, initial8: ByteArray): ParsedHeader? {
        val type = String(initial8, 4, 4, Charset.forName("US-ASCII"))
        var size = readUInt32BE(initial8, 0).toLong()
        var headerSize = 8
        var extraRead = 0

        if (size == 1L) {
            val ext = ByteArray(8)
            if (fileContainer.read(ext, 0, ext.size) != ext.size) return null
            size = readUInt64BE(ext, 0)
            headerSize = 16
            extraRead = 8
        } else if (size == 0L) {
            return ParsedHeader(Mp4Box(type, 0, headerSize), extraRead)
        }

        if (size < headerSize) return null
        return ParsedHeader(Mp4Box(type, size, headerSize), extraRead)
    }

    private fun readBoxPayload(fileContainer: IFileContainer, box: Mp4Box): ByteArray? {
        val payloadSize = box.payloadSize
        if (payloadSize <= 0 || payloadSize > MAX_VAPC_PAYLOAD_SIZE) {
            ALog.e(TAG, "invalid vapc payload size=$payloadSize")
            return null
        }
        val payload = ByteArray(payloadSize.toInt())
        var offset = 0
        while (offset < payload.size) {
            val read = fileContainer.read(payload, offset, payload.size - offset)
            if (read <= 0) {
                ALog.e(TAG, "read vapc payload interrupted at offset=$offset")
                return null
            }
            offset += read
        }
        return payload
    }

    private fun readUInt32BE(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 24) or
                ((data[offset + 1].toInt() and 0xff) shl 16) or
                ((data[offset + 2].toInt() and 0xff) shl 8) or
                (data[offset + 3].toInt() and 0xff)
    }

    private fun readUInt64BE(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xff)
        }
        return value
    }

    private fun parseVapcConfig(
        config: AnimConfig,
        bytes: ByteArray,
        payloadOffset: Long,
        fileContainer: IFileContainer,
        defaultFps: Int
    ): Boolean {
        val streamed = try {
            parseVapcStreaming(config, bytes, payloadOffset, fileContainer, defaultFps)
        } catch (t: Throwable) {
            ALog.e(TAG, "stream vapc parse failed, fallback to tree", t)
            false
        }
        if (streamed) return true
        return parseVapcTree(config, String(bytes, Charset.forName("UTF-8")), defaultFps)
    }

    /**
     * 只把 info / src 建成对象。frame 记下载荷内的偏移和每个素材的出现帧，
     * 播放时再按窗口把要用的帧读出来。
     */
    private fun parseVapcStreaming(
        config: AnimConfig,
        bytes: ByteArray,
        payloadOffset: Long,
        fileContainer: IFileContainer,
        defaultFps: Int
    ): Boolean {
        val indexed = FrameSource.index(bytes, fileContainer, payloadOffset) ?: return false
        if (!config.parseInfo(indexed.info)) {
            indexed.frames?.close()
            return false
        }
        if (defaultFps > 0) {
            config.fps = defaultFps
        }
        player.fps = config.fps
        val src = indexed.src
        val mixFrames = indexed.frames
        if (config.isMix && (src == null || mixFrames == null)) {
            mixFrames?.close()
            return false
        }
        config.jsonConfig = JSONObject().apply {
            put("info", indexed.info)
            if (src != null) put("src", src)
        }
        if (!config.isMix || src == null || mixFrames == null) {
            mixFrames?.close()
            return config.isMix.not()
        }
        player.pluginManager.getMixAnimPlugin()?.setStreamData(SrcMap(src), mixFrames)
        return true
    }

    private fun parseVapcTree(config: AnimConfig, json: String, defaultFps: Int): Boolean {
        val jsonObj = JSONObject(json)
        config.jsonConfig = jsonObj
        val result = config.parse(jsonObj)
        if (defaultFps > 0) {
            config.fps = defaultFps
        }
        player.fps = config.fps
        return result
    }

    private fun findVapcPayloadInTail(fileContainer: IFileContainer): LocatedVapc? {
        val fileLength = fileContainer.fileLength()
        if (fileLength > 12) {
            findVapcInRange(fileLength) { position, dst, len ->
                if (!fileContainer.seekAbsolute(position)) {
                    -1
                } else {
                    fileContainer.read(dst, 0, len)
                }
            }?.let { payload ->
                ALog.i(TAG, "vapc found from container tail")
                return payload
            }
        }

        val path = fileContainer.filePath ?: return null
        val file = File(path)
        if (!file.isFile || !file.canRead()) return null

        return try {
            RandomAccessFile(file, "r").use { raf ->
                findVapcInRange(raf.length()) { position, dst, len ->
                    raf.seek(position)
                    raf.read(dst, 0, len)
                }?.also {
                    ALog.i(TAG, "vapc found from file tail")
                }
            }
        } catch (t: Throwable) {
            ALog.e(TAG, "findVapcPayloadInTail $t", t)
            null
        }
    }

    /**
     * 从文件尾往前按 64KB 找 vapc，只分配真正的配置负载。
     * 不再把最多 1MB 的尾部整段读进内存。
     */
    private fun findVapcInRange(
        fileLength: Long,
        readAt: (position: Long, dst: ByteArray, len: Int) -> Int
    ): LocatedVapc? {
        if (fileLength <= 12) return null
        val origin = maxOf(0L, fileLength - TAIL_SEARCH_SIZE)
        val chunk = ByteArray(TAIL_CHUNK_SIZE)
        var pos = fileLength
        while (pos > origin) {
            val chunkStart = maxOf(origin, pos - chunk.size)
            val size = (pos - chunkStart).toInt()
            if (!readFully(chunkStart, chunk, size, readAt)) return null
            var index = size - 4
            while (index >= 0) {
                if (chunk[index] == 'v'.code.toByte() &&
                    chunk[index + 1] == 'a'.code.toByte() &&
                    chunk[index + 2] == 'p'.code.toByte() &&
                    chunk[index + 3] == 'c'.code.toByte()
                ) {
                    readVapcPayloadAt(fileLength, chunkStart + index, readAt)?.let { return it }
                }
                index--
            }
            if (chunkStart == origin) break
            pos = chunkStart + 3
        }
        return null
    }

    private fun readVapcPayloadAt(
        fileLength: Long,
        vapcPosition: Long,
        readAt: (position: Long, dst: ByteArray, len: Int) -> Int
    ): LocatedVapc? {
        val lengthIndex = vapcPosition - 4
        if (lengthIndex < 0) return null
        val lengthBytes = ByteArray(4)
        if (!readFully(lengthIndex, lengthBytes, 4, readAt)) return null
        val boxLength = readUInt32BE(lengthBytes, 0).toLong()
        if (boxLength <= 8 || boxLength > MAX_VAPC_PAYLOAD_SIZE + 8) return null
        if (lengthIndex + boxLength > fileLength) return null

        val payload = ByteArray((boxLength - 8).toInt())
        if (!readFully(vapcPosition + 4, payload, payload.size, readAt)) return null
        return LocatedVapc(payload, vapcPosition + 4)
    }

    /**
     * readAt 每次都从 dst[0] 写入。分多次读时用一块小缓冲再拷过去，避免后一次覆盖前一次。
     */
    private fun readFully(
        position: Long,
        dst: ByteArray,
        len: Int,
        readAt: (position: Long, dst: ByteArray, len: Int) -> Int
    ): Boolean {
        if (len <= 0 || len > dst.size) return false
        val scratch = ByteArray(minOf(8192, len))
        var done = 0
        while (done < len) {
            val want = minOf(scratch.size, len - done)
            val read = readAt(position + done, scratch, want)
            if (read <= 0) return false
            System.arraycopy(scratch, 0, dst, done, read)
            done += read
        }
        return true
    }

}