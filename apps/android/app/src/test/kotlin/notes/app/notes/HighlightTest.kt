package notes.app.notes

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import notes.core.db.Search.MARK_CLOSE
import notes.core.db.Search.MARK_OPEN

class HighlightTest {
    @Test
    fun `turns match markers into styled ranges`() {
        val style = SpanStyle(fontWeight = FontWeight.Bold)
        val s = highlight("a ${MARK_OPEN}milk$MARK_CLOSE b ${MARK_OPEN}oat$MARK_CLOSE", style)
        assertEquals("a milk b oat", s.text)
        assertEquals(listOf(2..5, 9..11), s.spanStyles.map { it.start until it.end })
    }
}
