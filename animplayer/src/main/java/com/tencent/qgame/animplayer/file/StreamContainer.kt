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
package com.tencent.qgame.animplayer.file

import android.annotation.TargetApi
import android.content.Context
import android.media.MediaExtractor
import android.os.Build
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.util.ALog
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference

/**
 * 字节流播放。优先落成临时文件再交给 [FileContainer]，播放期不再持有整段 ByteArray。
 * 落盘失败时退回内存读取。
 */
class StreamContainer : IFileContainer {

    private val impl: IFileContainer
    private val tempFile: File?

    constructor(bytes: ByteArray) : this(bytes, null)

    constructor(context: Context, bytes: ByteArray) : this(bytes, context.applicationContext.cacheDir)

    private constructor(bytes: ByteArray, cacheDir: File?) {
        val file = spill(bytes, cacheDir)
        val opened = if (file == null) {
            null
        } else {
            try {
                FileContainer(file)
            } catch (t: Throwable) {
                ALog.e(TAG, "open spilled file failed", t)
                file.delete()
                null
            }
        }
        if (file != null && opened != null) {
            impl = opened
            tempFile = file
            rememberTemp(this, file)
            ALog.i(TAG, "stream spilled to ${file.absolutePath}, bytes=${bytes.size}")
        } else {
            tempFile = null
            impl = MemoryStreamContainer(bytes)
            ALog.e(TAG, "stream spill failed, keep ${bytes.size} bytes in memory")
        }
    }

    override val filePath: String?
        get() = impl.filePath

    override fun setDataSource(extractor: MediaExtractor) {
        impl.setDataSource(extractor)
    }

    override fun fileLength(): Long = impl.fileLength()

    override fun startRandomRead() {
        impl.startRandomRead()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = impl.read(b, off, len)

    override fun skip(pos: Long) {
        impl.skip(pos)
    }

    override fun seekAbsolute(position: Long): Boolean = impl.seekAbsolute(position)

    override fun closeRandomRead() {
        impl.closeRandomRead()
    }

    override fun close() {
        // 只关句柄，不删文件。close 之后同一个容器还要能再次播放。
        impl.close()
    }

    companion object {
        private const val TAG = "${Constant.TAG}.StreamContainer"
        private val tempFiles = ArrayList<Pair<WeakReference<StreamContainer>, File>>()

        private fun rememberTemp(container: StreamContainer, file: File) {
            synchronized(tempFiles) {
                val iterator = tempFiles.iterator()
                while (iterator.hasNext()) {
                    val (ref, old) = iterator.next()
                    if (ref.get() == null) {
                        old.delete()
                        iterator.remove()
                    }
                }
                tempFiles.add(WeakReference(container) to file)
            }
        }

        private fun spill(bytes: ByteArray, cacheDir: File?): File? {
            val folders = listOfNotNull(cacheDir, applicationCacheDir(), tmpDir())
            for (folder in folders) {
                writeTemp(bytes, folder)?.let { return it }
            }
            return null
        }

        private fun writeTemp(bytes: ByteArray, folder: File): File? {
            if ((!folder.exists() && !folder.mkdirs()) || !folder.canWrite()) return null
            val file = try {
                File.createTempFile("vap_stream_", ".mp4", folder)
            } catch (t: Throwable) {
                ALog.e(TAG, "create temp file failed in ${folder.absolutePath}", t)
                return null
            }
            return try {
                FileOutputStream(file).use { it.write(bytes) }
                file.deleteOnExit()
                file
            } catch (t: Throwable) {
                ALog.e(TAG, "write temp file failed", t)
                file.delete()
                null
            }
        }

        private fun applicationCacheDir(): File? {
            return try {
                val thread = Class.forName("android.app.ActivityThread")
                val app = thread.getMethod("currentApplication").invoke(null) as? Context
                app?.cacheDir
            } catch (t: Throwable) {
                null
            }
        }

        private fun tmpDir(): File? {
            val path = System.getProperty("java.io.tmpdir") ?: return null
            return File(path)
        }
    }
}

@TargetApi(Build.VERSION_CODES.M)
private class MemoryStreamContainer(private val bytes: ByteArray) : IFileContainer {
    override val filePath: String? get() = null
    private var stream: ByteArrayInputStream = ByteArrayInputStream(bytes)
    private var randomReadPos: Long = 0
    private var randomReadEnabled = false

    override fun setDataSource(extractor: MediaExtractor) {
        extractor.setDataSource(StreamMediaDataSource(bytes))
    }

    override fun fileLength(): Long = bytes.size.toLong()

    override fun startRandomRead() {
        randomReadEnabled = true
        randomReadPos = 0
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (randomReadEnabled) {
            val remain = bytes.size - randomReadPos
            if (remain <= 0) return -1
            val toRead = minOf(len.toLong(), remain).toInt()
            System.arraycopy(bytes, randomReadPos.toInt(), b, off, toRead)
            randomReadPos += toRead
            return toRead
        }
        return stream.read(b, off, len)
    }

    override fun skip(pos: Long) {
        if (randomReadEnabled) {
            randomReadPos = minOf(randomReadPos + pos, bytes.size.toLong())
            return
        }
        stream.skip(pos)
    }

    override fun seekAbsolute(position: Long): Boolean {
        if (!randomReadEnabled || position < 0 || position > bytes.size) return false
        randomReadPos = position
        return true
    }

    override fun closeRandomRead() {
        randomReadEnabled = false
        randomReadPos = 0
    }

    override fun close() {
        closeRandomRead()
        stream.close()
    }
}
