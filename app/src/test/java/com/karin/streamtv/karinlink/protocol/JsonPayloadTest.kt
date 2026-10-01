package com.karin.streamtv.karinlink.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payload accessors that have to survive whatever a peer sends.
 *
 * The behaviour these lock in is the opposite of `JSONObject.optString`, which
 * quietly turns a number into a string and a non-numeric string into 0.
 */
class JsonPayloadTest {

    /**
     * Parsed from JSON text rather than assembled with a builder, so the accessors
     * are exercised against what a peer would really put on the wire, explicit
     * nulls and all.
     */
    private val payload = LinkProtocol.json.parseToJsonElement(
        """
        {
          "title": "Episode 1",
          "count": 42,
          "ratio": 1.5,
          "live": true,
          "blank": "",
          "nothing": null,
          "nested": { "deep": "value" },
          "list": ["a", "b"],
          "obj": { "k": 1 }
        }
        """.trimIndent()
    ) as JsonObject

    @Test
    fun `a string field is read as written`() {
        assertEquals("Episode 1", payload.str("title"))
    }

    @Test
    fun `an absent key yields the fallback`() {
        assertEquals("none", payload.str("missing", "none"))
    }

    @Test
    fun `a number is not silently read as a string`() {
        // optString would have answered "42" here.
        assertEquals("", payload.str("count"))
        assertEquals("fallback", payload.str("count", "fallback"))
    }

    @Test
    fun `an explicit null yields the fallback`() {
        assertEquals("", payload.str("nothing"))
        assertEquals("none", payload.str("nothing", "none"))
    }

    @Test
    fun `a non numeric string is not read as a number`() {
        // optLong would have answered 0 here, indistinguishable from a real 0.
        val odd = buildJsonObject { put("positionMs", "abc") }

        assertEquals(-1L, odd.long("positionMs", -1L))
        assertEquals(7L, odd.long("missing", 7L))
    }

    @Test
    fun `a quoted number is not read as a number`() {
        val odd = buildJsonObject { put("positionMs", "120000") }

        assertEquals(-1L, odd.long("positionMs", -1L))
    }

    @Test
    fun `numeric fields are read with their own type`() {
        assertEquals(42L, payload.long("count"))
        assertEquals(1.5, payload.double("ratio"), 0.0001)
        assertTrue(payload.bool("live"))
        assertFalse(payload.bool("missing"))
    }

    @Test
    fun `a string is not read as a boolean`() {
        val odd = buildJsonObject { put("flag", "true") }

        assertFalse(odd.bool("flag"))
        assertTrue(odd.bool("flag", true))
    }

    @Test
    fun `an empty string stays an empty string`() {
        // Distinct from absent, which matters for "cleared this field" messages.
        assertEquals("", payload.str("blank"))
    }

    @Test
    fun `nested containers are read or defaulted`() {
        assertEquals("value", payload.obj("nested")?.str("deep"))
        assertNull(payload.obj("missing"))
        assertNull(payload.obj("title"))

        assertEquals(2, payload.arr("list").size)
        assertEquals(0, payload.arr("missing").size)
        assertEquals(0, payload.arr("title").size)
    }

    @Test
    fun `an object field is read as an object`() {
        assertEquals(1L, payload.obj("obj")?.long("k"))
    }
}
