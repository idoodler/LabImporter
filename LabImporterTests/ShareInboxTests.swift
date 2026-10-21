import Foundation
import Testing
@testable import LabImporter

/// The hand-off between the share extension and the app.
struct ShareInboxTests {
    private let inbox = ShareInbox(directory: FileManager.default.temporaryDirectory
        .appendingPathComponent("ShareInboxTests-\(UUID().uuidString)", isDirectory: true))

    @Test func anItemIsTakenOnceByItsLink() throws {
        let item = try inbox.store("KREA: 0,8 mg/dl")
        let url = ShareInbox.url(for: item)
        #expect(url.absoluteString == "labimporter://share/\(item.id.uuidString)")
        #expect(ShareInbox.itemID(from: url) == item.id)
        #expect(inbox.take(item.id)?.text == "KREA: 0,8 mg/dl")
        #expect(inbox.take(item.id) == nil)
    }

    @Test func waitingItemsComeOutOldestFirst() throws {
        let now = Date.now
        try inbox.store("second", now: now.addingTimeInterval(-60))
        try inbox.store("first", now: now.addingTimeInterval(-120))
        #expect(inbox.takeNext(now: now)?.text == "first")
        #expect(inbox.takeNext(now: now)?.text == "second")
        #expect(inbox.takeNext(now: now) == nil)
    }

    @Test func staleItemsNeverPopUp() throws {
        let now = Date.now
        try inbox.store("yesterday", now: now.addingTimeInterval(-ShareInbox.lifetime - 1))
        #expect(inbox.takeNext(now: now) == nil)
        // …and are cleaned up.
        #expect(inbox.takeNext(now: now.addingTimeInterval(-ShareInbox.lifetime * 2)) == nil)
    }

    @Test func otherLinksAreNotShareLinks() {
        #expect(ShareInbox.itemID(from: URL(string: "labimporter://metric/2160-0")!) == nil)
        #expect(ShareInbox.itemID(from: URL(string: "labimporter://share/not-a-uuid")!) == nil)
        #expect(ShareInbox.itemID(from: URL(string: "https://share/\(UUID().uuidString)")!) == nil)
        #expect(DeepLink.metricCode(from: URL(string: "labimporter://metric/2160-0")!) == "2160-0")
    }
}
