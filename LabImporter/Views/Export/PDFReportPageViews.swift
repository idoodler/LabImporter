import SwiftUI

// The cover/summary page and the latest-results table page of the export. Trend
// pages live in `PDFTrendPageViews.swift`; shared chrome in `PDFReportComposer.swift`.

// MARK: - Cover page

struct PDFCoverPage: View {
    let data: PDFReportData
    let theme: PDFTheme
    let pageSize: CGSize
    let pageNumber: Int
    let totalPages: Int

    var body: some View {
        PDFPageScaffold(theme: theme, pageSize: pageSize, pageNumber: pageNumber, totalPages: totalPages) {
            VStack(alignment: .leading, spacing: 22) {
                banner
                patientCard
                statsRow
                if !data.categories.isEmpty { legend }
                Spacer(minLength: 0)
                disclaimer
            }
        }
    }

    // In colour mode the banner is a filled gradient (built from the report's
    // dominant category colours) with white text. In monochrome the fill is
    // dropped to save toner — dark text with a baseline rule instead.
    private var bannerForeground: Color { theme.isColor ? .white : theme.textPrimary }
    private var bannerSecondary: Color { theme.isColor ? .white.opacity(0.85) : theme.textSecondary }

    private var banner: some View {
        HStack(spacing: 16) {
            ZStack {
                if theme.isColor { Circle().fill(Color.white.opacity(0.22)) }
                Image(systemName: "heart.text.square.fill")
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(bannerForeground)
            }
            .frame(width: 64, height: 64)

            VStack(alignment: .leading, spacing: 6) {
                Text("Lab Report")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(bannerForeground)
                Text("Generated \(data.generatedAt.formatted(date: .long, time: .shortened))")
                    .font(.system(size: 12))
                    .foregroundStyle(bannerSecondary)
            }
            Spacer(minLength: 0)
        }
        .padding(theme.isColor
                 ? EdgeInsets(top: 24, leading: 24, bottom: 24, trailing: 24)
                 : EdgeInsets(top: 4, leading: 0, bottom: 16, trailing: 0))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            if theme.isColor {
                RoundedRectangle(cornerRadius: 20)
                    .fill(LinearGradient(colors: theme.headerGradient(from: data.dominantCategories),
                                         startPoint: .topLeading, endPoint: .bottomTrailing))
            }
        }
        .overlay(alignment: .bottom) {
            if !theme.isColor { Rectangle().fill(theme.hairline).frame(height: 1) }
        }
    }

    private var patientCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(metaItems, id: \.label) { item in
                HStack(spacing: 10) {
                    Image(systemName: item.icon)
                        .font(.system(size: 12))
                        .foregroundStyle(theme.textTertiary)
                        .frame(width: 18)
                    Text(item.label)
                        .font(.system(size: 12))
                        .foregroundStyle(theme.textSecondary)
                    Spacer()
                    Text(item.value)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(theme.textPrimary)
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.cardBackground, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(theme.cardStroke, lineWidth: 0.5))
    }

    private var statsRow: some View {
        HStack(spacing: 12) {
            statTile(value: "\(data.reportCount)", label: String(localized: "Reports"), emphasised: false)
            statTile(value: "\(data.metrics.count)", label: String(localized: "Metrics"), emphasised: false)
            statTile(value: "\(data.outOfRangeCount)",
                     label: String(localized: "Out of Range"),
                     emphasised: data.outOfRangeCount > 0)
            statTile(value: data.timeRange.shortLabel, label: String(localized: "Trend Range"), emphasised: false)
        }
    }

    private func statTile(value: String, label: String, emphasised: Bool) -> some View {
        VStack(spacing: 6) {
            Text(value)
                .font(.system(size: 24, weight: .bold).monospacedDigit())
                .foregroundStyle(emphasised ? theme.valueColor(for: .high) : theme.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label)
                .font(.system(size: 10))
                .foregroundStyle(theme.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        .background(theme.cardBackground, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(theme.cardStroke, lineWidth: 0.5))
    }

    private var legend: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Included Categories")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(theme.textSecondary)
            FlowLayout(spacing: 8, lineSpacing: 8) {
                ForEach(data.categories, id: \.self) { category in
                    HStack(spacing: 6) {
                        Circle().fill(theme.color(for: category)).frame(width: 8, height: 8)
                        Text(category.displayName)
                            .font(.system(size: 11))
                            .foregroundStyle(theme.textPrimary)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(theme.color(for: category).opacity(theme.isColor ? 0.1 : 0),
                                in: Capsule())
                    .overlay(Capsule().stroke(theme.cardStroke, lineWidth: 0.5))
                }
            }
        }
    }

    private var disclaimer: some View {
        // swiftlint:disable:next line_length
        Text("This report was generated by LabImporter from data stored in Apple Health. It is for personal reference only — it is not a medical document and is not a substitute for professional medical advice.")
            .font(.system(size: 9))
            .foregroundStyle(theme.textTertiary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private struct MetaItem { let icon: String; let label: String; let value: String }

    private var metaItems: [MetaItem] {
        var items: [MetaItem] = []
        if !data.patientName.isEmpty {
            items.append(MetaItem(icon: "person.fill", label: String(localized: "Patient"), value: data.patientName))
        }
        if let dob = data.dateOfBirth {
            items.append(MetaItem(icon: "calendar", label: String(localized: "Date of Birth"), value: dobValue(dob)))
        }
        if let sex = sexLabel {
            items.append(MetaItem(icon: "figure.stand", label: String(localized: "Sex"), value: sex))
        }
        if !data.authorName.isEmpty {
            items.append(MetaItem(icon: "cross.case.fill", label: String(localized: "Author"), value: data.authorName))
        }
        if let range = dateRangeValue {
            items.append(MetaItem(icon: "clock.arrow.circlepath", label: String(localized: "Period Covered"), value: range))
        }
        return items
    }

    private func dobValue(_ dob: Date) -> String {
        let dateString = dob.formatted(date: .long, time: .omitted)
        if let years = Calendar.current.dateComponents([.year], from: dob, to: data.generatedAt).year, years >= 0 {
            return "\(dateString) (\(String(localized: "Age \(years)")))"
        }
        return dateString
    }

    private var sexLabel: String? {
        switch data.biologicalSexRaw {
        case 1: return String(localized: "Female")
        case 2: return String(localized: "Male")
        case 3: return String(localized: "Other")
        default: return nil
        }
    }

    private var dateRangeValue: String? {
        guard let earliest = data.earliestDate, let latest = data.latestDate else { return nil }
        let fromText = earliest.formatted(date: .abbreviated, time: .omitted)
        let toText = latest.formatted(date: .abbreviated, time: .omitted)
        return fromText == toText ? fromText : "\(fromText) – \(toText)"
    }
}

// MARK: - Latest results page

struct PDFLatestResultsPage: View {
    let rows: [LatestRow]
    let showTitle: Bool
    let patientName: String
    let theme: PDFTheme
    let pageSize: CGSize
    let pageNumber: Int
    let totalPages: Int

    var body: some View {
        PDFPageScaffold(theme: theme, pageSize: pageSize, pageNumber: pageNumber, totalPages: totalPages,
                        runningTitle: String(localized: "Lab Report"), patientName: patientName) {
            VStack(alignment: .leading, spacing: 14) {
                if showTitle {
                    PDFSectionHeader(title: String(localized: "Latest Results"),
                                     subtitle: String(localized: "Most recent reading for each value"),
                                     theme: theme)
                }
                tableHeader
                VStack(spacing: 0) {
                    ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                        rowView(row, zebra: index.isMultiple(of: 2))
                    }
                }
            }
        }
    }

    private var tableHeader: some View {
        HStack(spacing: 8) {
            Text("Test").frame(maxWidth: .infinity, alignment: .leading)
            Text("Result").frame(width: 96, alignment: .trailing)
            Text("Reference").frame(width: 92, alignment: .trailing)
            Text("Date").frame(width: 66, alignment: .trailing)
        }
        .font(.system(size: 9, weight: .semibold))
        .foregroundStyle(theme.textTertiary)
        .textCase(.uppercase)
        .padding(.horizontal, 8)
        .padding(.bottom, 4)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 0.5) }
    }

    @ViewBuilder
    private func rowView(_ row: LatestRow, zebra: Bool) -> some View {
        switch row {
        case let .category(category, count):
            categoryRow(category, count: count)
        case let .metric(metric):
            metricRow(metric, zebra: zebra)
        }
    }

    private func categoryRow(_ category: LabCategory, count: Int) -> some View {
        HStack(spacing: 8) {
            Circle().fill(theme.color(for: category)).frame(width: 9, height: 9)
            Text(category.displayName)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(theme.textPrimary)
            Spacer()
            Text("\(count)")
                .font(.system(size: 10).monospacedDigit())
                .foregroundStyle(theme.textTertiary)
        }
        .padding(.horizontal, 8)
        .padding(.top, 12)
        .padding(.bottom, 5)
    }

    private func metricRow(_ metric: PDFMetric, zebra: Bool) -> some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 1) {
                Text(metric.name)
                    .font(.system(size: 11))
                    .foregroundStyle(theme.textPrimary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                Text(metric.code)
                    .font(.system(size: 8))
                    .foregroundStyle(theme.textTertiary)
                    .textCase(.uppercase)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 4) {
                if let status = metric.latestStatus, status.isOutOfRange {
                    PDFStatusBadge(status: status, theme: theme)
                }
                Text(resultText(metric))
                    .font(.system(size: 11, weight: .semibold).monospacedDigit())
                    .foregroundStyle(theme.valueColor(for: metric.latestStatus))
                    .lineLimit(1)
            }
            .frame(width: 96, alignment: .trailing)

            Text(metric.referenceRange?.formatted() ?? "—")
                .font(.system(size: 9).monospacedDigit())
                .foregroundStyle(theme.textSecondary)
                .frame(width: 92, alignment: .trailing)
                .lineLimit(1)

            Text(metric.latest?.date.formatted(.dateTime.year(.twoDigits).month(.defaultDigits).day()) ?? "—")
                .font(.system(size: 9).monospacedDigit())
                .foregroundStyle(theme.textSecondary)
                .frame(width: 66, alignment: .trailing)
                .lineLimit(1)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(zebra ? theme.rowAlternate : Color.clear)
    }

    private func resultText(_ metric: PDFMetric) -> String {
        guard let value = metric.latest?.value else { return "—" }
        let number = PDFFormat.value(value)
        return metric.unit.isEmpty ? number : "\(number) \(metric.unit)"
    }
}
