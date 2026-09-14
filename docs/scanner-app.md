# Image To Text Scanner (`:scanner`)

A standalone, fully offline OCR scanner app for Android, built to the
"AI Image To Text Scanner" spec. It installs as its own app (separate from
the Maya assistant) with a blue `#1469E8` / white Material 3 UI.

## Install

```bash
# from the project root (Maya workspace)
env -u ANDROID_SDK_HOME ANDROID_USER_HOME="E:/Android/dotandroid" \
  ./gradlew --project-cache-dir C:/maya-build/project-cache :scanner:assembleDebug
adb install C:/maya-build/scanner/outputs/apk/debug/scanner-debug.apk
```

> The `env -u ANDROID_SDK_HOME ...` prefix works around conflicting
> `ANDROID_SDK_HOME` / `ANDROID_USER_HOME` values set on this machine
> (AGP 9 refuses ambiguous preference paths). Consider unsetting
> `ANDROID_SDK_HOME` globally to drop the prefix.
> The `--project-cache-dir` flag keeps Gradle caches off OneDrive, whose
> sync locks otherwise corrupt them.

## Features

| Spec item | Implementation |
| --- | --- |
| Camera scanner | CameraX full-screen preview, capture button, torch, framing guide |
| Gallery import | Up to 20 images, batch OCR, combined into one document |
| PDF extraction | `PdfRenderer` → bitmaps → OCR (max 20 pages, ~160 dpi) |
| Handwriting mode | Camera flow tagged `handwriting` with dedicated messaging |
| Edit before OCR | Brightness/contrast sliders, sharpen, shadow removal, B&W, rotate |
| OCR | ML Kit bundled recognizers: Latin, Devanagari, Chinese, Japanese, Korean; auto mode falls back across scripts and keeps the richest result |
| Result screen | Editable text, word/char count, name field, Copy / Share / Save / Export PDF |
| Translation | ML Kit on-device translate: en, hi, fr, de, es, it, zh, ja — models download once, then work offline |
| History | JSON-file store: open, rename, share text, export PDF, delete, clear all; thumbnails + word counts + dates |
| Settings | OCR script preference, default translation language, dark mode, auto-enhance toggle, privacy note, about |
| Privacy | No login, no account, no cloud; everything stays in app storage |

## Architecture

```
scanner/src/main/java/com/amayra/scanner/
├── MainActivity.kt          # Compose NavHost over 7 routes
├── ScannerViewModel.kt      # OCR job pipeline (ScanJob state machine)
├── PdfExtractor.kt          # PdfRenderer → bitmaps
├── data/
│   ├── ScannerDatabase.kt   # JSON-file doc store (atomic writes, StateFlow)
│   ├── SettingsStore.kt     # DataStore preferences
│   └── Exporter.kt          # TXT writer, pure-Kotlin PDF writer, share intents
├── ocr/
│   ├── OcrEngine.kt         # ML Kit wrapper + auto script fallback
│   └── ImageEnhancer.kt     # grayscale/contrast/sharpen/shadow-flatten/threshold
├── translate/
│   └── TranslationEngine.kt # ML Kit translate with friendly download errors
└── ui/
    ├── ScannerNav.kt        # Home, Camera, Edit, Result, Translate, History, Settings
    └── theme/Theme.kt       # brand palette, light + dark schemes
```

Persistence follows the Maya app's established pattern (JSON tables with
mutex + atomic rename) because AGP 9's built-in Kotlin can't apply KSP for
Room codegen.

## Tests

`scanner/src/test/java/com/amayra/scanner/ScannerLogicTest.kt` — 8 JVM unit
tests covering word counting, OCR script parsing and the translation
language table. Run with `:scanner:testDebugUnitTest`.
