#!/usr/bin/env python3
"""Regression tests for the pinned JCEF release-bundle preparation helper."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT_PATH = Path(__file__).with_name("prepare_bundled_jcef.py")
SPEC = importlib.util.spec_from_file_location("prepare_bundled_jcef", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
JCEF = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(JCEF)


class PrepareBundledJcefTest(unittest.TestCase):
    def create_bundle(
        self,
        root: Path,
        package_platform: str = "windows-amd64",
        metadata_platform: str | None = None,
    ) -> None:
        for relative_name in JCEF.PLATFORM_PACKAGES[package_platform]["required"]:
            path = root / relative_name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"fixture")
        (root / "build_meta.json").write_text(
            json.dumps(
                {
                    "release_tag": JCEF.JCEF_RELEASE_TAG,
                    "platform": metadata_platform or package_platform,
                }
            ),
            encoding="utf-8",
        )

    def test_validate_bundle_rejects_wrong_platform(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self.create_bundle(root, metadata_platform="linux-amd64")

            with self.assertRaisesRegex(SystemExit, "JCEF platform mismatch"):
                JCEF.validate_bundle(root, "windows-amd64")

    def test_windows_locale_trim_keeps_supported_languages_only(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            locales = root / "locales"
            locales.mkdir()
            for name in JCEF.SUPPORTED_WINDOWS_LOCALES | {"fr.pak", "de.pak"}:
                (locales / name).write_bytes(name.encode("utf-8"))

            stats = JCEF.trim_optional_locales(root, "windows-amd64")

            self.assertEqual(2, stats["removedFiles"])
            self.assertEqual(len(b"fr.pak") + len(b"de.pak"), stats["removedBytes"])
            self.assertEqual(len(JCEF.SUPPORTED_WINDOWS_LOCALES), stats["retainedFiles"])
            self.assertEqual(
                JCEF.SUPPORTED_WINDOWS_LOCALES,
                {path.name for path in locales.iterdir()},
            )

    def test_macos_locale_trim_keeps_supported_languages_only(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            resources = root / "Chromium Embedded Framework.framework" / "Resources"
            resources.mkdir(parents=True)
            for name in JCEF.SUPPORTED_MACOS_LOCALES | {"de.lproj", "fr.lproj"}:
                locale = resources / name
                locale.mkdir()
                (locale / "locale.pak").write_bytes(name.encode("utf-8"))

            stats = JCEF.trim_optional_locales(root, "macosx-arm64")

            self.assertEqual(2, stats["removedFiles"])
            self.assertEqual(len(b"de.lproj") + len(b"fr.lproj"), stats["removedBytes"])
            self.assertEqual(len(JCEF.SUPPORTED_MACOS_LOCALES), stats["retainedFiles"])
            self.assertEqual(
                JCEF.SUPPORTED_MACOS_LOCALES,
                {path.name for path in resources.iterdir()},
            )

    def test_macos_bundles_are_pinned_for_both_chip_families(self) -> None:
        self.assertEqual(
            "jcef-natives-macosx-arm64",
            JCEF.PLATFORM_PACKAGES["macosx-arm64"]["artifact"],
        )
        self.assertEqual(
            "1746a503e38614ea3e4fe7986e22443ab48a3a245ba1f4b17575aaccab5e7994",
            JCEF.PLATFORM_PACKAGES["macosx-arm64"]["sha256"],
        )
        self.assertEqual(
            "jcef-natives-macosx-amd64",
            JCEF.PLATFORM_PACKAGES["macosx-amd64"]["artifact"],
        )
        self.assertEqual(
            "36ed38af450dff481513c352a92a88aaa73ec34a399edadc7a4a947c7d1ddaed",
            JCEF.PLATFORM_PACKAGES["macosx-amd64"]["sha256"],
        )

    def test_validate_macos_bundle_accepts_required_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self.create_bundle(root, package_platform="macosx-arm64")

            JCEF.validate_bundle(root, "macosx-arm64")


if __name__ == "__main__":
    unittest.main()
