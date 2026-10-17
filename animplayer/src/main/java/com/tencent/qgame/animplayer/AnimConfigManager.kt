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
            val vapcBuf = findVapcPayload(fileContainer)
            if (vapcBuf != null) {
                val parsed = runCatching {
                    parseVapcConfig(config, vapcBuf, defaultFps)
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

    private fun findVapcPayload(fileContainer: IFileContainer): ByteArray? {
        while (true) {
            val initial = ByteArray(8)
            if (fileContainer.read(initial, 0, initial.size) != 8) break

            val box = parseBoxHeader(fileContainer, initial) ?: break
            if ("vapc" == box.type) {
                return readBoxPayload(fileContainer, box)
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

    private fun parseBoxHeader(fileContainer: IFileContainer, initial8: ByteArray): Mp4Box? {
        val type = String(initial8, 4, 4, Charset.forName("US-ASCII"))
        var size = readUInt32BE(initial8, 0).toLong()
        var headerSize = 8

        if (size == 1L) {
            val ext = ByteArray(8)
            if (fileContainer.read(ext, 0, ext.size) != ext.size) return null
            size = readUInt64BE(ext, 0)
            headerSize = 16
        } else if (size == 0L) {
            return Mp4Box(type, 0, headerSize)
        }

        if (size < headerSize) return null
        return Mp4Box(type, size, headerSize)
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

    private fun parseVapcConfig(config: AnimConfig, vapcBuf: ByteArray, defaultFps: Int): Boolean {
        val json = String(vapcBuf, 0, vapcBuf.size, Charset.forName("UTF-8"))
        val jsonObj = JSONObject(json)
        config.jsonConfig = jsonObj
        val result = config.parse(jsonObj)
        if (defaultFps > 0) {
            config.fps = defaultFps
        }
        player.fps = config.fps
        return result
    }

    private fun findVapcPayloadInTail(fileContainer: IFileContainer): ByteArray? {
        val fileLength = fileContainer.fileLength()
        if (fileLength > 12) {
            val tailSize = minOf(TAIL_SEARCH_SIZE, fileLength)
            if (fileContainer.seekAbsolute(fileLength - tailSize)) {
                val tail = ByteArray(tailSize.toInt())
                if (fileContainer.read(tail, 0, tail.size) == tail.size) {
                    extractVapcPayloadFromTail(tail)?.let { payload ->
                        ALog.i(TAG, "vapc found from container tail")
                        return payload
                    }
                }
            }
        }

        val path = fileContainer.filePath ?: return null
        val file = File(path)
        if (!file.isFile || !file.canRead()) return null

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val tailSize = minOf(TAIL_SEARCH_SIZE, raf.length()).toInt()
                if (tailSize < 12) return null

                val tail = ByteArray(tailSize)
                raf.seek(raf.length() - tailSize)
                raf.readFully(tail)
                extractVapcPayloadFromTail(tail)?.also {
                    ALog.i(TAG, "vapc found from file tail")
                }
            }
        } catch (t: Throwable) {
            ALog.e(TAG, "findVapcPayloadInTail $t", t)
            null
        }
    }

    private fun extractVapcPayloadFromTail(tail: ByteArray): ByteArray? {
        val vapcIndex = tail.lastIndexOfAscii("vapc")
        if (vapcIndex < 4) return null

        val lengthIndex = vapcIndex - 4
        val boxLength = readUInt32BE(tail, lengthIndex).toLong()
        if (boxLength <= 8 || boxLength > MAX_VAPC_PAYLOAD_SIZE + 8) {
            ALog.e(TAG, "invalid tail vapc length=$boxLength")
            return null
        }
        if (lengthIndex + boxLength > tail.size) {
            ALog.e(TAG, "tail vapc box exceeds search window, length=$boxLength")
            return null
        }

        return tail.copyOfRange(vapcIndex + 4, lengthIndex + boxLength.toInt())
    }

    private fun ByteArray.lastIndexOfAscii(text: String): Int {
        val target = text.toByteArray(Charset.forName("US-ASCII"))
        for (i in size - target.size downTo 0) {
            var matched = true
            for (j in target.indices) {
                if (this[i + j] != target[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return -1
    }

}