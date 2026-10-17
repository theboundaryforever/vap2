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
package com.tencent.qgame.animplayer

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.view.Surface
import com.tencent.qgame.animplayer.util.ALog
import javax.microedition.khronos.egl.*

class EGLUtil {

    companion object {
        private const val TAG = "${Constant.TAG}.EGLUtil"
    }

    private var egl: EGL10? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglSurface: EGLSurface? = null
    private var eglContext: EGLContext? = null
    private var eglConfig: EGLConfig? = null
    private var surface: Surface? = null
    
    // 标记EGL资源是否已释放
    private var isReleased = false

    init {
        eglDisplay = EGL10.EGL_NO_DISPLAY
        eglSurface = EGL10.EGL_NO_SURFACE
        eglContext = EGL10.EGL_NO_CONTEXT
        isReleased = false
    }

    fun start(surfaceTexture: SurfaceTexture) {
        try {
            egl = EGLContext.getEGL() as EGL10
            eglDisplay = egl?.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            egl?.eglInitialize(eglDisplay, version)
            eglConfig = chooseConfig()
            surface = Surface(surfaceTexture)
            eglSurface = egl?.eglCreateWindowSurface(eglDisplay, eglConfig, surface, null)
            eglContext = createContext(egl, eglDisplay, eglConfig)
            if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
                ALog.e(TAG, "error:${Integer.toHexString(egl?.eglGetError() ?: 0)}")
                return
            }

            if (egl?.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext) == false) {
                ALog.e(TAG, "make current error:${Integer.toHexString(egl?.eglGetError() ?: 0)}")
            }
        } catch (e: Throwable) {
            ALog.e(TAG, "error:$e", e)
        }
    }


    private fun chooseConfig(): EGLConfig? {
        val configsCount = IntArray(1)
        val configs = arrayOfNulls<EGLConfig>(1)
        val attributes =getAttributes()
        val confSize = 1
        if (egl?.eglChooseConfig(eglDisplay, attributes, configs, confSize, configsCount) == true) {
            return configs[0]
        }
        return null
    }

    private fun getAttributes(): IntArray {
        return intArrayOf(
            EGL10.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,  //指定渲染api类别
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 0,
            EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_NONE
        )
    }

    private fun createContext(egl: EGL10?, eglDisplay: EGLDisplay?, eglConfig: EGLConfig?): EGLContext? {
        val attrs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL10.EGL_NONE
        )
        return egl?.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attrs)
    }

    // 在 EGLUtil.kt 中
    fun swapBuffers(): Boolean {
        if (isReleased) {
            ALog.d(TAG, "swapBuffers: EGL already released")
            return false
        }
        if (eglDisplay == null || eglDisplay == EGL10.EGL_NO_DISPLAY || 
            eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
            ALog.d(TAG, "swapBuffers: EGL resources not valid")
            return false
        }
        try {
            return egl?.eglSwapBuffers(eglDisplay, eglSurface) ?: false
        } catch (e: Exception) {
            ALog.e(TAG, "swapBuffers exception: $e")
            return false
        }
    }

    fun release() {
        if (isReleased) {
            ALog.d(TAG, "release: already released")
            return
        }
        
        isReleased = true
        
        try {
            egl?.apply {
                try {
                    // 解除当前上下文
                    if (eglDisplay != null && eglDisplay != EGL10.EGL_NO_DISPLAY) {
                        eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                    }
                } catch (e: Exception) {
                    ALog.e(TAG, "release: eglMakeCurrent error: $e")
                }
                
                try {
                    // 销毁surface
                    if (eglDisplay != null && eglDisplay != EGL10.EGL_NO_DISPLAY &&
                        eglSurface != null && eglSurface != EGL10.EGL_NO_SURFACE) {
                        eglDestroySurface(eglDisplay, eglSurface)
                        eglSurface = EGL10.EGL_NO_SURFACE
                    }
                } catch (e: Exception) {
                    ALog.e(TAG, "release: eglDestroySurface error: $e")
                }
                
                try {
                    // 销毁context
                    if (eglDisplay != null && eglDisplay != EGL10.EGL_NO_DISPLAY &&
                        eglContext != null && eglContext != EGL10.EGL_NO_CONTEXT) {
                        eglDestroyContext(eglDisplay, eglContext)
                        eglContext = EGL10.EGL_NO_CONTEXT
                    }
                } catch (e: Exception) {
                    ALog.e(TAG, "release: eglDestroyContext error: $e")
                }
                
                try {
                    // 终止EGL
                    if (eglDisplay != null && eglDisplay != EGL10.EGL_NO_DISPLAY) {
                        eglTerminate(eglDisplay)
                        eglDisplay = EGL10.EGL_NO_DISPLAY
                    }
                } catch (e: Exception) {
                    ALog.e(TAG, "release: eglTerminate error: $e")
                }
            }
        } catch (e: Exception) {
            ALog.e(TAG, "release: outer exception: $e")
        }
        
        try {
            surface?.release()
            surface = null
        } catch (e: Exception) {
            ALog.e(TAG, "release: surface release error: $e")
        }
        
        ALog.i(TAG, "release: completed")
    }

    fun makeCurrent(): Boolean {
        if (isReleased) {
            ALog.d(TAG, "makeCurrent: EGL already released")
            return false
        }
        if (eglDisplay == null || eglDisplay == EGL10.EGL_NO_DISPLAY ||
            eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE || 
            eglContext == null || eglContext == EGL10.EGL_NO_CONTEXT) {
            ALog.e(TAG, "makeCurrent failed: EGL resources not initialized")
            return false
        }
        // 关键步骤：在当前线程绑定上下文
        try {
            if (egl?.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext) == false) {
                val error = egl?.eglGetError()
                ALog.e(TAG, "makeCurrent error: ${Integer.toHexString(error ?: 0)}")
                return false
            }
        } catch (e: Exception) {
            ALog.e(TAG, "makeCurrent exception: $e", e)
            return false
        }
        return true
    }

}