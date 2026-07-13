#!/usr/bin/env python3
"""Regression tests for tag-specific multi-language release notes."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


SCRIPT_PATH = Path(__file__).with_name("generate_release_notes.py")
SPEC = importlib.util.spec_from_file_location("generate_release_notes", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
NOTES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(NOTES)


class GenerateReleaseNotesTest(unittest.TestCase):
    def setUp(self) -> None:
        date_tag = "2026-07-13"
        self.asset_map = {
            key: f"{date_tag}-{suffix}"
            for key, suffix, _cn, _en in NOTES.ASSET_SPECS
        }
        self.asset_map.update(
            {
                "windows_core_update": f"{date_tag}-windows64.core-update.zip",
                "windows_tensorrt_split_readme": (
                    f"{date_tag}-windows64.nvidia.tensorrt.portable.README.txt"
                ),
                "windows_tensorrt_split_parts": [
                    f"{date_tag}-windows64.nvidia.tensorrt.portable.7z.001",
                    f"{date_tag}-windows64.nvidia.tensorrt.portable.7z.002",
                ],
                "windows_tensorrt_split_sha256": (
                    f"{date_tag}-windows64.nvidia.tensorrt.portable.sha256.txt"
                ),
                "windows_tensorrt_split_manifest": (
                    f"{date_tag}-windows64.nvidia.tensorrt.portable.manifest.json"
                ),
            }
        )

    def build_notes(self, release_tag: str) -> str:
        return NOTES.build_release_notes(
            self.asset_map,
            {
                "katago_version": "v1.16.5",
                "model_source": "test-model.bin.gz",
            },
            "wimi321/lizzieyzy-next",
            release_tag,
        )

    def assert_current_release_content(self, notes: str) -> None:
        for language in NOTES.RELEASE_LANGUAGES:
            self.assertEqual(1, notes.count(f"## {language}\n"))
        self.assertIn("PR #106", notes)
        self.assertIn("智子云算力", notes)
        self.assertIn("Zhizi Cloud", notes)
        self.assertIn("100% / 150% / 200%", notes)
        self.assertIn("windows64.nvidia50.cuda.portable.zip", notes)
        self.assertIn("windows64.nvidia.tensorrt.portable.7z.001", notes)
        self.assertNotIn("知子", notes)
        self.assertNotIn("新增“腾讯棋谱”入口", notes)
        self.assertNotIn("新增“騰訊棋譜”入口", notes)

    def test_next_2026_07_13_1_notes_match_the_already_published_build(self) -> None:
        notes = self.build_notes("next-2026-07-13.1")

        self.assert_current_release_content(notes)
        self.assertIn("默认简体中文", notes)
        self.assertNotIn("自动匹配电脑的系统语言", notes)
        self.assertNotIn("matching system language", notes)

    def test_next_2026_07_13_2_notes_include_system_language_detection(self) -> None:
        notes = self.build_notes("next-2026-07-13.2")

        self.assert_current_release_content(notes)
        self.assertIn("自动匹配电脑的系统语言", notes)
        self.assertIn("matching system language", notes)


if __name__ == "__main__":
    unittest.main()
