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
topology = NOTES.topology


class GenerateReleaseNotesTest(unittest.TestCase):
    def setUp(self) -> None:
        date_tag = "2026-07-13"
        self.asset_map = {
            asset.key: asset.filename.render(date_tag)
            for asset in topology.assets()
        }
        self.asset_map["windows_tensorrt_split_parts"] = [
            topology.asset("windows_tensorrt_split_001").filename.render(date_tag),
            topology.asset("windows_tensorrt_split_002").filename.render(date_tag),
        ]

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
        self.assertIn("windows64.nvidia.portable.zip", notes)
        self.assertIn("windows64.experimental.directml.portable.zip", notes)
        self.assertNotIn("windows64.nvidia50.cuda.portable.zip", notes)
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

    def test_bundle_metadata_comes_from_the_shared_katago_asset_catalog(self) -> None:
        metadata = NOTES.load_bundle_metadata()

        self.assertEqual("v1.18.1", metadata["katago_version"])
        self.assertEqual(
            "kata1-tf3-b11c768-s11500M-d6163M.bin.gz", metadata["model_source"]
        )
        self.assertEqual(
            "katago-v1.18.1-cuda12.8-cudnn9.8.0-windows-x64.zip",
            metadata["windows_nvidia_bundle"],
        )
        self.assertEqual(
            metadata["windows_nvidia_bundle"],
            metadata["windows_nvidia50_cuda_bundle"],
        )


if __name__ == "__main__":
    unittest.main()
