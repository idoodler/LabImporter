import CoreSpotlight
import SwiftUI
import UniformTypeIdentifiers

// MARK: - Deep links

/// Parses and builds the app's custom-scheme deep links. Spotlight results (and
/// any future shortcuts) funnel through here so routing lives in one place. The
/// only link today is a metric detail: `labimporter://metric/<LOINC>` — note it
/// carries a *code*, never a reading, so nothing private rides in the URL.
enum DeepLink {
    static let scheme = "labimporter"

    /// The LOINC code carried by a `labimporter://metric/<code>` URL, or `nil` for
    /// anything that isn't a metric link (e.g. an imported file URL).
    static func metricCode(from url: URL) -> String? {
        guard url.scheme == scheme, url.host == "metric" else { return nil }
        let code = url.lastPathComponent
        return code.isEmpty || code == "/" ? nil : code
    }

    /// The canonical deep link for a metric, also used as its Spotlight identity.
    static func metricURLString(code: String) -> String { "\(scheme)://metric/\(code)" }
}

// MARK: - Spotlight indexing

/// Publishes the user's distinct lab metrics to the system Spotlight index so
/// they can be found from iOS search. Only the *names* of tracked values are
/// exposed — never a reading — and tapping a result deep-links into the app to
/// open that metric's trend detail. The index is reconciled on every report
/// load; identical content is skipped via a signature so the frequent
/// "became active" reload does no work.
@MainActor
final class SpotlightIndexService {
    static let shared = SpotlightIndexService()
    private init() {}

    /// Groups every item under one domain so a single delete clears them when
    /// reports change (or all are removed).
    private static let domainIdentifier = "\(DeepLink.scheme).metric"

    private let index = CSSearchableIndex.default()
    private var lastSignature: Int?
    private var thumbnailCache: [LabCategory: Data] = [:]

    /// Reconciles the Spotlight index with the metrics present in `reports`.
    func reindex(reports: [LabReport]) {
        let codes = distinctNumericCodes(in: reports)
        let signature = signature(for: codes)
        guard signature != lastSignature else { return }
        lastSignature = signature

        guard !codes.isEmpty else {
            index.deleteSearchableItems(withDomainIdentifiers: [Self.domainIdentifier]) { _ in }
            return
        }

        let items = codes.map(makeItem(for:))
        // Clear the domain first so codes from deleted reports don't linger, then
        // publish the current set.
        index.deleteSearchableItems(withDomainIdentifiers: [Self.domainIdentifier]) { [index] _ in
            index.indexSearchableItems(items) { _ in }
        }
    }

    // MARK: - Items

    private func makeItem(for code: String) -> CSSearchableItem {
        let category = LabCategory.forCode(code)
        let name = LabMapping.displayName(for: code)

        let attributes = CSSearchableItemAttributeSet(contentType: .content)
        attributes.title = name
        attributes.displayName = name
        // Deliberately value-free: the category plus a generic label, so a search
        // hit reveals *that* a value is tracked, never its reading.
        attributes.contentDescription = "\(category.displayName) · \(String(localized: "Lab value"))"
        attributes.keywords = keywords(name: name, code: code, category: category)
        attributes.thumbnailData = thumbnail(for: category)

        return CSSearchableItem(
            uniqueIdentifier: DeepLink.metricURLString(code: code),
            domainIdentifier: Self.domainIdentifier,
            attributeSet: attributes
        )
    }

    private func keywords(name: String, code: String, category: LabCategory) -> [String] {
        var keywords = [name, code, category.displayName, String(localized: "Lab value")]
        let catalog = LabMapping.catalogName(for: code)
        if catalog != name { keywords.append(catalog) }
        return keywords
    }

    // MARK: - Distinct codes & signature

    /// Codes that have at least one numeric reading — the same set the dashboard
    /// surfaces and the trend detail can actually chart, so every search hit lands
    /// on a populated screen.
    private func distinctNumericCodes(in reports: [LabReport]) -> [String] {
        var seen = Set<String>()
        var codes: [String] = []
        for report in reports {
            for entry in report.entries where entry.numericValue != nil && seen.insert(entry.code).inserted {
                codes.append(entry.code)
            }
        }
        return codes
    }

    /// A content fingerprint over the indexed codes *and* their resolved names, so
    /// a locale switch or a rename re-publishes fresh titles while an unchanged
    /// reload is a no-op.
    private func signature(for codes: [String]) -> Int {
        var hasher = Hasher()
        for code in codes.sorted() {
            hasher.combine(code)
            hasher.combine(LabMapping.displayName(for: code))
        }
        return hasher.finalize()
    }

    // MARK: - Thumbnail

    /// A rounded tile in the metric's category colour with its glyph — mirroring
    /// the dashboard's card styling so a search hit feels like the app. Thumbnails
    /// depend only on the category, so the handful of possibilities are rendered
    /// once and cached.
    private func thumbnail(for category: LabCategory) -> Data? {
        if let cached = thumbnailCache[category] { return cached }
        let renderer = ImageRenderer(content: SpotlightMetricThumbnail(category: category))
        renderer.scale = 3
        guard let data = renderer.uiImage?.pngData() else { return nil }
        thumbnailCache[category] = data
        return data
    }
}

// MARK: - Thumbnail view

/// The Spotlight result icon: a category-tinted rounded tile with the category's
/// SF Symbol, echoing the dashboard's `RoundedRectangle` + category-colour look.
private struct SpotlightMetricThumbnail: View {
    let category: LabCategory

    var body: some View {
        RoundedRectangle(cornerRadius: 22, style: .continuous)
            .fill(category.color.gradient)
            .frame(width: 96, height: 96)
            .overlay(
                Image(systemName: category.icon)
                    .font(.system(size: 44, weight: .semibold))
                    .foregroundStyle(.white)
            )
    }
}
