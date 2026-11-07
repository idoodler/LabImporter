import SwiftUI

struct LabValueRowView: View {
    @Binding var value: LabValue
    /// True when another exportable row carries the same LOINC code; surfaces a
    /// "Duplicate" badge so the user can resolve the collision before saving.
    var isDuplicate: Bool = false

    @State private var showCodePicker = false
    @FocusState private var isFocused: Bool

    private var hasLoincCode: Bool {
        LabMapping.loincCode(for: value.code) != nil
    }

    /// Where this row's value sits relative to the user's reference range for the
    /// code, or `nil` when there is no value or no range (so no badge shows).
    private var rangeStatus: RangeStatus? {
        LabMapping.rangeStatus(for: value.numericValue, code: value.code)
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Button {
                value.isSelected = !value.isSelected
            } label: {
                Image(systemName: value.isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(value.isSelected ? Color.accentColor : Color.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Include in Export")
            .accessibilityValue(value.isSelected ? "Included" : "Excluded")

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(value.resolvedName)
                        .font(.body)

                    if value.isSuggestedCode {
                        Text("Suggested")
                            .font(.caption2.weight(.medium))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.accentColor.opacity(0.15), in: Capsule())
                            .foregroundStyle(Color.accentColor)
                    } else if !hasLoincCode {
                        Image(systemName: "doc.badge.minus")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                            .accessibilityLabel("No LOINC Code")
                            .help("No LOINC code — excluded from CDA export")
                    }

                    if isDuplicate {
                        Label("Duplicate", systemImage: "exclamationmark.triangle.fill")
                            .labelStyle(.titleAndIcon)
                            .font(.caption2.weight(.medium))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.orange.opacity(0.15), in: Capsule())
                            .foregroundStyle(.orange)
                            .help("Another value uses the same LOINC code — keep only one")
                    }

                    if let rangeStatus {
                        RangeStatusBadge(status: rangeStatus,
                                         range: LabMapping.referenceRange(for: value.code),
                                         unit: value.unit)
                    }
                }

                HStack(spacing: 10) {
                    Button { showCodePicker = true } label: {
                        HStack(spacing: 3) {
                            Text(value.code)
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                                .textCase(.uppercase)
                            Image(systemName: "chevron.up.chevron.down")
                                .font(.system(size: 7))
                                .foregroundStyle(.quaternary)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("review-code-button")

                    // Like Android's row: a suggestion stands until it's confirmed or another test is chosen.
                    if value.isSuggestedCode {
                        Button("Confirm") { value.isSuggestedCode = false }
                            .buttonStyle(.plain)
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(Color.accentColor)
                            .accessibilityIdentifier("review-confirm-\(value.code)")
                    }
                }

                if value.needsSourceCheck {
                    compareBadge
                        .padding(.top, 3)
                }

                if value.needsCorrection {
                    correctBadge
                        .padding(.top, 3)
                }
            }
            // The name gets all the width the value doesn't need: long catalog names otherwise run to six lines.
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 6) {
                valueField

                if !value.unit.isEmpty {
                    Text(value.unit)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .fixedSize()
                }
            }
            .layoutPriority(1)
        }
        .padding(.vertical, 2)
        .sheet(isPresented: $showCodePicker) {
            CodePickerSheet(code: $value.code, name: $value.name, onChoose: { value.isSuggestedCode = false })
        }
    }

    /// The result isn't in the report text. Said in full to VoiceOver, here and on the value itself.
    private var compareBadge: some View {
        HStack(spacing: 3) {
            Image(systemName: "exclamationmark.magnifyingglass")
            Text("Compare with report")
        }
            .font(.caption2.weight(.medium))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.orange.opacity(0.15), in: Capsule())
            .foregroundStyle(.orange)
            .fixedSize(horizontal: false, vertical: true)
            .help(Self.compareExplanation)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Self.compareExplanation)
            .accessibilityIdentifier("review-compare-badge")
    }

    /// Saving would leave this result out, and it can look fine: the field shows text a number can't be read from.
    private var correctBadge: some View {
        HStack(spacing: 3) {
            Image(systemName: "exclamationmark.triangle.fill")
                .accessibilityHidden(true)
            Text("Can't be saved as written. Correct or deselect it.")
        }
            .font(.caption2.weight(.medium))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.red.opacity(0.15), in: Capsule())
            .foregroundStyle(.red)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("review-correct-badge")
    }

    private static let compareExplanation: LocalizedStringKey =
        "This value wasn't found in the report text. Compare it with your report."

    @ViewBuilder
    private var valueField: some View {
        if value.displayValue == "-" {
            Text("–")
                .font(.body)
                .foregroundStyle(.secondary)
        } else {
            // A qualitative result ("Negativ", "1:160") is edited as text.
            let isQualitative = value.qualitativeResult != nil
            TextField("Value", text: valueText)
                .multilineTextAlignment(.trailing)
                .keyboardType(isQualitative ? .default : .decimalPad)
                .focused($isFocused)
                .frame(minWidth: 44, maxWidth: isQualitative ? 130 : 80)
                .accessibilityIdentifier("review-value-field")
                .accessibilityHint(value.needsSourceCheck ? Text(Self.compareExplanation) : Text(verbatim: ""))
        }
    }

    // A binding over the model's value, stripped of its unit for editing. Reading
    // derives the string on demand and writing propagates the user's edit — so we
    // never mutate the model just because the row appeared (which previously
    // re-parsed a lossily-formatted value and looked like an edit).
    private var valueText: Binding<String> {
        Binding(
            get: { strippedValue(value.displayValue) },
            set: { newRaw in
                // A set with the text it already holds is no edit: the value stays to be compared.
                guard newRaw != strippedValue(value.displayValue) else { return }
                value.applyEditedResult(newRaw)
            }
        )
    }

    private func strippedValue(_ raw: String) -> String {
        guard !value.unit.isEmpty else { return raw }
        for suffix in [" \(value.unit)", value.unit] where raw.lowercased().hasSuffix(suffix.lowercased()) {
            return String(raw.dropLast(suffix.count)).trimmingCharacters(in: .whitespaces)
        }
        return raw
    }
}

// MARK: - Preview

#Preview {
    @Previewable @State var value = LabValue(
        code: "KREA",
        name: "Creatinine",
        displayValue: "0.91",
        numericValue: 0.91,
        unit: "mg/dl"
    )
    List {
        LabValueRowView(value: $value)
    }
}

#if DEBUG
#Preview("Compare with Report") {
    @Previewable @State var misread = LabValue.sampleToCheck[0]
    @Previewable @State var suggested = LabValue.sampleToCheck[1]
    List {
        LabValueRowView(value: $misread)
        LabValueRowView(value: $suggested)
    }
}

#Preview("Compare with Report – Dark") {
    @Previewable @State var misread = LabValue.sampleToCheck[0]
    @Previewable @State var suggested = LabValue.sampleToCheck[1]
    List {
        LabValueRowView(value: $misread)
        LabValueRowView(value: $suggested)
    }
    .preferredColorScheme(.dark)
}

#Preview("Can't Be Saved") {
    @Previewable @State var misread = LabValue.sampleToCorrect
    List {
        LabValueRowView(value: $misread)
    }
}

#Preview("Can't Be Saved – Dark") {
    @Previewable @State var misread = LabValue.sampleToCorrect
    List {
        LabValueRowView(value: $misread)
    }
    .preferredColorScheme(.dark)
}

#Preview("Qualitative") {
    @Previewable @State var negative = LabValue.sampleQualitative[0]
    @Previewable @State var titer = LabValue.sampleQualitative[1]
    @Previewable @State var threshold = LabValue.sampleQualitative[2]
    List {
        LabValueRowView(value: $negative)
        LabValueRowView(value: $titer)
        LabValueRowView(value: $threshold)
    }
}

#Preview("Qualitative – Dark") {
    @Previewable @State var negative = LabValue.sampleQualitative[0]
    @Previewable @State var threshold = LabValue.sampleQualitative[2]
    List {
        LabValueRowView(value: $negative)
        LabValueRowView(value: $threshold)
    }
    .preferredColorScheme(.dark)
}
#endif
