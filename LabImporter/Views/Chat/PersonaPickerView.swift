import SwiftUI

/// Lets the user choose which specialist to talk to, and create/edit/delete
/// their own. Built-in presets and user-created personas are listed together;
/// only custom ones can be edited or deleted.
struct PersonaPickerView: View {
    let builtIns: [MedicalPersona]
    let custom: [MedicalPersona]
    let selectedID: String?
    /// The user's saved conditions/diagnoses (empty if none yet), shown on the
    /// health-profile row.
    let healthContext: String
    let onSelect: (MedicalPersona) -> Void
    let onCreate: () -> Void
    let onEdit: (MedicalPersona) -> Void
    let onDelete: (MedicalPersona) -> Void
    let onEditProfile: () -> Void

    var body: some View {
        List {
            Section("About You") {
                healthProfileRow
            }

            Section {
                ForEach(builtIns) { persona in
                    row(for: persona)
                }
            } header: {
                Text("Specialists")
            } footer: {
                Text("Each specialist reads only your own data, privately on your device.")
            }

            Section("Your Specialists") {
                ForEach(custom) { persona in
                    row(for: persona)
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) { onDelete(persona) } label: {
                                Label("Delete", systemImage: "trash")
                            }
                            Button { onEdit(persona) } label: {
                                Label("Edit", systemImage: "pencil")
                            }
                            .tint(.blue)
                        }
                }
                Button(action: onCreate) {
                    Label("Create Your Own", systemImage: "plus.circle.fill")
                }
            }
        }
        .navigationTitle("Choose a Specialist")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var healthProfileRow: some View {
        Button(action: onEditProfile) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(LabCategory.endocrine.color.gradient)
                        .frame(width: 44, height: 44)
                    Image(systemName: "person.text.rectangle.fill")
                        .font(.title3)
                        .foregroundStyle(.white)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text("Your Health Profile")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text(healthContext.isEmpty
                         ? Text("Add conditions like your diabetes type for tailored answers")
                         : Text(healthContext))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func row(for persona: MedicalPersona) -> some View {
        Button {
            onSelect(persona)
        } label: {
            HStack(spacing: 14) {
                personaIcon(persona)
                VStack(alignment: .leading, spacing: 2) {
                    Text(persona.name)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text(persona.summary)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                if persona.id == selectedID {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(persona.color)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func personaIcon(_ persona: MedicalPersona) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [persona.color, persona.color.opacity(0.7)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
                .frame(width: 44, height: 44)
            Image(systemName: persona.iconName)
                .font(.title3)
                .foregroundStyle(.white)
        }
    }
}

// MARK: - Previews

#Preview("With custom") {
    NavigationStack {
        PersonaPickerView(
            builtIns: MedicalPersona.builtIns,
            custom: [.sampleCustom],
            selectedID: "diabetes",
            healthContext: "Type 1 diabetes since 2015",
            onSelect: { _ in }, onCreate: {}, onEdit: { _ in }, onDelete: { _ in }, onEditProfile: {}
        )
    }
}

#Preview("No custom · Dark") {
    NavigationStack {
        PersonaPickerView(
            builtIns: MedicalPersona.builtIns,
            custom: [],
            selectedID: nil,
            healthContext: "",
            onSelect: { _ in }, onCreate: {}, onEdit: { _ in }, onDelete: { _ in }, onEditProfile: {}
        )
    }
    .preferredColorScheme(.dark)
}
