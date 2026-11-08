import Foundation
import Testing
@testable import LabImporter

/// Exports are copies of health data outside Apple Health: they don't outlive their share sheet.
struct ExportFilesTests {
    private let temporary = FileManager.default.temporaryDirectory
        .appendingPathComponent("ExportFilesTests-\(UUID().uuidString)", isDirectory: true)

    private var exports: URL { temporary.appendingPathComponent(ExportFiles.directory.lastPathComponent, isDirectory: true) }

    @Test func aClosedShareSheetLeavesNothingBehind() throws {
        let url = try ExportFiles.url(named: "LabResults-2026-05-06.xml", in: exports)
        try Data("<health record/>".utf8).write(to: url)
        #expect(FileManager.default.fileExists(atPath: url.path))

        ExportFiles.removeAll(in: exports)

        #expect(!FileManager.default.fileExists(atPath: url.path))
        // And nothing to remove is fine.
        ExportFiles.removeAll(in: exports)
    }

    @Test func aLaunchRemovesWhatAnEarlierRunLeft() throws {
        let leftover = try ExportFiles.url(named: "LabReport-2026-05-06.pdf", in: exports)
        try Data("%PDF".utf8).write(to: leftover)
        let older = ["LabResults-2020-01-01.xml", "LabReport-2020-01-01.pdf", "LabImporter-2020-01-01.json"]
            .map { temporary.appendingPathComponent($0) }
        // Not ours: another file that happens to be in the temporary folder.
        let other = temporary.appendingPathComponent("LabResults.txt")
        try (older + [other]).forEach { try Data("x".utf8).write(to: $0) }

        ExportFiles.removeStale(temporaryDirectory: temporary)

        #expect(!FileManager.default.fileExists(atPath: leftover.path))
        for file in older { #expect(!FileManager.default.fileExists(atPath: file.path), "\(file.lastPathComponent)") }
        #expect(FileManager.default.fileExists(atPath: other.path))
    }

    @Test func aReportFileIsWrittenWhereTheCleanupLooks() throws {
        let values = [LabValue(code: "2160-0", name: "x", displayValue: "0,8", numericValue: 0.8, unit: "mg/dl")]
        let url = try CDAExportService().exportToTempFile(labValues: values, date: Fixtures.date(2026, 5, 6))
        #expect(url.deletingLastPathComponent().standardizedFileURL == ExportFiles.directory.standardizedFileURL)
        try FileManager.default.removeItem(at: url)
    }

    @Test func aTransferFileIsWrittenWhereTheCleanupLooks() throws {
        let url = try ReportTransferService.writeFile(ReportTransferService.Export(contents: "{}", written: 1, leftOut: 0))
        #expect(url.deletingLastPathComponent().standardizedFileURL == ExportFiles.directory.standardizedFileURL)
        try FileManager.default.removeItem(at: url)
    }
}
