# INSFAV

**Required:** A JSON backup exported from SamMods InstaPro version 15.30 or earlier. INSFAV reads and displays the `favorite_page_pref` (Favorites) section. Without a compatible backup, the app cannot show your InstaPro favorites.

[Download the signed INSFAV 5.0.0 release](https://github.com/BlackWare-AI/INSFAV/releases/latest).

INSFAV is a local Android viewer and organizer for Instagram profiles saved in an InstaPro Favorites backup. It presents the original collections in an AMOLED-black interface, with search, account counts, optional local profile pictures and a high-resolution image viewer. It does not access your InstaPro installation or synchronize favorites with Instagram.

## What it does

- Imports a compatible InstaPro Favorites JSON backup and preserves the original JSON for export.
- Shows favorite collections, saved profiles, search, collection membership and account statistics.
- Displays local profile photos stored in a user-selected `.thumbnails/<username>.webp` folder using Android's Storage Access Framework (SAF).
- Offers an integrated, optional Thumb Fetcher for collecting profile images, with a persisted queue, pause/resume, progress notifications and Android-permitted background processing.
- Opens a profile image locally first, then attempts to retrieve the original Instagram image for the in-memory HD viewer, where available. The HD image is not permanently saved.

## How to use

1. In SamMods InstaPro 15.30 or earlier, export its settings/backup as a `.json` file. The backup must contain the `favorite_page_pref` Favorites data. An arbitrary Instagram data export or a newer incompatible backup is not a substitute.
2. Open INSFAV and choose **Import JSON**. Your Favorites collections and accounts appear.
3. Browse the collections, search for profiles and open their links.
4. Optional local avatars: select the `.thumbnails` folder in Settings, grant read access, then tap **Rescan Thumbnails**.
5. Optional Thumb Fetcher: grant the chosen folder write access, configure Instagram cookies and concurrency in Fetcher settings, then start the missing-images queue. Instagram authentication, restrictions and connectivity may limit retrieval.
6. Export your collection JSON as a backup whenever needed.

### Read-only thumbnail rescan

Rescan enumerates the folder once to discover added, changed and removed entries. A private SQLite checkpoint stores every candidate, including duplicates and validated unreadable images. Reliable, unchanged size and modification time allow reuse without opening or decoding images. Missing provider metadata requires validation again. Small thumbnails are opened once for bounded header and pixel validation; at most two validation workers run concurrently. Completed batches survive interruption; retry re-enumerates metadata and reuses saved work. The existing JSON index remains available until a full scan finishes. Rescan never writes, renames or deletes source files, even with write permission. See [rescan measurements and limitations](docs/RESCAN-PERFORMANCE.md).

## Requirements and privacy

- Android 8.0 (API 26) or newer.
- A compatible InstaPro `.json` backup is required to view favorites.
- Local image folders are user-selected; scanning is read-only. The Fetcher writes only when explicitly granted access.
- Original source thumbnails are not re-encoded into a second private thumbnail archive.
- HD photos are kept in memory, not saved by INSFAV.
- The Fetcher depends on Instagram availability and account access; live retrieval and uninterrupted background work are not guaranteed. Android Force Stop and operating-system restrictions still apply.
- INSFAV is an independent project and is not affiliated with Instagram, Meta or SamMods.

## Open source and development

This public MIT-licensed repository can be forked to study, modify and extend INSFAV. Third-party contributions are welcome through pull requests. Read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting changes.

The current published application is **INSFAV 5.0.0, build 10**. The current source is preserved on the `preservation/current-main-2026-10-24` branch. The earlier pre-4.0.0 mainline is preserved on `backup/pre-4.0.0-main-2026-10-21`. Published release history and assets are retained.

Build using JDK 17, Android SDK Platform 35 and the Gradle wrapper:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleRelease
```

The `release` build requires the original private signing key to produce an installable, update-compatible APK. Configure `INSFAV_KEYSTORE`, `INSFAV_STORE_PASSWORD`, `INSFAV_KEY_ALIAS` and `INSFAV_KEY_PASSWORD` locally or as secured CI secrets. **Never publish the keystore or passwords.** Unsigned APKs are not an installable public release, and replacing an installed APK signed with another certificate is not a safe in-place upgrade.

For the guarded GitHub Actions publication workflow, required secret names, certificate pinning and verification steps, see [Official release signing](docs/RELEASE-SIGNING.md). Missing configuration blocks publication; the workflow never generates a replacement key or claims compatibility with an unverified previous APK.

Source files: `web/` (UI), `app/src/main/java/` (Android, SAF and Fetcher), `app/src/test/` and `tests/` (verification). Web bundles in `app/src/main/assets/` are generated via `npm ci --ignore-scripts && npm run web`.

For technical verification and limitations, see [Thumbnail verification](docs/THUMBNAIL-VERIFICATION-TR.md) and [Fetcher integration](docs/FETCHER-040.md).
