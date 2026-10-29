"""Offline checks for release input and monotonic-version safeguards."""
import io
import json
import os
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

    def validate_with_previous(self, code=1, name="1.0.0", package=release.PACKAGE):
        previous = {"versionCode": code, "versionName": name, "packageName": package}
        latest = {"assets": [{"name": "update.json", "browser_download_url":
                              f"https://github.com/{release.REPOSITORY}/releases/download/v1.0.0/update.json"}]}
        with patch.object(release, "api", side_effect=[None, None, latest]), \
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


if __name__ == "__main__":
    unittest.main()
