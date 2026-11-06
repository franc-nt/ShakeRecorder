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
CERTIFICATE = "017edcae8126e6d6904e4fdb651b889394e12d555f7576952a3c730e9eb07b59"


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
        raise ValueError("APK signing certificate differs from the installed app")
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
