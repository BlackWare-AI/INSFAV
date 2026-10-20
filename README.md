# INSFAV

INSFAV is an independent Instagram account collection manager by **BlackWare**, with **OpenAI ChatGPT** providing AI development assistance. It is not affiliated with Meta or Instagram.

Version/build are defined in `version.properties` and read from the actual Android package by the About screen. Application ID: `com.instagram.insfav`. Minimum Android 8.0 / API 26; compile and target API 35.

## Local profile images and optional Thumbnail Fetcher

INSFAV keeps its local SAF thumbnail catalog and adds an optional session-based Thumbnail Fetcher in Settings. Existing collections, JSON import/export, counters and offline image browsing remain available. See [FETCHER-INTEGRATION.md](docs/FETCHER-INTEGRATION.md) for the imported motor, setup, queue states, shared request broker, storage permissions and testing limits.

1. Open **Application Information → Settings → Select Thumbnail Folder**.
2. Select `.thumbnails` using the Android system folder picker. If your picker hides dot folders, select its parent; INSFAV recognizes a direct `.thumbnails` child automatically.
3. Grant read access. INSFAV persists the SAF read grant and scans the folder without modifying source files during scanning. The optional fetcher writes only validated thumbnails after write authorization.
4. Use **Rescan Thumbnails** after changing the folder contents. Scrolling never rescans the directory.

```text
.thumbnails/
  example.user.webp
  second_user.jpg
  third_user.JPEG
  another_user.PNG
```

Names are trimmed, one leading `@` is removed, and matching is case insensitive. Periods within usernames are preserved. Supported extensions are `.webp`, `.jpg`, `.jpeg`, and `.png`. Only direct children of the chosen folder are scanned. Filename mapping is sufficient; a `manifest.json` is not required and is currently ignored. Account JSON records do not need image-path fields.

Each candidate is checked with bounded image decoding. Malformed, unsupported, oversized and unreadable files do not crash the application. Missing photos use a gray person glyph in the original 40 dp rounded rectangular box. Lists decode 128 px center-cropped thumbnails. Fullscreen uses the same selected local source, retains its aspect ratio and supports pinch zoom, double tap, pan and Back. Source images up to 2048 px are shown at their available decoded resolution; larger images use a memory-bounded preview up to 2048 px, explicitly labeled. Fullscreen previews are encoded losslessly in memory and are not saved to permanent storage. No save-photo button or artificial Full HD source is provided.

If duplicate candidates normalize to the same username, readable images win; then higher pixel area, WebP/JPEG/PNG preference at equal resolution, exact filename and URI provide deterministic tie breaking. Provider enumeration order does not change the choice.

### Statistics

- **Total Accounts**: all collection memberships, including repeated accounts.
- **Unique Accounts**: normalized nonempty usernames in the current JSON.
- **Matched Images**: unique current accounts whose indexed selected candidate passed validation and whose folder remains accessible.
- **Missing Images**: unique current accounts minus matched accounts; includes accounts with only unreadable candidates.
- **Unreadable Images**: candidate files that failed validation across the whole scanned folder, plus selected files found unreadable during later access.
- **Duplicate Image Candidates**: extra files beyond the first candidate for a normalized username across the folder, including invalid candidates.
- **Indexed Folder Size**: sum of known sizes of supported-extension source files. Unknown sizes are marked separately. This is not app cache usage.
- **Last Scan Date**: last successfully committed complete scan.
- **Selected Folder**: friendly name and provider document ID. Folder URIs are never written to diagnostic logs.

Counts describe the last committed index, not a continuously watched filesystem. If files change, rescan. A lost grant, removed/moved folder or unavailable provider is reported in Settings; reselecting the folder restores access when available. Android's folder picker restrictions still apply: no access is requested to restricted system directories, storage roots or other apps' private data.

**Clear Image Cache** removes only app-owned disposable display files, including former v3.0 image-cache files. **Clear Thumbnail Index** removes only the app's mapping and display cache; it keeps the selected folder and its read grant. Neither operation deletes source photos or the collection JSON. Both require confirmation. Folder replacement invalidates the old mapping and releases the old read grant after the new selection is saved. Scans are foreground, cancellable with Activity destruction and transactional: an interrupted rescan preserves the last completed index for the same folder; it never publishes a partial catalog. Rescan manually after interruption.

## Collection data and migration

Import accepts a collection array or an object whose `favorite_page_pref` is an array or a JSON-encoded collection string. Original import text is retained atomically and exported unchanged, including unknown fields, preferences, ordering, case and multi-collection memberships. Invalid input does not replace the existing document. Import size is limited to 32 MiB.

Updating a release signed with the same key preserves `document.json`, usage/import/reset counters and settings. Former retrieval queue, cooldown and failure-state files are removed; no retrieval can resume. The old `thumbnails` private files are not used as external sources and can be removed with explicit **Clear Image Cache**. The `.thumbnails` directory belongs to the user and is never written by INSFAV.

Collection management currently means importing/exporting backups, browsing, expanding/collapsing collections and resetting the imported document. Arbitrary manual account/category editing is not presented as an implemented capability.

## Architecture

- `MainActivity`: protected local WebView, native window insets, read-only SAF folder selection, JSON system pickers, app metadata, external profile intents and bounded task lifetimes.
- `Store`: atomic original JSON, preferences, index metadata and rotated redacted diagnostics.
- `LocalImages`: transactional SAF scan, persisted URI/index, permission checks and a disposable 24 MiB thumbnail cache (trimmed to 18 MiB on overflow).
- `ImageCodec`: validated, bounded local decoding and prompt stream/bitmap cleanup.
- `ThumbnailPolicy`: Android-independent identity, duplicate ordering and statistics policy.
- `web/`: readable UI source. `app/src/main/assets/`: reproducible minified runtime bundles, fonts and supplied logo.

The application has no third-party runtime dependency. JUnit/Robolectric and npm packages are development-only. About credits do not imply ownership by OpenAI. The original supplied logo is retained in `artwork/` and packaged into launcher, adaptive and in-app assets through normal sizing, without redesigning it. `tools/prepare-icons.py` (optional Pillow development tool) reproduces the lossless WebP sizes and validates decoded pixels.

## Build

Install JDK 17, Android SDK Platform 35 / Build Tools 35.0.0, then open the project root in Android Studio:

```sh
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug :app:testDebugUnitTest
```

Windows: `gradlew.bat`. Gradle 8.13 / AGP 8.9.1 are pinned. Generated web bundles are committed, so Android Studio does not require Node to build. To edit the UI, edit `web/`, then run:

```sh
npm ci --ignore-scripts
npm run web
```

Pinned Terser/CleanCSS tooling preserves global bridge bindings and creates source maps outside the APK. CI regenerates bundles and rejects drift. R8 and resource shrinking are enabled for release. Never edit generated assets directly.

Release signing uses private environment variables `INSFAV_KEYSTORE`, `INSFAV_KEY_ALIAS` (default `insfav`), `INSFAV_STORE_PASSWORD`, and `INSFAV_KEY_PASSWORD` (defaults to store password). Without them Gradle produces an unsigned release. The supplied release uses the same private release key as v3.0, allowing a normal update. Debug and release signatures differ; export data before uninstalling to switch variants.

The alternative `tools/build-sdk.py` uses AAPT2, javac, D8/R8, zipalign and apksigner directly with `JAVA_HOME` and `ANDROID_HOME`; add `--release` for R8. It reads the same version properties and supports the same signing variables. Gradle additionally performs resource shrinking.

## Verification

```sh
node --test tests/core.test.cjs
javac -d /tmp/insfav-policy app/src/main/java/com/instagram/insfav/ThumbnailPolicy.java tests/java/com/instagram/insfav/ThumbnailPolicyTest.java
java -cp /tmp/insfav-policy com.instagram.insfav.ThumbnailPolicyTest
CHROME_BIN=/path/to/chromium node tests/browser.cjs
```

Browser tests use a simulated Android bridge and anonymous data. `PRIVATE_JSON` optionally verifies a local private backup without printing/copying its contents; `UI_SCREENSHOT` writes an anonymous test screenshot. They do not prove physical device behavior.

Robolectric tests run the actual storage, scan and image modules against an isolated SAF provider at simulated Android API 28 and 34. They cover JSON preservation, duplicate matching, malformed images, read-grant persistence/loss, folder replacement, hidden-child selection, cache/index isolation and absence of directory rescans on image access. Real Samsung/system-picker behavior, USB/cloud providers and physical-device frame pacing still require device testing.

Do not commit personal backups, usernames, private logs or signing keys. Diagnostic logs are limited to two approximately 256 KiB files and omit document URIs, account names and original JSON.

Android SAF references: https://developer.android.com/training/data-storage/shared/documents-files and https://developer.android.com/reference/android/provider/DocumentsContract .

### Application Information

Total Accounts, Usage Count, and Load Count use the same compact rows as version/build metadata. Features & Functions opens its own AMOLED screen; Android Back or its toolbar back button returns to the existing About view. Counter sources, successful-import increments, startup increments, and Android preference keys are unchanged.

For the presentation regression, extract the previous packaged assets from commit `68717797719c11e77bb059af05c582f45612c16e`, then run `BASELINE_ASSETS=/absolute/baseline/assets CHROME_BIN=/path/to/chrome node tests/about-counters.cjs`. `BASELINE_REF` optionally selects that same previous commit for native source identity checks. These are browser bridge tests, not physical device persistence tests.
