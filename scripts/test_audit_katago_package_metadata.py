#!/usr/bin/env python3

from pathlib import Path
import tempfile
import unittest

from audit_katago_package_metadata import (
    PackageMetadataAuditError,
    audit_package_metadata,
)


class AuditKataGoPackageMetadataTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.root = Path(self.temp_dir.name)
        self.version_file = self.root / "VERSION.txt"
        self.engine_manifest = self.root / "engine-manifest.txt"

    def write_metadata(
        self,
        display_version: str = "v1.17.2",
        engine_version: str = "v1.17.2",
        display_asset: str = "katago-v1.17.2-trt.zip",
        engine_asset: str = "katago-v1.17.2-trt.zip",
    ) -> None:
        self.version_file.write_text(
            f"KataGo release: {display_version}\n"
            f"Windows TensorRT bundle: {display_asset}\n",
            encoding="utf-8",
        )
        self.engine_manifest.write_text(
            f"KataGo release: {engine_version}\nAsset: {engine_asset}\n",
            encoding="utf-8",
        )

    def test_accepts_matching_version_and_asset(self) -> None:
        self.write_metadata()
        audit_package_metadata(
            self.version_file,
            self.engine_manifest,
            "1.17.2",
            "katago-v1.17.2-trt.zip",
        )

    def test_rejects_stale_display_version(self) -> None:
        self.write_metadata(display_version="v1.17.1")
        with self.assertRaises(PackageMetadataAuditError):
            audit_package_metadata(
                self.version_file,
                self.engine_manifest,
                "1.17.2",
                "katago-v1.17.2-trt.zip",
            )

    def test_rejects_asset_mismatch(self) -> None:
        self.write_metadata(display_asset="katago-v1.17.1-trt.zip")
        with self.assertRaises(PackageMetadataAuditError):
            audit_package_metadata(
                self.version_file,
                self.engine_manifest,
                "1.17.2",
                "katago-v1.17.2-trt.zip",
            )


if __name__ == "__main__":
    unittest.main()
