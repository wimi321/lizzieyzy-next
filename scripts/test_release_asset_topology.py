#!/usr/bin/env python3
"""Contract tests for the 发布资产拓扑."""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts import generate_release_notes as notes
from scripts import publish_release_request as publisher
from scripts import release_asset_provenance as provenance
from scripts import release_asset_topology as topology


DATE_TAG = "2026-08-19"
SCRIPT = Path(__file__).with_name("release_asset_topology.py")

WINDOWS_PUBLIC = (
    f"{DATE_TAG}-windows64.opencl.installer.exe",
    f"{DATE_TAG}-windows64.opencl.portable.zip",
    f"{DATE_TAG}-windows64.nvidia.installer.exe",
    f"{DATE_TAG}-windows64.nvidia.portable.zip",
    f"{DATE_TAG}-windows64.experimental.directml.portable.zip",
    f"{DATE_TAG}-windows64.experimental.openvino.portable.zip",
    f"{DATE_TAG}-windows64.experimental.rocm.gfx103x.portable.zip",
    f"{DATE_TAG}-windows64.experimental.rocm.gfx110x.portable.zip",
    f"{DATE_TAG}-windows64.experimental.rocm.gfx1151.portable.zip",
    f"{DATE_TAG}-windows64.experimental.rocm.gfx120x.portable.zip",
    f"{DATE_TAG}-windows64.with-katago.installer.exe",
    f"{DATE_TAG}-windows64.with-katago.portable.zip",
    f"{DATE_TAG}-windows64.without.engine.installer.exe",
    f"{DATE_TAG}-windows64.without.engine.portable.zip",
    f"{DATE_TAG}-windows64.core-update.zip",
    "lizzieyzy-next-update-manifest.json",
    f"{DATE_TAG}-windows64.nvidia.tensorrt.portable.7z.001",
    f"{DATE_TAG}-windows64.nvidia.tensorrt.portable.7z.002",
    f"{DATE_TAG}-windows64.nvidia.tensorrt.portable.README.txt",
    f"{DATE_TAG}-windows64.nvidia.tensorrt.portable.manifest.json",
    f"{DATE_TAG}-windows64.nvidia.tensorrt.portable.sha256.txt",
)
LINUX_PUBLIC = (
    f"{DATE_TAG}-linux64.opencl.zip",
    f"{DATE_TAG}-linux64.nvidia.zip",
    f"{DATE_TAG}-linux64.with-katago.zip",
)
MAC_ARM64_PUBLIC = (f"{DATE_TAG}-mac-apple-silicon.with-katago.dmg",)
MAC_AMD64_PUBLIC = (f"{DATE_TAG}-mac-intel.with-katago.dmg",)
DIRECT_DOWNLOAD = (
    f"{DATE_TAG}-windows64.opencl.portable.zip",
    f"{DATE_TAG}-windows64.core-update.zip",
    f"{DATE_TAG}-windows64.opencl.installer.exe",
    f"{DATE_TAG}-windows64.with-katago.portable.zip",
    f"{DATE_TAG}-windows64.with-katago.installer.exe",
    f"{DATE_TAG}-windows64.nvidia.portable.zip",
    f"{DATE_TAG}-windows64.nvidia.installer.exe",
    f"{DATE_TAG}-windows64.experimental.directml.portable.zip",
    f"{DATE_TAG}-windows64.experimental.openvino.portable.zip",
    f"{DATE_TAG}-windows64.experimental.rocm.gfx103x.portable.zip",
    f"{DATE_TAG}-windows64.experimental.rocm.gfx110x.portable.zip",
    f"{DATE_TAG}-windows64.experimental.rocm.gfx1151.portable.zip",
    f"{DATE_TAG}-windows64.experimental.rocm.gfx120x.portable.zip",
    f"{DATE_TAG}-windows64.nvidia.tensorrt.portable.7z.001",
    f"{DATE_TAG}-windows64.nvidia.tensorrt.portable.7z.002",
    f"{DATE_TAG}-windows64.without.engine.portable.zip",
    f"{DATE_TAG}-windows64.without.engine.installer.exe",
    f"{DATE_TAG}-mac-apple-silicon.with-katago.dmg",
    f"{DATE_TAG}-mac-intel.with-katago.dmg",
    f"{DATE_TAG}-linux64.with-katago.zip",
    f"{DATE_TAG}-linux64.opencl.zip",
    f"{DATE_TAG}-linux64.nvidia.zip",
)
NOTES_TABLE_KEYS = (
    "windows_opencl_portable",
    "windows_opencl_installer",
    "windows_portable",
    "windows_installer",
    "windows_nvidia_portable",
    "windows_nvidia_installer",
    "windows_directml_experimental",
    "windows_openvino_experimental",
    "windows_rocm_gfx103x_experimental",
    "windows_rocm_gfx110x_experimental",
    "windows_rocm_gfx1151_experimental",
    "windows_rocm_gfx120x_experimental",
    "windows_no_engine_portable",
    "windows_no_engine_installer",
    "mac_arm64",
    "mac_amd64",
    "linux64",
    "linux64_opencl",
    "linux64_nvidia",
)
WORKFLOW_UNITS = (
    (
        "windows",
        "Windows",
        "build-windows-release.yml",
        "Windows release {release_tag} | {date_tag} | prerelease={prerelease}",
    ),
    (
        "linux",
        "Linux",
        "build-linux-release.yml",
        "Linux release {release_tag} | {date_tag} | prerelease={prerelease}",
    ),
    (
        "mac-amd64",
        "macOS Intel",
        "build-macos-amd64-release.yml",
        "macOS Intel release {release_tag} | {date_tag} | prerelease={prerelease}",
    ),
    (
        "mac-arm64",
        "macOS Apple Silicon",
        "build-macos-arm64-release.yml",
        "macOS Apple Silicon release {release_tag} | {date_tag} | prerelease={prerelease}",
    ),
)


class ReleaseAssetTopologyContractTest(unittest.TestCase):
    def test_universe_has_exactly_the_current_26_public_assets(self) -> None:
        keys = tuple(asset.key for asset in topology.assets())
        names = tuple(asset.filename.render(DATE_TAG) for asset in topology.assets())

        self.assertEqual(26, len(keys))
        self.assertEqual(26, len(set(keys)))
        self.assertEqual(
            WINDOWS_PUBLIC + LINUX_PUBLIC + MAC_AMD64_PUBLIC + MAC_ARM64_PUBLIC,
            names,
        )
        self.assertEqual(
            1,
            sum(asset.filename.kind is topology.FilenameKind.LITERAL for asset in topology.assets()),
        )
        manifest = topology.asset("windows_update_manifest")
        self.assertEqual("lizzieyzy-next-update-manifest.json", manifest.filename.value)
        self.assertEqual(
            (
                f"{DATE_TAG}-windows64.nvidia.tensorrt.portable.7z.001",
                f"{DATE_TAG}-windows64.nvidia.tensorrt.portable.7z.002",
            ),
            (
                topology.asset("windows_tensorrt_split_001").filename.render(DATE_TAG),
                topology.asset("windows_tensorrt_split_002").filename.render(DATE_TAG),
            ),
        )

    def test_platform_inventories_match_current_public_sets(self) -> None:
        self.assertEqual(WINDOWS_PUBLIC, topology.public_inventory("windows", DATE_TAG))
        self.assertEqual(LINUX_PUBLIC, topology.public_inventory("linux", DATE_TAG))
        self.assertEqual(MAC_ARM64_PUBLIC, topology.public_inventory("mac-arm64", DATE_TAG))
        self.assertEqual(MAC_AMD64_PUBLIC, topology.public_inventory("mac-amd64", DATE_TAG))

    def test_role_queries_preserve_current_subsets_and_order(self) -> None:
        self.assertEqual(DIRECT_DOWNLOAD, topology.direct_download_names(DATE_TAG))
        self.assertEqual(
            NOTES_TABLE_KEYS,
            tuple(asset.key for asset in topology.release_notes_table_assets()),
        )
        notes_keys = {asset.key for asset in topology.release_notes_assets()}
        self.assertTrue(
            {
                "windows_core_update",
                "windows_tensorrt_split_001",
                "windows_tensorrt_split_002",
            }.issubset(notes_keys)
        )
        self.assertNotIn("windows_update_manifest", notes_keys)
        self.assertEqual(
            tuple(sorted(WINDOWS_PUBLIC)),
            topology.provenance_names("windows", DATE_TAG),
        )
        self.assertEqual(
            tuple(sorted(LINUX_PUBLIC)),
            topology.provenance_names("linux", DATE_TAG),
        )

    def test_release_units_own_workflow_and_platform_identity(self) -> None:
        units = topology.release_units()
        self.assertEqual(
            WORKFLOW_UNITS,
            tuple(
                (
                    unit.platform,
                    unit.publisher_identity,
                    unit.workflow_file,
                    unit.run_name_template,
                )
                for unit in units
            ),
        )
        self.assertEqual(
            WINDOWS_PUBLIC,
            tuple(asset.filename.render(DATE_TAG) for asset in topology.release_unit("windows").assets),
        )
        for unit in units:
            self.assertEqual((("release_prerelease", "true"),), unit.dispatch_inputs)

    def test_unsupported_platform_or_invalid_date_tag_fails(self) -> None:
        with self.assertRaisesRegex(topology.TopologyError, "Unsupported platform"):
            topology.public_inventory("win32", DATE_TAG)
        with self.assertRaisesRegex(topology.TopologyError, "YYYY-MM-DD"):
            topology.public_inventory("linux", "20260819")
        with self.assertRaisesRegex(topology.TopologyError, "YYYY-MM-DD"):
            topology.public_inventory("linux", "")


class ExpectedNamesCliTest(unittest.TestCase):
    def run_cli(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), *args],
            check=False,
            capture_output=True,
            text=True,
        )

    def test_prints_one_filename_per_line_in_public_order(self) -> None:
        result = self.run_cli(
            "expected-names",
            "--platform",
            "linux",
            "--date-tag",
            DATE_TAG,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("\n".join(LINUX_PUBLIC) + "\n", result.stdout)

    def test_rejects_unsupported_platform_and_invalid_date_tag(self) -> None:
        unsupported = self.run_cli(
            "expected-names",
            "--platform",
            "win32",
            "--date-tag",
            DATE_TAG,
        )
        self.assertNotEqual(0, unsupported.returncode)
        self.assertIn("Unsupported platform", unsupported.stderr)
        invalid = self.run_cli(
            "expected-names",
            "--platform",
            "linux",
            "--date-tag",
            "19-08-2026",
        )
        self.assertNotEqual(0, invalid.returncode)


class TopologyDeletionTest(unittest.TestCase):
    def test_consumers_have_no_independent_asset_catalog(self) -> None:
        self.assertFalse(hasattr(notes, "ASSET_SPECS"))
        self.assertFalse(hasattr(notes, "TENSORRT_SPLIT_PART_PATTERN"))
        self.assertFalse(hasattr(publisher, "DIRECT_DOWNLOAD_SUFFIXES"))
        self.assertFalse(hasattr(provenance, "PLATFORM_ASSET_SUFFIXES"))
        self.assertFalse(hasattr(publisher.WORKFLOWS[0], "exact_suffixes"))
        self.assertEqual(
            topology.direct_download_names(DATE_TAG),
            publisher.direct_download_names(DATE_TAG),
        )
        self.assertEqual(
            topology.provenance_names("linux", DATE_TAG),
            provenance.expected_asset_names("linux", DATE_TAG),
        )
        self.assertEqual(
            [unit.workflow_file for unit in topology.release_units()],
            [spec.workflow_file for spec in publisher.WORKFLOWS],
        )


class LinuxValidatorSmokeTest(unittest.TestCase):
    validator = Path(__file__).with_name("validate_release_assets.sh")

    def test_validator_stays_compatible_with_macos_bash_3(self) -> None:
        validator_text = self.validator.read_text(encoding="utf-8")
        self.assertNotRegex(validator_text, r"\b(?:mapfile|readarray)\b")

    def run_validator(self, release_dir: Path) -> subprocess.CompletedProcess[str]:
        bash = os.environ.get("LIZZIE_BASH") or shutil.which("bash") or "bash"
        env = os.environ.copy()
        if os.name == "nt" and Path(bash).is_absolute():
            env.update(
                {
                    "DATE_TAG": DATE_TAG,
                    "LIZZIE_PYTHON": os.environ.get("LIZZIE_PYTHON", sys.executable),
                    "RELEASE_DIR_PATH": str(release_dir),
                    "VALIDATOR_PATH": str(self.validator),
                }
            )
            command = [
                bash,
                "-lc",
                (
                    'export PYTHON_BIN="$(cygpath -u "$LIZZIE_PYTHON")"; '
                    'bash "$(cygpath -u "$VALIDATOR_PATH")" linux '
                    '"$(cygpath -u "$RELEASE_DIR_PATH")" "$DATE_TAG"'
                ),
            ]
        else:
            command = [
                bash,
                str(self.validator),
                "linux",
                str(release_dir),
                DATE_TAG,
            ]
        return subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            env=env,
        )

    def write_linux_inventory(self, release_dir: Path, names: tuple[str, ...]) -> None:
        release_dir.mkdir(parents=True, exist_ok=True)
        for name in names:
            (release_dir / name).write_bytes(b"asset")

    def test_accepts_exact_linux_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            release_dir = Path(temp)
            self.write_linux_inventory(release_dir, LINUX_PUBLIC)
            result = self.run_validator(release_dir)
            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
            self.assertIn("Validated public release assets for linux", result.stdout)

    def test_rejects_missing_expected_linux_asset(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            release_dir = Path(temp)
            self.write_linux_inventory(release_dir, LINUX_PUBLIC[1:])
            result = self.run_validator(release_dir)
            self.assertNotEqual(0, result.returncode)
            combined = result.stdout + result.stderr
            self.assertTrue(
                "Missing expected asset" in combined or "Unexpected asset count" in combined,
                combined,
            )

    def test_rejects_unexpected_helper_asset(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            release_dir = Path(temp)
            self.write_linux_inventory(release_dir, LINUX_PUBLIC)
            (release_dir / f"{DATE_TAG}-linux64-sha256.txt").write_text("deadbeef\n")
            result = self.run_validator(release_dir)
            self.assertNotEqual(0, result.returncode)
            combined = result.stdout + result.stderr
            self.assertTrue(
                "Unexpected helper file" in combined or "Unexpected asset" in combined,
                combined,
            )


if __name__ == "__main__":
    unittest.main()
