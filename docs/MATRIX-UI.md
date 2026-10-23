# INSFAV 5.0.0 / build 10: matrix, scrolling and explicit retrieval

This change preserves the application ID, version/build, signing identity, About modal, list row/folder geometry, Favorites JSON and thumbnail storage/index formats. Matrix is a display option, not a new thumbnail file format; existing 128/256 WebP files remain usable. No rescan is required just to change layout.

## Display and persistence

Settings has an EN/TR selector at the top-right and List / Matrix 2 / Matrix 3 / Matrix 4 / Matrix 5 at the top. `UiPreferences.read/save` validates and commits these choices in app-private SharedPreferences (`insfav-ui`), independently of Favorites, SAF permissions, encrypted cookie storage and the persistent image index. Defaults remain English and List. These new display preferences are local; existing encrypted settings-backup schema is unchanged.

`core.rowsFor` chunks each open/search-expanded folder into virtual matrix rows. The cell side is `(listWidth - 18 - 8*(columns-1))/columns`. Each row reserves image + padding + a measured username line, including enlarged system text. Incomplete final rows keep the same column sizing. `app.makeRow` keeps the existing image viewer and profile-opening actions, shows only `@username` below each matrix image and uses ellipsis. `changeLayout` anchors the visible account when columns/width change. Every mounted avatar in a matrix row participates in the existing bounded image-loading pool; recycled rows release all their images. List mode retains 68px rows and 67.5px avatars.

`i18n.js` translates presentation strings in Settings, retrieval, search and notifications. It never changes user data, queue codes or diagnostics. Language and view choices use a separate AMOLED picker, never the native select popup. The missing-avatar symbol is 27.6px (20% larger) and the Missing Images copy SVG is 18×26px inside its unchanged 28×28px control. The Application Information modal and its content are excluded. Technical codes/identifiers and unrecognized provider messages remain unchanged. Language selection persists in native preferences; it does not enable network operations.

## Floating scrolling controls

`mainScroll` overlays the right edge without resizing the list. Buttons are 26×34px with transparent interiors and animated appearance. Jump scrolling uses a bounded 240–650ms animation, cancellable by new input. The thumb has a 42px minimum height and a 15px grip extending left; its shaft width is now 7.5px (50% smaller). The custom rail is not painted and native WebView scrollbar painting is disabled only while these main-list controls are available. Controls fade after 480ms without input; scrolling/touch restores them. A new 100ms hold is required for every drag. Movement before activation, viewport changes, modal opening, pointer cancellation and release cancel the hold. Glow indicates an armed drag. Bounds derive from actual document height. Start/end retains the existing short settling step after animation that prevents residual touch fling from moving away from the target.

## Explicit Fetcher start

Code review identified persisted `armed` state plus recovery/replan paths, sticky-service recreation and the no-eligible-accounts branch as possible causes of unsolicited work/notifications. A specific user's device event has not been reproduced from supplied diagnostics.

`WorkScheduler.enabled` requires a token belonging to the current process, created only by an explicit run action; old persisted flags are insufficient even before Application.onCreate finishes. `Fetcher.initialize` now revokes persisted run authorization via `ManualRun.reset` and cancels stale scheduled work. `FetchService.onStartCommand` rejects null/old-process intents before entering foreground and returns START_NOT_STICKY. Explicit requests carry the current process token. Manual Start Missing, Resume and Retry retain their normal authorization; verification remains an explicit action. A manually started background batch can continue within its current process/schedule. After process death/reboot, the queue is retained and the user must select Start Missing or Resume. `QueueEngine.run` disarms when no eligible work remains, including saved Retry Later outcomes, instead of leaving recovery enabled for completed work. Successful/skipped/failed records are not reset or requeued by these changes.

## Verification

Targeted coverage: `UiPreferencesTest`, `ManualRunTest`, matrix cases in `core.test.cjs`, `matrix-language.cjs`, updated real-pointer `scroll-controls.cjs`, and Android WebView `ScrollDeviceChecks` with actual emulator touch at system font scales 1.0/1.3/1.5. Matrix tests use synthetic profiles and placeholder/fixture images, not Instagram. Unit tests and lint must pass before publishing. No physical-device or live Instagram test is claimed. Exact run results, source commit and APK hash are recorded in the downloadable release report and verification.json.
