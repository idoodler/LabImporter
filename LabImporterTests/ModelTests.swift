import Foundation
import Testing
@testable import LabImporter

struct LoincTests {
    @Test func catalogIsBundled() {
        #expect(LoincDirectory.shared.count > 10_000)
        #expect(!LoincDirectory.shared.version.isEmpty)
    }

    @Test func lookups() {
        #expect(LabMapping.loincCode(for: " 2160-0 ")?.loinc == "2160-0")
        #expect(LabMapping.loincCode(for: "KREA") == nil)
        #expect(LabMapping.loincURL(for: "2160-0")?.absoluteString == "https://loinc.org/2160-0/")
        #expect(LabMapping.loincURL(for: "KREA") == nil)
        #expect(LabMapping.catalogName(for: "2160-0") == "Creatinine [Mass/Vol]")
        #expect(LabMapping.displayName(for: "KREA") == "KREA")
    }

    @Test func searchAndLanguages() {
        #expect(LoincDirectory.shared.search("Kalium", language: "de", limit: 5).contains { $0.code == "2823-3" })
        #expect(LoincDirectory.shared.search("", limit: 10).count == 10)
        #expect(LoincDirectory.shared.resolvedLanguage(for: "DE-de") == "de")
        #expect(LoincDirectory.shared.resolvedLanguage(for: "pt-PT").hasPrefix("pt"))
        #expect(LoincDirectory.shared.detail(for: "2160-0")?.system.isEmpty == false)
    }

    @Test func categories() {
        #expect(LabCategory.forCode("2160-0") == .renal)
        #expect(LabCategory.forCode("4548-4") == .glycemic)
        #expect(LabCategory.forCode("2093-3") == .lipids)
        #expect(LabCategory.forCode("not-a-code") == .other)
        #expect(LabCategory.category(loincClass: "HEM/BC", component: "Hemoglobin") == .hematology)
        #expect(LabCategory.category(loincClass: "CHEM", component: "Sodium") == .electrolytes)
    }
}

struct LabValueTests {
    private let creatinine = LabValue(code: "2160-0", name: "Creatinine", displayValue: "0.8", numericValue: 0.8, unit: "mg/dl")

    @Test func duplicatesAreDetectedAmongExportableRows() {
        let second = LabValue(code: "2160-0", name: "Creatinine", displayValue: "0.9", numericValue: 0.9, unit: "mg/dl")
        let unmapped = LabValue(code: "KREA", name: "KREA", displayValue: "1", numericValue: 1, unit: "")
        #expect([creatinine, second, unmapped].duplicateLoincCodes() == ["2160-0"])
        #expect(creatinine.isDuplicate(in: ["2160-0"]))
        #expect(!unmapped.isDuplicate(in: ["2160-0"]))
        let deselected = LabValue(code: "2160-0", name: "x", displayValue: "1", numericValue: 1, unit: "", isSelected: false)
        #expect([creatinine, deselected].duplicateLoincCodes().isEmpty)
    }

    @Test func dedupKeepsTheRowWithData() {
        let empty = LabValue(code: "2160-0", name: "Creatinine", displayValue: "-", numericValue: nil, unit: "mg/dl")
        let result = [empty, creatinine].deduplicatedByLoinc()
        #expect(result.count == 1)
        #expect(result.first?.id == creatinine.id)
    }

    @Test func savedDataComparisonIgnoresDisplayFormatting() {
        var reformatted = creatinine
        reformatted.displayValue = "0,80"
        #expect(creatinine.matchesSavedData(of: reformatted))
        var remapped = creatinine
        remapped.code = "38483-4"
        #expect(!creatinine.matchesSavedData(of: remapped))
    }
}

struct PreferencesTests {
    @Test func jsonShapeIsStable() throws {
        let raw = #"{"pinnedCodes":["4548-4"],"orderedCodes":["2160-0"],"hiddenCodes":[],"# +
            #""customNames":{"4548-4":"A1c"},"referenceRanges":{"2160-0":{"high":1.2}}}"#
        let prefs = try #require(LabDisplayPreferences(rawValue: raw))
        #expect(prefs.pinnedCodes == ["4548-4"])
        #expect(prefs.nickname(for: "4548-4") == "A1c")
        #expect(prefs.referenceRange(for: "2160-0")?.status(for: 1.5) == .high)
        let roundTrip = try #require(LabDisplayPreferences(rawValue: prefs.rawValue))
        #expect(roundTrip.nickname(for: "4548-4") == "A1c")
        #expect(roundTrip.orderedCodes == ["2160-0"])
    }

    @Test func legacyAndCorruptBlobsDecode() {
        #expect(LabDisplayPreferences(rawValue: #"{"pinnedCodes":["1"],"orderedCodes":[],"hiddenCodes":[]}"#)?.pinnedCodes == ["1"])
        #expect(LabDisplayPreferences(rawValue: "garbage")?.pinnedCodes.isEmpty == true)
    }

    @Test func nicknamesTrimAndClear() {
        var prefs = LabDisplayPreferences()
        prefs.setNickname("  HbA1c ", for: " 4548-4 ")
        #expect(prefs.nickname(for: "4548-4") == "HbA1c")
        prefs.setNickname("  ", for: "4548-4")
        #expect(prefs.nickname(for: "4548-4") == nil)
        prefs.setReferenceRange(ReferenceRange(low: nil, high: nil), for: "2160-0")
        #expect(prefs.referenceRange(for: "2160-0") == nil)
    }

    @Test func referenceRanges() {
        let range = ReferenceRange(low: 3.5, high: 5.3)
        #expect(range.status(for: 3.4) == .low)
        #expect(range.status(for: 3.5) == .normal)
        #expect(range.status(for: 5.31) == .high)
        #expect(ReferenceRange(low: nil, high: 5.3).status(for: -100) == .normal)
    }
}
