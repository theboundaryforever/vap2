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

import android.app.Activity
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.AnimView
import com.tencent.qgame.animplayer.inter.IAnimListener
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.IALog
import com.tencent.qgame.animplayer.util.ScaleType
import com.tencent.qgame.playerproj.databinding.ActivityAnimSimpleDemoBinding
import java.io.File


/**
 * VAPX demo (融合特效Demo)
 * 必须使用组件里提供的工具才能生成VAPX动画
 */
class AnimActiveDemoActivity : Activity(), IAnimListener {

    companion object {
        private const val TAG = "AnimSimpleDemoActivity"
    }

    private val dir by lazy {
        // 存放在sdcard应用缓存文件中
        getExternalFilesDir(null)?.absolutePath ?: Environment.getExternalStorageDirectory().path
    }

    // 视频信息
    data class VideoInfo(val fileName: String, val md5: String)

    private val videoInfo = VideoInfo("test_local.mp4", "645d9b91a45f0fe60cc3965a9917a7f9")


    // 动画View
    private lateinit var animView: AnimView

    private val uiHandler by lazy {
        Handler(Looper.getMainLooper())
    }

    private lateinit var binding: ActivityAnimSimpleDemoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnimSimpleDemoBinding.inflate(layoutInflater)
        setContentView(binding?.root)
        // 文件加载完成后会调用init方法
        val files = Array(1) {
            videoInfo.fileName
        }
        FileUtil.copyAssetsToStorage(this, dir, files) {
            uiHandler.post {
                init()
            }
        }
    }

    private fun init() {
        // 初始化日志
        initLog()
        // 初始化调试开关
        initTestView()
        // 获取动画view
        animView = binding.playerView
        // 铺满屏幕。FIT_CENTER 会按 750:1624 缩小宽度，左右留空。
        animView.setScaleType(ScaleType.FIT_XY)
        // 注册动画监听
        animView.setAnimListener(this)
        /**
         * 开始播放主流程
         * ps: 主要流程都是对AnimView的操作，其它比如队列，或改变窗口大小等操作都不是必须的
         */
        play(videoInfo)
    }


    private fun play(videoInfo: VideoInfo) {
        // 播放前强烈建议检查文件的md5是否有改变
        // 因为下载或文件存储过程中会出现文件损坏，导致无法播放
        Thread {
            val file = File(dir + "/" + videoInfo.fileName)
            val md5 = FileUtil.getFileMD5(file)
            Log.i(TAG, "play file=${file.absolutePath}, exists=${file.exists()}, size=${file.length()}, md5=$md5")
            if (videoInfo.md5 == md5) {
                // 开始播放动画文件
                uiHandler.post {
                    animView.startPlay(file)
                }
            } else {
                Log.e(TAG, "md5 is not match, error md5=$md5")
            }
        }.start()
    }

    /**
     * 视频信息准备好后的回调，用于检查视频准备好后是否继续播放
     * @return true 继续播放 false 停止播放
     */
    override fun onVideoConfigReady(config: AnimConfig): Boolean {
        return true
    }

    /**
     * 视频开始回调
     */
    override fun onVideoStart() {
        Log.i(TAG, "onVideoStart")
    }

    /**
     * 视频渲染每一帧时的回调
     * @param frameIndex 帧索引
     */
    override fun onVideoRender(frameIndex: Int, config: AnimConfig?) {
    }

    /**
     * 视频播放结束(失败也会回调onComplete)
     */
    override fun onVideoComplete() {
        Log.i(TAG, "onVideoComplete")
    }

    /**
     * 播放器被销毁情况下会调用onVideoDestroy
     */
    override fun onVideoDestroy() {
        Log.i(TAG, "onVideoDestroy")
    }

    /**
     * 失败回调
     * 一次播放时可能会调用多次，建议onFailed只做错误上报
     * @param errorType 错误类型
     * @param errorMsg 错误消息
     */
    override fun onFailed(errorType: Int, errorMsg: String?) {
        Log.i(TAG, "onFailed errorType=$errorType errorMsg=$errorMsg")
    }


    override fun onPause() {
        super.onPause()
        // 页面切换是停止播放
        animView.stopPlay()
    }


    private fun initLog() {
        ALog.isDebug = false
        ALog.log = object : IALog {
            override fun i(tag: String, msg: String) {
                Log.i(tag, msg)
            }

            override fun d(tag: String, msg: String) {
                Log.d(tag, msg)
            }

            override fun e(tag: String, msg: String) {
                Log.e(tag, msg)
            }

            override fun e(tag: String, msg: String, tr: Throwable) {
                Log.e(tag, msg, tr)
            }
        }
    }

    private fun initTestView() {
        binding?.btnLayout?.visibility = View.VISIBLE
        /**
         * 开始播放按钮
         */
        binding.btnPlay.setOnClickListener {
            play(videoInfo)
        }
        /**
         * 结束视频按钮
         */
        binding.btnStop.setOnClickListener {
            animView.stopPlay()
        }
    }

}

