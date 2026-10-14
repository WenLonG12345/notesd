package notes.app

import kotlin.test.assertEquals
import notes.core.db.NoteFilter
import org.junit.Test

class ListFilterPrefTest {
    @Test
    fun `round-trips every filter`() {
        for (f in listOf(null, NoteFilter.Untagged, NoteFilter.Tag("travel/portugal", "Travel/Portugal"))) {
            assertEquals(f, decodeFilter(encodeFilter(f)))
        }
    }

    @Test
    fun `anything else is all notes`() {
        assertEquals(null, decodeFilter("tag:no-name"))
        assertEquals(null, decodeFilter("something"))
    }
}
