# CLAUDE.md

Guidance for AI assistants (and humans) working in this repository.

## What this is

**LabImporter** is a native iOS app that imports lab report values into Apple
Health using **on-device AI**. The pipeline is:

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
├── LabImporterApp.swift        # @main entry; routes to HomeView or UnsupportedDeviceView
├── Info.plist                  # usage strings, document types (PDF/image), HealthKit
├── LabImporter.entitlements    # com.apple.developer.healthkit
├── Localizable.xcstrings        # all user-facing strings (String(localized:))
├── Models/
│   ├── LabValue.swift          # editable in-memory value (used in Review flow)
│   ├── LabReport.swift         # a saved report (Codable); .asLabValues bridges to LabValue
│   ├── LabMapping.swift        # thin catalog adapter: LOINC code ↔ display name ↔ CDA export / loinc.org URL
│   └── LabDisplayPreferences.swift  # pinned/ordered/hidden + custom per-code names (RawRepresentable for @AppStorage)
├── Services/
│   ├── OCRService.swift        # actor; Vision text recognition + PDFKit rendering
│   ├── LabParserService.swift  # actor; Foundation Models @Generable structured parse
│   ├── HealthKitService.swift  # actor (.shared); read/write/delete HKCDADocumentSample + CDA XML parser
│   ├── CDAExportService.swift  # struct; builds C-CDA R2.1 Lab Report XML + UCUM unit mapping
│   └── LoincDirectory.swift    # Sendable singleton; in-memory index over the bundled LOINC catalog
└── Views/
    ├── HomeView.swift          # orchestrates the whole import flow + report loading
    ├── ImportLandingView.swift # empty-state entry points (scan/file/paste/manual)
    ├── DashboardView.swift     # metric cards w/ sparklines + status (uses Swift Charts)
    ├── TrendsView.swift        # per-metric interactive chart (Swift Charts)
    ├── HistoryView.swift       # list of saved reports
    ├── ReportDetailView.swift  # single report view/edit/share
    ├── ReviewView.swift        # review & correct parsed values before saving
    ├── LabValueRowView.swift   # row used in review/detail
    ├── CodePickerSheet.swift   # add/change a lab code
    ├── SettingsView.swift      # patient metadata + display preferences
    ├── DocumentScannerView.swift  # UIViewControllerRepresentable wrapping VNDocumentCameraViewController
    ├── WelcomeView.swift       # first-launch onboarding (gated by hasSeenWelcome)
    └── UnsupportedDeviceView.swift  # also defines enum DeviceSupport.isSupported

fastlane/        # Fastfile (build/release/certs lanes) + Matchfile (git storage)
.github/workflows/  # numbered setup workflows + lint + build
.claude/         # SessionStart hook (installs SwiftLint) + pre-push lint hook
Config.xcconfig  # BUNDLE_IDENTIFIER = dev.idoodler.$(DEVELOPMENT_TEAM).labimporter
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
  - `displayName(for:)` — the user's custom name for a code if they set one (in
    Sort & Visibility), else the localized catalog name, else the raw code. The
    custom name is a cosmetic display preference (`LabDisplayPreferences.customNames`,
    synced via iCloud) — it never reaches the exported CDA.
  - `loincCode(for:)` — validates the code + returns an English display for CDA
    export; **returns nil → value is not exportable** (unknown/unmapped code).
  - `loincURL(for:)` — the loinc.org details page for a code.
- **Import resolution (printed report → LOINC)** lives in
  `LabParserService.resolveLoinc`: an already-valid LOINC code passes through;
  otherwise the AI's test name is matched against the catalog **in the report's
  own language** (`LoincDirectory.search(_:language:…)`, English as fallback) and
  the top hit is offered as a suggestion (`isSuggestedCode`). There is no static
  abbreviation table — resolution is entirely catalog/FTS driven.

### CDA round-trip
- **Export:** `CDAExportService.generateCDA` emits a C-CDA R2.1 Lab Report. Only
  values that are `isSelected`, have a `numericValue`, and have a LOINC mapping
  are included. Units are normalized to UCUM via `ucum(_:)`.
- **Import-back:** `HealthKitService` reads `HKCDADocumentSample`s and
  `CDADocumentParser` (an `XMLParser` delegate) reconstructs `LabReport`s. The
  source filter restricts to this app's bundle ID + the default source.
- Document author/custodian are stamped as `LabImporter`; the parser ignores
  those sentinel values when reconstructing patient/author names.
- **Versioning:** every exported document carries a schema version in the
  authoring device — `manufacturerModelName` = `LabImporter <version> (<build>)`
  (provenance) and `softwareName` = `LabImporter CDA v<N>` where `N` is
  `CDAExportService.schemaVersion`. On read-back, `CDADocumentParser` parses `N`;
  `CDAMigrator.upgrade(_:fromSchemaVersion:)` **ignores** documents with no
  recognized version (legacy exports) and chains migrations up to the current
  schema. See "Migrating the exported CDA" below before changing the export.

### UI patterns
- All user-facing text uses `String(localized:)` / SwiftUI auto-localization and
  lives in `Localizable.xcstrings`. Add new strings there; the app ships German + English.
- **Naming the Health app:** never leave "Apple Health" untranslated. Every
  reference to the Health app must use the **on-device localized app name** for
  that language, keeping the **`Apple` brand prefix**. Canonical forms:
  `de Apple Health` · `fr Apple Santé` · `es Apple Salud` · `it Apple Salute` ·
  `pt-BR Apple Saúde` · `nl Apple Gezondheid` · `pl Apple Zdrowie` ·
  `ja Appleヘルスケア` · `zh-Hans Apple 健康` · `ru Apple Здоровье` ·
  `tr Apple Sağlık` · `uk Apple Здоров'я`. Inflect the localized noun for the
  surrounding grammar (pl/ru/uk case declensions, fr `d'Apple` elision, uk в/у
  euphony) — the `Apple` prefix stays invariant. Where the **English source**
  itself uses a bare "Health" (e.g. the `Save Reports to Health` toggle), mirror
  that and use the bare localized name without the prefix. `Health Records`
  (`Gesundheitsakte`, …) is a *different* Apple feature — leave it alone.
- OCR recognizes German + English (`["de-DE", "en-US"]`); narrative CDA labels are German.
- Preferences (`labDisplayPrefs`, `patientName`, `hasSeenWelcome`, etc.) are read
  via `@AppStorage`. `LabDisplayPreferences` is `RawRepresentable` over JSON — note
  the deliberate separate `Payload` type to avoid the Codable+RawRepresentable
  recursion trap (documented in the source; don't "simplify" it away).
- Reports are reloaded on `HomeView` `.task`, on returning from the review sheet,
  and on `didBecomeActiveNotification`.

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
2. **`add_identifiers.yml`** — registers the bundle ID and enables HealthKit.
3. **`create_certs.yml`** — creates/renews the Distribution cert + profile via
   Match; honors `ENABLE_NUKE_CERTS` / `FORCE_NUKE_CERTS` repo variables.
4. **`build_labimporter.yml`** — on `macos-26` with Xcode 26.2: lints, bumps the
   build number from the latest TestFlight build, builds, archives, uploads to
   TestFlight. Manual + scheduled (first Sunday monthly).
5. **`lint.yml`** — SwiftLint on every PR to `main` (and manual dispatch).

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
- **Touching the AI parse:** prompts and `@Generable`/`@Guide` schemas live in
  `LabParserService.swift`. Keep `@Guide` descriptions concrete and example-driven —
  they directly steer extraction quality.
- **Migrating the exported CDA:** the read-back is version-gated (see "CDA
  round-trip"). Whenever you change what the export *means* — remap a LOINC code,
  change a unit convention, restructure observations — do all of:
  1. Bump `CDAExportService.schemaVersion` (e.g. `1` → `2`).
  2. Add a `CDAMigration` conformer (in `HealthKitService.swift`) whose
     `fromVersion` is the *old* version and whose `migrate(_:)` upgrades a parsed
     `LabReport` one step (e.g. rewrite an entry's `code`/`name`). Migrations run
     on the reconstructed `LabReport`, not raw XML.
  3. Register it in `CDAMigrator.migrations` (the runner chains steps in order
     from a document's version up to the current `schemaVersion`).
  4. Optionally raise `CDAMigrator.minimumSupportedVersion` to drop very old
     schemas. Documents below the minimum — and unversioned legacy exports — are
     ignored on read-back by design (no implicit data migration).
- **New user-facing text:** always `String(localized:)`; add to `Localizable.xcstrings`.
- **Before pushing:** make sure `swiftlint lint --strict` passes (the hook enforces it).
- **No tests exist** in the project today; verify changes by building in Xcode on
  a supported device and exercising the import flow.

## Privacy note

This is a health app. All lab processing is on-device by design. Do not introduce
network calls that transmit lab data, and don't weaken the "no server" guarantee
described in `README.md`.
