import Foundation

/// Hands text shared to the LabImporter share extension over to the app.
///
/// The extension can't import a report itself — reading values needs the
/// app's on-device AI, the review screen and Apple Health — so it drops the
/// text into an inbox in the App Group container the two share. The app takes
/// it out when it opens: through the `labimporter://share/<id>` link the
/// extension asks the system to open, or simply the next time it becomes
/// active. Items expire after a day so an old share never pops up unexpectedly.
///
/// Compiled into both the app and the extension (no LabImporterCore here).
struct ShareInbox: Sendable {
    /// Info.plist key carrying the App Group identifier (the same in app and extension).
    static let appGroupKey = "LabImporterAppGroup"
    static let lifetime: TimeInterval = 24 * 60 * 60

    struct Item: Equatable, Sendable {
        let id: UUID
        let text: String
        let date: Date
    }

    private struct Stored: Codable {
        let text: String
        let date: Date
    }

    let directory: URL

    /// The inbox in the shared App Group container, or nil when the group
    /// isn't available (e.g. an unsigned simulator build).
    static var shared: ShareInbox? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: appGroupKey) as? String,
              let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)
        else { return nil }
        return ShareInbox(directory: container.appendingPathComponent("ShareInbox", isDirectory: true))
    }

    // MARK: - Links

    /// The link the extension opens to hand an item to the app.
    static func url(for item: Item) -> URL {
        URL(string: "\(DeepLinkScheme.value)://share/\(item.id.uuidString)")!
    }

    /// The item a `labimporter://share/<id>` link refers to.
    static func itemID(from url: URL) -> UUID? {
        guard url.scheme == DeepLinkScheme.value, url.host == "share" else { return nil }
        return UUID(uuidString: url.lastPathComponent)
    }

    // MARK: - Items

    /// Saves shared text for the app. Health data: readable only while the device is unlocked.
    @discardableResult
    func store(_ text: String, now: Date = .now) throws -> Item {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let item = Item(id: UUID(), text: text, date: now)
        let data = try JSONEncoder().encode(Stored(text: text, date: now))
        try data.write(to: file(for: item.id), options: [.atomic, .completeFileProtection])
        return item
    }

    /// Removes and returns the item with [id], if it's still there and fresh.
    func take(_ id: UUID, now: Date = .now) -> Item? {
        let url = file(for: id)
        defer { try? FileManager.default.removeItem(at: url) }
        guard let data = try? Data(contentsOf: url),
              let stored = try? JSONDecoder().decode(Stored.self, from: data),
              now.timeIntervalSince(stored.date) < Self.lifetime
        else { return nil }
        return Item(id: id, text: stored.text, date: stored.date)
    }

    /// Removes and returns the oldest fresh item; expired ones are discarded.
    func takeNext(now: Date = .now) -> Item? {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        let items = files.compactMap { url -> (UUID, Date)? in
            guard url.pathExtension == "json", let id = UUID(uuidString: url.deletingPathExtension().lastPathComponent),
                  let data = try? Data(contentsOf: url),
                  let stored = try? JSONDecoder().decode(Stored.self, from: data)
            else {
                try? FileManager.default.removeItem(at: url)
                return nil
            }
            return (id, stored.date)
        }
        for (id, _) in items.sorted(by: { $0.1 < $1.1 }) {
            if let item = take(id, now: now) { return item }
        }
        return nil
    }

    private func file(for id: UUID) -> URL {
        directory.appendingPathComponent("\(id.uuidString).json")
    }
}

/// The app's URL scheme (see `CFBundleURLTypes`), shared with the extension;
/// `DeepLink` (metric links) and the share hand-off both use it.
enum DeepLinkScheme {
    static let value = "labimporter"
}
