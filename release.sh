#!/usr/bin/env bash
# Publish only an already-committed, tagged, signed and verified version.
set -euo pipefail
cd "$(dirname "$0")"
if [[ $# != 1 || ! -f "$1" ]]; then
    echo "Usage: bash release.sh /path/to/release-notes.md" >&2
    exit 2
fi
NOTES=$(realpath "$1")
[[ -z "$(git status --porcelain)" ]] || { echo "Commit all release changes first" >&2; exit 1; }
VERSION=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)
TAG="v$VERSION"
HEAD=$(git rev-parse HEAD)
[[ "$(git rev-parse "$TAG^{commit}")" == "$HEAD" ]] || { echo "Tag must point at HEAD" >&2; exit 1; }
# Check the remote tag (including annotated tags) rather than silently creating it.
REMOTE=$(git ls-remote https://github.com/franc-nt/ShakeRecorder.git "refs/tags/$TAG" "refs/tags/$TAG^{}")
grep -q "^$HEAD[[:space:]]" <<< "$REMOTE" || { echo "Push the exact release tag before publishing" >&2; exit 1; }
gh auth status >/dev/null
bash ./build.sh --no-version-bump
gh release create "$TAG" build/ShakeRecorder.apk build/update.json \
    --repo franc-nt/ShakeRecorder --verify-tag --draft \
    --title "ShakeRecorder $VERSION" --notes-file "$NOTES"
# Confirm both assets exist before exposing the release to the in-app updater.
gh release view "$TAG" --repo franc-nt/ShakeRecorder --json assets | python3 -c '
import json, sys
names = {a["name"] for a in json.load(sys.stdin)["assets"]}
assert {"ShakeRecorder.apk", "update.json"} <= names, "Release assets incomplete"
'
gh release edit "$TAG" --repo franc-nt/ShakeRecorder --draft=false --latest
gh release view "$TAG" --repo franc-nt/ShakeRecorder --json url,isDraft,tagName
