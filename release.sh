#!/usr/bin/env bash
# Publish only an already-committed, tagged, signed and verified version.
set -euo pipefail
cd "$(dirname "$0")"
MIGRATION_PRERELEASE=false
if [[ ${1:-} == --migration-prerelease ]]; then
    MIGRATION_PRERELEASE=true
    shift
fi
if [[ $# != 1 || ! -f "$1" ]]; then
    echo "Usage: bash release.sh [--migration-prerelease] /path/to/release-notes.md" >&2
    exit 2
fi
NOTES=$(realpath "$1")
VERSION=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)
# 1.25 starts a different signing lineage and cannot update installed 1.24.
# Keep it out of /releases/latest, which the legacy updater still consumes.
if [[ "$VERSION" == 1.25 ]]; then
    $MIGRATION_PRERELEASE || {
        echo "Version 1.25 requires --migration-prerelease; it cannot replace Latest 1.24" >&2
        exit 1
    }
elif $MIGRATION_PRERELEASE; then
    echo "--migration-prerelease is reserved for the first new-key release, version 1.25" >&2
    exit 2
fi
[[ -z "$(git status --porcelain)" ]] || { echo "Commit all release changes first" >&2; exit 1; }
TAG="v$VERSION"
HEAD=$(git rev-parse HEAD)
[[ "$(git rev-parse "$TAG^{commit}")" == "$HEAD" ]] || { echo "Tag must point at HEAD" >&2; exit 1; }
# Check the remote tag (including annotated tags) rather than silently creating it.
REMOTE=$(git ls-remote https://github.com/franc-nt/ShakeRecorder.git "refs/tags/$TAG" "refs/tags/$TAG^{}")
grep -q "^$HEAD[[:space:]]" <<< "$REMOTE" || { echo "Push the exact release tag before publishing" >&2; exit 1; }
gh auth status >/dev/null
bash ./build.sh --no-version-bump

CREATE_FLAGS=(--draft)
PUBLISH_FLAGS=(--draft=false)
TITLE="ShakeRecorder $VERSION"
if $MIGRATION_PRERELEASE; then
    CREATE_FLAGS+=(--prerelease --latest=false)
    PUBLISH_FLAGS+=(--prerelease --latest=false)
    TITLE+=" (nova assinatura, migração pendente)"
    MIGRATION_NOTES=$(mktemp)
    trap 'rm -f "$MIGRATION_NOTES"' EXIT
    cat > "$MIGRATION_NOTES" <<'NOTICE'
## Nova assinatura: não atualiza a versão 1.24

Este APK inicia uma nova linha de assinatura. O Android não permite instalá-lo por cima da 1.24, nem abrindo o APK manualmente. Não desinstale o aplicativo nem limpe seus dados para contornar o conflito: a migração das gravações e configurações ainda precisa de um plano separado e validado. O app não possui exportação/importação de configurações.

Esta pré-release não substitui o Latest 1.24 e não é oferecida pelo atualizador. Uma instalação limpa em outro aparelho ou emulador não comprova migração dos dados da instalação existente. As próximas versões desta nova linha precisam usar a mesma chave nova.

NOTICE
    cat "$NOTES" >> "$MIGRATION_NOTES"
    NOTES="$MIGRATION_NOTES"
else
    PUBLISH_FLAGS+=(--latest)
fi
gh release create "$TAG" build/ShakeRecorder.apk build/update.json \
    --repo franc-nt/ShakeRecorder --verify-tag "${CREATE_FLAGS[@]}" \
    --title "$TITLE" --notes-file "$NOTES"
# Confirm both assets exist before exposing the release to the in-app updater.
gh release view "$TAG" --repo franc-nt/ShakeRecorder --json assets | python3 -c '
import json, sys
names = {a["name"] for a in json.load(sys.stdin)["assets"]}
assert {"ShakeRecorder.apk", "update.json"} <= names, "Release assets incomplete"
'
gh release edit "$TAG" --repo franc-nt/ShakeRecorder "${PUBLISH_FLAGS[@]}"
gh release view "$TAG" --repo franc-nt/ShakeRecorder --json url,isDraft,isPrerelease,tagName
