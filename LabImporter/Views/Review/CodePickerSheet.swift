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
    @State private var backgroundColors: [Color] = []
    @FocusState private var searchFocused: Bool

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
                    .listRowBackground(Rectangle().fill(.ultraThinMaterial))
                }
            } header: {
                if query.isEmpty {
                    Text("Common tests")
                } else {
                    Text("Matches")
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background { CategoryBackground(colors: backgroundColors) }
        .overlay {
            if loincResults.isEmpty && !query.isEmpty {
                ContentUnavailableView.search(text: query)
            }
        }
        .searchable(text: $query, prompt: Text("Search lab tests"))
        .searchFocused($searchFocused)
        .onAppear { searchFocused = true }
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
            if current == query {
                loincResults = found
                backgroundColors = Self.washColors(for: found)
            }
        }
    }

    // Up to three distinct category colors from the current results, mirroring the
    // Dashboard/History wash so the picker shares the app's color system. Computed
    // once per result set (not on every body pass) and stored in `backgroundColors`.
    private static func washColors(for terms: [LoincTerm]) -> [Color] {
        var seen = Set<LabCategory>()
        var result: [Color] = []
        for term in terms {
            let category = LabCategory.forCode(term.code)
            if seen.insert(category).inserted { result.append(category.color) }
            if result.count == 3 { break }
        }
        return result
    }

    private func row(rowCode: String, title: String, subtitle: String?, action: @escaping () -> Void) -> some View {
        let category = LabCategory.forCode(rowCode)
        let isSelected = code.uppercased() == rowCode.uppercased()
        return Button(action: action) {
            HStack(spacing: 14) {
                CategoryIcon(color: category.color)

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
                Spacer(minLength: 0)
                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(category.color)
                }
            }
            .padding(.vertical, 2)
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

// MARK: - CategoryIcon

/// A category-tinted gradient disc with a test-tube glyph — the same rounded,
/// shadowed icon used by `LoincTermDetailView`'s header and the History rows, so
/// lab tests read consistently wherever they're listed.
struct CategoryIcon: View {
    let color: Color
    var size: CGFloat = 38

    var body: some View {
        ZStack {
            Circle()
                .fill(
                    LinearGradient(
                        colors: [color, color.opacity(0.65)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            Image(systemName: "testtube.2")
                .font(.system(size: size * 0.42, weight: .semibold))
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .shadow(color: color.opacity(0.35), radius: 4, x: 0, y: 2)
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
