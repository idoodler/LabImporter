<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/icon/AppIcon-dark.png">
    <img src="docs/icon/AppIcon-light.png" alt="LabImporter app icon" width="160">
  </picture>
</p>

<h1 align="center">LabImporter</h1>

<p align="center">Native iOS and Android apps that import lab report values into Apple Health / Health Connect using on-device AI.</p>

Scan, import, or paste your lab report — LabImporter reads the values with on-device OCR and an on-device language model, lets you review and correct them, then saves them to your device's health store: **Apple Health** on iPhone and iPad (as a CDA clinical document), **Health Connect** on Android (as FHIR laboratory results). Nothing is sent to a server, and no account is needed.

- **iPhone and iPad:** parsing runs on Apple Intelligence, so LabImporter needs an **iPhone 15 Pro, iPhone 16, or newer**, or an **iPad with Apple silicon (M1 or later)**, running **iOS or iPadOS 26.0 or later**.
- **Android:** runs on **Android 8.0 or later** and reads reports with **Gemini Nano** where the phone offers it to apps, or else with Google's open **Gemma** model, downloaded once. See [Requirements](#requirements).

> [!IMPORTANT]
> **Not medical advice.** LabImporter is not a medical device and does not provide medical advice, diagnosis, or treatment. Extracted values may be inaccurate or incomplete — always verify them against your original report and never make medical decisions based on this app. See the [Medical disclaimer](#medical-disclaimer) below.

---

## Screenshots

Every screenshot shows made-up sample data (patient "Max Mustermann", four reports over one year) — no real lab results. Expand a device to see its screens in light and dark mode.

<details>
<summary><b>iPhone</b> — iOS, SwiftUI with Liquid Glass</summary>

| | Light | Dark |
|---|---|---|
| **Dashboard** — metric cards with sparklines | <img src="screenshots/ios/01-dashboard-light.png" width="240" alt="Dashboard, light"> | <img src="screenshots/ios/01-dashboard-dark.png" width="240" alt="Dashboard, dark"> |
| **Trends** — interactive per-metric chart | <img src="screenshots/ios/02-trends-light.png" width="240" alt="Trends, light"> | <img src="screenshots/ios/02-trends-dark.png" width="240" alt="Trends, dark"> |
| **Review** — AI-extracted values before saving | <img src="screenshots/ios/03-review-light.png" width="240" alt="Review, light"> | <img src="screenshots/ios/03-review-dark.png" width="240" alt="Review, dark"> |
| **Reports** — full import history by year | <img src="screenshots/ios/04-reports-light.png" width="240" alt="Reports, light"> | <img src="screenshots/ios/04-reports-dark.png" width="240" alt="Reports, dark"> |
| **Settings** — sort, search, storage and transfer | <img src="screenshots/ios/05-settings-light.png" width="240" alt="Settings, light"> | <img src="screenshots/ios/05-settings-dark.png" width="240" alt="Settings, dark"> |
| **PDF Export** — pick sections, range and values | <img src="screenshots/ios/06-pdf-export-light.png" width="240" alt="PDF Export, light"> | <img src="screenshots/ios/06-pdf-export-dark.png" width="240" alt="PDF Export, dark"> |

</details>

<details>
<summary><b>iPhone Duo</b> — the foldable iPhone, unfolded</summary>

| | Light | Dark |
|---|---|---|
| **Dashboard** — metric cards with sparklines | <img src="screenshots/ios-duo/01-dashboard-light.png" width="280" alt="Dashboard, light"> | <img src="screenshots/ios-duo/01-dashboard-dark.png" width="280" alt="Dashboard, dark"> |
| **Trends** — the chart as a sheet over the dashboard | <img src="screenshots/ios-duo/02-trends-light.png" width="280" alt="Trends, light"> | <img src="screenshots/ios-duo/02-trends-dark.png" width="280" alt="Trends, dark"> |
| **Review** — AI-extracted values before saving | <img src="screenshots/ios-duo/03-review-light.png" width="280" alt="Review, light"> | <img src="screenshots/ios-duo/03-review-dark.png" width="280" alt="Review, dark"> |
| **Reports** — full import history by year | <img src="screenshots/ios-duo/04-reports-light.png" width="280" alt="Reports, light"> | <img src="screenshots/ios-duo/04-reports-dark.png" width="280" alt="Reports, dark"> |
| **Settings** — sort, search, storage and transfer | <img src="screenshots/ios-duo/05-settings-light.png" width="280" alt="Settings, light"> | <img src="screenshots/ios-duo/05-settings-dark.png" width="280" alt="Settings, dark"> |
| **PDF Export** — pick sections, range and values | <img src="screenshots/ios-duo/06-pdf-export-light.png" width="280" alt="PDF Export, light"> | <img src="screenshots/ios-duo/06-pdf-export-dark.png" width="280" alt="PDF Export, dark"> |

</details>

<details>
<summary><b>Android</b> — Jetpack Compose with Material 3 Expressive</summary>

| | Light | Dark |
|---|---|---|
| **Dashboard** — metric cards with sparklines | <img src="screenshots/android/01-dashboard-light.png" width="220" alt="Dashboard, light"> | <img src="screenshots/android/01-dashboard-dark.png" width="220" alt="Dashboard, dark"> |
| **Trends** — interactive per-metric chart | <img src="screenshots/android/02-trends-light.png" width="220" alt="Trends, light"> | <img src="screenshots/android/02-trends-dark.png" width="220" alt="Trends, dark"> |
| **Review** — values read by the on-device model (here the downloaded Gemma) before saving | <img src="screenshots/android/03-review-light.png" width="220" alt="Review, light"> | <img src="screenshots/android/03-review-dark.png" width="220" alt="Review, dark"> |
| **Reports** — full import history by year | <img src="screenshots/android/04-reports-light.png" width="220" alt="Reports, light"> | <img src="screenshots/android/04-reports-dark.png" width="220" alt="Reports, dark"> |
| **Settings** — patient details, sort, search and storage | <img src="screenshots/android/05-settings-light.png" width="220" alt="Settings, light"> | <img src="screenshots/android/05-settings-dark.png" width="220" alt="Settings, dark"> |
| **PDF Export** — pick sections, range and values | <img src="screenshots/android/06-pdf-export-light.png" width="220" alt="PDF Export, light"> | <img src="screenshots/android/06-pdf-export-dark.png" width="220" alt="PDF Export, dark"> |

</details>

<sub>Taken in the iOS Simulator (the app's debug screenshot mode, `--ss`) and the Android emulator, where the same sample reports were imported from [`screenshots/sample-reports.json`](screenshots/sample-reports.json) (Settings → Transfer → Import Reports). That file is a regular LabImporter transfer file, so it also works for trying the iPhone ⇄ Android transfer.</sub>

---

## Features

Both apps have every feature unless it is marked *iPhone* or *Android*.

- **Import** by scanning multi-page documents with the camera, picking PDFs or images, pasting text, or creating a report by hand — or send a report from another app: a PDF or image via "Open With" / Share, or the text of a report (e.g. selected in Mail or a patient portal) via the share sheet
- **On-device AI parsing** — the values are read on the device, never on a server: *iPhone* with Foundation Models (Apple Intelligence) and Vision OCR; *Android* with Gemini Nano or the downloaded Gemma model and ML Kit text recognition. Where no model can run, the app says so instead of guessing values
- **Gemma for phones without Gemini Nano** *(Android)* — on phones that don't offer Gemini Nano to apps (e.g. Galaxy S25), LabImporter offers a one-time download of Google's open Gemma 4 E2B model (2.6 GB, pinned and checked against its SHA-256). The download continues in the background when you leave the app, and a notification says when the model is ready
- **LOINC matching** — every value is matched to its LOINC code from the bundled catalog (about 18,500 lab terms, named in every app language), searched in the report's own language and ranked by how well the printed label names the test and whether the unit fits; a report date is kept only when the report actually prints it
- **Review & correct** — edit values, change codes (search or browse the LOINC catalog), add or remove entries before saving
- **Dashboard** — metric cards with current value, sparkline, and a trend-direction indicator (rising / falling / steady)
- **Trend charts** — interactive per-metric chart with finger-scrubbing to inspect individual values
- **History** — full list of imported reports with edit and delete, one at a time or several at once
- **Customise** — Sort & Visibility pins metrics to the top, reorders or hides them; give any metric your own name and reference range
- **Health Connect** *(Android)* — reports are saved as FHIR R4 laboratory results in Health Connect's medical records (Android 16 and later). On older Android versions they are kept privately in the app; after the update to Android 16 the dashboard offers to move them into Health Connect
- **System search** — find any tracked metric from system search and jump straight to its trend: *iPhone* through Spotlight, *Android* through launcher shortcuts (also shown in the launcher's search). Results show only metric names by default, with an opt-in (Settings → Search) to display the latest reading
- **Siri & Shortcuts** *(iPhone, opt-in)* — "Scan a lab report in LabImporter", search your values, or ask for a value's latest reading — only for the values you allow, one by one or all at once. Off until you turn it on (onboarding or Settings → Siri & Shortcuts)
- **iCloud sync** *(iPhone, opt-in)* — optionally roam your dashboard layout (card order, pins, hidden metrics, custom names, and reference ranges) across your devices; your lab values always stay in Apple Health and never sync
- **Share** — export any report as a CDA file to send to a doctor or another app
- **Move between iPhone and Android** — Settings → Transfer exports every report as one file (a standard FHIR Bundle) and imports it on the other platform; reports already there are skipped
- **PDF export** — generate an information-rich PDF report (cover summary, latest-results table, and trend charts), choosing which values and time range to include, in color or black & white, on A4, US Letter, or Legal paper (defaulting to your region's size)
- **Fits every screen** — a sidebar layout on iPad; a navigation rail on Android tablets and unfolded foldables. In Android freeform windows (ChromeOS, Samsung DeX, desktop mode, Waydroid) the content stays clear of the window's caption bar
- **25 languages** — both apps are fully localized in 🇧🇬 Bulgarian · 🏴󠁥󠁳󠁣󠁴󠁿 Catalan · 🇭🇷 Croatian · 🇨🇿 Czech · 🇩🇰 Danish · 🇳🇱 Dutch · 🇬🇧 English · 🇫🇮 Finnish · 🇫🇷 French · 🇩🇪 German · 🇬🇷 Greek · 🇭🇺 Hungarian · 🇮🇹 Italian · 🇯🇵 Japanese · 🇵🇱 Polish · 🇧🇷 Portuguese (Brazil) · 🇵🇹 Portuguese (Portugal) · 🇷🇴 Romanian · 🇷🇺 Russian · 🇨🇳 Simplified Chinese · 🇸🇰 Slovak · 🇪🇸 Spanish · 🇸🇪 Swedish · 🇹🇷 Turkish · 🇺🇦 Ukrainian
- **Privacy-first** — your reports stay on your device, in Apple Health, Health Connect, or the Android app's private storage; no account or server required

---

## Requirements

### iPhone and iPad

- iOS or iPadOS 26.0 or later
- An Apple Intelligence–capable device:
  - **iPhone 15 Pro, iPhone 16, or newer**, or
  - **iPad** with Apple silicon (M1 or later)
- An Apple Developer account (free tier is sufficient for personal use via Xcode)
- For GitHub Actions builds: an Apple Developer Program membership (paid, required for TestFlight)

### Android

- Android 8.0 (API 26) or later; saving to Health Connect needs Android 16 (on older versions reports are kept in the app)
- An on-device model, one of:
  - **Gemini Nano**, on phones whose system offers it to apps (ML Kit GenAI Prompt API, through AICore), or
  - **Gemma 4 E2B**, downloaded once: a 64-bit phone that isn't a low-RAM device, with about 3.1 GB of free storage
- Without either, the app shows **Device Not Supported** — like the iPhone app without Apple Intelligence
- Text recognition reads Latin script; reports in other scripts (Cyrillic, Greek, Japanese, Chinese) can be pasted as text instead of scanned

---

## Building with GitHub Actions (no Mac required)

This repository uses Fastlane + Fastlane Match for fully automated, Mac-free builds of the iPhone app. The steps below are only for it. The Android app needs no secrets: on every pull request (or when run manually), **Build Android** (`build_android.yml`) checks the translations, runs the tests and lint, builds the APKs and uploads them as the `labimporter-android` artifact. See [Building the Android app](#building-the-android-app) to build them yourself.

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

> Secrets can be set at the organisation level — if they are already configured there you have nothing extra to do.

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

This registers your app's bundle ID and the share extension's bundle ID in App Store Connect and enables the **HealthKit**, **iCloud (key-value storage)** and **App Groups** capabilities. The iCloud capability backs the opt-in dashboard-layout sync; if you enable it after a profile already exists, re-run **3. Create Certificates** (with `ENABLE_NUKE_CERTS` or `FORCE_NUKE_CERTS` set) so the provisioning profile is regenerated to include it.

The bundle identifier is `dev.idoodler.<TEAMID>.labimporter`; the share extension's is `dev.idoodler.<TEAMID>.labimporter.share`. The `<TEAMID>` placeholder is substituted automatically from the `TEAMID` secret at build time via `Config.xcconfig`.

**One manual step (App Group).** The share extension hands shared text to the app through an App Group, and the App Store Connect API can't create or assign App Groups. Once, in the [Apple Developer portal](https://developer.apple.com/account/resources/identifiers/list/applicationGroup), register the App Group `group.dev.idoodler.<TEAMID>.labimporter`, then edit both identifiers above, enable **App Groups** and assign that group. If profiles already exist, re-run **3. Create Certificates** with `ENABLE_NUKE_CERTS` or `FORCE_NUKE_CERTS` set so they include it.

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

2. Open `LabImporter.xcodeproj` in Xcode 26 or later (requires the iOS 26 SDK).

3. Configure code signing by creating a `Local.xcconfig` file next to `Config.xcconfig` at the repo root:

   ```
   DEVELOPMENT_TEAM = YOUR_TEAM_ID
   CODE_SIGN_STYLE = Automatic
   ```

   Replace `YOUR_TEAM_ID` with your 10-character Apple Developer Team ID (from [developer.apple.com/account](https://developer.apple.com/account)). `Config.xcconfig` includes this file via `#include?`, so the team and bundle identifier resolve automatically. `Local.xcconfig` is gitignored — keep it out of the Xcode project (the include works purely on the file path) so your Team ID never lands in commits.

4. Build and run on a connected device or simulator (iOS 26+). The first build also compiles the shared Kotlin module via Gradle, so a JDK 17+ must be installed (e.g. `brew install openjdk@21`).

> **Note:** The on-device Foundation Models framework requires a physical device with Apple Intelligence support. On unsupported hardware the app shows a "Device Not Supported" screen, since lab value parsing depends entirely on the on-device model.

Tests: `tools/ios_test.sh`.

---

## Building the Android app

Requires JDK 17+ and the Android SDK (platform 37); no Mac needed. From the repo root:

```bash
./gradlew :androidApp:assembleRelease
```

The APKs land in `androidApp/build/outputs/apk/release/`: `arm64-v8a` for phones and Apple-silicon emulators, `x86_64` for Intel/AMD PCs and emulators (e.g. Waydroid), and a universal APK with both. Release test builds are signed with the debug key.

**Emulators have no Gemini Nano**, so the release APK offers the Gemma download there, just as on a phone without it (2.6 GB; it then reads reports on the emulator's CPU, about a minute each). For a quicker look, install a **debug** build (`./gradlew :androidApp:assembleDebug`) and tap **Use Development Stand-in** on that screen: imports then return clearly labelled *sample* values (not read from your document) so review, saving and the dashboard can be exercised. The stand-in exists only in debug builds.

Tests: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest` (shared logic, plus Android unit and Compose UI tests against the real LOINC catalog).

The version of both apps is set once, as `MARKETING_VERSION` in `Config.xcconfig`; Android's version code follows from it.

---

## How it works

Both apps are native — SwiftUI on iPhone and iPad; Jetpack Compose with **Material 3 Expressive** and Material You dynamic colour on Android (`androidApp/`) — and share their business logic through a **Kotlin Multiplatform** module (`shared/`): the LOINC catalog and search (the same `loinc.db`), LOINC resolution, the AI prompt and the checks on its reply, categories, UCUM units, C-CDA export and read-back with schema migrations, the FHIR codec, the dashboard rules, PDF pagination and the iPhone ⇄ Android transfer file. The iOS app links it as the static `LabImporterCore` framework, so this logic exists once, for both apps.

| Step | iPhone and iPad | Android |
|---|---|---|
| Document input | `VisionKit` document scanner (multi-page), `UIDocumentPicker` for PDFs / images, clipboard, files opened from other apps (`CFBundleDocumentTypes` + `onOpenURL`), share extension for text | ML Kit document scanner, system file picker (PDFs / images), paste, "Open with" / Share for files and text |
| PDF rendering | `PDFKit` — extracts embedded text or renders pages for OCR | `PdfRenderer` — renders pages for OCR |
| Text extraction | `Vision` — `VNRecognizeTextRequest` (app's current language + English fallback) | ML Kit Text Recognition (bundled, Latin script) |
| Lab value parsing | `FoundationModels` — `@Generable` structured output via `LanguageModelSession` | **Gemini Nano** via the ML Kit GenAI Prompt API (AICore), or **Gemma 4 E2B** via LiteRT-LM (GPU where it works, else CPU), its output constrained to the reply's JSON Schema |
| LOINC matching and checks | Shared Kotlin core | Shared Kotlin core |
| Saving | `HealthKit` — `HKCDADocumentSample` (C-CDA R2.1 clinical document) | **Health Connect** medical records — FHIR R4 laboratory `Observation`s (Android 16+); elsewhere the app's private storage (excluded from backup), movable to Health Connect later |
| System search | `CoreSpotlight` — on-device index of tracked metrics; results deep-link into the metric's trend detail via the `labimporter://` URL scheme | Launcher shortcuts — one per tracked metric, opening its trend |
| Voice | App Intents — Siri & Shortcuts | — |

**Gemini Nano and Gemma on Android.** If Gemini Nano is offered but still needs downloading, Android fetches it and the import waits, showing progress. On phones whose system doesn't offer Gemini Nano to apps, LabImporter offers to download Google's open Gemma 4 E2B model once (2.6 GB, Apache 2.0, from Hugging Face at a pinned revision; Wi-Fi only unless you allow mobile data). Android's download manager fetches it, so it continues when you leave the app or close it; a background job then checks the file against its SHA-256 and installs it, and a notification says when it's ready (or why it failed). Settings shows the model and can delete it again. Where neither model can run, the app shows **Device Not Supported** — values are never guessed.

---

## iCloud sync

iCloud sync is part of the iPhone and iPad app; it is **opt-in** and **layout-only**. On first launch, the last onboarding step (after the welcome, disclaimer and Apple Health steps) offers it as a toggle, off by default; you can change it later under **Settings → iCloud Sync**. The Android app has no layout sync — use Settings → Transfer to bring your reports over.

When enabled, only your **dashboard layout** roams across your devices through your private iCloud key-value store (`NSUbiquitousKeyValueStore`): the card order, which metrics are pinned, which are hidden, any custom names (nicknames) you've given metrics, and any custom reference ranges you've set. That's the entire payload.

**Your lab values never sync.** They live exclusively in Apple Health, and patient metadata (name, date of birth, biological sex) stays on the device it was entered on. No lab data is ever transmitted, with or without sync enabled.

---

## Privacy

All processing happens entirely on-device. No lab data is sent to any server. The Foundation Models framework runs the language model locally without any network requests. On Android, the only network use is downloading an on-device model: Gemini Nano by the system, or — only on phones without Gemini Nano for apps, and only when you tap Download — the pinned Gemma model file from Hugging Face, fetched by Android's download manager and checked against its SHA-256. That request carries nothing about you or your reports; a build-time test (`NetworkGuardrailTest`) fails if any other code reaches for the network, and ML Kit's usage-statistics backend is removed so it can't send either. Reports the Android app keeps itself (before Health Connect is available) are excluded from cloud backup and device transfer. The optional [iCloud sync](#icloud-sync) carries only your dashboard layout — never lab values or patient metadata. The Spotlight search index and Android's launcher shortcuts also stay on-device and contain only metric names unless you explicitly opt in to showing the latest reading in results. Siri reads back only the values you have allowed.

---

## AI Disclosure

### On-device AI inside the app

Lab value extraction is powered by Apple's on-device Foundation Models framework (`LanguageModelSession` / `@Generable`). The language model runs entirely on the device — no lab data is transmitted to any external server or API. An Apple Intelligence-capable device (iPhone 15 Pro, iPhone 16, or newer, or an iPad with Apple silicon, on iOS / iPadOS 26+) is required for parsing; on unsupported hardware the app shows a "Device Not Supported" screen.

On Android, the same prompt runs on **Gemini Nano** (ML Kit GenAI Prompt API) or, on phones without Gemini Nano for apps, on the downloaded **Gemma 4 E2B** model (LiteRT-LM, output constrained to the reply's JSON Schema). Both run entirely on the phone. The model quality check in `evaluation/` runs the app's exact prompt and post-processing over sample reports in several languages; see its README for the last result.

### Built with AI assistance

This app was designed and built with the assistance of [Claude Code](https://claude.ai/code) by Anthropic. AI-assisted development was used throughout: app architecture, Swift/SwiftUI implementation, HealthKit and CDA integration, on-device model prompting, the dashboard, trends, and history features, the shared Kotlin Multiplatform core, and the Android app (Jetpack Compose, Health Connect, Gemini Nano and Gemma).

---

## Medical disclaimer

LabImporter is **not a medical device** and does **not** provide medical advice, diagnosis, or treatment. It is a convenience tool for importing and visualising your own lab values.

Values are extracted automatically — including by on-device AI and OCR — and may be **inaccurate or incomplete**. Always verify every value against your original lab report before relying on it. **Never make medical decisions based on this app.** For any questions about your results, consult a qualified healthcare professional.

The software is provided "as is", without warranty of any kind, as described in the [LICENSE](LICENSE).

---

## Support

LabImporter is free and open source. If you find it useful, you can support its development:

<a href="https://liberapay.com/idoodler/donate" target="_blank"><img src="https://img.shields.io/badge/Liberapay-Donate-F6C915?logo=liberapay&logoColor=black" alt="Donate using Liberapay"></a>

[liberapay.com/idoodler](https://liberapay.com/idoodler/donate)

---

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
