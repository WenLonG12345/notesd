import NotesCore
import SwiftUI

/// Locked notes (§10.1): change the lock password, lock now, or reset after forgetting it.
struct LockedNotesSection: View {
    @Environment(AppGraph.self) private var graph
    @State private var changing = false
    @State private var resetting = false
    @State private var message: String?

    var body: some View {
        Section {
            if graph.lockState == .none {
                LabeledContent("No lock password yet") {}
                Text("Choose Lock note from a note’s ••• menu to create one.")
                    .font(.notesSmall).foregroundStyle(Color.ink2)
            } else {
                if graph.lockState == .unlocked {
                    Button("Lock now") { Task { await graph.locks.lock() } }
                }
                Button("Change lock password") { changing = true }
                Button("Forgot the lock password?", role: .destructive) { resetting = true }
                    .foregroundStyle(Color.danger)
                if let message {
                    Text(message).font(.notesSmall).foregroundStyle(Color.ink2)
                }
            }
        } header: {
            SectionHeader("Locked notes")
        } footer: {
            Text("Locked notes lock again when you leave the app or after five minutes without use.")
                .font(.notesSmall).foregroundStyle(Color.ink2)
        }
        .sheet(isPresented: $changing) {
            ChangeLockPassword(locks: graph.locks) {
                message = "Lock password changed. Other devices use the new one once they sync."
            }
        }
        .sheet(isPresented: $resetting) {
            ResetLocks(locks: graph.locks) { message = nil }
        }
    }
}

private struct PasswordField: View {
    let label: String
    @Binding var text: String
    var error: String?
    var content: UITextContentType = .password

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            SecureField(label, text: $text).textContentType(content).frame(minHeight: 44)
                .contentShape(.rect)
            if let error { Text(error).font(.notesSmall).foregroundStyle(Color.errInk) }
        }
    }
}

/// A form sheet with Cancel and one action, its errors and a busy state.
private struct KeyringSheet<Fields: View>: View {
    let title: String
    let action: String
    var destructive = false
    let busy: Bool
    let footer: String
    let formError: String?
    let submit: () -> Void
    @ViewBuilder let fields: () -> Fields
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    fields()
                } footer: {
                    Text(footer).font(.notesSmall).foregroundStyle(Color.ink2)
                }
                if let formError { Text(formError).font(.notesSmall).foregroundStyle(Color.errInk) }
            }
            .font(.notesBody)
            .scrollContentBackground(.hidden)
            .background(Color.notesBg)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .cancel) { dismiss() }.disabled(busy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if busy {
                        ProgressView()
                    } else {
                        Button(action, systemImage: destructive ? "trash" : "checkmark", action: submit)
                            .buttonStyle(.glassProminent)
                            .tint(destructive ? Color.danger : Color(Palette.link))
                    }
                }
            }
        }
        .tint(Color(Palette.link))
        .interactiveDismissDisabled(busy)
    }
}

private struct ChangeLockPassword: View {
    let locks: LockSession
    let done: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var current = ""
    @State private var next = ""
    @State private var confirm = ""
    @State private var errors: [String: String] = [:]
    @State private var busy = false

    var body: some View {
        KeyringSheet(
            title: "Change lock password", action: "Change", busy: busy,
            footer: "Locked notes stay as they are; only the password that opens them changes.",
            formError: errors["form"], submit: submit
        ) {
            PasswordField(label: "Current lock password", text: $current, error: errors["current"])
            PasswordField(
                label: "New lock password", text: $next, error: errors["next"], content: .newPassword)
            PasswordField(
                label: "Confirm new password", text: $confirm, error: errors["confirm"],
                content: .newPassword)
        }
    }

    private func submit() {
        var found: [String: String] = [:]
        if current.isEmpty { found["current"] = "Enter your current lock password." }
        if next.count < minLockPassword {
            found["next"] = "Use at least \(minLockPassword) characters."
        }
        if confirm != next { found["confirm"] = "The passwords don’t match." }
        errors = found
        guard found.isEmpty else { return }
        busy = true
        Task {
            let outcome = await locks.changePassword(current: current, next: next)
            busy = false
            switch outcome {
            case .done:
                done()
                dismiss()
            case .wrongPassword: errors = ["current": "That isn’t your lock password."]
            default: errors = ["form": outcome.message ?? ""]
            }
        }
    }
}

private struct ResetLocks: View {
    let locks: LockSession
    let done: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var account = ""
    @State private var errors: [String: String] = [:]
    @State private var busy = false

    var body: some View {
        KeyringSheet(
            title: "Reset lock password", action: "Reset", destructive: true, busy: busy,
            footer:
                "Resetting forgets the lock password. Notes locked with it stay encrypted and can never be opened again, on any device. You can then create a new lock password.",
            formError: errors["form"], submit: submit
        ) {
            PasswordField(label: "Account password", text: $account, error: errors["account"])
        }
    }

    private func submit() {
        guard !account.isEmpty else {
            errors = ["account": "Enter your account password."]
            return
        }
        busy = true
        Task {
            let outcome = await locks.reset(accountPassword: account)
            busy = false
            switch outcome {
            case .done:
                done()
                dismiss()
            case .wrongPassword: errors = ["account": "That isn’t your account password."]
            default: errors = ["form": outcome.message ?? ""]
            }
        }
    }
}
