package ru.family.tracker.data.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadTest {

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

    @Test
    fun `valid qr payload parses and normalizes the server`() {
        assertEquals(
            PairingPayload(server = "https://api.example.net", code = "123456"),
            parseQrPayload(qrPayload("  https://api.example.net/  ", "123456")),
        )
    }

    @Test
    fun `broken json returns null`() {
        assertNull(parseQrPayload("""{"server":"https://api.example.net","code":"""))
        assertNull(parseQrPayload("not json at all"))
    }

    @Test
    fun `missing server or code returns null`() {
        assertNull(parseQrPayload("""{"server":"https://api.example.net"}"""))
        assertNull(parseQrPayload("""{"code":"123456"}"""))
    }

    @Test
    fun `empty values return null`() {
        assertNull(parseQrPayload(qrPayload("", "123456")))
        assertNull(parseQrPayload(qrPayload("https://api.example.net", "")))
        assertNull(parseQrPayload(qrPayload("   ", "   ")))
    }

    @Test
    fun `code must be exactly six digits`() {
        assertNull(parseQrPayload(qrPayload("https://api.example.net", "12ab56")))
        assertNull(parseQrPayload(qrPayload("https://api.example.net", "12345")))
        assertNull(parseQrPayload(qrPayload("https://api.example.net", "1234567")))
    }

    @Test
    fun `server with a path is rejected`() {
        assertNull(parseQrPayload(qrPayload("https://api.example.net/api", "123456")))
        assertNull(parseQrPayload(qrPayload("https://api.example.net/?a=1", "123456")))
        assertNull(parseQrPayload(qrPayload("ftp://api.example.net", "123456")))
    }

    @Test
    fun `unknown fields are ignored`() {
        val raw = """{"server":"https://api.example.net","code":"123456","extra":true}"""

        assertEquals(
            PairingPayload(server = "https://api.example.net", code = "123456"),
            parseQrPayload(raw),
        )
    }
}
