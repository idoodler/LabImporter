import LabImporterCore
import SwiftUI

// MARK: - LabSortEditor

/// Per-metric management for the dashboard: reorder, pin, hide, and rename the
/// LOINC codes the user has data for. Nicknames are written straight into the
/// bound `LabDisplayPreferences` (so they persist and roam via iCloud) and are
/// applied everywhere through `LabMapping.displayName(for:)`.
struct LabSortEditor: View {
    @Binding var prefs: LabDisplayPreferences
    let allCodes: [CodeName]

    /// Pinned / visible / hidden and the edits on them — the shared rules
    /// (LabImporterCore `SortEditorState`), identical on Android.
    @State private var state: LabImporterCore.SortEditorState
    /// The LOINC code pushed onto the detail screen, where the user renames the
    /// metric and sets its reference range. `nil` until they tap a row's info
    /// button.
    @State private var detailCode: String?

    init(prefs: Binding<LabDisplayPreferences>, allCodes: [CodeName]) {
        _prefs = prefs
        self.allCodes = allCodes
        SharedCore.start()
        _state = State(initialValue: LabImporterCore.SortEditorState.companion.from(
            preferences: prefs.wrappedValue.shared, all: allCodes.map(\.code)
        ))
    }

    var body: some View {
        List {
            pinnedSection
            visibleSection
            hiddenSection
        }
        .environment(\.editMode, .constant(.active))
        .navigationTitle("Sort & Visibility")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: state) { save() }
        .navigationDestination(item: $detailCode) { code in
            if let term = LoincDirectory.shared.term(for: code) {
                LoincTermDetailView(term: term)
            }
        }
    }

    /// Opens the LOINC detail screen for `code`, where renaming and reference
    /// range editing live. Codes the catalog doesn't know have no detail screen,
    /// so the info button is hidden for them (see `row(for:)`).
    private func showDetail(_ code: String) {
        detailCode = code
    }

    private func items(_ codes: [String]) -> [CodeName] {
        codes.compactMap { code in allCodes.first { $0.code == code } }
    }

    private var pinnedItems: [CodeName] { items(state.pinnedCodes) }
    private var unpinnedItems: [CodeName] { items(state.unpinnedCodes) }

    @ViewBuilder
    private var pinnedSection: some View {
        if !pinnedItems.isEmpty {
            Section("Pinned") {
                ForEach(pinnedItems) { item in row(for: item) }
                    .onMove { from, dest in move(inPinned: true, from: from, destination: dest) }
            }
        }
    }

    @ViewBuilder
    private var visibleSection: some View {
        Section("Visible") {
            ForEach(unpinnedItems) { item in row(for: item) }
                .onMove { from, dest in move(inPinned: false, from: from, destination: dest) }
        }
    }

    private func row(for item: CodeName) -> some View {
        HStack(spacing: 12) {
            Button { togglePin(item.code) } label: {
                Image(systemName: state.pinned.contains(item.code) ? "pin.fill" : "pin")
                    .foregroundStyle(state.pinned.contains(item.code) ? Color.yellow : Color.secondary)
                    .frame(width: 20)
            }
            .buttonStyle(.plain)
            Circle()
                .fill(LabCategory.forCode(item.code).color.gradient)
                .frame(width: 9, height: 9)
            // Resolve live (not the captured `item.name`) so a rename updates the
            // row immediately; show the catalog default beneath a nickname.
            VStack(alignment: .leading, spacing: 1) {
                Text(LabMapping.displayName(for: item.code))
                if prefs.nickname(for: item.code) != nil {
                    Text(LabMapping.catalogName(for: item.code))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                if let range = prefs.referenceRange(for: item.code) {
                    Label(range.formatted(), systemImage: "ruler")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            // The catalog detail screen hosts renaming and reference-range
            // editing; only LOINC codes the catalog knows have one.
            if LoincDirectory.shared.term(for: item.code) != nil {
                Button { showDetail(item.code) } label: {
                    Image(systemName: "info.circle")
                        .foregroundStyle(Color.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Details")
            }
            Button { hideCode(item.code) } label: {
                Image(systemName: "eye.slash")
                    .foregroundStyle(Color.secondary)
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder
    private var hiddenSection: some View {
        let hiddenItems = items(state.hiddenCodes)
        if !hiddenItems.isEmpty {
            Section("Hidden") {
                ForEach(hiddenItems) { item in
                    HStack(spacing: 12) {
                        Circle()
                            .fill(LabCategory.forCode(item.code).color.opacity(0.4))
                            .frame(width: 9, height: 9)
                        Text(item.name)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button("Restore") { restoreCode(item.code) }
                            .buttonStyle(.plain)
                            .foregroundStyle(Color.accentColor)
                            .fontWeight(.medium)
                    }
                    .moveDisabled(true)
                }
            }
        }
    }

    private func togglePin(_ code: String) { state = state.togglePin(code: code) }

    private func hideCode(_ code: String) { state = state.hide(code: code) }

    private func restoreCode(_ code: String) { state = state.restore(code: code) }

    /// A drag within a section (List moves one row at a time).
    private func move(inPinned: Bool, from: IndexSet, destination: Int) {
        guard let source = from.first else { return }
        state = state.move(inPinned: inPinned, from: Int32(source), to: Int32(destination))
    }

    private func save() {
        prefs = LabDisplayPreferences(shared: state.applyTo(preferences: prefs.shared))
    }
}

// MARK: - Previews

#if DEBUG
#Preview("Sort & Visibility") {
    @Previewable @State var prefs = LabDisplayPreferences()
    NavigationStack {
        LabSortEditor(prefs: $prefs, allCodes: CodeName.sampleCodes)
    }
}
#endif
