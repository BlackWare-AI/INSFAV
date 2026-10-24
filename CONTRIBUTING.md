# Contributing to INSFAV

Third-party contributions are welcome. Please open an issue for a larger change, then submit a pull request to `main`.

## Before you submit

- Keep INSFAV independent from INSFAV Plus. Do not merge the Plus application or change its package into this repository.
- Preserve the compatible InstaPro Favorites JSON format, original JSON export, collection membership, thumbnail formats and current data behavior.
- Thumbnail rescan must remain read-only: it must not write, rename or delete user source files.
- Do not add automatic Instagram access, new endpoints or behavior that conflicts with the documented privacy and access limits.
- Do not include cookies, personal JSON backups, account data, signing keys, keystores, passwords or other secrets in commits, logs, screenshots or test fixtures.
- Keep official releases signed only with the existing private release key. Forks may build and distribute their own builds, but they must not claim to be official INSFAV releases or update-compatible with BlackWare's signed APK.
- Do not overwrite or delete published release tags or assets. Never add signing secrets to a public fork.

## Pull requests

Describe the user-visible change, why it is needed, and how you verified it. Include relevant screenshots for UI changes and list any checks you could not run.

Run the applicable checks before requesting review:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

For generated web assets, edit `web/` and regenerate with `npm ci --ignore-scripts && npm run web`. Include generated output only when it matches the source.

A pull request is a proposal. Maintainers review and decide whether to merge it. Do not expect fork changes to be included in an official signed release automatically.
