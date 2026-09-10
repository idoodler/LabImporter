import Foundation

/// Bridges `StartLabScanIntent` — which may run on Siri's own execution
/// context before any view exists, since the intent cold-launches the app —
/// into the live `HomeView`. `HomeView` registers a handler once it's mounted;
/// a request that arrives first is held and flushed as soon as one is
/// registered, mirroring the app's existing "pending" patterns
/// (`pendingImportURL`, `pendingDeepLinkCode` in `HomeView`).
final class SiriActionBridge: @unchecked Sendable {
    static let shared = SiriActionBridge()
    private init() {}

    private let lock = NSLock()
    private var onScanRequested: (@MainActor () -> Void)?
    private var pendingScanRequest = false

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
}
