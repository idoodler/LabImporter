import SwiftUI
import Charts
import PhotosUI
import UniformTypeIdentifiers

// MARK: - Dashboard

struct DashboardView: View {
    let reports: [LabReport]
    @Binding var photosPickerItem: PhotosPickerItem?
    let onCamera: () -> Void
    let onPaste: () -> Void
    let clipboardAvailable: Bool
    let isProcessing: Bool

    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @State private var draggingCode: String?
    @State private var wiggle = false

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                if isProcessing {
                    processingBanner
                }
                metricsGrid
                trendsLink
                footer
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .navigationTitle("Lab Results")
        .navigationBarTitleDisplayMode(.large)
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

    // MARK: - Processing banner

    private var processingBanner: some View {
        HStack(spacing: 12) {
            ProgressView().controlSize(.small)
            Text("Analyzing lab report…")
                .font(.subheadline.weight(.medium))
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
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
            ForEach(sortedMetrics) { metric in
                let isDragging = draggingCode == metric.entry.code
                let isOtherDragging = draggingCode != nil && !isDragging
                MetricCard(metric: metric, isPinned: prefs.pinnedSet.contains(metric.entry.code))
                    .scaleEffect(isDragging ? 1.05 : 1.0)
                    .opacity(isDragging ? 0.6 : 1.0)
                    .rotationEffect(.degrees(isOtherDragging ? (wiggle ? 1.5 : -1.5) : 0))
                    .animation(
                        isOtherDragging
                            ? .easeInOut(duration: 0.15).repeatForever(autoreverses: true)
                            : .default,
                        value: wiggle
                    )
                    .onDrag {
                        draggingCode = metric.entry.code
                        return NSItemProvider(object: metric.entry.code as NSString)
                    }
                    .onDrop(
                        of: [UTType.text],
                        delegate: MetricDropDelegate(
                            targetCode: metric.entry.code,
                            draggingCode: $draggingCode,
                            reorder: reorderMetric
                        )
                    )
                    .contextMenu {
                        let pinned = prefs.pinnedSet.contains(metric.entry.code)
                        Button { togglePin(metric.entry.code) } label: {
                            Label(
                                pinned ? "Unpin" : "Pin to Top",
                                systemImage: pinned ? "pin.slash" : "pin"
                            )
                        }
                    }
            }
        }
        .onChange(of: draggingCode) { _, newValue in
            wiggle = newValue != nil
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
                    .foregroundStyle(.secondary)
            }
            .foregroundStyle(.primary)
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
            )
        }
    }

    @ViewBuilder
    private var footer: some View {
        if let date = reports.max(by: { $0.date < $1.date })?.date {
            Text("Last updated \(date.formatted(date: .abbreviated, time: .omitted))")
                .font(.caption)
                .foregroundStyle(.secondary)
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
    }

    private var sortedMetrics: [MetricData] {
        let pinned = prefs.pinnedSet
        var orderMap: [String: Int] = [:]
        for (idx, code) in prefs.orderedCodes.enumerated() where orderMap[code] == nil {
            orderMap[code] = idx
        }
        return metrics.sorted { lhs, rhs in
            let aPin = pinned.contains(lhs.entry.code)
            let bPin = pinned.contains(rhs.entry.code)
            if aPin != bPin { return aPin }
            let aOrd = orderMap[lhs.entry.code] ?? Int.max
            let bOrd = orderMap[rhs.entry.code] ?? Int.max
            if aOrd != bOrd { return aOrd < bOrd }
            return lhs.entry.name < rhs.entry.name
        }
    }

    // MARK: - Drag-to-reorder

    private func reorderMetric(from fromCode: String, into toCode: String) {
        var codes = prefs.orderedCodes
        for code in sortedMetrics.map(\.entry.code) where !codes.contains(code) {
            codes.append(code)
        }
        guard let fromIdx = codes.firstIndex(of: fromCode),
              let toIdx = codes.firstIndex(of: toCode),
              fromIdx != toIdx else { return }
        codes.move(fromOffsets: IndexSet(integer: fromIdx), toOffset: toIdx > fromIdx ? toIdx + 1 : toIdx)
        var updated = prefs
        updated.orderedCodes = codes
        withAnimation(.spring(duration: 0.25)) {
            prefs = updated
        }
    }

    // MARK: - Pin helpers

    private func togglePin(_ code: String) {
        var updated = prefs
        if updated.pinnedCodes.contains(code) {
            updated.pinnedCodes.removeAll { $0 == code }
        } else {
            updated.pinnedCodes.append(code)
            if !updated.orderedCodes.contains(code) {
                updated.orderedCodes.append(code)
            }
        }
        prefs = updated
    }
}

// MARK: - Drop delegate

private struct MetricDropDelegate: DropDelegate {
    let targetCode: String
    @Binding var draggingCode: String?
    let reorder: (String, String) -> Void

    func dropEntered(info: DropInfo) {
        guard let from = draggingCode, from != targetCode else { return }
        reorder(from, targetCode)
    }

    func dropUpdated(info: DropInfo) -> DropProposal? {
        DropProposal(operation: .move)
    }

    func performDrop(info: DropInfo) -> Bool {
        draggingCode = nil
        return true
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
    let isPinned: Bool

    private var statusColor: Color {
        switch metric.status {
        case .normal:    return Color.green
        case .borderline: return Color.orange
        case .abnormal:  return Color.red
        case .none:      return Color.secondary
        }
    }

    private var statusLabel: String {
        switch metric.status {
        case .normal:    return "Normal"
        case .borderline: return "Borderline"
        case .abnormal:  return "Elevated"
        case .none:      return ""
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 4) {
                Text(metric.entry.name)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                if isPinned {
                    Image(systemName: "pin.fill")
                        .font(.caption2)
                        .foregroundStyle(Color.accentColor)
                }
            }

            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(metric.entry.displayValue)
                    .font(.title2.bold())
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                if !metric.entry.unit.isEmpty {
                    Text(metric.entry.unit)
                        .font(.caption)
                        .foregroundStyle(.secondary)
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
                .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
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
