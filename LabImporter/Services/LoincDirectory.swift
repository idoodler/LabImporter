import Foundation
import SQLite3

// SQLite wants to know whether a bound value is transient (copy it now) or
// static. Our bound strings are only guaranteed valid for the call, so copy.
private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

// A common laboratory LOINC term resolved for the active app language.
struct LoincTerm: Identifiable, Sendable {
    let code: String         // LOINC_NUM, e.g. "2160-0"
    let name: String          // localized display name
    let englishName: String   // English name (used for stable CDA display)
    let description: String?  // localized description, if distinct from the name
    let ucum: String          // EXAMPLE_UCUM_UNITS (may be empty)
    let rank: Int             // COMMON_TEST_RANK (lower = more commonly ordered)

    var id: String { code }
}

// The full structured attributes of a LOINC term, as shown on a loinc.org
// details page. The headline name/description are localized; the six-part name
// and other attributes are LOINC's standard English values.
struct LoincDetail: Sendable {
    let code: String
    let name: String          // localized
    let description: String?  // localized
    let component: String
    let property: String
    let timing: String        // time aspect
    let system: String
    let scale: String
    let method: String
    let loincClass: String
    let status: String
    let longName: String
    let shortName: String
    let ucum: String
}

// Read-only index over the bundled LOINC catalog (loinc.db, produced by
// tools/build_loinc_resource.py).
//
// Backed by SQLite for speed: the database opens instantly (no parse), exact
// code lookups are indexed, and search uses an FTS5 index — so nothing is
// decoded at launch and resident memory stays near zero. A single connection is
// shared and serialized with a lock (queries are microseconds, so contention is
// irrelevant), which lets the lookups stay synchronous and callable from any
// isolation domain — `@unchecked Sendable` is justified by that lock.
final class LoincDirectory: @unchecked Sendable {
    static let shared = LoincDirectory()

    let version: String
    let count: Int
    let license: String

    private let database: OpaquePointer?
    private let language: String
    private let lock = NSLock()
    private var termStatement: OpaquePointer?
    private var searchStatement: OpaquePointer?
    private var topStatement: OpaquePointer?
    private var detailStatement: OpaquePointer?

    private init() {
        language = Bundle.main.preferredLocalizations.first ?? "en"

        guard let url = Bundle.main.url(forResource: "loinc", withExtension: "db") else {
            database = nil; version = ""; count = 0; license = ""; return
        }
        var handle: OpaquePointer?
        guard sqlite3_open_v2(url.path, &handle, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else {
            sqlite3_close(handle); database = nil; version = ""; count = 0; license = ""; return
        }
        database = handle

        // Resolve the localized name + description with an English fallback in one row.
        let columns = """
            (SELECT name FROM label WHERE code = ?1 AND lang = ?2),
            (SELECT descr FROM label WHERE code = ?1 AND lang = ?2),
            t.english, t.ucum, t.rank
        """
        sqlite3_prepare_v2(handle, "SELECT \(columns) FROM term t WHERE t.code = ?1", -1, &termStatement, nil)

        let resultColumns = """
            l.code,
            (SELECT name FROM label WHERE code = l.code AND lang = ?2),
            (SELECT descr FROM label WHERE code = l.code AND lang = ?2),
            t.english, t.ucum, t.rank
        """
        sqlite3_prepare_v2(handle, """
            SELECT \(resultColumns)
            FROM label_fts f
            JOIN label l ON l.rowid = f.rowid
            JOIN term t ON t.code = l.code
            WHERE label_fts MATCH ?1 AND l.lang IN (?2, 'en')
            GROUP BY l.code
            ORDER BY t.rank
            LIMIT ?3 OFFSET ?4
            """, -1, &searchStatement, nil)

        sqlite3_prepare_v2(handle, """
            SELECT t.code,
                   (SELECT name FROM label WHERE code = t.code AND lang = ?1),
                   (SELECT descr FROM label WHERE code = t.code AND lang = ?1),
                   t.english, t.ucum, t.rank
            FROM term t ORDER BY t.rank LIMIT ?2 OFFSET ?3
            """, -1, &topStatement, nil)

        sqlite3_prepare_v2(handle, """
            SELECT (SELECT name FROM label WHERE code = ?1 AND lang = ?2),
                   (SELECT descr FROM label WHERE code = ?1 AND lang = ?2),
                   t.english, t.ucum, t.component, t.property, t.timing, t.system,
                   t.scale, t.method, t.loinc_class, t.status, t.long_name, t.short_name
            FROM term t WHERE t.code = ?1
            """, -1, &detailStatement, nil)

        version = LoincDirectory.scalar(handle, "SELECT value FROM meta WHERE key = 'version'") ?? ""
        count = Int(LoincDirectory.scalar(handle, "SELECT count(*) FROM term") ?? "0") ?? 0
        license = LoincDirectory.scalar(handle, "SELECT value FROM meta WHERE key = 'license'") ?? ""
    }

    // MARK: - Lookups

    func term(for code: String) -> LoincTerm? {
        let trimmed = code.trimmingCharacters(in: .whitespaces)
        guard let statement = termStatement else { return nil }
        lock.lock(); defer { lock.unlock() }
        defer { sqlite3_reset(statement); sqlite3_clear_bindings(statement) }
        sqlite3_bind_text(statement, 1, trimmed, -1, sqliteTransient)
        sqlite3_bind_text(statement, 2, language, -1, sqliteTransient)
        guard sqlite3_step(statement) == SQLITE_ROW else { return nil }
        return makeTerm(statement, codeColumn: nil, code: trimmed)
    }

    func isKnownLoinc(_ code: String) -> Bool {
        term(for: code) != nil
    }

    // Full structured attributes for a code (for the term detail screen).
    func detail(for code: String) -> LoincDetail? {
        let trimmed = code.trimmingCharacters(in: .whitespaces)
        guard let statement = detailStatement else { return nil }
        lock.lock(); defer { lock.unlock() }
        defer { sqlite3_reset(statement); sqlite3_clear_bindings(statement) }
        sqlite3_bind_text(statement, 1, trimmed, -1, sqliteTransient)
        sqlite3_bind_text(statement, 2, language, -1, sqliteTransient)
        guard sqlite3_step(statement) == SQLITE_ROW else { return nil }
        let english = column(statement, 2) ?? trimmed
        let name = column(statement, 0) ?? english
        let localizedDescr = column(statement, 1)
        let description = (localizedDescr?.isEmpty == false && localizedDescr != name) ? localizedDescr : nil
        return LoincDetail(
            code: trimmed,
            name: name,
            description: description,
            component: column(statement, 4) ?? "",
            property: column(statement, 5) ?? "",
            timing: column(statement, 6) ?? "",
            system: column(statement, 7) ?? "",
            scale: column(statement, 8) ?? "",
            method: column(statement, 9) ?? "",
            loincClass: column(statement, 10) ?? "",
            status: column(statement, 11) ?? "",
            longName: column(statement, 12) ?? "",
            shortName: column(statement, 13) ?? "",
            ucum: column(statement, 3) ?? ""
        )
    }

    func search(_ query: String, limit: Int = 80, offset: Int = 0) -> [LoincTerm] {
        guard database != nil else { return [] }
        if let match = ftsQuery(query) {
            guard let statement = searchStatement else { return [] }
            lock.lock(); defer { lock.unlock() }
            defer { sqlite3_reset(statement); sqlite3_clear_bindings(statement) }
            sqlite3_bind_text(statement, 1, match, -1, sqliteTransient)
            sqlite3_bind_text(statement, 2, language, -1, sqliteTransient)
            sqlite3_bind_int(statement, 3, Int32(limit))
            sqlite3_bind_int(statement, 4, Int32(offset))
            return collect(statement)
        }
        guard let statement = topStatement else { return [] }
        lock.lock(); defer { lock.unlock() }
        defer { sqlite3_reset(statement); sqlite3_clear_bindings(statement) }
        sqlite3_bind_text(statement, 1, language, -1, sqliteTransient)
        sqlite3_bind_int(statement, 2, Int32(limit))
        sqlite3_bind_int(statement, 3, Int32(offset))
        return collect(statement)
    }

    // MARK: - Row helpers

    private func collect(_ statement: OpaquePointer) -> [LoincTerm] {
        var results: [LoincTerm] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            let code = column(statement, 0) ?? ""
            results.append(makeTerm(statement, codeColumn: 0, code: code))
        }
        return results
    }

    // Builds a term from a result row. When `codeColumn` is nil the statement has
    // no code column (single-term lookup) and `code` is used directly; otherwise
    // the localized/english columns are offset by one.
    private func makeTerm(_ statement: OpaquePointer, codeColumn: Int32?, code: String) -> LoincTerm {
        let base: Int32 = codeColumn == nil ? 0 : 1
        let localized = column(statement, base)
        let localizedDescr = column(statement, base + 1)
        let english = column(statement, base + 2) ?? code
        let ucum = column(statement, base + 3) ?? ""
        let rank = Int(sqlite3_column_int(statement, base + 4))
        let name = localized ?? english
        let description = (localizedDescr?.isEmpty == false && localizedDescr != name) ? localizedDescr : nil
        return LoincTerm(code: code, name: name, englishName: english,
                         description: description, ucum: ucum, rank: rank)
    }

    private func column(_ statement: OpaquePointer, _ index: Int32) -> String? {
        guard let text = sqlite3_column_text(statement, index) else { return nil }
        return String(cString: text)
    }

    // Turns a free-text query into an FTS5 prefix query, e.g. "creat ser" ->
    // `"creat"* "ser"*`. Returns nil when there are no usable tokens (caller then
    // shows the most-common terms instead).
    private func ftsQuery(_ query: String) -> String? {
        let tokens = query.lowercased().split { !$0.isLetter && !$0.isNumber }.map(String.init)
        guard !tokens.isEmpty else { return nil }
        return tokens.map { "\"\($0)\"*" }.joined(separator: " ")
    }

    private static func scalar(_ handle: OpaquePointer?, _ sql: String) -> String? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK else { return nil }
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_ROW, let text = sqlite3_column_text(statement, 0) else {
            return nil
        }
        return String(cString: text)
    }
}
