import SwiftUI

struct LabOrderEditorView: View {
    let allCodes: [CodeName]

    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @Environment(\.dismiss) private var dismiss

    @State private var visibleOrdered: [CodeName] = []
    @State private var hiddenSet: Set<String> = []
    @State private var pinnedSet: Set<String> = []
    @State private var didLoad = false

    var body: some View {
        List {
            visibleSection
            hiddenSection
        }
        .environment(\.editMode, .constant(.active))
        .navigationTitle("Order & Visibility")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Save") { save(); dismiss() }
                    .fontWeight(.semibold)
            }
        }
        .onAppear { loadInitialState() }
    }

    @ViewBuilder
    private var visibleSection: some View {
        Section("Visible") {
            ForEach(visibleOrdered) { item in
                HStack(spacing: 12) {
                    Button { togglePin(item.code) } label: {
                        Image(systemName: pinnedSet.contains(item.code) ? "pin.fill" : "pin")
                            .foregroundStyle(pinnedSet.contains(item.code) ? Color.yellow : Color.secondary)
                            .frame(width: 20)
                    }
                    .buttonStyle(.plain)
                    Text(item.name)
                    Spacer()
                    Button { hideCode(item.code) } label: {
                        Image(systemName: "eye.slash")
                            .foregroundStyle(Color.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .onMove { from, dest in visibleOrdered.move(fromOffsets: from, toOffset: dest) }
        }
    }

    @ViewBuilder
    private var hiddenSection: some View {
        let hiddenItems = allCodes.filter { hiddenSet.contains($0.code) }
        if !hiddenItems.isEmpty {
            Section("Hidden") {
                ForEach(hiddenItems) { item in
                    HStack {
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

    private func loadInitialState() {
        guard !didLoad else { return }
        didLoad = true
        let hidden = prefs.hiddenSet
        var seen = Set<String>()
        var initial: [CodeName] = []
        for code in prefs.orderedCodes where !hidden.contains(code) {
            guard let item = allCodes.first(where: { $0.code == code }),
                  seen.insert(code).inserted else { continue }
            initial.append(item)
        }
        for item in allCodes where !hidden.contains(item.code) && seen.insert(item.code).inserted {
            initial.append(item)
        }
        visibleOrdered = initial
        hiddenSet = hidden
        pinnedSet = prefs.pinnedSet
    }

    private func togglePin(_ code: String) {
        if pinnedSet.contains(code) {
            pinnedSet.remove(code)
        } else {
            pinnedSet.insert(code)
        }
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
