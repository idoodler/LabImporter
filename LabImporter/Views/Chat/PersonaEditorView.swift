import SwiftUI

/// Create or edit a user-defined specialist. The user chooses a name, look,
/// focus areas and an optional tone note; the focus areas decide which Health
/// tools the chat session is granted. The fixed safety preamble is applied at
/// session-build time (see `MedicalPersona.systemInstructions`) and is not
/// editable here, so a custom persona can never drop the medical-safety framing.
struct PersonaEditorView: View {
    let existing: MedicalPersona?
    let onSave: (MedicalPersona) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var tone: String
    @State private var iconName: String
    @State private var accent: LabCategory
    @State private var domains: Set<LabCategory>

    /// Focus areas offered for selection — every clinical category except the
    /// catch-all "Other".
    private static let selectableDomains = LabCategory.allCases.filter { $0 != .other }

    /// A curated palette of glyphs that read well as a specialist avatar.
    private static let iconChoices = [
        "stethoscope", "heart.fill", "drop.degreesign.fill", "cross.vial.fill",
        "atom", "leaf.fill", "bolt.fill", "lungs.fill", "drop.fill", "pills.fill",
        "brain.head.profile", "figure.run", "sparkles", "cross.case.fill"
    ]

    init(existing: MedicalPersona?, onSave: @escaping (MedicalPersona) -> Void) {
        self.existing = existing
        self.onSave = onSave
        _name = State(initialValue: existing?.name ?? "")
        _tone = State(initialValue: existing?.tone ?? "")
        _iconName = State(initialValue: existing?.iconName ?? Self.iconChoices[0])
        _accent = State(initialValue: existing?.accent ?? .glycemic)
        _domains = State(initialValue: Set(existing?.domains ?? []))
    }

    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canSave: Bool { !trimmedName.isEmpty && !domains.isEmpty }

    /// Selected focus areas in the canonical `LabCategory` order, so the saved
    /// persona (and its summary) is stable regardless of tap order.
    private var orderedDomains: [LabCategory] {
        LabCategory.allCases.filter { domains.contains($0) }
    }

    private var summary: String {
        orderedDomains.isEmpty
            ? String(localized: "General health")
            : orderedDomains.map(\.displayName).joined(separator: ", ")
    }

    var body: some View {
        NavigationStack {
            Form {
                previewCard
                nameSection
                appearanceSection
                focusSection
                toneSection
            }
            .navigationTitle(existing == nil ? "New Specialist" : "Edit Specialist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbarItems }
        }
    }

    // MARK: - Preview card

    private var previewCard: some View {
        Section {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [accent.color, accent.color.opacity(0.7)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 52, height: 52)
                    Image(systemName: iconName)
                        .font(.title2)
                        .foregroundStyle(.white)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(trimmedName.isEmpty ? String(localized: "Your specialist") : trimmedName)
                        .font(.body.weight(.semibold))
                    Text(summary)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
            .padding(.vertical, 4)
        }
    }

    // MARK: - Sections

    private var nameSection: some View {
        Section("Name") {
            TextField("e.g. My Diabetes Coach", text: $name)
        }
    }

    private var appearanceSection: some View {
        Section("Appearance") {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    ForEach(LabCategory.allCases.filter { $0 != .other }, id: \.self) { category in
                        Circle()
                            .fill(category.color)
                            .frame(width: 30, height: 30)
                            .overlay(selectionRing(selected: accent == category))
                            .onTapGesture { accent = category }
                    }
                }
                .padding(.vertical, 4)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(Self.iconChoices, id: \.self) { glyph in
                        Image(systemName: glyph)
                            .font(.title3)
                            .frame(width: 40, height: 40)
                            .foregroundStyle(iconName == glyph ? Color.white : accent.color)
                            .background(
                                Circle().fill(iconName == glyph ? accent.color : accent.color.opacity(0.12))
                            )
                            .onTapGesture { iconName = glyph }
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    private func selectionRing(selected: Bool) -> some View {
        Circle()
            .stroke(Color.primary, lineWidth: selected ? 2.5 : 0)
            .padding(-3)
            .opacity(selected ? 1 : 0)
    }

    private var focusSection: some View {
        Section {
            ForEach(Self.selectableDomains, id: \.self) { category in
                Button {
                    toggle(category)
                } label: {
                    HStack {
                        Image(systemName: category.icon)
                            .foregroundStyle(category.color)
                            .frame(width: 24)
                        Text(category.displayName)
                            .foregroundStyle(.primary)
                        Spacer()
                        if domains.contains(category) {
                            Image(systemName: "checkmark")
                                .foregroundStyle(.tint)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        } header: {
            Text("Focus Areas")
        } footer: {
            Text("Focus areas decide which of your health data this specialist can read and what it emphasizes. Pick at least one.")
        }
    }

    private var toneSection: some View {
        Section {
            TextField("e.g. Explain simply and focus on trends", text: $tone, axis: .vertical)
                .lineLimit(2...5)
                .onChange(of: tone) { _, newValue in
                    if newValue.count > MedicalPersona.maxToneLength {
                        tone = String(newValue.prefix(MedicalPersona.maxToneLength))
                    }
                }
        } header: {
            Text("Personality & Tone")
        } footer: {
            Text("Optional. This adjusts wording and emphasis only — it can't change the safety rules every specialist follows.")
        }
    }

    // MARK: - Toolbar & actions

    @ToolbarContentBuilder
    private var toolbarItems: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            Button("Cancel") { dismiss() }
        }
        ToolbarItem(placement: .confirmationAction) {
            Button("Save") { save() }
                .disabled(!canSave)
        }
    }

    private func toggle(_ category: LabCategory) {
        if domains.contains(category) {
            domains.remove(category)
        } else {
            domains.insert(category)
        }
    }

    private func save() {
        let persona = MedicalPersona(
            id: existing?.id ?? UUID().uuidString,
            name: trimmedName,
            summary: summary,
            iconName: iconName,
            accent: accent,
            domains: orderedDomains,
            tone: tone,
            isBuiltIn: false
        )
        onSave(persona)
        dismiss()
    }
}

// MARK: - Previews

#Preview("New") {
    PersonaEditorView(existing: nil, onSave: { _ in })
}

#Preview("Edit · Dark") {
    PersonaEditorView(existing: .sampleCustom, onSave: { _ in })
        .preferredColorScheme(.dark)
}
