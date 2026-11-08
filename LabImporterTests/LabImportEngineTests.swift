import Testing
@testable import LabImporter

/// Holds an import open until the test lets it finish.
@MainActor
private final class Gate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var isOpen = false

    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { continuation = $0 }
    }

    func open() {
        isOpen = true
        continuation?.resume()
        continuation = nil
    }
}

/// What an import was given to report its OCR pages with, kept for the test to call late.
@MainActor
private final class ProgressHandler {
    var report: (@Sendable (Int, Int) -> Void)?
}

@MainActor
struct LabImportEngineTests {
    @Test func anOldImportFinishingLateDoesNotHideTheNewOnesHUD() async {
        let engine = LabImportEngine()
        let oldImport = Gate()
        let newImport = Gate()

        engine.startImport(phase: .analyzing) { _ in await oldImport.wait() }
        let old = engine.importTask
        await Task.yield()
        engine.cancelImport()
        #expect(!engine.isProcessing)

        engine.startImport(phase: .analyzing) { _ in await newImport.wait() }
        let new = engine.importTask
        #expect(engine.isProcessing)

        oldImport.open()
        await old?.value
        #expect(engine.isProcessing, "The cancelled import finished, and the new one is still running")

        newImport.open()
        await new?.value
        #expect(!engine.isProcessing)
    }

    @Test func aCancelledImportFinishingItsReadingLeavesTheNewHUDAlone() async {
        let engine = LabImportEngine()
        let oldImport = Gate()
        let newImport = Gate()
        let oldStarted = Gate()
        let oldProgress = ProgressHandler()

        engine.startImport(phase: .extractingText) { engine in
            oldProgress.report = engine.pageProgressHandler
            oldStarted.open()
            await oldImport.wait()
            try await engine.handleExtractedText("Creatinine 0.8 mg/dl")
        }
        let old = engine.importTask
        await oldStarted.wait()

        engine.startImport(phase: .extractingText) { _ in await newImport.wait() }
        let new = engine.importTask
        let newProgress = engine.pageProgressHandler

        // The new import's own progress arrives, and then the old one's last page does.
        newProgress(2, 3)
        while engine.ocrProgress == nil { await Task.yield() }
        oldProgress.report?(1, 9)
        for _ in 0..<20 { await Task.yield() }
        #expect(engine.ocrProgress == OCRPageProgress(page: 2, total: 3))

        oldImport.open()
        await old?.value
        #expect(engine.phase == .extractingText, "The new import is still reading")
        #expect(engine.isProcessing)

        newImport.open()
        await new?.value
    }

    @Test func aCancelledImportStaysDismissedWhenItFinishes() async {
        let engine = LabImportEngine()
        let gate = Gate()
        engine.startImport(phase: .analyzing) { _ in await gate.wait() }
        let task = engine.importTask
        engine.cancelImport()
        gate.open()
        await task?.value
        #expect(!engine.isProcessing)
    }
}
