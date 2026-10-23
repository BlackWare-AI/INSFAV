# AMOLED main list and finite scroll controls

This change keeps INSFAV 5.0.0 / build 10 and the existing native thumbnail/index/Fetcher implementation. All control dimensions below are CSS pixels.

## Presentation

Main-list folder and account frames use black (#000), a single 1px #111 border and 26px outside corners. An expanded folder supplies the top corners; its last account supplies the bottom corners. Interior rows have no corner rounding or top border. The original 68px rows, 67.5px avatars with 14px corners, horizontal padding, fonts, navigation and 14px inter-folder spacing are preserved. A 20px column gap inside folder headers reserves space for the rotated arrow when long names and large fonts would otherwise collide with the count; neither the arrow anchor nor the text's left padding changes.

The controls use the existing right gutter without changing list width. On a phone the 15px gutter contains an 11px hit area, with 2px clearance on each side. The thumb artwork is 7px wide. Buttons are fixed above the track; the track ends 12px above the bottom navigation overlay. Controls hide on pages too short to scroll, when all folders are closed, or while an app overlay is open. Search-expanded accounts use the same controls. The old root scrollbar's paint is transparent only while the custom control is visible; no scrollbar width or display rule is changed.

## Interaction and data flow

`web/app.js` creates one `mainScroll` controller. `rebuild()` cancels an old gesture and updates whether account rows exist. `renderVisible()` updates the controller using the real document scroll height and viewport, within the existing animation-frame schedule. Folder toggles, filtering and viewport changes recalculate the finite range. The virtual row heights, offsets, overscan, row pool and image pool are unchanged.

The up/down buttons go to the beginning/end of the entire page, including every open folder. Pointer-up also handles touch buttons because Chrome suppresses click for the tap that stops an active fling; keyboard activation retains normal click behavior. A jump settles for at most eight frames/150ms to absorb a final compositor scroll update; fresh input cancels this immediately. Endpoints disable their corresponding button.

Thumb states are idle, holding and armed. A primary pointer starts a 3000ms timer. Before activation, movement over 8px cancels rather than moving the page. Activation shows a light glow and records the pointer and scroll origins, so arming does not jump. After activation, captured pointer movement maps proportionally to the finite track travel, clamped to the page bounds. Releasing, pointer cancellation, lost capture, blur, a second pointer, wheel input, resize, document visibility, content rebuild or opening an overlay clears capture and the timer. The pointer identity and viewport dimensions are checked so stale events cannot activate or cancel a different gesture. Every drag requires a new hold. The track's empty area has no jump handler. The scrollbar role supports Home, End, arrow keys and Page Up/Down.

`web/core.js` exports two internal frontend helpers: `scrollMetrics()` derives finite range, proportional thumb size (minimum 28px), travel and position; `dragScrollTarget()` clamps movement against that range. No Android bridge command, storage format, database schema or user data is added.

## Verification

- Core tests cover proportional geometry, zero/short pages, 20,000-account ranges, tiny tracks, invalid dimensions and both drag boundaries.
- `tests/scroll-controls.cjs` uses a simulated native bridge with offline data, real browser pointer/touch events and a fake clock for the 2999/3000ms boundary. It covers AMOLED borders, row geometry, multiple folders, 280/320/393/600px widths, long titles, enlarged fonts, overlays, cancellation, keyboard input, empty search and ordinary touch scrolling.
- `ScrollDeviceChecks` runs in the emulator instrumentation suite. It loads 4,000 synthetic accounts, changes Android system font scale to 1.0/1.3/1.5, checks actual WebView geometry and exercises real Android taps, the 3-second hold, drag and relock. It restores the original font setting and document. Proof is exported as scroll-ui.json and a screenshot.
- CI exports the browser log, screenshot, emulator proof and existing Android unit/lint reports. Test counts and completed results are recorded in the release verification file; browser fixtures and emulator data are not real Instagram tests.

No source image is opened or changed by these controls. Existing JSON, SQLite, SAF access, thumbnail naming and 128/256 WebP compatibility remain unchanged.
