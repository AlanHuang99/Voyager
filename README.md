# Voyager

Yet another Android file manager — but with Material Design 3, Jetpack Compose, and network protocol support built in.

## Features

- **Local file browsing** — list/grid views, sort, search, hidden files
- **Remote connections** — SFTP, FTP, SMB, WebDAV
- **12 themes** — System, Black (AMOLED), White, Dark, Ocean, Purple, Forest, Mocha, Macchiato, Frappé, Latte, Custom
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

## License

[GPLv3](LICENSE)
