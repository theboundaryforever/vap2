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
package com.tencent.qgame.animplayer.mix

import android.util.SparseArray
import com.tencent.qgame.animplayer.PointRect
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 单帧
 */
class Frame(val index: Int, json: JSONObject) {
    var srcId = ""
    var z = 0
    var frame: PointRect
    var mFrame: PointRect
    var mt = 0 // 遮罩旋转角度v2 版本只支持 0 与 90度

    init {
        srcId = json.getString("srcId")
        z = json.getInt("z")

        val f = json.getJSONArray("frame")
        frame = PointRect(f.getInt(0), f.getInt(1), f.getInt(2), f.getInt(3))

        val m = json.getJSONArray("mFrame")
        mFrame = PointRect(m.getInt(0), m.getInt(1), m.getInt(2), m.getInt(3))

        mt = json.getInt("mt")
    }
}


/**
 * 一帧的集合
 */
class FrameSet(json: JSONObject) {
    var index = 0 // 哪一帧
    val list = ArrayList<Frame>()
    init {
        index = json.getInt("i")
        val objJsonArray = json.getJSONArray("obj")
        val objLen = objJsonArray?.length() ?: 0
        for (i in 0 until objLen) {
            val frameJson = objJsonArray?.getJSONObject(i) ?: continue
            val frame = Frame(index, frameJson)
            list.add(frame)
        }
        // 绘制顺序排序
        list.sortBy {it.z}
    }
}

/**
 * 所有帧集合
 */
class FrameAll {
    // 每一帧的集合。只留绘制要用的数据，不留原始 JSON。
    val map = SparseArray<FrameSet>()

    constructor()

    constructor(json: JSONObject) : this() {
        val frameJsonArray = json.getJSONArray("frame")
        val frameLen = frameJsonArray?.length() ?: 0
        for (i in 0 until frameLen) {
            val frameSetJson = frameJsonArray?.getJSONObject(i) ?: continue
            val frameSet = FrameSet(frameSetJson)
            map.put(frameSet.index, frameSet)
        }
    }

    companion object {
        /**
         * 逐帧读入后立刻丢掉该帧的 JSON 节点。
         * 不把整段 frame 数组建成一棵 JSONObject，解析峰值只剩当前这一帧。
         */
        fun readArray(tokener: JSONTokener): FrameAll {
            val all = FrameAll()
            if (tokener.nextClean() != '[') {
                throw JSONException("frame array")
            }
            var separator = tokener.nextClean()
            if (separator == ']') {
                return all
            }
            tokener.back()
            while (true) {
                val frameSet = FrameSet(JSONObject(tokener))
                all.map.put(frameSet.index, frameSet)
                separator = tokener.nextClean()
                when (separator) {
                    ',' -> continue
                    ']' -> return all
                    else -> throw JSONException("frame array")
                }
            }
        }
    }
}