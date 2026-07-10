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
    def create_bundle(self, root: Path, platform: str = "windows-amd64") -> None:
        for relative_name in JCEF.PLATFORM_PACKAGES["windows-amd64"]["required"]:
            path = root / relative_name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"fixture")
        (root / "build_meta.json").write_text(
            json.dumps({"release_tag": JCEF.JCEF_RELEASE_TAG, "platform": platform}),
            encoding="utf-8",
        )

    def test_validate_bundle_rejects_wrong_platform(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self.create_bundle(root, platform="linux-amd64")

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


if __name__ == "__main__":
    unittest.main()
