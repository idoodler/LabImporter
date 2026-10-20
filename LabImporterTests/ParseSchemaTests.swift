import Foundation
import FoundationModels
import LabImporterCore
import Testing
@testable import LabImporter

struct ParseSchemaTests {
    /// The Foundation Models schema steers extraction on iOS; Android's Gemini
    /// Nano prompt is built from the same texts (`LabParseSchema`). They must
    /// not drift apart.
    @Test func generableGuidesMatchTheSharedSchema() throws {
        let data = try JSONEncoder().encode(AILabReport.generationSchema)
        let json = try JSONSerialization.jsonObject(with: data)
        var descriptions: Set<String> = []
        func collect(_ node: Any) {
            if let object = node as? [String: Any] {
                if let text = object["description"] as? String { descriptions.insert(text) }
                object.values.forEach(collect)
            } else if let array = node as? [Any] {
                array.forEach(collect)
            }
        }
        collect(json)
        let schema = LabParseSchema.shared
        let shared: Set<String> = [
            schema.ENTRIES, schema.REPORT_DATE, schema.PATIENT_NAME, schema.AUTHOR_NAME, schema.REPORT_LANGUAGE,
            schema.CODE, schema.NAME, schema.RAW_VALUE, schema.UNIT
        ]
        #expect(descriptions == shared)
    }
}
