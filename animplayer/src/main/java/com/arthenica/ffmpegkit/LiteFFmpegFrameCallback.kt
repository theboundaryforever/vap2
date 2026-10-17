package com.arthenica.ffmpegkit

import java.nio.ByteBuffer

interface LiteFFmpegFrameCallback {
    /**
     * 借出一块容量不小于 [frameSize] 的直接缓冲。native 会从地址 0 写入一帧。
     * 返回 null 表示停止解码。
     */
    fun acquireFrame(frameSize: Int): ByteBuffer?

    /**
     * [buffer] 的前 [frameSize] 字节已写好。返回 false 表示停止解码。
     * 调用方拿到缓冲后负责归还，native 不再使用它。
     */
    fun onFrame(buffer: ByteBuffer, frameSize: Int): Boolean

    /** native 写入失败时归还 [acquireFrame] 借出的缓冲。 */
    fun releaseFrame(buffer: ByteBuffer)
}
