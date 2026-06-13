import SwiftUI

/// Lets the user record conditions or diagnoses — e.g. their diabetes type —
/// that every specialist should take into account when explaining results. It's
/// free text in the user's own words, shared with the on-device chat as
/// background only. It never leaves the device and, like everything in the chat,
/// doesn't replace a professional diagnosis.
struct HealthProfileEditor: View {
    @Binding var healthContext: String
    @Environment(\.dismiss) private var dismiss

    /// Edited locally and committed on Save, so Cancel discards changes.
    @State private var draft: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(
                        "e.g. Type 1 diabetes since 2015, high blood pressure",
                        text: $draft,
                        axis: .vertical
                    )
                    .lineLimit(3...8)
                    .onChange(of: draft) { _, newValue in
                        if newValue.count > 600 { draft = String(newValue.prefix(600)) }
                    }
                } header: {
                    Text("Conditions & Diagnoses")
                } footer: {
                    Text("""
                    Share conditions you'd like every specialist to consider, like your \
                    diabetes type. It stays on your device and helps tailor explanations — \
                    it doesn't replace a diagnosis from a professional.
                    """)
                }
            }
            .navigationTitle("Your Health Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        healthContext = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                        dismiss()
                    }
                }
            }
            .onAppear { draft = healthContext }
        }
    }
}

// MARK: - Previews

#Preview("Empty") {
    HealthProfileEditor(healthContext: .constant(""))
}

#Preview("Filled · Dark") {
    HealthProfileEditor(healthContext: .constant("Type 1 diabetes since 2015. Hashimoto's thyroiditis."))
        .preferredColorScheme(.dark)
}
