package ru.family.tracker.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.family.tracker.data.api.PointIn
import java.time.Instant

class IdleClusteringTest {

    private val base = Instant.parse("2026-10-20T08:00:00Z").toEpochMilli()

    private fun point(
        minute: Long,
        lat: Double = 55.75,
        lon: Double = 37.61,
    ) = PointIn(
        lat = lat,
        lon = lon,
        recordedAtMillis = base + minute * 60_000,
    )

    private fun minutes(count: Long) = count * 60_000

    @Test
    fun `empty and single point produce no stops`() {
        assertTrue(findStops(emptyList()).isEmpty())
        assertTrue(findStops(listOf(point(0))).isEmpty())
    }

    @Test
    fun `four minutes standing is not a stop`() {
        val stops = findStops(listOf(point(0), point(2), point(4)))

        assertTrue(stops.isEmpty())
    }

    @Test
    fun `five minutes standing is a stop`() {
        val stops = findStops(listOf(point(0), point(2), point(5)))

        assertEquals(1, stops.size)
        val stop = stops.single()
        assertEquals(base, stop.startMillis)
        assertEquals(base + minutes(5), stop.endMillis)
        assertEquals(minutes(5), stop.durationMillis)
        assertEquals(3, stop.pointCount)
        assertEquals(55.75, stop.lat, 1e-9)
        assertEquals(37.61, stop.lon, 1e-9)
    }

    @Test
    fun `two separate stops in one day`() {
        val stops = findStops(
            listOf(
                point(0),
                point(2),
                point(6),
                point(12, lat = 55.80, lon = 37.70),
                point(20, lat = 55.80, lon = 37.70),
                point(26, lat = 55.80, lon = 37.70),
            ),
        )

        assertEquals(2, stops.size)
        assertEquals(base, stops[0].startMillis)
        assertEquals(base + minutes(6), stops[0].endMillis)
        assertEquals(base + minutes(12), stops[1].startMillis)
        assertEquals(base + minutes(26), stops[1].endMillis)
        assertEquals(3, stops[1].pointCount)
    }

    @Test
    fun `points a hundred metres apart are not merged`() {
        // 0.0009 degrees of latitude is about 100 m, twice the 50 m radius.
        val stops = findStops(
            listOf(
                point(0, lat = 55.7500),
                point(10, lat = 55.7509),
            ),
        )

        assertTrue(stops.isEmpty())
    }

    @Test
    fun `fifty-six metres apart is outside the fifty metre stop radius`() {
        // 0.0005 degrees of latitude is about 55.6 m. The pair stands for five minutes,
        // so only the radius can keep it out of the result; a radius of 80 m fails here.
        val stops = findStops(
            listOf(
                point(0, lat = 55.7500),
                point(5, lat = 55.7505),
            ),
        )

        assertTrue(stops.isEmpty())
    }

    @Test
    fun `thirty-nine metres apart stays inside the fifty metre stop radius`() {
        // 0.00035 degrees of latitude is about 39 m, so this is one stop. A radius of
        // 30 m (or any value below ~39 m) fails here.
        val stops = findStops(
            listOf(
                point(0, lat = 55.7500),
                point(5, lat = 55.75035),
            ),
        )

        assertEquals(1, stops.size)
        assertEquals(2, stops.single().pointCount)
    }

    @Test
    fun `stop coordinates are the mean of its points`() {
        val stops = findStops(
            listOf(
                point(0, lat = 55.7500, lon = 37.6100),
                point(3, lat = 55.7501, lon = 37.6101),
                point(5, lat = 55.7502, lon = 37.6102),
            ),
        )

        val stop = stops.single()
        assertEquals(55.7501, stop.lat, 1e-9)
        assertEquals(37.6101, stop.lon, 1e-9)
    }

    @Test
    fun `input is sorted by timestamp`() {
        val stops = findStops(listOf(point(5), point(0), point(2)))

        val stop = stops.single()
        assertEquals(base, stop.startMillis)
        assertEquals(base + minutes(5), stop.endMillis)
        assertEquals(3, stop.pointCount)
    }

    @Test
    fun `an outlier inside a standing period closes the cluster`() {
        val stops = findStops(
            listOf(
                point(0),
                point(2),
                point(3, lat = 55.80, lon = 37.70),
                point(5),
                point(7),
            ),
        )

        // The two same-place runs last 2 minutes each; the outlier forms a singleton.
        // A naive first-to-last check would have reported one bogus seven-minute stop.
        assertTrue(stops.isEmpty())
    }
}
