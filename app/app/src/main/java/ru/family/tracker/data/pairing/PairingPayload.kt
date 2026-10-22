package ru.family.tracker.data.pairing

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.family.tracker.data.api.normalizeBaseUrl

/**
 * Content of the pairing QR code: the server address a child must talk to and the
 * one-time 6-digit code.
 *
 * Encoded with kotlinx.serialization instead of string concatenation so quotes,
 * backslashes and other special characters are escaped correctly. Property order in the
 * class defines field order in the JSON: `server`, then `code`.
 */
@Serializable
data class PairingPayload(val server: String, val code: String)

private val pairingJson = Json {
    // Parents may add fields to the payload in future app versions; a child app must
    // still understand the payload instead of failing to pair.
    ignoreUnknownKeys = true
}

/** Exactly six ASCII digits; `\d` is avoided because it also accepts non-ASCII digits. */
private val sixDigitCode = Regex("[0-9]{6}")

/** Builds the exact QR payload, e.g. `{"server":"https://api.example.net","code":"123456"}`. */
fun qrPayload(serverUrl: String, code: String): String =
    pairingJson.encodeToString(PairingPayload(serverUrl, code))

/**
 * Decodes and validates a scanned QR payload.
 *
 * Returns `null` for anything that is not a usable pairing payload: malformed JSON,
 * missing/blank `server` or `code`, a code that is not exactly six digits, or a server
 * address that [normalizeBaseUrl] rejects (wrong scheme, path, query or fragment).
 * On success the server address is normalized, so callers can persist it as-is.
 */
fun parseQrPayload(raw: String): PairingPayload? {
    val payload = try {
        pairingJson.decodeFromString(PairingPayload.serializer(), raw)
    } catch (_: Exception) {
        return null
    }

    if (!sixDigitCode.matches(payload.code)) return null

    val server = runCatching { normalizeBaseUrl(payload.server) }.getOrNull() ?: return null
    return PairingPayload(server = server, code = payload.code)
}
