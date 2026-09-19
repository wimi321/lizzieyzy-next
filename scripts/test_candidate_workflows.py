#!/usr/bin/env python3
"""Security and contract checks for non-publishing candidate workflows."""

from __future__ import annotations

from pathlib import Path
import re
import unittest



ROOT = Path(__file__).resolve().parents[1]
WORKFLOW_DIR = ROOT / ".github" / "workflows"
PACKAGE_WORKFLOWS = {
    "candidate-windows.yml": ("windows", "./scripts/package_windows_exe.sh"),
    "candidate-linux.yml": ("linux", "./scripts/package_release.sh"),
    "candidate-macos.yml": ("mac", "./scripts/package_macos_dmg.sh"),
}
ALL_WORKFLOWS = (*PACKAGE_WORKFLOWS, "candidate-java17.yml")


class CandidateWorkflowTest(unittest.TestCase):
    def text(self, name: str) -> str:
        return (WORKFLOW_DIR / name).read_text(encoding="utf-8")


    def test_every_candidate_workflow_is_read_only_non_publishing_and_bounded(self) -> None:
        forbidden = (
            "contents: write",
            "secrets.",
            "environment:",
            "gh release",
            "r2_release.py",
            "publish_release_request.py",
            "sign_macos_release",
        )
        for name in ALL_WORKFLOWS:
            with self.subTest(workflow=name):
                text = self.text(name)
                self.assertRegex(text, r"(?m)^on:\n  pull_request:\n")
                self.assertRegex(text, r"(?m)^  workflow_dispatch:")
                self.assertRegex(text, r"(?m)^permissions:\n  contents: read$")
                self.assertIn("actions/upload-artifact@v6", text)
                self.assertNotIn("sleep ", text)
                for token in forbidden:
                    self.assertNotIn(token, text)
                self.assertRegex(text, r"(?m)^jobs:\n")
                self.assertRegex(text, r"(?m)^    timeout-minutes: [1-9][0-9]*$")

    def test_package_workflows_call_foreground_authorities_and_upload_provenance(self) -> None:
        for name, (platform, package_command) in PACKAGE_WORKFLOWS.items():
            with self.subTest(workflow=name):
                text = self.text(name)
                self.assertIn(package_command, text)
                self.assertIn("scripts/validate_release_assets.sh", text)
                self.assertIn("scripts/release_asset_provenance.py", text)
                self.assertIn("release-asset-provenance.json", text)
                self.assertNotIn("candidate.json", text)
                self.assertNotIn("if: always()", text)
                if platform != "mac":
                    self.assertIn(f"--platform {platform}", text)
                else:
                    self.assertIn('--platform "$CANDIDATE_PLATFORM"', text)
                    self.assertIn("platform: mac-arm64", text)
                    self.assertIn("platform: mac-amd64", text)

    def test_path_dispatch_is_platform_local_but_shared_contracts_fan_out(self) -> None:
        shared = (
            "scripts/release_asset_provenance.py",
            "scripts/release_asset_topology.py",
            "scripts/release_metadata.sh",
            "scripts/package_runtime_tools.py",
            "scripts/generate_app_icons.py",
        )
        platform_paths = {
            "candidate-windows.yml": (
                "scripts/package_windows_exe.sh",
                "scripts/katago_windows_pins.sh",
            ),
            "candidate-linux.yml": ("scripts/package_release.sh",),
            "candidate-macos.yml": (
                "scripts/package_macos_dmg.sh",
                "scripts/macos_bundle_version.py",
                "scripts/create_macos_drag_dmg.sh",
                "scripts/validate_macos_dmg_layout.sh",
            ),
        }
        for name, local_paths in platform_paths.items():
            with self.subTest(workflow=name):
                text = self.text(name)
                for path in (*shared, *local_paths):
                    self.assertIn(repr(path), text)
                self.assertNotIn("'src/main/**'", text)

    def test_windows_candidate_uses_release_tag_installer_serial(self) -> None:
        text = self.text("candidate-windows.yml")
        self.assertIn('serial="${RELEASE_TAG##*.}"', text)
        self.assertIn('WINDOWS_BUILD_SERIAL="$serial"', text)
        self.assertNotIn("WINDOWS_BUILD_SERIAL: ${{ github.run_attempt }}", text)

    def test_java17_workflow_keeps_build_and_runtime_toolchains_distinct(self) -> None:
        text = self.text("candidate-java17.yml")
        self.assertIsNotNone(re.search(r"java-version: '21'.+Build full shaded JAR", text, re.DOTALL))
        self.assertIn("java-version: '17'", text)
        self.assertIn('JAVA_HOME="$JAVA_HOME_21_X64" mvn', text)
        self.assertIn(
            '-Dlizzie.test.java.executable="$JAVA_HOME_17_X64/bin/java"', text
        )
        static = text.index("--scenario static")
        application = text.index("--scenario application-smoke")
        self.assertLess(static, application)
        self.assertIn("target/lizzie-yzy2.5.3-shaded.jar", text)

    def test_dispatch_inputs_are_only_inert_environment_data(self) -> None:
        for name in PACKAGE_WORKFLOWS:
            with self.subTest(workflow=name):
                text = self.text(name)
                self.assertIn("INPUT_DATE_TAG: ${{ inputs.date_tag }}", text)
                self.assertIn("INPUT_RELEASE_TAG: ${{ inputs.release_tag }}", text)
                for match in re.finditer(r"run: [>|-]*\n(?P<body>(?:\s{10,}.+\n?)*)", text):
                    self.assertNotIn("${{ inputs.", match.group("body"))


if __name__ == "__main__":
    unittest.main()
