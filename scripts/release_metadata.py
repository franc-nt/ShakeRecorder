#!/usr/bin/env python3
"""Verify the release artifact before writing metadata consumed by the app."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
# The planned 1.25 lineage starts with a new, owner-authorized signing key.
# This build-time pin does not make its APK compatible with installed 1.24.
CERTIFICATE = "8eebaa0eff8e5a598a4012dafff7e7c4009e5afbc5ea029b990ce6800977e993"


def android_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        choices = sorted((Path(sdk) / "build-tools").glob(f"*/{name}"))
        if choices:
            return str(choices[-1])
    tool = shutil.which(name)
    if not tool:
        raise ValueError(f"Android SDK tool missing: {name}")
    return tool


def verify_identity(signature, badging, version_code, version_name):
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", signature)
    if [c.lower() for c in certificates] != [CERTIFICATE]:
        raise ValueError("APK signing certificate differs from the configured release certificate")
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package or package.groups() != ("com.shakerecorder", str(version_code), version_name):
        raise ValueError("APK package/version does not match the project")


def metadata(apk, version_code, version_name):
    data = apk.read_bytes()
    if not data:
        raise ValueError("APK is empty")
    return {"schemaVersion": 1, "versionCode": version_code,
            "versionName": version_name, "apkName": "ShakeRecorder.apk",
            "sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def main():
    apk, output = map(Path, sys.argv[1:])
    if apk.name != "ShakeRecorder.apk":
        raise ValueError("Release asset must be named ShakeRecorder.apk")
    gradle = (ROOT / "app/build.gradle.kts").read_text()
    code = int(re.search(r"versionCode = (\d+)", gradle)[1])
    name = re.search(r'versionName = "([^"]+)"', gradle)[1]
    signature = subprocess.check_output([android_tool("apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    badging = subprocess.check_output([android_tool("aapt"), "dump", "badging", str(apk)], text=True)
    verify_identity(signature, badging, code, name)
    output.write_text(json.dumps(metadata(apk, code, name), indent=2) + "\n")


if __name__ == "__main__":
    main()
