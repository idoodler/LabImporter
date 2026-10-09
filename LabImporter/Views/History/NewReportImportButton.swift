import SwiftUI
import VisionKit

/// A self-contained "Import Report" menu for `HistoryView`'s empty state —
/// the same four entry points as `ImportLandingView`'s hero card (scan,
/// file, paste, manual entry), condensed into one menu since this is a
/// secondary action inside an empty state rather than a dedicated landing
/// screen. Owns its own `LabImportEngine` and review sheet so it's a true
/// drop-in, mirroring why `ReviewView` also owns its own engine rather than
/// sharing one: each screen that can originate an import needs its own.
struct NewReportImportButton: View {
    /// Called after a successful save, so the empty state's host can reload
    /// its report list.
    let onSaved: () -> Void

    @State private var engine = LabImportEngine()
    @State private var labValues: [LabValue] = []
    @State private var reportDate: Date?
    @State private var patientName: String?
    @State private var authorName: String?
    @State private var showReview = false

    var body: some View {
        Menu {
            Button {
                engine.scan()
            } label: {
                Label("Scan Document", systemImage: "doc.viewfinder")
            }
            .disabled(!VNDocumentCameraViewController.isSupported)

            Button {
                engine.pickFile()
            } label: {
                Label("Choose File", systemImage: "folder")
            }

            Button {
                engine.paste()
            } label: {
                Label("Paste from Clipboard", systemImage: "doc.on.clipboard")
            }
            .disabled(!clipboardHasContent)

            Button {
                labValues = []
                reportDate = nil
                patientName = nil
                authorName = nil
                showReview = true
            } label: {
                Label("Create Report Manually", systemImage: "square.and.pencil")
            }
        } label: {
            Text("Import Report")
        }
        .buttonStyle(.borderedProminent)
        .labImport(engine: engine)
        .onAppear {
            engine.onParsed = { result in
                labValues = result.values
                reportDate = result.reportDate
                patientName = result.patientName
                authorName = result.authorName
                showReview = true
            }
        }
        .sheet(isPresented: $showReview) {
            NavigationStack {
                ReviewView(
                    labValues: labValues,
                    reportDate: reportDate ?? Date(),
                    extractedPatientName: patientName,
                    extractedAuthorName: authorName,
                    onSaved: onSaved
                )
            }
            .interactiveDismissDisabled()
        }
    }

    private var clipboardHasContent: Bool {
        let pasteboard = UIPasteboard.general
        return pasteboard.hasImages || pasteboard.hasStrings
    }
}

// MARK: - Preview

#Preview {
    ContentUnavailableView {
        Label("No Reports Yet", systemImage: "doc.text.magnifyingglass")
    } description: {
        Text("Import a lab report and save it to Apple Health to see it here.")
    } actions: {
        NewReportImportButton(onSaved: {})
    }
}
