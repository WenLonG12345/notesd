package notes.app.notes

import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import notes.core.sync.SyncStatus
import org.junit.Test

class NotSyncedTest {
    @Test
    fun `quiet while sync works, shown only when changes are stuck`() = runTest {
        val status = MutableStateFlow<SyncStatus>(SyncStatus.Synced(0))
        val pending = MutableStateFlow(0)
        val seen = ArrayList<NotSynced?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { notSynced(status, pending).toList(seen) }

        pending.value = 2 // edited, not sent yet: the next sync will take it
        status.value = SyncStatus.Offline
        status.value = SyncStatus.Syncing // a retry doesn't hide it
        status.value = SyncStatus.Failed("Bad gateway")
        status.value = SyncStatus.Synced(1)
        pending.value = 0
        status.value = SyncStatus.Offline // offline with nothing to send: nothing to say

        assertEquals(listOf(null, NotSynced(2, null), NotSynced(2, "Bad gateway"), null), seen)
        job.cancel()
    }

    @Test
    fun `signed out and not connected are left to their own banners`() = runTest {
        val status = MutableStateFlow<SyncStatus>(SyncStatus.SignedOut)
        val pending = MutableStateFlow(3)
        val seen = ArrayList<NotSynced?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { notSynced(status, pending).toList(seen) }

        status.value = SyncStatus.NotConnected

        assertEquals(listOf<NotSynced?>(null), seen)
        job.cancel()
    }
}
