import SwiftUI

extension View {
    /// The shared "Reference Range" alert that sets a user-defined normal range
    /// for a LOINC `code` — reused by the Sort & Visibility editor and the trends
    /// screen so the wording and behaviour stay identical everywhere.
    ///
    /// LOINC ships no reference ranges, so this is purely the user's own value
    /// (see `ReferenceRange`). Present it by setting `code`; seed `low`/`high`
    /// first with the current range so the fields show it for editing. Either
    /// field may be left blank for a one-sided limit; leaving both blank — or
    /// tapping "Reset to Default" — clears the range (the per-code reset) and
    /// removes out-of-range flagging. The chosen range is written to
    /// `prefs.referenceRanges` and applied across the app via
    /// `LabMapping.referenceRange(for:)`. `unit`, when known, is shown in the
    /// message so the user enters bounds in the right unit.
    func referenceRangeAlert(code: Binding<String?>,
                             low: Binding<String>,
                             high: Binding<String>,
                             unit: String = "",
                             prefs: Binding<LabDisplayPreferences>) -> some View {
        let isPresented = Binding(
            get: { code.wrappedValue != nil },
            set: { if !$0 { code.wrappedValue = nil } }
        )
        return alert("Reference Range", isPresented: isPresented, presenting: code.wrappedValue) { code in
            TextField("Low", text: low)
                .keyboardType(.decimalPad)
            TextField("High", text: high)
                .keyboardType(.decimalPad)
            Button("Save") {
                prefs.wrappedValue.setReferenceRange(
                    ReferenceRange(low: parse(low.wrappedValue), high: parse(high.wrappedValue)),
                    for: code
                )
            }
            if prefs.wrappedValue.referenceRange(for: code) != nil {
                Button("Reset to Default", role: .destructive) {
                    prefs.wrappedValue.setReferenceRange(nil, for: code)
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: { code in
            Text(message(for: code, unit: unit))
        }
    }
}

/// Parses a user-entered bound, tolerating the locale's comma decimal separator
/// and treating a blank field as "no limit" (`nil`).
private func parse(_ text: String) -> Double? {
    let trimmed = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
    return trimmed.isEmpty ? nil : Double(trimmed)
}

private func message(for code: String, unit: String) -> String {
    let name = LabMapping.displayName(for: code)
    if unit.isEmpty {
        return String(localized: """
        Set the normal range for “\(name)”. Results outside it are flagged across the app. \
        Leave a field blank for no limit.
        """)
    }
    return String(localized: """
    Set the normal range for “\(name)” in \(unit). Results outside it are flagged across the app. \
    Leave a field blank for no limit.
    """)
}
