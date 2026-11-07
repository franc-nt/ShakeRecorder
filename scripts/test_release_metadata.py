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

    def test_release_pin_is_a_sha256_fingerprint(self):
        self.assertRegex(CERTIFICATE, r"^[0-9a-f]{64}$")

    def test_rejects_wrong_signer(self):
        with self.assertRaisesRegex(ValueError, "configured release certificate"):
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


class SigningMigrationTests(unittest.TestCase):
    """A new build pin selects one signing lineage, never both of them."""

    legacy_certificate = "017edcae8126e6d6904e4fdb651b889394e12d555f7576952a3c730e9eb07b59"
    new_certificate = "8eebaa0eff8e5a598a4012dafff7e7c4009e5afbc5ea029b990ce6800977e993"
    badging = "package: name='com.shakerecorder' versionCode='25' versionName='1.25'"

    def verify_with_new_pin(self, signature):
        verify_identity(signature, self.badging, 25, "1.25")

    def test_new_lineage_pin_matches_authorized_public_certificate(self):
        self.assertEqual(CERTIFICATE, self.new_certificate)
        self.assertNotEqual(CERTIFICATE, self.legacy_certificate)

    def test_new_pin_accepts_only_new_certificate(self):
        self.verify_with_new_pin(f"Signer #1 certificate SHA-256 digest: {self.new_certificate}\n")

    def test_new_pin_rejects_legacy_certificate(self):
        with self.assertRaisesRegex(ValueError, "configured release certificate"):
            self.verify_with_new_pin(f"Signer #1 certificate SHA-256 digest: {self.legacy_certificate}\n")

    def test_new_pin_rejects_new_and_legacy_signers(self):
        with self.assertRaises(ValueError):
            self.verify_with_new_pin(
                f"Signer #1 certificate SHA-256 digest: {self.new_certificate}\n"
                f"Signer #2 certificate SHA-256 digest: {self.legacy_certificate}\n"
            )


if __name__ == "__main__":
    unittest.main()
