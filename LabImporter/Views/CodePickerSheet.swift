import SwiftUI

// MARK: - AddCodePickerPage
// Pushed via NavigationLink inside addValueSheet's NavigationStack.
// dismiss() pops back to the form; name is auto-filled only when empty.

struct AddCodePickerPage: View {
    @Binding var code: String
    @Binding var name: String
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        codeList(
            query: $query,
            currentCode: code,
            onSelect: { entry in
                code = entry.loinc
                if name.isEmpty {
                    name = LoincDirectory.shared.displayName(for: entry)
                }
                dismiss()
            }
        )
        .navigationTitle("Lab Test")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - CodePickerSheet

struct CodePickerSheet: View {
    @Binding var code: String
    @Binding var name: String
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        NavigationStack {
            codeList(
                query: $query,
                currentCode: code,
                onSelect: { entry in
                    code = entry.loinc
                    name = LoincDirectory.shared.displayName(for: entry)
                    dismiss()
                }
            )
            .navigationTitle("Lab Test")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

@ViewBuilder
private func codeList(
    query: Binding<String>,
    currentCode: String,
    onSelect: @escaping (LoincDirectory.Entry) -> Void
) -> some View {
    let trimmed = query.wrappedValue.trimmingCharacters(in: .whitespacesAndNewlines)
    let results: [LoincDirectory.Entry] = trimmed.isEmpty
        ? []
        : LoincDirectory.shared.search(trimmed, limit: 100)

    List {
        if trimmed.isEmpty {
            Text("Start typing to search LOINC codes.")
                .foregroundStyle(.secondary)
        } else if results.isEmpty {
            Text(LoincDirectory.shared.isAvailable
                 ? String(localized: "No matches")
                 : String(localized: "LOINC database not loaded yet."))
                .foregroundStyle(.secondary)
        } else {
            ForEach(results) { entry in
                Button {
                    onSelect(entry)
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(LoincDirectory.shared.displayName(for: entry))
                                .lineLimit(2)
                            Text(entry.loinc)
                                .font(.caption2.monospaced())
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if currentCode == entry.loinc {
                            Image(systemName: "checkmark")
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                }
                .foregroundStyle(.primary)
            }
        }
    }
    .searchable(text: query, prompt: Text("Search LOINC code or name"))
}
