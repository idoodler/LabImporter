import SwiftUI

extension View {
    /// The shared "Rename" alert that sets a nickname for a LOINC `code`
    /// — reused by the Sort & Visibility editor, the trends screen, and the LOINC
    /// detail screen so the wording and behaviour stay identical everywhere.
    ///
    /// Present it by setting `code` to the LOINC code (seed `draft` first with the
    /// current nickname so the field shows it for editing); dismissing clears
    /// `code`. The chosen name is written to `prefs.nicknames` and applied across
    /// the app via `LabMapping.displayName`. Saving a blank name (or "Reset to
    /// Default") clears the nickname and falls back to the catalog name.
    func renameLabAlert(code: Binding<String?>,
                        draft: Binding<String>,
                        prefs: Binding<LabDisplayPreferences>) -> some View {
        let isPresented = Binding(
            get: { code.wrappedValue != nil },
            set: { if !$0 { code.wrappedValue = nil } }
        )
        return alert("Rename", isPresented: isPresented, presenting: code.wrappedValue) { code in
            TextField(LabMapping.catalogName(for: code), text: draft)
            Button("Save") { prefs.wrappedValue.setNickname(draft.wrappedValue, for: code) }
            if prefs.wrappedValue.nickname(for: code) != nil {
                Button("Reset to Default", role: .destructive) {
                    prefs.wrappedValue.setNickname(nil, for: code)
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: { code in
            Text("Choose a nickname to show everywhere instead of “\(LabMapping.catalogName(for: code))”.")
        }
    }
}
