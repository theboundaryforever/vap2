package com.tencent.qgame.animplayer

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.GlFloatArray
import com.tencent.qgame.animplayer.util.ShaderUtil
import com.tencent.qgame.animplayer.util.TexCoordsUtil
import com.tencent.qgame.animplayer.util.VertexUtil

class Render(surfaceTexture: SurfaceTexture?, private val isSoftRender: Boolean = false) : IRenderListener {

    companion object {
        private const val TAG = "${Constant.TAG}.Render"
    }

    private var shaderProgram = 0
    private val eglUtil: EGLUtil = EGLUtil()
    private var textureId = 0
    private val yuvTextures = IntArray(3)

    private val vertexArray = GlFloatArray()
    private val alphaArray = GlFloatArray()
    private val rgbArray = GlFloatArray()

    private var avPosition = 0
    private var rgbPosition = 0
    private var alphaPosition = 0
    private var textureUniform = 0

    private var currentConfig: AnimConfig? = null
    private var viewWidth = 0
    private var viewHeight = 0

    init {
        ALog.i(TAG, "[DEBUG] Render init. Mode: ${if (isSoftRender) "Soft" else "Hard"}")
        surfaceTexture?.let { eglUtil.start(it) }
        initRender()
    }

    override fun initRender() {
        val vs = if (isSoftRender) YUVShader.VERTEX_SHADER else RenderConstant.VERTEX_SHADER
        val fs = if (isSoftRender) YUVShader.FRAGMENT_SHADER else RenderConstant.FRAGMENT_SHADER
        shaderProgram = ShaderUtil.createProgram(vs, fs)
        ALog.d(TAG, "[DEBUG] Shader program created: $shaderProgram")

        if (isSoftRender) {
            GLES20.glGenTextures(3, yuvTextures, 0)

            avPosition = GLES20.glGetAttribLocation(shaderProgram, "v_Position")
            rgbPosition = GLES20.glGetAttribLocation(shaderProgram, "vTexCoordinateRgb")
            alphaPosition = GLES20.glGetAttribLocation(shaderProgram, "vTexCoordinateAlpha")
        } else {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

            avPosition = GLES20.glGetAttribLocation(shaderProgram, "vPosition")
            rgbPosition = GLES20.glGetAttribLocation(shaderProgram, "vTexCoordinateRgb")
            alphaPosition = GLES20.glGetAttribLocation(shaderProgram, "vTexCoordinateAlpha")
            textureUniform = GLES20.glGetUniformLocation(shaderProgram, "texture")

            ALog.d(TAG, "[DEBUG] Hard render texture: $textureId, textureUniform: $textureUniform")
        }
    }

    override fun renderFrame() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(shaderProgram)

        if (!isSoftRender) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(textureUniform, 0)
        }

        if (currentConfig != null) {
            GLES20.glEnableVertexAttribArray(avPosition)
            vertexArray.setVertexAttribPointer(avPosition)

            GLES20.glEnableVertexAttribArray(alphaPosition)
            alphaArray.setVertexAttribPointer(alphaPosition)

            if (rgbPosition >= 0) {
                GLES20.glEnableVertexAttribArray(rgbPosition)
                rgbArray.setVertexAttribPointer(rgbPosition)
            }
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun setYUVData(width: Int, height: Int, y: ByteArray?, u: ByteArray?, v: ByteArray?) {
        if (!isSoftRender) ALog.d(TAG, "[DEBUG] setYUVData ignored: Hard mode")
    }

    override fun destroyRender() { eglUtil.release() }
    override fun swapBuffers() { eglUtil.swapBuffers() }
    override fun getExternalTexture(): Int = if (isSoftRender) yuvTextures[0] else textureId
    override fun clearFrame() { GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); eglUtil.swapBuffers() }

    override fun setAnimConfig(config: AnimConfig) {
        currentConfig = config

        vertexArray.setArray(
            VertexUtil.create(
                config.width,
                config.height,
                PointRect(0, 0, config.width, config.height),
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

        ALog.i(TAG, "setAnimConfig: width=${config.width}, height=${config.height}, videoWidth=${config.videoWidth}, videoHeight=${config.videoHeight}")

        if (viewWidth <= 0 || viewHeight <= 0) {
            updateViewPort(config.width, config.height)
        }
    }

    override fun updateViewPort(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            ALog.d(TAG, "updateViewPort: invalid size width=$width, height=$height")
            return
        }
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
        ALog.i(TAG, "updateViewPort: width=$width, height=$height")
    }

    override fun releaseTexture() {}
}
