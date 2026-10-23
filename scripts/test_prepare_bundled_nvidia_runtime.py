#!/usr/bin/env python3
"""Regression tests for the self-contained Windows NVIDIA runtime bundles."""

from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
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
    def test_current_docs_distinguish_bundled_runtime_from_optional_downloads(self) -> None:
        root = Path(__file__).parents[1]
        expectations = {
            "docs/INSTALL.md": ("已内置所需官方 NVIDIA 运行库", "重新安装 NVIDIA 完整包"),
            "docs/INSTALL_EN.md": (
                "includes the required official NVIDIA runtime files",
                "reinstall the full NVIDIA package",
            ),
            "docs/RELEASE_NOTES_TEMPLATE.md": (
                "已内置所需官方 NVIDIA 运行库", "TensorRT",
            ),
            "docs/RELEASE_CHECKLIST.md": (
                "已内置所需官方 NVIDIA 运行库",
                "app/engines/katago/windows-x64/lizzieyzy-next-nvidia-runtime-manifest.txt",
            ),
        }
        forbidden = (
            "首次自动准备官方运行库", "首次会自动准备官方运行库",
            "首次需要时会自动准备官方运行库", "第一次需要时自动准备官方运行库",
            "自动把需要的官方 NVIDIA 运行库准备到你的用户目录",
            "automatically prepares the required official NVIDIA runtime files in your user folder",
            "auto-prepare the official runtime on first use",
            "公式ランタイムの自動準備", "공식 런타임 자동 준비",
            "runtime/nvidia-runtime/manifest.txt",
        )
        for relative_path, required_phrases in expectations.items():
            with self.subTest(document=relative_path):
                text = (root / relative_path).read_text(encoding="utf-8")
                for phrase in required_phrases:
                    self.assertTrue(phrase in text, f"{relative_path}: missing {phrase!r}")
                for phrase in forbidden:
                    self.assertFalse(phrase in text, f"{relative_path}: stale {phrase!r}")

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

    def test_tensorrt_runtime_uses_mandatory_official_sha256(self) -> None:
        profile = NVIDIA_RUNTIME.RUNTIME_PROFILES["cuda12.8-cudnn9-tensorrt"]
        packages = NVIDIA_RUNTIME.load_direct_package_specs(profile["direct_specs"])

        self.assertEqual(1, len(packages))
        self.assertEqual("tensorrt", packages[0]["key"])
        self.assertEqual(NVIDIA_RUNTIME.TENSORRT_10_9_SHA256, packages[0]["sha256"])
        self.assertEqual(
            "c2758eb60191f01a47b24f54700e5463f577ebe129cd18fe835d0aa9f1e1a16d",
            packages[0]["sha256"],
        )

        broken = dict(profile["direct_specs"][0])
        broken["sha256"] = ""
        with self.assertRaisesRegex(
            NVIDIA_RUNTIME.RuntimeErrorWithContext, "Missing or invalid pinned SHA-256"
        ):
            NVIDIA_RUNTIME.load_direct_package_specs((broken,))

    def test_cuda_12_8_profile_requires_exact_nvrtc_manifest_package(self) -> None:
        valid = [
            {
                "key": "cuda_nvrtc",
                "version": NVIDIA_RUNTIME.CUDA_12_8_NVRTC_VERSION,
                "sha256": NVIDIA_RUNTIME.CUDA_12_8_NVRTC_SHA256,
            }
        ]
        NVIDIA_RUNTIME.validate_profile_packages("cuda12.8-fixture", valid)

        wrong_version = [dict(valid[0], version="12.8.60")]
        with self.assertRaisesRegex(
            NVIDIA_RUNTIME.RuntimeErrorWithContext, "requires CUDA NVRTC 12.8.61"
        ):
            NVIDIA_RUNTIME.validate_profile_packages("cuda12.8-fixture", wrong_version)

    def test_profile_preparation_verifies_archive_and_real_output_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source_archive = root / "fixture-runtime.zip"
            cache_dir = root / "cache"
            output_dir = root / "output"
            with ZipFile(source_archive, "w") as archive:
                archive.writestr("fixture/bin/nvrtc64_120_0.dll", b"compiler")
                archive.writestr("fixture/bin/nvrtc-builtins64_128.dll", b"builtins")
                archive.writestr("fixture/LICENSE.txt", b"license")
            digest = hashlib.sha256(source_archive.read_bytes()).hexdigest()
            cached_archive = cache_dir / "archives" / source_archive.name
            cached_archive.parent.mkdir(parents=True)
            shutil.copy2(source_archive, cached_archive)
            output_dir.mkdir()
            (output_dir / "stale.dll").write_bytes(b"stale")
            profile = {
                "manifest_specs": (),
                "direct_specs": (
                    {
                        "display_name": "Fixture NVRTC",
                        "key": "fixture_nvrtc",
                        "version": "12.8.61",
                        "url": source_archive.as_uri(),
                        "sha256": digest,
                        "size_bytes": source_archive.stat().st_size,
                        "dll_patterns": ("*.dll",),
                    },
                ),
            }

            packages, extracted = NVIDIA_RUNTIME.prepare_runtime_profile(
                "fixture", profile, cache_dir, output_dir
            )

            self.assertEqual(digest, packages[0]["sha256"])
            self.assertEqual(
                {"nvrtc64_120_0.dll", "nvrtc-builtins64_128.dll"}, set(extracted)
            )
            self.assertEqual(b"compiler", (output_dir / "nvrtc64_120_0.dll").read_bytes())
            self.assertEqual(
                b"builtins", (output_dir / "nvrtc-builtins64_128.dll").read_bytes()
            )
            self.assertFalse((output_dir / "stale.dll").exists())
            manifest = (output_dir / NVIDIA_RUNTIME.MANIFEST_FILE_NAME).read_text(
                encoding="utf-8"
            )
            self.assertIn("Profile: fixture", manifest)
            self.assertIn(f"sha256={digest}", manifest)

    def test_windows_packager_pins_runtime_digests_and_companion(self) -> None:
        package_script = Path(__file__).with_name("package_windows_exe.sh").read_text(
            encoding="utf-8"
        )
        java_runtime_helper = (
            Path(__file__).parents[1]
            / "src/main/java/featurecat/lizzie/util/KataGoRuntimeHelper.java"
        ).read_text(encoding="utf-8")
        companion_pins = Path(__file__).with_name("katago_windows_pins.sh").read_text(
            encoding="utf-8"
        )
        catalog = json.loads(
            (
                Path(__file__).parents[1]
                / "src/main/resources/katago-assets.json"
            ).read_text(encoding="utf-8")
        )
        companion_sha256 = catalog["assets"]["windows-nvidia"]["executableSha256"]

        self.assertIn('HUMAN_SL_CUDA_COMPANION_NAME="katago-human-sl-cuda.exe"', package_script)
        self.assertIn(
            "assets.windows-nvidia.executableSha256", companion_pins
        )
        self.assertIn(
            "assets.windows-tensorrt.assetName", companion_pins
        )
        self.assertIn("NVIDIA_CUDA_ASSET.executableSha256()", java_runtime_helper)
        self.assertIn("TENSORRT_KATAGO_ASSET_INFO.sha256()", java_runtime_helper)
        self.assertEqual(64, len(companion_sha256))
        self.assertIn('source "$ROOT_DIR/scripts/katago_windows_pins.sh"', package_script)
        self.assertIn(NVIDIA_RUNTIME.TENSORRT_10_9_SHA256, package_script)
        self.assertIn(NVIDIA_RUNTIME.CUDA_12_8_NVRTC_SHA256, package_script)
        self.assertIn("HumanSL companion SHA-256:", package_script)
        self.assertIn("shutil.copy2(companion_source, companion_target)", package_script)
        self.assertIn(
            'companion_source="$ROOT_DIR/engines/katago/'
            '$NVIDIA_ENGINE_PLATFORM_DIR/katago.exe"',
            package_script,
        )
        self.assertIn(
            'TensorRT HumanSL CUDA companion source is missing: $companion_source',
            package_script,
        )
        self.assertNotIn("NVIDIA50_CUDA_ENGINE_PLATFORM_DIR", package_script)

    def test_tensorrt_split_support_matrix_requires_unified_nvidia_cuda(self) -> None:
        package_script = Path(__file__).with_name("package_windows_exe.sh").read_text(
            encoding="utf-8"
        )
        gate = re.search(
            r'if \[\[ "\$\{WINDOWS_BUILD_TENSORRT_SPLIT:-true\}" == "true" \]\] \\\n'
            r'  && \[\[ -f "\$ROOT_DIR/weights/default\.bin\.gz" \]\] \\\n'
            r'  && \[\[ "\$has_nvidia_katago_assets" == "true" \]\]; then',
            package_script,
        )

        self.assertIsNotNone(gate)
        required_asset_flags = set(
            re.findall(r'\$has_([a-z0-9_]+)_katago_assets', gate.group(0))
        )
        self.assertEqual({"nvidia"}, required_asset_flags)
        self.assertNotIn("has_nvidia50_cuda_katago_assets", package_script)
        support_matrix = {
            (False, False): False,
            (True, False): True,
            (False, True): False,
            (True, True): True,
        }
        for (has_standard_nvidia, has_nvidia50_cuda), expected in support_matrix.items():
            with self.subTest(
                has_standard_nvidia=has_standard_nvidia,
                has_nvidia50_cuda=has_nvidia50_cuda,
            ):
                available_assets = {
                    "nvidia": has_standard_nvidia,
                }
                actual = all(available_assets[flag] for flag in required_asset_flags)
                self.assertEqual(expected, actual)


if __name__ == "__main__":
    unittest.main()
