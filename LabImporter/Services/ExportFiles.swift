import Foundation

/// Where exports wait while a share sheet has them. Each is a copy of the user's
/// health data outside Apple Health, so it lives no longer than that sheet: its
/// `onDismiss` calls `removeAll`, and `removeStale` catches what a killed app left.
enum ExportFiles {
    static var directory: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("Exports", isDirectory: true)
    }

    /// A place to write the export called `name`.
    static func url(named name: String, in directory: URL = directory) throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent(name)
    }

    /// Only one share sheet can be up, so whatever is here belongs to the one that closed.
    static func removeAll(in directory: URL = directory) {
        // Nothing to remove is the usual case. A removal that fails is tried again at the next launch.
        try? FileManager.default.removeItem(at: directory)
    }

    /// At launch nothing is being shared, so whatever is left is stale. Versions before
    /// `directory` wrote straight into the temporary directory, under these names.
    static func removeStale(temporaryDirectory: URL = FileManager.default.temporaryDirectory) {
        removeAll(in: temporaryDirectory.appendingPathComponent(directory.lastPathComponent, isDirectory: true))
        // An unreadable folder has nothing this could remove.
        let files = (try? FileManager.default.contentsOfDirectory(at: temporaryDirectory, includingPropertiesForKeys: nil)) ?? []
        for file in files where isOlderExport(file) {
            // Tried again at the next launch; the system also clears this folder in time.
            try? FileManager.default.removeItem(at: file)
        }
    }

    private static func isOlderExport(_ file: URL) -> Bool {
        let name = file.lastPathComponent
        return (name.hasPrefix("LabResults-") && file.pathExtension == "xml")
            || (name.hasPrefix("LabReport-") && file.pathExtension == "pdf")
            || (name.hasPrefix("LabImporter-") && file.pathExtension == "json")
    }
}
