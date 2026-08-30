#!/usr/bin/env python3
"""Regression tests for exact Windows release asset validation."""

from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from scripts import validate_windows_release_assets as validator


DATE_TAG = "2026-08-19"
RELEASE_TAG = f"next-{DATE_TAG}.1"


class WindowsReleaseAssetValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.release_dir = Path(self.temporary_directory.name)
        self.core_name = f"{DATE_TAG}-windows64.core-update.zip"
        self.prefix = f"{DATE_TAG}-windows64.nvidia.tensorrt.portable.7z"
        self.part_names = [f"{self.prefix}.001", f"{self.prefix}.002"]
        self.readme_name = f"{DATE_TAG}-windows64.nvidia.tensorrt.portable.README.txt"
        self.manifest_name = f"{DATE_TAG}-windows64.nvidia.tensorrt.portable.manifest.json"
        self.checksum_name = f"{DATE_TAG}-windows64.nvidia.tensorrt.portable.sha256.txt"
        self.create_valid_fixture()

    def write_json(self, name: str, value: dict[str, object]) -> None:
        (self.release_dir / name).write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    def read_json(self, name: str) -> dict[str, object]:
        return json.loads((self.release_dir / name).read_text(encoding="utf-8"))

    def write_checksums(self, names: list[str] | None = None) -> None:
        if names is None:
            names = [*self.part_names, self.readme_name, self.manifest_name]
        lines = [
            f"{validator.sha256(self.release_dir / name)}  {name}"
            for name in names
        ]
        (self.release_dir / self.checksum_name).write_text(
            "\n".join(lines) + "\n",
            encoding="utf-8",
        )

    def create_valid_fixture(self) -> None:
        core_path = self.release_dir / self.core_name
        with zipfile.ZipFile(core_path, "w") as archive:
            archive.writestr("app/lizzie-yzy2.5.3-shaded.jar", b"jar")
            archive.writestr("app/LizzieYzy Next.cfg", b"cfg")
            archive.writestr("lizzieyzy-next-core.jar", b"alias")
            archive.writestr("README.txt", b"readme")
            archive.writestr("lizzieyzy-next-core-update-manifest.json", b"{}")
        core_hash = validator.sha256(core_path)
        self.write_json(
            "lizzieyzy-next-update-manifest.json",
            {
                "schemaVersion": 1,
                "releaseTag": RELEASE_TAG,
                "publishedAt": f"{DATE_TAG}T00:00:00Z",
                "notesUrl": (
                    "https://github.com/wimi321/lizzieyzy-next/releases/tag/"
                    f"{RELEASE_TAG}"
                ),
                "prerelease": True,
                "components": [
                    {
                        "id": "core",
                        "platform": "windows",
                        "flavor": "all",
                        "version": RELEASE_TAG,
                        "assetName": self.core_name,
                        "downloadUrl": (
                            "https://github.com/wimi321/lizzieyzy-next/releases/download/"
                            f"{RELEASE_TAG}/{self.core_name}"
                        ),
                        "sizeBytes": core_path.stat().st_size,
                        "sha256": core_hash,
                        "installAction": "replace-core",
                        "defaultSelectedIfChanged": True,
                    }
                ],
            },
        )

        for index, name in enumerate(self.part_names, 1):
            (self.release_dir / name).write_bytes(f"part-{index}".encode("ascii"))
        (self.release_dir / self.readme_name).write_text(
            "both parts required\n",
            encoding="utf-8",
        )
        self.write_json(
            self.manifest_name,
            {
                "dateTag": DATE_TAG,
                "releaseDisplayVersion": RELEASE_TAG,
                "assetKind": "optional-tensorrt-split-package",
                "archivePrefix": self.prefix,
                "engineBackend": "nvidia-tensorrt",
                "parts": [
                    {
                        "name": name,
                        "sizeBytes": (self.release_dir / name).stat().st_size,
                        "sha256": validator.sha256(self.release_dir / name),
                    }
                    for name in self.part_names
                ],
            },
        )
        self.write_checksums()

    def validate(self, prerelease: bool = True) -> None:
        validator.validate_windows_release_assets(
            self.release_dir,
            DATE_TAG,
            RELEASE_TAG,
            prerelease,
        )

    def test_accepts_exact_release_identity_and_integrity(self) -> None:
        self.validate()

    def test_rejects_missing_or_extra_tensorrt_volume(self) -> None:
        (self.release_dir / self.part_names[1]).unlink()
        with self.assertRaisesRegex(validator.ValidationError, "exactly contiguous"):
            self.validate()

        (self.release_dir / self.part_names[1]).write_bytes(b"part-2")
        (self.release_dir / f"{self.prefix}.003").write_bytes(b"stale-part")
        with self.assertRaisesRegex(validator.ValidationError, "exactly contiguous"):
            self.validate()

    def test_rejects_manifest_part_order_size_or_hash_mismatch(self) -> None:
        for field, value, message in (
            ("name", self.part_names[1], "not contiguous/in order"),
            ("sizeBytes", 999, "size mismatch"),
            ("sha256", "0" * 64, "SHA mismatch"),
        ):
            with self.subTest(field=field):
                self.create_valid_fixture()
                manifest = self.read_json(self.manifest_name)
                parts = manifest["parts"]
                assert isinstance(parts, list) and isinstance(parts[0], dict)
                parts[0][field] = value
                self.write_json(self.manifest_name, manifest)
                self.write_checksums()
                with self.assertRaisesRegex(validator.ValidationError, message):
                    self.validate()

    def test_rejects_incomplete_or_incorrect_checksum_inventory(self) -> None:
        self.write_checksums([*self.part_names, self.readme_name])
        with self.assertRaisesRegex(validator.ValidationError, "checksum inventory"):
            self.validate()

        self.write_checksums()
        checksum_path = self.release_dir / self.checksum_name
        checksum_path.write_text(
            checksum_path.read_text(encoding="utf-8").replace(
                validator.sha256(self.release_dir / self.part_names[0]),
                "0" * 64,
                1,
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(validator.ValidationError, "checksum mismatch"):
            self.validate()

    def test_rejects_update_manifest_release_identity_mismatch(self) -> None:
        for field, value, message in (
            ("releaseTag", "next-2026-08-18.1", "releaseTag"),
            ("publishedAt", "2026-08-18T00:00:00Z", "publishedAt"),
            (
                "notesUrl",
                "https://github.com/example/repo/releases/tag/next-old",
                "notesUrl",
            ),
        ):
            with self.subTest(field=field):
                self.create_valid_fixture()
                manifest = self.read_json("lizzieyzy-next-update-manifest.json")
                manifest[field] = value
                self.write_json("lizzieyzy-next-update-manifest.json", manifest)
                with self.assertRaisesRegex(validator.ValidationError, message):
                    self.validate()

    def test_rejects_manifest_urls_that_only_share_a_matching_suffix(self) -> None:
        manifest = self.read_json("lizzieyzy-next-update-manifest.json")
        expected_notes = str(manifest["notesUrl"])
        components = manifest["components"]
        assert isinstance(components, list) and isinstance(components[0], dict)
        expected_download = str(components[0]["downloadUrl"])

        for field, malicious, message in (
            (
                "notesUrl",
                f"https://evil.invalid/proxy/{expected_notes}",
                "notesUrl",
            ),
            (
                "notesUrl",
                f"{expected_notes}?redirect=https://evil.invalid",
                "notesUrl",
            ),
            (
                "downloadUrl",
                f"https://evil.invalid/proxy/{expected_download}",
                "downloadUrl",
            ),
            (
                "downloadUrl",
                f"{expected_download}#stale-replacement",
                "downloadUrl",
            ),
        ):
            with self.subTest(field=field):
                self.create_valid_fixture()
                manifest = self.read_json("lizzieyzy-next-update-manifest.json")
                if field == "notesUrl":
                    manifest[field] = malicious
                else:
                    items = manifest["components"]
                    assert isinstance(items, list) and isinstance(items[0], dict)
                    items[0][field] = malicious
                self.write_json("lizzieyzy-next-update-manifest.json", manifest)
                with self.assertRaisesRegex(validator.ValidationError, message):
                    self.validate()

    def test_rejects_update_manifest_prerelease_mismatch(self) -> None:
        with self.assertRaisesRegex(validator.ValidationError, "prerelease"):
            self.validate(prerelease=False)

    def test_rejects_core_component_version_or_download_tag_mismatch(self) -> None:
        for field, value, message in (
            ("version", "next-old", "version"),
            ("downloadUrl", "https://example.invalid/wrong.zip", "downloadUrl"),
        ):
            with self.subTest(field=field):
                self.create_valid_fixture()
                manifest = self.read_json("lizzieyzy-next-update-manifest.json")
                components = manifest["components"]
                assert isinstance(components, list) and isinstance(components[0], dict)
                components[0][field] = value
                self.write_json("lizzieyzy-next-update-manifest.json", manifest)
                with self.assertRaisesRegex(validator.ValidationError, message):
                    self.validate()

    def test_rejects_tensorrt_manifest_release_tag_mismatch(self) -> None:
        manifest = self.read_json(self.manifest_name)
        manifest["releaseDisplayVersion"] = "next-old"
        self.write_json(self.manifest_name, manifest)
        self.write_checksums()
        with self.assertRaisesRegex(validator.ValidationError, "releaseDisplayVersion"):
            self.validate()


class WindowsReleaseValidationWiringTest(unittest.TestCase):
    root = Path(__file__).resolve().parents[1]

    def test_windows_workflow_passes_exact_release_identity_to_validator(self) -> None:
        workflow = (self.root / ".github/workflows/build-windows-release.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn('echo "release_prerelease=$release_prerelease"', workflow)
        self.assertIn(
            "RELEASE_METADATA_PRERELEASE: "
            "${{ steps.release_metadata.outputs.release_prerelease }}",
            workflow,
        )
        self.assertIn('"$RELEASE_METADATA_PRERELEASE"', workflow)
        self.assertNotIn(
            '"${{ steps.release_metadata.outputs.release_prerelease }}"', workflow
        )
        self.assertIn("scripts/validate_release_assets.sh", workflow)
        validator_wrapper = (self.root / "scripts/validate_release_assets.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("validate_windows_release_assets.py", validator_wrapper)

    def test_package_and_publisher_require_exact_two_tensorrt_volumes(self) -> None:
        package_script = (self.root / "scripts/package_windows_exe.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("${#split_parts[@]} != 2", package_script)
        self.assertIn(".7z.001", package_script)
        self.assertIn(".7z.002", package_script)

    def test_windows_ci_runs_jcef_tests_with_isolated_work_directories(self) -> None:
        workflow = (self.root / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        local_ci = (self.root / "scripts/run_local_ci.py").read_text(encoding="utf-8")
        self.assertIn("scripts/run_local_ci.py --profile windows", workflow)
        self.assertIn('"scripts/test_prepare_bundled_jcef.py"', local_ci)
        self.assertIn('"scripts.test_validate_windows_release_assets"', local_ci)
        self.assertIn("tempfile.gettempdir()", local_ci)
        self.assertIn("temp / 'credential-tests'", local_ci)
        self.assertIn("temp / 'full-tests'", local_ci)


if __name__ == "__main__":
    unittest.main()
