import NotesCore
import SwiftUI

/// Shortest lock password accepted; it's the only thing between the server and the notes.
let minLockPassword = 8

extension LockSession.Outcome {
    /// What a keyring change that needs the server says when it can't finish.
    var message: String? {
        switch self {
        case .done: nil
        case .wrongPassword: "Wrong password."
        case .notConnected: "Connect to your server first: it keeps the lock password for every device."
        case .offline: "Can’t reach the server. Try again when you’re online."
        case .failed: "Something went wrong. Try again."
        }
    }
}

/// A locked note while locked notes are locked: its title and the lock password field.
struct LockedNoteView: View {
    let title: String
    /// Locked with a key that was reset: no password opens it.
    let unreadable: Bool
    let locks: LockSession
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false
    @FocusState private var focused: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                Image(systemName: "lock.fill").font(.system(size: 34)).foregroundStyle(Color.accentDeep)
                    .accessibilityHidden(true)
                    .padding(.top, 48)
                Text(title.isEmpty ? "Locked note" : title).font(.notesTitle).foregroundStyle(Color.ink)
                    .multilineTextAlignment(.center)
                if unreadable {
                    Text("This note was locked with a password that was reset. It can’t be opened.")
                        .font(.notesBody).foregroundStyle(Color.ink2).multilineTextAlignment(.center)
                } else {
                    Text("This note is locked.").font(.notesSmall).foregroundStyle(Color.ink2)
                    VStack(alignment: .leading, spacing: 6) {
                        SecureField("Lock password", text: $password)
                            .textContentType(.password)
                            .submitLabel(.go)
                            .focused($focused)
                            .onSubmit(unlock)
                            // Typing clears the error; the field emptied after a wrong password doesn't.
                            .onChange(of: password) { _, typed in if !typed.isEmpty { error = nil } }
                            .padding(.horizontal, 14)
                            .frame(minHeight: 48)
                            .background(Color(Palette.field), in: .rect(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.hair))
                            .disabled(busy)
                        if let error {
                            Text(error).font(.notesSmall).foregroundStyle(Color.errInk)
                        }
                    }
                    .padding(.top, 8)
                    Button(action: unlock) {
                        Text(busy ? "Unlocking…" : "Unlock").frame(maxWidth: .infinity, minHeight: 36)
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.capsule)
                    .tint(Color(Palette.link))
                    .disabled(busy)
                }
            }
            .padding(.horizontal, 32)
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity)
        }
        .onAppear { if !unreadable { focused = true } }
    }

    private func unlock() {
        guard !busy, !password.isEmpty else { return }
        busy = true
        Task {
            let ok = await locks.unlock(password)
            busy = false
            if !ok {
                error = "Wrong password."
                password = ""
            }
        }
    }
}

/// Locking a note while locked notes are locked (or there's no lock password
/// yet): unlock with the password, or create one. `done` runs once unlocked.
struct LockSheet: View {
    let locks: LockSession
    /// Whether a lock password exists already.
    let exists: Bool
    let done: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var password = ""
    @State private var confirm = ""
    @State private var errors: [String: String] = [:]
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField("Lock password", text: $password)
                        .textContentType(exists ? .password : .newPassword)
                        .frame(minHeight: 44)
                        .contentShape(.rect)
                        .onChange(of: password) { errors = [:] }
                    if let e = errors["password"] { Text(e).font(.notesSmall).foregroundStyle(Color.errInk) }
                    if !exists {
                        SecureField("Confirm password", text: $confirm)
                            .textContentType(.newPassword)
                            .frame(minHeight: 44)
                            .contentShape(.rect)
                            .onChange(of: confirm) { errors["confirm"] = nil }
                        if let e = errors["confirm"] { Text(e).font(.notesSmall).foregroundStyle(Color.errInk) }
                    }
                } footer: {
                    Text(
                        exists
                            ? "Locked notes share one lock password, made the first time a note was locked on any of your devices. Enter it to lock this note."
                            : "Locked notes are encrypted on your devices; the server can’t read them. Every locked note uses this password. If you forget it, those notes can’t be recovered."
                    )
                    .font(.notesSmall).foregroundStyle(Color.ink2)
                }
                if let e = errors["form"] {
                    Text(e).font(.notesSmall).foregroundStyle(Color.errInk)
                }
            }
            .font(.notesBody)
            .scrollContentBackground(.hidden)
            .background(Color.notesBg)
            .navigationTitle(exists ? "Lock this note" : "Create a lock password")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .cancel) { dismiss() }.disabled(busy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if busy {
                        ProgressView().accessibilityLabel(exists ? "Unlocking…" : "Creating…")
                    } else {
                        Button(exists ? "Lock" : "Create and lock", systemImage: "lock", action: submit)
                            .buttonStyle(.glassProminent)
                    }
                }
            }
        }
        .tint(Color(Palette.link))
        .interactiveDismissDisabled(busy)
    }

    private func submit() {
        if exists {
            busy = true
            Task {
                let ok = await locks.unlock(password)
                busy = false
                if ok {
                    dismiss()
                    done()
                } else {
                    errors = ["password": "Wrong password."]
                }
            }
            return
        }
        var found: [String: String] = [:]
        if password.count < minLockPassword {
            found["password"] = "Use at least \(minLockPassword) characters."
        }
        if confirm != password { found["confirm"] = "The passwords don’t match." }
        errors = found
        guard found.isEmpty else { return }
        busy = true
        Task {
            let outcome = await locks.setup(password)
            busy = false
            if outcome == .done {
                dismiss()
                done()
            } else {
                errors = ["form": outcome.message ?? ""]
            }
        }
    }
}

/// Covers a locked note while the app isn't in front, so the app switcher's snapshot doesn't show it.
struct PrivacyCover: View {
    var body: some View {
        ZStack {
            Color.notesBg
            Image(systemName: "lock.fill").font(.system(size: 34)).foregroundStyle(Color.accentDeep)
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}

/// The lock sheet to show: unlock with the existing password, or create one.
struct LockPrompt: Identifiable {
    var exists: Bool

    var id: Bool { exists }
}
