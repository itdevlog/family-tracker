package ru.family.tracker.ui.parent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class QrPayloadTest {

    @Test
    fun `payload is ordered json without spaces`() {
        assertEquals(
            """{"server":"https://api.example.net","code":"123456"}""",
            qrPayload("https://api.example.net", "123456"),
        )
    }

    @Test
    fun `special characters are escaped by the serializer`() {
        val server = "https://api.example.net/?a=1&b=2"
        val code = """12"34\56"""

        assertEquals(
            """{"server":"https://api.example.net/?a=1&b=2","code":"12\"34\\56"}""",
            qrPayload(server, code),
        )
    }

    @Test
    fun `payload round trips through a json parser`() {
        val parsed = Json.parseToJsonElement(
            qrPayload("https://api.example.net", "654321"),
        ).jsonObject

        assertEquals(2, parsed.size)
        assertEquals("https://api.example.net", parsed["server"]!!.jsonPrimitive.content)
        assertEquals("654321", parsed["code"]!!.jsonPrimitive.content)
    }
}
