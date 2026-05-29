import SwiftUI

// MARK: - LabTestPickerList
// Shared lab-test chooser: live search over the full bundled LOINC catalog
// (LoincDirectory). With no query it shows the most commonly ordered tests.
// Selecting a row stores the raw LOINC number as the value's code; LabMapping
// resolves it everywhere.

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
            Section(query.isEmpty ? Text("Common tests") : Text("Matches")) {
                ForEach(loincResults) { term in
                    row(rowCode: term.code, title: term.name, subtitle: term.description) {
                        select(term.code, term.name)
                    }
                }
            }
        }
        .searchable(text: $query, prompt: Text("Search lab tests"))
        .task(id: query) {
            let current = query
            let found = await Task.detached(priority: .userInitiated) {
                LoincDirectory.shared.search(current)
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
                        Button("Cancel") { dismiss() }
                    }
                }
        }
    }
}
