# Signed GitHub Releases

Every delivered code change requires a new version and a GitHub Release. The app
reads the latest published non-prerelease release from the public repository
`franc-nt/ShakeRecorder`. It must not contain a GitHub token.

## Signing transition: version 1.25

The private key used by versions 1.23 and 1.24 was lost. Its public certificate
SHA-256 remains part of the historical record:

`017edcae8126e6d6904e4fdb651b889394e12d555f7576952a3c730e9eb07b59`

After being informed of the incompatibility, the owner authorized one new random
signing key. It was generated locally. Its public certificate SHA-256 is now the
single build-time pin in `scripts/release_metadata.py`:

`8eebaa0eff8e5a598a4012dafff7e7c4009e5afbc5ea029b990ce6800977e993`

This starts a new signing lineage. **An APK signed with the new key cannot update
1.24, even when opened manually.** The package remains `com.shakerecorder`.
Changing the pin does not migrate Android installations; retain
`AppUpdater.requireCompatible` and its exact signer comparison. Do not accept
both fingerprints as a workaround. The old key cannot be recovered from an APK.

Migration of existing recordings and settings is still pending. Do not uninstall
1.24, clear its data, or claim its data will be restored. Settings have no
export/import path; `allowBackup` is not proof of recovery. Any migration on the
existing phone requires a separate, approved and validated preservation plan.
Clean installation tests must use another device or emulator with no existing
installation. They do not prove migration works.

### Current custody exception

Only the password has verified storage and recovery in the owner's Space. The
**private key** remains local, without a verified durable backup. The
owner requested immediate publication of the first new-key prerelease despite
this limitation. This is a one-time exception for version 1.25, not a claim that a
backup exists or that future releases are guaranteed. A saved password alone is
not a backup of the keystore. Do not transmit the private key to any destination
without the required approval. Release notes must disclose pending key custody
and pending data migration. This document does not claim an APK was built,
published, installed or physically tested.

## Signing continuity after the transition

Preserve the SAME new signing key for every later release. Use durable custody at
a destination specifically approved by the owner after disclosing storage and
access risks. A temporary folder or build workspace is not a durable backup.
Do not configure persistent storage or credentials based on this document alone,
or claim a backup exists without completing and verifying it.

Before starting any later release, confirm the key is available, verify its
certificate fingerprint against the new pin above, and validate recovery from
the authorized backup without displaying or logging key material or passwords.
If the key or verified recovery is unavailable, stop delivery and report the
blocker. Never generate a further replacement, use a debug fallback, or claim
continuity from a different key. Future in-place updates depend on preserving
this exact key; the pending custody work means continuity is not yet assured.

Provide these values as environment variables for the build process, without
printing them, adding them to shell history, or committing them:

- `SHAKE_KEYSTORE_PATH`: absolute path to the authorized keystore outside the checkout
- `SHAKE_KEYSTORE_PASSWORD`: store password
- `SHAKE_KEY_ALIAS`: signing alias
- `SHAKE_KEY_PASSWORD`: key password

`JAVA_HOME` must point to JDK 21 (Android 16 Robolectric tests) and `ANDROID_HOME`
(or `ANDROID_SDK_ROOT`) to an Android SDK with platform 36 and build-tools
providing `apksigner` and `aapt`. Python 3 is required. Missing/invalid credentials
fail closed; no key generation or debug fallback occurs in the build scripts.
Do not persist secrets in GitHub Actions or another service without explicit
owner approval. No workflow that provisions secrets or changes permissions is
included.

## Validate without a signing key

Run `./build.sh --check-only` during development to run Python release-tool tests,
Android unit tests, and debug/release lint. This mode requires JDK 21 and the
Android SDK but does not require signing credentials, create any key, package an
APK, increment versionCode/versionName, or replace the release APK/metadata.
Normal Gradle caches, test results, and lint reports may be written.

`--check-only` and `--no-version-bump` are mutually exclusive; duplicate flags and
unknown arguments are rejected. Passing checks is not a release and does not
demonstrate installation compatibility. Delivery still requires the authorized
key and pinned certificate, a new version, a verified APK/metadata pair, truthful
release notes, and the publication workflow below.

## Build and publish

1. Make the change and run `./build.sh`. This increments versionCode and the minor
   versionName, runs release-tool tests, Android unit tests and debug/release lint,
   builds the signed release, verifies its certificate and package/version, and
   produces `build/ShakeRecorder.apk` and `build/update.json`. A failed build rolls
   back the version bump. Use `--no-version-bump` only to retry the same unpublished
   version, not to deliver another change with an already published version.
2. Review results and release notes. Confirm versionCode is higher than every
   previously published version. Commit all changes, including the version bump.
   Push the commit and a matching tag (for example `v1.25`) to the public repository
   using an authorized account. Do not change repository visibility or permissions.
3. With GitHub CLI authenticated for release publication, choose the correct mode:
   - First new-key release 1.25:
     `bash release.sh --migration-prerelease /absolute/path/to/release-notes.md`.
     The flag is mandatory for 1.25 and rejected for other versions. The script
     adds a prominent incompatibility/data-migration warning, creates a draft
     prerelease, verifies both assets, then publishes with `--prerelease` and
     `--latest=false`. It does not replace Latest 1.24 or offer the APK through
     the legacy updater. The supplied notes must disclose pending key custody,
     actual validation results and any other remaining limitations.
   - Later releases signed with the same new key:
     `bash release.sh /absolute/path/to/release-notes.md` supports normal stable
     publication as Latest. Before using it, resolve the distribution plan for
     remaining 1.24 users: they also consume the same Latest endpoint and cannot
     install new-key releases. A later version number does not fix this.
   Keep notes outside the checkout or commit them first; publication requires a
   clean tree. The script verifies the tag points at the pushed commit, rebuilds
   the same version with all checks and uploads both assets to a draft. It never
   overwrites an existing release. If a run stops after draft creation, inspect
   that draft and its assets before retrying; do not blindly rerun.
4. Verify the published URL, tag, both assets and release state. For 1.25, confirm
   `prerelease=true`, that Latest still resolves to `v1.24`, and that 1.24 assets
   are unchanged. Disclose any failed or unperformed check.
5. On a separate test device, validate the first clean new-key installation and
   sharing. For later same-key versions, check an in-place upgrade from a prior
   new-key version, including version, settings and recordings. Do not uninstall
   an existing app to perform this test. Android's installer confirmation remains
   required and the app may need permission to install updates from that source.
   A successful build is not a physical-device test or proof of data migration.

GitHub write failures are blockers: report them, preserve the verified local APK,
and do not claim a release exists. A build alone does not publish anything.

## Metadata contract

Both assets belong to the same release. `update.json` is generated only after
APK verification and includes exactly these fields:

```json
{
  "schemaVersion": 1,
  "versionCode": 25,
  "versionName": "1.25",
  "apkName": "ShakeRecorder.apk",
  "sizeBytes": 123456,
  "sha256": "64-lowercase-hex-characters-computed-from-the-final-signed-apk"
}
```

The numbers/hash above are illustrative, not a publishable manifest. Do not edit
metadata by hand. Do not rename the APK asset or upload an APK from another build.
The metadata schema does not convey signing compatibility; the updater still
verifies the downloaded APK against the installed app's certificate.
