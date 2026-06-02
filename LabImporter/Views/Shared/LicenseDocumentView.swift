import SwiftUI

// MARK: - LicenseDocumentView

/// Shared layout for the app's license screens. Renders a license document as a
/// scrollable footnote with an optional bold header line (used by the LOINC
/// screen to show the catalog version). Both `LicenseView` and `LoincLicenseView`
/// build on this so the two screens stay visually identical and the styling
/// lives in one place.
struct LicenseDocumentView: View {
    let title: LocalizedStringKey
    /// An optional bold line shown above the body (e.g. `LOINC® 2.82`). Hidden
    /// when `nil` or empty.
    var header: String?
    let text: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let header, !header.isEmpty {
                    Text(verbatim: header)
                        .font(.subheadline.weight(.semibold))
                }
                Text(text)
                    .font(.footnote)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding()
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Previews

#Preview("Document") {
    NavigationStack {
        LicenseDocumentView(title: "License", text: "MIT License\n\nCopyright (c) 2026 idoodler")
    }
}
