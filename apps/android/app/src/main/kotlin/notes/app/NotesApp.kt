package notes.app

import android.app.Application

open class NotesApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = createGraph()
    }

    /** Tests swap in a graph without the Android Keystore, which Robolectric lacks. */
    protected open fun createGraph() = AppGraph(this)
}
