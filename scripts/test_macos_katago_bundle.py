#!/usr/bin/env python3
"""Regression tests for the self-contained macOS KataGo bundler."""

from __future__ import annotations

import importlib.util
import platform
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
MODULE_PATH = SCRIPT_DIR / "macos_katago_bundle.py"
SPEC = importlib.util.spec_from_file_location("macos_katago_bundle", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {MODULE_PATH}")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class MacosKataGoBundleUnitTest(unittest.TestCase):
    def test_system_dependencies_are_allowed(self) -> None:
        self.assertTrue(MODULE.is_system_dependency("/usr/lib/libc++.1.dylib"))
        self.assertTrue(
            MODULE.is_system_dependency(
                "/System/Library/Frameworks/CoreML.framework/Versions/A/CoreML"
            )
        )
        self.assertFalse(
            MODULE.is_system_dependency(
                "/opt/homebrew/opt/protobuf/lib/libprotobuf.35.1.0.dylib"
            )
        )
        self.assertFalse(
            MODULE.is_system_dependency("/usr/local/opt/libzip/lib/libzip.5.dylib")
        )

    def test_otool_dependency_parser(self) -> None:
        output = """/tmp/katago:
\t/usr/lib/libc++.1.dylib (compatibility version 1.0.0, current version 1900.178.0)
\t/opt/homebrew/opt/protobuf/lib/libprotobuf.35.1.0.dylib (compatibility version 35.0.0, current version 35.1.0)
"""
        self.assertEqual(
            MODULE.parse_otool_dependencies(output),
            [
                "/usr/lib/libc++.1.dylib",
                "/opt/homebrew/opt/protobuf/lib/libprotobuf.35.1.0.dylib",
            ],
        )


@unittest.skipUnless(
    platform.system() == "Darwin" and shutil.which("clang"),
    "Mach-O integration test requires macOS and clang",
)
class MacosKataGoBundleIntegrationTest(unittest.TestCase):
    def test_recursive_dependencies_work_after_sources_are_removed(self) -> None:
        with tempfile.TemporaryDirectory(prefix="katago-bundle-test.") as temp:
            root = Path(temp)
            source = root / "source"
            output = root / "bundle"
            source.mkdir()

            leaf_source = source / "leaf.c"
            middle_source = source / "middle.c"
            main_source = source / "main.c"
            leaf_source.write_text(
                "int leaf_value(void) { return 41; }\n",
                encoding="ascii",
            )
            middle_source.write_text(
                "extern int leaf_value(void);\n"
                "int middle_value(void) { return leaf_value() + 1; }\n",
                encoding="ascii",
            )
            main_source.write_text(
                "#include <stdio.h>\n"
                "#include <string.h>\n"
                "extern int middle_value(void);\n"
                "int main(int argc, char **argv) {\n"
                "  if (argc > 1 && strcmp(argv[1], \"version\") == 0) {\n"
                "    printf(\"KataGo v9.9.9 fixture\\n\");\n"
                "  }\n"
                "  return middle_value() == 42 ? 0 : 2;\n"
                "}\n",
                encoding="ascii",
            )

            leaf = source / "libleaf.1.dylib"
            middle = source / "libmiddle.1.dylib"
            executable = source / "katago"
            subprocess.run(
                [
                    "clang",
                    "-dynamiclib",
                    str(leaf_source),
                    "-install_name",
                    str(leaf),
                    "-o",
                    str(leaf),
                ],
                check=True,
            )
            subprocess.run(
                [
                    "clang",
                    "-dynamiclib",
                    str(middle_source),
                    str(leaf),
                    "-install_name",
                    str(middle),
                    "-o",
                    str(middle),
                ],
                check=True,
            )
            subprocess.run(
                ["clang", str(main_source), str(middle), "-o", str(executable)],
                check=True,
            )

            subprocess.run(
                [
                    "python3",
                    str(MODULE_PATH),
                    "bundle",
                    "--katago",
                    str(executable),
                    "--output",
                    str(output),
                    "--expected-version",
                    "9.9.9",
                ],
                check=True,
            )
            shutil.move(str(source), str(root / "source.removed"))
            subprocess.run(
                [
                    "python3",
                    str(MODULE_PATH),
                    "audit",
                    "--bundle",
                    str(output),
                    "--expected-version",
                    "9.9.9",
                ],
                check=True,
            )
            result = subprocess.run(
                [str(output / "katago"), "version"],
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertIn("KataGo v9.9.9", result.stdout)
            dependencies = subprocess.run(
                ["otool", "-L", str(output / "katago")],
                check=True,
                capture_output=True,
                text=True,
            ).stdout
            self.assertNotIn(str(source), dependencies)
            self.assertIn("@executable_path/lib/libmiddle.1.dylib", dependencies)

            missing_library = output / "lib" / "libleaf.1.dylib"
            shutil.move(str(missing_library), str(root / missing_library.name))
            failed_audit = subprocess.run(
                [
                    "python3",
                    str(MODULE_PATH),
                    "audit",
                    "--bundle",
                    str(output),
                ],
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(0, failed_audit.returncode)
            self.assertIn(
                "libraries do not match bundle-manifest.json",
                failed_audit.stderr,
            )


if __name__ == "__main__":
    unittest.main()
