import SwiftUI
import Charts

struct TrendsView: View {
    let reports: [LabReport]

    @AppStorage("trendsSelectedCode") private var selectedCode: String = ""
    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()

    private struct DataPoint: Identifiable {
        let id = UUID()
        let date: Date
        let value: Double
        let unit: String
    }

    private var availableCodes: [(code: String, name: String)] {
        var seen = Set<String>()
        var result: [(code: String, name: String)] = []
        for report in reports {
            for entry in report.entries where entry.numericValue != nil {
                if seen.insert(entry.code).inserted {
                    result.append((code: entry.code, name: entry.name))
                }
            }
        }
        let pinned = prefs.pinnedSet
        var orderMap: [String: Int] = [:]
        for (idx, code) in prefs.orderedCodes.enumerated() {
            if orderMap[code] == nil { orderMap[code] = idx }
        }
        return result.sorted { lhs, rhs in
            let aPin = pinned.contains(lhs.code)
            let bPin = pinned.contains(rhs.code)
            if aPin != bPin { return aPin }
            let aOrd = orderMap[lhs.code] ?? Int.max
            let bOrd = orderMap[rhs.code] ?? Int.max
            if aOrd != bOrd { return aOrd < bOrd }
            return lhs.name < rhs.name
        }
    }

    private var dataPoints: [DataPoint] {
        reports
            .flatMap { report in
                report.entries
                    .filter { $0.code == selectedCode }
                    .compactMap { entry -> DataPoint? in
                        guard let value = entry.numericValue else { return nil }
                        return DataPoint(date: report.date, value: value, unit: entry.unit)
                    }
            }
            .sorted { $0.date < $1.date }
    }

    private var currentUnit: String { dataPoints.first?.unit ?? "" }

    var body: some View {
        VStack(spacing: 0) {
            if availableCodes.isEmpty {
                noDataView
            } else {
                codePicker
                trendContent
            }
        }
        .navigationTitle("Trends")
        .navigationBarTitleDisplayMode(.large)
        .onAppear {
            let codes = availableCodes.map(\.code)
            if selectedCode.isEmpty || !codes.contains(selectedCode) {
                selectedCode = codes.first ?? ""
            }
        }
    }

    // MARK: - Subviews

    private var noDataView: some View {
        ContentUnavailableView(
            "No Numeric Data",
            systemImage: "chart.line.uptrend.xyaxis",
            description: Text("Import reports with numeric lab values to see trends.")
        )
    }

    private var codePicker: some View {
        Picker("Lab Value", selection: $selectedCode) {
            ForEach(availableCodes, id: \.code) { item in
                Text(item.name).tag(item.code)
            }
        }
        .pickerStyle(.menu)
        .padding([.horizontal, .top])
    }

    @ViewBuilder
    private var trendContent: some View {
        if dataPoints.count < 2 {
            ContentUnavailableView(
                "Not Enough Data",
                systemImage: "chart.xyaxis.line",
                description: Text("Import at least two reports containing this value to see a trend.")
            )
        } else {
            ScrollView {
                trendChart
                    .padding()
            }
        }
    }

    private var trendChart: some View {
        Chart(dataPoints) { point in
            LineMark(
                x: .value("Date", point.date),
                y: .value(currentUnit, point.value)
            )
            .foregroundStyle(Color.accentColor.opacity(0.9))

            PointMark(
                x: .value("Date", point.date),
                y: .value(currentUnit, point.value)
            )
            .foregroundStyle(Color.accentColor)

            AreaMark(
                x: .value("Date", point.date),
                y: .value(currentUnit, point.value)
            )
            .foregroundStyle(Color.accentColor.opacity(0.15))
        }
        .chartXAxis {
            AxisMarks(values: .automatic) { _ in
                AxisGridLine().foregroundStyle(Color.primary.opacity(0.15))
                AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                    .foregroundStyle(.secondary)
            }
        }
        .chartYAxis {
            AxisMarks { _ in
                AxisGridLine().foregroundStyle(Color.primary.opacity(0.15))
                AxisValueLabel().foregroundStyle(.secondary)
            }
        }
        .chartYAxisLabel(currentUnit)
        .frame(minHeight: 260)
        .padding()
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
    }
}
