import SwiftUI
import VisionKit

struct HomeView: View {
    // Report state
    @State private var reports: [LabReport] = []
    @State private var isLoaded = false

    // Import flow state
    @State private var importEngine = LabImportEngine()
    @State private var labValues: [LabValue] = []
    @State private var parsedReportDate: Date?
    @State private var parsedPatientName: String?
    @State private var parsedAuthorName: String?
    @State private var showReview = false
    @State private var clipboardHasContent = false
    /// An "Open With" file that arrived before onboarding was dismissed; held
    /// back so its review sheet isn't presented underneath the welcome cover.
    @State private var pendingImportURL: URL?
    @AppStorage("hasSeenWelcome") private var hasSeenWelcome = false

    // iPad sidebar state
    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @State private var sidebarSelection: SidebarSection? = .dashboard
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    /// Sections shown in the iPad sidebar. On compact widths (iPhone) these are
    /// reached through the dashboard's own toolbar instead, so the sidebar is
    /// only built when the layout is regular-width.
    private enum SidebarSection: String, CaseIterable, Identifiable {
        case dashboard, reports, settings
        var id: String { rawValue }

        var title: LocalizedStringKey {
            switch self {
            case .dashboard: return "Lab Results"
            case .reports: return "Reports"
            case .settings: return "Settings"
            }
        }

        var icon: String {
            switch self {
            case .dashboard: return "square.grid.2x2"
            case .reports: return "doc.text"
            case .settings: return "gearshape"
            }
        }
    }

    var body: some View {
        // The layout adapts to width — a sidebar split view on regular-width
        // devices (iPad, large iPhones in landscape) and the original stack on
        // compact widths — while the import overlay, review sheet, report
        // loading and onboarding stay shared across both so behavior is
        // identical no matter how the content is presented.
        Group {
            if horizontalSizeClass == .regular {
                splitRoot
            } else {
                compactRoot
            }
        }
        // The processing HUD is presented in its own top-level UIWindow (see
        // `labImport`), so it floats above the navigation bar and blocks the
        // toolbar buttons (history/settings/import) while an import is running.
        .labImport(engine: importEngine)
        .sheet(isPresented: $showReview) {
            NavigationStack {
                ReviewView(
                    labValues: labValues,
                    reportDate: parsedReportDate ?? Date(),
                    extractedPatientName: parsedPatientName,
                    extractedAuthorName: parsedAuthorName
                )
            }
            .interactiveDismissDisabled()
        }
        .task { await loadReports() }
        .onChange(of: showReview) { _, showing in
            if !showing { Task { await loadReports() } }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            Task { await loadReports() }
        }
        .onAppear {
            refreshClipboardState()
            configureImportEngine()
        }
        .onOpenURL { url in
            handleIncomingFile(url)
        }
        .fullScreenCover(isPresented: Binding(
            get: { !hasSeenWelcome },
            set: { _ in }
        )) {
            WelcomeView {
                hasSeenWelcome = true
                flushPendingImport()
            }
        }
    }

    // MARK: - Compact layout (iPhone)

    private var compactRoot: some View {
        NavigationStack {
            mainContent(showsLibraryToolbarItems: true)
        }
    }

    // MARK: - Regular layout (iPad)

    private var splitRoot: some View {
        NavigationSplitView {
            List(selection: $sidebarSelection) {
                ForEach(SidebarSection.allCases) { section in
                    Label(section.title, systemImage: section.icon)
                        .tag(section)
                }
            }
            .navigationTitle("Lab Importer")
            .navigationSplitViewColumnWidth(min: 240, ideal: 280)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    importMenu
                }
            }
        } detail: {
            detailColumn
        }
        .navigationSplitViewStyle(.balanced)
    }

    @ViewBuilder
    private var detailColumn: some View {
        switch sidebarSelection ?? .dashboard {
        case .dashboard:
            NavigationStack {
                mainContent(showsLibraryToolbarItems: false)
            }
        case .reports:
            NavigationStack {
                HistoryView()
            }
        case .settings:
            SettingsView(prefs: $prefs, allCodes: allCodeNames, isModal: false)
        }
    }

    /// The landing-or-dashboard content shared by both layouts. `showsLibraryToolbarItems`
    /// hides the dashboard's History/Settings toolbar buttons when the sidebar
    /// already provides those destinations.
    @ViewBuilder
    private func mainContent(showsLibraryToolbarItems: Bool) -> some View {
        if !isLoaded {
            ProgressView()
                .scaleEffect(1.4)
        } else if reports.isEmpty {
            ImportLandingView(
                onScan: { importEngine.scan() },
                onPickFile: { importEngine.pickFile() },
                onPaste: { importEngine.paste() },
                onManual: createManually,
                scannerAvailable: VNDocumentCameraViewController.isSupported,
                clipboardAvailable: clipboardHasContent,
                isProcessing: importEngine.isProcessing
            )
        } else {
            DashboardView(
                reports: reports,
                onScan: { importEngine.scan() },
                onPickFile: { importEngine.pickFile() },
                onPaste: { importEngine.paste() },
                onManual: createManually,
                scannerAvailable: VNDocumentCameraViewController.isSupported,
                clipboardAvailable: clipboardHasContent,
                isProcessing: importEngine.isProcessing,
                showsLibraryToolbarItems: showsLibraryToolbarItems
            )
        }
    }

    // MARK: - Sidebar import menu

    private var importMenu: some View {
        Menu {
            Button { importEngine.scan() } label: {
                Label("Scan Document", systemImage: "doc.viewfinder")
            }
            .disabled(!VNDocumentCameraViewController.isSupported)
            Button { importEngine.pickFile() } label: {
                Label("Choose File", systemImage: "folder")
            }
            Button { importEngine.paste() } label: {
                Label("Paste from Clipboard", systemImage: "doc.on.clipboard")
            }
            .disabled(!clipboardHasContent)
            Button(action: createManually) {
                Label("Create Report Manually", systemImage: "square.and.pencil")
            }
        } label: {
            Image(systemName: "plus")
                .fontWeight(.semibold)
        }
        .accessibilityLabel("Import Report")
    }

    /// Distinct lab codes across all reports, used to populate the Settings
    /// sort/visibility editor when Settings is shown as a sidebar detail.
    private var allCodeNames: [CodeName] {
        var seen = Set<String>()
        var result: [CodeName] = []
        for report in reports {
            for entry in report.entries where seen.insert(entry.code).inserted {
                result.append(CodeName(code: entry.code, name: entry.resolvedName))
            }
        }
        return result
    }

    // MARK: - Incoming files ("Open With")

    /// Routes a file opened from another app (share sheet / Files) into the
    /// import pipeline. If onboarding hasn't been completed yet the URL is
    /// stashed and replayed once `WelcomeView` is dismissed — otherwise the
    /// review sheet would present beneath the welcome cover and stay hidden.
    private func handleIncomingFile(_ url: URL) {
        guard hasSeenWelcome else {
            pendingImportURL = url
            return
        }
        Task { await importEngine.processFile(at: url) }
    }

    private func flushPendingImport() {
        guard let url = pendingImportURL else { return }
        pendingImportURL = nil
        Task { await importEngine.processFile(at: url) }
    }

    // MARK: - Import engine

    private func configureImportEngine() {
        importEngine.onParsed = { result in
            labValues = result.values
            parsedReportDate = result.reportDate
            parsedPatientName = result.patientName
            parsedAuthorName = result.authorName
            showReview = true
        }
    }

    // MARK: - Report loading

    private func loadReports() async {
        do {
            reports = try await HealthKitService.shared.loadCDADocuments()
        } catch {
            // Silently ignore authorization errors on first launch before user grants access
        }
        isLoaded = true
    }

    // MARK: - Clipboard

    private func refreshClipboardState() {
        let pasteboard = UIPasteboard.general
        clipboardHasContent = pasteboard.hasImages || pasteboard.hasStrings
    }

    // MARK: - Manual creation

    private func createManually() {
        labValues = []
        parsedReportDate = nil
        parsedPatientName = nil
        parsedAuthorName = nil
        showReview = true
    }
}

#Preview {
    HomeView()
}
