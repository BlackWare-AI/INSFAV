# INSFAV

Independent Android collection browser based on the supplied HTML/CSS/JavaScript UI. Not affiliated with Instagram or Meta. Application ID: `com.instagram.insfav`. Minimum Android 8.0 (API 26), target/compile API 35. The main reference device is Galaxy A72 / Android 14.

## Build

Install JDK 17 and Android SDK Platform 35 / Build Tools 35.0.0. Open the root directory in Android Studio, allow Gradle sync, then build:

```sh
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug
```

Windows: use `gradlew.bat`. Gradle 8.13 and Android Gradle Plugin 8.9.1 are pinned. The app has no third-party runtime library dependency. JavaScript and CSS are bundled locally, including licensed Inter fonts. Network access is unnecessary for the collection UI after installation.

`assembleRelease` uses R8 and resource shrinking. Without signing environment variables, Gradle produces an unsigned release. To sign your release, set these variables privately:

- `INSFAV_KEYSTORE`: absolute path to your PKCS12/JKS file
- `INSFAV_KEY_ALIAS`: defaults to `insfav`
- `INSFAV_STORE_PASSWORD`
- `INSFAV_KEY_PASSWORD`: defaults to the store password

Never commit these values or the signing key. The delivered release uses the separately delivered signing kit. Keep that key for future updates. Debug and release use the same application ID and different signatures, so switching between them requires uninstalling the other build. Export your JSON first; Android uninstall removes private app data.

### SDK-only fallback

If Gradle dependency repositories are inaccessible, this project also includes an independently verified SDK build path:

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
python3 tools/build-sdk.py
python3 tools/build-sdk.py --release
```

Outputs: `app/build/sdk/debug/INSFAV-debug.apk` and the equivalent release directory. This path uses AAPT2, javac, D8 / R8, zipalign and apksigner from Build Tools 35.0.0. Release signing uses the same environment variables. It performs code shrinking through R8; Gradle additionally provides resource shrinking. There are only two application XML resources, both used, so no large unused resource set is bundled.

## Use

1. Tap `+` to import a JSON document with the Android system picker.
2. Tap a collection header to expand or collapse it. Search by display name or username.
3. Tap an account name to open Instagram, falling back to its HTTPS profile page. Tap a cached image to open the separate fullscreen viewer.
4. Tap the information button, then **Settings**, for retrieval, storage, JSON export and diagnostic logs.

### Original data preservation

Accepted documents: a collection array, an object with an array `favorite_page_pref`, or an object with a JSON-encoded string in that field. Collection entries require `name` and `items`; account entries require a string `pageId` and an optional string `name`. The original import text is saved atomically and exported unchanged, including unrelated preferences, unknown fields, original casing, ordering and repeated collection memberships. Search indexes and image identities are separate derived data. Invalid imports do not replace the stored document. Import size is limited to 32 MiB.

Do not put personal backups, usernames, screenshots, private logs or signing credentials in this public repository. Tests contain anonymous fixtures only. The actual supplied backup was checked privately and is not included.

### Profile retrieval limitations

`ProfileProvider` uses only cookie-free, public HTTPS pages; no Meta key, login, private endpoints, CAPTCHA bypass or hidden session data. `ProfileParser` requires matching `og:url` and account identity in `og:title`, plus an HTTPS image from an allowed Instagram CDN. Generic logos, static assets, malformed images and ambiguous pages are rejected. Images must also pass bounded decoding validation. Generic HTTP 404 responses do **not** prove account deletion and remain missing-data failures. A confirmed-unavailable status is reserved for a provider that can establish that fact reliably.

In the real small-scale tests on two public profiles, HTTP 200 pages contained no `og:image`, so real login-free image retrieval was **not successful in this environment**. The downloader is implemented, but reliable access to actual photos is not claimed. Website changes or restrictions may require replacing the provider. The application remains useful for all collection and JSON operations without images.

Retrieval starts only from Settings. One profile is processed at a time, with a persisted random 6–25 second interval between profile attempts. One profile attempt can include its HTML request and its validated CDN image request. Shared serialization also applies to fullscreen fetches. Existing images are skipped except during an explicit refresh. Failed-only retrieval skips profiles with an existing image. HTTP 429 respects `Retry-After`; access restrictions/login requirements pause retrieval. Three consecutive other failures also pause it to avoid thousands of useless requests. Resuming is a deliberate user action. These delays do not guarantee that Instagram will allow access.

The foreground service saves cursor, counters, failures, cooldown and work state. Pause and cancel are cooperative; an in-flight request may finish or reach its bounded timeout before stopping. Process death leaves a resumable queue; reopening converts interrupted work to paused state and does not restart network work. Android may suspend/stop the service and modern Android versions impose data-sync time limits. `onTimeout` checkpoints and stops work; return to the app to resume manually. No boot receiver or exact alarm is used.

A fullscreen viewer shows the 128 px cached thumbnail immediately and requests available larger metadata images after a random 2–6 seconds. It does not manufacture a Full HD URL or upscale a tiny image into a claimed high-resolution photo. Existing temporary larger images are reused. New larger-image requests are skipped while batch retrieval is active. Pinch zoom, double tap and panning are implemented. No image-save button is provided.

### Storage and privacy

- Original JSON, queue, profile status and 128 × 128 WebP thumbnails: private `filesDir`, persistent across normal updates signed with the same key.
- Larger images: only the temporary `cacheDir/full-resolution` directory, bounded to 2048 px, trimmed to 32 MiB / seven days after writes, manually clearable and eligible for Android eviction.
- Cache clearing never removes the JSON collection document. Collection clearing cancels pending retrieval but leaves image caches separate.
- Diagnostic logs: two rotated files of approximately 256 KiB each. Logs contain event/error codes without usernames, original JSON, request URLs, cookies or API keys.
- A local HTTPS origin is served by WebView request interception. File/content access, remote scripts, cookies and remote WebView navigation are disabled. Instagram is opened in an external app/browser.
- Native window insets reserve status bars, cutouts, gesture/navigation area and the keyboard. The page has a pure-black background and original rounded rectangular image boxes.

## Tests

```sh
node --test tests/core.test.cjs
javac -d /tmp/insfav-test app/src/main/java/com/instagram/insfav/ProfileParser.java tests/java/com/instagram/insfav/ProfileParserTest.java
java -cp /tmp/insfav-test com.instagram.insfav.ProfileParserTest
```

For browser integration tests, install Playwright outside the runtime application and set `CHROME_BIN` to your Chromium executable:

```sh
CHROME_BIN=/path/to/chromium node tests/browser.cjs
```

The optional `PRIVATE_JSON` variable points to a local backup for private verification; it is never printed or copied into the project. `UI_SCREENSHOT` optionally writes a screenshot of anonymous test data only. Browser tests emulate native bridge responses: they verify UI dispatch and rendering, **not** execution of Android services or real image retrieval. Device testing is still required for actual service lifecycle, notifications, background power restrictions, OEM keyboard/insets behavior and APK installation.

The JavaScript tests cover schema validation, lossless preservation, search, memberships, image deduplication and bounded virtual windows at 1,000/5,000/10,000 accounts. JVM parser tests cover identity matching, logo rejection, CDN restrictions and ambiguous pages. CI builds debug/release and runs lint and JavaScript tests on GitHub Actions. A neutral temporary icon is provided in `ic_app.xml` for later replacement.
