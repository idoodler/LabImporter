import SwiftUI

/// Wraps `UIActivityViewController` so the review screen can share an exported
/// CDA file via the standard share sheet.
struct CDAShareSheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
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
/// count, a "ready to save" badge, and a row of category chips.
struct ReviewHeaderCard: View {
    let supportedCount: Int
    let exportableCount: Int
    let groups: [CategoryCount]
    let dominantColor: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 14) {
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
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(.white)
                }
                .frame(width: 54, height: 54)
                .shadow(color: dominantColor.opacity(0.35), radius: 5, x: 0, y: 2)

                VStack(alignment: .leading, spacing: 3) {
                    Text("Lab Report")
                        .font(.headline)
                    Text("\(supportedCount) values")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 0)

                if exportableCount > 0 {
                    readyBadge
                }
            }

            if groups.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(groups) { group in
                            CategoryChip(category: group.category, count: group.count)
                        }
                    }
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 22))
        .overlay(
            RoundedRectangle(cornerRadius: 22)
                .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
    }

    private var readyBadge: some View {
        HStack(spacing: 5) {
            Image(systemName: "checkmark.circle.fill")
            Text("Ready to save")
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(.green)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(.green.opacity(0.12), in: Capsule())
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
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(category.color.opacity(0.12), in: Capsule())
        .overlay(
            Capsule().stroke(category.color.opacity(0.25), lineWidth: 0.5)
        )
    }
}

/// Section listing values that carry no LOINC code and therefore can't be saved
/// to Apple Health. Shown at the bottom of the review screen so the user can
/// still see (and remove) what was parsed but won't be exported.
struct UnsupportedValuesSection: View {
    let values: [LabValue]
    let onDelete: (LabValue) -> Void

    var body: some View {
        Section {
            ForEach(values) { value in
                row(value)
                    .padding(.vertical, 2)
                    .listRowBackground(Rectangle().fill(.ultraThinMaterial))
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            onDelete(value)
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
            }
        } header: {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
                Text("Not supported for export")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
            }
            .textCase(nil)
        } footer: {
            Text("These values don't have a LOINC code and won't be saved to Apple Health.")
        }
    }

    private func row(_ value: LabValue) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "doc.badge.minus")
                .font(.body)
                .foregroundStyle(.tertiary)
                .frame(width: 22)

            VStack(alignment: .leading, spacing: 2) {
                Text(value.resolvedName).font(.body)
                Text(value.code)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .textCase(.uppercase)
            }
            Spacer()
            HStack(spacing: 6) {
                Text(value.displayValue)
                    .font(.body.monospacedDigit())
                    .foregroundStyle(.secondary)
                if !value.unit.isEmpty {
                    Text(value.unit)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
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
                .foregroundStyle(.secondary)
        }
        .textCase(nil)
    }
}
