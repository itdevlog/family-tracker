package familytracker

import familytracker.db.PointIn
import familytracker.live.LiveBroadcaster
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveBroadcasterTest {
    @Test
    fun `a stalled consumer is dropped after the send timeout and does not block others`() = runBlocking {
        val familyId = UUID.randomUUID()
        val childId = UUID.randomUUID()
        val stalled = RecordingSession(stall = true)
        val healthy = RecordingSession()
        val broadcaster = LiveBroadcaster(this, sendTimeoutMs = 100)

        broadcaster.register(familyId, stalled)
        broadcaster.register(familyId, healthy)

        broadcaster.broadcastLocations(familyId, childId, listOf(point(0)))

        // The healthy peer is served even though the stalled one never accepts a frame.
        withTimeout(2_000) { while (healthy.texts.isEmpty()) delay(10) }

        // Wait past the send timeout so the stalled session is unregistered.
        delay(400)
        assertEquals(1, stalled.textSends)

        // A later batch must not retry the session that already timed out.
        broadcaster.broadcastLocations(familyId, childId, listOf(point(1)))
        withTimeout(2_000) { while (healthy.texts.size < 2) delay(10) }

        assertEquals(1, stalled.textSends)
        assertEquals(2, healthy.texts.size)
    }

    private fun point(index: Int) = PointIn(
        lat = 55.0 + index / 1_000.0,
        lon = 37.0 + index / 1_000.0,
        accuracy = 5.0f,
        speed = 1.5f,
        recordedAt = 1_700_000_000_000L + index,
    )

    /**
     * Minimal session double. [stall] suspends every text send forever, mimicking a peer
     * that stopped reading and filled its outgoing queue.
     */
    private class RecordingSession(private val stall: Boolean = false) : WebSocketSession {
        val texts = mutableListOf<String>()
        var textSends = 0
            private set

        override val coroutineContext = Job()
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override val incoming = Channel<Frame>(Channel.UNLIMITED)
        override val outgoing = Channel<Frame>(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()

        override suspend fun send(frame: Frame) {
            if (frame !is Frame.Text) return
            textSends++
            if (stall) awaitCancellation()
            texts += frame.readText()
        }

        override suspend fun flush() = Unit

        @Deprecated("Use cancel() instead.", level = DeprecationLevel.ERROR)
        override fun terminate() = Unit
    }
}
