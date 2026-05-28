import Foundation
import SQLite3

// Read-only access to the bundled LOINC reference database
// (LabImporter/Resources/loinc.db). The DB is produced by
// tools/build_loinc_db.py from a Regenstrief LOINC release. When the bundle
// only contains the empty placeholder (zero rows), `isAvailable` is false
// and every query returns empty — callers should fall back to the legacy
// hard-coded code list in LabMapping.
final class LoincDirectory {

    static let shared = LoincDirectory()

    struct Entry: Identifiable, Equatable, Hashable {
        var id: String { loinc }
        let loinc: String
        let longCommonName: String
        let shortname: String?
        let component: String?
        let exampleUnit: String?
        let className: String?
    }

    private let queue = DispatchQueue(label: "LoincDirectory.serial")
    private var handle: OpaquePointer?
    private(set) var isAvailable: Bool = false
    private(set) var version: String?
    private(set) var codeCount: Int = 0
    private(set) var attribution: String?

    private init() {
        open()
    }

    deinit {
        if let handle { sqlite3_close(handle) }
    }

    // MARK: - Open

    private func open() {
        guard let url = Bundle.main.url(forResource: "loinc", withExtension: "db") else {
            return
        }
        var raw: OpaquePointer?
        let flags = SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX
        guard sqlite3_open_v2(url.path, &raw, flags, nil) == SQLITE_OK, let raw else {
            if let raw { sqlite3_close(raw) }
            return
        }
        handle = raw

        // Meta lookups
        let placeholder = (metaValue("placeholder") ?? "false").lowercased() == "true"
        version = metaValue("loinc_version")
        attribution = metaValue("attribution")
        codeCount = Int(metaValue("code_count") ?? "0") ?? 0
        isAvailable = !placeholder && codeCount > 0
    }

    // MARK: - Public API

    func entry(for loinc: String) -> Entry? {
        queue.sync { fetchEntry(loinc: loinc) }
    }

    func search(_ query: String, limit: Int = 50) -> [Entry] {
        queue.sync { fetchSearch(query: query, limit: limit) }
    }

    func entries(matchingCodes codes: [String]) -> [Entry] {
        queue.sync { fetchByCodes(codes) }
    }

    func localizedName(for loinc: String, languageCode: String) -> String? {
        queue.sync { fetchLocalizedName(loinc: loinc, languageCode: languageCode) }
    }

    // MARK: - Internal queries

    private func metaValue(_ key: String) -> String? {
        guard let handle else { return nil }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        let sql = "SELECT value FROM meta WHERE key = ? LIMIT 1"
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, key, -1, SQLITE_TRANSIENT)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return stmt.flatMap { textColumn($0, index: 0) }
    }

    private func fetchEntry(loinc: String) -> Entry? {
        guard let handle, isAvailable else { return nil }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        let sql = """
            SELECT loinc, long_common_name, shortname, component, example_ucum_units, class
            FROM loinc_codes WHERE loinc = ? LIMIT 1
            """
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, loinc, -1, SQLITE_TRANSIENT)
        guard sqlite3_step(stmt) == SQLITE_ROW, let stmt else { return nil }
        return entry(from: stmt)
    }

    private func fetchSearch(query: String, limit: Int) -> [Entry] {
        guard let handle, isAvailable else { return [] }
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }

        // FTS5 query — prefix-match each token so partial typing works.
        let ftsQuery = trimmed
            .split(whereSeparator: { !$0.isLetter && !$0.isNumber && $0 != "-" })
            .map { "\($0)*" }
            .joined(separator: " ")

        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        let sql = """
            SELECT c.loinc, c.long_common_name, c.shortname, c.component, c.example_ucum_units, c.class
            FROM loinc_search s
            JOIN loinc_codes c ON c.loinc = s.loinc
            WHERE loinc_search MATCH ?
            ORDER BY rank
            LIMIT ?
            """
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
        sqlite3_bind_text(stmt, 1, ftsQuery, -1, SQLITE_TRANSIENT)
        sqlite3_bind_int(stmt, 2, Int32(limit))
        var results: [Entry] = []
        while sqlite3_step(stmt) == SQLITE_ROW, let stmt {
            results.append(entry(from: stmt))
        }
        return results
    }

    private func fetchByCodes(_ codes: [String]) -> [Entry] {
        guard let handle, isAvailable, !codes.isEmpty else { return [] }
        let placeholders = Array(repeating: "?", count: codes.count).joined(separator: ",")
        let sql = """
            SELECT loinc, long_common_name, shortname, component, example_ucum_units, class
            FROM loinc_codes WHERE loinc IN (\(placeholders))
            """
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
        for (index, code) in codes.enumerated() {
            sqlite3_bind_text(stmt, Int32(index + 1), code, -1, SQLITE_TRANSIENT)
        }
        var results: [Entry] = []
        while sqlite3_step(stmt) == SQLITE_ROW, let stmt {
            results.append(entry(from: stmt))
        }
        return results
    }

    private func fetchLocalizedName(loinc: String, languageCode: String) -> String? {
        guard let handle, isAvailable else { return nil }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        let sql = """
            SELECT long_common_name FROM loinc_translations
            WHERE loinc = ? AND language_code = ? LIMIT 1
            """
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, loinc, -1, SQLITE_TRANSIENT)
        sqlite3_bind_text(stmt, 2, languageCode, -1, SQLITE_TRANSIENT)
        guard sqlite3_step(stmt) == SQLITE_ROW, let stmt else { return nil }
        return textColumn(stmt, index: 0)
    }

    private func entry(from stmt: OpaquePointer) -> Entry {
        Entry(
            loinc: textColumn(stmt, index: 0) ?? "",
            longCommonName: textColumn(stmt, index: 1) ?? "",
            shortname: textColumn(stmt, index: 2),
            component: textColumn(stmt, index: 3),
            exampleUnit: textColumn(stmt, index: 4),
            className: textColumn(stmt, index: 5)
        )
    }

    private func textColumn(_ stmt: OpaquePointer, index: Int32) -> String? {
        guard let cString = sqlite3_column_text(stmt, index) else { return nil }
        return String(cString: cString)
    }
}

// Swift can't import SQLITE_TRANSIENT (a C macro casting -1 to sqlite_destructor_type),
// so we recreate it here.
// swiftlint:disable:next identifier_name
private let SQLITE_TRANSIENT = unsafeBitCast(
    OpaquePointer(bitPattern: -1),
    to: sqlite3_destructor_type.self
)
