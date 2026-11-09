import SwiftUI

struct LabValueRowView: View {
    @Binding var value: LabValue
    /// True when another exportable row carries the same LOINC code; surfaces a
    /// "Duplicate" badge so the user can resolve the collision before saving.
    var isDuplicate: Bool = false

    @State private var showCodePicker = false
    @FocusState private var isFocused: Bool
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// How wide the value's field may grow: its text follows Dynamic Type, so its room has to.
    @ScaledMetric(relativeTo: .body) private var numberFieldWidth: CGFloat = 80
    @ScaledMetric(relativeTo: .body) private var resultFieldWidth: CGFloat = 130

    /// "Creatinine, Include in Export": VoiceOver walks a row's controls on their own, so each says whose it is.
    private func spoken(_ action: String) -> Text {
        Text(verbatim: "\(value.resolvedName), \(action)")
    }

    private var hasLoincCode: Bool {
        LabMapping.loincCode(for: value.code) != nil
    }

    /// Where this row's value sits relative to the user's reference range for the
    /// code, or `nil` when there is no value or no range (so no badge shows).
    private var rangeStatus: RangeStatus? {
        LabMapping.rangeStatus(for: value.numericValue, code: value.code)
    }

    /// How far the LOINC code's and Confirm's 44 pt targets reach over what's above and below them.
    private static let codeTargetReach: CGFloat = 14
    private static let nameColumnSpacing: CGFloat = 2

    /// A badge under the code keeps out of its targets' reach: lying over their lower 9 pt, it was what a
    /// touch there found, and left them 35 pt tall. They reach over nothing at the accessibility sizes.
    private var badgeSpacing: CGFloat {
        dynamicTypeSize.isAccessibilitySize ? 3 : Self.codeTargetReach - Self.nameColumnSpacing
    }

    var body: some View {
        // The largest text leaves the name and the value too narrow side by side.
        HStack(alignment: dynamicTypeSize.isAccessibilitySize ? .top : .center, spacing: 12) {
            Button {
                value.isSelected = !value.isSelected
            } label: {
                // The check takes the background's colour: white on the dark-mode accent, light for text on grey, can't be seen.
                Image(systemName: value.isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(
                        value.isSelected ? Color(uiColor: .systemBackground) : Color.textSecondary,
                        value.isSelected ? Color.accentColor : Color.textSecondary
                    )
            }
            .tapTargetButton()
            .accessibilityLabel(spoken(String(localized: "Include in Export")))
            .accessibilityValue(value.isSelected ? "Included" : "Excluded")
            .accessibilityIdentifier("review-include-button")

            AdaptiveRow(spacing: 12) {
                nameColumn
                valueColumn
            }
        }
        .padding(.vertical, 2)
        .sheet(isPresented: $showCodePicker) {
            CodePickerSheet(code: $value.code, name: $value.name, onChoose: { value.isSuggestedCode = false })
        }
    }

    private var nameColumn: some View {
            VStack(alignment: .leading, spacing: Self.nameColumnSpacing) {
                // The badges move under a name they'd squeeze: German names broke at every syllable beside "Vorschlag".
                FittingRow(alignment: .center, spacing: 4) {
                    Text(value.resolvedName)
                        .font(.body)

                    if value.isSuggestedCode {
                        Text("Suggested")
                            .font(.caption2.weight(.medium))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.statusLow.opacity(0.12), in: Capsule())
                            .foregroundStyle(.statusLow)
                    } else if !hasLoincCode {
                        Image(systemName: "doc.badge.minus")
                            .font(.caption2)
                            .foregroundStyle(.textTertiary)
                            .accessibilityLabel("No LOINC Code")
                            .help("No LOINC code — won't be saved or shared")
                    }

                    if isDuplicate {
                        Label("Duplicate", systemImage: "exclamationmark.triangle.fill")
                            .labelStyle(.titleAndIcon)
                            .font(.caption2.weight(.medium))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.orange.opacity(0.15), in: Capsule())
                            .foregroundStyle(.statusWarning)
                            .help("Another value uses the same LOINC code — keep only one")
                    }

                    if let rangeStatus {
                        RangeStatusBadge(status: rangeStatus,
                                         range: LabMapping.referenceRange(for: value.code),
                                         unit: value.unit)
                    }
                }
                // "Glucose, Suggested": the badges are said with the name they belong to. On their own, each was
                // one more stop for VoiceOver, and the row's swipe-to-delete action made each a target the
                // accessibility audit found too small (13 pt tall).
                .accessibilityElement(children: .combine)

                FittingRow(alignment: .center, spacing: 10) {
                    Button { showCodePicker = true } label: {
                        HStack(spacing: 3) {
                            Text(value.code)
                                .font(.caption2)
                                .foregroundStyle(.textTertiary)
                                .textCase(.uppercase)
                            Image(systemName: "chevron.up.chevron.down")
                                .decorativeIcon(size: 7)
                                .foregroundStyle(.textTertiary)
                        }
                        // The button speaks for what's in it: left alone, its 7 pt chevron counts as a tiny target of its own.
                        .accessibilityElement(children: .ignore)
                    }
                    .tapTargetButton(vertical: Self.codeTargetReach, horizontal: 0)
                    .accessibilityLabel(spoken(String(localized: "LOINC Code")))
                    .accessibilityValue(Text(verbatim: value.code))
                    .accessibilityIdentifier("review-code-button")

                    // Like Android's row: a suggestion stands until it's confirmed or another test is chosen.
                    if value.isSuggestedCode {
                        Button("Confirm") { value.isSuggestedCode = false }
                            .tapTargetButton(vertical: Self.codeTargetReach, horizontal: 0)
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(Color.accentColor)
                            .accessibilityLabel(spoken(String(localized: "Confirm")))
                            .accessibilityIdentifier("review-confirm-\(value.code)")
                    }
                }

                if value.needsSourceCheck {
                    compareBadge
                        .padding(.top, badgeSpacing)
                }

                if value.needsCorrection {
                    correctBadge
                        .padding(.top, value.needsSourceCheck ? 3 : badgeSpacing)
                }
            }
            // The name gets all the width the value doesn't need: long catalog names otherwise run to six lines.
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var valueColumn: some View {
            // A long unit beside the value broke at single letters ("Thou-sand/uL").
            AdaptiveRow(spacing: 6) {
                valueField

                if !value.unit.isEmpty {
                    Text(value.unit)
                        .font(.caption)
                        .foregroundStyle(.textSecondary)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                        .fixedSize(horizontal: !dynamicTypeSize.isAccessibilitySize, vertical: true)
                        // A text: the row's swipe-to-delete action, which every element in the row carries, made
                        // the accessibility audit take it for a target, too small (14 pt tall).
                        .accessibilityRespondsToUserInteraction(false)
                }
            }
            .layoutPriority(1)
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
            .foregroundStyle(.statusWarning)
            .fixedSize(horizontal: false, vertical: true)
            .help(Self.compareExplanation)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Self.compareExplanation)
            // It explains, it doesn't act: the audit took it for a target 17 pt tall.
            .accessibilityRespondsToUserInteraction(false)
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
            .foregroundStyle(.statusCritical)
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
                .foregroundStyle(.textSecondary)
        } else {
            // A qualitative result ("Negativ", "1:160") is edited as text.
            let isQualitative = value.qualitativeResult != nil
            TextField("Value", text: valueText)
                .multilineTextAlignment(dynamicTypeSize.isAccessibilitySize ? .leading : .trailing)
                .keyboardType(isQualitative ? .default : .decimalPad)
                .focused($isFocused)
                .frame(
                    minWidth: 44,
                    maxWidth: dynamicTypeSize.isAccessibilitySize ? .infinity : (isQualitative ? resultFieldWidth : numberFieldWidth),
                    minHeight: 44
                )
                // The system's field is 22 pt tall: this 44 pt around it focuses it too,
                // reaching over the row's edges rather than growing it. Its text is taller
                // than that at the accessibility sizes, where it would draw over its unit.
                .contentShape(Rectangle())
                .onTapGesture { isFocused = true }
                .padding(.vertical, dynamicTypeSize.isAccessibilitySize ? 0 : -10)
                // Every row's field says "Value" otherwise: VoiceOver has to say which test it is.
                .accessibilityLabel(Text(verbatim: value.resolvedName))
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

#Preview("Long Name, Suggested – German") {
    @Previewable @State var platelets = LabValue(
        code: "777-3", name: "Thrombozyten mittels automatisierter Zählung", displayValue: "263", numericValue: 263,
        unit: "Thousand/uL", isSuggestedCode: true
    )
    List {
        LabValueRowView(value: $platelets)
    }
    .environment(\.locale, Locale(identifier: "de"))
}

#Preview("Largest Text – Long Unit") {
    @Previewable @State var platelets = LabValue(
        code: "777-3", name: "Platelets", displayValue: "263", numericValue: 263, unit: "Thousand/uL"
    )
    List {
        LabValueRowView(value: $platelets)
    }
    .dynamicTypeSize(.accessibility5)
}
#endif
