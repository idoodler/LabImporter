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

// MARK: - Search preferences

/// Namespace for the Spotlight feature's user preferences. Kept non-isolated so
/// both the indexing service and the SwiftUI `@AppStorage` bindings can share the
/// same key.
enum SpotlightSearch {
    /// Off by default: when the user opts in, each metric's most recent reading is
    /// surfaced in search results (a value tile + the reading in the subtitle).
    /// Stored in standard `UserDefaults` — a local device preference, not synced.
    static let showLatestValueKey = "showLatestValueInSearch"
}

// MARK: - Spotlight indexing

/// Publishes the user's distinct lab metrics to the system Spotlight index so
/// they can be found from iOS search. By default only the *names* of tracked
/// values are exposed; the user can opt in (Settings) to also surface each
/// metric's latest reading. Tapping a result deep-links into the app to open
/// that metric's trend detail. The index is reconciled on every report load and
/// whenever the preference changes; identical content is skipped via a signature
/// so the frequent "became active" reload does no work.
@MainActor
final class SpotlightIndexService {
    static let shared = SpotlightIndexService()
    private init() {}

    /// Groups every item under one domain so a single delete clears them when
    /// reports change (or all are removed).
    private static let domainIdentifier = "\(DeepLink.scheme).metric"

    /// A metric reduced to its most recent reading — the unit of indexing.
    private struct LatestMetric {
        let code: String
        let entry: LabReport.Entry
        let date: Date
    }

    private let index = CSSearchableIndex.default()
    private var lastSignature: Int?
    private var thumbnailCache: [String: Data] = [:]

    /// Reconciles the Spotlight index with the metrics present in `reports`.
    func reindex(reports: [LabReport]) {
        let showValue = UserDefaults.standard.bool(forKey: SpotlightSearch.showLatestValueKey)
        let metrics = latestMetrics(in: reports)
        let signature = signature(for: metrics, showValue: showValue)
        guard signature != lastSignature else { return }
        lastSignature = signature

        guard !metrics.isEmpty else {
            index.deleteSearchableItems(withDomainIdentifiers: [Self.domainIdentifier]) { _ in }
            return
        }

        let items = metrics.map { makeItem(for: $0, showValue: showValue) }
        // Clear the domain first so codes from deleted reports don't linger, then
        // publish the current set.
        index.deleteSearchableItems(withDomainIdentifiers: [Self.domainIdentifier]) { [index] _ in
            index.indexSearchableItems(items) { _ in }
        }
    }

    // MARK: - Items

    private func makeItem(for metric: LatestMetric, showValue: Bool) -> CSSearchableItem {
        let category = LabCategory.forCode(metric.code)
        let name = LabMapping.displayName(for: metric.code)

        let attributes = CSSearchableItemAttributeSet(contentType: .content)
        attributes.title = name
        attributes.displayName = name
        attributes.contentDescription = description(for: metric, category: category, showValue: showValue)
        attributes.keywords = keywords(name: name, code: metric.code, category: category)
        attributes.thumbnailData = thumbnail(for: metric, category: category, showValue: showValue)

        return CSSearchableItem(
            uniqueIdentifier: DeepLink.metricURLString(code: metric.code),
            domainIdentifier: Self.domainIdentifier,
            attributeSet: attributes
        )
    }

    /// The subtitle. With the opt-in off it's deliberately value-free (category +
    /// a generic label); with it on it shows the latest reading and its date.
    private func description(for metric: LatestMetric, category: LabCategory, showValue: Bool) -> String {
        guard showValue else {
            return "\(category.displayName) · \(String(localized: "Lab value"))"
        }
        let date = metric.date.formatted(date: .abbreviated, time: .omitted)
        return "\(valueText(for: metric.entry)) · \(date)"
    }

    /// The reading with its unit, e.g. `5.4 %` (unit omitted when there isn't one).
    private func valueText(for entry: LabReport.Entry) -> String {
        entry.unit.isEmpty ? entry.displayValue : "\(entry.displayValue) \(entry.unit)"
    }

    private func keywords(name: String, code: String, category: LabCategory) -> [String] {
        var keywords = [name, code, category.displayName, String(localized: "Lab value")]
        let catalog = LabMapping.catalogName(for: code)
        if catalog != name { keywords.append(catalog) }
        return keywords
    }

    // MARK: - Latest readings & signature

    /// The most recent numeric reading per code — the same metrics the dashboard
    /// surfaces and the trend detail can chart, so every search hit lands on a
    /// populated screen.
    private func latestMetrics(in reports: [LabReport]) -> [LatestMetric] {
        var latest: [String: LatestMetric] = [:]
        for report in reports {
            for entry in report.entries where entry.numericValue != nil {
                if let existing = latest[entry.code], existing.date >= report.date { continue }
                latest[entry.code] = LatestMetric(code: entry.code, entry: entry, date: report.date)
            }
        }
        return Array(latest.values)
    }

    /// A content fingerprint over the indexed codes, their resolved names and —
    /// when surfaced — their latest readings, so a locale switch, a rename, a new
    /// report or toggling the preference re-publishes while an unchanged reload is
    /// a no-op.
    private func signature(for metrics: [LatestMetric], showValue: Bool) -> Int {
        var hasher = Hasher()
        hasher.combine(showValue)
        for metric in metrics.sorted(by: { $0.code < $1.code }) {
            hasher.combine(metric.code)
            hasher.combine(LabMapping.displayName(for: metric.code))
            if showValue {
                hasher.combine(metric.entry.displayValue)
                hasher.combine(metric.entry.unit)
                hasher.combine(metric.date)
            }
        }
        return hasher.finalize()
    }

    // MARK: - Thumbnail

    /// A rounded tile in the metric's category colour — mirroring the dashboard's
    /// card styling so a search hit feels like the app. Shows the latest reading
    /// when the user has opted in, otherwise the category's glyph. Rendered tiles
    /// are cached by their visible content (category, and value/unit when shown).
    private func thumbnail(for metric: LatestMetric, category: LabCategory, showValue: Bool) -> Data? {
        let value = showValue ? metric.entry.displayValue : nil
        let unit = showValue ? metric.entry.unit : nil
        let cacheKey = "\(category.rawValue)|\(value ?? "")|\(unit ?? "")"
        if let cached = thumbnailCache[cacheKey] { return cached }

        let tile = SpotlightMetricThumbnail(category: category, value: value, unit: unit)
        let renderer = ImageRenderer(content: tile)
        renderer.scale = 3
        guard let data = renderer.uiImage?.pngData() else { return nil }
        thumbnailCache[cacheKey] = data
        return data
    }
}

// MARK: - Thumbnail view

/// The Spotlight result icon: a category-tinted rounded tile echoing the
/// dashboard's `RoundedRectangle` + category-colour look. Shows the latest
/// reading as a compact value "widget" when one is supplied, otherwise the
/// category's SF Symbol.
private struct SpotlightMetricThumbnail: View {
    let category: LabCategory
    var value: String?
    var unit: String?

    var body: some View {
        RoundedRectangle(cornerRadius: 22, style: .continuous)
            .fill(category.color.gradient)
            .frame(width: 96, height: 96)
            .overlay { content }
    }

    @ViewBuilder
    private var content: some View {
        if let value {
            VStack(spacing: 1) {
                Text(value)
                    .font(.system(size: 32, weight: .bold, design: .rounded))
                if let unit, !unit.isEmpty {
                    Text(unit)
                        .font(.system(size: 15, weight: .semibold))
                        .opacity(0.85)
                }
            }
            .foregroundStyle(.white)
            .lineLimit(1)
            .minimumScaleFactor(0.4)
            .padding(10)
        } else {
            Image(systemName: category.icon)
                .font(.system(size: 44, weight: .semibold))
                .foregroundStyle(.white)
        }
    }
}
