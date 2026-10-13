package notes.app.notes

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class ListTimeTest {
    private val zone = ZoneId.of("Europe/Lisbon")
    private fun at(day: Int, hour: Int) =
        LocalDateTime.of(2026, 3, day, hour, 5).atZone(zone).toInstant().toEpochMilli()
    private val now = at(20, 15) // a Friday

    @Test
    fun `formats row times`() {
        fun time(ts: Long) = listTime(ts, now, zone, Locale.US)
        assertEquals("9:05 AM", time(at(20, 9)).replace(' ', ' '))
        assertEquals("Yesterday", time(at(19, 23)))
        assertEquals("Monday", time(at(16, 9)))
        assertEquals("3/1/26", time(at(1, 9)))
    }

    @Test
    fun `groups by date`() {
        val items =
            listOf(
                at(20, 9),
                at(19, 9),
                at(10, 9),
                at(1, 9),
                LocalDateTime.of(2025, 1, 1, 0, 0).atZone(zone).toInstant().toEpochMilli()
            )
        val groups = groupByDate(items, { it }, now, zone)
        assertEquals(listOf("Today", "Previous 7 days", "Previous 30 days", "Earlier"), groups.map { it.label })
        assertEquals(listOf(1, 1, 2, 1), groups.map { it.items.size })
    }
}
