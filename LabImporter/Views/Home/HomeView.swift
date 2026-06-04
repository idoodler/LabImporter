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
    /// Whether the user has acknowledged the medical disclaimer shown after the
    /// welcome screen and before the Apple Health permission gate.
    @AppStorage("hasAcknowledgedDisclaimer") private var hasAcknowledgedDisclaimer = false
    @AppStorage("hasGrantedHealthAccess") private var hasGrantedHealthAccess = false
    /// Whether the user has made the required up-front iCloud sync decision.
    /// Gates entry into the app so no reports can be added before deciding.
    @AppStorage("hasChosenICloudSync") private var hasChosenICloudSync = false
    @AppStorage(CloudSyncService.enabledKey) private var iCloudSyncEnabled = false

    // iPad sidebar state
    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @State private var sidebarSelection: SidebarSection? = .dashboard

    /// Whether to present the iPad sidebar split view. Keyed off the device
    /// idiom rather than `horizontalSizeClass` on purpose: a large iPhone flips
    /// between compact (portrait) and regular (landscape) on every rotation, and
    /// driving the root layout off that swaps the whole navigation container —
    /// tearing down any pushed screen and dumping the user back on the
    /// dashboard. The idiom is stable across rotation, so the iPhone keeps its
    /// `NavigationStack` (and its navigation state) and only the iPad gets the
    /// sidebar. `NavigationSplitView` still collapses itself when an iPad is
    /// horizontally compact (Slide Over), so no behavior is lost there.
    private var usesSidebarLayout: Bool {
        UIDevice.current.userInterfaceIdiom == .pad
    }

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
        // The layout adapts to the device — a sidebar split view on iPad and the
        // original stack on iPhone — while the import overlay, review sheet,
        // report loading and onboarding stay shared across both so behavior is
        // identical no matter how the content is presented. See
        // `usesSidebarLayout` for why this is keyed off the idiom, not the size
        // class, so rotating an iPhone doesn't reset navigation.
        Group {
            if usesSidebarLayout {
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
        .task {
            if isScreenshotMode {
                setupScreenshotMode()
                return
            }
            // Reinstalled apps may still have CDA write access from a previous
            // launch — in that case skip the permission gate entirely.
            if !hasGrantedHealthAccess,
               HealthKitService.shared.cdaWriteAuthorizationStatus() == .sharingAuthorized {
                hasGrantedHealthAccess = true
            }
            await loadReportsIfAuthorized()
        }
        .onChange(of: hasGrantedHealthAccess) { _, granted in
            // Hold report loading until the user has cleared the permission
            // gate — otherwise the system prompt fires before the explainer.
            if granted { Task { await loadReportsIfAuthorized() } }
        }
        .onChange(of: showReview) { _, showing in
            if !showing { Task { await loadReportsIfAuthorized() } }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            // Re-reading the pasteboard here is essential: copying happens in
            // *another* app, so the only signal we get is becoming active again.
            // Without this the Paste button stays stuck in whatever state it had
            // at launch until the app is fully relaunched.
            refreshClipboardState()
            Task { await loadReportsIfAuthorized() }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIPasteboard.changedNotification)) { _ in
            // Live updates while we're already foregrounded (e.g. copying from a
            // text field in this app, or via Split View / Slide Over).
            refreshClipboardState()
        }
        .onReceive(NotificationCenter.default.publisher(for: .labReportsDidChange)) { _ in
            // A report was saved, edited, or deleted — possibly from the pushed
            // Reports list, which has its own state. Reload so the Dashboard and
            // Trends reflect the change immediately instead of staying stale until
            // the app next becomes active.
            Task { await loadReportsIfAuthorized() }
        }
        .onAppear {
            refreshClipboardState()
            configureImportEngine()
        }
        .onOpenURL { url in
            handleIncomingFile(url)
        }
        .fullScreenCover(isPresented: Binding(
            get: { !hasSeenWelcome || !hasAcknowledgedDisclaimer || !hasGrantedHealthAccess || !hasChosenICloudSync },
            set: { _ in }
        )) {
            onboardingFlow
        }
    }

    /// Four-step onboarding: marketing welcome → medical disclaimer →
    /// Apple Health permission gate → required iCloud sync decision. Swapping the
    /// inner view inside the same fullScreenCover keeps the cover presented
    /// without a dismiss/re-present flash between steps. The iCloud decision is
    /// mandatory, so the cover stays up — and the dashboard / import entry points
    /// stay unreachable — until the user picks an option.
    @ViewBuilder
    private var onboardingFlow: some View {
        if !hasSeenWelcome {
            WelcomeView {
                withAnimation(.smooth(duration: 0.35)) {
                    hasSeenWelcome = true
                }
            }
            .transition(.opacity)
        } else if !hasAcknowledgedDisclaimer {
            DisclaimerView {
                withAnimation(.smooth(duration: 0.35)) {
                    hasAcknowledgedDisclaimer = true
                }
            }
            .transition(.opacity)
        } else if !hasGrantedHealthAccess {
            HealthPermissionView {
                withAnimation(.smooth(duration: 0.35)) {
                    hasGrantedHealthAccess = true
                }
            }
            .transition(.opacity)
        } else {
            CloudSyncOptInView { enabled in
                iCloudSyncEnabled = enabled
                withAnimation(.smooth(duration: 0.35)) {
                    hasChosenICloudSync = true
                }
                flushPendingImport()
            }
            .transition(.opacity)
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
                isProcessing: importEngine.isProcessing,
                showsLibraryToolbarItems: showsLibraryToolbarItems
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
        guard hasSeenWelcome, hasAcknowledgedDisclaimer, hasGrantedHealthAccess, hasChosenICloudSync else {
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

    /// Wraps `loadReports` so it does nothing until the user has cleared the
    /// Apple Health permission gate. Without this guard, `.task` would call
    /// `requestAuthorization` and surface the system prompt before the
    /// explainer view has even appeared.
    private func loadReportsIfAuthorized() async {
        guard hasGrantedHealthAccess else { return }
        await loadReports()
    }

    private func loadReports() async {
        guard !isScreenshotMode else { return }
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

// MARK: - Screenshot mode

private extension HomeView {
    var isScreenshotMode: Bool { CommandLine.arguments.contains("--ss") }

    var screenshotScreen: String {
        guard let idx = CommandLine.arguments.firstIndex(of: "--ss-screen"),
              CommandLine.arguments.indices.contains(idx + 1)
        else { return "dashboard" }
        return CommandLine.arguments[idx + 1]
    }

    func setupScreenshotMode() {
        hasSeenWelcome = true
        hasAcknowledgedDisclaimer = true
        hasGrantedHealthAccess = true
        hasChosenICloudSync = true
        reports = LabReport.sampleHistory
        isLoaded = true
        if screenshotScreen == "review" {
            labValues = LabReport.sampleHistory[0].asLabValues
            parsedPatientName = LabReport.sampleHistory[0].patientName
            parsedAuthorName = LabReport.sampleHistory[0].authorName
            parsedReportDate = LabReport.sampleHistory[0].date
            showReview = true
        }
    }
}

#Preview {
    HomeView()
}
