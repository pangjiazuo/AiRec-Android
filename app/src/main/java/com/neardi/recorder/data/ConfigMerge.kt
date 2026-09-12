package com.neardi.recorder.data

import org.json.JSONArray
import org.json.JSONObject

/** 后端只接受完整配置；这里生成、合并最小改动，避免旧界面覆盖新配置。 */
object ConfigMerge {
    fun diff(original: JSONObject, draft: JSONObject): JSONObject {
        val result = JSONObject()
        for (key in draft.keys()) {
            val before = original.opt(key)
            val after = draft.get(key)
            if (key == "channels" && after is JSONArray) {
                val changes = JSONArray()
                for (index in 0 until after.length()) {
                    val channel = after.getJSONObject(index)
                    val id = channel.getInt("id")
                    val old = original.objects("channels").find { it.optInt("id") == id }
                        ?: throw IllegalArgumentException("通道 $id 不存在")
                    val delta = diff(old, channel)
                    if (delta.length() > 0) changes.put(delta.put("id", id))
                }
                if (changes.length() > 0) result.put(key, changes)
            } else if (before is JSONObject && after is JSONObject) {
                val changes = diff(before, after)
                if (changes.length() > 0) result.put(key, changes)
            } else if (!equivalent(before, after)) {
                result.put(key, copyValue(after))
            }
        }
        return result
    }

    fun merge(latest: JSONObject, changes: JSONObject): JSONObject {
        val result = JSONObject(latest.toString())
        for (key in changes.keys()) {
            val value = changes.get(key)
            if (key == "channels" && value is JSONArray) {
                val channels = result.getJSONArray("channels")
                val seen = mutableSetOf<Int>()
                for (index in 0 until value.length()) {
                    val patch = value.getJSONObject(index)
                    val id = patch.getInt("id")
                    require(id in 1..5 && seen.add(id)) { "通道编号应为 1～5 且不能重复" }
                    val position = (0 until channels.length()).firstOrNull { channels.getJSONObject(it).getInt("id") == id }
                        ?: throw IllegalArgumentException("通道 $id 不存在")
                    channels.put(position, merge(channels.getJSONObject(position), patch))
                }
            } else if (value is JSONObject && result.optJSONObject(key) != null) {
                result.put(key, merge(result.getJSONObject(key), value))
            } else {
                result.put(key, copyValue(value))
            }
        }
        return result
    }

    private fun copyValue(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject(value.toString())
        is JSONArray -> JSONArray(value.toString())
        else -> value
    }

    private fun equivalent(a: Any?, b: Any?): Boolean {
        if (a is JSONObject && b is JSONObject) {
            val keysA = a.keys().asSequence().toSet()
            return keysA == b.keys().asSequence().toSet() && keysA.all { equivalent(a.opt(it), b.opt(it)) }
        }
        if (a is JSONArray && b is JSONArray) {
            return a.length() == b.length() && (0 until a.length()).all { equivalent(a.opt(it), b.opt(it)) }
        }
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        return a == b
    }
}
