package notes.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import notes.app.notes.NotesNavigation
import notes.app.ui.NotesTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as NotesApp).graph
        setContent {
            NotesTheme {
                NotesNavigation(graph)
            }
        }
    }
}
