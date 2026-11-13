import AppIntents
import Foundation

/// Lets Siri open LabImporter's scanner hands-free. Never touches a health
/// value — it just starts the same flow the "Scan Document" button drives —
/// so it's the one Siri capability enabled by default once the user turns on
/// Siri access at all (`SiriExposurePreferences.allowScanShortcut`).
struct StartLabScanIntent: AppIntent {
    static let title: LocalizedStringResource = "Scan a Lab Report"
    static let description = IntentDescription(
        "Opens LabImporter's scanner to capture a new report."
    )

    /// The app must come to the foreground: scanning needs the camera UI, and
    /// review afterward needs a live screen.
    static let openAppWhenRun: Bool = true
    // No authentication policy of its own: it speaks no value, and opening the app already needs an unlocked phone.

    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard SiriExposurePreferences.current().canStartScan else {
            return .result(dialog: "Starting a scan via Siri isn't turned on yet. Allow it in LabImporter's Settings.")
        }
        SiriActionBridge.shared.requestScan()
        return .result(dialog: "Opening LabImporter's scanner…")
    }
}
