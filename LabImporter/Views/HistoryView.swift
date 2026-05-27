import SwiftUI

struct HistoryView: View {
    @State private var reports: [LabReport] = []
    @State private var loadError: String?

    var body: some View {
        ZStack {
            backgroundGradient
            Group {
                if reports.isEmpty {
                    ContentUnavailableView(
                        "No Reports Yet",
                        systemImage: "doc.text.magnifyingglass",
                        description: Text("Import or export a lab report to see it here.")
                    )
                    .foregroundStyle(.white)
                } else {
                    reportList
                }
            }
        }
        .navigationTitle("History")
        .navigationBarTitleDisplayMode(.large)
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
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

    private var reportList: some View {
        List {
            ForEach(reports) { report in
                NavigationLink(destination: ReportDetailView(report: report)) {
                    reportRow(report)
                }
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
            .onDelete(perform: deleteReports)
        }
        .listStyle(.plain)
        .listRowSpacing(8)
        .scrollContentBackground(.hidden)
        .padding(.horizontal, 4)
    }

    private func reportRow(_ report: LabReport) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(report.date.formatted(date: .abbreviated, time: .omitted))
                .font(.headline)
                .foregroundStyle(.white)

            let meta = [report.patientName, report.authorName]
                .filter { !$0.isEmpty }
                .joined(separator: " · ")
            if !meta.isEmpty {
                Text(meta)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.7))
            }

            Text("\(report.entries.count) value\(report.entries.count == 1 ? "" : "s")")
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.5))
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(.white.opacity(0.15), lineWidth: 0.5)
        )
    }

    private func loadReports() async {
        do {
            reports = try await ReportHistoryService.shared.loadAll()
        } catch {
            loadError = error.localizedDescription
        }
    }

    private func deleteReports(at offsets: IndexSet) {
        let toDelete = offsets.map { reports[$0].id }
        reports.remove(atOffsets: offsets)
        Task {
            for reportId in toDelete {
                try? await ReportHistoryService.shared.delete(id: reportId)
            }
        }
    }
}
