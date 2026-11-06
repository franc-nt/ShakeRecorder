# Signed GitHub Releases

Every delivered code change requires a new version and a GitHub Release. The app
reads the latest published non-prerelease release from the public repository
`franc-nt/ShakeRecorder`. It must not contain a GitHub token.

## Signing continuity

Keep the original keystore and back it up securely under the owner's control.
The same signing key must remain available for every future release. Use durable
custody at a destination specifically approved by the owner after disclosing the
storage and access risks; a temporary directory or build workspace is not a
backup. Do not configure persistent storage or credentials based on this document
alone, or claim a backup exists without completing and verifying it.

Before starting any new release, confirm the original key is available, verify its
certificate fingerprint against the pin below, and validate recovery from the
authorized backup without displaying or logging key material or passwords. If
the original key or verified recovery is unavailable, stop delivery and report
the blocker. Never generate a replacement when the original is missing, and never
deliver an APK signed with a temporary key.

The certificate used by installed version 1.23 has SHA-256:

`017edcae8126e6d6904e4fdb651b889394e12d555f7576952a3c730e9eb07b59`

Release validation pins this certificate. The legacy installation used a debug
certificate; signing the release build with that same existing key allows an
in-place update. A different key will not update that installation. Never generate
a fresh debug or release key as a workaround. Losing this key prevents compatible
updates. Changing the certificate pin alone does not migrate Android installations.

Provide these values as environment variables for the build process, without
printing them, adding them to shell history, or committing them:

- `SHAKE_KEYSTORE_PATH`: absolute path to the existing keystore outside the checkout
- `SHAKE_KEYSTORE_PASSWORD`: existing store password
- `SHAKE_KEY_ALIAS`: existing signing alias
- `SHAKE_KEY_PASSWORD`: existing key password

`JAVA_HOME` must point to JDK 21 (Android 16 Robolectric tests) and `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to an
Android SDK with platform 36 and build-tools providing `apksigner` and `aapt`.
Python 3 is required. Missing/invalid credentials fail closed; no debug fallback.
Do not persist secrets in GitHub Actions or any other service without explicit
owner approval. No workflow that provisions secrets or changes permissions is
included.

## Build and publish

1. Make the change and run `./build.sh`. This increments versionCode and the minor
   versionName, runs release-tool tests, Android unit tests and debug/release lint,
   builds the signed release, verifies its certificate and package/version, and
   produces `build/ShakeRecorder.apk` and `build/update.json`. A failed build rolls
   back the version bump. Use `--no-version-bump` only to retry the same unpublished
   version, not to deliver another change with an already published version.
2. Review results and release notes. Confirm versionCode is higher than every
   previously published version. Commit all changes, including the version bump.
   Push the commit and a matching tag (for example `v1.24`) to the public repository
   using an authorized account. Do not change repository visibility or permissions.
3. With GitHub CLI authenticated for release publication, run
   `bash release.sh /absolute/path/to/release-notes.md`. Keep notes outside the
   checkout or commit them first, because publication requires a clean tree.
   The script verifies the tag points at the pushed commit and rebuilds the same
   version with all checks. It uploads both assets to a draft before publishing it
   as latest. It never overwrites an existing release. If a run stops after draft
   creation, inspect the draft and assets before retrying; do not blindly rerun.
4. Verify the published release URL, tag and both downloadable assets. On a phone
   with the preceding version, check for updates, download, confirm installation,
   then check the version, settings and existing recordings. Do not uninstall.
   Android may require allowing this app to install updates from unknown sources.
   A successful build does not replace this physical-device upgrade check.

GitHub write failures are blockers: report them, preserve the verified local APK,
and do not claim a release exists. A build alone does not publish anything.

## Metadata contract

Both assets belong to the same release. `update.json` is generated only after
APK verification and includes exactly these fields:

```json
{
  "schemaVersion": 1,
  "versionCode": 24,
  "versionName": "1.24",
  "apkName": "ShakeRecorder.apk",
  "sizeBytes": 123456,
  "sha256": "64-lowercase-hex-characters-computed-from-the-final-signed-apk"
}
```

The numbers/hash above are illustrative, not a publishable manifest. Do not edit
metadata by hand. Do not rename the APK asset or upload an APK from another build.
