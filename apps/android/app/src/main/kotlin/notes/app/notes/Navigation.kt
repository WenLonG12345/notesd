package notes.app.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import notes.app.AppGraph
import notes.app.account.Account
import notes.app.account.OnboardingScreen
import notes.app.account.OnboardingViewModel
import notes.app.settings.SettingsScreen
import notes.app.ui.hideKeyboard
import notes.core.api.ApiClient
import notes.core.text.Uuid7

@Serializable
data object NotesKey : NavKey

/** A note by id; a new note gets its id up front and exists once it has text. */
@Serializable
data class EditorKey(val id: String) : NavKey

@Serializable
data object SettingsKey : NavKey

/** Connecting to a server; with [signInAgain], signing back in to the one these notes belong to. */
@Serializable
data class OnboardingKey(val signInAgain: Boolean = false) : NavKey

@Composable
fun NotesNavigation(graph: AppGraph) {
    val start = remember {
        val firstRun = graph.accounts.account.value == Account.None && !graph.onboardingDismissed.value
        if (firstRun) OnboardingKey() else NotesKey
    }
    val backStack = rememberNavBackStack(start)
    val view = LocalView.current
    val account by graph.accounts.account.collectAsStateWithLifecycle()

    // The keyboard belongs to the window, not the screen being left: hide it as back starts, not after the
    // exit animation, or it hangs over the list.
    fun pop() {
        view.hideKeyboard()
        backStack.removeLastOrNull()
    }

    /** To the notes list, with nothing behind it. */
    fun home() {
        view.hideKeyboard()
        backStack.add(NotesKey)
        while (backStack.size > 1) backStack.removeAt(0)
    }
    NavDisplay(
        backStack = backStack,
        onBack = { pop() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        entryProvider = entryProvider {
            entry<NotesKey> {
                NotesListScreen(
                    vm = viewModel { NotesListViewModel(graph.store, graph.deletions) },
                    signedOut = account is Account.SignedOut,
                    onSignIn = { backStack += OnboardingKey(signInAgain = true) },
                    onOpen = { backStack += EditorKey(it) },
                    onNew = { backStack += EditorKey(Uuid7.generate()) },
                    onSettings = { backStack += SettingsKey }
                )
            }
            entry<EditorKey> { key ->
                EditorScreen(
                    vm = viewModel { EditorViewModel(graph.store, key.id, graph.scope) },
                    onBack = { pop() },
                    onDelete = {
                        graph.deletions.request(key.id)
                        pop()
                    }
                )
            }
            entry<SettingsKey> {
                SettingsScreen(
                    graph,
                    onBack = { pop() },
                    onConnect = { backStack += OnboardingKey() },
                    onSignIn = { backStack += OnboardingKey(signInAgain = true) }
                )
            }
            entry<OnboardingKey> { key ->
                val signedOut = account as? Account.SignedOut
                val firstRun = backStack.size == 1
                OnboardingScreen(
                    vm = viewModel {
                        OnboardingViewModel(
                            graph.accounts,
                            graph.deviceName,
                            { ApiClient(it) },
                            lockedServer = signedOut?.server?.takeIf { key.signInAgain },
                            username = signedOut?.username.orEmpty()
                        )
                    },
                    onDone = { home() },
                    onSkip = if (firstRun && account == Account.None) {
                        {
                            graph.setOnboardingDismissed(true)
                            home()
                        }
                    } else {
                        null
                    },
                    onExit = if (firstRun) null else ({ pop() })
                )
            }
        }
    )
}
