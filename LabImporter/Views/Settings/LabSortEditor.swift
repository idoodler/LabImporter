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
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

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
        .readableListText()
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

    @ViewBuilder
    private func row(for item: CodeName) -> some View {
        // The largest text leaves a name beside four controls one letter per line.
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 12) {
                    categoryDot(item)
                    rowName(item)
                }
                HStack(spacing: 20) {
                    pinButton(item)
                    detailButton(item)
                    hideButton(item)
                }
            }
        } else {
            HStack(spacing: 12) {
                pinButton(item)
                categoryDot(item)
                rowName(item)
                Spacer()
                detailButton(item)
                hideButton(item)
            }
        }
    }

    private func categoryDot(_ item: CodeName) -> some View {
        Circle()
            .fill(LabCategory.forCode(item.code).color.gradient)
            .frame(width: 9, height: 9)
    }

    // Resolve live (not the captured `item.name`) so a rename updates the
    // row immediately; show the default name beneath a nickname.
    private func rowName(_ item: CodeName) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(LabMapping.displayName(for: item.code))
            if prefs.nickname(for: item.code) != nil {
                Text(LabMapping.plainName(for: item.code))
                    .font(.caption2)
                    .foregroundStyle(Color.textSecondary)
            }
            if let range = prefs.referenceRange(for: item.code) {
                Label(range.formatted(), systemImage: "ruler")
                    .font(.caption2)
                    .foregroundStyle(Color.textSecondary)
            }
        }
    }

    /// "Glucose, Hide": VoiceOver walks the buttons on their own, so each says whose it is.
    private func spoken(_ action: String, _ item: CodeName) -> Text {
        Text(verbatim: "\(LabMapping.displayName(for: item.code)), \(action)")
    }

    private func pinButton(_ item: CodeName) -> some View {
        let isPinned = state.pinned.contains(item.code)
        return Button { togglePin(item.code) } label: {
            Image(systemName: isPinned ? "pin.fill" : "pin")
                .foregroundStyle(isPinned ? Color.pinnedMark : Color.textSecondary)
        }
        .tapTargetButton()
        .accessibilityLabel(spoken(String(localized: "Pinned"), item))
        .accessibilityAddTraits(isPinned ? .isSelected : [])
    }

    // The catalog detail screen hosts renaming and reference-range
    // editing; only LOINC codes the catalog knows have one.
    @ViewBuilder
    private func detailButton(_ item: CodeName) -> some View {
        if LoincDirectory.shared.term(for: item.code) != nil {
            Button { showDetail(item.code) } label: {
                Image(systemName: "info.circle")
                    .foregroundStyle(Color.textSecondary)
            }
            .tapTargetButton()
            .accessibilityLabel(spoken(String(localized: "Details"), item))
        }
    }

    private func hideButton(_ item: CodeName) -> some View {
        Button { hideCode(item.code) } label: {
            Image(systemName: "eye.slash")
                .foregroundStyle(Color.textSecondary)
        }
        .tapTargetButton()
        .accessibilityLabel(spoken(String(localized: "Hide"), item))
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
                            .foregroundStyle(.textSecondary)
                        Spacer()
                        Button("Restore") { restoreCode(item.code) }
                            .tapTargetButton()
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
