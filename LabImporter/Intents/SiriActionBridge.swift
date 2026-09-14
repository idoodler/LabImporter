import Foundation

/// Bridges `StartLabScanIntent` and `SearchLabValuesIntent` — which may run on
/// Siri's own execution context before any view exists, since either intent
/// can cold-launch the app — into the live `HomeView`. `HomeView` registers a
/// handler for each once it's mounted; a request that arrives first is held
/// and flushed as soon as one is registered, mirroring the app's existing
/// "pending" patterns (`pendingImportURL`, `pendingDeepLinkCode` in
/// `HomeView`).
final class SiriActionBridge: @unchecked Sendable {
    static let shared = SiriActionBridge()
    private init() {}

    private let lock = NSLock()
    private var onScanRequested: (@MainActor () -> Void)?
    private var pendingScanRequest = false
    private var onSearchRequested: (@MainActor (String) -> Void)?
    private var pendingSearchTerm: String?

    /// Registered by `HomeView` once its import engine exists. Immediately
    /// flushes a scan request that arrived before the app finished launching.
    @MainActor
    func setScanHandler(_ handler: @escaping @MainActor () -> Void) {
        lock.lock()
        let shouldFlush = pendingScanRequest
        pendingScanRequest = false
        onScanRequested = handler
        lock.unlock()
        if shouldFlush { handler() }
    }

    /// Called by `StartLabScanIntent`. Runs the scan immediately if a handler
    /// is already registered (app already running); otherwise marks the
    /// request pending for `setScanHandler` to flush once the app launches.
    func requestScan() {
        lock.lock()
        let handler = onScanRequested
        if handler == nil { pendingScanRequest = true }
        lock.unlock()
        if let handler {
            Task { @MainActor in handler() }
        }
    }

    /// Registered by `HomeView` once it can present the search results sheet.
    /// Immediately flushes a search request that arrived before the app
    /// finished launching.
    @MainActor
    func setSearchHandler(_ handler: @escaping @MainActor (String) -> Void) {
        lock.lock()
        let shouldFlush = pendingSearchTerm
        pendingSearchTerm = nil
        onSearchRequested = handler
        lock.unlock()
        if let shouldFlush { handler(shouldFlush) }
    }

    /// Called by `SearchLabValuesIntent`. Runs the search immediately if a
    /// handler is already registered (app already running); otherwise marks
    /// the term pending for `setSearchHandler` to flush once the app launches.
    func requestSearch(term: String) {
        lock.lock()
        let handler = onSearchRequested
        if handler == nil { pendingSearchTerm = term }
        lock.unlock()
        if let handler {
            Task { @MainActor in handler(term) }
        }
    }
}
