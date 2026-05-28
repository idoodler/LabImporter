import SwiftUI

struct ReportDetailView: View {
    let report: LabReport
    let onDeleted: (UUID) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var showEdit = false
    @State private var showDeleteAlert = false

    var body: some View {
        List {
            Section {
                LabeledContent("Date", value: report.date.formatted(date: .long, time: .omitted))

                if !report.patientName.isEmpty {
                    LabeledContent("Patient", value: report.patientName)
                }

                if !report.authorName.isEmpty {
                    LabeledContent("Author", value: report.authorName)
                }
            }

            Section("Lab Values") {
                ForEach(report.entries) { entry in
                    entryRow(entry)
                }
            }
        }
        .navigationTitle(report.date.formatted(date: .abbreviated, time: .omitted))
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showEdit = true } label: {
                    Image(systemName: "pencil")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showDeleteAlert = true } label: {
                    Image(systemName: "trash")
                }
                .tint(.red)
            }
        }
        .alert("Delete Report?", isPresented: $showDeleteAlert) {
            Button("Delete", role: .destructive) {
                onDeleted(report.id)
                dismiss()
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("This report will be permanently removed from Apple Health.")
        }
        .sheet(isPresented: $showEdit, onDismiss: { dismiss() }, content: {
            NavigationStack {
                ReviewView(
                    labValues: report.asLabValues,
                    reportDate: report.date,
                    replacingReport: report
                )
            }
            .interactiveDismissDisabled()
        })
    }

    private func entryRow(_ entry: LabReport.Entry) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name)
                    .font(.body)
                Text(entry.code)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .textCase(.uppercase)
            }

            Spacer()

            let valueText = entry.displayValue == "-"
                ? "–"
                : "\(entry.displayValue) \(entry.unit)".trimmingCharacters(in: .whitespaces)

            Text(valueText)
                .font(.body.monospacedDigit())
                .foregroundStyle(.secondary)
        }
    }
}
