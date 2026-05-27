import SwiftUI

struct HistoryView: View {
    @State private var reports: [LabReport] = []
    @State private var loadError: String?

    var body: some View {
        Group {
            if reports.isEmpty {
                ContentUnavailableView(
                    "No Reports Yet",
                    systemImage: "doc.text.magnifyingglass",
                    description: Text("Import a lab report and save it to Apple Health to see it here.")
                )
            } else {
                reportList
            }
        }
        .navigationTitle("History")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            if !reports.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink(destination: TrendsView(reports: reports)) {
                        Image(systemName: "chart.line.uptrend.xyaxis")
                    }
                }
            }
        }
        .task { await loadReports() }
        .alert("Load Error", isPresented: .constant(loadError != nil)) {
            Button("OK") { loadError = nil }
        } message: {
            Text(loadError ?? "")
        }
    }

    private var reportList: some View {
        List {
            ForEach(reports) { report in
                NavigationLink(destination: ReportDetailView(report: report)) {
                    reportRow(report)
                }
            }
            .onDelete(perform: deleteReports)
        }
        .navigationTitle("History")
    }

    private func reportRow(_ report: LabReport) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(report.date.formatted(date: .abbreviated, time: .omitted))
                .font(.headline)

            let meta = [report.patientName, report.authorName]
                .filter { !$0.isEmpty }
                .joined(separator: " · ")
            if !meta.isEmpty {
                Text(meta)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Text("\(report.entries.count) value\(report.entries.count == 1 ? "" : "s")")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }

    private func loadReports() async {
        do {
            reports = try await HealthKitService.shared.loadCDADocuments()
        } catch {
            loadError = error.localizedDescription
        }
    }

    private func deleteReports(at offsets: IndexSet) {
        let toDelete = offsets.map { reports[$0].id }
        reports.remove(atOffsets: offsets)
        Task {
            for reportId in toDelete {
                try? await HealthKitService.shared.deleteCDADocument(id: reportId)
            }
        }
    }
}
