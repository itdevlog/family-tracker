package familytracker

import kotlinx.serialization.Serializable

/**
 * Uniform error body: every failure responds with `{"error": "..."}`.
 */
@Serializable
data class ErrorResponse(val error: String)
