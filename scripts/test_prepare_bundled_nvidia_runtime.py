#!/usr/bin/env python3
"""Regression tests for the self-contained Windows NVIDIA runtime bundles."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile


SCRIPT_PATH = Path(__file__).with_name("prepare_bundled_nvidia_runtime.py")
SPEC = importlib.util.spec_from_file_location("prepare_bundled_nvidia_runtime", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
NVIDIA_RUNTIME = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(NVIDIA_RUNTIME)


class PrepareBundledNvidiaRuntimeTest(unittest.TestCase):
    def test_every_cuda_profile_includes_nvrtc(self) -> None:
        for profile_name, profile in NVIDIA_RUNTIME.RUNTIME_PROFILES.items():
            package_keys = {spec[2] for spec in profile["manifest_specs"]}
            self.assertIn(
                "cuda_nvrtc",
                package_keys,
                f"{profile_name} must include NVRTC for cuDNN runtime-compiled engines",
            )

    def test_nvrtc_archive_extracts_compiler_and_builtins(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            archive_path = root / "cuda_nvrtc.zip"
            output_dir = root / "runtime"
            with ZipFile(archive_path, "w") as archive:
                archive.writestr("cuda_nvrtc/bin/nvrtc64_120_0.dll", b"compiler")
                archive.writestr("cuda_nvrtc/bin/nvrtc-builtins64_128.dll", b"builtins")
                archive.writestr("cuda_nvrtc/LICENSE.txt", b"license")

            extracted = NVIDIA_RUNTIME.extract_package(
                {"key": "cuda_nvrtc", "dll_patterns": ("*.dll",)},
                archive_path,
                output_dir,
            )

            self.assertEqual(
                {"nvrtc64_120_0.dll", "nvrtc-builtins64_128.dll"}, set(extracted)
            )
            self.assertTrue((output_dir / "nvrtc64_120_0.dll").is_file())
            self.assertTrue((output_dir / "nvrtc-builtins64_128.dll").is_file())

    def test_tensorrt_packager_embeds_humansl_cuda_companion(self) -> None:
        package_script = Path(__file__).with_name("package_windows_exe.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn('HUMAN_SL_CUDA_COMPANION_NAME="katago-human-sl-cuda.exe"', package_script)
        self.assertIn("HumanSL companion SHA-256:", package_script)
        self.assertIn("shutil.copy2(companion_source, companion_target)", package_script)


if __name__ == "__main__":
    unittest.main()
