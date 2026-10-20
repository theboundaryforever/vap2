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
package com.tencent.qgame.animplayer.util

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.file.IFileContainer
import kotlin.collections.HashMap


object MediaUtil {

    private const val TAG = "${Constant.TAG}.MediaUtil"

    private var isTypeMapInit = false
    private val supportTypeMap = HashMap<String, Boolean>()

    // mime+尺寸 -> 硬解是否支持（只缓存成功查询的结果）
    private val sizeSupportCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // 解码器列表在进程内不变，只构造一次
    private val regularCodecInfos: Array<android.media.MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    }

    const val MIME_HEVC = "video/hevc"
    const val MIME_AVC = "video/avc"

    fun getExtractor(file: IFileContainer): MediaExtractor {
        val extractor = MediaExtractor()
        file.setDataSource(extractor)
        return extractor
    }

    /**
     * 是否为h265的视频
     */
    fun checkIsHevc(videoFormat: MediaFormat):Boolean {
        val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: ""
        return mime.contains("hevc")
    }

    fun selectVideoTrack(extractor: MediaExtractor): Int {
        val numTracks = extractor.trackCount
        for (i in 0 until numTracks) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                ALog.i(TAG, "Extractor selected track $i ($mime): $format")
                return i
            }
        }
        return -1
    }

    fun selectAudioTrack(extractor: MediaExtractor): Int {
        val numTracks = extractor.trackCount
        for (i in 0 until numTracks) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                ALog.i(TAG, "Extractor selected track $i ($mime): $format")
                return i
            }
        }
        return -1
    }

    fun getVideoFormat(file: IFileContainer): MediaFormat? {
        return try {
            val extractor = getExtractor(file)
            try {
                val trackIndex = selectVideoTrack(extractor)
                if (trackIndex >= 0) extractor.getTrackFormat(trackIndex) else null
            } finally {
                extractor.release()
            }
        } catch (t: Throwable) {
            ALog.e(TAG, "getVideoFormat $t", t)
            null
        }
    }

    fun checkSupportVideoSize(mimeType: String, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return checkSupportCodec(mimeType)
        }

        // 设备解码能力在进程内不会变化，同 mime+尺寸的结果直接复用，
        // 避免每次开播都遍历整个 MediaCodecList（部分机型耗时几十毫秒）
        val cacheKey = "${mimeType.toLowerCase()}_${width}x$height"
        sizeSupportCache[cacheKey]?.let { return it }

        return try {
            regularCodecInfos.any { codecInfo ->
                !codecInfo.isEncoder && codecInfo.supportedTypes.any { type ->
                    type.equals(mimeType, ignoreCase = true) && runCatching {
                        val caps = codecInfo.getCapabilitiesForType(type)
                        val videoCaps = caps.videoCapabilities
                        videoCaps.isSizeSupported(width, height)
                    }.getOrDefault(false)
                }
            }.also {
                sizeSupportCache[cacheKey] = it
                ALog.i(TAG, "checkSupportVideoSize mime=$mimeType size=${width}x$height support=$it")
            }
        } catch (t: Throwable) {
            ALog.e(TAG, "checkSupportVideoSize $t", t)
            checkSupportCodec(mimeType)
        }
    }

    /**
     * 找一个支持该分辨率的硬件解码器。软件解码器（c2.android / OMX.google）会在内存里留整帧 YUV，大素材很容易到几百 MB。
     */
    fun findHardwareDecoderName(mimeType: String, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        return try {
            regularCodecInfos.firstOrNull { info ->
                !info.isEncoder && !isSoftwareCodec(info) && info.supportedTypes.any { type ->
                    type.equals(mimeType, ignoreCase = true) && runCatching {
                        info.getCapabilitiesForType(type).videoCapabilities?.isSizeSupported(width, height) == true
                    }.getOrDefault(false)
                }
            }?.name
        } catch (t: Throwable) {
            ALog.e(TAG, "findHardwareDecoderName $t", t)
            null
        }
    }

    private fun isSoftwareCodec(info: android.media.MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isSoftwareOnly
        }
        val name = info.name.lowercase()
        return name.startsWith("omx.google.") || name.startsWith("c2.android.")
    }

    /**
     * 检查设备解码支持类型
     */
    @Synchronized
    fun checkSupportCodec(mimeType: String): Boolean {
        if (!isTypeMapInit) {
            isTypeMapInit = true
            getSupportType()
        }
        return supportTypeMap.containsKey(mimeType.toLowerCase())
    }


    private fun getSupportType() {
        try {
            val numCodecs = MediaCodecList.getCodecCount()
            for (i in 0 until numCodecs) {
                val codecInfo = MediaCodecList.getCodecInfoAt(i)
                if (codecInfo.isEncoder) {
                    continue
                }
                val types = codecInfo.supportedTypes
                for (j in types.indices) {
                    supportTypeMap[types[j].toLowerCase()] = true
                }
            }
            ALog.i(TAG, "supportType=${supportTypeMap.keys}")
        } catch (t: Throwable) {
            ALog.e(TAG, "getSupportType $t")
        }
    }

}