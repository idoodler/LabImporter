import Foundation
import LabImporterCore

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
        // A platform's value is either a single scalar (e.g. `ko_fi: name`) or an
        // array (e.g. `github: [a, b]`); the shared core (LabImporterCore `About`)
        // turns entries into URLs and titles, identically on Android.
        let entries = config.mapValues { raw -> [String] in
            if let single = raw as? String { return [single] }
            return (raw as? [Any])?.compactMap { $0 as? String } ?? []
        }
        return LabImporterCore.About.shared.fundingLinks(config: entries).compactMap { link in
            guard let platform = FundingPlatform(rawValue: link.platform.key),
                  let url = URL(string: link.url) else { return nil }
            return FundingLink(platform: platform, url: url, title: link.title)
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
        LabImporterCore.About.shared.repositoryWebUrl(remote: string("GitRepositoryURL")).flatMap(URL.init(string:))
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
        guard repositoryHasIssues != false, let repository = repositoryURL else { return nil }
        return URL(string: LabImporterCore.About.shared.issueReportUrl(
            repositoryUrl: repository.absoluteString, version: version, build: build, branch: branch, commit: commit
        ))
    }
}
