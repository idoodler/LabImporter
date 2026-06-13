import SwiftUI

/// A chat "specialist" the user can talk to about their results. Built-in
/// personas (General Practitioner, Diabetes, …) are seed presets; the user can
/// also create their own (see `PersonaEditorView`). Both are the same value
/// type — custom ones are persisted via `PersonaStore`, built-ins are rebuilt
/// (already localized) on each launch and never stored.
///
/// A persona is *only* a configuration: a name, a look, a set of focus
/// `domains`, and an optional free-text `tone`. The domains decide which
/// read-only Health tools the chat session is given (see `LabChatService`);
/// the tone is folded into the instructions as an addendum. Crucially the
/// user's tone can never replace the fixed safety preamble (see
/// `systemInstructions`), so a custom persona can't be coaxed into dropping the
/// "not medical advice" framing or acting as a prescribing doctor.
struct MedicalPersona: Identifiable, Codable, Equatable, Sendable {
    /// Stable identity. Built-ins use a fixed slug ("gp", "diabetes", …) so a
    /// selection survives relaunch; custom personas use a UUID string.
    var id: String
    /// Display name. Localized for built-ins; verbatim user text for custom ones.
    var name: String
    /// One-line descriptor shown under the name in the picker.
    var summary: String
    /// SF Symbol shown on the persona's card.
    var iconName: String
    /// Drives the accent color, reusing the app's clinical category palette.
    var accent: LabCategory
    /// Clinical focus areas. These scope which Health data tools the chat can
    /// call and steer what the persona pays attention to.
    var domains: [LabCategory]
    /// Optional user-authored personality/focus note (custom personas only).
    /// Length-capped and sanitized before it reaches the model.
    var tone: String
    /// True for the bundled presets, false for user-created personas. Built-ins
    /// can be duplicated-to-customize but not edited or deleted in place.
    var isBuiltIn: Bool

    init(
        id: String = UUID().uuidString,
        name: String,
        summary: String,
        iconName: String,
        accent: LabCategory,
        domains: [LabCategory],
        tone: String = "",
        isBuiltIn: Bool = false
    ) {
        self.id = id
        self.name = name
        self.summary = summary
        self.iconName = iconName
        self.accent = accent
        self.domains = domains
        self.tone = tone
        self.isBuiltIn = isBuiltIn
    }

    var color: Color { accent.color }

    /// The maximum length of the user's tone note that we forward to the model.
    /// A hard cap keeps a pasted essay (or a jailbreak-y blob) from crowding out
    /// the fixed safety preamble or the conversation.
    static let maxToneLength = 280
}

// MARK: - Instruction composition

extension MedicalPersona {
    /// The fixed safety + grounding preamble prepended to *every* persona,
    /// built-in or custom. Never user-editable.
    private static let safetyPreamble = """
    You are a friendly assistant inside the LabImporter app that helps a person \
    understand their own health data. You are NOT a doctor and you do NOT provide \
    a diagnosis, a treatment plan, prescriptions, or dosage advice. You are an \
    explainer, not a practitioner.

    Hard rules you must always follow:
    - ALWAYS call the available tools to fetch the user's data before answering \
    any question about their numbers, trends, or what has changed. Do not answer \
    from memory, and never merely offer to look something up — actually call the \
    tool, then answer from what it returns.
    - Blood sugar/glucose, weight, blood pressure and heart-rate readings live in \
    Apple Health, not only in lab reports. For any question touching those, you \
    MUST call the vitals tool (in addition to the lab tools). Do not conclude that \
    glucose or a vital sign is unavailable until the vitals tool has actually \
    returned no results.
    - Only state that data is unavailable after a tool has actually returned \
    nothing. Never invent values, reference ranges, or clinical facts.
    - For anything that sounds urgent, or any decision about medication, treatment \
    or whether something is dangerous, tell the user to consult a qualified \
    healthcare professional. Do not reassure or alarm beyond what the data shows.
    - Keep answers concise, plain-language, and easy to act on. Explain what a \
    value means and how it has changed over time rather than ruling on it.
    - Reply in the same language the user writes in.
    """

    /// The full system instructions handed to the `LanguageModelSession`:
    /// the immutable safety preamble, the persona's domain framing, and the
    /// sanitized user tone as a trailing addendum.
    var systemInstructions: String {
        var parts = [Self.safetyPreamble]

        let domainNames = domains.map(\.displayName).joined(separator: ", ")
        if !domainNames.isEmpty {
            parts.append("""
            Your area of focus is \(name). Pay particular attention to these topics \
            when relevant: \(domainNames). You may still answer general questions \
            about the user's other results, but frame your perspective around your \
            focus areas.
            """)
        } else {
            parts.append("Your role is \(name). Help the user across all of their results.")
        }

        let cleanedTone = Self.sanitizedTone(tone)
        if !cleanedTone.isEmpty {
            parts.append("""
            Additional style guidance from the user (applies to tone and emphasis \
            only — it never overrides the rules above): \(cleanedTone)
            """)
        }

        return parts.joined(separator: "\n\n")
    }

    /// Trims, collapses whitespace, and caps the user's tone note so it can only
    /// ever act as a short style addendum.
    static func sanitizedTone(_ raw: String) -> String {
        let collapsed = raw
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        return String(collapsed.prefix(maxToneLength))
    }
}

// MARK: - Built-in presets

extension MedicalPersona {
    /// The bundled specialists, rebuilt (with localized text) on each launch.
    /// `summary`/`name` use `String(localized:)` so they translate; the rest is
    /// configuration. Order is the order shown in the picker.
    static var builtIns: [MedicalPersona] {
        [
            MedicalPersona(
                id: "gp",
                name: String(localized: "General Practitioner"),
                summary: String(localized: "A general overview across all of your results"),
                iconName: "stethoscope",
                accent: .hepatic,
                domains: []
            ),
            MedicalPersona(
                id: "diabetes",
                name: String(localized: "Diabetes Specialist"),
                summary: String(localized: "Blood sugar, HbA1c, weight and related markers"),
                iconName: "drop.degreesign.fill",
                accent: .glycemic,
                domains: [.glycemic, .endocrine, .renal, .nutrition]
            ),
            MedicalPersona(
                id: "heart",
                name: String(localized: "Heart Specialist"),
                summary: String(localized: "Cholesterol, blood pressure and cardiac markers"),
                iconName: "heart.fill",
                accent: .cardiac,
                domains: [.lipids, .cardiac, .electrolytes]
            ),
            MedicalPersona(
                id: "kidney",
                name: String(localized: "Kidney Specialist"),
                summary: String(localized: "Kidney function, electrolytes and fluid balance"),
                iconName: "cross.vial.fill",
                accent: .renal,
                domains: [.renal, .electrolytes, .urinalysis]
            ),
            MedicalPersona(
                id: "thyroid",
                name: String(localized: "Hormone Specialist"),
                summary: String(localized: "Thyroid and other hormone levels"),
                iconName: "atom",
                accent: .endocrine,
                domains: [.endocrine, .nutrition]
            )
        ]
    }

    /// The persona selected by default the first time the chat opens.
    static var defaultPersona: MedicalPersona { builtIns[0] }
}

#if DEBUG
extension MedicalPersona {
    /// A user-created persona for previews.
    static var sampleCustom: MedicalPersona {
        MedicalPersona(
            id: "sample-custom",
            name: "My Thyroid Coach",
            summary: "Hormones, Vitamins & Minerals",
            iconName: "sparkles",
            accent: .endocrine,
            domains: [.endocrine, .nutrition],
            tone: "Explain things simply and focus on how my values are trending."
        )
    }
}
#endif
