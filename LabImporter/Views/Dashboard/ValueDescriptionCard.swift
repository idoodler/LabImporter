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
                        .foregroundStyle(.textTertiary)
                        .accessibilityHidden(true)
                }
                // The full catalog label in the app's language, which tells this test from its neighbours;
                // LOINC's description is terse syntax ("Calcidiol in Ser/Plas (MCnc)").
                Text(term.name)
                    .font(.callout)
                    .foregroundStyle(.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(verbatim: "LOINC \(term.code)")
                    .font(.caption2)
                    .foregroundStyle(.textTertiary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .cardSurface()
            .padding(.horizontal)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Previews

#if DEBUG
#Preview("Description") {
    NavigationStack {
        ValueDescriptionCard(term: .sample)
    }
}

#Preview("Name Only") {
    NavigationStack {
        ValueDescriptionCard(term: LoincTerm(code: "2160-0", name: "Creatinine", englishName: "Creatinine",
                                             description: nil, ucum: "mg/dL", rank: 1))
    }
}

#Preview("Dark") {
    NavigationStack {
        ValueDescriptionCard(term: .sample)
    }
    .preferredColorScheme(.dark)
}

#Preview("Largest Text") {
    NavigationStack {
        ValueDescriptionCard(term: .sample)
    }
    .dynamicTypeSize(.accessibility5)
}

#Preview("RTL") {
    NavigationStack {
        ValueDescriptionCard(term: .sample)
    }
    .environment(\.layoutDirection, .rightToLeft)
}
#endif
