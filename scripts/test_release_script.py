"""Test publication routing with local stubs: no GitHub, Gradle or signing access."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "release.sh"


class ReleaseScriptTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        shutil.copy2(SCRIPT, self.root / "release.sh")
        (self.root / "app").mkdir()
        (self.root / "bin").mkdir()
        (self.root / "build").mkdir()
        self.set_version("1.25")
        self.notes = self.root / "notes.md"
        self.notes.write_text("Compartilhar uma gravação pelo seletor Android.\n")
        self.log = self.root / "calls.log"
        self.captured_notes = self.root / "published-notes.md"
        self.write_tool("bin/git", '''#!/bin/sh
printf '%s\n' "git:$*" >> "$CALL_LOG"
case "$1" in
    status) printf '%s' "${GIT_DIRTY:-}" ;;
    rev-parse) printf '%s\n' aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ;;
    ls-remote) printf '%s\t%s\n' aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "refs/tags/v$TEST_VERSION" ;;
    *) exit 99 ;;
esac
''')
        self.write_tool("bin/gh", '''#!/bin/sh
printf '%s\n' "gh:$*" >> "$CALL_LOG"
case "$1 $2" in
    "auth status") exit 0 ;;
    "release create")
        while [ "$#" -gt 0 ]; do
            if [ "$1" = --notes-file ]; then cp "$2" "$CAPTURED_NOTES"; break; fi
            shift
        done
        exit "${GH_CREATE_EXIT:-0}"
        ;;
    "release view")
        case "$*" in
            *"--json assets") printf '%s\n' "$ASSETS_JSON" ;;
            *) printf '{}\n' ;;
        esac
        ;;
    "release edit") exit 0 ;;
    *) exit 99 ;;
esac
''')
        self.write_tool("build.sh", '''#!/bin/sh
printf '%s\n' "build:$*" >> "$CALL_LOG"
exit "${BUILD_EXIT:-0}"
''')
        self.environment = {
            key: value for key, value in os.environ.items()
            if not key.startswith("SHAKE_") and key not in ("GH_TOKEN", "GITHUB_TOKEN")
        }
        self.environment.update({
            "PATH": str(self.root / "bin") + os.pathsep + os.environ["PATH"],
            "CALL_LOG": str(self.log),
            "CAPTURED_NOTES": str(self.captured_notes),
            "TEST_VERSION": "1.25",
            "ASSETS_JSON": json.dumps({"assets": [
                {"name": "ShakeRecorder.apk"}, {"name": "update.json"},
            ]}),
            "GIT_DIRTY": "", "BUILD_EXIT": "0", "GH_CREATE_EXIT": "0",
        })

    def write_tool(self, name, contents):
        path = self.root / name
        path.write_text(contents)
        path.chmod(0o755)

    def set_version(self, version):
        (self.root / "app/build.gradle.kts").write_text(f'versionName = "{version}"\n')

    def run_release(self, *arguments):
        return subprocess.run(
            ["bash", str(self.root / "release.sh"), *map(str, arguments)],
            env=self.environment, capture_output=True, text=True, check=False,
        )

    def test_first_new_key_release_cannot_publish_as_latest_by_default(self):
        result = self.run_release(self.notes)
        self.assertEqual(result.returncode, 1)
        self.assertIn("requires --migration-prerelease", result.stderr)
        self.assertFalse(self.log.exists())

    def test_migration_release_is_prerelease_and_explicitly_not_latest(self):
        result = self.run_release("--migration-prerelease", self.notes)
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.log.read_text().splitlines()
        self.assertIn("build:--no-version-bump", calls)
        create = next(call for call in calls if call.startswith("gh:release create "))
        publish = next(call for call in calls if call.startswith("gh:release edit "))
        self.assertIn("--draft", create.split())
        for call in (create, publish):
            self.assertIn("--prerelease", call.split())
            self.assertIn("--latest=false", call.split())
            self.assertNotIn("--latest", call.split())
        self.assertIn("--draft=false", publish.split())
        self.assertIn("nova assinatura, migração pendente", create)

    def test_migration_notes_include_warning_and_keep_original_notes_unchanged(self):
        original_notes = self.notes.read_text()
        result = self.run_release("--migration-prerelease", self.notes)
        self.assertEqual(result.returncode, 0, result.stderr)
        published = self.captured_notes.read_text()
        self.assertIn("não atualiza a versão 1.24", published)
        self.assertIn("nem abrindo o APK manualmente", published)
        self.assertIn("Não desinstale o aplicativo nem limpe seus dados", published)
        self.assertIn("não possui exportação/importação de configurações", published)
        self.assertIn(original_notes, published)
        self.assertEqual(self.notes.read_text(), original_notes)
        create = next(call for call in self.log.read_text().splitlines()
                      if call.startswith("gh:release create "))
        temporary_notes = create.split("--notes-file ")[1]
        self.assertFalse(Path(temporary_notes).exists())

    def test_later_same_lineage_release_keeps_standard_stable_workflow(self):
        self.set_version("1.26")
        self.environment["TEST_VERSION"] = "1.26"
        result = self.run_release(self.notes)
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.log.read_text().splitlines()
        publish = next(call for call in calls if call.startswith("gh:release edit "))
        self.assertIn("--latest", publish.split())
        self.assertNotIn("--prerelease", publish.split())
        self.assertEqual(self.captured_notes.read_text(), self.notes.read_text())

    def test_migration_flag_is_reserved_for_version_125(self):
        for version in ("1.24", "1.26"):
            with self.subTest(version=version):
                self.set_version(version)
                result = self.run_release("--migration-prerelease", self.notes)
                self.assertEqual(result.returncode, 2)
                self.assertFalse(self.log.exists())

    def test_invalid_arguments_stop_before_any_work(self):
        for arguments in (
            (), ("--migration-prerelease",),
            ("--unknown", self.notes),
            ("--migration-prerelease", "--migration-prerelease", self.notes),
            (self.notes, "--migration-prerelease"),
        ):
            with self.subTest(arguments=arguments):
                result = self.run_release(*arguments)
                self.assertEqual(result.returncode, 2)
                self.assertFalse(self.log.exists())

    def test_dirty_tree_blocks_before_build_or_publication(self):
        self.environment["GIT_DIRTY"] = " M README.md"
        result = self.run_release("--migration-prerelease", self.notes)
        self.assertEqual(result.returncode, 1)
        self.assertNotIn("build:", self.log.read_text())
        self.assertNotIn("gh:", self.log.read_text())

    def test_failed_build_never_creates_release(self):
        self.environment["BUILD_EXIT"] = "7"
        result = self.run_release("--migration-prerelease", self.notes)
        self.assertEqual(result.returncode, 7)
        self.assertNotIn("gh:release create", self.log.read_text())

    def test_failed_draft_creation_never_publishes(self):
        self.environment["GH_CREATE_EXIT"] = "8"
        result = self.run_release("--migration-prerelease", self.notes)
        self.assertEqual(result.returncode, 8)
        self.assertNotIn("gh:release edit", self.log.read_text())

    def test_incomplete_assets_never_publish_draft(self):
        self.environment["ASSETS_JSON"] = '{"assets":[{"name":"ShakeRecorder.apk"}]}'
        result = self.run_release("--migration-prerelease", self.notes)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Release assets incomplete", result.stderr)
        self.assertNotIn("gh:release edit", self.log.read_text())


if __name__ == "__main__":
    unittest.main()
