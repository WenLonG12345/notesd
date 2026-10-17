import NotesCore
import SwiftUI

/// Email through Resend: the API key (never shown again once saved) and the sender address.
struct EmailSection: View {
    @Environment(AppGraph.self) private var graph
    @State private var apiKey = ""
    @State private var from: String?
    @State private var errors: [String: String] = [:]
    @State private var message: String?
    @State private var busy = false

    private var saved: EmailSettings? { graph.email.settings }
    private var configured: Bool { saved?.configured == true }

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                SecureField(
                    "Resend API key", text: $apiKey,
                    prompt: Text(verbatim: configured ? "Saved · …\(saved?.keyHint ?? "")" : "Resend API key (re_…)")
                )
                .font(.notesMono)
                .frame(minHeight: 44)
                .contentShape(.rect)
                .accessibilityLabel("Resend API key")
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onChange(of: apiKey) { errors["apiKey"] = nil }
                if let e = errors["apiKey"] { FieldError(e) }
            }
            VStack(alignment: .leading, spacing: 4) {
                TextField(
                    "From", text: Binding(get: { from ?? saved?.from ?? "" }, set: { from = $0 }),
                    prompt: Text(verbatim: "Your Name <notes@example.com>")
                )
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .frame(minHeight: 44)
                .contentShape(.rect)
                .onChange(of: from) { errors["from"] = nil }
                .accessibilityLabel("From")
                if let e = errors["from"] { FieldError(e) }
            }
            if let message {
                Text(message).font(.notesSmall).foregroundStyle(Color.ink2)
            }
            Button(busy ? "Saving…" : "Save", action: save).disabled(busy)
            if configured {
                Button("Remove", role: .destructive, action: remove).disabled(busy).foregroundStyle(Color.danger)
            }
        } header: {
            SectionHeader("Email")
        } footer: {
            Text("Email notes through Resend. Use an API key with sending access and an address on a verified domain.")
                .font(.notesSmall).foregroundStyle(Color.ink2)
        }
        .disabled(busy)
        .task { await graph.email.refresh() }
    }

    private func save() {
        let fromValue = (from ?? saved?.from ?? "").trimmed
        var found: [String: String] = [:]
        if !configured && apiKey.trimmed.isEmpty { found["apiKey"] = "Paste your Resend API key." }
        if fromValue.isEmpty { found["from"] = "Enter the address emails come from." }
        errors = found
        message = nil
        if !found.isEmpty { return }
        busy = true
        Task {
            let key = apiKey.trimmed
            let result = await graph.email.save(from: fromValue, apiKey: key.isEmpty ? nil : key)
            busy = false
            if case .ok = result {
                apiKey = ""
                from = nil
                message = "Saved. Choose Email… from a note’s ••• menu."
            } else {
                failed(result)
            }
        }
    }

    private func remove() {
        busy = true
        message = nil
        Task {
            let result = await graph.email.remove()
            busy = false
            if case .ok = result {
                apiKey = ""
                from = nil
                errors = [:]
            } else {
                failed(result)
            }
        }
    }

    private func failed<T>(_ result: ApiResult<T>) {
        switch result {
        case .rejected(400, _, let text, let field?) where field == "apiKey" || field == "from":
            let s = text.prefix(1).uppercased() + text.dropFirst()
            errors = [field: s.hasSuffix(".") ? s : s + "."]
        case .unreachable: message = "Can’t reach the server."
        default: message = "Something went wrong. Try again."
        }
    }
}

private struct FieldError: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.notesSmall).foregroundStyle(Color.errInk)
    }
}
