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

import android.media.MediaExtractor
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.util.ALog
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile

class FileContainer(private val file: File) : IFileContainer {
    override val filePath: String? get() = file.absolutePath
    companion object {
        private const val TAG = "${Constant.TAG}.FileContainer"
    }

    private var randomAccessFile: RandomAccessFile? = null

    init {
        ALog.i(TAG, "FileContainer init")
        if (!(file.exists() && file.isFile && file.canRead())) throw FileNotFoundException("Unable to read $file")
    }

    override fun setDataSource(extractor: MediaExtractor) {
        extractor.setDataSource(file.toString())
    }

    override fun fileLength(): Long = file.length()

    override fun startRandomRead() {
        randomAccessFile = RandomAccessFile(file, "r")
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        return randomAccessFile?.read(b, off, len) ?: -1
    }

    override fun skip(pos: Long) {
        randomAccessFile?.apply {
            seek(filePointer + pos)
        }
    }

    override fun seekAbsolute(position: Long): Boolean {
        val raf = randomAccessFile ?: return false
        if (position < 0 || position > raf.length()) return false
        raf.seek(position)
        return true
    }

    override fun closeRandomRead() {
        randomAccessFile?.close()
        randomAccessFile = null
    }

    override fun close() {
        closeRandomRead()
    }
}
