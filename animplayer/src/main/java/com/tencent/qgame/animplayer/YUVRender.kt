package com.tencent.qgame.animplayer

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.GlFloatArray
import com.tencent.qgame.animplayer.util.ShaderUtil
import com.tencent.qgame.animplayer.util.TexCoordsUtil
import com.tencent.qgame.animplayer.util.VertexUtil
import java.nio.ByteBuffer

class YUVRender(
    private val surfaceTexture: SurfaceTexture,
    private val isSoftRender: Boolean = true
) : IRenderListener {

    companion object {
        private const val TAG = "${Constant.TAG}.YUVRender"
    }

    private val eglUtil = EGLUtil()

    private var eglReady = false
    private var destroyed = false

    private val vertexArray = GlFloatArray()
    private val alphaArray = GlFloatArray()
    private val rgbArray = GlFloatArray()

    private var shaderProgram = 0

    private var avPosition = 0
    private var rgbPosition = 0
    private var alphaPosition = 0

    private var samplerY = 0
    private var samplerU = 0
    private var samplerV = 0

    private var convertMatrixUniform = 0
    private var convertOffsetUniform = 0

    private val textureId = IntArray(3)
    private val textureAllocated = BooleanArray(3)

    private var widthYUV = 0
    private var heightYUV = 0

    @Volatile
    private var y: ByteBuffer? = null

    @Volatile
    private var u: ByteBuffer? = null

    @Volatile
    private var v: ByteBuffer? = null
    
    // 数据同步锁，防止Y/U/V数据竞态条件
    private val dataLock = Object()

    private val YUV_OFFSET = floatArrayOf(
        0f,
        -0.501960814f,
        -0.501960814f
    )

    private val YUV_MATRIX = floatArrayOf(
        1f, 1f, 1f,
        0f, -0.3441f, 1.772f,
        1.402f, -0.7141f, 0f
    )

    /**
     * 必须在 RenderThread 调用
     */
    // 在 YUVRender.kt 中
    fun initGL() {
        // 必须确保先释放旧的 EGL 环境
        if (eglReady) destroyRender()

        ALog.i(TAG, "initGL starting...")
        eglUtil.start(surfaceTexture)
        if (eglUtil.makeCurrent()) {
            initRender()
            eglReady = true
            ALog.i(TAG, "initGL completed successfully")
        } else {
            ALog.e(TAG, "initGL failed: makeCurrent failed")
        }
    }

    override fun initRender() {
        shaderProgram = ShaderUtil.createProgram(
            YUVShader.VERTEX_SHADER,
            YUVShader.FRAGMENT_SHADER
        )

        if (shaderProgram == 0) {
            ALog.e(TAG, "createProgram failed")
            return
        }

        avPosition =
            GLES20.glGetAttribLocation(shaderProgram, "v_Position")

        rgbPosition =
            GLES20.glGetAttribLocation(shaderProgram, "vTexCoordinateRgb")

        alphaPosition =
            GLES20.glGetAttribLocation(shaderProgram, "vTexCoordinateAlpha")

        samplerY =
            GLES20.glGetUniformLocation(shaderProgram, "sampler_y")

        samplerU =
            GLES20.glGetUniformLocation(shaderProgram, "sampler_u")

        samplerV =
            GLES20.glGetUniformLocation(shaderProgram, "sampler_v")

        convertMatrixUniform =
            GLES20.glGetUniformLocation(shaderProgram, "convertMatrix")

        convertOffsetUniform =
            GLES20.glGetUniformLocation(shaderProgram, "offset")

        GLES20.glGenTextures(
            3,
            textureId,
            0
        )

        textureId.forEach { id ->
            GLES20.glBindTexture(
                GLES20.GL_TEXTURE_2D,
                id
            )

            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )

            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )

            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )

            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
        }

        ALog.i(
            TAG,
            "GL initialized texture=${textureId.joinToString()}"
        )
    }

    override fun renderFrame() {
        if (!isSoftRender) return
        if (destroyed) return
        
        if (!eglReady) {
            ALog.d(TAG, "renderFrame: EGL not ready yet")
            return
        }

        if (!eglUtil.makeCurrent()) {
            ALog.e(TAG, "renderFrame makeCurrent failed thread=${Thread.currentThread().name}")
            return
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        draw()

        swapBuffers()
    }

    private fun draw() {
        // 在synchronized块中快速复制数据引用，避免长时间持有锁
        val (yData, uData, vData) = synchronized(dataLock) {
            Triple(y, u, v)
        }
        
        if (yData == null || uData == null || vData == null) {
            return
        }

        GLES20.glUseProgram(shaderProgram)

        GLES20.glPixelStorei(
            GLES20.GL_UNPACK_ALIGNMENT,
            1
        )

        uploadTexture(
            0,
            GLES20.GL_TEXTURE0,
            textureId[0],
            samplerY,
            widthYUV,
            heightYUV,
            yData
        )

        uploadTexture(
            1,
            GLES20.GL_TEXTURE1,
            textureId[1],
            samplerU,
            widthYUV / 2,
            heightYUV / 2,
            uData
        )

        uploadTexture(
            2,
            GLES20.GL_TEXTURE2,
            textureId[2],
            samplerV,
            widthYUV / 2,
            heightYUV / 2,
            vData
        )

        GLES20.glUniform3fv(
            convertOffsetUniform,
            1,
            YUV_OFFSET,
            0
        )

        GLES20.glUniformMatrix3fv(
            convertMatrixUniform,
            1,
            false,
            YUV_MATRIX,
            0
        )

        GLES20.glEnableVertexAttribArray(avPosition)
        vertexArray.setVertexAttribPointer(avPosition)

        GLES20.glEnableVertexAttribArray(alphaPosition)
        alphaArray.setVertexAttribPointer(alphaPosition)

        if (rgbPosition >= 0) {
            GLES20.glEnableVertexAttribArray(rgbPosition)
            rgbArray.setVertexAttribPointer(rgbPosition)
        }

        GLES20.glDrawArrays(
            GLES20.GL_TRIANGLE_STRIP,
            0,
            4
        )

        // 清除数据
        synchronized(dataLock) {
            y = null
            u = null
            v = null
        }
    }

    private fun uploadTexture(
        index: Int,
        unit: Int,
        texture: Int,
        sampler: Int,
        width: Int,
        height: Int,
        buffer: ByteBuffer
    ) {
        GLES20.glActiveTexture(unit)

        GLES20.glBindTexture(
            GLES20.GL_TEXTURE_2D,
            texture
        )

        buffer.position(0)

        if (textureAllocated[index]) {
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                width,
                height,
                GLES20.GL_LUMINANCE,
                GLES20.GL_UNSIGNED_BYTE,
                buffer
            )
        } else {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_LUMINANCE,
                width,
                height,
                0,
                GLES20.GL_LUMINANCE,
                GLES20.GL_UNSIGNED_BYTE,
                buffer
            )
            textureAllocated[index] = true
        }

        GLES20.glUniform1i(
            sampler,
            unit - GLES20.GL_TEXTURE0
        )
    }

    override fun setYUVData(
        width: Int,
        height: Int,
        y: ByteArray?,
        u: ByteArray?,
        v: ByteArray?
    ) {
        if (destroyed) return

        synchronized(dataLock) {
            if (widthYUV != width || heightYUV != height) {
                textureAllocated.fill(false)
            }
            widthYUV = width
            heightYUV = height

            this.y = y?.let { ByteBuffer.wrap(it) }
            this.u = u?.let { ByteBuffer.wrap(it) }
            this.v = v?.let { ByteBuffer.wrap(it) }
        }
    }

    override fun setYUVData(
        width: Int,
        height: Int,
        y: ByteBuffer?,
        u: ByteBuffer?,
        v: ByteBuffer?
    ) {
        if (destroyed) return

        synchronized(dataLock) {
            if (widthYUV != width || heightYUV != height) {
                textureAllocated.fill(false)
            }
            widthYUV = width
            heightYUV = height

            this.y = y
            this.u = u
            this.v = v
        }
    }

    override fun setAnimConfig(config: AnimConfig) {

        vertexArray.setArray(
            VertexUtil.create(
                config.width,
                config.height,
                PointRect(
                    0,
                    0,
                    config.width,
                    config.height
                ),
                vertexArray.array
            )
        )

        alphaArray.setArray(
            TexCoordsUtil.create(
                config.videoWidth,
                config.videoHeight,
                config.alphaPointRect,
                alphaArray.array
            )
        )

        rgbArray.setArray(
            TexCoordsUtil.create(
                config.videoWidth,
                config.videoHeight,
                config.rgbPointRect,
                rgbArray.array
            )
        )
    }

    override fun updateViewPort(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || destroyed || !eglReady) return
        if (!eglUtil.makeCurrent()) return
        GLES20.glViewport(0, 0, width, height)
        ALog.i(TAG, "updateViewPort: width=$width, height=$height")
    }

    override fun destroyRender() {

        if (destroyed) return

        destroyed = true

        if (eglReady) {
            try {
                eglUtil.makeCurrent()
            } catch (e: Exception) {
                ALog.e(TAG, "destroyRender: makeCurrent error: $e", e)
            }

            try {
                releaseTexture()
            } catch (e: Exception) {
                ALog.e(TAG, "destroyRender: releaseTexture error: $e", e)
            }

            try {
                if (shaderProgram != 0) {
                    GLES20.glDeleteProgram(shaderProgram)
                    shaderProgram = 0
                }
            } catch (e: Exception) {
                ALog.e(TAG, "destroyRender: glDeleteProgram error: $e", e)
            }

            try {
                eglUtil.release()
            } catch (e: Exception) {
                ALog.e(TAG, "destroyRender: eglUtil.release error: $e", e)
            }

            eglReady = false
        }
        
        ALog.i(TAG, "destroyRender: completed")
    }

    override fun releaseTexture() {
        GLES20.glDeleteTextures(
            3,
            textureId,
            0
        )
        textureAllocated.fill(false)
    }

    override fun swapBuffers() {
        if (destroyed || !eglReady) return
        try {
            eglUtil.swapBuffers()
        } catch (e: Exception) {
            ALog.e(TAG, "swapBuffers exception: $e", e)
        }
    }

    override fun clearFrame() {
        if (destroyed || !eglReady) return
        try {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            swapBuffers()
        } catch (e: Exception) {
            ALog.e(TAG, "clearFrame exception: $e", e)
        }
    }

    override fun getExternalTexture(): Int {
        return textureId[0]
    }

    fun isEglReady(): Boolean {
        return eglReady
    }

    /**
     * 绑定 EGL 上下文，供 renderThread 上的插件初始化使用
     */
    fun bindGlContext(): Boolean {
        if (!eglReady) return false
        return eglUtil.makeCurrent()
    }
}