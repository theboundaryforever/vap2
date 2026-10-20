package com.tencent.qgame.animplayer.util

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 把送去显示的画面缩到屏幕上真正会显示的大小。不放大。
 * 宽高保持偶数，方便 YUV420。
 * H.264 参考帧仍按码流原尺寸分配，这里缩的是输出，不是那组参考帧。
 */
object PlaybackSize {

    fun scaledVideoSize(
        videoWidth: Int,
        videoHeight: Int,
        contentWidth: Int,
        contentHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        maxVideoPixels: Int = Int.MAX_VALUE
    ): Pair<Int, Int> {
        if (videoWidth <= 0 || videoHeight <= 0) return videoWidth to videoHeight
        val displayScale = displayScale(contentWidth, contentHeight, outputWidth, outputHeight)
        val pixelScale = pixelBudgetScale(videoWidth, videoHeight, maxVideoPixels)
        val scale = min(displayScale, pixelScale)
        if (scale >= 0.99f) return videoWidth to videoHeight
        var width = evenDown((videoWidth * scale).roundToInt())
        var height = evenDown((videoHeight * scale).roundToInt())
        // Rounding each side independently can exceed a strict pixel budget by one
        // aligned row/column. Keep the advertised cap true for YUV allocations.
        while (maxVideoPixels != Int.MAX_VALUE &&
            width.toLong() * height > maxVideoPixels.toLong() &&
            (width > 2 || height > 2)
        ) {
            if (width >= height && width > 2) width -= 2 else if (height > 2) height -= 2
        }
        return width to height
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

    private fun pixelBudgetScale(videoWidth: Int, videoHeight: Int, maxVideoPixels: Int): Float {
        if (maxVideoPixels <= 0 || maxVideoPixels == Int.MAX_VALUE) return 1f
        val pixels = videoWidth.toLong() * videoHeight
        if (pixels <= maxVideoPixels.toLong()) return 1f
        return sqrt(maxVideoPixels.toDouble() / pixels.toDouble()).toFloat()
            .coerceIn(0.05f, 1f)
    }

    private fun evenDown(value: Int): Int {
        val safe = value.coerceAtLeast(2)
        return safe - (safe and 1)
    }
}
