import NotesCore
import SwiftUI

/// Addresses for the note, then Send; `send` syncs the note and asks the server to email it.
struct EmailSheet: View {
    let from: String?
    let send: (_ to: [String]) async -> EmailOutcome
    @Environment(\.dismiss) private var dismiss
    @State private var to = ""
    @State private var error: String?
    @State private var busy = false
    @State private var sentTo: [String]?
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                if let sentTo {
                    Text("Sent to \(sentTo.joined(separator: ", ")).")
                        .foregroundStyle(Color.ink2)
                        .accessibilityAddTraits(.updatesFrequently)
                } else {
                    Section {
                        TextField("name@example.com", text: $to)
                            .keyboardType(.emailAddress)
                            .textContentType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .submitLabel(.send)
                            .frame(minHeight: 44)
                            .contentShape(.rect)
                            .focused($focused)
                            .disabled(busy)
                            .onSubmit(submit)
                            .onChange(of: to) { error = nil }
                            .accessibilityLabel("To")
                    } header: {
                        SectionHeader("To")
                    } footer: {
                        VStack(alignment: .leading, spacing: 6) {
                            if let error {
                                Text(error).foregroundStyle(Color.errInk)
                            }
                            Text(hint).foregroundStyle(Color.ink2)
                        }
                        .font(.notesSmall)
                    }
                }
            }
            .font(.notesBody)
            .scrollContentBackground(.hidden)
            .background(Color.notesBg)
            .navigationTitle(sentTo == nil ? "Email this note" : "Sent")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if sentTo != nil {
                    ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
                } else {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(role: .cancel) { dismiss() }.disabled(busy)
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        if busy {
                            ProgressView().accessibilityLabel("Sending…")
                        } else {
                            Button("Send", systemImage: "arrow.up", action: submit)
                                .buttonStyle(.glassProminent)
                        }
                    }
                }
            }
            .onAppear { focused = true }
        }
        // The accent is too light for text; link brown passes contrast, in the bar too.
        .tint(Color(Palette.link))
        .interactiveDismissDisabled(busy)
    }

    private var hint: String {
        [from.map { "From \($0)." }, "Separate addresses with commas."].compactMap { $0 }.joined(separator: " ")
    }

    private func submit() {
        guard !busy else { return }
        let recipients = to.split(whereSeparator: { ",; \n".contains($0) }).map(String.init)
        if recipients.isEmpty {
            error = "Add at least one address."
            return
        }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            switch await send(recipients) {
            case .sent: sentTo = recipients
            case .offline: error = "Can’t reach the server. Email sends from there; try again when online."
            case .notSynced: error = "This note hasn’t reached the server yet. Try again once it’s synced."
            case .failed(let message, _): error = message
            }
        }
    }
}
