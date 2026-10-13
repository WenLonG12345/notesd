package notes.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import notes.app.notes.Deletions
import notes.app.settings.Appearance
import notes.core.db.Database
import notes.core.db.NoteStore

/** The app's long-lived objects, wired by hand. One per process, owned by [NotesApp]. */
class AppGraph(context: Context) {
    /** Outlives screens: saves and deletes started here finish after the screen that asked is gone. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database = Database(context.getDatabasePath("notes.db").apply { parentFile?.mkdirs() }.path)
    val store = NoteStore(database)
    val deletions = Deletions(scope) { store.delete(it) }
    val appearance = Appearance(context)
}
