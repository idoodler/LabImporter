import SwiftUI
import Charts

struct TrendsView: View {
    let reports: [LabReport]

    @State private var selectedCode: String = ""

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
        return result.sorted { $0.name < $1.name }
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
        ZStack {
            backgroundGradient
            VStack(spacing: 0) {
                if availableCodes.isEmpty {
                    noDataView
                } else {
                    codePicker
                    trendContent
                }
            }
        }
        .navigationTitle("Trends")
        .navigationBarTitleDisplayMode(.large)
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
        .onAppear {
            if selectedCode.isEmpty, let first = availableCodes.first {
                selectedCode = first.code
            }
        }
    }

    // MARK: - Subviews

    private var backgroundGradient: some View {
        LinearGradient(
            colors: [
                Color(hue: 0.65, saturation: 0.6, brightness: 0.35),
                Color(hue: 0.75, saturation: 0.7, brightness: 0.25)
            ],
            startPoint: .top,
            endPoint: .bottom
        )
        .ignoresSafeArea()
    }

    private var noDataView: some View {
        ContentUnavailableView(
            "No Numeric Data",
            systemImage: "chart.line.uptrend.xyaxis",
            description: Text("Import reports with numeric lab values to see trends.")
        )
        .foregroundStyle(.white)
    }

    private var codePicker: some View {
        Picker("Lab Value", selection: $selectedCode) {
            ForEach(availableCodes, id: \.code) { item in
                Text(item.name).tag(item.code)
            }
        }
        .pickerStyle(.menu)
        .tint(.white)
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
            .foregroundStyle(.white)
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
            .foregroundStyle(.white.opacity(0.9))

            PointMark(
                x: .value("Date", point.date),
                y: .value(currentUnit, point.value)
            )
            .foregroundStyle(.white)

            AreaMark(
                x: .value("Date", point.date),
                y: .value(currentUnit, point.value)
            )
            .foregroundStyle(.white.opacity(0.1))
        }
        .chartXAxis {
            AxisMarks(values: .automatic) { _ in
                AxisGridLine().foregroundStyle(.white.opacity(0.2))
                AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                    .foregroundStyle(.white.opacity(0.7))
            }
        }
        .chartYAxis {
            AxisMarks { _ in
                AxisGridLine().foregroundStyle(.white.opacity(0.2))
                AxisValueLabel().foregroundStyle(.white.opacity(0.7))
            }
        }
        .chartYAxisLabel(currentUnit)
        .frame(minHeight: 260)
        .padding()
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(.white.opacity(0.15), lineWidth: 0.5)
        )
    }
}
