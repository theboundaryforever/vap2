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

import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.media.MediaExtractor
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.util.ALog
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class AssetsFileContainer(assetManager: AssetManager, val assetsPath: String) : IFileContainer {
    override val filePath: String? get() = assetsPath
    companion object {
        private const val TAG = "${Constant.TAG}.FileContainer"
    }

    private val assetFd: AssetFileDescriptor = assetManager.openFd(assetsPath)
    private val assetsInputStream: AssetManager.AssetInputStream =
        assetManager.open(assetsPath, AssetManager.ACCESS_STREAMING) as AssetManager.AssetInputStream
    private var randomAccessFile: FileChannel? = null
    private var randomAccessStream: FileInputStream? = null
    private val randomReadStart = assetFd.startOffset
    private val randomReadLength = if (assetFd.declaredLength >= 0) assetFd.declaredLength else -1L

    init {
        ALog.i(TAG, "AssetsFileContainer init")
    }

    override fun setDataSource(extractor: MediaExtractor) {
        if (assetFd.declaredLength < 0) {
            extractor.setDataSource(assetFd.fileDescriptor)
        } else {
            extractor.setDataSource(assetFd.fileDescriptor, assetFd.startOffset, assetFd.declaredLength)
        }
    }

    override fun fileLength(): Long = randomReadLength

    override fun startRandomRead() {
        closeRandomRead()
        val inputStream = FileInputStream(assetFd.fileDescriptor)
        randomAccessStream = inputStream
        randomAccessFile = inputStream.channel.apply {
            position(randomReadStart)
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val channel = randomAccessFile ?: return assetsInputStream.read(b, off, len)
        val remain = if (randomReadLength >= 0) {
            randomReadLength - (channel.position() - randomReadStart)
        } else {
            len.toLong()
        }
        if (remain <= 0) return -1
        val toRead = minOf(len.toLong(), remain).toInt()
        val buffer = ByteBuffer.wrap(b, off, toRead)
        val read = channel.read(buffer)
        return read
    }

    override fun skip(pos: Long) {
        randomAccessFile?.position(randomAccessFile!!.position() + pos)
            ?: assetsInputStream.skip(pos)
    }

    override fun seekAbsolute(position: Long): Boolean {
        val channel = randomAccessFile ?: return false
        if (position < 0) return false
        if (randomReadLength >= 0 && position > randomReadLength) return false
        channel.position(randomReadStart + position)
        return true
    }

    override fun closeRandomRead() {
        randomAccessFile?.close()
        randomAccessFile = null
        randomAccessStream = null
    }

    override fun close() {
        closeRandomRead()
        assetFd.close()
        assetsInputStream.close()
    }
}
