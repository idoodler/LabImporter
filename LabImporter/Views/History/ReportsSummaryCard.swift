import SwiftUI

/// The totals above the list of reports.
struct ReportsSummaryCard: View {
    let reports: [LabReport]
    let categoryCount: Int

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let totalValues = reports.reduce(0) { $0 + $1.entries.count }
        let latest = reports.map(\.date).max()
        return VStack(spacing: 14) {
            AdaptiveRow(spacing: 0) {
                stat(value: "\(reports.count)", label: String(localized: "Reports"))
                divider
                stat(value: "\(totalValues)", label: String(localized: "Lab Values"))
                divider
                stat(value: "\(categoryCount)", label: String(localized: "Categories"))
            }

            if let latest {
                HStack(spacing: 6) {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.caption2)
                    Text("Last updated \(latest.formatted(date: .abbreviated, time: .omitted))")
                        .font(.caption)
                }
                .foregroundStyle(.textSecondary)
            }
        }
        .padding(.vertical, 18)
        .padding(.horizontal, 12)
        .frame(maxWidth: .infinity)
        .cardSurface(cornerRadius: 22)
    }

    @ViewBuilder
    private var divider: some View {
        if !dynamicTypeSize.isAccessibilitySize { Divider().frame(height: 34) }
    }

    private func stat(value: String, label: String) -> some View {
        VStack(spacing: 3) {
            Text(value)
                .font(.title2.bold().monospacedDigit())
                .foregroundStyle(.primary)
            Text(label)
                .font(.caption2)
                .foregroundStyle(.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
    }
}

#if DEBUG
#Preview("Summary") {
    ReportsSummaryCard(reports: LabReport.sampleHistory, categoryCount: 4)
        .padding()
}

#Preview("Summary · Dark") {
    ReportsSummaryCard(reports: LabReport.sampleHistory, categoryCount: 4)
        .padding()
        .preferredColorScheme(.dark)
}
#endif
