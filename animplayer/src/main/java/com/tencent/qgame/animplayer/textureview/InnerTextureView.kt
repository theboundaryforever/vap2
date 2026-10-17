package com.tencent.qgame.animplayer.textureview

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.TextureView
import com.tencent.qgame.animplayer.AnimPlayer

class InnerTextureView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr) {

    var player: AnimPlayer? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val outputW = player?.outputSurfaceWidth ?: 0
        val outputH = player?.outputSurfaceHeight ?: 0
        if (outputW > 0 && outputH > 0) {
            try {
                surfaceTexture?.setDefaultBufferSize(outputW, outputH)
            } catch (_: Throwable) {
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        val res = player?.isRunning() == true
                && ev != null
                && player?.pluginManager?.onDispatchTouchEvent(ev) == true
        return if (!res) super.dispatchTouchEvent(ev) else true
    }
}