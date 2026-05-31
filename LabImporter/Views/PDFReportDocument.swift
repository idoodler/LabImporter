import SwiftUI

// SwiftUI views used by `PDFExportService` to render each PDF page. They render
// in a forced light color scheme on a white page so the output is crisp and
// printer-friendly, while keeping the app's category colors and card styling.

// MARK: - Page

struct PDFPageView: View {
    let page: PDFPage
    let pageNumber: Int
    let pageCount: Int
    let generatedOn: String
    let size: CGSize
    let margin: CGFloat
    let footerHeight: CGFloat

    var body: some View {
        ZStack(alignment: .top) {
            Color.white
            PDFBackgroundWash(colors: washColors)
            content
            footer
        }
        .frame(width: size.width, height: size.height)
        .environment(\.colorScheme, .light)
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(page.blocks.enumerated()), id: \.offset) { _, block in
                PDFBlockView(block: block)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, margin)
        .padding(.top, margin)
        .padding(.bottom, footerHeight)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var footer: some View {
        VStack(spacing: 0) {
            Spacer()
            PDFFooterView(pageNumber: pageNumber, pageCount: pageCount, generatedOn: generatedOn)
                .frame(height: footerHeight)
                .padding(.horizontal, margin)
        }
    }

    private var washColors: [Color] {
        var seen = Set<Color>()
        var result: [Color] = []
        for block in page.blocks {
            for color in block.washColors where seen.insert(color).inserted {
                result.append(color)
            }
            if result.count >= 3 { break }
        }
        return result
    }
}

private struct PDFBlockView: View {
    let block: PDFBlock

    var body: some View {
        switch block {
        case .cover(let info):         PDFCoverView(info: info)
        case .reportHeader(let info):  PDFReportHeaderView(info: info)
        case .sectionHeader(let info): PDFSectionHeaderView(info: info)
        case .row(let info):           PDFValueRowView(info: info)
        }
    }
}

// MARK: - Cover (multi-report)

private struct PDFCoverView: View {
    let info: PDFCoverInfo

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            colorBar
            VStack(alignment: .leading, spacing: 8) {
                Text(info.title)
                    .font(.largeTitle.bold())
                Text(info.dateRange)
                    .font(.title3)
                    .foregroundStyle(.secondary)
                if !info.patient.isEmpty {
                    Label(info.patient, systemImage: "person.fill")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 12)
                statRow
            }
            .padding(20)
        }
        .frame(height: PDFMetrics.coverHeight, alignment: .top)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(white: 0.98), in: RoundedRectangle(cornerRadius: 20))
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(Color.primary.opacity(0.08), lineWidth: 0.5)
        )
    }

    private var colorBar: some View {
        LinearGradient(colors: barColors, startPoint: .leading, endPoint: .trailing)
            .frame(height: 8)
            .clipShape(.rect(topLeadingRadius: 20, topTrailingRadius: 20))
    }

    private var barColors: [Color] {
        guard let first = info.colors.first else { return [.accentColor, .accentColor.opacity(0.6)] }
        return info.colors.count == 1 ? [first, first.opacity(0.6)] : info.colors
    }

    private var statRow: some View {
        HStack(spacing: 0) {
            stat("\(info.reportCount)", String(localized: "Reports"))
            statDivider
            stat("\(info.valueCount)", String(localized: "Lab Values"))
            statDivider
            stat("\(info.categoryCount)", String(localized: "Categories"))
        }
        .frame(maxWidth: .infinity)
    }

    private var statDivider: some View {
        Rectangle().fill(Color.primary.opacity(0.1)).frame(width: 0.5, height: 32)
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(spacing: 3) {
            Text(value).font(.title2.bold().monospacedDigit())
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Report header band

private struct PDFReportHeaderView: View {
    let info: PDFReportHeaderInfo

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                icon
                VStack(alignment: .leading, spacing: 2) {
                    Text(info.dateText)
                        .font(.title3.bold())
                        .foregroundStyle(.white)
                    if !meta.isEmpty {
                        Text(meta)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.85))
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                Text("\(info.valueCount) values")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.9))
            }
            if !info.chips.isEmpty { chipRow }
        }
        .padding(16)
        .frame(height: info.height, alignment: .top)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(gradient, in: RoundedRectangle(cornerRadius: 18))
    }

    private var icon: some View {
        ZStack {
            Circle().fill(.white.opacity(0.22))
            Image(systemName: "doc.text.fill")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.white)
        }
        .frame(width: 42, height: 42)
    }

    private var chipRow: some View {
        HStack(spacing: 6) {
            ForEach(Array(info.chips.prefix(6).enumerated()), id: \.offset) { _, chip in
                HStack(spacing: 4) {
                    Circle().fill(chip.color).frame(width: 6, height: 6)
                    Text(chip.name).font(.caption2.weight(.medium))
                    Text("\(chip.count)").font(.caption2.monospacedDigit()).opacity(0.7)
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(.white.opacity(0.18), in: Capsule())
            }
        }
        .lineLimit(1)
    }

    private var meta: String {
        [info.patient, info.author].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private var gradient: LinearGradient {
        LinearGradient(
            colors: [info.dominant, info.dominant.opacity(0.7)],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }
}

// MARK: - Section header

private struct PDFSectionHeaderView: View {
    let info: PDFSectionInfo

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(info.category.color).frame(width: 9, height: 9)
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
            Spacer()
            Text("\(info.count)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .padding(.top, 14)
        .frame(height: PDFMetrics.sectionHeaderHeight, alignment: .bottom)
        .overlay(alignment: .bottom) {
            Rectangle().fill(info.category.color.opacity(0.25)).frame(height: 1)
        }
    }

    private var title: String {
        info.continued
            ? String(localized: "\(info.category.displayName) (continued)")
            : info.category.displayName
    }
}

// MARK: - Value row

private struct PDFValueRowView: View {
    let info: PDFRowInfo

    var body: some View {
        HStack(spacing: 10) {
            Circle().fill(info.color).frame(width: 6, height: 6)
            VStack(alignment: .leading, spacing: 1) {
                Text(info.name)
                    .font(.footnote)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(info.code)
                    .font(.system(size: 8))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
                    .lineLimit(1)
            }
            Spacer(minLength: 12)
            Text(info.value)
                .font(.footnote.monospacedDigit().weight(.medium))
                .foregroundStyle(.primary)
                .lineLimit(1)
        }
        .frame(height: PDFMetrics.rowHeight)
        .overlay(alignment: .bottom) {
            Rectangle().fill(Color.primary.opacity(0.08)).frame(height: 0.5)
        }
    }
}

// MARK: - Footer

private struct PDFFooterView: View {
    let pageNumber: Int
    let pageCount: Int
    let generatedOn: String

    var body: some View {
        VStack(spacing: 4) {
            Rectangle().fill(Color.primary.opacity(0.12)).frame(height: 0.5)
            HStack {
                Label { Text(verbatim: "LabImporter") } icon: {
                    Image(systemName: "cross.case.fill")
                }
                .font(.system(size: 8, weight: .semibold))
                .foregroundStyle(.secondary)
                Spacer()
                (Text("Page \(pageNumber) of \(pageCount)") + Text(verbatim: " · \(generatedOn)"))
                    .font(.system(size: 8))
                    .foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Background wash

private struct PDFBackgroundWash: View {
    let colors: [Color]

    private let anchors: [UnitPoint] = [.topLeading, .topTrailing, .bottomTrailing]

    var body: some View {
        ZStack {
            ForEach(Array(palette.enumerated()), id: \.offset) { index, color in
                RadialGradient(
                    colors: [color.opacity(0.10), .clear],
                    center: anchors[index % anchors.count],
                    startRadius: 0,
                    endRadius: 420
                )
            }
        }
        .ignoresSafeArea()
    }

    private var palette: [Color] {
        colors.isEmpty ? [Color.accentColor.opacity(0.4)] : colors
    }
}
