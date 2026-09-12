package com.neardi.recorder.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConfigMergeTest {
    @Test fun diffOnlyIncludesEditedFieldsAndMatchingChannelIds() {
        val original = configFixture()
        val draft = JSONObject(original.toString())
        draft.getJSONArray("channels").getJSONObject(0).getJSONObject("detection").put("threshold_seconds", 12)
        draft.getJSONObject("storage").put("max_gb", 32)
        val patch = ConfigMerge.diff(original, draft)
        assertEquals(setOf("channels", "storage"), patch.keys().asSequence().toSet())
        val channel = patch.getJSONArray("channels").getJSONObject(0)
        assertEquals(setOf("id", "detection"), channel.keys().asSequence().toSet())
        assertFalse(channel.has("source"))
        assertFalse(channel.has("crop"))
        assertEquals(1, patch.getJSONArray("channels").length())
        assertEquals(setOf("threshold_seconds"), channel.getJSONObject("detection").keys().asSequence().toSet())
        assertEquals(3, original.getJSONArray("channels").getJSONObject(0).getJSONObject("detection").getInt("threshold_seconds"))
    }

    @Test fun mergeKeepsConcurrentUpdatesAndDoesNotMutateInputs() {
        val original = configFixture()
        val draft = JSONObject(original.toString())
        draft.getJSONArray("channels").getJSONObject(0).getJSONObject("detection").put("threshold_seconds", 10)
        val latest = JSONObject(original.toString())
        latest.getJSONArray("channels").getJSONObject(0).getJSONObject("detection").put("confidence", 0.8)
        latest.getJSONArray("channels").getJSONObject(0).put("crop", JSONArray("[0.1,0.1,0.8,0.8]"))
        val merged = ConfigMerge.merge(latest, ConfigMerge.diff(original, draft))
        assertEquals(0.8, merged.objects("channels")[0].obj("detection").getDouble("confidence"), 0.0)
        assertEquals(10, merged.objects("channels")[0].obj("detection").getInt("threshold_seconds"))
        assertEquals("[0.1,0.1,0.8,0.8]", merged.objects("channels")[0].getJSONArray("crop").toString())
        assertEquals(3, latest.objects("channels")[0].obj("detection").getInt("threshold_seconds"))
    }

    @Test fun unknownOrDuplicateChannelIdsAreRejected() {
        val config = configFixture()
        listOf("""{"channels":[{"id":6}]}""", """{"channels":[{"id":1},{"id":1}]}""").forEach {
            assertThrows(IllegalArgumentException::class.java) { ConfigMerge.merge(config, JSONObject(it)) }
        }
    }

    @Test fun categoryArrayCanBeClearedAndUnchangedNumbersDoNotBecomeEdits() {
        val original = configFixture()
        original.objects("channels")[0].obj("detection").put("categories", JSONArray("[\"person\",\"vehicle\"]"))
        val draft = JSONObject(original.toString())
        draft.objects("channels")[0].obj("detection").put("categories", JSONArray()).put("threshold_seconds", 3.0)
        val changes = ConfigMerge.diff(original, draft)
        val detection = changes.objects("channels")[0].obj("detection")
        assertFalse(detection.has("threshold_seconds"))
        assertEquals(0, ConfigMerge.merge(original, changes).objects("channels")[0].obj("detection").getJSONArray("categories").length())
    }

    @Test fun singleChannelEditUsesIdAndPreservesOtherChannelsAfterServerReordering() {
        val original = configFixture()
        val draft = JSONObject(original.toString())
        draft.objects("channels").first { it.getInt("id") == 2 }.apply {
            put("name", "入口二")
            obj("detection").put("threshold_seconds", 17)
        }
        val latest = JSONObject(original.toString())
        val reordered = JSONArray()
        latest.objects("channels").reversed().forEach { channel ->
            channel.put("server_extension", "keep-${channel.getInt("id")}")
            if (channel.getInt("id") == 3) channel.put("name", "其他客户端刚改名")
            reordered.put(channel)
        }
        latest.put("channels", reordered)

        val patch = ConfigMerge.diff(original, draft)
        assertEquals(1, patch.getJSONArray("channels").length())
        assertEquals(2, patch.getJSONArray("channels").getJSONObject(0).getInt("id"))
        val merged = ConfigMerge.merge(latest, patch)
        assertEquals(listOf(5, 4, 3, 2, 1), merged.objects("channels").map { it.getInt("id") })
        for (channel in latest.objects("channels")) {
            val updated = merged.objects("channels").first { it.getInt("id") == channel.getInt("id") }
            if (channel.getInt("id") == 2) {
                assertEquals("入口二", updated.getString("name"))
                assertEquals(17, updated.obj("detection").getInt("threshold_seconds"))
                assertEquals(channel.getString("source"), updated.getString("source"))
                assertEquals(channel.getJSONArray("crop").toString(), updated.getJSONArray("crop").toString())
                assertEquals("keep-2", updated.getString("server_extension"))
            } else assertTrue("未编辑的通道应原样保留", updated.jsonMatches(channel))
        }
        assertTrue(merged.getJSONObject("storage").jsonMatches(latest.getJSONObject("storage")))
        assertTrue(merged.getJSONObject("server").jsonMatches(latest.getJSONObject("server")))
    }

    @Test fun globalStorageEditDoesNotRewriteConcurrentChannelChanges() {
        val original = configFixture()
        val draft = JSONObject(original.toString())
        draft.getJSONObject("storage").put("max_gb", 32)
        val latest = JSONObject(original.toString())
        latest.objects("channels").first { it.getInt("id") == 4 }.obj("detection").put("threshold_seconds", 19)
        val patch = ConfigMerge.diff(original, draft)
        assertFalse(patch.has("channels"))
        val merged = ConfigMerge.merge(latest, patch)
        assertEquals(32, merged.getJSONObject("storage").getInt("max_gb"))
        assertTrue(merged.getJSONArray("channels").jsonMatches(latest.getJSONArray("channels")))
        assertEquals(24, latest.getJSONObject("storage").getInt("max_gb"))
    }
}

/** Android 的 org.json 没有 similar；按结构比较，忽略对象键顺序。 */
private fun Any?.jsonMatches(other: Any?): Boolean = when {
    this is JSONObject && other is JSONObject -> {
        val keys = keys().asSequence().toSet()
        keys == other.keys().asSequence().toSet() && keys.all { get(it).jsonMatches(other.get(it)) }
    }
    this is JSONArray && other is JSONArray -> length() == other.length() &&
        (0 until length()).all { get(it).jsonMatches(other.get(it)) }
    this is Number && other is Number -> toDouble() == other.toDouble()
    else -> this == other
}
