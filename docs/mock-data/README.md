# Mock lab data

Sample lab reports for trying out LabImporter, capturing screenshots, and
demos. All values are **fictional** (patient *Max Mustermann*) and use the
German abbreviations the parser recognises out of the box
(`LabMapping.loinc(forPrinted:)`).

## How to import

1. Open one of the `.txt` files and copy its full contents to the clipboard.
   - On a Mac with the Simulator: `pbcopy < docs/mock-data/04_2026-05-06.txt`
   - On device: AirDrop / Notes / Mail the file to yourself and copy the text.
2. In the app, tap **Paste from Clipboard** on the import screen.
3. The on-device model parses the values — review them, then **Save to Health**.

> Pasting needs an Apple Intelligence–capable device (parsing runs the
> on-device Foundation Models model). On the Simulator / unsupported hardware
> the app shows *Device Not Supported*.

## The files

| File | Draw date | Story |
|---|---|---|
| `01_2025-01-15.txt` | 15 Jan 2025 | Baseline — HbA1c, glucose, LDL and triglycerides all **elevated** |
| `02_2025-06-10.txt` | 10 Jun 2025 | Improving |
| `03_2025-11-20.txt` | 20 Nov 2025 | Near normal |
| `04_2026-05-06.txt` | 06 May 2026 | Back in range |
| `quick-paste-single-line.txt` | 06 May 2026 | One-liner in the `CODE: value unit;` form, for a fast single import |

Import all four full reports (oldest first) to get a populated **History**,
multi-point **Trend** charts, and **Dashboard** cards that visibly move from
borderline/elevated down into the normal range — ideal for screenshots.

## Metrics included

HbA1c, blood glucose, total cholesterol, LDL, HDL, triglycerides, creatinine,
eGFR, GPT/ALT, gamma-GT and TSH — each maps to a LOINC code, so every value is
exportable to Apple Health as part of the CDA document.
