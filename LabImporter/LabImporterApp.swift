import SwiftUI

@main
struct LabImporterApp: App {
    init() {
        // Start roaming card layout + patient metadata across the user's devices.
        MainActor.assumeIsolated { CloudSyncService.shared.start() }
    }

    var body: some Scene {
        WindowGroup {
            if DeviceSupport.isSupported || CommandLine.arguments.contains("--ss") {
                HomeView()
            } else {
                UnsupportedDeviceView()
            }
        }
    }
}
