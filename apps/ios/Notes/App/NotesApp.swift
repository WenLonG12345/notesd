import NotesCore
import SwiftUI

@main
struct NotesApp: App {
    @State private var graph: AppGraph
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let graph = AppGraph()
        _graph = State(initialValue: graph)
        // Registered before launch ends, as BGTaskScheduler requires.
        let engine = graph.engine
        SyncScheduler.registerBackgroundTask {
            let outcome = await engine.sync().outcome
            return outcome == .done || outcome == .notConnected
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(graph)
                .tint(Color.notesTint)
                .onAppear { applyAppearance() }
                .onChange(of: graph.appearance) { applyAppearance() }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: graph.scheduler.enteredForeground()
            case .background: graph.scheduler.enteredBackground()
            default: break
            }
        }
    }

    private func applyAppearance() {
        for scene in UIApplication.shared.connectedScenes {
            for window in (scene as? UIWindowScene)?.windows ?? [] {
                window.overrideUserInterfaceStyle = graph.appearance.style
            }
        }
    }
}

/// Onboarding until there's an account (or the user chose to go without),
/// then the notes.
struct RootView: View {
    @Environment(AppGraph.self) private var graph
    @State private var path: [Route] = []

    var body: some View {
        Group {
            if graph.account == .none && !graph.onboardingDismissed {
                OnboardingView(model: OnboardingModel(accounts: graph.accounts, deviceName: graph.deviceName))
            } else {
                NavigationStack(path: $path) {
                    NotesListView(path: $path)
                        .navigationDestination(for: Route.self) { route in
                            switch route {
                            case .note(let id, let startTag, let isNew):
                                NoteScreen(
                                    store: graph.store, images: graph.images, id: id, startTag: startTag,
                                    isNew: isNew, path: $path
                                )
                                .id(id)
                            case .tags: TagsView(path: $path)
                            case .settings: SettingsView()
                            }
                        }
                }
            }
        }
        .background(Color.notesBg)
        .task { graph.scheduler.start() }
    }
}
