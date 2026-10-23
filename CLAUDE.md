# CLAUDE.md

Guidance for AI assistants (and humans) working in this repository.

## What this is

**LabImporter** is a native iOS app — with an Android port (see "Android &
shared Kotlin module") — that imports lab report values into Apple Health
using **on-device AI**. The pipeline is:

```
Document input  →  Text extraction  →  AI parsing  →  Review/correct  →  Save to Health
(scan/PDF/image/  (Vision OCR or       (Foundation    (SwiftUI sheet)   (HKCDADocumentSample,
 paste/manual)     PDFKit text)         Models)                          CDA R2 XML)
```

Everything runs locally — no servers, no accounts, no network requests for lab
data. The Foundation Models language model runs entirely on device.

- **Language:** Swift 6.0 (strict concurrency)
- **UI:** SwiftUI (iOS 26 SDK, including Liquid Glass APIs like `.glassEffect`)
- **Deployment target:** iOS 26.0
- **Hardware requirement:** Apple Intelligence–capable device (A17 Pro / M1 or
  later). On ineligible hardware the app shows `UnsupportedDeviceView`.
- **Persistence:** There is **no app database**. Lab reports are stored *in
  Apple Health* as CDA clinical documents and re-read on each launch. Only UI
  preferences and patient metadata live in `UserDefaults` (`@AppStorage`).

## Repository layout

```
LabImporter/
├── LabImporterApp.swift        # @main entry; starts SharedCore; routes to HomeView or UnsupportedDeviceView
├── Info.plist                  # usage strings, document types (PDF/image), HealthKit, App Group key
├── LabImporter.entitlements    # HealthKit, iCloud KVS, App Group (shared with the share extension)
├── Localizable.xcstrings        # all user-facing strings (String(localized:))
├── AppIcon.icon/               # app icon (Icon Composer); SVG layers from tools/generate_app_icon_layers.py,
│                               #   previews rendered to docs/icon/ via ictool.
│                               #   Assets.xcassets/AppIconPreview (in-app, 1024px) and LaunchIcon (launch screen,
│                               #   120pt @2x/@3x — must stay point-sized, UILaunchScreen draws it unscaled) use the same renders.
├── Models/                     # SwiftUI-friendly value types; their rules delegate to LabImporterCore
│   ├── LabValue.swift          # editable in-memory value (used in Review flow)
│   ├── LabReport.swift         # a saved report (Codable); .asLabValues bridges to LabValue
│   ├── LabMapping.swift        # thin catalog adapter: LOINC code ↔ display name ↔ CDA export / loinc.org URL
│   ├── LabDisplayPreferences.swift  # pinned/ordered/hidden + per-code nicknames (RawRepresentable for @AppStorage)
│   └── SiriExposurePreferences.swift  # what's exposed to Siri (RawRepresentable for @AppStorage) — see "Siri & App Intents"
├── Services/
│   ├── OCRService.swift        # actor; Vision text recognition + PDFKit rendering
│   ├── LabParserService.swift  # actor; Foundation Models @Generable structured parse
│   ├── HealthKitService.swift  # actor (.shared); read/write/delete HKCDADocumentSample; read-back via shared CdaParser
│   ├── CDAExportService.swift  # struct; thin wrapper over the shared CdaExport (C-CDA R2.1 + UCUM)
│   ├── LoincDirectory.swift    # Swift face of the shared LOINC catalog (LabImporterCore)
│   ├── SharedCore.swift        # starts LabImporterCore; Swift ⇄ Kotlin conversions at the boundary
│   ├── ShareInbox.swift        # App Group inbox: share extension → app (also compiled into the extension)
│   └── ReportTransferService.swift  # iPhone ⇄ Android transfer file (shared ReportTransfer) + Transferable
├── Intents/                    # App Intents — Siri's only integration path (SiriKit is retired)
│   ├── LabImporterShortcuts.swift  # AppShortcutsProvider; declares phrases for both intents
│   ├── AskLabValueIntent.swift  # reads back a value's latest reading, gated by SiriExposurePreferences
│   ├── StartLabScanIntent.swift  # opens the scanner (openAppWhenRun); never touches a value
│   ├── LabMetricEntity.swift    # AppEntity (not IndexedEntity — see "Siri & App Intents"); the only source of "which metrics can Siri see"
│   ├── SiriOnScreenContext.swift  # lock-protected bridge: Review sheet → Siri's on-screen-awareness suggestions
│   └── SiriActionBridge.swift   # lock-protected bridge: StartLabScanIntent → the live HomeView's scan flow
└── Views/                     # SwiftUI screens, grouped by feature
    ├── Home/
    │   ├── HomeView.swift          # orchestrates the whole import flow + report loading
    │   └── SidebarSection.swift    # sidebar section enum; also the shared nav state across layouts
    ├── Onboarding/             # WelcomeView, DisclaimerView, HealthPermissionView, CloudSyncOptInView,
    │                           #   SpotlightOptInView, SiriIntelligenceOptInView, OnboardingScaffold (first-launch gates)
    ├── Import/                 # ImportLandingView (scan/file/paste/manual entry points),
    │                           #   DocumentScannerView (VNDocumentCameraViewController), LabImportEngine
    ├── Review/                 # ReviewView (+Preview), ReviewHeaderCard, LabValueRowView,
    │                           #   AddValueSheet, CodePickerSheet, LoincBrowserView
    ├── Dashboard/              # DashboardView (Swift Charts cards), MetricCard, TrendsView
    ├── History/                # HistoryView (saved reports list), ReportDetailView
    ├── Settings/               # SettingsView (patient metadata + display prefs), LabSortEditor, RenameLabAlert,
    │                           #   SiriAccessEditor (per-metric + per-capability Siri opt-in)
    └── Shared/                 # MorphingCategoryBackground, PreviewSampleData,
                                #   UnsupportedDeviceView (also defines enum DeviceSupport.isSupported)

LabImporterShare/   # share extension (text only): hands shared text to the app via ShareInbox
LabImporterTests/   # unit tests (Swift Testing), hosted in the app; golden CDA shared with the Kotlin tests
LabImporterUITests/ # UI tests (XCUITest)
tools/              # sync_xcode_project.rb (targets/files/scheme), ios_test.sh, LOINC/icon/string generators
shared/, androidApp/  # Kotlin Multiplatform core + Android app — see "Android & shared Kotlin module"
fastlane/        # Fastfile (build/release/certs lanes) + Matchfile (git storage)
.github/workflows/  # numbered setup workflows + lint + build
.claude/         # SessionStart hook (installs SwiftLint) + pre-push lint hook
Config.xcconfig  # MARKETING_VERSION (the app version, both apps) + BUNDLE_IDENTIFIER = dev.idoodler.$(DEVELOPMENT_TEAM).labimporter
```

## Architecture & key conventions

### Concurrency (Swift 6 strict)
- Long-running work lives in **`actor`** types: `OCRService`, `LabParserService`,
  `HealthKitService`. Views call them with `await` from `Task { … }`.
- `HealthKitService` is a singleton (`HealthKitService.shared`); its `HKHealthStore`
  is `nonisolated(unsafe)` because `HKHealthStore` is thread-safe by design.
- Legacy completion-handler APIs (Vision, `HKDocumentQuery`, `HKSourceQuery`) are
  bridged with `withCheckedThrowingContinuation`, guarding against double-resume
  via a `finished` flag.
- `LabValue` is `@unchecked Sendable` by deliberate choice — it's a value type
  mutated only on `@MainActor` (see the comment on the type; preserve it).

### Data model relationships
- **`LabValue`** — mutable, used while reviewing/editing (`isSelected`, editable code).
- **`LabReport` / `LabReport.Entry`** — immutable, what gets persisted to/read from
  Health. Use `report.asLabValues` to convert for editing.
- **Shared core:** the rules below — LOINC catalog and mapping, categories,
  CDA export/read-back with schema migrations, AI post-processing and LOINC
  resolution, dashboard ordering/sparkline windows, display-preference JSON —
  live **once** in `shared/` (Kotlin) and are used by iOS and Android alike.
  The Swift types keep their APIs for SwiftUI and convert at the boundary
  (`SharedCore.swift`). Change behaviour in Kotlin, not in a Swift copy.
- **`LoincDirectory`** wraps the full LOINC catalog. `tools/build_loinc_resource.py`
  (run by the "Generate LOINC resource" Xcode build phase) distills the official
  release zip under `Vendor/LOINC/*.zip` into a bundled SQLite database `loinc.db`
  (FTS5) — the ~18.5k common laboratory terms, each with a name + description
  localized into every language the app ships. The DB is a build product (not in
  git); only the source zip is committed. `LabMapping` falls back to
  `LoincDirectory` for any code that is already a raw LOINC number (e.g. picked in
  `CodePickerSheet`).
- **`LabMapping`** is a thin, data-driven adapter over `LoincDirectory` (no curated
  tables of its own):
  - `displayName(for:)` — the user's nickname for a code if they set one (in
    Sort & Visibility), else the localized catalog name, else the raw code. The
    nickname is a cosmetic display preference (`LabDisplayPreferences.nicknames`,
    persisted under the legacy JSON key `customNames`, synced via iCloud) — it
    never reaches the exported CDA.
  - `loincCode(for:)` — validates the code + returns an English display for CDA
    export; **returns nil → value is not exportable** (unknown/unmapped code).
  - `loincURL(for:)` — the loinc.org details page for a code.
- **Import resolution (printed report → LOINC)** lives in the shared
  `LabParsePipeline.resolveLoinc`: an already-valid LOINC code passes through;
  otherwise the AI's test name is matched against the catalog **in the report's
  own language** (English as fallback), then in every catalog language
  (everyday spellings like "Kreatinin" differ from the LOINC translation
  "Creatinin"), then without little words ("Albumin im Urin"), then by word stem
  ("Cholesterin" → "Cholesterol"); finally the printed code, **as a whole word
  only** (so "KREA" never prefix-matches "Kreatinkinase"). Hits are ranked by
  `LoincRanking`, not by commonness alone: a label that *names* the test beats
  one that merely mentions it (Hämoglobin → 718-7, not MCHC; plain
  "Cholesterol" is total cholesterol, never the cholesterol/HDL ratio — the
  word "total" is dropped as a retry), the unit's kind of quantity (`UnitKind`)
  rules out terms that can't hold the value (mg/dl is never a urine-strip
  finding or a crystal count) and picks mass vs. moles, and a molar unit keeps
  the name's usual specimen (µmol/l creatinine → serum, not blood). The winner is
  offered as a suggestion (`isSuggestedCode`); nothing fitting → the printed code
  stays for the user to map. A *second* suggestion for an already-claimed LOINC
  stays unmapped instead of replacing a value. There is no static abbreviation
  table — resolution is entirely catalog/FTS driven. The model's report date
  is kept only if the report text prints it (`verifiedDate`; a mis-copied year
  falls back to the one printed date with that day and month, else no date).
- **Model quality check:** `evaluation/` (JVM, not part of the app or CI) runs
  an on-device model over synthetic sample reports with the app's exact prompt,
  schema and post-processing, and scores values, LOINC codes and dates — see
  `evaluation/README.md`. Re-run it (or `--args="replay"` for post-processing
  changes) before touching the prompt, the LOINC ranking or the downloaded model.

### CDA round-trip
- **Export:** the shared `CdaExport.generate` (via `CDAExportService.generateCDA`)
  emits a C-CDA R2.1 Lab Report. Only values that are `isSelected`, have a
  `numericValue`, and have a LOINC mapping are included. Units are normalized to
  UCUM (`Ucum`). Its exact output is pinned by a golden file
  (`shared/src/jvmTest/golden/report.cda.xml`) asserted by both the Kotlin and
  the Swift tests.
- **Import-back:** `HealthKitService` reads `HKCDADocumentSample`s and the shared
  `CdaParser` reconstructs `LabReport`s (`HealthKitService.report(fromCDA:id:)`).
  The source filter restricts to this app's bundle ID + the default source.
- Document author/custodian are stamped as `LabImporter`; the parser ignores
  those sentinel values when reconstructing patient/author names.
- **Versioning:** every exported document carries a schema version in the
  authoring device — `manufacturerModelName` = `LabImporter <version> (<build>)`
  (provenance) and `softwareName` = `LabImporter CDA v<N>` where `N` is the shared
  `ReportSchema.VERSION` (the Android FHIR records carry the same number). On
  read-back, `CdaParser` parses `N`; `ReportSchema.upgrade` **ignores** documents
  with no recognized version (legacy exports) and chains migrations up to the
  current schema. See "Migrating the exported CDA" below before changing the export.

### UI patterns
- All user-facing text uses `String(localized:)` / SwiftUI auto-localization and
  lives in `Localizable.xcstrings`. **The app ships 25 languages** — `en`
  (source), `bg`, `ca`, `cs`, `da`, `de`, `el`, `es`, `fi`, `fr`, `hr`, `hu`,
  `it`, `ja`, `nl`, `pl`, `pt-BR`, `pt-PT`, `ro`, `ru`, `sk`, `sv`, `tr`, `uk`,
  `zh-Hans` (every iOS-supported language spoken in the EU, plus `ja`/`ru`/`tr`/
  `uk`/`zh-Hans`) — and **every new key must be translated into all of them**, not
  just English/German. Don't leave a string EN-only (which silently falls back to the
  key) or EN+DE-only. Preserve format specifiers (`%lld`, `%@`) and follow the
  Health-app naming convention below in each language. Reuse an existing key
  verbatim when the same source string already exists (it's already fully
  translated) rather than adding a duplicate.
- **Naming the Health app:** never leave "Apple Health" untranslated. Every
  reference to the Health app must use the **on-device localized app name** for
  that language, keeping the **`Apple` brand prefix**. Canonical forms:
  `de Apple Health` · `fr Apple Santé` · `es Apple Salud` · `it Apple Salute` ·
  `pt-BR / pt-PT Apple Saúde` · `nl Apple Gezondheid` · `pl Apple Zdrowie` ·
  `ja Appleヘルスケア` · `zh-Hans Apple 健康` · `ru Apple Здоровье` ·
  `tr Apple Sağlık` · `uk Apple Здоров'я` · `bg Apple Здраве` ·
  `ca Apple Salut` · `cs Apple Zdraví` · `da Apple Helbred` · `el Apple Υγεία` ·
  `fi Apple Terveys` · `hr Apple Zdravlje` · `hu Apple Egészség` ·
  `ro Apple Sănătate` · `sk Apple Zdravie` · `sv Apple Hälsa`. Inflect the
  localized noun for the surrounding grammar (pl/ru/uk/cs/sk/hr case declensions,
  fi/hu case suffixes, el article agreement, fr/ca `d'Apple` elision, uk в/у
  euphony) — the `Apple` prefix stays invariant. Where the **English source**
  itself uses a bare "Health" (e.g. the `Save Reports to Health` toggle), mirror
  that and use the bare localized name without the prefix. `Health Records`
  (`Gesundheitsakte`, …) is a *different* Apple feature — leave it alone.
- OCR recognizes the app's current language plus English —
  `OCRService.recognitionLanguages(for:)` uses `Bundle.main.preferredLocalizations`
  first, then English, filtered to Vision's `supportedRecognitionLanguages()`.
  Narrative CDA labels are German.
- Preferences (`labDisplayPrefs`, `patientName`, `hasSeenWelcome`, etc.) are read
  via `@AppStorage`. `LabDisplayPreferences` is `RawRepresentable` over the shared
  JSON codec (`LabImporterCore.LabDisplayPreferences.encode/decode`) — the same
  blob Android stores; it always writes every key so older builds keep decoding it.
- Reports are reloaded on `HomeView` `.task`, on returning from the review sheet,
  and on `didBecomeActiveNotification`.
- **Always ship `#Preview`s, in all variations.** Every SwiftUI view must have
  SwiftUI previews — and not just one happy-path preview: cover the view's
  *meaningful variations* so each state is inspectable without running the app.
  That means, as applicable: empty vs populated (and "few items" in between),
  light **and** dark (`.preferredColorScheme`), the size classes the view adapts
  to (e.g. an onboarding screen's portrait vs landscape/compact-height layout via
  `.previewInterfaceOrientation`), and any first-class state the view renders
  differently (loading, error, in-range vs out-of-range, pinned, selected,
  RTL via `.environment(\.layoutDirection, .rightToLeft)`). Name each preview so
  the variation is obvious (`#Preview("Dark")`, `#Preview("Empty")`). Drive them
  from `PreviewSampleData` / the model `sample*` fixtures rather than ad-hoc data.
  New or edited views are not done until their previews cover every variation.

### Siri & App Intents
- SiriKit is retired; **App Intents is the only path into Siri**. Everything
  lives in `Intents/` and stays feature-detectable/self-contained — no
  separate intents extension target, no `NSUserActivityTypes`/`IntentsSupported`
  Info.plist entries (those are SiriKit-era).
- **Exposure is opt-in, never implied.** `SiriExposurePreferences`
  (`Models/SiriExposurePreferences.swift`) is the single source of truth for
  what Siri may do: a master `isEnabled` switch (set in onboarding via
  `SiriIntelligenceOptInView`, or later in Settings), a per-LOINC-code
  `allowedCodes` allow-list (empty by default — enabling Siri alone exposes
  *no* value), and separate toggles for the scan shortcut, on-screen
  awareness, and knowledge-graph indexing. `SiriAccessEditor` is the Settings
  screen that edits all of it. Every intent/query re-checks this preference
  itself (never trusts that a value was allowed when a Shortcut was built) —
  see `AskLabValueIntent.perform()` and `LabMetricEntityQuery`.
- **`LabMetricEntity`** (`AppEntity`) is the *only* place metrics are exposed
  to Siri's own suggestions: its query filters by `allowedCodes` for
  resolution and additionally by `allowKnowledgeIndexing` for proactive
  suggestions. It deliberately does **not** conform to `IndexedEntity` —
  that would also donate these metrics to the system search index, i.e.
  Spotlight, duplicating `SpotlightIndexService` (`Services/`), which is the
  single, dedicated Spotlight feature (opted into separately via
  `SpotlightOptInView`/`SpotlightSearch.showLatestValueKey` and covering
  *every* tracked metric, not just Siri-allowed ones). Never build a second
  path that exposes entities to Siri without going through
  `LabMetricEntity`, and never make it `IndexedEntity` — that would
  reintroduce the duplicate Spotlight surface.
- App Intents run outside SwiftUI's environment — sometimes before any view
  exists (a cold launch via `StartLabScanIntent.openAppWhenRun`). Two small
  lock-protected singletons bridge that gap instead of `@AppStorage`
  bindings or `@Observable` state: `SiriOnScreenContext` (Review sheet →
  what's currently on screen) and `SiriActionBridge` (the intent → the live
  `HomeView`'s scan flow, queuing the request if the app isn't running yet).
  Follow this pattern — don't reach for `NotificationCenter` or a new
  `@AppStorage` flag for intent → view communication.
- `ParameterSummary`/`AppShortcut` phrase strings (the `Summary(...)` in an
  intent and the `phrases:` in `LabImporterShortcuts`) are **not** localized
  in `Localizable.xcstrings` today — their String Catalog extraction format
  wasn't verified against a real Xcode toolchain, so they're left as English
  source rather than risking a malformed catalog entry. Everything a user
  actually reads or hears (dialogs, titles, descriptions, all Settings/
  onboarding UI) *is* fully localized the normal way. If you verify the
  phrase/summary extraction format in Xcode, localize them and remove this
  note.

## Android & shared Kotlin module

```
shared/        # Kotlin Multiplatform (android, iosArm64, iosSimulatorArm64, jvm-for-tests)
  src/commonMain/kotlin/dev/idoodler/labimporter/core/
    LabCore.kt           # entry point: start(dbPath, appLanguage, prefsProvider)
    model/               # LabValue, LabReport, LabMapping, LabCategory, ReferenceRange,
                         #   LabDisplayPreferences (same JSON blob as iOS), Dashboard (metrics,
                         #   ordering, trend windows, code names)
    loinc/LoincDirectory # androidx.sqlite over the same loinc.db (bundled driver on
                         #   Android/JVM, system SQLite on iOS); search + bestMatch for resolution
    loinc/LoincRanking   # which catalog hit fits a printed name (label fit, UnitKind, specimen)
    export/              # Ucum, CdaExport (C-CDA R2.1), CdaParser, FhirLabCodec, ReportSchema
                         #   (one schema version + migration chain for CDA and FHIR)
    parse/               # LabParseSchema/LabParsePrompt (shared AI instructions, guide texts,
                         #   JSON contract/schema), LabParsePipeline (numbers, LOINC resolution,
                         #   date check against the text, dedup)
    transfer/            # ReportTransfer: iPhone ⇄ Android file (FHIR Bundle; also reads CDA)
    export/PdfReport.kt  # PDF content + pagination (PdfReport, PdfLayout) — both apps' PDFs
    model/               # … also ChartAxis (rounded chart ticks), SortEditorState (Sort & Visibility rules)
    about/               # About: FUNDING.yml links, repository URL, "Report an Issue" link
    platform/            # NumberFormatting (printf-exact on both platforms), SQLite, locks
  src/jvmTest/           # the shared tests (+ golden/report.cda.xml, also used by the Swift tests)
androidApp/    # Jetpack Compose, Material 3 Expressive, dynamic colour
  ai/                    # OnDeviceAi (availability/problems), GeminiNanoAi (ML Kit GenAI Prompt API),
                         #   GemmaAi (downloaded Gemma via LiteRT-LM), SelectableAi, LabParserService
  ai/gemma/              # GemmaModel (pinned file), ModelDownloads (the only network code), GemmaModelStore,
                         #   GemmaRuntime (LiteRT-LM), ModelInstallJob, ModelDownloadNotifications
  src/debug/…/ai/        # DevelopmentStandIn — debug builds only (emulators); src/release has a null stub
  data/                  # HealthConnectReportStore (FHIR Medical Records), LocalReportStore, ReportRepository,
                         #   AppPreferences (incl. patient date of birth/sex for the PDF cover)
  export/                # PdfReportRenderer: draws the shared PdfReport pages with PdfDocument
  shortcuts/             # LauncherShortcuts: static Scan + one dynamic shortcut per value
  ui/design/             # design system: category colour/icon/shape (CategoryStyle), CategoryWash
                         #   backgrounds, ShapeBadge/CategoryChip/TrendBadge, AreaChart, grouped lists
  ui/                    # dashboard (home), trends, reports, report detail, import, review,
                         #   settings (+ Sort & Visibility), PDF export, LOINC catalog/term,
                         #   onboarding + UnsupportedDeviceScreen
  translations/Android.xcstrings  # translations of Android-only wording (String Catalog format)
  src/test, src/testDebug         # JVM + Robolectric/Compose UI tests
tools/xcstrings_to_android.py  # generates values-<lang>/strings.xml from both catalogs (--check in CI)
tools/generate_android_icon.py # layered adaptive launcher icon (+ night + monochrome) from the iOS icon art
```

- **Business logic belongs in `shared/`** whenever both platforms need it — and
  iOS uses it: CDA export/read-back, LOINC catalog/mapping/resolution, AI
  post-processing, categories, preferences JSON and the dashboard rules all come
  from the static `LabImporterCore` framework, built by the Xcode "Build shared
  Kotlin (LabImporterCore)" phase (`./gradlew :shared:embedAndSignAppleFrameworkForXcode`;
  needs a JDK 17+). SKIE bridges `suspend`/`Flow`/sealed types to Swift. In Swift
  test files, don't `import LabImporterCore` next to `@testable import
  LabImporter` unless needed — its model types share the app's names.
- **Android AI — like iOS, never a guess.** Gemini Nano via the ML Kit GenAI
  Prompt API (`GeminiNanoAi`): AICore's status decides — AVAILABLE → ready;
  DOWNLOADABLE/DOWNLOADING → the model downloads with progress (an import waits
  for it); UNAVAILABLE or an incompatible AICore → `UnsupportedDeviceScreen`
  replaces the app ("Device Not Supported", with Check Again), the counterpart of
  iOS's `UnsupportedDeviceView`. Every `GenAiException` maps to an `AiProblem`
  with a specific message (`GenAiErrors`). Only the system's own verdict makes a
  phone "unsupported" (feature status UNAVAILABLE, NOT_AVAILABLE/NOT_SUPPORTED;
  update errors ask for an update); no reply, an unexpected error or no client
  is `NOT_DETERMINED` ("couldn't check"). The screen shows the system's answer
  (`Unsupported.detail`). Note: Google offers the Prompt API only on some phones
  (ML Kit's device list) — e.g. not the Galaxy S25, which has Gemini Nano only
  for the other GenAI APIs. **Release builds:** R8 once broke ML Kit's component
  lookup (keep rules in `proguard-rules.pro`) — after changing shrinking, run the
  release APK on an emulator and check logcat for `Gemini Nano feature status`.
  The token limit is checked up front
  and a reply cut off at the output limit counts as "too long" (same advice as
  iOS). A reply that isn't a complete, well-formed result is a failure, not an
  empty result.
- **Gemma for phones without Gemini Nano for apps** (`GemmaAi`, `ai/gemma/`).
  Where the system says Gemini Nano is unsupported and the phone is 64-bit and
  not low-RAM, `SelectableAi` merges the system's verdict with an *offer*
  (`Unsupported.modelDownload`): `UnsupportedDeviceScreen` shows the download of
  Google's open **Gemma 4 E2B** (Apache 2.0) in the LiteRT-LM format — its size,
  that only the model file is fetched (from huggingface.co), the license, Wi-Fi
  only unless the user allows mobile data. `GemmaModel` pins one file of one
  revision with its size and SHA-256; any change there needs the quality check
  (`evaluation/`) first. The **system download manager** fetches it
  (`SystemModelDownloads` — the app's only network code), so it carries on in the
  background and survives the app being closed; `ModelDownloadReceiver` →
  `ModelInstallJob` checks and installs it when it completes, and
  `ModelDownloadNotifications` tells a user who isn't in the app (checking /
  ready / why not; the download's own progress is the system's notification).
  While it downloads the app is usable and the dashboard (`ModelStatusCard`),
  Settings and the import dialog show progress, a wait for Wi-Fi or the check.
  A failed download, a damaged file (SHA-256 mismatch) or a load that took the
  app down (`GemmaModelStore.loading`) stays failed until **Try Again** — 2.6 GB
  never restart by themselves. The model loads on first use (GPU, else CPU),
  output constrained to `LabParsePrompt.jsonSchema`, and is released when the app
  goes to the background. Gemini Nano wins wherever it's available; Settings then
  offers to delete the no-longer-needed model. **INTERNET** is declared for this
  download only: `NetworkGuardrailTest` fails if any other app or shared code uses
  networking APIs, if the URL stops being the pinned one, or if a library's
  telemetry backend returns (ML Kit's Clearcut backend is removed in the
  manifest). LiteRT-LM has no R8 rules of its own — keep the ones in
  `proguard-rules.pro` and smoke-test the release APK. Its GPU backend
  *initializes* without a GPU driver and only fails at the first step ("Can not
  find OpenCL library", as on the emulator), so `LiteRtGemmaRuntime.load` takes
  one step before calling the GPU usable; the CPU fallback follows from that.
  The GPU backend converts the weights into the cache folder (1.5 GB on the
  Mac) — deleted with the model and when the GPU fails. **Testing on the
  emulator** costs ~4–5 GB of host disk (the model plus that cache inside the
  AVD image) and the AVD needs ≥ 4 GB RAM; an emulator whose host disk fills up
  goes offline. The model download's background path (download manager →
  `ModelDownloadReceiver` → `ModelInstallJob` → "AI Model Ready", with the app's
  process killed mid-download) was verified on the emulator with a release build.
- **Emulators:** they have no Gemini Nano, so a release build shows "Device Not
  Supported". Debug builds additionally offer a clearly labelled *development
  stand-in* on that screen (or `adb shell am start -n
  dev.idoodler.labimporter/.MainActivity --ez dev.idoodler.labimporter.DEVELOPMENT_STAND_IN true`):
  it streams a fixed sample reply through the real shared pipeline so review,
  saving and the dashboard can be exercised; its results carry a "sample values"
  banner. It lives only in `src/debug` and never ships.
- **Window insets:** screens pad their top with `safeDrawing` / `systemBars`
  (Material's Scaffold, top bars and rail do by default), never the status bar
  alone: in a freeform window (Waydroid multi-window, ChromeOS, DeX, desktop
  mode) Android draws its caption bar over the app and reports it only as the
  caption-bar inset. `WindowInsetsGuardrailTest` fails on status-bar-only
  padding. Keep backgrounds full-bleed; pad the content. Android 13 and older
  draw that caption inside the app's window (`DecorCaptionView`) over the
  content, and on Waydroid (LineageOS 20) its inset is 0 at first and arrives
  a layout pass later: `LegacyCaption` moves the content below the caption
  until then (only while one visibly covers it and no inset was reported; a
  no-op on Android 14+ and in fullscreen; it logs `Legacy caption covers the
  content by N px`). Verified on Waydroid multi-window, Android 13.
- **Android storage:** Health Connect *Medical Records* (FHIR R4 laboratory
  `Observation`s, Android 16+, still `@ExperimentalPersonalHealthRecordApi`).
  There's no `DiagnosticReport`, so each Observation carries its report id as a
  `meta.tag`, plus the `ReportSchema` version tag. Without Health Connect the same
  FHIR JSON is kept in `noBackupFilesDir` (`LocalReportStore`) and moved over when
  the user connects — one report at a time, each local copy deleted only once
  Health Connect has it. A move that stops half-way (or saves made while Health
  Connect was unavailable) is finished on the next `ReportRepository.reload()`,
  and any report not yet moved stays listed meanwhile (upserts make retries
  harmless). After an update to Android 16 the dashboard offers the move
  (`HealthConnectOfferCard`, `HealthConnectState.offersMove`) until the user
  connects or says "Not Now".
- **Transfer (iPhone ⇄ Android):** Settings → Export All Reports writes one FHIR
  Bundle file (`ReportTransfer`); Import Reports reads it (or a single CDA from
  either app) and skips reports already present (content fingerprint). No server,
  no account — the user carries the file.
- **Android strings:** English source in `androidApp/src/main/res/values/strings.xml`.
  Reuse an iOS source string verbatim where one exists (its translations are
  reused); otherwise add the translations to `androidApp/translations/Android.xcstrings`.
  Then run `python3 tools/xcstrings_to_android.py --check` — it regenerates every
  `values-<lang>/strings.xml` (don't edit those) and fails on any gap. Lint treats
  a missing translation as fatal. `Android.xcstrings` may also override single
  languages of shared wording — needed where the iOS text uses Apple's automatic
  inflection (`^[Bienvenido](inflect: true)`), which Android can't render: use
  gender-neutral wording there (`--check` fails on leftover markup). Counts that
  change the wording are `<plurals>` with every quantity the language uses.
- **Android look & navigation:** Material 3 Expressive with dynamic colour, but
  every screen sits on a `CategoryWash` (the Android counterpart of the iOS
  `CategoryBackground`) and each clinical category has one colour (shared
  with iOS), one Material symbol and one `MaterialShapes` badge
  (`ui/design/CategoryStyle.kt`) — reuse those instead of ad-hoc colours.
  There is deliberately **no tab bar**: the dashboard is home, Reports and
  Settings open from its top bar, details push on a back stack with predictive
  back, and importing is a FAB menu (`ImportFabMenu`). **Wide windows** (≥ 840 ×
  480 dp — tablets, unfolded phones; like iOS's regular × regular) get a
  navigation rail with per-section stacks instead (`ui/NavigationLayout.kt`, the
  counterpart of the iPad sidebar); `stackForLayout` converts the stack when the
  window changes. List screens use `readableListPadding` so rows stay readable
  on wide windows.
- **PDF export:** what goes on which page (cover, latest results grouped by
  category, trend charts; per paper size) is `PdfReport` in `shared/`, so iOS's
  SwiftUI pages and Android's `PdfReportRenderer` produce the same document.
  `PdfReportRenderer.drawPage` works on any `Canvas`, which is how the JVM tests
  check every page stays above its footer (Robolectric has no `PdfDocument`).
- **Launcher shortcuts** are Android's counterpart of the iOS App Shortcut and
  Spotlight index: names only; a value's latest reading appears solely with
  Settings › Show Latest Value in Search (off by default). Shortcut intents carry
  a LOINC code, never a reading. Requests from shortcuts go through
  `AppViewModel.launchRequest` and wait for the main screen (like `SiriActionBridge`).
- **Top bars over a wash** use `washTopBarColors()`: clear when expanded, solid
  when content scrolls under (no backdrop blur on Android, so never translucent).
- **Launcher icon:** generated — run `python3 tools/generate_android_icon.py`
  (add `--preview DIR` for SVG previews). Background layer = gradient + card,
  foreground layer = heart, plus night variants and the themed-icon monochrome
  layer. Don't hand-edit the generated `ic_launcher_*` drawables.
- **Previews:** the same rule as SwiftUI — every composable screen ships
  `@Preview`s for its variations (empty/populated, light/dark, sizes).
- Build & test: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleRelease`
  (`androidApp/build/outputs/apk/release/`). CI: `build_android.yml`.
- **Robolectric tests** all run in one sandbox (`robolectric.graphicsMode=NATIVE`
  in `build.gradle.kts`): a second sandbox can't load the bundled SQLite again and
  the catalog would silently be empty. Use `waitFor` (`ComposeWait.kt`) rather
  than `waitUntil` for work on IO dispatchers; a text field inside a dialog never
  idles under Robolectric, so test such dialogs' logic in `shared/` instead.
- **Performance:** debug builds run StrictMode (logged). The emulator must use
  the host GPU (`-gpu host`): with software rendering every full-screen layer
  costs ~45 ms and frames/ANRs say nothing about the app. Measure with
  `adb shell dumpsys gfxinfo dev.idoodler.labimporter framestats`.
- **Kotlin names seen from Swift:** a function starting with `new`, `copy`,
  `alloc` or `init` is renamed by Kotlin/Native (e.g. `doNewX`) — avoid those prefixes in `shared/`.

## iOS share extension

`LabImporterShare` accepts **text** (selected in Mail, a patient portal, …);
PDFs and images already reach the app via "Open in LabImporter", so the
extension doesn't duplicate that. Share extensions may not open their app
(Apple documents that for Today widgets only), so the extension stores the text
in the App Group inbox (`ShareInbox`), asks the system to open
`labimporter://share/<id>` (declined for share extensions today) and otherwise
tells the user to open LabImporter, which imports the waiting text when it
becomes active (items expire after a day). The App Group
`group.$(BUNDLE_IDENTIFIER)` must exist in the developer portal (README).

## Build, run & lint

### Local (macOS, requires Xcode 26 / iOS 26 SDK)
```
open LabImporter.xcodeproj      # set your Development Team under Signing & Capabilities
```
Build/run on a physical Apple Intelligence device — the simulator and unsupported
hardware fall back to `UnsupportedDeviceView` since parsing needs the on-device model.

### SwiftLint — runs in CI and is REQUIRED before pushing
- Config: `.swiftlint.yml` (strict on PRs). Notable: `line_length` warn 160/err 200,
  `function_body_length` warn 60/err 120, opt-in `empty_count`/`explicit_init`,
  analyzer rule `unused_import`. `trailing_comma`/`comma` are disabled.
- Run it the same way CI does:
  ```
  swiftlint lint --strict
  ```
- A **pre-push hook** (`.claude/settings.json`) runs `swiftlint lint --strict`
  on `git push` and blocks the push on violations. In Claude Code on the web,
  the `SessionStart` hook auto-installs SwiftLint 0.63.2; locally it's assumed via brew.
- Prefer fixing violations over adding `// swiftlint:disable`. The existing
  `disable:next cyclomatic_complexity` on the big `LabMapping`/`ucum` switches is
  the accepted exception — match that style if you must.

## CI / release (Fastlane + Match, no Mac required)

GitHub Actions mirror the [Trio](https://github.com/idoodler-s-Diabetes-Management/Trio)
infrastructure and **reuse the same org-level secrets**. Workflows are numbered
and run in order on first setup:

1. **`validate_secrets.yml`** — checks `GH_PAT`, App Store Connect API key, and
   creates the private `Match-Secrets` repo. Reusable (`workflow_call`).
2. **`add_identifiers.yml`** — registers the app and share-extension bundle IDs
   and enables HealthKit, iCloud and App Groups (the App Group itself is a
   one-time manual step in the developer portal — see README).
3. **`create_certs.yml`** — creates/renews the Distribution cert + profile via
   Match; honors `ENABLE_NUKE_CERTS` / `FORCE_NUKE_CERTS` repo variables.
4. **`build_labimporter.yml`** — on `macos-26` with Xcode 26.2: lints, bumps the
   build number from the latest TestFlight build, builds, archives, uploads to
   TestFlight. Manual + scheduled (first Sunday monthly).
5. **`lint.yml`** — SwiftLint on every PR to `main` (and manual dispatch).

`build_android.yml` (every PR to `main`) runs the shared Kotlin tests, the
Android unit/UI tests, the translation check, Android lint and builds the APKs —
no secrets needed.

Not part of that numbered setup sequence, but also on every PR to `main` (and
manual dispatch): **`build_pr.yml`** — SwiftLint plus the iOS unit and UI
tests (`tools/ios_test.sh all`, which builds the app) on `macos-26` / Xcode 26.2
against the **Simulator SDK** with `CODE_SIGNING_ALLOWED=NO`. It calls no
Fastlane lane and needs **no secrets at all** — no App Store Connect API key,
no Match, no signing identity — so it works even before this repo's release
secrets are configured. It proves the Swift code compiles cleanly with the
pinned Xcode version and the tests pass; it does *not* produce a signed archive
or an `.ipa` (that needs real signing credentials — see
`build_labimporter.yml`). Use this to confirm a branch is sound before merging.

Fastlane lanes (`fastlane/Fastfile`): `build_labimporter`, `release`,
`identifiers`, `certs`, `validate_secrets`, `nuke_certs`,
`check_and_renew_certificates`. Match uses git storage
(`fastlane/Matchfile` → `Match-Secrets` repo). Ruby deps pinned in `Gemfile`
(`fastlane 2.231.0`).

Required secrets: `GH_PAT`, `TEAMID`, `FASTLANE_ISSUER_ID`, `FASTLANE_KEY_ID`,
`FASTLANE_KEY`, `MATCH_PASSWORD`. The bundle ID prefix `$(DEVELOPMENT_TEAM)` in
`Config.xcconfig` is substituted from `TEAMID` at build time.

## Working in this repo

- **Branch & commits:** never push to `main` unless explicitly told. Commit with
  clear messages; push with `git push -u origin <branch>`.
- **Adding a lab metric:** LOINC is the canonical identity, and names come from
  the bundled catalog (`LoincDirectory`) — there is no per-metric name curation
  and no abbreviation table to extend. The parser resolves any test the catalog
  knows by matching the AI's test name (in the report's language) against the FTS
  index, so a metric is "supported" as soon as its LOINC term is in `loinc.db`.
  If a real-world report consistently mis-resolves, prefer improving the
  `@Guide`/instructions in `LabParserService` (or widening the catalog) over
  reintroducing a hard-coded mapping. No `Localizable.xcstrings` change is needed
  — the catalog supplies localized names.
- **Touching the AI parse:** the instructions, prompt and guide texts are shared
  (`LabParsePrompt`/`LabParseSchema` in `shared/…/parse/LabParsing.kt`); the iOS
  `@Generable`/`@Guide` schema in `LabParserService.swift` repeats the guide texts
  verbatim and `ParseSchemaTests` fails if they drift. Keep `@Guide` descriptions
  concrete and example-driven — they directly steer extraction quality.
- **When the on-device model can't be used, say so — never guess.** iOS shows
  `UnsupportedDeviceView` on ineligible hardware and maps every other Foundation
  Models state to a clear message (`LabParserError`: Apple Intelligence off,
  still downloading, busy, too long…). Android mirrors this (see below). There
  is no rule-based fallback on either platform.
- **Migrating the exported CDA:** the read-back is version-gated (see "CDA
  round-trip"). Whenever you change what the export *means* — remap a LOINC code,
  change a unit convention, restructure observations — do all of:
  1. Bump `ReportSchema.VERSION` (`shared/…/export/ReportSchema.kt`, e.g. `1` → `2`)
     — it versions the iOS CDA documents and the Android FHIR records alike.
  2. Add a `ReportMigration` whose `fromVersion` is the *old* version and whose
     `migrate(report)` upgrades a parsed `LabReport` one step (e.g. rewrite an
     entry's `code`/`name`). Migrations run on the reconstructed `LabReport`, not
     raw XML/JSON.
  3. Register it in `ReportSchema.migrations` (the runner chains steps in order
     from a document's version up to the current `VERSION`).
  4. Optionally raise `ReportSchema.MINIMUM_SUPPORTED_VERSION` to drop very old
     schemas. Documents below the minimum — and unversioned legacy exports — are
     ignored on read-back by design (no implicit data migration).
  5. Re-record the golden CDA (`./gradlew :shared:jvmTest -Pgolden.record=true`)
     and review the diff — both test suites assert it.
- **New user-facing text:** always `String(localized:)`; add to
  `Localizable.xcstrings` and translate the key into **all 25 shipped languages**
  (see UI patterns) — never EN-only or EN+DE-only.
- **Bumping the version:** change `MARKETING_VERSION` in `Config.xcconfig` —
  the only place it's set. The iPhone app and its share extension take it from
  there, and Gradle reads it for Android (`versionName`; `versionCode` follows
  as MAJOR×10000 + MINOR×100 + PATCH, so minor and patch stay below 100). Never
  set it in the Xcode project (`tools/sync_xcode_project.rb` removes it). Build
  numbers aren't in that file: TestFlight's is set by CI (latest + 1).
- **README screenshots** live in `screenshots/ios`, `screenshots/ios-duo` and
  `screenshots/android` (the README shows each device in a collapsed
  `<details>`), always with made-up sample data, in English, light and dark,
  status bar at 9:41, scaled to half size (`sips --resampleWidth`).
  iOS: the DEBUG screenshot mode — `xcrun simctl launch <sim> <bundle id>
  -AppleLanguages "(en)" -AppleLocale en_US --ss --ss-screen
  dashboard|trends|review|history|settings|pdf` (sample history from
  `PreviewSampleData`), `simctl ui <sim> appearance light|dark`,
  `simctl status_bar <sim> override --time 9:41 …`; on an iPhone 17 Pro and an
  "iPhone Duo" (foldable) simulator (`simctl create "iPhone Duo"
  com.apple.CoreSimulator.SimDeviceType.iPhone-Duo <iOS 27.1 runtime>`, ~2 GB;
  its launches take ~20 s). Android: the emulator with a release build; import
  `screenshots/sample-reports.json` (the same reports as iOS's sample history)
  via Settings → Import Reports, `cmd uimode night yes|no`, the SystemUI demo
  mode for the status bar; the Review screen is a real read of a shared text
  report (Gemma on the emulator's CPU, ~1 min).
- **Before pushing:** make sure `swiftlint lint --strict` passes (the hook enforces it).
- **Xcode project:** don't hand-edit `project.pbxproj` for new files/targets — run
  `GEM_HOME=~/.gem/ruby/2.6.0 ruby tools/sync_xcode_project.rb` (needs the
  `xcodeproj` gem). It adds/removes Swift files, keeps the test targets and the
  share extension (embedded in the app) in shape, and the scheme's test action.
- **Tests** — run them before refactoring and after:
  - Shared Kotlin: `./gradlew :shared:jvmTest` (catalog, resolution, CDA golden,
    transfer, dashboard, number formatting — values pinned against iOS).
  - iOS: `tools/ios_test.sh unit|ui|all [simulator]` (Swift Testing unit tests
    incl. the Swift ⇄ Kotlin bridge, share inbox, parser errors; XCUITest launch).
  - Android: `./gradlew :androidApp:testDebugUnitTest` (JVM + Robolectric/Compose
    UI tests against the real LOINC catalog: AI gating, import flow, transfer,
    screens).
  Still verify AI changes on a real device — neither simulator nor emulator runs
  the production models.

## Privacy note

This is a health app. All lab processing is on-device by design. (The only
network use on Android is downloading an on-device model — Gemini Nano by the
system's AICore, or the pinned Gemma file the user chose to download, by the
system download manager — never lab data; `NetworkGuardrailTest` enforces it.)
Do not introduce network calls that transmit lab data, and don't weaken the "no
server" guarantee described in `README.md`.
