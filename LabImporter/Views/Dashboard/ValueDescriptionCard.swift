import SwiftUI

// MARK: - ValueDescriptionCard

/// Tappable summary of the selected value's LOINC term shown beneath the trend
/// chart; navigates to the full structured details (and the loinc.org link).
struct ValueDescriptionCard: View {
    let term: LoincTerm

    var body: some View {
        NavigationLink {
            LoincTermDetailView(term: term)
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("About this value")
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
                if let description {
                    Text(description)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                Text(verbatim: "LOINC \(term.code)")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
            .overlay(
                RoundedRectangle(cornerRadius: 20)
                    .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
            )
            .padding(.horizontal)
        }
        .buttonStyle(.plain)
    }

    private var description: String? {
        if let description = term.description, !description.isEmpty { return description }
        // Fall back to the English name when it adds detail beyond the title.
        return term.englishName == term.name ? nil : term.englishName
    }
}
