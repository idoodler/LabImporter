import SwiftUI

/// Side by side, until accessibility text sizes leave each part too narrow to read:
/// then one above the other, so text wraps at words instead of at single letters.
struct AdaptiveRow<Content: View>: View {
    var horizontalAlignment: VerticalAlignment = .center
    var spacing: CGFloat = 12
    @ViewBuilder let content: Content

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: spacing))
            : AnyLayout(HStackLayout(alignment: horizontalAlignment, spacing: spacing))
        layout { content }
    }
}

/// Side by side while the parts fit on one line, one above the other when they
/// don't — at any text size, and never by cutting text off or shrinking it.
struct FittingRow<Content: View>: View {
    var alignment: VerticalAlignment = .firstTextBaseline
    var spacing: CGFloat = 4
    @ViewBuilder let content: Content

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: alignment, spacing: spacing) { content }
            VStack(alignment: .leading, spacing: spacing) { content }
        }
    }
}

/// A small inline control (an icon, a code) with the 44 pt tap target accessibility
/// asks for; apply with `tapTargetButton(vertical:horizontal:)`.
private struct TapTargetButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
            .opacity(configuration.isPressed ? 0.5 : 1)
    }
}

/// The target reaches over its neighbours rather than pushing them away, so rows keep
/// their density — until the text is taller than the target anyway and would be overlapped.
private struct TapTargetOverlap: ViewModifier {
    let vertical: CGFloat
    let horizontal: CGFloat

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func body(content: Content) -> some View {
        content
            .padding(.vertical, dynamicTypeSize.isAccessibilitySize ? 0 : -vertical)
            .padding(.horizontal, dynamicTypeSize.isAccessibilitySize ? 0 : -horizontal)
    }
}

extension View {
    /// A plain button whose tap target is at least 44 pt, reaching `vertical` and `horizontal` points over what's around it.
    func tapTargetButton(vertical: CGFloat = 8, horizontal: CGFloat = 8) -> some View {
        buttonStyle(TapTargetButtonStyle())
            .modifier(TapTargetOverlap(vertical: vertical, horizontal: horizontal))
    }
}

// `.tint(.prominentFill)` on every `.borderedProminent` button: white on the accent
// colour is under 2:1 in dark mode, where the accent is light enough to read as text on a
// bordered button's grey (4.7:1), and this fill is the accent made dark enough for 4.5:1.

extension View {
    /// For a `List`: its section headers and footers draw in the system's secondary
    /// colour, which is 3.4:1 on white and less over a wash. They follow this instead.
    func readableListText() -> some View {
        foregroundStyle(Color.primary, Color.textSecondary, Color.textTertiary)
    }
}

extension Image {
    /// A glyph inside a tile of fixed size. It says nothing the text beside it doesn't,
    /// so it's hidden from VoiceOver and needn't follow Dynamic Type.
    func decorativeIcon(size: CGFloat, weight: Font.Weight = .regular) -> some View {
        font(.system(size: size, weight: weight))
            .accessibilityHidden(true)
    }
}

// MARK: - Previews

#if DEBUG
private struct LayoutSample: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            AdaptiveRow {
                Image(systemName: "testtube.2").decorativeIcon(size: 24)
                Text(verbatim: "A name beside its value")
            }
            FittingRow {
                Text(verbatim: "Non-reactive").font(.title2.bold())
                Text(verbatim: "mg/dL").font(.caption)
            }
            Button {} label: {
                Image(systemName: "pin")
            }
            .tapTargetButton()
        }
        .padding()
    }
}

#Preview("Standard") {
    LayoutSample()
}

#Preview("Largest Text") {
    LayoutSample()
        .dynamicTypeSize(.accessibility5)
}

#Preview("Dark") {
    LayoutSample()
        .preferredColorScheme(.dark)
}

#Preview("RTL") {
    LayoutSample()
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
