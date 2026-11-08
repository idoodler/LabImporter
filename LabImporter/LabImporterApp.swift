import SwiftUI

@main
struct LabImporterApp: App {
    init() {
        // The shared core (LOINC catalog, mapping, export rules) is used everywhere.
        SharedCore.start()
        ExportFiles.removeStale()
        // Start roaming card layout + patient metadata across the user's devices.
        MainActor.assumeIsolated { CloudSyncService.shared.start() }
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if (DeviceSupport.isSupported || ScreenshotMode.isActive) && !ScreenshotMode.showsUnsupportedDevice {
                    HomeView()
                } else {
                    UnsupportedDeviceView()
                }
            }
            .preferredColorScheme(ScreenshotMode.colorScheme)
        }
    }
}
