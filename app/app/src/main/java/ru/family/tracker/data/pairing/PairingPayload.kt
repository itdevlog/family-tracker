package ru.family.tracker.data.pairing

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

private val pairingJson = Json

/** Builds the exact QR payload, e.g. `{"server":"https://api.example.net","code":"123456"}`. */
fun qrPayload(serverUrl: String, code: String): String =
    pairingJson.encodeToString(PairingPayload(serverUrl, code))
