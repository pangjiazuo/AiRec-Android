package com.neardi.recorder.data

import org.json.JSONObject

/** 地址填到其他服务时，给出错误提示，避免残缺JSON进入表单导致崩溃。 */
object ResponseValidation {
    fun status(value: JSONObject) {
        val channels = value.optJSONArray("channels") ?: throw IllegalArgumentException("该地址未返回录像机通道状态")
        require(channels.length() == 5 && (0 until channels.length()).map {
            channels.optJSONObject(it)?.optInt("id")
        }.toSet() == (1..5).toSet()) { "设备通道状态格式不完整" }
    }
    fun config(value: JSONObject) {
        status(value)
        require(value.optJSONObject("storage") != null && value.optJSONObject("server") != null) { "设备配置缺少存储或服务设置" }
        val channels = value.getJSONArray("channels")
        for (index in 0 until channels.length()) {
            val channel = channels.getJSONObject(index)
            require(channel.optJSONObject("recording") != null && channel.optJSONObject("detection")?.optJSONArray("categories") != null) {
                "通道${channel.optInt("id")}缺少录像或识别设置"
            }
        }
    }
}
