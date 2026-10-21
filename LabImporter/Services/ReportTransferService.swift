import CoreTransferable
import Foundation
import LabImporterCore
import UniformTypeIdentifiers

/// Moves reports between devices and platforms — iPhone ⇄ Android — as a file
/// the user carries themselves (AirDrop, Mail, Files); there is no server and
/// no account. The format and its rules live in the shared core
/// (LabImporterCore `ReportTransfer`): a FHIR Bundle of every report, and on
/// import also a single LabImporter CDA document from either app. Reports that
/// are already in Apple Health are skipped, so importing twice adds nothing.
enum ReportTransferService {
    struct Summary: Equatable {
        let imported: Int
        let alreadyPresent: Int
    }

    enum Failure: LocalizedError {
        case unreadable
        case unsupported

        var errorDescription: String? {
            switch self {
            case .unreadable: String(localized: "Could not load the selected file.")
            case .unsupported: String(localized: "This file doesn't contain LabImporter reports.")
            }
        }
    }

    /// The file types the importer accepts.
    static let importTypes: [UTType] = [.json, .xml]

    /// Every report as the transfer file's contents.
    static func exportContents(of reports: [LabReport]) -> String {
        SharedCore.start()
        return ReportTransfer.shared.export(reports: reports.map(\.shared), softwareIdentity: CDAExportService.softwareIdentity)
    }

    /// The new reports in a transfer file, and how many of its reports are
    /// already among [existing]. Throws `Failure.unsupported` for anything
    /// that isn't a LabImporter transfer file or CDA document.
    static func plan(contents: String, existing: [LabReport]) throws -> (reports: [LabReport], alreadyPresent: Int) {
        SharedCore.start()
        guard let read = ReportTransfer.shared.read(content: contents) as? ReportTransferReadResultReports else {
            throw Failure.unsupported
        }
        let plan = ReportTransfer.shared.plan(existing: existing.map(\.shared), incoming: read.reports)
        return (plan.toImport.map(LabReport.init(shared:)), Int(plan.alreadyPresent))
    }

    /// Imports a transfer file into Apple Health.
    static func importFile(at url: URL, existing: [LabReport]) async throws -> Summary {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), let contents = String(data: data, encoding: .utf8) else {
            throw Failure.unreadable
        }
        let (reports, alreadyPresent) = try plan(contents: contents, existing: existing)
        let exporter = CDAExportService()
        for report in reports {
            let xml = exporter.generateCDA(
                labValues: report.asLabValues, date: report.date,
                patientName: report.patientName, authorName: report.authorName
            )
            try await HealthKitService.shared.importCDADocument(xml, date: report.date)
        }
        return Summary(imported: reports.count, alreadyPresent: alreadyPresent)
    }
}

/// Every report as a shareable transfer file, written only when it's shared.
struct ReportArchive: Transferable {
    let reports: [LabReport]

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .json) { archive in
            SharedCore.start()
            let name = ReportTransfer.shared.fileName(date: Date.now.sharedDate)
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
            try ReportTransferService.exportContents(of: archive.reports).write(to: url, atomically: true, encoding: .utf8)
            return SentTransferredFile(url)
        }
    }
}
