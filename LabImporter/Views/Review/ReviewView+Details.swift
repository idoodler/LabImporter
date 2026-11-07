import SwiftUI

/// The patient and lab names on the report under review. They belong to that
/// report until a new one is saved: opening an old report or cancelling must
/// not change what the next report starts with.
struct ReviewNames: Equatable {
    var patient: String
    var author: String

    private static let patientKey = "patientName"
    private static let authorKey = "authorName"

    /// A saved report keeps its own names, even empty ones; a new one starts with the names last saved.
    init(replacing report: LabReport?, defaults: UserDefaults = .standard) {
        patient = report?.patientName ?? defaults.string(forKey: Self.patientKey) ?? ""
        author = report?.authorName ?? defaults.string(forKey: Self.authorKey) ?? ""
    }

    /// A new report's names are remembered for the next one; editing an old report leaves them.
    func rememberForNextReport(replacing report: LabReport?, defaults: UserDefaults = .standard) {
        guard report == nil else { return }
        defaults.set(patient.trimmingCharacters(in: .whitespacesAndNewlines), forKey: Self.patientKey)
        defaults.set(author.trimmingCharacters(in: .whitespacesAndNewlines), forKey: Self.authorKey)
    }
}

extension ReviewView {
    /// Patient and lab after the values, folded to one line of what's filled in.
    /// Names found in the report stay in view even then: they're offers to take up.
    var detailsSection: some View {
        Section {
            DisclosureGroup(isExpanded: $showsDetails) {
                patientRows
                authorRows
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Patient and Lab")
                    Text(detailsSummary)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .accessibilityElement(children: .combine)
            }
            .accessibilityIdentifier("review-details")

            if !showsDetails {
                patientNameSuggestion
                authorNameSuggestion
            }
        }
        .listRowBackground(Rectangle().fill(.ultraThinMaterial))
    }

    var patientSection: some View {
        Section("Patient") { patientRows }
            .listRowBackground(Rectangle().fill(.ultraThinMaterial))
    }

    var authorSection: some View {
        Section("Lab / Doctor") { authorRows }
            .listRowBackground(Rectangle().fill(.ultraThinMaterial))
    }

    private var detailsSummary: String {
        var filledIn = [names.patient, names.author]
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        if birthdateInterval != 0 {
            filledIn.append(Date(timeIntervalSinceReferenceDate: birthdateInterval).formatted(date: .abbreviated, time: .omitted))
        }
        if patientSexRaw != 0 {
            filledIn.append(sexName(patientSexRaw))
        }
        return filledIn.isEmpty ? String(localized: "Not filled in") : filledIn.joined(separator: " · ")
    }

    @ViewBuilder
    private var patientRows: some View {
        TextField("Full Name (optional)", text: $names.patient)
            .autocorrectionDisabled()
            .textContentType(.name)
            .focused($anyFieldFocused)

        patientNameSuggestion

        if birthdateInterval != 0 {
            HStack {
                DatePicker(
                    "Birthday",
                    selection: birthdateBinding,
                    in: ...Date.now,
                    displayedComponents: .date
                )
                Button { birthdateInterval = 0 } label: {
                    Image(systemName: "xmark.circle.fill")
                }
                .foregroundStyle(Color.secondary)
                .buttonStyle(.plain).accessibilityLabel("Clear Birthday")
            }
        }

        if let hkDob = hkBirthdate, hkBirthdateDiffers {
            SuggestionRow(label: "Detected in Health: \"\(hkDob.formatted(date: .abbreviated, time: .omitted))\"") {
                birthdateInterval = hkDob.timeIntervalSinceReferenceDate
            }
        }

        Picker("Gender", selection: $patientSexRaw) {
            Text("–").tag(0)
            Text("Female").tag(1)
            Text("Male").tag(2)
            Text("Other").tag(3)
        }

        if let hkSexRaw = hkSex, hkSexRaw != 0, hkSexRaw != patientSexRaw {
            SuggestionRow(label: "Detected in Health: \"\(sexName(hkSexRaw))\"") {
                patientSexRaw = hkSexRaw
            }
        }
    }

    @ViewBuilder
    private var authorRows: some View {
        TextField("Lab / Doctor (optional)", text: $names.author)
            .autocorrectionDisabled()
            .textContentType(.organizationName)
            .focused($anyFieldFocused)

        authorNameSuggestion
    }

    @ViewBuilder
    private var patientNameSuggestion: some View {
        if let extracted = extractedPatientName, !extracted.isEmpty, extracted != names.patient {
            SuggestionRow(label: "Detected in report: \"\(extracted)\"") {
                names.patient = extracted
            }
        }
    }

    @ViewBuilder
    private var authorNameSuggestion: some View {
        if let extracted = extractedAuthorName, !extracted.isEmpty, extracted != names.author {
            SuggestionRow(label: "Detected in report: \"\(extracted)\"") {
                names.author = extracted
            }
        }
    }

    private var birthdateBinding: Binding<Date> {
        Binding(
            get: { Date(timeIntervalSinceReferenceDate: birthdateInterval) },
            set: { birthdateInterval = $0.timeIntervalSinceReferenceDate }
        )
    }

    private var hkBirthdateDiffers: Bool {
        guard let hkDob = hkBirthdate else { return false }
        guard birthdateInterval != 0 else { return true }
        let stored = Date(timeIntervalSinceReferenceDate: birthdateInterval)
        return !Calendar.current.isDate(stored, inSameDayAs: hkDob)
    }

    private func sexName(_ raw: Int) -> String {
        switch raw {
        case 1: return String(localized: "Female")
        case 2: return String(localized: "Male")
        case 3: return String(localized: "Other")
        default: return "–"
        }
    }
}
