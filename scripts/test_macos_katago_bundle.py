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
from unittest import mock
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
MODULE_PATH = SCRIPT_DIR / "macos_katago_bundle.py"
BUILD_SCRIPT_PATH = SCRIPT_DIR / "build_macos_katago.sh"
SPEC = importlib.util.spec_from_file_location("macos_katago_bundle", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {MODULE_PATH}")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

TOOLS_SPEC = importlib.util.spec_from_file_location(
    "verify_macos_release_tools", SCRIPT_DIR / "verify_macos_release_tools.py"
)
if TOOLS_SPEC is None or TOOLS_SPEC.loader is None:
    raise RuntimeError("Unable to load macOS release tool verifier")
RELEASE_TOOLS = importlib.util.module_from_spec(TOOLS_SPEC)
TOOLS_SPEC.loader.exec_module(RELEASE_TOOLS)


class MacosReleaseToolchainTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.home = Path(temporary.name) / "JDK 21" / "Contents" / "Home"
        (self.home / "bin").mkdir(parents=True)
        for name in RELEASE_TOOLS.JAVA_TOOLS:
            (self.home / "bin" / name).touch()
        self.catalog = {"origin": "project-source-build"}
        patcher = mock.patch.object(RELEASE_TOOLS.platform, "system", return_value="Darwin")
        patcher.start()
        self.addCleanup(patcher.stop)
        patcher = mock.patch.object(RELEASE_TOOLS.shutil, "which", side_effect=self.which)
        patcher.start()
        self.addCleanup(patcher.stop)
        patcher = mock.patch.object(
            RELEASE_TOOLS.subprocess, "run",
            return_value=subprocess.CompletedProcess([], 0, "Apache Maven 3.9.16\nJava version: 21.0.12, vendor: Eclipse Adoptium\n"),
        )
        self.process_run = patcher.start()
        self.addCleanup(patcher.stop)

    def which(self, name: str) -> str:
        if name in RELEASE_TOOLS.JAVA_TOOLS:
            return str(self.home / "bin" / name)
        return str(self.home.parent / "tools" / name)

    def test_existing_tools_are_checked_without_installing_or_upgrading(self) -> None:
        result = RELEASE_TOOLS.verify_tools(self.catalog, str(self.home))
        self.assertEqual(str(self.home.resolve()), result["javaHome"])
        self.process_run.assert_called_once()
        self.assertEqual([self.which("mvn"), "-version"], self.process_run.call_args.args[0])
        self.assertEqual(30, self.process_run.call_args.kwargs["timeout"])

    def test_missing_packaging_tool_fails_before_maven(self) -> None:
        for missing in ("mvn", "codesign", "otool"):
            with self.subTest(tool=missing), mock.patch.object(
                RELEASE_TOOLS.shutil, "which",
                side_effect=lambda name: None if name == missing else self.which(name),
            ), self.assertRaisesRegex(RuntimeError, "Required release tool is missing"):
                RELEASE_TOOLS.verify_tools(self.catalog, str(self.home))
        self.process_run.assert_not_called()

    def test_missing_or_shadowed_jdk_tool_is_rejected(self) -> None:
        (self.home / "bin" / "jpackage").unlink()
        with self.assertRaisesRegex(RuntimeError, "jpackage must resolve"):
            RELEASE_TOOLS.verify_tools(self.catalog, str(self.home))
        (self.home / "bin" / "jpackage").touch()
        with mock.patch.object(RELEASE_TOOLS.shutil, "which", return_value="/other/jdk/bin/java"):
            with self.assertRaisesRegex(RuntimeError, "java must resolve"):
                RELEASE_TOOLS.verify_tools(self.catalog, str(self.home))
        self.process_run.assert_not_called()

    def test_maven_using_a_different_java_version_is_rejected(self) -> None:
        for version in ("17.0.15", "27", "210.0.1"):
            self.process_run.return_value = subprocess.CompletedProcess([], 0, f"Java version: {version}\n")
            with self.subTest(version=version), self.assertRaisesRegex(RuntimeError, "Maven must use JDK 21"):
                RELEASE_TOOLS.verify_tools(self.catalog, str(self.home))

    def test_maven_start_failure_is_not_ignored(self) -> None:
        self.process_run.side_effect = subprocess.CalledProcessError(1, "mvn")
        with self.assertRaises(subprocess.CalledProcessError):
            RELEASE_TOOLS.verify_tools(self.catalog, str(self.home))

    def test_wrong_host_unpinned_catalog_and_missing_java_home_are_rejected(self) -> None:
        with mock.patch.object(RELEASE_TOOLS.platform, "system", return_value="Windows"):
            with self.assertRaisesRegex(RuntimeError, "native macOS host"):
                RELEASE_TOOLS.verify_tools(self.catalog, str(self.home))
        with self.assertRaisesRegex(RuntimeError, "pinned project-source-build"):
            RELEASE_TOOLS.verify_tools({"origin": "official-release"}, str(self.home))
        with self.assertRaisesRegex(RuntimeError, "JAVA_HOME"):
            RELEASE_TOOLS.verify_tools(self.catalog, "")
        self.process_run.assert_not_called()

    def test_both_release_workflows_verify_instead_of_upgrading_homebrew(self) -> None:
        for arch in ("amd64", "arm64"):
            workflow = (SCRIPT_DIR.parent / f".github/workflows/build-macos-{arch}-release.yml").read_text("utf-8")
            with self.subTest(arch=arch):
                self.assertIn("python3 scripts/verify_macos_release_tools.py", workflow)
                self.assertNotIn("brew update", workflow)
                self.assertNotIn("brew install", workflow)
                self.assertLess(workflow.index("verify_macos_release_tools.py"), workflow.index("mvn -DskipTests package"))
                self.assertIn("sign_macos_release_with_retry.sh", workflow)
                self.assertIn("validate_release_assets.sh", workflow)


class MacosKataGoBuildScriptTest(unittest.TestCase):
    def test_homebrew_libzip_paths_are_passed_explicitly_to_cmake(self) -> None:
        script = BUILD_SCRIPT_PATH.read_text(encoding="utf-8")
        self.assertIn("brew --prefix libzip", script)
        self.assertIn('-DLIBZIP_INCLUDE_DIR_ZIP="$libzip_include"', script)
        self.assertIn('-DLIBZIP_INCLUDE_DIR_ZIPCONF="$libzip_include"', script)
        self.assertIn('-DLIBZIP_LIBRARY="$libzip_library"', script)


class MacosKataGoBundleUnitTest(unittest.TestCase):
    def test_deployment_parser_covers_modern_legacy_and_universal_slices(self) -> None:
        modern = "Load command 9\n cmd LC_BUILD_VERSION\n platform macos\n minos 15.0\n sdk 26.5\n"
        legacy = "Load command 8\n cmd LC_VERSION_MIN_MACOSX\n version 10.15\n sdk 15.0\n"
        self.assertEqual(["15.0", "10.15"], MODULE.macos_deployment_versions(modern + legacy))
        self.assertEqual(["15.0"], MODULE.macos_deployment_versions(modern.replace("macos", "1")))

    def test_deployment_parser_rejects_missing_and_non_macos_commands(self) -> None:
        for value in (
            "", "Load command 1\n cmd LC_RPATH\n path /lib\n",
            "Load command 1\n cmd LC_BUILD_VERSION\n platform ios\n minos 15.0\n",
            "Load command 1\n cmd LC_BUILD_VERSION\n platform macos\n sdk 26.5\n",
            "Load command 1\n cmd LC_VERSION_MIN_IPHONEOS\n version 15.0\n",
        ):
            with self.subTest(value=value), self.assertRaises(MODULE.BundleError):
                MODULE.macos_deployment_versions(value)

    def test_newer_host_dylib_is_rejected_even_when_executable_supports_macos15(self) -> None:
        for version in ("15.1", "26.0"):
            output = f"Load command 1\n cmd LC_BUILD_VERSION\n platform macos\n minos {version}\n"
            with mock.patch.object(MODULE, "run", return_value=subprocess.CompletedProcess([], 0, output)):
                with self.assertRaisesRegex(MODULE.BundleError, "libprotobuf.*requires macOS"):
                    MODULE.audit_macos_deployment(Path("/bundle/lib/libprotobuf.dylib"))

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

    def test_rewrite_keeps_signature_until_install_names_are_updated(self) -> None:
        target = Path("/bundle/lib/libexample.dylib")
        node = MODULE.BinaryNode(
            source=Path("/source/libexample.dylib"),
            target=target,
            executable=False,
            edges=[
                MODULE.DependencyEdge(
                    original="@rpath/libdependency.dylib",
                    source=Path("/source/libdependency.dylib"),
                    bundled_name="libdependency.dylib",
                )
            ],
        )
        commands: list[list[str]] = []

        def record(command: list[str], **_kwargs: object) -> subprocess.CompletedProcess[str]:
            commands.append(command)
            return subprocess.CompletedProcess(command, 0, "", "")

        with mock.patch.object(MODULE, "run", side_effect=record):
            MODULE.rewrite_binary(node)

        self.assertEqual(
            commands,
            [
                [
                    "install_name_tool",
                    "-change",
                    "@rpath/libdependency.dylib",
                    "@loader_path/libdependency.dylib",
                    str(target),
                ],
                [
                    "install_name_tool",
                    "-id",
                    "@loader_path/libexample.dylib",
                    str(target),
                ],
            ],
        )
        self.assertFalse(
            any("--remove-signature" in command for command in commands),
            "Removing a signature before rewriting regresses Xcode 16 compatibility",
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
                    "-mmacosx-version-min=15.0",
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
                    "-mmacosx-version-min=15.0",
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
                ["clang", "-mmacosx-version-min=15.0", str(main_source), str(middle), "-o", str(executable)],
                check=True,
            )
            for binary in (leaf, middle, executable):
                subprocess.run(
                    ["codesign", "--force", "--sign", "-", str(binary)],
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
