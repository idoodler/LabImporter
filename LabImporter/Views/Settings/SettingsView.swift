import SafariServices
import SwiftUI

// MARK: - SettingsView

struct SettingsView: View {
    @Binding var prefs: LabDisplayPreferences
    let allCodes: [CodeName]
    /// `true` when presented as a sheet (iPhone) — shows a Close button. `false`
    /// when hosted as the detail pane of the iPad sidebar, where the split view
    /// owns dismissal and a Close button would be out of place.
    var isModal = true
    /// Every saved report, for exporting them to another device (and to skip
    /// what's already here when importing).
    var reports: [LabReport] = []
    @Environment(\.dismiss) private var dismiss
    @State private var showTransferImporter = false
    @State private var transferResult: TransferResult?
    @State private var browserURL: IdentifiedURL?
    @AppStorage(CloudSyncService.enabledKey) private var iCloudSyncEnabled = false
    @AppStorage(SpotlightSearch.showLatestValueKey) private var showLatestValueInSearch = false
    @AppStorage(SiriExposurePreferences.storageKey) private var siriPrefs = SiriExposurePreferences()

    var body: some View {
        NavigationStack {
            List {
                Section {
                    BuildInfoCard()
                        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                        .listRowBackground(Color.clear)
                }

                Section("Dashboard") {
                    NavigationLink {
                        LabSortEditor(prefs: $prefs, allCodes: allCodes)
                    } label: {
                        SettingsRowLabel("Sort & Visibility",
                                         systemImage: "arrow.up.arrow.down", color: .blue)
                    }
                }

                transferSection

                Section {
                    Toggle(isOn: $iCloudSyncEnabled) {
                        SettingsRowLabel("iCloud Sync",
                                         systemImage: "arrow.triangle.2.circlepath", color: .blue)
                    }
                } footer: {
                    Text("""
                    Sync your dashboard layout — the card order, what you pin and hide, your \
                    nicknames, and your reference ranges — across your devices. Your lab values \
                    stay in Apple Health.
                    """)
                }

                Section {
                    Toggle(isOn: $showLatestValueInSearch) {
                        SettingsRowLabel("Show Latest Value in Search",
                                         systemImage: "magnifyingglass", color: .orange)
                    }
                } header: {
                    Text("Search")
                } footer: {
                    Text("""
                    Let each value's most recent reading appear in iOS search results. \
                    When off, only the value's name is shown — never a reading.
                    """)
                }

                Section {
                    Toggle(isOn: $siriPrefs.isEnabled) {
                        SettingsRowLabel("Allow Siri Access",
                                         systemImage: "waveform", color: .purple)
                    }
                    NavigationLink {
                        SiriAccessEditor(prefs: $siriPrefs, allCodes: allCodes)
                    } label: {
                        SettingsRowLabel("Manage Siri Access",
                                         systemImage: "hand.raised", color: .purple)
                    }
                } header: {
                    Text("Siri & Shortcuts")
                } footer: {
                    Text("""
                    Let Siri answer questions and start scans for LabImporter. You still choose \
                    exactly which values it can read.
                    """)
                }

                Section("LOINC") {
                    NavigationLink {
                        LoincCatalogView()
                    } label: {
                        SettingsRowLabel("Browse Catalog",
                                         systemImage: "magnifyingglass", color: .indigo)
                    }
                    NavigationLink {
                        LoincLicenseView()
                    } label: {
                        SettingsRowLabel("LOINC License", systemImage: "doc.text", color: .teal)
                    }
                    if !LoincDirectory.shared.version.isEmpty {
                        LabeledContent {
                            Text(verbatim: LoincDirectory.shared.version)
                        } label: {
                            SettingsRowLabel("Version", systemImage: "number.square", color: .gray)
                        }
                    }
                }

                Section("About") {
                    NavigationLink {
                        LicenseView()
                    } label: {
                        SettingsRowLabel("License", systemImage: "doc.text", color: .pink)
                    }
                    if let repository = AppInfo.repositoryURL {
                        linkRow("View on GitHub",
                                systemImage: "chevron.left.forwardslash.chevron.right",
                                color: .gray, url: repository)
                    }
                    if let newIssue = AppInfo.newIssueURL {
                        linkRow("Report an Issue",
                                systemImage: "exclamationmark.bubble",
                                color: .red, url: newIssue)
                    }
                }

                let fundingLinks = AppInfo.fundingLinks
                if !fundingLinks.isEmpty {
                    Section {
                        ForEach(fundingLinks) { link in
                            linkRow(label: SettingsRowLabel(verbatim: link.title,
                                                            systemImage: link.platform.systemImage,
                                                            color: link.platform.color),
                                    url: link.url)
                        }
                    } header: {
                        Text("Support")
                    } footer: {
                        Text("LabImporter is free and open source. If you find it useful, you can support its development.")
                    }
                }

                Section {
                    SettingsRowLabel("Not Medical Advice",
                                     systemImage: "cross.case", color: .red)
                } footer: {
                    Text("""
                    LabImporter is not a medical device and does not provide medical advice, \
                    diagnosis, or treatment. Extracted values may be inaccurate or incomplete — \
                    always verify them against your original report. Never make medical decisions \
                    based on this app; consult a qualified healthcare professional about your results.
                    """)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                if isModal {
                    ToolbarItem(placement: .topBarLeading) {
                        Button(role: .close) { dismiss() }
                    }
                }
            }
            .sheet(item: $browserURL) { item in
                SafariView(url: item.url)
                    .ignoresSafeArea()
            }
            .fileImporter(isPresented: $showTransferImporter, allowedContentTypes: ReportTransferService.importTypes) { result in
                guard case .success(let url) = result else { return }
                Task { await importReports(from: url) }
            }
            .alert(item: $transferResult) { result in
                Alert(title: Text(result.title), message: Text(result.message))
            }
        }
    }

    // MARK: - Transfer (iPhone ⇄ Android)

    private var transferSection: some View {
        Section {
            ShareLink(item: ReportArchive(reports: reports), preview: SharePreview("LabImporter Reports")) {
                SettingsRowLabel("Export All Reports", systemImage: "square.and.arrow.up", color: .green)
            }
            .disabled(reports.isEmpty)
            Button {
                showTransferImporter = true
            } label: {
                SettingsRowLabel("Import Reports", systemImage: "square.and.arrow.down", color: .green)
            }
        } header: {
            Text("Transfer")
        } footer: {
            Text("""
            Move your reports between iPhone and Android: export them as a file and import it \
            in LabImporter on the other device. Reports that are already there are skipped.
            """)
        }
    }

    private func importReports(from url: URL) async {
        do {
            let summary = try await ReportTransferService.importFile(at: url, existing: reports)
            transferResult = TransferResult(
                title: String(localized: "Reports Imported"),
                message: String(localized: "New reports: \(summary.imported) · Already present: \(summary.alreadyPresent)")
            )
        } catch {
            transferResult = TransferResult(title: String(localized: "Import Failed"), message: error.localizedDescription)
        }
    }

    /// A standard settings row that opens a web URL in the in-app browser, with a
    /// trailing external-link glyph to signal it leaves the app.
    private func linkRow(_ titleKey: LocalizedStringKey,
                         systemImage: String,
                         color: Color,
                         url: URL) -> some View {
        linkRow(label: SettingsRowLabel(titleKey, systemImage: systemImage, color: color), url: url)
    }

    /// Shared link-row body, taking a pre-built label so callers can supply either
    /// a localized title or a verbatim brand name (funding platforms).
    private func linkRow(label: SettingsRowLabel, url: URL) -> some View {
        Button {
            browserURL = IdentifiedURL(url: url)
        } label: {
            HStack {
                label
                Spacer()
                Image(systemName: "arrow.up.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            // Make the whole row (including the gap the Spacer opens up)
            // tappable, not just the label and trailing glyph.
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens in Safari")
    }
}

// MARK: - SettingsRowLabel

/// A list-row label with a rounded, color-filled icon tile in the style of the
/// iOS Settings app — used to give the otherwise plain settings screens some life.
struct SettingsRowLabel: View {
    private let title: Text
    let systemImage: String
    let color: Color

    init(_ title: LocalizedStringKey, systemImage: String, color: Color) {
        self.title = Text(title)
        self.systemImage = systemImage
        self.color = color
    }

    /// Verbatim variant for non-localizable text such as funding-platform brand
    /// names ("Ko-fi", "Patreon", …) which must render exactly as written.
    init(verbatim title: String, systemImage: String, color: Color) {
        self.title = Text(verbatim: title)
        self.systemImage = systemImage
        self.color = color
    }

    var body: some View {
        Label {
            title
        } icon: {
            Image(systemName: systemImage)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(color.gradient, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
        }
    }
}

// MARK: - Funding

/// A single funding destination resolved from `FUNDING.yml`: a platform plus the
/// public URL its entry points at.
struct FundingLink: Identifiable {
    let id = UUID()
    let platform: FundingPlatform
    let url: URL
    /// Brand name, or a custom link's host (shared rule, LabImporterCore `FundingLink`).
    let title: String
}

/// The funding platforms GitHub's `FUNDING.yml` supports — raw values are the
/// YAML keys. URLs, titles and display order come from the shared core
/// (LabImporterCore `FundingPlatform`); this adds the row's symbol and colour.
enum FundingPlatform: String, CaseIterable {
    case github
    case koFi = "ko_fi"
    case buyMeACoffee = "buy_me_a_coffee"
    case patreon
    case openCollective = "open_collective"
    case liberapay
    case polar
    case issuehunt
    case tidelift
    case communityBridge = "community_bridge"
    case lfxCrowdfunding = "lfx_crowdfunding"
    case thanksDev = "thanks_dev"
    case custom

    var systemImage: String {
        switch self {
        case .github: return "heart.fill"
        case .koFi: return "cup.and.saucer.fill"
        case .buyMeACoffee: return "cup.and.saucer.fill"
        case .patreon: return "p.circle.fill"
        case .openCollective: return "person.3.fill"
        case .liberapay: return "banknote.fill"
        case .polar: return "circle.hexagonpath.fill"
        case .issuehunt: return "ladybug.fill"
        case .tidelift: return "shield.lefthalf.filled"
        case .communityBridge: return "person.2.fill"
        case .lfxCrowdfunding: return "dollarsign.circle.fill"
        case .thanksDev: return "hands.clap.fill"
        case .custom: return "link"
        }
    }

    var color: Color {
        switch self {
        case .github: return .pink
        case .koFi: return .red
        case .buyMeACoffee: return .yellow
        case .patreon: return .orange
        case .openCollective: return .blue
        case .liberapay: return .green
        case .polar: return .indigo
        case .issuehunt: return .mint
        case .tidelift: return .purple
        case .communityBridge: return .teal
        case .lfxCrowdfunding: return .cyan
        case .thanksDev: return .brown
        case .custom: return .gray
        }
    }
}

// MARK: - In-app browser

/// Wraps a `URL` so it can drive a `.sheet(item:)` presentation.
struct IdentifiedURL: Identifiable {
    let id = UUID()
    let url: URL
}

/// SwiftUI wrapper around `SFSafariViewController` for in-app web browsing.
struct SafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }

    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
}

// MARK: - Transfer result

private struct TransferResult: Identifiable {
    let id = UUID()
    let title: String
    let message: String
}

// MARK: - CodeName

struct CodeName: Identifiable {
    var id: String { code }
    let code: String
    let name: String
}

// MARK: - LicenseView

/// The app's MIT license, read from the bundled `LICENSE` file via `AppInfo`.
struct LicenseView: View {
    var body: some View {
        LicenseDocumentView(title: "License", text: AppInfo.licenseText)
    }
}

// MARK: - Previews

#if DEBUG
#Preview("Settings · No Reports") {
    @Previewable @State var prefs = LabDisplayPreferences()
    SettingsView(prefs: $prefs, allCodes: CodeName.sampleCodes)
}

#Preview("Settings · With Reports") {
    @Previewable @State var prefs = LabDisplayPreferences()
    SettingsView(prefs: $prefs, allCodes: CodeName.sampleCodes, reports: LabReport.sampleHistory)
}

#Preview("Settings · Dark") {
    @Previewable @State var prefs = LabDisplayPreferences()
    SettingsView(prefs: $prefs, allCodes: CodeName.sampleCodes, reports: LabReport.sampleHistory)
        .preferredColorScheme(.dark)
}
#endif
