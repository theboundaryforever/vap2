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

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils

object TextureLoadUtil {
    private const val TAG = "TextureUtil"
    fun loadTexture(bitmap: Bitmap?): Int {
        val textureObjectIds = IntArray(1)
        GLES20.glGenTextures(1, textureObjectIds, 0)

        if (textureObjectIds[0] == 0) {
            return 0
        }

        if (bitmap == null) {
            GLES20.glDeleteTextures(1, textureObjectIds, 0)
            return 0
        }

        if (bitmap.isRecycled) {
            ALog.e(TAG, "bitmap isRecycled")
            return 0
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureObjectIds[0])
        // 融合贴图按目标区域拉伸绘制，不需要 mipmap；生成金字塔会多占约 1/3 显存。
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        return textureObjectIds[0]
    }
    
    
    fun releaseTexure(textureId: Int) {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }
}