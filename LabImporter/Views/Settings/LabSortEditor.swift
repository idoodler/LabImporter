import SwiftUI

// MARK: - LabSortEditor

/// Per-metric management for the dashboard: reorder, pin, hide, and rename the
/// LOINC codes the user has data for. Nicknames are written straight into the
/// bound `LabDisplayPreferences` (so they persist and roam via iCloud) and are
/// applied everywhere through `LabMapping.displayName(for:)`.
struct LabSortEditor: View {
    @Binding var prefs: LabDisplayPreferences
    let allCodes: [CodeName]

    @State private var visibleOrdered: [CodeName]
    @State private var hiddenSet: Set<String>
    @State private var pinnedSet: Set<String>
    /// The LOINC code pushed onto the detail screen, where the user renames the
    /// metric and sets its reference range. `nil` until they tap a row's info
    /// button.
    @State private var detailCode: String?

    init(prefs: Binding<LabDisplayPreferences>, allCodes: [CodeName]) {
        _prefs = prefs
        self.allCodes = allCodes

        let currentPrefs = prefs.wrappedValue
        let hidden = currentPrefs.hiddenSet

        var seen = Set<String>()
        var initial: [CodeName] = []
        for code in currentPrefs.orderedCodes where !hidden.contains(code) {
            guard let item = allCodes.first(where: { $0.code == code }),
                  seen.insert(code).inserted else { continue }
            initial.append(item)
        }
        for item in allCodes where !hidden.contains(item.code) && seen.insert(item.code).inserted {
            initial.append(item)
        }

        _visibleOrdered = State(initialValue: initial)
        _hiddenSet = State(initialValue: hidden)
        _pinnedSet = State(initialValue: currentPrefs.pinnedSet)
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
        .onChange(of: visibleOrdered.map(\.code)) { save() }
        .onChange(of: hiddenSet) { save() }
        .onChange(of: pinnedSet) { save() }
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

    /// Visible codes split by pin state. Both partitions preserve their relative
    /// order within `visibleOrdered`, which is normalized pinned-first so the
    /// editor order matches how the dashboard groups the cards.
    private var pinnedItems: [CodeName] { visibleOrdered.filter { pinnedSet.contains($0.code) } }
    private var unpinnedItems: [CodeName] { visibleOrdered.filter { !pinnedSet.contains($0.code) } }

    @ViewBuilder
    private var pinnedSection: some View {
        if !pinnedItems.isEmpty {
            Section("Pinned") {
                ForEach(pinnedItems) { item in row(for: item) }
                    .onMove { from, dest in
                        var pinned = pinnedItems
                        pinned.move(fromOffsets: from, toOffset: dest)
                        visibleOrdered = pinned + unpinnedItems
                    }
            }
        }
    }

    @ViewBuilder
    private var visibleSection: some View {
        Section("Visible") {
            ForEach(unpinnedItems) { item in row(for: item) }
                .onMove { from, dest in
                    var unpinned = unpinnedItems
                    unpinned.move(fromOffsets: from, toOffset: dest)
                    visibleOrdered = pinnedItems + unpinned
                }
        }
    }

    private func row(for item: CodeName) -> some View {
        HStack(spacing: 12) {
            Button { togglePin(item.code) } label: {
                Image(systemName: pinnedSet.contains(item.code) ? "pin.fill" : "pin")
                    .foregroundStyle(pinnedSet.contains(item.code) ? Color.yellow : Color.secondary)
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
        let hiddenItems = allCodes.filter { hiddenSet.contains($0.code) }
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

    private func togglePin(_ code: String) {
        if pinnedSet.contains(code) {
            pinnedSet.remove(code)
        } else {
            pinnedSet.insert(code)
        }
        // Keep the backing order pinned-first so a toggled row moves between the
        // two sections in place, matching the dashboard's grouping.
        visibleOrdered = pinnedItems + unpinnedItems
    }

    private func hideCode(_ code: String) {
        hiddenSet.insert(code)
        visibleOrdered.removeAll { $0.code == code }
    }

    private func restoreCode(_ code: String) {
        hiddenSet.remove(code)
        if let item = allCodes.first(where: { $0.code == code }) {
            visibleOrdered.append(item)
        }
    }

    private func save() {
        let hiddenOrdered = allCodes.filter { hiddenSet.contains($0.code) }
        var updated = prefs
        updated.orderedCodes = visibleOrdered.map(\.code) + hiddenOrdered.map(\.code)
        updated.pinnedCodes = Array(pinnedSet)
        updated.hiddenCodes = Array(hiddenSet)
        prefs = updated
    }
}

// MARK: - Previews

#Preview("Sort & Visibility") {
    @Previewable @State var prefs = LabDisplayPreferences()
    NavigationStack {
        LabSortEditor(prefs: $prefs, allCodes: CodeName.sampleCodes)
    }
}
