# LabImporter

A native iOS app that imports lab report values into Apple Health using on-device AI.

Photograph or screenshot your lab report — the app uses Vision OCR and the on-device Foundation Models framework to extract values, lets you review and correct them, then imports supported values directly into Apple Health.

---

## Requirements

- iOS 26.0 or later
- iPhone with Apple Intelligence support (A17 Pro / M1 or later)
- An Apple Developer account (free tier is sufficient for personal use via Xcode)
- For GitHub Actions builds: an Apple Developer Program membership (paid, required for TestFlight)

---

## Building with GitHub Actions (no Mac required)

This repository uses the same Fastlane + Fastlane Match infrastructure as [Trio](https://github.com/idoodler-s-Diabetes-Management/Trio). If you have already configured secrets for Trio in your organisation, they are reused here without any changes.

### Required secrets

Add these to your repository (or organisation) under **Settings → Secrets and variables → Actions**:

| Secret | Description |
|---|---|
| `GH_PAT` | GitHub Personal Access Token with **repo** and **workflow** scopes — used to access the `Match-Secrets` repository |
| `TEAMID` | Your 10-character Apple Developer Team ID (found at [developer.apple.com/account](https://developer.apple.com/account)) |
| `FASTLANE_ISSUER_ID` | App Store Connect API key Issuer ID (UUID) |
| `FASTLANE_KEY_ID` | App Store Connect API key ID (10 characters) |
| `FASTLANE_KEY` | App Store Connect API key contents (the `.p8` file, paste the full text including header/footer) |
| `MATCH_PASSWORD` | A strong password used to encrypt certificates in the `Match-Secrets` repository |

> All six secrets are shared with Trio — if they are already set at the organisation level you have nothing extra to do.

### Optional repository variables

Set these under **Settings → Secrets and variables → Actions → Variables**:

| Variable | Value | Effect |
|---|---|---|
| `ENABLE_NUKE_CERTS` | `true` | Allow automatic certificate renewal when the Distribution cert expires |
| `FORCE_NUKE_CERTS` | `true` | Immediately revoke and recreate all Distribution certificates |

### First-time setup (run once, in order)

#### 1. Validate Secrets

Go to **Actions → 1. Validate Secrets → Run workflow**.

This checks that all secrets are valid, that your App Store Connect API key works, and creates the private `Match-Secrets` repository in your GitHub account if it does not already exist.

#### 2. Add Identifiers

Go to **Actions → 2. Add Identifiers → Run workflow**.

This registers the `de.idoodler.LabImporter` App ID in App Store Connect and enables the **HealthKit** capability on it.

After this step, go to [App Store Connect](https://appstoreconnect.apple.com) and create a new app record for **LabImporter** using the `de.idoodler.LabImporter` bundle ID. This is required before the first build can upload to TestFlight.

#### 3. Create Certificates

Go to **Actions → 3. Create Certificates → Run workflow**.

This generates the Distribution certificate and provisioning profile and stores them encrypted in the `Match-Secrets` repository.

#### 4. Build LabImporter

Go to **Actions → 4. Build LabImporter → Run workflow**.

This runs on a GitHub-hosted `macos-26` runner with Xcode 26.2, builds and archives the app, increments the build number automatically from the latest TestFlight build, and uploads the IPA to TestFlight.

After the first manual run succeeds, builds are triggered automatically on the **first Sunday of each month**.

---

## Building locally with Xcode

1. Clone the repository:
   ```
   git clone https://github.com/idoodler-s-Diabetes-Management/LabImporter.git
   ```

2. Open `LabImporter.xcodeproj` in Xcode 17 or later (requires the iOS 26 SDK).

3. Select your development team in the project settings under **Signing & Capabilities**.

4. Build and run on a connected device or simulator (iOS 26+).

> **Note:** The on-device Foundation Models framework requires a physical device with Apple Intelligence support. The OCR and review UI work in the simulator, but AI-powered parsing falls back to the regex parser when the model is unavailable.

---

## How it works

| Step | Technology |
|---|---|
| Image input | `PhotosUI` PhotosPicker or Camera |
| Text extraction | `Vision` — `VNRecognizeTextRequest` (German + English) |
| Lab value parsing | `FoundationModels` — `@Generable` structured output via `LanguageModelSession` |
| Parsing fallback | Swift regex, splits on `;` separators common in German lab reports |
| Health import | `HealthKit` — `HKQuantitySample` with user-entered metadata |

### Extending HealthKit support

Apple Health's set of writable clinical quantity types is currently limited. Blood glucose (`HKQuantityTypeIdentifier.bloodGlucose`) is the only lab type wired up today. To add more as Apple expands the API, open `LabImporter/Models/LabMapping.swift` and add cases to `healthKitMapping(for:)`.

---

## Privacy

All processing happens entirely on-device. No lab data is sent to any server. The Foundation Models framework runs the language model locally without any network requests.

---

## License

MIT
