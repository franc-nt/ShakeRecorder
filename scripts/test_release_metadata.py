import hashlib
from pathlib import Path
import tempfile
import unittest
from release_metadata import CERTIFICATE, metadata, verify_identity


class ReleaseMetadataTests(unittest.TestCase):
    signature = f"Signer #1 certificate SHA-256 digest: {CERTIFICATE}\n"
    badging = "package: name='com.shakerecorder' versionCode='24' versionName='1.24'"

    def test_matching_package_and_signer(self):
        verify_identity(self.signature, self.badging, 24, "1.24")

    def test_rejects_wrong_signer(self):
        with self.assertRaises(ValueError):
            verify_identity(self.signature.replace(CERTIFICATE, "a" * 64), self.badging, 24, "1.24")

    def test_rejects_unsigned(self):
        with self.assertRaises(ValueError):
            verify_identity("", self.badging, 24, "1.24")

    def test_rejects_extra_signer(self):
        with self.assertRaises(ValueError):
            verify_identity(self.signature * 2, self.badging, 24, "1.24")

    def test_rejects_wrong_package(self):
        with self.assertRaises(ValueError):
            verify_identity(self.signature, self.badging.replace("com.shakerecorder", "other.app"), 24, "1.24")

    def test_rejects_wrong_version(self):
        for code, name in [(23, "1.24"), (24, "1.23")]:
            with self.subTest(code=code, name=name), self.assertRaises(ValueError):
                verify_identity(self.signature, self.badging, code, name)

    def test_metadata_hash_and_size(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "ShakeRecorder.apk"
            apk.write_bytes(b"fixture APK bytes")
            result = metadata(apk, 24, "1.24")
            self.assertEqual(result, {"schemaVersion": 1, "versionCode": 24,
                "versionName": "1.24", "apkName": "ShakeRecorder.apk",
                "sizeBytes": 17, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()})

    def test_rejects_empty_apk(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "ShakeRecorder.apk"
            apk.touch()
            with self.assertRaises(ValueError):
                metadata(apk, 24, "1.24")


if __name__ == "__main__":
    unittest.main()
