import SwiftUI

// MARK: - LabTestPickerList
// Shared lab-test chooser: live search over the full bundled LOINC catalog
// (LoincDirectory). With no query it shows the most commonly ordered tests.
// Selecting a row stores the raw LOINC number as the value's code; LabMapping
// resolves it everywhere.

// Catalog terms whose user-defined alias (custom display name) contains the
// query, so a manually renamed test stays findable by the name the user gave it.
// Returns at most the codes that have an alias set, resolved back to full terms.
private func aliasMatches(_ query: String) -> [LoincTerm] {
    let needle = query.trimmingCharacters(in: .whitespaces).lowercased()
    guard !needle.isEmpty else { return [] }
    return LabDisplayPreferences.current().customNames.compactMap { code, alias in
        alias.lowercased().contains(needle) ? LoincDirectory.shared.term(for: code) : nil
    }
}

private struct LabTestPickerList: View {
    @Binding var code: String
    @Binding var name: String
    /// When true (the "add" flow) the name is only auto-filled if still empty.
    let preserveExistingName: Bool
    let onSelect: () -> Void

    @State private var query = ""
    @State private var loincResults: [LoincTerm] = []

    var body: some View {
        List {
            Section {
                ForEach(loincResults) { term in
                    // Surface the user's alias (custom display name) when they've
                    // renamed this code, so the picker matches what they see
                    // everywhere else; the catalog name then becomes the subtitle.
                    let alias = LabDisplayPreferences.current().customName(for: term.code)
                    row(rowCode: term.code,
                        title: alias ?? term.name,
                        subtitle: alias != nil ? term.name : term.description) {
                        select(term.code, alias ?? term.name)
                    }
                }
            } header: {
                if query.isEmpty {
                    Text("Common tests")
                } else {
                    Text("Matches")
                }
            }
        }
        .searchable(text: $query, prompt: Text("Search lab tests"))
        .task(id: query) {
            let current = query
            let found = await Task.detached(priority: .userInitiated) { () -> [LoincTerm] in
                // Alias matches rank ahead of the catalog's full-text matches so a
                // renamed test is findable by the name the user gave it.
                let aliasHits = aliasMatches(current)
                let catalog = LoincDirectory.shared.search(current)
                var seen = Set(aliasHits.map(\.code))
                return aliasHits + catalog.filter { seen.insert($0.code).inserted }
            }.value
            if current == query { loincResults = found }
        }
    }

    private func row(rowCode: String, title: String, subtitle: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                    Text(rowCode)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .textCase(.uppercase)
                }
                Spacer()
                if code.uppercased() == rowCode.uppercased() {
                    Image(systemName: "checkmark")
                        .foregroundStyle(Color.accentColor)
                }
            }
        }
        .foregroundStyle(.primary)
    }

    private func select(_ newCode: String, _ newName: String) {
        code = newCode
        if !preserveExistingName || name.isEmpty {
            name = newName
        }
        onSelect()
    }
}

// MARK: - AddCodePickerPage
// Pushed via NavigationLink inside addValueSheet's NavigationStack.
// dismiss() pops back to the form; name is auto-filled only when empty.

struct AddCodePickerPage: View {
    @Binding var code: String
    @Binding var name: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        LabTestPickerList(code: $code, name: $name, preserveExistingName: true) { dismiss() }
            .navigationTitle("Lab Test")
            .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - CodePickerSheet

struct CodePickerSheet: View {
    @Binding var code: String
    @Binding var name: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            LabTestPickerList(code: $code, name: $name, preserveExistingName: false) { dismiss() }
                .navigationTitle("Lab Test")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button(role: .close) { dismiss() }
                    }
                }
        }
    }
}

// MARK: - Preview

#Preview {
    @Previewable @State var code = "2160-0"
    @Previewable @State var name = "Creatinine"
    CodePickerSheet(code: $code, name: $name)
}
