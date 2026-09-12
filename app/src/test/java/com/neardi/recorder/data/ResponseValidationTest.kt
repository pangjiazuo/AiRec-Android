package com.neardi.recorder.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class ResponseValidationTest {
    @Test fun unrelatedServerAndIncompleteConfigFailBeforeRendering() {
        assertThrows(IllegalArgumentException::class.java) { ResponseValidation.status(JSONObject("{\"ok\":true}")) }
        assertThrows(IllegalArgumentException::class.java) { ResponseValidation.config(JSONObject("{\"channels\":[]}")) }
        val channels = JSONArray((1..5).map { JSONObject().put("id", it) })
        val partial = JSONObject().put("channels", channels)
        ResponseValidation.status(partial)
        assertThrows(IllegalArgumentException::class.java) { ResponseValidation.config(partial) }
        partial.put("storage", JSONObject()).put("server", JSONObject())
        assertThrows(IllegalArgumentException::class.java) { ResponseValidation.config(partial) }
    }
    @Test fun duplicateChannelIdCannotHideMissingChannel() {
        val channels = JSONArray((1..5).map { JSONObject().put("id", 1) })
        assertThrows(IllegalArgumentException::class.java) { ResponseValidation.status(JSONObject().put("channels", channels)) }
    }
}
