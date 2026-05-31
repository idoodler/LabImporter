import SwiftUI

struct ReportDetailView: View {
    let report: LabReport
    let onDeleted: (UUID) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var showEdit = false
    @State private var showDeleteAlert = false
    @State private var shareURL: IdentifiedURL?
    @State private var shareError: String?

    private let cdaService = CDAExportService()
    private let pdfService = PDFExportService()

    var body: some View {
        List {
            Section {
                headerCard
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }

            ForEach(categoryGroups, id: \.category) { group in
                Section {
                    ForEach(group.entries) { entry in
                        entryRow(entry, color: group.category.color)
                            .listRowBackground(Rectangle().fill(.ultraThinMaterial))
                    }
                } header: {
                    sectionHeader(group)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background { CategoryBackground(colors: backgroundColors) }
        .navigationTitle(report.date.formatted(date: .abbreviated, time: .omitted))
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { showEdit = true } label: {
                        Label("Edit", systemImage: "pencil")
                    }
                    Button { exportPDF() } label: {
                        Label("Export as PDF", systemImage: "doc.richtext")
                    }
                    Button { shareCDA() } label: {
                        Label("Share CDA File", systemImage: "square.and.arrow.up")
                    }
                    Divider()
                    Button(role: .destructive) { showDeleteAlert = true } label: {
                        Label("Delete", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
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
        .alert("Export Error", isPresented: .constant(shareError != nil)) {
            Button("OK") { shareError = nil }
        } message: {
            Text(shareError ?? "")
        }
        .sheet(isPresented: $showEdit) {
            NavigationStack {
                ReviewView(
                    labValues: report.asLabValues,
                    reportDate: report.date,
                    replacingReport: report,
                    onSaved: { dismiss() }
                )
            }
            .interactiveDismissDisabled()
        }
        .sheet(item: $shareURL) { item in
            ShareSheet(url: item.url)
        }
    }

    // MARK: - Header

    private var headerCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 14) {
                ZStack {
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: [dominantColor, dominantColor.opacity(0.65)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                    Image(systemName: "doc.text.fill")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(.white)
                }
                .frame(width: 54, height: 54)
                .shadow(color: dominantColor.opacity(0.35), radius: 5, x: 0, y: 2)

                VStack(alignment: .leading, spacing: 3) {
                    Text(report.date.formatted(date: .long, time: .omitted))
                        .font(.headline)
                    Text("\(report.entries.count) values")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }

            if !metaItems.isEmpty {
                Divider()
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(metaItems, id: \.label) { item in
                        HStack(spacing: 8) {
                            Image(systemName: item.icon)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .frame(width: 18)
                            Text(item.label)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            Spacer()
                            Text(item.value)
                                .font(.subheadline)
                        }
                    }
                }
            }

            if categoryGroups.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(categoryGroups, id: \.category) { group in
                            categoryChip(group)
                        }
                    }
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 22))
        .overlay(
            RoundedRectangle(cornerRadius: 22)
                .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
    }

    private func categoryChip(_ group: CategoryGroup) -> some View {
        HStack(spacing: 6) {
            Circle()
                .fill(group.category.color)
                .frame(width: 8, height: 8)
            Text(group.category.displayName)
                .font(.caption.weight(.medium))
            Text("\(group.entries.count)")
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(group.category.color.opacity(0.12), in: Capsule())
        .overlay(
            Capsule().stroke(group.category.color.opacity(0.25), lineWidth: 0.5)
        )
    }

    // MARK: - Section header

    private func sectionHeader(_ group: CategoryGroup) -> some View {
        HStack(spacing: 8) {
            Circle()
                .fill(group.category.color)
                .frame(width: 9, height: 9)
            Text(group.category.displayName)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
            Spacer()
            Text("\(group.entries.count)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .textCase(nil)
    }

    // MARK: - Value row

    @ViewBuilder
    private func entryRow(_ entry: LabReport.Entry, color: Color) -> some View {
        if let term = LoincDirectory.shared.term(for: entry.code) {
            NavigationLink(destination: LoincTermDetailView(term: term)) {
                entryRowContent(entry, color: color)
            }
        } else {
            entryRowContent(entry, color: color)
        }
    }

    private func entryRowContent(_ entry: LabReport.Entry, color: Color) -> some View {
        HStack(spacing: 12) {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)

            VStack(alignment: .leading, spacing: 2) {
                Text(entry.resolvedName)
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
                .font(.body.monospacedDigit().weight(.medium))
                .foregroundStyle(entry.displayValue == "-" ? .secondary : .primary)
        }
        .padding(.vertical, 2)
    }

    // MARK: - Derived data

    private struct CategoryGroup {
        let category: LabCategory
        let entries: [LabReport.Entry]
    }

    // Entries grouped by clinical category, categories ordered by the canonical
    // `LabCategory` order so related panels stay together across reports.
    private var categoryGroups: [CategoryGroup] {
        let grouped = Dictionary(grouping: report.entries) { LabCategory.forCode($0.code) }
        return LabCategory.allCases.compactMap { category in
            guard let entries = grouped[category], !entries.isEmpty else { return nil }
            let sorted = entries.sorted { $0.resolvedName.localizedCaseInsensitiveCompare($1.resolvedName) == .orderedAscending }
            return CategoryGroup(category: category, entries: sorted)
        }
    }

    private var dominantColor: Color {
        report.dominantCategory?.color ?? .accentColor
    }

    private var backgroundColors: [Color] {
        categoryGroups
            .sorted { $0.entries.count > $1.entries.count }
            .prefix(3)
            .map { $0.category.color }
    }

    private struct MetaItem {
        let icon: String
        let label: String
        let value: String
    }

    private var metaItems: [MetaItem] {
        var items: [MetaItem] = []
        if !report.patientName.isEmpty {
            items.append(MetaItem(icon: "person.fill", label: String(localized: "Patient"), value: report.patientName))
        }
        if !report.authorName.isEmpty {
            items.append(MetaItem(icon: "cross.case.fill", label: String(localized: "Author"), value: report.authorName))
        }
        return items
    }

    // MARK: - Sharing

    private func shareCDA() {
        do {
            let url = try cdaService.exportToTempFile(
                labValues: report.asLabValues,
                date: report.date,
                patientName: report.patientName,
                authorName: report.authorName
            )
            shareURL = IdentifiedURL(url: url)
        } catch {
            shareError = error.localizedDescription
        }
    }

    private func exportPDF() {
        do {
            shareURL = IdentifiedURL(url: try pdfService.exportToTempFile(reports: [report]))
        } catch {
            shareError = error.localizedDescription
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        ReportDetailView(report: .sample, onDeleted: { _ in })
    }
}
