import SwiftUI
import PhotosUI

struct ImportLandingView: View {
    @Binding var photosPickerItem: PhotosPickerItem?
    let onCamera: () -> Void
    let onPaste: () -> Void
    let onManual: () -> Void
    let clipboardAvailable: Bool
    let isProcessing: Bool

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            heroSection
            Spacer()
            importCard
                .padding(.horizontal, 24)
                .opacity(isProcessing ? 0.4 : 1)
                .allowsHitTesting(!isProcessing)
            Spacer()
                .frame(height: 56)
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Hero

    private var heroSection: some View {
        VStack(spacing: 20) {
            ZStack {
                Circle()
                    .fill(Color.primary.opacity(0.08))
                    .frame(width: 110, height: 110)
                    .shadow(color: .black.opacity(0.15), radius: 24, y: 8)
                Image(systemName: "waveform.path.ecg.rectangle.fill")
                    .font(.system(size: 52))
                    .foregroundStyle(.primary)
            }

            VStack(spacing: 8) {
                Text("Lab Importer")
                    .font(.largeTitle.bold())
                    .foregroundStyle(.primary)
                Text("Photograph your lab report and\nsave it directly to Apple Health.")
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Import Card

    private var importCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("IMPORT REPORT")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .kerning(1)

            PhotosPicker(selection: $photosPickerItem, matching: .images) {
                Label("Choose from Photos", systemImage: "photo.on.rectangle")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)

            Button(action: onCamera) {
                Label("Take a Photo", systemImage: "camera")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .disabled(!UIImagePickerController.isSourceTypeAvailable(.camera))

            Button(action: onPaste) {
                Label("Paste from Clipboard", systemImage: "doc.on.clipboard")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .disabled(!clipboardAvailable)

            Divider()
                .padding(.vertical, 2)

            Button(action: onManual) {
                Label("Create Report Manually", systemImage: "square.and.pencil")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
        }
        .padding(24)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28))
        .overlay(
            RoundedRectangle(cornerRadius: 28)
                .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
    }
}
