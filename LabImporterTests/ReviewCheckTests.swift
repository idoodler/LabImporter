import Foundation
import Testing
@testable import LabImporter

/// What Review does to stop a wrong value: the flag across the Swift ⇄ Kotlin
/// boundary, clearing it, the shared number rule, and whose names a report keeps.
struct ReviewCheckTests {
    private func flagged(_ text: String = "222", _ number: Double? = 222) -> LabValue {
        LabValue(code: "1742-6", name: "ALT", displayValue: text, numericValue: number, unit: "U/L", needsSourceCheck: true)
    }

    @Test func theFlagCrossesTheBridgeBothWays() {
        #expect(flagged().shared.needsSourceCheck)
        #expect(LabValue(shared: flagged().shared).needsSourceCheck)
        let manual = LabValue(code: "1742-6", name: "ALT", displayValue: "22", numericValue: 22, unit: "U/L")
        #expect(!manual.needsSourceCheck, "A value entered by hand has nothing to be compared with")
        #expect(!LabValue(shared: manual.shared).needsSourceCheck)
    }

    @Test func aSavedReportIsNeverFlagged() {
        #expect(LabReport.sample.asLabValues.allSatisfy { !$0.needsSourceCheck })
    }

    @Test func editingTheResultClearsTheFlagAndKeepsTheRow() {
        var value = flagged()
        let id = value.id
        value.applyEditedResult("22,5")
        #expect(!value.needsSourceCheck)
        #expect(value.id == id)
        #expect(value.displayValue == "22,5")
        #expect(value.numericValue == 22.5)
    }

    @Test func aTypedResultIsANumberOnlyByTheSharedRule() {
        for notANumber in ["inf", "-inf", "nan", "NaN", "Infinity", "0x10", "6,5 H"] {
            var value = flagged()
            value.applyEditedResult(notANumber)
            #expect(value.numericValue == nil, "\(notANumber)")
            #expect(LabValue.number(from: notANumber) == nil, "\(notANumber)")
        }
        #expect(LabValue.number(from: "0,8") == 0.8)
    }

    @Test func onlyValuesAboutToBeSavedCountAsToCheck() {
        SharedCore.start()
        var deselected = flagged()
        deselected.isSelected = false
        let unmapped = LabValue(code: "KREA", name: "Kreatinin", displayValue: "1", numericValue: 1, unit: "", needsSourceCheck: true)
        #expect([flagged(), deselected, unmapped].valuesToCheckCount() == 1)
    }

    @Test func aResultThatCannotBeSavedIsCountedAndDeselectingSettlesIt() {
        SharedCore.start()
        var value = flagged()
        value.applyEditedResult("neg 1")
        #expect(!value.isExportable)
        #expect(value.needsCorrection)
        #expect([value].valuesToCorrectCount() == 1)
        #expect([value].valuesToCheckCount() == 0)
        value.isSelected = false
        #expect([value].valuesToCorrectCount() == 0)
        var empty = flagged("-", nil)
        #expect(!empty.needsCorrection, "A report that prints no result has nothing to correct")
        empty.applyEditedResult("")
        #expect(!empty.needsCorrection)
    }

    private func parse(_ raw: String, unit: String = "/uL", in text: String) async throws -> LabValue {
        let parser = LabParserService { _, _ in
            AILabReport(
                entries: [AILabEntry(code: "777-3", name: "Platelets", rawValue: raw, unit: unit)],
                reportDate: "", patientName: "", authorName: "", reportLanguage: "en"
            )
        }
        let result = try await parser.parseLabValues(from: text)
        return try #require(result.values.first)
    }

    @Test func thousandsAreShownAsSavedAcrossTheBridge() async throws {
        let value = try await parse("263,000", in: "Platelets 263,000 /uL\nKreatinin 0.8 mg/dl")
        #expect(value.numericValue == 263_000)
        #expect(value.displayValue == "263000")
        #expect(!value.needsCorrection)
        let unclear = try await parse("263,000", in: "Platelets 263,000 /uL")
        #expect(unclear.numericValue == nil)
        #expect(unclear.needsCorrection)
    }

    @Test func aUnitCopiedAlongIsNoObstacleToSaving() async throws {
        let value = try await parse("5.6 mmol/l", unit: "mmol/l", in: "Glucose 5.6 mmol/l")
        #expect(value.numericValue == 5.6)
        #expect(value.isExportable)
    }

    @Test func theParserComparesTheWholeReportAndCarriesAMissingDate() async throws {
        let parser = LabParserService { part, progress in
            let read = try await StandInReport.read(part, progress)
            return AILabReport(entries: read.entries, reportDate: "", patientName: "", authorName: "", reportLanguage: "en")
        }
        let result = try await parser.parseLabValues(from: StandInReport.text)
        #expect(result.reportDate == nil)
        #expect(result.values.filter(\.needsSourceCheck).map(\.displayValue) == ["922"])
    }

    // MARK: - Names

    private func emptyDefaults() throws -> UserDefaults {
        let suite = "ReviewCheckTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    private func report(patient: String, author: String) -> LabReport {
        LabReport(id: UUID(), date: Date(), patientName: patient, authorName: author, entries: [])
    }

    @Test func aNewReportStartsWithTheNamesLastSaved() throws {
        let defaults = try emptyDefaults()
        #expect(ReviewNames(replacing: nil, defaults: defaults) == ReviewNames(replacing: report(patient: "", author: ""), defaults: defaults))
        defaults.set("Max", forKey: "patientName")
        defaults.set("Labor", forKey: "authorName")
        let names = ReviewNames(replacing: nil, defaults: defaults)
        #expect(names.patient == "Max" && names.author == "Labor")
    }

    @Test func aSavedReportKeepsItsOwnNamesEvenEmptyOnes() throws {
        let defaults = try emptyDefaults()
        defaults.set("Max", forKey: "patientName")
        defaults.set("Labor", forKey: "authorName")
        let old = ReviewNames(replacing: report(patient: "Oma", author: ""), defaults: defaults)
        #expect(old.patient == "Oma")
        #expect(old.author == "", "An empty name doesn't pick up the current one")
    }

    @Test func onlySavingANewReportRemembersItsNames() throws {
        let defaults = try emptyDefaults()
        defaults.set("Max", forKey: "patientName")
        defaults.set("Labor", forKey: "authorName")
        let saved = report(patient: "Oma", author: "Altes Labor")

        // Opening, and cancelling, an old report changes nothing; nor does saving it.
        let old = ReviewNames(replacing: saved, defaults: defaults)
        #expect(defaults.string(forKey: "patientName") == "Max")
        old.rememberForNextReport(replacing: saved, defaults: defaults)
        #expect(defaults.string(forKey: "patientName") == "Max")
        #expect(defaults.string(forKey: "authorName") == "Labor")

        var new = ReviewNames(replacing: nil, defaults: defaults)
        new.patient = " Erika "
        new.author = ""
        new.rememberForNextReport(replacing: nil, defaults: defaults)
        #expect(defaults.string(forKey: "patientName") == "Erika")
        #expect(defaults.string(forKey: "authorName") == "")
    }
}
