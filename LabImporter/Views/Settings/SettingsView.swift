import SafariServices
import SwiftUI

// MARK: - App info

enum AppInfo {
    private static func string(_ key: String) -> String? {
        guard let value = Bundle.main.infoDictionary?[key] as? String,
              !value.isEmpty else { return nil }
        return value
    }

    static var version: String { string("CFBundleShortVersionString") ?? "—" }
    static var build: String { string("CFBundleVersion") ?? "—" }
    static var branch: String { string("GitBranch") ?? "unknown" }
    static var commit: String { string("GitCommit") ?? "unknown" }

    /// The project's funding destinations, parsed from the repo's
    /// `.github/FUNDING.yml`. The "Embed Build Metadata" build phase serializes
    /// that file to JSON and stamps it into `Info.plist` (base64-encoded under
    /// `FundingConfig`); here we decode it into one `FundingLink` per entry,
    /// honoring the same platform set GitHub's Sponsor button supports. Returns
    /// an empty array when nothing is stamped (e.g. a fork with no funding, or a
    /// local build), in which case the Support section is hidden.
    static var fundingLinks: [FundingLink] {
        guard let encoded = string("FundingConfig"),
              let data = Data(base64Encoded: encoded),
              let config = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return []
        }
        // `allCases` declaration order drives the display order in Settings.
        return FundingPlatform.allCases.flatMap { platform -> [FundingLink] in
            // A platform's value is either a single scalar (e.g. `ko_fi: name`)
            // or an array (e.g. `github: [a, b]`, `custom: [url1, url2]`).
            let raw = config[platform.rawValue]
            let values: [String]
            if let single = raw as? String {
                values = [single]
            } else if let many = raw as? [Any] {
                values = many.compactMap { $0 as? String }
            } else {
                values = []
            }
            return values.compactMap { value in
                let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty, let url = platform.url(for: trimmed) else { return nil }
                return FundingLink(platform: platform, url: url)
            }
        }
    }

    /// The app's license text, read from the `LICENSE` file copied into the
    /// bundle at build time (see the "Copy LICENSE" build phase). This keeps the
    /// single source of truth in the repo-root `LICENSE` rather than duplicating
    /// it in source. Returns a short message if the file is missing.
    static var licenseText: String {
        guard let url = Bundle.main.url(forResource: "LICENSE", withExtension: nil),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return String(localized: "The license is unavailable in this build.")
        }
        return text
    }

    /// Web URL of the repository this build came from, stamped into `Info.plist`
    /// at build time (`GitRepositoryURL`) so forks open their own repo. Returns
    /// `nil` when the build did not stamp a URL (e.g. local Xcode builds), in
    /// which case the GitHub buttons are hidden.
    static var repositoryURL: URL? {
        guard let value = string("GitRepositoryURL") else { return nil }
        return webURL(from: value)
    }

    /// Whether the source repository has GitHub Issues enabled, stamped into
    /// `Info.plist` at build time (`GitHasIssues`) by querying the GitHub API.
    /// Returns `nil` when the build couldn't determine it (e.g. a local Xcode
    /// build, a non-GitHub remote, or no network) — callers treat `nil` as
    /// "unknown" and keep showing issue affordances rather than hiding them.
    static var repositoryHasIssues: Bool? {
        guard let value = string("GitHasIssues") else { return nil }
        return (value as NSString).boolValue
    }

    /// URL that opens the "new issue" composer for `repositoryURL`, pre-filling
    /// the body with build metadata to help triage reports. Returns `nil` when
    /// the repository has Issues disabled (`repositoryHasIssues == false`) so
    /// the "Report an Issue" row is hidden.
    static var newIssueURL: URL? {
        guard repositoryHasIssues != false,
              let base = repositoryURL?.appendingPathComponent("issues/new"),
              var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else { return nil }
        let body = """


        ---
        Version: \(version) (\(build))
        Branch: \(branch)
        Commit: \(commit)
        """
        components.queryItems = [URLQueryItem(name: "body", value: body)]
        return components.url
    }

    /// Normalizes a git remote string (`https`, `.git` suffix, or `git@host:owner/repo`
    /// SSH form) into a browsable `https` web URL.
    private static func webURL(from remote: String) -> URL? {
        var value = remote.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return nil }

        if let range = value.range(of: "git@") {
            // git@github.com:owner/repo(.git) -> https://github.com/owner/repo
            let hostAndPath = value[range.upperBound...].replacingOccurrences(of: ":", with: "/")
            value = "https://" + hostAndPath
        }
        if value.hasSuffix(".git") {
            value = String(value.dropLast(4))
        }
        return URL(string: value)
    }
}

// MARK: - SettingsView

struct SettingsView: View {
    @Binding var prefs: LabDisplayPreferences
    let allCodes: [CodeName]
    /// `true` when presented as a sheet (iPhone) — shows a Close button. `false`
    /// when hosted as the detail pane of the iPad sidebar, where the split view
    /// owns dismissal and a Close button would be out of place.
    var isModal = true
    @Environment(\.dismiss) private var dismiss
    @State private var browserURL: IdentifiedURL?
    @AppStorage(CloudSyncService.enabledKey) private var iCloudSyncEnabled = false

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

    /// Row title. Custom links have no brand, so we show their host (e.g.
    /// `paypal.me`); every other platform shows its brand name.
    var title: String {
        if platform == .custom {
            return url.host?.replacingOccurrences(of: "www.", with: "") ?? url.absoluteString
        }
        return platform.brandName
    }
}

/// The funding platforms GitHub's `FUNDING.yml` supports. Raw values match the
/// YAML keys verbatim so a parsed config maps straight onto these cases, and
/// `allCases` declaration order is the order rows appear in Settings.
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

    /// Brand name shown in the row (not localized — these are proper nouns).
    var brandName: String {
        switch self {
        case .github: return "GitHub Sponsors"
        case .koFi: return "Ko-fi"
        case .buyMeACoffee: return "Buy Me a Coffee"
        case .patreon: return "Patreon"
        case .openCollective: return "Open Collective"
        case .liberapay: return "Liberapay"
        case .polar: return "Polar"
        case .issuehunt: return "IssueHunt"
        case .tidelift: return "Tidelift"
        case .communityBridge: return "LFX Mentorship"
        case .lfxCrowdfunding: return "LFX Crowdfunding"
        case .thanksDev: return "thanks.dev"
        case .custom: return "Custom"
        }
    }

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

    /// `String(format:)` template for the funding URL, with `%@` standing in for
    /// the entry value. Templates mirror the ones GitHub's Sponsor button uses;
    /// `custom` entries are already full URLs, so their template is just `%@`.
    private var urlTemplate: String {
        switch self {
        case .github: return "https://github.com/sponsors/%@"
        case .koFi: return "https://ko-fi.com/%@"
        case .buyMeACoffee: return "https://www.buymeacoffee.com/%@"
        case .patreon: return "https://www.patreon.com/%@"
        case .openCollective: return "https://opencollective.com/%@"
        case .liberapay: return "https://liberapay.com/%@/donate"
        case .polar: return "https://polar.sh/%@"
        case .issuehunt: return "https://issuehunt.io/r/%@"
        case .tidelift: return "https://tidelift.com/funding/github/%@"
        case .communityBridge: return "https://mentorship.lfx.linuxfoundation.org/project/%@"
        case .lfxCrowdfunding: return "https://crowdfunding.lfx.linuxfoundation.org/projects/%@"
        case .thanksDev: return "https://thanks.dev/%@"
        case .custom: return "%@"
        }
    }

    /// Builds the public funding URL for a parsed entry value. The value is the
    /// raw YAML value: a username for most platforms, `platform/package` for
    /// Tidelift, the `u/gh/<user>` path tail for thanks.dev, or a full URL for
    /// `custom`.
    func url(for value: String) -> URL? {
        // FUNDING.yml allows schemeless custom URLs (e.g. `octocat.com`), but the
        // in-app browser only opens http(s) — default a missing scheme to https.
        if self == .custom, !value.contains("://") {
            return URL(string: "https://\(value)")
        }
        return URL(string: String(format: urlTemplate, value))
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
#Preview("Settings") {
    @Previewable @State var prefs = LabDisplayPreferences()
    SettingsView(prefs: $prefs, allCodes: CodeName.sampleCodes)
}
#endif
