import SwiftUI

/// Presented right after a successful save when Siri is on in Selected-Values
/// mode and the report introduced tracked values Siri has never been asked
/// about (`Array<LabValue>.undecidedSiriCodes`). Same per-value toggle
/// language as `SiriAccessEditor`'s list, but scoped to just the new codes so
/// the "nice experience" is a short, one-time ask rather than a trip to
/// Settings. Every code shown here is marked decided as soon as the sheet
/// appears — before the user touches anything — so a value is never asked
/// about twice no matter how the sheet is dismissed (Done or a swipe), and so
/// the presenter's own dismiss handler (which re-checks for undecided codes)
/// never races this view's own bookkeeping. `SiriAccessEditor` remains where
/// a decision can be changed later.
struct NewValuesSiriPromptView: View {
    let codes: [CodeName]
    @Binding var prefs: SiriExposurePreferences

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(codes.sorted { $0.name < $1.name }) { item in
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
                    Text("You just tracked new values Siri hasn't been asked about. Turn on any you'd like Siri to read back.")
                } footer: {
                    Text("Off by default. Turn on a value to let Siri read back its latest reading when you ask.")
                }
            }
            .navigationTitle("Let Siri Answer New Values?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        // Marked decided immediately, not on dismiss — see the type doc for why.
        .onAppear { prefs.markDecided(codes.map(\.code)) }
    }

    private func allowedBinding(for code: String) -> Binding<Bool> {
        Binding(
            get: { prefs.allowedSet.contains(code) },
            set: { prefs.setAllowed($0, for: code) }
        )
    }
}

// MARK: - Presentation

extension View {
    /// Presents `NewValuesSiriPromptView` for as long as `codes` is non-empty,
    /// clearing it and running `onDismiss` once the sheet closes, however it
    /// closes (its "Done" button or an interactive swipe).
    func promptsForNewSiriValues(
        codes: Binding<[CodeName]>,
        prefs: Binding<SiriExposurePreferences>,
        onDismiss: @escaping () -> Void
    ) -> some View {
        sheet(isPresented: Binding(
            get: { !codes.wrappedValue.isEmpty },
            set: { isPresented in
                guard !isPresented else { return }
                codes.wrappedValue = []
                onDismiss()
            }
        )) {
            NewValuesSiriPromptView(codes: codes.wrappedValue, prefs: prefs)
        }
    }
}

// MARK: - Previews

#if DEBUG
#Preview("Populated") {
    @Previewable @State var prefs = SiriExposurePreferences()
    NewValuesSiriPromptView(codes: CodeName.sampleCodes, prefs: $prefs)
}

#Preview("Single Value") {
    @Previewable @State var prefs = SiriExposurePreferences()
    NewValuesSiriPromptView(codes: Array(CodeName.sampleCodes.prefix(1)), prefs: $prefs)
}

#Preview("Dark") {
    @Previewable @State var prefs = SiriExposurePreferences()
    NewValuesSiriPromptView(codes: CodeName.sampleCodes, prefs: $prefs)
        .preferredColorScheme(.dark)
}
#endif
