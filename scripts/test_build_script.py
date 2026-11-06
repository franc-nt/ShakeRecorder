"""Exercise build modes without invoking Android tools or creating a signing key."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "build.sh"
SIGNING_NAMES = (
    "SHAKE_KEYSTORE_PATH", "SHAKE_KEYSTORE_PASSWORD",
    "SHAKE_KEY_ALIAS", "SHAKE_KEY_PASSWORD",
)


class BuildScriptTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        shutil.copy2(SCRIPT, self.root / "build.sh")
        (self.root / "app").mkdir()
        (self.root / "bin").mkdir()
        self.version_file = self.root / "app/build.gradle.kts"
        self.version = 'versionCode = 24\nversionName = "1.24"\n'
        self.version_file.write_text(self.version)
        self.artifacts = self.root / "build"
        self.artifacts.mkdir()
        (self.artifacts / "ShakeRecorder.apk").write_bytes(b"previous artifact")
        (self.artifacts / "update.json").write_text('{"previous":true}\n')
        self.log = self.root / "calls.log"
        self.make_tool(self.root / "bin/python3", "python", "PYTHON_EXIT")
        self.make_tool(self.root / "gradlew", "gradle", "GRADLE_EXIT")
        self.environment = {
            key: value for key, value in os.environ.items()
            if key not in SIGNING_NAMES
        }
        self.environment.update({
            "PATH": str(self.root / "bin") + os.pathsep + os.environ["PATH"],
            "JAVA_HOME": str(self.root / "unused-jdk"),
            "CALL_LOG": str(self.log),
            "PYTHON_EXIT": "0",
            "GRADLE_EXIT": "0",
        })

    def make_tool(self, path, name, exit_variable):
        path.write_text(
            '#!/bin/sh\n'
            f'printf "%s\\n" "{name}:$*" >> "$CALL_LOG"\n'
            f'exit "${{{exit_variable}:-0}}"\n'
        )
        path.chmod(0o755)

    def run_build(self, *arguments):
        return subprocess.run(
            ["bash", str(self.root / "build.sh"), *arguments],
            env=self.environment, capture_output=True, text=True, check=False,
        )

    def assert_no_release_changes(self):
        self.assertEqual(self.version_file.read_text(), self.version)
        self.assertEqual(
            (self.artifacts / "ShakeRecorder.apk").read_bytes(), b"previous artifact"
        )
        self.assertEqual(
            (self.artifacts / "update.json").read_text(), '{"previous":true}\n'
        )
        self.assertEqual(
            sorted(path.name for path in self.artifacts.iterdir()),
            ["ShakeRecorder.apk", "update.json"],
        )
        self.assertFalse((self.root / "app/build").exists())

    def test_check_only_runs_checks_without_signing_or_packaging(self):
        result = self.run_build("--check-only")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.log.read_text().splitlines(), [
            "python:-m unittest discover -s scripts -p test_*.py",
            "gradle:testDebugUnitTest lintDebug lintRelease --console=plain",
        ])
        self.assert_no_release_changes()

    def test_check_only_does_not_access_supplied_signing_configuration(self):
        self.environment.update({name: "not-a-real-signing-value" for name in SIGNING_NAMES})
        result = self.run_build("--check-only")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_no_release_changes()

    def test_check_only_propagates_python_failure_without_running_gradle(self):
        self.environment["PYTHON_EXIT"] = "7"
        result = self.run_build("--check-only")
        self.assertEqual(result.returncode, 7)
        self.assertNotIn("gradle:", self.log.read_text())
        self.assert_no_release_changes()

    def test_check_only_propagates_gradle_failure(self):
        self.environment["GRADLE_EXIT"] = "8"
        result = self.run_build("--check-only")
        self.assertEqual(result.returncode, 8)
        self.assertNotIn("Validação concluída", result.stdout)
        self.assert_no_release_changes()

    def test_invalid_or_combined_flags_fail_before_any_work(self):
        for arguments in (
            ("--check-only", "--no-version-bump"),
            ("--no-version-bump", "--check-only"),
            ("--check-only", "--check-only"),
            ("--no-version-bump", "--no-version-bump"),
            ("--unknown",), ("--check-only", "extra"),
        ):
            with self.subTest(arguments=arguments):
                result = self.run_build(*arguments)
                self.assertEqual(result.returncode, 2)
                self.assertIn("Uso:", result.stderr)
                self.assertFalse(self.log.exists())
                self.assert_no_release_changes()

    def test_release_modes_still_require_signing_before_any_changes(self):
        for arguments in ((), ("--no-version-bump",)):
            with self.subTest(arguments=arguments):
                result = self.run_build(*arguments)
                self.assertEqual(result.returncode, 1)
                self.assertIn("Missing required signing environment variable", result.stderr)
                self.assertFalse(self.log.exists())
                self.assert_no_release_changes()


if __name__ == "__main__":
    unittest.main()
