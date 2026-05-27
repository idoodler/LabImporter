import SwiftUI
import PhotosUI

struct ImportLandingView: View {
    @Binding var photosPickerItem: PhotosPickerItem?
    let onCamera: () -> Void
    let onPaste: () -> Void
    let clipboardAvailable: Bool

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            heroSection
            Spacer()
            importCard
                .padding(.horizontal, 24)
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
                    .fill(.white.opacity(0.12))
                    .frame(width: 110, height: 110)
                    .shadow(color: .black.opacity(0.25), radius: 24, y: 8)
                Image(systemName: "waveform.path.ecg.rectangle.fill")
                    .font(.system(size: 52))
                    .foregroundStyle(.white)
            }

            VStack(spacing: 8) {
                Text("Lab Importer")
                    .font(.largeTitle.bold())
                    .foregroundStyle(.white)
                Text("Photograph your lab report and\nsave it directly to Apple Health.")
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white.opacity(0.75))
            }
        }
    }

    // MARK: - Import Card

    private var importCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("IMPORT REPORT")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white.opacity(0.55))
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
            .tint(.white)
            .disabled(!UIImagePickerController.isSourceTypeAvailable(.camera))

            Button(action: onPaste) {
                Label("Paste from Clipboard", systemImage: "doc.on.clipboard")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .tint(.white)
            .disabled(!clipboardAvailable)
        }
        .padding(24)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28))
        .overlay(
            RoundedRectangle(cornerRadius: 28)
                .stroke(.white.opacity(0.18), lineWidth: 0.5)
        )
    }
}
