import SwiftUI

extension View {
    /// The shared "Rename" alert that sets a custom display name for a LOINC `code`
    /// — reused by the Sort & Visibility editor, the trends screen, and the LOINC
    /// detail screen so the wording and behaviour stay identical everywhere.
    ///
    /// Present it by setting `code` to the LOINC code (seed `draft` first with the
    /// current custom name so the field shows it for editing); dismissing clears
    /// `code`. The chosen name is written to `prefs.customNames` and applied across
    /// the app via `LabMapping.displayName`. Saving a blank name (or "Reset to
    /// Default") clears the override and falls back to the catalog name.
    func renameLabAlert(code: Binding<String?>,
                        draft: Binding<String>,
                        prefs: Binding<LabDisplayPreferences>) -> some View {
        let isPresented = Binding(
            get: { code.wrappedValue != nil },
            set: { if !$0 { code.wrappedValue = nil } }
        )
        return alert("Rename", isPresented: isPresented, presenting: code.wrappedValue) { code in
            TextField(LabMapping.catalogName(for: code), text: draft)
            Button("Save") { prefs.wrappedValue.setCustomName(draft.wrappedValue, for: code) }
            if prefs.wrappedValue.customName(for: code) != nil {
                Button("Reset to Default", role: .destructive) {
                    prefs.wrappedValue.setCustomName(nil, for: code)
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: { code in
            Text("Choose a short name to show everywhere instead of “\(LabMapping.catalogName(for: code))”.")
        }
    }
}
