import SwiftUI

struct LicenseView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                section(title: "LabImporter", body: Self.mitLicenseText)
                section(title: "LOINC", body: Self.loincAttribution)
            }
            .padding(20)
        }
        .navigationTitle("License")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func section(title: String, body: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.headline)
            Text(body)
                .font(.system(.footnote, design: .monospaced))
                .textSelection(.enabled)
        }
    }

    private static let mitLicenseText: String = """
    MIT License

    Copyright (c) 2026 idoodler

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.
    """

    private static var loincAttribution: String {
        LoincDirectory.shared.attribution ?? defaultLoincAttribution
    }

    private static let defaultLoincAttribution: String = """
    This product includes the LOINC table, LOINC codes, and LOINC linguistic
    variants files, copyright © 1995-2024, Regenstrief Institute, Inc. and
    the Logical Observation Identifiers Names and Codes (LOINC) Committee,
    available at no cost under the license at http://loinc.org/license.

    LOINC® is a registered United States trademark of Regenstrief Institute, Inc.
    """
}
