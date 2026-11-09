import SwiftUI
import UIKit

/// Wraps `UIActivityViewController` so the review screen can share an exported
/// CDA file via the standard share sheet.
struct CDAShareSheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        // On iPad the share sheet is a popover and requires a non-nil source view
        // or it traps; anchor it to its own view so it presents centered.
        if let popover = controller.popoverPresentationController {
            popover.sourceView = controller.view
            popover.permittedArrowDirections = []
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {
        if let popover = uiViewController.popoverPresentationController,
           let source = popover.sourceView {
            popover.sourceRect = CGRect(x: source.bounds.midX, y: source.bounds.midY, width: 0, height: 0)
        }
    }
}

/// The pinned bottom action bar on the review screen: a prominent "save to
/// Health" button and a secondary "share CDA" button, fading into the content
/// above. Dimmed and non-interactive until at least one value is exportable.
struct ReviewActionBar: View {
    let isEnabled: Bool
    /// When true, the report still has two or more values sharing a LOINC code.
    /// We show a warning and keep save/share dimmed until the user resolves them,
    /// so the disabled state isn't a mystery.
    var hasDuplicates: Bool = false
    /// Selected values that can't be saved as written; save/share stay dimmed until they're dealt with.
    var valuesToCorrect: Int = 0
    let onSave: () -> Void
    let onShare: () -> Void
    /// Pinned over the list, or a row in it (`ReviewView.pinsActionBar`).
    var isPinned = true

    /// Short screens (iPhone and folded iPhone Duo in landscape) put the two
    /// buttons side by side so the pinned bar doesn't eat a third of the height.
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// Height of the top strip over which the material fades in. The buttons sit
    /// below it, so content scrolling underneath never shows through behind them.
    private let fadeHeight: CGFloat = 20

    private var buttonLayout: AnyLayout {
        verticalSizeClass == .compact
            ? AnyLayout(HStackLayout(spacing: 12))
            : AnyLayout(VStackLayout(spacing: 10))
    }

    var body: some View {
        VStack(spacing: 10) {
            if hasDuplicates {
                Label(
                    "Some tests appear more than once. Keep one value per test before saving.",
                    systemImage: "exclamationmark.triangle.fill"
                )
                .font(.footnote)
                .foregroundStyle(.statusWarning)
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            if valuesToCorrect > 0 {
                Label("\(valuesToCorrect) values to correct", systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(.statusCritical)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            buttonLayout {
                // White whatever the list around it sets (`readableListText`), when the bar is one of its rows.
                Button(action: onSave) { buttonTitle("Save to Health Records").foregroundStyle(.white) }
                    .buttonStyle(.borderedProminent)
                    .tint(.prominentFill)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)

                Button(action: onShare) { buttonTitle("Share Report File") }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
            }
            // A capsule around three lines of text is a circle that cuts them off.
            .buttonBorderShape(dynamicTypeSize.isAccessibilitySize ? .roundedRectangle(radius: 20) : .automatic)
            .opacity(isEnabled ? 1 : 0.4)
            .allowsHitTesting(isEnabled)
        }
        .padding(.horizontal, isPinned ? nil : 0)
        .padding(.bottom, isPinned ? nil : 0)
        .padding(.top, isPinned ? fadeHeight : 0)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("review-action-bar")
        .background {
            if isPinned {
                Rectangle()
                    .fill(.regularMaterial)
                    .mask {
                        VStack(spacing: 0) {
                            LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom)
                                .frame(height: fadeHeight)
                            Rectangle()
                        }
                    }
                    .ignoresSafeArea(edges: .bottom)
            }
        }
    }

    private func buttonTitle(_ title: LocalizedStringKey) -> some View {
        Text(title)
            .frame(maxWidth: dynamicTypeSize.isAccessibilitySize ? .infinity : nil)
    }
}

/// Removes the view from the layout while the software keyboard is on screen.
///
/// The review screen pins `ReviewActionBar` to the bottom via `safeAreaInset`, so
/// SwiftUI lifts it to sit just above the keyboard when a field is focused — the
/// exact spot the keyboard's own "Done" accessory toolbar docks to, leaving the two
/// overlapping. Dropping the action bar during editing hands that strip to the
/// keyboard toolbar; it animates back in when editing ends. We watch the keyboard
/// notifications (rather than a `FocusState`) so this also covers the per-row value
/// fields, whose focus is private to `LabValueRowView`. The `onReceive` lives on an
/// always-present container so the hide notification still arrives once the content
/// has been removed.
private struct HideWhileKeyboardVisible: ViewModifier {
    @State private var keyboardVisible = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        Group {
            if !keyboardVisible {
                content.transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.25)) { keyboardVisible = true }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.25)) { keyboardVisible = false }
        }
    }
}

extension View {
    /// Hides the view while the software keyboard is visible — see `HideWhileKeyboardVisible`.
    func hiddenWhileKeyboardVisible() -> some View {
        modifier(HideWhileKeyboardVisible())
    }
}

/// A "we detected X — use it?" row offering to fill a field with a value found
/// in the report or in Apple Health.
struct SuggestionRow: View {
    let label: LocalizedStringKey
    let onUse: () -> Void

    var body: some View {
        HStack {
            Text(label)
                .font(.footnote)
                .foregroundStyle(.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer()
            Button("Use", action: onUse)
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
                .fontWeight(.semibold)
                .font(.footnote)
        }
    }
}

/// A clinical category paired with how many values fall into it — used to drive
/// the chips on the review header and the grouped section headers.
struct CategoryCount: Identifiable {
    let category: LabCategory
    let count: Int

    var id: String { category.rawValue }
}

/// Summary card shown at the top of the review screen, mirroring the look of the
/// header cards in `HistoryView` / `ReportDetailView`: a gradient icon, the value
/// count, what's left to do, and a row of category chips. Kept short so the
/// values themselves start on the first screen.
struct ReviewHeaderCard: View {
    let supportedCount: Int
    let exportableCount: Int
    var duplicateCount: Int = 0
    /// Selected values that can't be saved as written.
    var valuesToCorrect: Int = 0
    /// Values about to be saved that the report text doesn't print.
    var valuesToCheck: Int = 0
    let groups: [CategoryCount]
    let dominantColor: Color

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var chips: some View {
        ForEach(groups) { group in
            CategoryChip(category: group.category, count: group.count)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: [dominantColor, dominantColor.opacity(0.65)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                    Image(systemName: "checklist")
                        .decorativeIcon(size: 18, weight: .semibold)
                        .foregroundStyle(.white)
                }
                .frame(width: 44, height: 44)
                .shadow(color: dominantColor.opacity(0.35), radius: 5, x: 0, y: 2)

                // The status has its own line and may wrap: some languages' words don't fit beside the count.
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(supportedCount) values")
                        .font(.headline)
                    statusBadge
                }

                Spacer(minLength: 0)
            }

            if groups.count > 1 {
                if dynamicTypeSize.isAccessibilitySize {
                    // One line held a chip and a half at the largest text ("Ho…"): they wrap instead.
                    FlowLayout(spacing: 8) { chips }
                } else {
                    // One scrolling line, however many categories: the values must start on the first screen.
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) { chips }
                    }
                    .edgeFadeScroll()
                }
            }

            if duplicateCount > 0 {
                Label(
                    "Some tests appear more than once. Keep one value per test before saving.",
                    systemImage: "exclamationmark.triangle.fill"
                )
                .font(.footnote.weight(.medium))
                .foregroundStyle(.statusWarning)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 22)
    }

    @ViewBuilder
    private var statusBadge: some View {
        if valuesToCorrect > 0 {
            badge("\(valuesToCorrect) values to correct", systemImage: "exclamationmark.triangle.fill", color: .statusCritical)
                .accessibilityIdentifier("review-values-to-correct")
        } else if valuesToCheck > 0 {
            badge("\(valuesToCheck) values to check", systemImage: "exclamationmark.magnifyingglass", color: .statusWarning)
                .accessibilityIdentifier("review-values-to-check")
        } else if exportableCount > 0 {
            badge("Ready to save", systemImage: "checkmark.circle.fill", color: .statusPositive)
        }
    }

    private func badge(_ title: LocalizedStringKey, systemImage: String, color: Color) -> some View {
        HStack(spacing: 4) {
            Image(systemName: systemImage)
                .accessibilityHidden(true)
            Text(title)
        }
            .font(.caption.weight(.semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(color.opacity(0.12), in: Capsule())
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityElement(children: .combine)
    }
}

/// A pill summarizing one clinical category and its value count.
struct CategoryChip: View {
    let category: LabCategory
    let count: Int

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(category.color)
                .frame(width: 8, height: 8)
            Text(category.displayName)
                .font(.caption.weight(.medium))
            Text("\(count)")
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.textSecondary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(category.color.opacity(0.12), in: Capsule())
        .overlay(
            Capsule().stroke(category.color.opacity(0.25), lineWidth: 0.5)
        )
        .accessibilityElement(children: .combine)
    }
}

/// Section header for a category group: a colored dot, the localized category
/// name, and the count — matching `ReportDetailView`'s grouped headers.
struct CategorySectionHeader: View {
    let category: LabCategory
    let count: Int

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(category.color)
                .frame(width: 9, height: 9)
            Text(category.displayName)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
            Spacer()
            Text("\(count)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.textSecondary)
        }
        .textCase(nil)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Previews

#if DEBUG
#Preview("Header Card") {
    ReviewHeaderCard(
        supportedCount: 10,
        exportableCount: 8,
        groups: CategoryCount.sampleGroups,
        dominantColor: LabCategory.lipids.color
    )
    .padding()
    .background(Color(.systemGroupedBackground))
}

#Preview("Header Card – To Check") {
    ReviewHeaderCard(
        supportedCount: 10,
        exportableCount: 8,
        valuesToCheck: 2,
        groups: CategoryCount.sampleGroups,
        dominantColor: LabCategory.lipids.color
    )
    .padding()
    .background(Color(.systemGroupedBackground))
}

#Preview("Header Card – To Check, Dark") {
    ReviewHeaderCard(
        supportedCount: 10,
        exportableCount: 8,
        valuesToCheck: 1,
        groups: CategoryCount.sampleGroups,
        dominantColor: LabCategory.lipids.color
    )
    .padding()
    .background(Color(.systemGroupedBackground))
    .preferredColorScheme(.dark)
}

// A long translation of the status on the narrow left pane of an unfolded iPhone Duo: it wraps.
#Preview("Header Card – Narrow, Long Status", traits: .fixedLayout(width: 290, height: 220)) {
    ReviewHeaderCard(
        supportedCount: 13,
        exportableCount: 13,
        groups: CategoryCount.sampleGroups,
        dominantColor: LabCategory.glycemic.color
    )
    .padding()
    .background(Color(.systemGroupedBackground))
    .environment(\.locale, Locale(identifier: "el"))
}

// The left pane of the Review screen on an unfolded iPhone Duo.
#Preview("Header Card – Narrow (Duo pane)", traits: .fixedLayout(width: 290, height: 220)) {
    ReviewHeaderCard(
        supportedCount: 13,
        exportableCount: 13,
        groups: CategoryCount.sampleGroups,
        dominantColor: LabCategory.glycemic.color
    )
    .padding()
    .background(Color(.systemGroupedBackground))
}

// The largest text: the chips wrap rather than cut off at the card's edge.
#Preview("Header Card – Largest Text") {
    ScrollView {
        ReviewHeaderCard(
            supportedCount: 8,
            exportableCount: 8,
            valuesToCheck: 1,
            groups: CategoryCount.sampleGroups,
            dominantColor: LabCategory.glycemic.color
        )
        .padding()
    }
    .background(Color(.systemGroupedBackground))
    .dynamicTypeSize(.accessibility5)
}
#endif

#if DEBUG
#Preview("Category Chips") {
    VStack(alignment: .leading, spacing: 12) {
        CategorySectionHeader(category: .lipids, count: 4)
        HStack {
            ForEach(CategoryCount.sampleGroups) { group in
                CategoryChip(category: group.category, count: group.count)
            }
        }
        SuggestionRow(label: "Detected in report: \"Max Mustermann\"") {}
    }
    .padding()
}
#endif
