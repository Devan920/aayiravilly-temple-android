"""Publish a complete APK/update-manifest pair. Credentials stay in the runner environment."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

REPOSITORY = "Devan920/aayiravilly-temple-android"
PACKAGE = "in.aayiravilly.chittattinkara"
API = f"https://api.github.com/repos/{REPOSITORY}"


def api(path, method="GET", body=None):
    request = urllib.request.Request(API + path, method=method,
                                    data=None if body is None else json.dumps(body).encode("utf-8"), headers={
        "Authorization": "Bearer " + os.environ["GH_TOKEN"],
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "Aayiravilly-release-workflow",
        "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise RuntimeError(f"GitHub API returned HTTP {error.code}") from None


def find_release(tag):
    """The tag endpoint omits untagged drafts; authenticated listings include them."""
    page = 1
    matches = []
    while True:
        releases = api(f"/releases?per_page=100&page={page}")
        if not isinstance(releases, list):
            raise RuntimeError("Could not inspect existing releases, including drafts.")
        matches.extend(item for item in releases if item.get("tag_name") == tag)
        if len(releases) < 100:
            break
        page += 1
    if len(matches) > 1:
        raise ValueError("Multiple releases use this tag; inspect the drafts before publishing.")
    return matches[0] if matches else None


def configuration():
    if os.environ.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise ValueError("This release workflow is bound to the temple repository.")
    name = os.environ["VERSION_NAME"]
    code = os.environ["VERSION_CODE"]
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", name):
        raise ValueError("Use a stable three-part version such as 1.0.1.")
    if len(name) > 60 or not re.fullmatch(r"[1-9][0-9]{0,9}", code) or int(code) > 2_100_000_000:
        raise ValueError("Use an Android versionCode between 1 and 2100000000.")
    notes = os.environ.get("RELEASE_NOTES", "").strip()
    if not notes or len(notes) > 4000:
        raise ValueError("Release notes must contain between 1 and 4000 characters.")
    return name, int(code), notes


def validate(name, code):
    if api(f"/git/ref/tags/v{name}") or find_release(f"v{name}"):
        raise ValueError("That version/tag already exists. Inspect any draft before choosing another version.")
    latest = api("/releases/latest")
    if latest:
        assets = [item for item in latest["assets"] if item["name"] == "update.json"]
        if len(assets) != 1:
            raise ValueError("The current release has no unique update.json; repair it before publishing.")
        # Public release assets need no token; never forward API credentials to the asset CDN.
        url = assets[0]["browser_download_url"]
        if not url.startswith(f"https://github.com/{REPOSITORY}/releases/download/"):
            raise ValueError("Unexpected current-release manifest URL.")
        with urllib.request.urlopen(url, timeout=30) as response:
            content = response.read(65537)
        if len(content) > 65536:
            raise ValueError("The current update manifest is too large.")
        manifest = json.loads(content)
        if manifest.get("packageName") != PACKAGE or code <= int(manifest["versionCode"]):
            raise ValueError("Version code must increase from the latest temple release.")
        old_name = str(manifest["versionName"])
        if re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", old_name):
            if tuple(map(int, name.split('.'))) <= tuple(map(int, old_name.split('.'))):
                raise ValueError("Version name must increase from the latest temple release.")
    print(f"Validated release {name} (versionCode {code}).")


def publish(name, code, notes):
    validate(name, code)
    source = Path("app/build/outputs/apk/release/app-release.apk")
    if not source.is_file() or not 0 < source.stat().st_size <= 50 * 1024 * 1024:
        raise ValueError("Missing APK or APK exceeds the updater's 50 MB limit.")
    aapt = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0/aapt"
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(source)], text=True)
    expected = f"package: name='{PACKAGE}' versionCode='{code}' versionName='{name}'"
    if not any(line.startswith(expected) for line in badging.splitlines()):
        raise ValueError("APK identity/version does not match the release inputs.")
    artifact_dir = Path("app/build/release-artifacts")
    artifact_dir.mkdir(parents=True, exist_ok=True)
    apk = artifact_dir / f"Aayiravilly-Temple-v{name}.apk"
    shutil.copy2(source, apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    manifest = artifact_dir / "update.json"
    manifest.write_text(json.dumps({
        "versionCode": code,
        "versionName": name,
        "packageName": PACKAGE,
        "apkUrl": f"https://github.com/{REPOSITORY}/releases/download/v{name}/{apk.name}",
        "sha256": digest,
        "releaseNotes": notes,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    checksums = artifact_dir / "SHA256SUMS.txt"
    checksums.write_text(f"{digest}  {apk.name}\n", encoding="utf-8")
    body = notes + "\n\nAndroid asks for confirmation before installing an APK update.\n\n" \
        + f"APK SHA-256: `{digest}`\n"
    tag = "v" + name
    created = api("/releases", method="POST", body={
        "tag_name": tag, "target_commitish": os.environ["GITHUB_SHA"], "draft": True,
        "name": "Aayiravilly Temple " + name, "body": body, "prerelease": False,
    })
    if not isinstance(created, dict) or not isinstance(created.get("id"), int):
        raise RuntimeError("Draft creation returned no release ID; inspect GitHub before retrying.")
    release_id = created["id"]
    subprocess.run(["gh", "release", "upload", tag, "--repo", REPOSITORY,
                    str(apk), str(manifest), str(checksums)], check=True)
    release = api(f"/releases/{release_id}")
    if not isinstance(release, dict) or release.get("tag_name") != tag or not release.get("draft"):
        raise RuntimeError("Could not verify the expected draft release; publication stopped.")
    expected_assets = {file.name: file.stat().st_size for file in (apk, manifest, checksums)}
    actual_assets = {item["name"]: item["size"] for item in release["assets"] if item["state"] == "uploaded"}
    if actual_assets != expected_assets:
        raise ValueError("Uploaded release assets are incomplete. The release remains a draft.")
    published = api(f"/releases/{release_id}", method="PATCH", body={"draft": False, "make_latest": "true"})
    if not isinstance(published, dict) or published.get("draft") is not False:
        raise RuntimeError("GitHub did not confirm release publication; inspect its state before retrying.")
    print(f"Published https://github.com/{REPOSITORY}/releases/tag/{tag}")


if __name__ == "__main__":
    try:
        version_name, version_code, release_notes = configuration()
        if sys.argv[1:] == ["validate"]:
            validate(version_name, version_code)
        elif sys.argv[1:] == ["publish"]:
            publish(version_name, version_code, release_notes)
        else:
            raise ValueError("Usage: release.py validate|publish")
    except (KeyError, ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print(f"Release failed: {error}", file=sys.stderr)
        sys.exit(1)
