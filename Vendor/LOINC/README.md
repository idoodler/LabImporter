# LOINC source data

Drop the official LOINC distribution archive here as:

```
Vendor/LOINC/LOINC.zip
```

This is the **raw source data**, not the file that ships in the app. A build/dev
step will extract just the columns we need (LOINC_NUM, LONG_COMMON_NAME,
SHORTNAME, COMPONENT, …) from `LoincTable/Loinc.csv` inside the zip and emit a
compact resource that gets bundled into the app target. The full uncompressed
table is far too large to embed verbatim.

LOINC is © Regenstrief Institute, Inc. and distributed under the LOINC license.
Keep the license/notice files from the archive intact.
