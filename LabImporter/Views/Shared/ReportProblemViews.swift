import SwiftUI

/// What the last read of Apple Health couldn't show, so that it never looks like
/// there being nothing to show.
struct ReportLoadStatus: Equatable {
    /// The read failed: what's on screen may be out of date, and none doesn't mean there are none.
    var failed = false
    /// Reports only a newer version of the app can read.
    var newerVersion = 0
    /// Reports of this app that can't be read.
    var unreadable = 0

    var hasNotice: Bool { failed || newerVersion > 0 || unreadable > 0 }
}

/// The problems as cards, above whatever could be read. Empty when there are none.
struct ReportStatusCards: View {
    let status: ReportLoadStatus
    let onTryAgain: () -> Void

    var body: some View {
        if status.hasNotice {
            VStack(spacing: 12) {
                if status.failed {
                    ReportProblemCard(
                        title: Text("Couldn't Load Your Reports"),
                        message: Text("Your saved reports weren't changed, but they can't be read right now."),
                        tint: .statusCritical
                    ) {
                        Button("Try Again", action: onTryAgain)
                            .buttonStyle(.bordered)
                    }
                }
                if status.newerVersion > 0 {
                    ReportProblemCard(
                        title: Text("Update LabImporter to See All Reports"),
                        message: Text("Reports saved by a newer version of LabImporter: \(status.newerVersion). They're safe. Update the app to see them."),
                        tint: .statusWarning
                    ) { EmptyView() }
                }
                if status.unreadable > 0 {
                    ReportProblemCard(
                        title: Text("Some Reports Can't Be Read"),
                        message: Text("Reports that can't be read: \(status.unreadable). They stay in Apple Health and weren't deleted."),
                        tint: .statusWarning
                    ) { EmptyView() }
                }
            }
        }
    }
}

private struct ReportProblemCard<Actions: View>: View {
    let title: Text
    let message: Text
    let tint: Color
    @ViewBuilder let actions: Actions

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.title2)
                .foregroundStyle(tint)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 6) {
                title
                    .font(.subheadline.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                message
                    .font(.subheadline)
                    .foregroundStyle(.textSecondary)
                actions
                    .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .cardSurface(cornerRadius: 22)
    }
}

/// Reading failed and there's nothing to show: says so in place of the empty state,
/// whose "import your first report" would suggest the reports are gone.
struct ReportsLoadFailedView: View {
    let onTryAgain: () -> Void

    var body: some View {
        EmptyStateView(
            title: "Couldn't Load Your Reports",
            systemImage: "exclamationmark.triangle",
            message: "Your saved reports weren't changed, but they can't be read right now."
        ) {
            Button("Try Again", action: onTryAgain)
                .buttonStyle(.borderedProminent)
                .tint(.prominentFill)
        }
    }
}

/// `ReportsLoadFailedView` as the whole home screen, which still has to reach Settings.
struct HomeLoadFailedView: View {
    let showsSettingsButton: Bool
    let onTryAgain: () -> Void
    let onShowSettings: () -> Void

    var body: some View {
        ReportsLoadFailedView(onTryAgain: onTryAgain)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background { MorphingCategoryBackground() }
            .toolbar {
                if showsSettingsButton {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(action: onShowSettings) {
                            Image(systemName: "gearshape")
                        }
                        .accessibilityLabel("Settings")
                    }
                }
            }
    }
}

#Preview("Load failed") {
    ReportStatusCards(status: ReportLoadStatus(failed: true), onTryAgain: {})
        .padding()
}

#Preview("Load failed · Dark") {
    ReportStatusCards(status: ReportLoadStatus(failed: true), onTryAgain: {})
        .padding()
        .preferredColorScheme(.dark)
}

#Preview("Newer and unreadable") {
    ReportStatusCards(status: ReportLoadStatus(newerVersion: 2, unreadable: 1), onTryAgain: {})
        .padding()
}

#Preview("Newer · Dark, Large Text") {
    ReportStatusCards(status: ReportLoadStatus(newerVersion: 1), onTryAgain: {})
        .padding()
        .preferredColorScheme(.dark)
        .dynamicTypeSize(.accessibility2)
}

#Preview("Everything · RTL") {
    ReportStatusCards(status: ReportLoadStatus(failed: true, newerVersion: 3, unreadable: 2), onTryAgain: {})
        .padding()
        .environment(\.layoutDirection, .rightToLeft)
}

#Preview("Nothing to say") {
    ReportStatusCards(status: ReportLoadStatus(), onTryAgain: {})
}

#Preview("Full screen") {
    ReportsLoadFailedView(onTryAgain: {})
}

#Preview("Full screen · Dark") {
    ReportsLoadFailedView(onTryAgain: {})
        .preferredColorScheme(.dark)
}

#Preview("Full screen · Landscape", traits: .landscapeLeft) {
    ReportsLoadFailedView(onTryAgain: {})
}

#Preview("Home") {
    NavigationStack {
        HomeLoadFailedView(showsSettingsButton: true, onTryAgain: {}, onShowSettings: {})
    }
}

#Preview("Home · Sidebar, Dark", traits: .fixedLayout(width: 951, height: 669)) {
    NavigationStack {
        HomeLoadFailedView(showsSettingsButton: false, onTryAgain: {}, onShowSettings: {})
    }
    .preferredColorScheme(.dark)
}

#Preview("History") {
    NavigationStack {
        HistoryView(initialStatus: ReportLoadStatus(failed: true))
    }
}

#Preview("History · Dark") {
    NavigationStack {
        HistoryView(initialStatus: ReportLoadStatus(failed: true))
    }
    .preferredColorScheme(.dark)
}

#Preview("History · Update Needed, None Readable") {
    NavigationStack {
        HistoryView(initialStatus: ReportLoadStatus(newerVersion: 2))
    }
}

#if DEBUG
#Preview("History · Load Failed Above Reports") {
    NavigationStack {
        HistoryView(initialReports: LabReport.sampleHistory, initialStatus: ReportLoadStatus(failed: true))
    }
}

#Preview("History · Update Needed, Dark") {
    NavigationStack {
        HistoryView(initialReports: LabReport.sampleHistory, initialStatus: ReportLoadStatus(newerVersion: 1, unreadable: 1))
    }
    .preferredColorScheme(.dark)
}

#Preview("Dashboard · Load Failed Above Reports") {
    NavigationStack {
        DashboardView(
            reports: LabReport.sampleHistory,
            onScan: {}, onPickFile: {}, onPaste: {}, onManual: {},
            scannerAvailable: true, clipboardAvailable: true, isProcessing: false,
            status: ReportLoadStatus(failed: true)
        )
    }
}

#Preview("Dashboard · Update Needed, Dark") {
    NavigationStack {
        DashboardView(
            reports: LabReport.sampleHistory,
            onScan: {}, onPickFile: {}, onPaste: {}, onManual: {},
            scannerAvailable: true, clipboardAvailable: true, isProcessing: false,
            status: ReportLoadStatus(newerVersion: 2, unreadable: 1)
        )
    }
    .preferredColorScheme(.dark)
}
#endif
