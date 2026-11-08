import SwiftUI

/// Per-metric and per-capability control over what Siri can do with
/// LabImporter, reached from Settings ("Manage Siri Access"). Every value
/// starts unchecked — turning on Siri access in Settings or onboarding never
/// exposes a reading by itself; the user opts in here, one metric at a time.
struct SiriAccessEditor: View {
    @Binding var prefs: SiriExposurePreferences
    let allCodes: [CodeName]

    var body: some View {
        List {
            capabilitiesSection
            valuesSection
        }
        .readableListText()
        .navigationTitle("Siri & Shortcuts")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Capabilities

    private var capabilitiesSection: some View {
        Section {
            Toggle(isOn: $prefs.allowScanShortcut) {
                SettingsRowLabel("Start a Scan via Siri", systemImage: "doc.viewfinder", color: .blue)
            }
            Toggle(isOn: $prefs.allowOnScreenAwareness) {
                SettingsRowLabel("On-Screen Awareness", systemImage: "eye", color: .teal)
            }
            Toggle(isOn: $prefs.allowKnowledgeIndexing) {
                SettingsRowLabel("Add to Siri Suggestions", systemImage: "sparkles", color: .orange)
            }
            Toggle(isOn: $prefs.allowInAppSearch) {
                SettingsRowLabel("Search Your Values", systemImage: "magnifyingglass", color: .green)
            }
        } footer: {
            VStack(alignment: .leading, spacing: 8) {
                Text("Say “Scan a lab report” to Siri to open the scanner — never touches a lab value.")
                Text("While reviewing a report, let Siri see the values shown on screen — useful for hands-free corrections.")
                Text("Let Siri suggest values you've allowed — names only, never a reading.")
                Text("Let Siri or Spotlight's search jump straight to a tracked value's trend.")
            }
        }
    }

    // MARK: - Per-value access

    private var accessModeSection: some View {
        Section {
            Picker("Value Access", selection: $prefs.allowAllValues) {
                Text("All Values").tag(true)
                Text("Selected Values").tag(false)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .accessibilityLabel("Value Access")
        } footer: {
            Text(prefs.allowAllValues
                 ? "Siri may read back every value you track — including any you add later."
                 : "Choose exactly which values Siri may read back below.")
        }
    }

    @ViewBuilder
    private var valuesSection: some View {
        accessModeSection

        if prefs.allowAllValues {
            EmptyView()
        } else if allCodes.isEmpty {
            Section {
                Text("Import a report to choose which values Siri can access.")
                    .foregroundStyle(.textSecondary)
            }
        } else {
            Section {
                let sharingAName = allCodes.codesSharingAName
                ForEach(allCodes.sorted { $0.name < $1.name }) { item in
                    Toggle(isOn: allowedBinding(for: item.code)) {
                        SiriValueLabel(code: item.code, sharesItsName: sharingAName.contains(item.code))
                    }
                }
            } header: {
                Text("Values Siri Can Answer")
            } footer: {
                Text("Off by default. Turn on a value to let Siri read back its latest reading when you ask.")
            }
        }
    }

    private func allowedBinding(for code: String) -> Binding<Bool> {
        Binding(
            get: { prefs.allowedSet.contains(code) },
            set: { prefs.setAllowed($0, for: code) }
        )
    }
}

/// A value's row in Siri's opt-in lists. Plain names can cover several terms (mass and moles,
/// serum and whole blood), and turning one on is a privacy decision about that term alone: a
/// name that isn't unique in the list carries the full catalog label too.
struct SiriValueLabel: View {
    let code: String
    let sharesItsName: Bool

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(LabCategory.forCode(code).color.gradient)
                .frame(width: 9, height: 9)
            VStack(alignment: .leading, spacing: 1) {
                Text(LabMapping.displayName(for: code))
                if sharesItsName {
                    Text(LabMapping.catalogName(for: code))
                        .font(.caption2)
                        .foregroundStyle(.textSecondary)
                }
            }
        }
    }
}

extension [CodeName] {
    /// The codes whose name, as a row shows it, another code here also has.
    var codesSharingAName: Set<String> {
        let byName = Dictionary(grouping: map(\.code)) { LabMapping.displayName(for: $0) }
        return Set(byName.values.filter { $0.count > 1 }.joined())
    }
}

// MARK: - Previews

#if DEBUG
#Preview("Populated") {
    @Previewable @State var prefs = SiriExposurePreferences()
    NavigationStack {
        SiriAccessEditor(prefs: $prefs, allCodes: CodeName.sampleCodes)
    }
}

#Preview("Two Values Sharing a Name") {
    @Previewable @State var prefs = SiriExposurePreferences()
    NavigationStack {
        SiriAccessEditor(prefs: $prefs, allCodes: CodeName.sampleCodesSharingAName)
    }
}

#Preview("Empty") {
    @Previewable @State var prefs = SiriExposurePreferences()
    NavigationStack {
        SiriAccessEditor(prefs: $prefs, allCodes: [])
    }
}

#Preview("All Values") {
    @Previewable @State var prefs: SiriExposurePreferences = {
        var prefs = SiriExposurePreferences()
        prefs.allowAllValues = true
        return prefs
    }()
    NavigationStack {
        SiriAccessEditor(prefs: $prefs, allCodes: CodeName.sampleCodes)
    }
}

#Preview("Dark") {
    @Previewable @State var prefs = SiriExposurePreferences()
    NavigationStack {
        SiriAccessEditor(prefs: $prefs, allCodes: CodeName.sampleCodes)
    }
    .preferredColorScheme(.dark)
}
#endif
