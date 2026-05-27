import SwiftUI
import Charts
import PhotosUI

// MARK: - Dashboard

struct DashboardView: View {
    let reports: [LabReport]
    @Binding var photosPickerItem: PhotosPickerItem?
    let onCamera: () -> Void
    let onPaste: () -> Void
    let clipboardAvailable: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                metricsGrid
                trendsLink
                footer
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("Lab Results")
        .navigationBarTitleDisplayMode(.large)
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                NavigationLink(destination: HistoryView()) {
                    Image(systemName: "clock.arrow.circlepath")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                importMenu
            }
        }
    }

    // MARK: - Import menu

    @ViewBuilder
    private var importMenu: some View {
        Menu {
            PhotosPicker(selection: $photosPickerItem, matching: .images) {
                Label("Choose from Photos", systemImage: "photo.on.rectangle")
            }
            Button(action: onCamera) {
                Label("Take a Photo", systemImage: "camera")
            }
            .disabled(!UIImagePickerController.isSourceTypeAvailable(.camera))
            Button(action: onPaste) {
                Label("Paste from Clipboard", systemImage: "doc.on.clipboard")
            }
            .disabled(!clipboardAvailable)
        } label: {
            Image(systemName: "plus")
                .fontWeight(.semibold)
        }
    }

    // MARK: - Metrics grid

    private var metricsGrid: some View {
        LazyVGrid(
            columns: [GridItem(.flexible()), GridItem(.flexible())],
            spacing: 14
        ) {
            ForEach(metrics) { metric in
                MetricCard(metric: metric)
            }
        }
    }

    private var trendsLink: some View {
        NavigationLink(destination: TrendsView(reports: reports)) {
            HStack {
                Image(systemName: "chart.line.uptrend.xyaxis")
                    .font(.body.weight(.medium))
                Text("View All Trends")
                    .font(.body.weight(.medium))
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.5))
            }
            .foregroundStyle(.white.opacity(0.9))
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(.white.opacity(0.15), lineWidth: 0.5)
            )
        }
    }

    @ViewBuilder
    private var footer: some View {
        if let date = reports.max(by: { $0.date < $1.date })?.date {
            Text("Last updated \(date.formatted(date: .abbreviated, time: .omitted))")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.45))
        }
    }

    // MARK: - Metrics computation

    private var metrics: [MetricData] {
        var latestEntry: [String: (entry: LabReport.Entry, date: Date)] = [:]
        var allPoints: [String: [SparkPoint]] = [:]

        for report in reports {
            for entry in report.entries {
                guard let value = entry.numericValue else { continue }
                allPoints[entry.code, default: []].append(SparkPoint(date: report.date, value: value))
                if let existing = latestEntry[entry.code] {
                    if report.date > existing.date {
                        latestEntry[entry.code] = (entry: entry, date: report.date)
                    }
                } else {
                    latestEntry[entry.code] = (entry: entry, date: report.date)
                }
            }
        }

        return latestEntry.values.map { item in
            let points = (allPoints[item.entry.code] ?? []).sorted { $0.date < $1.date }
            let status = item.entry.numericValue.flatMap { value in
                LabMapping.referenceRange(for: item.entry.code)?.status(for: value)
            }
            return MetricData(entry: item.entry, history: points, status: status)
        }
        .sorted { $0.entry.name < $1.entry.name }
    }
}

// MARK: - Supporting types

private struct SparkPoint: Identifiable {
    let id = UUID()
    let date: Date
    let value: Double
}

private struct MetricData: Identifiable {
    var id: String { entry.code }
    let entry: LabReport.Entry
    let history: [SparkPoint]
    let status: RangeStatus?
}

// MARK: - MetricCard

private struct MetricCard: View {
    let metric: MetricData

    private var statusColor: Color {
        switch metric.status {
        case .normal: return Color.green
        case .borderline: return Color.orange
        case .abnormal: return Color.red
        case .none: return Color.white.opacity(0.5)
        }
    }

    private var statusLabel: String {
        switch metric.status {
        case .normal: return "Normal"
        case .borderline: return "Borderline"
        case .abnormal: return "Elevated"
        case .none: return ""
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(metric.entry.name)
                .font(.caption)
                .foregroundStyle(.white.opacity(0.7))
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)

            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(metric.entry.displayValue)
                    .font(.title2.bold())
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                if !metric.entry.unit.isEmpty {
                    Text(metric.entry.unit)
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.65))
                        .lineLimit(1)
                }
            }

            if metric.history.count > 1 {
                sparkline
            } else {
                Spacer().frame(height: 40)
            }

            if metric.status != nil {
                HStack(spacing: 5) {
                    Circle()
                        .fill(statusColor)
                        .frame(width: 7, height: 7)
                    Text(statusLabel)
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(statusColor)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 158, alignment: .topLeading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(.white.opacity(0.15), lineWidth: 0.5)
        )
    }

    private var sparkline: some View {
        Chart(metric.history) { point in
            LineMark(
                x: .value("Date", point.date),
                y: .value("Value", point.value)
            )
            .foregroundStyle(statusColor.opacity(0.85))

            AreaMark(
                x: .value("Date", point.date),
                y: .value("Value", point.value)
            )
            .foregroundStyle(
                LinearGradient(
                    colors: [statusColor.opacity(0.3), statusColor.opacity(0.05)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .frame(height: 40)
    }
}
