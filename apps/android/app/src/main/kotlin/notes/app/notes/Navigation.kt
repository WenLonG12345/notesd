package notes.app.notes

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import notes.app.AppGraph
import notes.app.settings.SettingsScreen
import notes.core.text.Uuid7

@Serializable
data object NotesKey : NavKey

/** A note by id; a new note gets its id up front and exists once it has text. */
@Serializable
data class EditorKey(val id: String) : NavKey

@Serializable
data object SettingsKey : NavKey

@Composable
fun NotesNavigation(graph: AppGraph) {
    val backStack = rememberNavBackStack(NotesKey)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        entryProvider = entryProvider {
            entry<NotesKey> {
                NotesListScreen(
                    vm = viewModel { NotesListViewModel(graph.store, graph.deletions) },
                    onOpen = { backStack += EditorKey(it) },
                    onNew = { backStack += EditorKey(Uuid7.generate()) },
                    onSettings = { backStack += SettingsKey }
                )
            }
            entry<EditorKey> { key ->
                EditorScreen(
                    vm = viewModel { EditorViewModel(graph.store, key.id, graph.scope) },
                    onBack = { backStack.removeLastOrNull() },
                    onDelete = {
                        graph.deletions.request(key.id)
                        backStack.removeLastOrNull()
                    }
                )
            }
            entry<SettingsKey> {
                SettingsScreen(graph.appearance, graph.store, onBack = { backStack.removeLastOrNull() })
            }
        }
    )
}
