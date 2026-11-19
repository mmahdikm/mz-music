"""Package local artifacts only; excludes SDKs, caches, fixtures and signing keys."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile
import os
import subprocess

root = Path(__file__).resolve().parents[1]
version = re.search(r'versionName = "([^"]+)"', (root / "app/build.gradle.kts").read_text())[1]
validation = root / "validation" / version
validation.mkdir(parents=True, exist_ok=True)
test_folder = validation / "unit-tests"
test_folder.mkdir(exist_ok=True)
tests = []
for report in sorted((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml")):
    suite = ET.parse(report).getroot()
    assert int(suite.get("failures", 0)) == 0 and int(suite.get("errors", 0)) == 0
    tests.append({"suite": suite.get("name"), "tests": int(suite.get("tests", 0))})
    shutil.copyfile(report, test_folder / report.name)
assert tests, "Run the JVM tests before packaging"
issues = ET.parse(root / "app/build/reports/lint-results-release.xml").getroot().findall("issue")
errors = [i for i in issues if i.get("severity") in ("Error", "Fatal")]
assert not errors, "Android lint must pass before packaging"
(validation / "build-checks.json").write_text(json.dumps({
    "version": version, "unit_test_count": sum(s["tests"] for s in tests), "suites": tests,
    "lint_errors": 0, "lint_warnings": [{"id": i.get("id"), "message": i.get("message")} for i in issues],
}, indent=2) + "\n")
dist = root / "dist"
dist.mkdir(exist_ok=True)
apk = dist / f"mz-music-{version}-release.apk"
shutil.copyfile(root / "app/build/outputs/apk/release/app-release.apk", apk)
sdk = Path(os.environ["ANDROID_HOME"])
build_tools = sdk / "build-tools/36.0.0"
badging = subprocess.check_output([str(build_tools / "aapt"), "dump", "badging", str(apk)], text=True)
manifest = subprocess.check_output([str(build_tools / "aapt"), "dump", "xmltree", str(apk), "AndroidManifest.xml"], text=True)
assert "application-debuggable" not in badging, "Never distribute a debuggable APK"
assert "PreviewActivity" not in manifest, "Preview activity must not be packaged"
for permission in ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.RECORD_AUDIO"]:
    assert permission not in manifest, "Unexpected permission: " + permission
signature = subprocess.check_output([str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
certificate = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", signature)[1]
assert certificate == (root / ".local-signing/certificate.sha256").read_text().strip(), "Signing identity changed; do not distribute this as an update"
(validation / "apk-manifest.txt").write_text(manifest)
(validation / "apk-signature.txt").write_text(signature)
(validation / "release-gates.json").write_text(json.dumps({"debuggable": False, "preview_activity": False,
    "internet_permission": False, "certificate_sha256": certificate}, indent=2) + "\n")
# Archive only the reviewed Git snapshot. Never sweep local reports or secrets.
subprocess.run(["python3", "tools/check_public_source.py"], cwd=root, check=True)
tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
files = [root / name for name in tracked if name]
archive = dist / f"mz-music-{version}-source.zip"
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
    for path in sorted(files):
        info = zipfile.ZipInfo("mz-music/" + path.relative_to(root).as_posix(), (2026, 11, 19, 0, 0, 0))
        info.external_attr = (0o100755 if path.stat().st_mode & 0o111 else 0o100644) << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        output.writestr(info, path.read_bytes())
(dist / "SHA256SUMS.txt").write_text("".join(
    hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name + "\n" for path in [apk, archive]))
print(f"Packaged mz music {version}: APK, source ZIP and SHA-256 checksums")
