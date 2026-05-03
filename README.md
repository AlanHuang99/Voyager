# Voyager

Yet another Android file manager — but with Material Design 3, Jetpack Compose, and network protocol support built in.

## Features

- **Local file browsing** — list/grid views, sort, search, hidden files
- **Remote connections** — SFTP, FTP, SMB, WebDAV
- **20 themes** — System, Black (AMOLED), White, Dark, Ocean, Purple, Forest, Catppuccin, Nord, Solarized, Gruvbox, Rosé Pine, Tokyo Night, High Contrast, Custom
- **Material You** — dynamic colors on Android 12+
- **File operations** — copy, cut, paste, rename, delete, create
- **Bookmarks** — save frequently accessed paths
- **F-Droid ready** — GPLv3, no proprietary dependencies, no tracking

## Tech Stack

- Kotlin + Jetpack Compose
- Material Design 3
- Room Database
- DataStore Preferences
- Apache SSHD (SFTP)
- Apache Commons Net (FTP)
- smbj (SMB)
- Sardine (WebDAV)

## Build

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Verification

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

## Distribution

- GitHub Actions runs unit tests, lint, debug builds, and unsigned release builds on pushes and pull requests.
- Version tags such as `v1.0.2` can publish signed release APKs to GitHub Releases and GitHub Pages when release signing secrets are configured.
- F-Droid metadata lives in `metadata/com.voyagerfiles.yml` and disables ABI splits through `gradleprops` so F-Droid builds one unsigned APK that F-Droid signs itself.

Release setup details are in [docs/RELEASE.md](docs/RELEASE.md).

## License

[GPLv3](LICENSE)
