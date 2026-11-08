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
        /// Reports the file holds that only a newer version of the app can read.
        let needingNewerVersion: Int
        /// Damaged reports in the file; what could be read of them is among `imported`.
        let damaged: Int
    }

    /// What importing a file would do.
    struct Plan {
        let reports: [LabReport]
        let alreadyPresent: Int
        let needingNewerVersion: Int
        let damaged: Int
    }

    /// A transfer file's contents. A value that can't be written isn't in them, so the
    /// caller must act on `leftOut`, and with nothing `written` there's no file to share.
    struct Export {
        let contents: String
        let written: Int
        let leftOut: Int
    }

    enum Failure: LocalizedError {
        case unreadable
        case unsupported
        case newerVersion
        case nothingToExport
        case savedReportsUnreadable

        var errorDescription: String? {
            switch self {
            case .unreadable: String(localized: "Could not load the selected file.")
            case .unsupported: String(localized: "This file doesn't contain LabImporter reports.")
            case .newerVersion: String(localized: "This file comes from a newer version of LabImporter. Update the app to import it.")
            case .nothingToExport: String(localized: "None of your values could be written to a file, so nothing was exported. Please try again.")
            case .savedReportsUnreadable: String(localized: "Your saved reports can't be read right now, so nothing was imported. Try again in a moment.")
            }
        }
    }

    /// The file types the importer accepts.
    static let importTypes: [UTType] = [.json, .xml]

    /// Every report as the transfer file's contents.
    static func export(of reports: [LabReport]) -> Export {
        SharedCore.start()
        let export = ReportTransfer.shared.export(reports: reports.map(\.shared), softwareIdentity: CDAExportService.softwareIdentity)
        return Export(contents: export.contents, written: Int(export.writtenResults), leftOut: Int(export.leftOutResults))
    }

    /// Writes `export` for a share sheet, whose dismissal removes it (`ExportFiles`).
    static func writeFile(_ export: Export) throws -> URL {
        guard export.written > 0 else { throw Failure.nothingToExport }
        SharedCore.start()
        let url = try ExportFiles.url(named: ReportTransfer.shared.fileName(date: Date.now.sharedDate))
        try export.contents.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    /// The new reports in a transfer file, and how many of its reports are
    /// already among [existing]. Throws `Failure.unsupported` for anything
    /// that isn't a LabImporter transfer file or CDA document, and
    /// `Failure.newerVersion` for one only a newer version of the app can read.
    static func plan(contents: String, existing: [LabReport]) throws -> Plan {
        SharedCore.start()
        let result = ReportTransfer.shared.read(content: contents)
        if result is ReportTransferReadResultNewerVersion { throw Failure.newerVersion }
        guard let read = result as? ReportTransferReadResultReports else {
            throw Failure.unsupported
        }
        let plan = ReportTransfer.shared.plan(existing: existing.map(\.shared), incoming: read.reports)
        return Plan(
            reports: plan.toImport.map(LabReport.init(shared:)), alreadyPresent: Int(plan.alreadyPresent),
            needingNewerVersion: Int(read.newerVersionReports), damaged: Int(read.unreadableReports)
        )
    }

    /// Imports a transfer file into Apple Health. Skipping what's already there needs the whole
    /// list, read now: a list from a failed read would make every report new, and nothing merges
    /// the duplicates once reading works again. `loadExisting` is Apple Health's, except in tests.
    static func importFile(
        at url: URL,
        loadExisting: @Sendable () async throws -> [LabReport] = { try await HealthKitService.shared.loadStoredReports().reports }
    ) async throws -> Summary {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        // Reported below as a file that can't be read.
        guard let data = try? Data(contentsOf: url), let contents = String(data: data, encoding: .utf8) else {
            throw Failure.unreadable
        }
        let existing: [LabReport]
        do {
            existing = try await loadExisting()
        } catch {
            throw Failure.savedReportsUnreadable
        }
        let plan = try plan(contents: contents, existing: existing)
        let exporter = CDAExportService()
        for report in plan.reports {
            let xml = exporter.generateCDA(
                labValues: report.asLabValues, date: report.date,
                patientName: report.patientName, authorName: report.authorName
            )
            try await HealthKitService.shared.importCDADocument(xml, date: report.date)
        }
        return Summary(
            imported: plan.reports.count, alreadyPresent: plan.alreadyPresent,
            needingNewerVersion: plan.needingNewerVersion, damaged: plan.damaged
        )
    }
}
