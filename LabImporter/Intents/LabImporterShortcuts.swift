import AppIntents

/// Declares LabImporter's App Intents to the system as ready-made Shortcuts /
/// Siri phrases. App Intents is the only path into Siri now that SiriKit is
/// retired, so this is the single place both intents are advertised.
struct LabImporterShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartLabScanIntent(),
            phrases: [
                "Scan a lab report in \(.applicationName)",
                "Start a new scan in \(.applicationName)"
            ],
            shortTitle: "Scan a Lab Report",
            systemImageName: "doc.viewfinder"
        )
        AppShortcut(
            intent: AskLabValueIntent(),
            phrases: [
                "Ask \(.applicationName) about a lab value",
                "What's my value in \(.applicationName)"
            ],
            shortTitle: "Ask About a Lab Value",
            systemImageName: "waveform.path.ecg"
        )
    }
}
