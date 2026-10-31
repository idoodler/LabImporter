import SwiftUI

/// Every result of a metric whose readings aren't numbers ("Non-reactive",
/// "1:160", "<0.5 mg/L") — the counterpart of `TrendsView` for values there is
/// nothing to chart: the latest result up top, then the whole history,
/// newest first.
struct ResultHistoryView: View {
    let metric: MetricData
    var onDismiss: (() -> Void)?

    private var categoryColor: Color {
        LabCategory.forCode(metric.entry.code).color
    }

    var body: some View {
        List {
            Section {
                latest
            }
            Section {
                ForEach(metric.results.reversed()) { result in
                    HStack {
                        Text(result.date.formatted(date: .long, time: .omitted))
                            .foregroundStyle(.secondary)
                        Spacer()
                        Text(result.formatted)
                            .fontWeight(.medium)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .accessibilityIdentifier("result-history")
        .background { CategoryBackground(colors: [categoryColor]) }
        .navigationTitle(metric.entry.resolvedName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let onDismiss {
                ToolbarItem(placement: .topBarLeading) {
                    Button(role: .close, action: onDismiss)
                }
            }
        }
    }

    private var latest: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 7) {
                Circle()
                    .fill(categoryColor.gradient)
                    .frame(width: 18, height: 18)
                    .accessibilityHidden(true)
                Text(metric.results.last?.date.formatted(date: .long, time: .omitted) ?? "")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(metric.entry.displayValue)
                    .font(.largeTitle.bold())
                    .lineLimit(2)
                    .minimumScaleFactor(0.6)
                if !metric.entry.unit.isEmpty {
                    Text(metric.entry.unit)
                        .font(.title3)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Previews

#if DEBUG
#Preview("Several Results") {
    NavigationStack {
        ResultHistoryView(metric: MetricData.sampleQualitative[0], onDismiss: {})
    }
}

#Preview("Several Results · Dark") {
    NavigationStack {
        ResultHistoryView(metric: MetricData.sampleQualitative[0], onDismiss: {})
    }
    .preferredColorScheme(.dark)
}

#Preview("With Unit") {
    NavigationStack {
        ResultHistoryView(metric: MetricData.sampleQualitative[2], onDismiss: {})
    }
}

#Preview("Single Result") {
    NavigationStack {
        ResultHistoryView(
            metric: MetricData(
                entry: LabReport.Entry(id: UUID(), code: "5196-1", name: "HBsAg", displayValue: "Non-reactive", numericValue: nil, unit: ""),
                history: [],
                results: [ResultPoint(date: Date(timeIntervalSince1970: 1_780_000_000), text: "Non-reactive", unit: "")]
            ),
            onDismiss: {}
        )
    }
}
#endif
