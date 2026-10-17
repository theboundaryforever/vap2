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
import android.media.MediaExtractor
import android.os.Build
import java.io.ByteArrayInputStream

@TargetApi(Build.VERSION_CODES.M)
class StreamContainer(private val bytes: ByteArray) : IFileContainer {
    override val filePath: String? get() = null // 流式数据没有固定路径
    private var stream: ByteArrayInputStream = ByteArrayInputStream(bytes)
    private var randomReadPos: Long = 0
    private var randomReadEnabled = false

    override fun setDataSource(extractor: MediaExtractor) {
        val dataSource = StreamMediaDataSource(bytes)
        extractor.setDataSource(dataSource)
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
