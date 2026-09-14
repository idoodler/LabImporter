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
                    .foregroundStyle(.secondary)
            }
        } else {
            Section {
                ForEach(allCodes.sorted { $0.name < $1.name }) { item in
                    Toggle(isOn: allowedBinding(for: item.code)) {
                        HStack(spacing: 12) {
                            Circle()
                                .fill(LabCategory.forCode(item.code).color.gradient)
                                .frame(width: 9, height: 9)
                            Text(LabMapping.displayName(for: item.code))
                        }
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
            set: { isOn in
                var codes = Set(prefs.allowedCodes)
                if isOn { codes.insert(code) } else { codes.remove(code) }
                prefs.allowedCodes = Array(codes)
            }
        )
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
