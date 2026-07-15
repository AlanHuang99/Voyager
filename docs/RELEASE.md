# Release Process

Voyager has two coordinated distribution paths:

- GitHub releases and GitHub Pages host APKs signed with the project's release key.
- The current F-Droid recipe rebuilds the source, verifies it against the upstream APK through the `Binaries` directive, checks the expected developer signing key with `AllowedAPKSigningKeys`, and publishes the verified developer-signed APK.

Keep the release inputs aligned. Do not commit keystores, generated APKs, local signing files, or F-Droid build output. F-Droid's reproducible-build flow is documented at [f-droid.org/docs/Reproducible_Builds](https://f-droid.org/docs/Reproducible_Builds/).

## GitHub Actions Secrets

Configure these repository secrets before publishing a GitHub release:

- `ANDROID_KEYSTORE_BASE64`: Base64-encoded release keystore file.
- `ANDROID_KEYSTORE_PASSWORD`: Keystore password.
- `ANDROID_KEY_ALIAS`: Release key alias.
- `ANDROID_KEY_PASSWORD`: Release key password.

Example keystore encoding:

```bash
base64 -w 0 voyager-release.keystore
```

The release workflow decodes the keystore into the runner temp directory and passes its path through `ANDROID_KEYSTORE_PATH`. Gradle only uses the release signing config when all four signing values are present.

## CI

Every push and pull request to `master` runs:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --stacktrace
```

The CI workflow uploads debug APKs and verification reports as short-lived workflow artifacts. It does not publish user-facing releases.

## GitHub Release And Pages

Create a version tag that matches `versionName` in `app/build.gradle.kts`:

```bash
git tag v1.0.2
git push origin v1.0.2
```

The release workflow will:

- Verify the tag matches `versionName`.
- Require all signing secrets.
- Run unit tests, lint, and `assembleRelease`.
- Upload signed APKs and `SHA256SUMS.txt` to the GitHub Release.
- Deploy a GitHub Pages site containing the latest APK download, checksums, and `latest.json`.

GitHub Pages must be configured to use GitHub Actions as the deployment source.

## F-Droid Metadata

The F-Droid recipe is `metadata/com.voyagerfiles.yml`.

Important constraints:

- `Builds.commit` should be the full commit hash for the source revision being built.
- `AutoUpdateMode: Version` with `UpdateCheckMode: Tags` lets F-Droid discover future version tags.
- The build uses `gradleprops: voyager.enableAbiSplits=false` so F-Droid reproduces the universal upstream APK instead of the ABI split set.

After a new GitHub release commit is final, update the F-Droid build block to the release commit hash and matching version if the F-Droid metadata is maintained in this repository.
