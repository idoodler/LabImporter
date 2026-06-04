import SwiftUI

struct HistoryView: View {
    @State private var reports: [LabReport] = []
    @State private var loadError: String?
    @State private var deleteError: String?
    @State private var reportToEdit: LabReport?
    // Reports the user swiped to delete, awaiting confirmation. Deletion from
    // Apple Health is irreversible, so we verify intent before removing them.
    @State private var pendingDeleteIDs: [UUID] = []
    // Multi-select state: drives the list's checkmarks while editing.
    @State private var editMode: EditMode = .inactive
    @State private var selection: Set<UUID> = []

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
        .navigationTitle(navigationTitle)
        .navigationBarTitleDisplayMode(.large)
        .background { CategoryBackground(colors: backgroundColors) }
        .environment(\.editMode, $editMode)
        .navigationBarBackButtonHidden(editMode.isEditing)
        .toolbar { toolbarContent }
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
        .alert("Delete Failed", isPresented: .constant(deleteError != nil)) {
            Button("OK") { deleteError = nil }
        } message: {
            Text(deleteError ?? "")
        }
        .alert("Delete Report?", isPresented: .constant(!pendingDeleteIDs.isEmpty)) {
            Button("Delete", role: .destructive) {
                deleteReports(ids: pendingDeleteIDs)
                pendingDeleteIDs = []
                exitEditing()
            }
            Button("Cancel", role: .cancel) { pendingDeleteIDs = [] }
        } message: {
            if pendingDeleteIDs.count == 1 {
                Text("This report will be permanently removed from Apple Health.")
            } else {
                Text("\(pendingDeleteIDs.count) reports will be permanently removed from Apple Health.")
            }
        }
    }

    // MARK: - Toolbar

    private var navigationTitle: String {
        if editMode.isEditing && !selection.isEmpty {
            return String(localized: "\(selection.count) Selected")
        }
        return String(localized: "Reports")
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            if !reports.isEmpty {
                Button {
                    withAnimation { toggleEditing() }
                } label: {
                    if editMode.isEditing {
                        Image(systemName: "checkmark")
                    } else {
                        Text("Edit")
                    }
                }
                .accessibilityLabel(editMode.isEditing ? Text("Done") : Text("Edit"))
            }
        }

        if editMode.isEditing {
            // Replaces the back button while selecting: a one-tap path to clear
            // every report (still gated behind the confirmation alert).
            ToolbarItem(placement: .topBarLeading) {
                Button("Delete All", role: .destructive) {
                    pendingDeleteIDs = reports.map(\.id)
                }
                .tint(.red)
            }

            ToolbarItem(placement: .bottomBar) {
                HStack {
                    Button(allSelected ? "Deselect All" : "Select All") {
                        selection = allSelected ? [] : Set(reports.map(\.id))
                    }
                    Spacer()
                    Button("Delete", role: .destructive) {
                        pendingDeleteIDs = Array(selection)
                    }
                    .tint(.red)
                    .disabled(selection.isEmpty)
                }
            }
        }
    }

    private var allSelected: Bool {
        !reports.isEmpty && selection.count == reports.count
    }

    private func toggleEditing() {
        if editMode.isEditing {
            exitEditing()
        } else {
            editMode = .active
        }
    }

    private func exitEditing() {
        editMode = .inactive
        selection = []
    }

    // MARK: - List

    private var reportList: some View {
        List(selection: $selection) {
            Section {
                summaryCard
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .selectionDisabled()
            }

            ForEach(groupedByYear, id: \.year) { group in
                Section(String(group.year)) {
                    ForEach(group.reports) { report in
                        // Drop the NavigationLink (and its disclosure chevron) while
                        // selecting — tapping a row toggles its checkmark, not navigation.
                        Group {
                            if editMode.isEditing {
                                ReportRow(report: report)
                            } else {
                                NavigationLink(destination: ReportDetailView(report: report, onDeleted: deleteReport)) {
                                    ReportRow(report: report)
                                }
                            }
                        }
                        .listRowBackground(Rectangle().fill(.ultraThinMaterial))
                        .swipeActions(edge: .leading, allowsFullSwipe: false) {
                            Button { reportToEdit = report } label: {
                                Label("Edit", systemImage: "pencil")
                            }
                            .tint(.blue)
                        }
                        // While selecting, show the multi-select circles instead of
                        // the per-row red delete control (swipe-to-delete still works
                        // outside edit mode).
                        .deleteDisabled(editMode.isEditing)
                    }
                    .onDelete { offsets in pendingDeleteIDs = offsets.map { group.reports[$0].id } }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
    }

    // MARK: - Summary card

    private var summaryCard: some View {
        let totalValues = reports.reduce(0) { $0 + $1.entries.count }
        let latest = reports.map(\.date).max()
        return VStack(spacing: 14) {
            HStack(spacing: 0) {
                stat(value: "\(reports.count)", label: String(localized: "Reports"))
                Divider().frame(height: 34)
                stat(value: "\(totalValues)", label: String(localized: "Lab Values"))
                Divider().frame(height: 34)
                stat(value: "\(distinctCategories.count)", label: String(localized: "Categories"))
            }

            if let latest {
                HStack(spacing: 6) {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.caption2)
                    Text("Last updated \(latest.formatted(date: .abbreviated, time: .omitted))")
                        .font(.caption)
                }
                .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 18)
        .padding(.horizontal, 12)
        .frame(maxWidth: .infinity)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 22))
        .overlay(
            RoundedRectangle(cornerRadius: 22)
                .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
    }

    private func stat(value: String, label: String) -> some View {
        VStack(spacing: 3) {
            Text(value)
                .font(.title2.bold().monospacedDigit())
                .foregroundStyle(.primary)
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Grouping & derived data

    private struct YearGroup {
        let year: Int
        let reports: [LabReport]
    }

    private var groupedByYear: [YearGroup] {
        let calendar = Calendar.current
        let grouped = Dictionary(grouping: reports) { calendar.component(.year, from: $0.date) }
        return grouped
            .map { YearGroup(year: $0.key, reports: $0.value.sorted { $0.date > $1.date }) }
            .sorted { $0.year > $1.year }
    }

    private var distinctCategories: Set<LabCategory> {
        var set = Set<LabCategory>()
        for report in reports {
            for entry in report.entries {
                set.insert(LabCategory.forCode(entry.code))
            }
        }
        return set
    }

    // Up to three category colors for the subtle background wash, ordered by how
    // often they appear across all reports.
    private var backgroundColors: [Color] {
        var counts: [LabCategory: Int] = [:]
        for report in reports {
            for entry in report.entries {
                counts[LabCategory.forCode(entry.code), default: 0] += 1
            }
        }
        // Tiebreak by the category's raw value so equal counts always sort the
        // same way. Swift's sort isn't stable, and this property recomputes on
        // every re-render (e.g. each selection toggle) — without a deterministic
        // order the wash colors would swap corners on unrelated state changes.
        return counts
            .sorted { $0.value != $1.value ? $0.value > $1.value : $0.key.rawValue < $1.key.rawValue }
            .prefix(3)
            .map { $0.key.color }
    }

    // MARK: - Data

    private func loadReports() async {
        if ScreenshotMode.isActive {
            reports = LabReport.sampleHistory
            return
        }
        do {
            reports = try await HealthKitService.shared.loadCDADocuments()
            // Drop selections (and leave edit mode) once nothing is left to act on.
            selection.formIntersection(Set(reports.map(\.id)))
            if reports.isEmpty { exitEditing() }
        } catch {
            loadError = error.localizedDescription
        }
    }

    private func deleteReport(_ id: UUID) {
        // Remove optimistically for instant feedback, then reconcile with
        // Health (the source of truth) once the delete resolves. A failed
        // delete reappears on reload and surfaces an error.
        reports.removeAll { $0.id == id }
        Task {
            do {
                try await HealthKitService.shared.deleteCDADocument(id: id)
            } catch {
                deleteError = error.localizedDescription
            }
            await loadReports()
        }
    }

    private func deleteReports(ids: [UUID]) {
        reports.removeAll { ids.contains($0.id) }
        Task {
            var failed = false
            for id in ids {
                do {
                    try await HealthKitService.shared.deleteCDADocument(id: id)
                } catch {
                    failed = true
                }
            }
            await loadReports()
            if failed {
                deleteError = String(localized: "Some reports couldn't be removed from Apple Health.")
            }
        }
    }
}

// MARK: - ReportRow

private struct ReportRow: View {
    let report: LabReport

    private var categories: [LabCategory] {
        var seen = Set<LabCategory>()
        var ordered: [LabCategory] = []
        for entry in report.entries {
            let category = LabCategory.forCode(entry.code)
            if seen.insert(category).inserted { ordered.append(category) }
        }
        return ordered
    }

    // The dominant category drives the icon color, matching ReportDetailView so
    // the same report reads consistently in the list and on its detail screen.
    private var accentColor: Color {
        report.dominantCategory?.color ?? .accentColor
    }

    private var meta: String {
        [report.patientName, report.authorName]
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
    }

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [accentColor, accentColor.opacity(0.65)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                Image(systemName: "doc.text.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .frame(width: 44, height: 44)
            .shadow(color: accentColor.opacity(0.35), radius: 4, x: 0, y: 2)

            VStack(alignment: .leading, spacing: 4) {
                Text(report.date.formatted(date: .abbreviated, time: .omitted))
                    .font(.headline)

                if !meta.isEmpty {
                    Text(meta)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                HStack(spacing: 6) {
                    CategoryDots(colors: categories.prefix(5).map(\.color))
                    Text("\(report.entries.count) values")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 6)
    }
}

// MARK: - CategoryDots

/// A compact row of overlapping colored dots representing the clinical
/// categories present in a report.
private struct CategoryDots: View {
    let colors: [Color]

    var body: some View {
        HStack(spacing: -4) {
            ForEach(Array(colors.enumerated()), id: \.offset) { _, color in
                Circle()
                    .fill(color)
                    .frame(width: 9, height: 9)
                    .overlay(
                        Circle().stroke(Color(.systemBackground), lineWidth: 1.2)
                    )
            }
        }
    }
}

// MARK: - Preview

#Preview("History") {
    NavigationStack {
        HistoryView()
    }
}

#Preview("Report Row") {
    List {
        ReportRow(report: .sample)
    }
}
