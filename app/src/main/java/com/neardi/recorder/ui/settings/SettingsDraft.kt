package com.neardi.recorder.ui.settings

import org.json.JSONObject

/** 草稿保留完整配置，输入通过校验后才交给网络层保存。 */
object SettingsDraft {
    fun normalize(draft: JSONObject): JSONObject {
        val result = JSONObject(draft.toString())
        val channels = result.optJSONArray("channels") ?: error("通道配置尚未加载")
        require(channels.length() == 5) { "必须保留完整的五路通道配置" }
        val ids = mutableSetOf<Int>()
        for (index in 0 until channels.length()) {
            val channel = channels.getJSONObject(index)
            val id = channel.getInt("id")
            require(id in 1..5 && ids.add(id)) { "通道编号必须为 1～5，且不能重复" }
            val prefix = "AHD $id · "
            val name = channel.optString("name").trim()
            require(name.length in 1..40) { prefix + "名称长度应为 1～40 字" }
            channel.put("name", name)
            number(channel, "width", 160.0, 1920.0, prefix + "图像宽度", true)
            number(channel, "height", 120.0, 1080.0, prefix + "图像高度", true)
            require(channel.getInt("width") % 8 == 0 && channel.getInt("height") % 8 == 0) { prefix + "图像宽高必须为 8 的整数倍" }
            number(channel, "fps", 1.0, 30.0, prefix + "录像帧率", true)
            number(channel, "preview_fps", 1.0, channel.getDouble("fps"), prefix + "预览帧率", true)
            val recording = channel.getJSONObject("recording")
            require(recording.optInt("segment_minutes") in listOf(1, 3, 5, 10)) { prefix + "片段长度只能为 1、3、5、10 分钟" }
            channel.optJSONObject("privacy")?.let { p ->
                require(p.opt("face_mosaic") is Boolean && p.opt("plate_mosaic") is Boolean) { prefix + "马赛克开关无效" }
            }
            val detection = channel.getJSONObject("detection")
            val categories = detection.optJSONArray("categories") ?: error(prefix + "缺少识别类别")
            require((0 until categories.length()).all { categories.optString(it) in listOf("person", "vehicle", "animal") }) { prefix + "识别类别无效" }
            require(!detection.optBoolean("enabled") || categories.length() > 0) { prefix + "启用侦测时请至少选择一种目标" }
            number(detection, "threshold_seconds", .1, 3600.0, prefix + "停留阈值")
            number(detection, "confidence", .1, .99, prefix + "识别置信度")
            number(detection, "sample_interval", .2, 10.0, prefix + "侦测间隔")
            number(detection, "lost_tolerance_seconds", .2, 30.0, prefix + "短暂消失容忍")
            require(detection.getDouble("lost_tolerance_seconds") >= detection.getDouble("sample_interval")) { prefix + "消失容忍时间不能小于侦测间隔" }
        }
        val storage = result.getJSONObject("storage")
        val target = storage.optString("target_id")
        require(target == "internal" || Regex("uuid:[A-Za-z0-9_-]{1,128}").matches(target)) { "请选择有效的录像保存介质" }
        number(storage, "max_gb", 1.0, 100000.0, "录像空间上限")
        number(storage, "min_free_gb", .25, 10000.0, "保留磁盘空间")
        return result
    }

    private fun number(obj: JSONObject, key: String, min: Double, max: Double, label: String, integer: Boolean = false) {
        val value = obj.opt(key)?.toString()?.trim()?.toDoubleOrNull()
        require(value != null && value.isFinite() && value in min..max && (!integer || value % 1.0 == 0.0)) {
            "$label 应为 $min～$max${if (integer) " 的整数" else ""}"
        }
        obj.put(key, if (integer) value.toInt() else value)
    }

    /** 只复制可裁剪的业务参数；通道身份、接线映射与未知扩展字段留在原通道。 */
    fun copyChannelParameters(draft: JSONObject, sourceId: Int, targetIds: Set<Int>): JSONObject {
        val result = JSONObject(draft.toString())
        val array = result.getJSONArray("channels")
        val channels = (0 until array.length()).map { array.getJSONObject(it) }
        val source = channels.first { it.getInt("id") == sourceId }
        channels.filter { it.getInt("id") in targetIds && it.getInt("id") != sourceId }.forEach { target ->
            source.optJSONObject("privacy")?.let { p ->
                val targetPrivacy = target.optJSONObject("privacy") ?: JSONObject()
                listOf("face_mosaic", "plate_mosaic").forEach { targetPrivacy.put(it, p.getBoolean(it)) }
                target.put("privacy", targetPrivacy)
            }
            listOf("width", "height", "fps", "preview_fps").forEach { target.put(it, source.get(it)) }
            mapOf("recording" to listOf("enabled", "segment_minutes"), "detection" to listOf("enabled", "categories", "threshold_seconds", "confidence", "sample_interval", "lost_tolerance_seconds")).forEach { (section, keys) ->
                val sourceSection = source.getJSONObject(section)
                val targetSection = target.getJSONObject(section)
                keys.forEach { key ->
                    // 用 JSON 副本避免类别数组在不同通道之间共享引用。
                    targetSection.put(key, JSONObject(sourceSection.toString()).get(key))
                }
            }
        }
        return result
    }
}
