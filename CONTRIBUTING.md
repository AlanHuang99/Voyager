# Contributing to Voyager

You can help by translating, reporting reproducible bugs, testing on your device, improving documentation, or submitting code. Voyager focuses on file browsing and management, with clear navigation and a small number of controls for common tasks.

## Translate or review wording

Use [Voyager on Crowdin](https://crowdin.com/project/voyagerandroid) to contribute from your browser. Choose a language, open `strings.xml`, and translate a few strings or suggest corrections. No local build is required. Preserve formatting placeholders and plurals, and comment on a string when you need context. See the [translation guide](docs/TRANSLATING.md) for terminology and review rules.

To request a language or volunteer as a reviewer, open a [GitHub Discussion](https://github.com/AlanHuang99/Voyager/discussions). Corrections are reviewed and included in subsequent app releases; submitting a translation does not update an installed app immediately.

## Ask questions or report a bug

Use [Discussions](https://github.com/AlanHuang99/Voyager/discussions) for help and working configurations. Search [Issues](https://github.com/AlanHuang99/Voyager/issues) before reporting a bug, then include:

- Voyager version and install source, device model, and Android version.
- Steps to reproduce, expected behavior, and what happened.
- Whether the location is local storage, a document tree, root, SFTP, FTP, SMB, or WebDAV. For a server issue, include the server software and version when available.
- Relevant screenshots or a short log excerpt, with passwords, keys, tokens, personal paths, filenames, and server details removed.

If an issue already describes your problem, add the missing details there. Discuss substantial feature proposals before implementing them, including how the feature fits file management and affects navigation.

## Submit code or documentation

1. Fork the repository and create a branch for one focused change.
2. Follow the [README build instructions](README.md#build-from-source) and read the relevant parts of [Architecture](docs/ARCHITECTURE.md).
3. Add user-facing strings to the English Android resources. Run the command below to fill missing locale entries with English while preserving existing translations. Feature PRs do not need to translate new wording; Crowdin supplies translations for review later.
4. Verify changed behavior with focused tests or device checks, then run the complete local gate below for code changes. Documentation changes should have working links and accurate commands.
5. Open a pull request describing the problem, resulting behavior, verification commands, and any device or server setup used. Link the relevant issue and include sanitized screenshots when the interface changes.

Run the resource preparation command from the repository root after adding English strings. It uses the checked-in resources and needs no Crowdin account or token:

```bash
uv run --no-project python scripts/prepare_translation_export.py
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Release builds compile without private signing credentials. Keep keystores, credentials, generated APKs, local configuration, and test logs out of commits. The `.debug` app can be installed alongside the release app for testing.

## Test on a device

The [testing guide](docs/TESTING.md) covers Android instrumentation, disposable protocol fixtures, and manual checks. Use disposable files and server accounts for operations that create, overwrite, or delete data. For navigation changes, check Back behavior, the keyboard, empty folders, and switching sessions. For translations, check long text and action labels in the app.

When reporting results, include the app version, Android version, tested scenario, and outcome. A concise report of a reproducible failure is useful even if you cannot provide a patch.
