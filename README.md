# LabImporter

A native iOS app that imports lab report values into Apple Health using on-device AI.

Scan, import, or paste your lab report — the app uses Vision OCR and the on-device Foundation Models framework to extract values, lets you review and correct them, then saves them directly into Apple Health as a CDA clinical document.

---

## Features

- **Import** by scanning multi-page documents with the camera, picking PDFs or images from Files, or pasting from the clipboard
- **On-device AI parsing** — Foundation Models + Vision OCR extract lab values without sending any data to a server
- **Review & correct** — edit values, change codes, add or remove entries before saving
- **Dashboard** — metric cards with current value, sparkline trend, and normal / borderline / elevated status
- **Trend charts** — interactive per-metric chart with finger-scrubbing to inspect individual values
- **History** — full list of imported reports with edit and delete
- **Customise** — pin metrics to the top, reorder, or hide them from the dashboard
- **Share** — export any report as a CDA file to send to a doctor or another app
- **Privacy-first** — all data lives in Apple Health on your device; no account or server required

---

## Screenshots

> Drop your captures into `docs/screenshots/` using the file names below and
> they will appear here automatically. Recommended device frame: iPhone 16 Pro
> (1206 × 2622), light mode. See [shot list](#what-to-capture) below.

| Import | Review & correct | Dashboard |
|:---:|:---:|:---:|
| ![Import screen](docs/screenshots/01-import.png) | ![Review parsed values](docs/screenshots/02-review.png) | ![Dashboard](docs/screenshots/03-dashboard.png) |
| Scan, choose a file, or paste a report | On-device AI extracts the values; edit before saving | Metric cards with sparklines and status |

| Trends | History | Settings |
|:---:|:---:|:---:|
| ![Trend chart](docs/screenshots/04-trends.png) | ![History list](docs/screenshots/05-history.png) | ![Settings](docs/screenshots/06-settings.png) |
| Interactive per-metric chart | All imported reports | Patient details & display preferences |

### What to capture

To produce a consistent set, import the [mock data](docs/mock-data/) (all four
reports, oldest first) so every screen has realistic, populated content.

1. **Import** (`ImportLandingView`) — the empty-state hero with the four entry
   points. This is the app's front door.
2. **Review** (`ReviewView`) — the parsed values mid-correction. Shows off the
   on-device AI step; the marquee feature.
3. **Dashboard** (`DashboardView`) — metric cards with sparklines and
   normal / borderline / elevated colours. Best single "what the app does" shot.
4. **Trends** (`TrendsView`) — a per-metric chart (e.g. HbA1c) trending down
   across the four reports, ideally with the scrubber active on a point.
5. **History** (`HistoryView`) — the list of all four imported reports.
6. **Settings** (`SettingsView`) — patient metadata + pin/reorder/hide
   preferences.

Optional extras worth a capture: the **first-launch onboarding**
(`WelcomeView`), a **report detail / share** sheet (`ReportDetailView`), and the
**document scanner** in action (`DocumentScannerView`).

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

This registers your app's bundle ID in App Store Connect and enables the **HealthKit** capability on it.

The bundle identifier is `dev.idoodler.<TEAMID>.labimporter` — the same pattern as Trio (`org.nightscout.<TEAMID>.trio`). The `<TEAMID>` placeholder is substituted automatically from the `TEAMID` secret at build time via `Config.xcconfig`.

After this step, go to [App Store Connect](https://appstoreconnect.apple.com) and create a new app record for **LabImporter** using the bundle ID shown in the workflow log. This is required before the first build can upload to TestFlight.

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

> **Note:** The on-device Foundation Models framework requires a physical device with Apple Intelligence support. On unsupported hardware the app shows a "Device Not Supported" screen, since lab value parsing depends entirely on the on-device model.

---

## How it works

| Step | Technology |
|---|---|
| Document input | `VisionKit` document scanner (multi-page), `UIDocumentPicker` for PDFs / images, or Clipboard |
| PDF rendering | `PDFKit` — extracts embedded text or renders pages for OCR |
| Text extraction | `Vision` — `VNRecognizeTextRequest` (German + English) |
| Lab value parsing | `FoundationModels` — `@Generable` structured output via `LanguageModelSession` |
| Health import | `HealthKit` — `HKCDADocumentSample` (CDA R2 clinical document) |

---

## Privacy

All processing happens entirely on-device. No lab data is sent to any server. The Foundation Models framework runs the language model locally without any network requests.

---

## AI Disclosure

### On-device AI inside the app

Lab value extraction is powered by Apple's on-device Foundation Models framework (`LanguageModelSession` / `@Generable`). The language model runs entirely on the device — no lab data is transmitted to any external server or API. An Apple Intelligence-capable device (A17 Pro / M1 chip or later, iOS 26+) is required for parsing; on unsupported hardware the app shows a "Device Not Supported" screen.

### Built with AI assistance

This app was designed and built with the assistance of [Claude Code](https://claude.ai/code) by Anthropic. AI-assisted development was used throughout: app architecture, Swift/SwiftUI implementation, HealthKit and CDA integration, on-device model prompting, and the dashboard, trends, and history features.

---

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
