package com.neardi.recorder.ui.settings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsDraftTest {
    private fun fixture(): JSONObject {
        val channels = JSONArray()
        for (id in 1..5) channels.put(JSONObject("""{
            "id":$id,"name":"AHD$id","enabled":true,"source":"/dev/video$id",
            "width":1280,"height":720,"fps":25,"preview_fps":16,"crop":[0,0,1,1],
            "recording":{"enabled":true,"segment_minutes":1,"future_mode":"channel$id"},
            "detection":{"enabled":true,"categories":["person","vehicle","animal"],"threshold_seconds":3,"confidence":0.5,"sample_interval":1,"lost_tolerance_seconds":2,"future_detector":"channel$id"}
        }"""))
        return JSONObject().put("channels", channels).put("storage", JSONObject("""{"target_id":"internal","max_gb":24,"min_free_gb":3,"unknown_storage":"keep"}"""))
            .put("server", JSONObject("""{"host":"0.0.0.0","port":8080}""")).put("future_feature", JSONObject("""{"enabled":true}"""))
    }

    @Test fun numericDraftIsValidatedWithoutMutatingOriginalOrRemovingUnknownFields() {
        val draft = fixture()
        val channel = draft.getJSONArray("channels").getJSONObject(0)
        channel.put("fps", "30").put("preview_fps", "20")
        channel.getJSONObject("detection").put("threshold_seconds", "0.1")
        val result = SettingsDraft.normalize(draft)
        assertEquals(30, result.getJSONArray("channels").getJSONObject(0).getInt("fps"))
        assertEquals("30", channel.getString("fps"))
        assertTrue(result.getJSONObject("future_feature").getBoolean("enabled"))
        assertEquals("keep", result.getJSONObject("storage").getString("unknown_storage"))
    }

    @Test fun applyingParametersPreservesWiringIdentityAndUnknownPerChannelFields() {
        val draft = fixture()
        val source = draft.getJSONArray("channels").getJSONObject(0)
        source.put("fps", 30).put("name", "门口")
        source.getJSONObject("recording").put("segment_minutes", 10)
        source.getJSONObject("detection").put("threshold_seconds", 12)
        val originalTarget = draft.getJSONArray("channels").getJSONObject(1)
        originalTarget.put("enabled", false).put("crop", JSONArray("[0.5,0,0.5,0.5]"))
        val copied = SettingsDraft.copyChannelParameters(draft, 1, setOf(2, 3))
        val target = copied.getJSONArray("channels").getJSONObject(1)
        assertEquals(2, target.getInt("id"))
        assertEquals("AHD2", target.getString("name"))
        assertFalse(target.getBoolean("enabled"))
        assertEquals("/dev/video2", target.getString("source"))
        assertEquals("[0.5,0,0.5,0.5]", target.getJSONArray("crop").toString())
        assertEquals(30, target.getInt("fps"))
        assertEquals(10, target.getJSONObject("recording").getInt("segment_minutes"))
        assertEquals("channel2", target.getJSONObject("recording").getString("future_mode"))
        assertEquals("channel2", target.getJSONObject("detection").getString("future_detector"))
        assertEquals(25, originalTarget.getInt("fps"))
        assertEquals(25, copied.getJSONArray("channels").getJSONObject(3).getInt("fps"))
    }

    @Test fun invalidFrameRatesAndNonFiniteInputsAreRejected() {
        for (invalid in listOf("", "NaN", "Infinity", "25.5", "31")) {
            val draft = fixture()
            draft.getJSONArray("channels").getJSONObject(0).put("fps", invalid)
            assertThrows(IllegalArgumentException::class.java) { SettingsDraft.normalize(draft) }
        }
        val draft = fixture()
        draft.getJSONArray("channels").getJSONObject(0).put("preview_fps", "26")
        assertThrows(IllegalArgumentException::class.java) { SettingsDraft.normalize(draft) }
    }

    @Test fun dwellSamplingAndStorageConstraintsAreCheckedBeforeNetworkSave() {
        val missingCategory = fixture()
        missingCategory.getJSONArray("channels").getJSONObject(0).getJSONObject("detection").put("categories", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { SettingsDraft.normalize(missingCategory) }
        val tolerance = fixture()
        tolerance.getJSONArray("channels").getJSONObject(0).getJSONObject("detection").put("sample_interval", 3)
        assertThrows(IllegalArgumentException::class.java) { SettingsDraft.normalize(tolerance) }
        val storage = fixture()
        storage.getJSONObject("storage").put("min_free_gb", ".1")
        assertThrows(IllegalArgumentException::class.java) { SettingsDraft.normalize(storage) }
    }
}
