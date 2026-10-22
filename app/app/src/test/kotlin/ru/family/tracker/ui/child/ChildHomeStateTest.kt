package ru.family.tracker.ui.child

import org.junit.Assert.assertEquals
import org.junit.Test

/** Save/restore policy for the SOS screen: results survive, an in-flight send never does. */
class ChildHomeStateTest {

    @Test
    fun `real results survive recreation`() {
        assertEquals(SosStatus.SENT, restoredSosStatus(SosStatus.SENT.name))
        assertEquals(SosStatus.PENDING, restoredSosStatus(SosStatus.PENDING.name))
        assertEquals(SosStatus.IDLE, restoredSosStatus(SosStatus.IDLE.name))
    }

    @Test
    fun `a stuck sending state is reset instead of disabling the button forever`() {
        assertEquals(SosStatus.IDLE, restoredSosStatus(SosStatus.SENDING.name))
    }

    @Test
    fun `unknown restored values fall back to idle`() {
        assertEquals(SosStatus.IDLE, restoredSosStatus(null))
        assertEquals(SosStatus.IDLE, restoredSosStatus("GARBAGE"))
    }

    @Test
    fun `hold duration is exactly two seconds`() {
        assertEquals(2_000, HOLD_MS)
    }
}
