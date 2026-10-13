package notes.app.notes

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

// Ported from the web app (apps/web/src/notes/list-time.ts).

private const val DAY = 86_400_000L

/** Apple Notes-style row time: a clock time today, then Yesterday, a weekday, a short date. */
fun listTime(
    ts: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String {
    val at = Instant.ofEpochMilli(ts).atZone(zone)
    val day = at.toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        day == today -> at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        day == today.minusDays(1) -> "Yesterday"
        now - ts < 7 * DAY -> at.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> at.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
    }
}

data class Group<T>(val label: String, val items: List<T>)

/** Splits notes (newest first) into the list's date sections, skipping empty ones. */
fun <T> groupByDate(
    items: List<T>,
    updatedAt: (T) -> Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault()
): List<Group<T>> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun label(ts: Long) = when {
        Instant.ofEpochMilli(ts).atZone(zone).toLocalDate() == today -> "Today"
        now - ts < 7 * DAY -> "Previous 7 days"
        now - ts < 30 * DAY -> "Previous 30 days"
        else -> "Earlier"
    }
    val groups = ArrayList<Group<T>>()
    for (item in items) {
        val label = label(updatedAt(item))
        val last = groups.lastOrNull()
        if (last?.label ==
            label
        ) {
            groups[groups.lastIndex] = last.copy(items = last.items + item)
        } else {
            groups +=
                Group(label, listOf(item))
        }
    }
    return groups
}
