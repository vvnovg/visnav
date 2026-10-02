package io.visnav.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class TrajectoryFormatHeaderTest {
    @Test fun headerEscapesRefpackString() {
        val nasty = "a\"b\\c\nd\u0001e"
        val o = Json.parseToJsonElement(TrajectoryFormat.fusionHeader(123L, nasty)).jsonObject
        assertEquals("fusion", o["type"]!!.jsonPrimitive.content)
        assertEquals("true", o["monitor"]!!.jsonPrimitive.content)
        assertEquals("123", o["session_started_ms"]!!.jsonPrimitive.content)
        assertEquals(nasty, o["refpack_created_at"]!!.jsonPrimitive.content)
    }
}
