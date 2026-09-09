#!/usr/bin/env python3
"""Regression tests for hardware-specific Windows download guidance."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
NOTES_PATH = ROOT / "scripts" / "generate_release_notes.py"
SPEC = importlib.util.spec_from_file_location("generate_release_notes", NOTES_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {NOTES_PATH}")
NOTES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(NOTES)


class WindowsDownloadGuidanceTest(unittest.TestCase):
    def test_current_user_guides_do_not_recommend_opencl_to_everyone(self) -> None:
        files = (
            ROOT / "README.md",
            ROOT / "docs" / "INSTALL.md",
            ROOT / "docs" / "PACKAGES.md",
            ROOT / "docs" / "PACKAGES_EN.md",
            ROOT / "docs" / "RELEASE_NOTES_TEMPLATE.md",
            ROOT / "assets" / "package-guide-zh.svg",
            ROOT / "scripts" / "package_windows_exe.sh",
        )
        combined = "\n".join(path.read_text(encoding="utf-8") for path in files)

        forbidden = (
            "Windows 大多数用户",
            "大多数普通用户先下 `windows64.opencl.portable.zip`",
            "Windows：先下 `*windows64.opencl.portable.zip`",
            "Windows 用户先下载 `<date>-windows64.opencl.portable.zip`",
            "Most regular users should start with `windows64.opencl.portable.zip`",
            "Windows: choose `windows64.opencl.portable.zip`",
            "Recommended Windows build for most users",
            "main recommended Windows choice",
            "大多数 Windows 用户先选 opencl portable",
        )
        for phrase in forbidden:
            self.assertNotIn(phrase, combined)

        self.assertIn("RTX 20/30/40/50 NVIDIA", combined)
        self.assertIn("AMD、Intel 或较老 NVIDIA", combined)
        self.assertIn("CPU 兼容版", combined)

        workflow = (
            ROOT / ".github" / "workflows" / "build-windows-release.yml"
        ).read_text(encoding="utf-8")
        install_note_fragment = "NVIDIA CUDA package for RTX 20/30/40/50"
        self.assertIn(install_note_fragment, combined)
        self.assertIn(install_note_fragment, workflow)

    def test_future_release_notes_put_cuda_before_opencl(self) -> None:
        date_tag = "2026-08-27"
        asset_map = {
            asset.key: asset.filename.render(date_tag)
            for asset in NOTES.topology.assets()
        }
        asset_map["windows_tensorrt_split_parts"] = [
            NOTES.topology.asset("windows_tensorrt_split_001").filename.render(date_tag),
            NOTES.topology.asset("windows_tensorrt_split_002").filename.render(date_tag),
        ]
        notes = NOTES.build_release_notes(
            asset_map,
            {
                "katago_version": "v1.18.1",
                "model_source": "kata1-tf3-b11c768-s11500M-d6163M.bin.gz",
            },
            "wimi321/lizzieyzy-next",
            "next-2099-01-01.1",
        )

        forbidden = (
            "recommended no-install OpenCL build",
            "main recommended Windows choice",
            "推奨 OpenCL 版",
            "추천 OpenCL 무설치",
            "OpenCL รุ่นแนะนำ",
            "OpenCL 版（推荐，免安装）",
            "OpenCL 版（推薦，免安裝）",
        )
        for phrase in forbidden:
            self.assertNotIn(phrase, notes)

        for language in NOTES.RELEASE_LANGUAGES:
            start = notes.index(f"## {language}\n")
            next_heading = notes.find("\n## ", start + 1)
            section = notes[start:] if next_heading < 0 else notes[start:next_heading]
            self.assertLess(
                section.index("windows64.nvidia.portable.zip"),
                section.index("windows64.opencl.portable.zip"),
            )


if __name__ == "__main__":
    unittest.main()
