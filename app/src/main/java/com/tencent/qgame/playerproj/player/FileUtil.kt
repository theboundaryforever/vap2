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
package com.tencent.qgame.playerproj.player

import android.content.Context
import java.io.*
import java.security.MessageDigest

object FileUtil {

    private val hexDigits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')

    fun copyAssetsToStorage(context: Context, dir: String, files: Array<String>, loadSuccess:()->Unit) {
        Thread {
            val buf = ByteArray(4096)
            val targetDir = File(dir)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            files.forEach {
                try {
                    val target = File(targetDir, it)
                    context.assets.open(it).use { inputStream ->
                        FileOutputStream(target, false).use { outputStream ->
                            var length = inputStream.read(buf)
                            while (length > 0) {
                                outputStream.write(buf, 0, length)
                                length = inputStream.read(buf)
                            }
                            outputStream.flush()
                        }
                    }
                    android.util.Log.i("FileUtil", "copy asset $it to ${target.absolutePath}, size=${target.length()}")
                } catch (e: IOException) {
                    android.util.Log.e("FileUtil", "copy asset failed: $it", e)
                    e.printStackTrace()
                    return@Thread
                }
            }
            loadSuccess.invoke()
        }.start()

    }

    fun getFileMD5(file: File): String? {
        if (!file.exists() || !file.isFile || file.length() <= 0) {
            return null
        }
        var inputStream: InputStream? = null
        try {
            val md = MessageDigest.getInstance("MD5")
            inputStream = FileInputStream(file)
            val dataBytes = ByteArray(4096)
            var iRd: Int
            iRd = inputStream.read(dataBytes)
            while (iRd != -1) {
                md.update(dataBytes, 0, iRd)
                iRd = inputStream.read(dataBytes)
            }
            inputStream.close()
            val digest = md.digest()
            if (digest != null) {
                val md5Str = bufferToHex(digest)
                android.util.Log.d("FileUtil", "文件 [${file.name}] 的完整 MD5 是: $md5Str")
                return bufferToHex(digest)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close()
                } catch (e: Throwable) {
                    e.printStackTrace()
                }

            }
        }
        return null
    }

    private fun bufferToHex(bytes: ByteArray): String {
        return bufferToHex(bytes, 0, bytes.size)
    }

    private fun bufferToHex(bytes: ByteArray, m: Int, n: Int): String {
        val sb = StringBuffer(2 * n)
        val k = m + n
        for (l in m until k) {
            appendHexPair(bytes[l], sb)
        }
        return sb.toString()
    }

    private fun appendHexPair(bt: Byte, sb: StringBuffer) {
        val c0 = hexDigits[bt.toInt() and 0xf0 ushr 4]
        val c1 = hexDigits[bt.toInt() and 0x0f]
        sb.append(c0)
        sb.append(c1)
    }
}