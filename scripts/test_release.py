"""Offline checks for release input and monotonic-version safeguards."""
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import release


class ReleaseValidationTests(unittest.TestCase):
    def setUp(self):
        self.environment = patch.dict(os.environ, {
            "GITHUB_REPOSITORY": release.REPOSITORY,
            "VERSION_NAME": "1.0.1", "VERSION_CODE": "2", "RELEASE_NOTES": "Better startup.",
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def test_valid_configuration(self):
        self.assertEqual(release.configuration(), ("1.0.1", 2, "Better startup."))

    def test_version_input_cannot_be_shell_or_path(self):
        for bad in ("1.0.1; echo unsafe", "../release", "v1.0.1", "1.0.1-beta", "1.00.1"):
            with self.subTest(bad=bad), patch.dict(os.environ, {"VERSION_NAME": bad}):
                with self.assertRaises(ValueError):
                    release.configuration()

    def test_invalid_android_version_codes(self):
        for bad in ("0", "-1", "2.5", "2147483647", "02", "2 --bad"):
            with self.subTest(bad=bad), patch.dict(os.environ, {"VERSION_CODE": bad}):
                with self.assertRaises(ValueError):
                    release.configuration()

    def test_wrong_repository_rejected(self):
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "another/repository"}):
            with self.assertRaises(ValueError):
                release.configuration()

    def test_existing_tag_is_never_replaced(self):
        with patch.object(release, "api", return_value={"ref": "exists"}):
            with self.assertRaisesRegex(ValueError, "already exists"):
                release.validate("1.0.1", 2)

    def test_existing_untagged_draft_is_never_replaced(self):
        with patch.object(release, "api", side_effect=[None, [{"id": 41, "tag_name": "v1.0.1", "draft": True}]]):
            with self.assertRaisesRegex(ValueError, "already exists"):
                release.validate("1.0.1", 2)

    def test_draft_lookup_paginates(self):
        first_page = [{"tag_name": f"v0.0.{number}", "id": number} for number in range(100)]
        draft = {"id": 101, "tag_name": "v1.0.1", "draft": True}
        with patch.object(release, "api", side_effect=[first_page, [draft]]) as api:
            self.assertEqual(release.find_release("v1.0.1"), draft)
            self.assertEqual(api.call_args_list[1].args, ("/releases?per_page=100&page=2",))

    def test_failed_draft_listing_fails_closed(self):
        with patch.object(release, "api", return_value=None):
            with self.assertRaises(RuntimeError):
                release.find_release("v1.0.1")

    def test_duplicate_drafts_are_rejected(self):
        with patch.object(release, "api", return_value=[{"tag_name": "v1.0.1"}, {"tag_name": "v1.0.1"}]):
            with self.assertRaises(ValueError):
                release.find_release("v1.0.1")

    def validate_with_previous(self, code=1, name="1.0.0", package=release.PACKAGE):
        previous = {"versionCode": code, "versionName": name, "packageName": package}
        latest = {"assets": [{"name": "update.json", "browser_download_url":
                              f"https://github.com/{release.REPOSITORY}/releases/download/v1.0.0/update.json"}]}
        with patch.object(release, "api", side_effect=[None, [], latest]), \
                patch.object(release.urllib.request, "urlopen", return_value=io.BytesIO(json.dumps(previous).encode())):
            release.validate("1.0.1", 2)

    def test_increasing_version_accepted(self):
        self.validate_with_previous()

    def test_same_or_lower_version_code_rejected(self):
        for code in (2, 3):
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, "Version code"):
                self.validate_with_previous(code=code)

    def test_decreasing_version_name_rejected(self):
        with self.assertRaisesRegex(ValueError, "Version name"):
            self.validate_with_previous(name="1.1.0")

    def test_foreign_package_rejected(self):
        with self.assertRaises(ValueError):
            self.validate_with_previous(package="com.other.app")

    def simulate_publication(self, missing_asset=False):
        calls = []
        old_cwd = Path.cwd()
        with tempfile.TemporaryDirectory() as temporary:
            os.chdir(temporary)
            try:
                apk = Path("app/build/outputs/apk/release/app-release.apk")
                apk.parent.mkdir(parents=True)
                apk.write_bytes(b"test APK bytes")

                def fake_api(path, method="GET", body=None):
                    calls.append((path, method, body))
                    if path == "/releases" and method == "POST":
                        return {"id": 777, "draft": True}
                    if path == "/releases/777" and method == "GET":
                        files = sorted(Path("app/build/release-artifacts").glob("*"))
                        if missing_asset:
                            files = files[:-1]
                        return {"id": 777, "tag_name": "v1.0.1", "draft": True, "assets": [
                            {"name": file.name, "size": file.stat().st_size, "state": "uploaded"} for file in files]}
                    if path == "/releases/777" and method == "PATCH":
                        return {"id": 777, "draft": False}
                    raise AssertionError(f"Unexpected API operation: {path} {method}")

                with patch.dict(os.environ, {"ANDROID_HOME": "sdk", "GITHUB_SHA": "a" * 40}), \
                        patch.object(release, "validate"), patch.object(release, "api", side_effect=fake_api), \
                        patch.object(release.subprocess, "run"), patch.object(release.subprocess, "check_output", return_value=
                            f"package: name='{release.PACKAGE}' versionCode='2' versionName='1.0.1'"):
                    if missing_asset:
                        with self.assertRaisesRegex(ValueError, "incomplete"):
                            release.publish("1.0.1", 2, "Test release")
                    else:
                        release.publish("1.0.1", 2, "Test release")
            finally:
                os.chdir(old_cwd)
        return calls

    def test_draft_verified_and_published_by_created_id(self):
        calls = self.simulate_publication()
        self.assertEqual([(path, method) for path, method, body in calls], [
            ("/releases", "POST"), ("/releases/777", "GET"), ("/releases/777", "PATCH")])
        self.assertEqual(calls[-1][2], {"draft": False, "make_latest": "true"})

    def test_incomplete_draft_is_not_published(self):
        calls = self.simulate_publication(missing_asset=True)
        self.assertFalse(any(method == "PATCH" for path, method, body in calls))


if __name__ == "__main__":
    unittest.main()
