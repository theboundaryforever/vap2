package com.tencent.qgame.animplayer.util

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 把素材像素缩到屏幕上真正会显示的大小。不放大。
 * 宽高保持偶数，方便 YUV420。
 */
object PlaybackSize {

    fun scaledVideoSize(
        videoWidth: Int,
        videoHeight: Int,
        contentWidth: Int,
        contentHeight: Int,
        outputWidth: Int,
        outputHeight: Int
    ): Pair<Int, Int> {
        if (videoWidth <= 0 || videoHeight <= 0) return videoWidth to videoHeight
        val scale = displayScale(contentWidth, contentHeight, outputWidth, outputHeight)
        if (scale >= 0.99f) return videoWidth to videoHeight
        return evenDown((videoWidth * scale).roundToInt()) to evenDown((videoHeight * scale).roundToInt())
    }

    fun displayScale(
        contentWidth: Int,
        contentHeight: Int,
        outputWidth: Int,
        outputHeight: Int
    ): Float {
        if (contentWidth <= 0 || contentHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) {
            return 1f
        }
        return min(outputWidth.toFloat() / contentWidth, outputHeight.toFloat() / contentHeight)
            .coerceIn(0.05f, 1f)
    }

    private fun evenDown(value: Int): Int {
        val safe = value.coerceAtLeast(2)
        return safe - (safe and 1)
    }
}
