package notes.app.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class DeletionsTest {
    private val scope = TestScope()
    private val deleted = ArrayList<String>()
    private val deletions = Deletions(scope, windowMs = 5_000) { deleted += it }

    @Test
    fun `deletes when the undo window ends`() {
        deletions.request("a")
        scope.advanceTimeBy(4_999)
        assertEquals(listOf("a"), deletions.pending.value)
        assertEquals(emptyList(), deleted)
        scope.advanceTimeBy(2)
        assertEquals(listOf("a"), deleted)
        assertEquals(emptyList(), deletions.pending.value)
    }

    @Test
    fun `undo keeps the note`() {
        deletions.request("a")
        deletions.undo("a")
        scope.advanceTimeBy(10_000)
        assertEquals(emptyList(), deleted)
        assertEquals(emptyList(), deletions.pending.value)
    }

    @Test
    fun `leaving the list deletes at once`() {
        deletions.request("a")
        deletions.request("b")
        deletions.commitAll()
        scope.runCurrent()
        assertEquals(listOf("a", "b"), deleted)
        deletions.undo("a")
        scope.advanceTimeBy(10_000)
        assertEquals(listOf("a", "b"), deleted, "nothing is deleted twice")
    }
}
