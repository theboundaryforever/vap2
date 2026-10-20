package com.tencent.qgame.animplayer

import android.annotation.TargetApi
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.tencent.qgame.animplayer.util.ALog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 硬解循环用到的几个 MediaCodec 操作。同步模式和 block 模式共用一套解码循环。
 */
internal interface CodecIo {
    val codec: MediaCodec

    fun start()

    /** 没有空闲输入槽时返回负数。 */
    fun dequeueInput(timeoutUs: Long): Int

    /**
     * 把 extractor 当前样本提交到 [index]。
     * 返回样本字节数；样本读完时提交 EOS 并返回 -1；槽位不可用时返回 0。
     */
    fun queueSample(index: Int, extractor: MediaExtractor): Int

    fun dequeueOutput(info: MediaCodec.BufferInfo, timeoutUs: Long): Int

    fun releaseOutput(index: Int, render: Boolean)

    fun flush()

    fun stop()

    fun release()
}

internal class SyncCodecIo(override val codec: MediaCodec) : CodecIo {

    override fun start() = codec.start()

    override fun dequeueInput(timeoutUs: Long): Int = codec.dequeueInputBuffer(timeoutUs)

    override fun queueSample(index: Int, extractor: MediaExtractor): Int {
        val buffer = codec.getInputBuffer(index)
        if (buffer == null) {
            codec.queueInputBuffer(index, 0, 0, 0L, 0)
            return 0
        }
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return -1
        }
        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
        return size
    }

    override fun dequeueOutput(info: MediaCodec.BufferInfo, timeoutUs: Long): Int =
        codec.dequeueOutputBuffer(info, timeoutUs)

    override fun releaseOutput(index: Int, render: Boolean) = codec.releaseOutputBuffer(index, render)

    override fun flush() = codec.flush()

    override fun stop() = codec.stop()

    override fun release() = codec.release()
}

/**
 * 同步模式下 Codec2 在 start 时按输入槽数一次性分配输入缓冲。MTK 每块至少 2MB，
 * 32 个槽位就是 60MB 以上。block 模式由这里按样本大小分配，解码器用完一块就回收一块，
 * 同时只留解码器手上那几块。
 */
@TargetApi(Build.VERSION_CODES.R)
internal class BlockCodecIo private constructor(
    override val codec: MediaCodec,
    private val callbackThread: HandlerThread,
) : CodecIo {

    companion object {
        private const val TAG = "${Constant.TAG}.BlockCodecIo"
        private const val MIN_BLOCK_SIZE = 64 * 1024
        private const val BLOCK_ALIGN = 4096

        /**
         * 解码器能直接读 app 分配的 LinearBlock 时才走 block 模式，否则每帧还要多拷一次。
         */
        fun isSupported(codec: MediaCodec): Boolean {
            return try {
                MediaCodec.LinearBlock.isCodecCopyFreeCompatible(arrayOf(codec.name))
            } catch (t: Throwable) {
                ALog.e(TAG, "check block model failed", t)
                false
            }
        }

        /** configure 失败会抛异常，此时 codec 已不可用，调用方要释放后重开。 */
        fun configure(codec: MediaCodec, format: MediaFormat, surface: Surface): BlockCodecIo {
            val thread = HandlerThread("anim_codec_callback").apply { start() }
            val io = BlockCodecIo(codec, thread)
            try {
                codec.setCallback(io.callback, Handler(thread.looper))
                codec.configure(format, surface, null, MediaCodec.CONFIGURE_FLAG_USE_BLOCK_MODEL)
            } catch (t: Throwable) {
                thread.quitSafely()
                throw t
            }
            return io
        }
    }

    private class Output(val index: Int, val offset: Int, val size: Int, val timeUs: Long, val flags: Int)

    private val formatChanged = Output(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED, 0, 0, 0L, 0)
    private val inputs = LinkedBlockingQueue<Int>()
    private val outputs = LinkedBlockingQueue<Output>()
    private val codecNames = arrayOf(codec.name)
    private val handler = Handler(callbackThread.looper)

    @Volatile
    private var error: MediaCodec.CodecException? = null

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            inputs.offer(index)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            outputs.offer(Output(index, info.offset, info.size, info.presentationTimeUs, info.flags))
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            ALog.e(TAG, "codec error", e)
            error = e
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            outputs.offer(formatChanged)
        }
    }

    override fun start() = codec.start()

    override fun dequeueInput(timeoutUs: Long): Int {
        throwIfFailed()
        return inputs.poll(timeoutUs, TimeUnit.MICROSECONDS) ?: -1
    }

    override fun queueSample(index: Int, extractor: MediaExtractor): Int {
        // flush 之前回调进来的槽位已经作废。先取请求，不能先把样本从 extractor 里读掉。
        val request = try {
            codec.getQueueRequest(index)
        } catch (e: IllegalArgumentException) {
            return 0
        } catch (e: IndexOutOfBoundsException) {
            return 0
        }
        val need = extractor.sampleSize
        // MTK 只认偏移为 0 的输入，多个样本拼进同一块时第二帧起就不再出画面，所以每个样本单独一块。
        val block = obtainBlock(maxOf(need, 0L).toInt())
        try {
            val size = if (need < 0) -1 else extractor.readSampleData(block.map(), 0)
            if (size < 0) {
                request.setLinearBlock(block, 0, 0)
                    .setPresentationTimeUs(0L)
                    .setFlags(MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    .queue()
                return -1
            }
            request.setLinearBlock(block, 0, size)
                .setPresentationTimeUs(extractor.sampleTime)
                .setFlags(0)
                .queue()
            return size
        } finally {
            // 只放掉 app 这边的引用。解码器手上的那份要等它读完才真正回收。
            recycle(block)
        }
    }

    override fun dequeueOutput(info: MediaCodec.BufferInfo, timeoutUs: Long): Int {
        throwIfFailed()
        val output = outputs.poll(timeoutUs, TimeUnit.MICROSECONDS) ?: return MediaCodec.INFO_TRY_AGAIN_LATER
        if (output !== formatChanged) {
            info.set(output.offset, output.size, output.timeUs, output.flags)
        }
        return output.index
    }

    override fun releaseOutput(index: Int, render: Boolean) {
        try {
            codec.releaseOutputBuffer(index, render)
        } catch (e: IllegalStateException) {
            ALog.e(TAG, "release stale output $index", e)
        }
    }

    /**
     * 异步模式 flush 之后要重新 start。放到回调线程上做，flush 前已经排队的回调先执行完再清掉。
     */
    override fun flush() {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                codec.flush()
                inputs.clear()
                outputs.clear()
                codec.start()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        failure?.let { throw it }
    }

    override fun stop() {
        codec.stop()
        inputs.clear()
        outputs.clear()
    }

    override fun release() {
        try {
            codec.release()
        } finally {
            callbackThread.quitSafely()
        }
    }

    private fun obtainBlock(size: Int): MediaCodec.LinearBlock {
        val capacity = (maxOf(size, MIN_BLOCK_SIZE) + BLOCK_ALIGN - 1) / BLOCK_ALIGN * BLOCK_ALIGN
        return MediaCodec.LinearBlock.obtain(capacity, codecNames)
            ?: throw IllegalStateException("obtain LinearBlock $capacity failed")
    }

    private fun recycle(block: MediaCodec.LinearBlock) {
        try {
            block.recycle()
        } catch (t: Throwable) {
            ALog.e(TAG, "recycle block failed", t)
        }
    }

    private fun throwIfFailed() {
        error?.let { throw it }
    }
}
