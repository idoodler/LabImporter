import SwiftUI

// MARK: - RangeStatus styling

extension RangeStatus {
    /// Tint for the badge and for emphasising an out-of-range value. `.normal`
    /// has no dedicated colour (it is never badged) and falls back to secondary.
    var color: Color {
        switch self {
        case .high: return .orange
        case .low: return .blue
        case .normal: return .secondary
        }
    }

    /// Directional glyph: a value above its range points up, below points down.
    var symbolName: String {
        switch self {
        case .high: return "arrow.up"
        case .low: return "arrow.down"
        case .normal: return "checkmark"
        }
    }

    /// Short, localized label shown in the capsule.
    var label: LocalizedStringKey {
        switch self {
        case .high: return "High"
        case .low: return "Low"
        case .normal: return "In range"
        }
    }
}

// MARK: - RangeStatusBadge

/// A compact "High"/"Low" capsule shown next to an out-of-range value, matching
/// the visual language of the existing "Suggested"/"Duplicate" badges. Renders
/// nothing for an in-range (`.normal`) status, so callers can place it
/// unconditionally. Drive it from `LabMapping.rangeStatus(for:code:)`.
struct RangeStatusBadge: View {
    let status: RangeStatus
    /// The reference range behind the flag, used for the accessibility hint so
    /// VoiceOver reads e.g. "High, reference range 3.5–5.3". Optional.
    var range: ReferenceRange?
    var unit: String = ""

    var body: some View {
        if status.isOutOfRange {
            Label(status.label, systemImage: status.symbolName)
                .labelStyle(.titleAndIcon)
                .font(.caption2.weight(.medium))
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(status.color.opacity(0.15), in: Capsule())
                .foregroundStyle(status.color)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(accessibilityText)
        }
    }

    private var accessibilityText: Text {
        let prefix = Text(status.label)
        guard let range, !range.isEmpty else { return prefix }
        return prefix + Text(verbatim: ", ") + Text("Reference range \(range.formatted(unit: unit))")
    }
}

// MARK: - Preview

#Preview {
    VStack(alignment: .leading, spacing: 12) {
        RangeStatusBadge(status: .high, range: ReferenceRange(low: 3.5, high: 5.3), unit: "%")
        RangeStatusBadge(status: .low, range: ReferenceRange(low: 3.5, high: 5.3))
        RangeStatusBadge(status: .normal)
    }
    .padding()
}
