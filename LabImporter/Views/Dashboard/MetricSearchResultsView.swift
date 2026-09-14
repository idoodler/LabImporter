import SwiftUI

/// The screen Siri/Spotlight's "Search in LabImporter" (`SearchLabValuesIntent`)
/// opens to: a searchable list of tracked values matching a term, each row
/// opening straight into that metric's trend. Mirrors `LoincBrowserView`'s
/// `.searchable` list, but searches the values the user already tracks (by
/// name) rather than the full LOINC catalog — the same universe
/// `distinctNumericCodes`/`latestEntry(for:)` expose to Spotlight indexing.
struct MetricSearchResultsView: View {
    let reports: [LabReport]
    @State private var query: String

    init(reports: [LabReport], initialTerm: String) {
        self.reports = reports
        _query = State(initialValue: initialTerm)
    }

    @Environment(\.dismiss) private var dismiss

    private struct MetricMatch: Identifiable {
        let code: String
        let name: String
        let entry: LabReport.Entry
        var id: String { code }
    }

    private var allResults: [MetricMatch] {
        reports.distinctNumericCodes
            .compactMap { code -> MetricMatch? in
                guard let latest = reports.latestEntry(for: code) else { return nil }
                return MetricMatch(code: code, name: LabMapping.displayName(for: code), entry: latest.entry)
            }
            .sorted { $0.name < $1.name }
    }

    private var matches: [MetricMatch] {
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return allResults }
        let needle = query.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
        return allResults.filter {
            $0.name.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil).contains(needle)
        }
    }

    var body: some View {
        NavigationStack {
            List(matches) { result in
                NavigationLink {
                    TrendsView(reports: reports, initialCode: result.code)
                } label: {
                    row(for: result)
                }
            }
            .overlay {
                if matches.isEmpty {
                    ContentUnavailableView.search(text: query)
                }
            }
            .searchable(text: $query, prompt: Text("Search Your Values"))
            .navigationTitle("Search Your Values")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func row(for result: MetricMatch) -> some View {
        HStack(spacing: 12) {
            Circle()
                .fill(LabCategory.forCode(result.code).color.gradient)
                .frame(width: 10, height: 10)
            Text(result.name)
            Spacer()
            if result.entry.numericValue != nil {
                Text(result.entry.unit.isEmpty ? result.entry.displayValue : "\(result.entry.displayValue) \(result.entry.unit)")
                    .foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Previews

#if DEBUG
#Preview("Populated") {
    MetricSearchResultsView(reports: LabReport.sampleHistory, initialTerm: "")
}

#Preview("Filtered") {
    MetricSearchResultsView(reports: LabReport.sampleHistory, initialTerm: "chol")
}

#Preview("No Matches") {
    MetricSearchResultsView(reports: LabReport.sampleHistory, initialTerm: "xyz-not-tracked")
}

#Preview("Empty") {
    MetricSearchResultsView(reports: [], initialTerm: "")
}

#Preview("Dark") {
    MetricSearchResultsView(reports: LabReport.sampleHistory, initialTerm: "")
        .preferredColorScheme(.dark)
}

#Preview("RTL") {
    MetricSearchResultsView(reports: LabReport.sampleHistory, initialTerm: "")
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
