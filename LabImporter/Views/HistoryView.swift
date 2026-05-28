import SwiftUI

struct HistoryView: View {
    @State private var reports: [LabReport] = []
    @State private var loadError: String?
    @State private var reportToEdit: LabReport?

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
        .onAppear { Task { await loadReports() } }
        .sheet(item: $reportToEdit, onDismiss: { Task { await loadReports() } }, content: { report in
            NavigationStack {
                ReviewView(
                    labValues: report.asLabValues,
                    reportDate: report.date,
                    replacingReport: report
                )
            }
            .interactiveDismissDisabled()
        })
        .alert("Load Error", isPresented: .constant(loadError != nil)) {
            Button("OK") { loadError = nil }
        } message: {
            Text(loadError ?? "")
        }
    }

    private var reportList: some View {
        List {
            ForEach(reports) { report in
                NavigationLink(destination: ReportDetailView(report: report, onDeleted: deleteReport)) {
                    reportRow(report)
                }
                .swipeActions(edge: .leading, allowsFullSwipe: false) {
                    Button { reportToEdit = report } label: {
                        Label("Edit", systemImage: "pencil")
                    }
                    .tint(.blue)
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

            Text("\(report.entries.count) values")
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

    private func deleteReport(_ id: UUID) {
        reports.removeAll { $0.id == id }
        Task { try? await HealthKitService.shared.deleteCDADocument(id: id) }
    }

    private func deleteReports(at offsets: IndexSet) {
        let ids = offsets.map { reports[$0].id }
        reports.remove(atOffsets: offsets)
        Task {
            for id in ids { try? await HealthKitService.shared.deleteCDADocument(id: id) }
        }
    }
}
