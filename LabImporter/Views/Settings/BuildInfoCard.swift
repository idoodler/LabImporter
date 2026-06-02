import SwiftUI
import UIKit

// MARK: - Build / installation details

extension AppInfo {
    /// How this build reached the device. Drives the build-info card's badge and
    /// whether an expiry date can be shown. Everything here is derived on device —
    /// no network of our own — in keeping with the app's no-server design.
    enum InstallKind {
        case appStore
        case testFlight
        /// Development / ad-hoc / enterprise — a build that carries an embedded
        /// provisioning profile and therefore a hard expiry.
        case development
        case simulator
    }

    /// The app's primary icon, loaded from the asset catalog at runtime so the
    /// build-info card can show it. Prefers the generated icon-file names Xcode
    /// injects into `CFBundleIcons` in the built `Info.plist`, then the asset name.
    /// Returns `nil` in contexts where neither resolves (e.g. some previews).
    static var icon: UIImage? {
        if let icons = Bundle.main.infoDictionary?["CFBundleIcons"] as? [String: Any],
           let primary = icons["CFBundlePrimaryIcon"] as? [String: Any],
           let files = primary["CFBundleIconFiles"] as? [String],
           let last = files.last,
           let image = UIImage(named: last) {
            return image
        }
        return UIImage(named: "AppIcon")
    }

    /// Where this build came from. Order matters: a build carrying an embedded
    /// provisioning profile (development / ad-hoc) is reported as `.development`
    /// before the receipt is consulted.
    static var installKind: InstallKind {
        #if targetEnvironment(simulator)
        return .simulator
        #else
        if provisioningProfileExpiration() != nil {
            return .development
        }
        return hasSandboxReceipt ? .testFlight : .appStore
        #endif
    }

    /// The date this build stops working, when that is knowable on device:
    /// - development / ad-hoc builds expire with their embedded provisioning
    ///   profile (`ExpirationDate`);
    /// - TestFlight builds expire 90 days after they were built.
    /// App Store builds don't expire, so this is `nil` for them.
    static var expirationDate: Date? {
        switch installKind {
        case .development:
            return provisioningProfileExpiration()
        case .testFlight:
            return buildDate.flatMap { Calendar.current.date(byAdding: .day, value: 90, to: $0) }
        case .appStore, .simulator:
            return nil
        }
    }

    /// Whether the bundle carries a TestFlight / App Store *sandbox* receipt —
    /// the reliable, offline, prompt-free signal that this is a TestFlight build.
    ///
    /// `appStoreReceiptURL` was deprecated in iOS 18 in favour of StoreKit's
    /// `AppTransaction`, but that API isn't readable synchronously and proved
    /// unreliable here (it throws before the app transaction is cached and then
    /// falls back to App Store, so TestFlight mis-reads as App Store). We keep the
    /// receipt check and read it inside a deprecated-scoped helper so the warning
    /// stays contained rather than leaking into the build.
    private static var hasSandboxReceipt: Bool {
        @available(iOS, deprecated: 18.0, message: "appStoreReceiptURL is intentional: the reliable TestFlight signal.")
        func receiptName() -> String? {
            Bundle.main.appStoreReceiptURL?.lastPathComponent
        }
        return receiptName() == "sandboxReceipt"
    }

    /// Best-effort build timestamp: the modification date of the main executable,
    /// stamped when the binary was linked. Used to derive the TestFlight 90-day window.
    private static var buildDate: Date? {
        guard let url = Bundle.main.executableURL,
              let values = try? url.resourceValues(forKeys: [.contentModificationDateKey]) else {
            return nil
        }
        return values.contentModificationDate
    }

    /// Parses `ExpirationDate` out of the bundled `embedded.mobileprovision`, if any.
    /// The file is a CMS-signed blob with a plain-text XML plist inside; we slice the
    /// plist out by its delimiters (Latin-1 keeps byte offsets aligned) and decode it.
    /// App Store / TestFlight builds carry no embedded profile, so this returns `nil`.
    private static func provisioningProfileExpiration() -> Date? {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url),
              let raw = String(data: data, encoding: .isoLatin1),
              let start = raw.range(of: "<?xml"),
              let end = raw.range(of: "</plist>") else {
            return nil
        }
        let plistSlice = String(raw[start.lowerBound..<end.upperBound])
        guard let plistData = plistSlice.data(using: .isoLatin1),
              let plist = try? PropertyListSerialization.propertyList(from: plistData, format: nil) as? [String: Any] else {
            return nil
        }
        return plist["ExpirationDate"] as? Date
    }
}

// MARK: - BuildInfoCard

/// A summary card — app icon, name, version/build, where the build came from, and
/// when it expires — shown at the top of Settings. Mirrors the material-card look
/// of the review and history headers, and computes everything on device (no
/// network), matching the app's no-server design.
struct BuildInfoCard: View {
    private var displayName: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String
            ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleName") as? String
            ?? "LabImporter"
    }

    /// Whether this build was stamped with git provenance. Local Xcode builds
    /// leave these at "unknown", in which case the source strip is hidden.
    private var hasGitInfo: Bool {
        AppInfo.branch != "unknown" || AppInfo.commit != "unknown"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 14) {
                icon

                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: displayName)
                        .font(.headline)
                    Text("Version \(AppInfo.version) (\(AppInfo.build))")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    if let expiry = AppInfo.expirationDate {
                        Label {
                            Text("Expires \(expiry.formatted(date: .abbreviated, time: .omitted))")
                        } icon: {
                            Image(systemName: "calendar.badge.clock")
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }
                }

                Spacer(minLength: 8)

                buildTypeBadge
            }

            if hasGitInfo {
                Divider()
                    .padding(.vertical, 14)
                VStack(spacing: 8) {
                    detailRow("Branch", systemImage: "arrow.triangle.branch", value: AppInfo.branch)
                    detailRow("Commit", systemImage: "number", value: AppInfo.commit)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
    }

    /// A compact key/value line for the source strip — a glyph + label on the
    /// left, the monospaced value trailing.
    private func detailRow(_ title: LocalizedStringKey, systemImage: String, value: String) -> some View {
        HStack(spacing: 8) {
            Label {
                Text(title)
            } icon: {
                Image(systemName: systemImage)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            Spacer(minLength: 8)
            Text(value)
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
        }
    }

    /// The real app icon when it resolves, falling back to a tinted glyph tile so
    /// the card still reads well in previews or odd build configurations.
    @ViewBuilder private var icon: some View {
        if let image = AppInfo.icon {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
                )
                .shadow(color: .black.opacity(0.15), radius: 4, x: 0, y: 2)
        } else {
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .fill(Color.accentColor.gradient)
                .frame(width: 56, height: 56)
                .overlay(
                    Image(systemName: "cross.case.fill")
                        .font(.system(size: 24, weight: .semibold))
                        .foregroundStyle(.white)
                )
        }
    }

    @ViewBuilder private var buildTypeBadge: some View {
        switch AppInfo.installKind {
        case .appStore: badge("App Store", color: .blue)
        case .testFlight: badge("TestFlight", color: .indigo)
        case .development: badge("Development", color: .orange)
        case .simulator: badge("Simulator", color: .gray)
        }
    }

    private func badge(_ title: LocalizedStringKey, color: Color) -> some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(color.opacity(0.12), in: Capsule())
    }
}

// MARK: - Preview

#Preview("Build Info Card") {
    BuildInfoCard()
        .padding()
        .background(Color(.systemGroupedBackground))
}
