#!/usr/bin/env python3
"""Tests for run-bound release asset checksum provenance."""

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts import release_asset_provenance as provenance


DATE_TAG = "2026-08-19"
RELEASE_TAG = f"next-{DATE_TAG}.1"
TARGET_SHA = "a" * 40
RUN_ID = 123
RUN_ATTEMPT = 2
SCRIPT = Path(__file__).with_name("release_asset_provenance.py")
REPO_ROOT = SCRIPT.parent.parent


class ReleaseAssetProvenanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.release_dir = Path(self.temporary_directory.name)
        for index, name in enumerate(
            provenance.expected_asset_names("linux", DATE_TAG), 1
        ):
            (self.release_dir / name).write_bytes(f"asset-{index}".encode("ascii"))
        self.asset_name = provenance.expected_asset_names("linux", DATE_TAG)[-1]
        self.asset_file = self.release_dir / self.asset_name
        self.manifest_file = self.release_dir / provenance.PROVENANCE_FILENAME
        self.write_manifest(self.payload())

    def payload(self) -> dict[str, object]:
        return provenance.build_provenance(
            self.release_dir,
            "linux",
            DATE_TAG,
            RELEASE_TAG,
            TARGET_SHA,
            RUN_ID,
            RUN_ATTEMPT,
        )

    def validate(self, payload: object) -> dict[str, dict[str, object]]:
        return provenance.validate_provenance(
            payload,
            platform="linux",
            date_tag=DATE_TAG,
            release_tag=RELEASE_TAG,
            target_sha=TARGET_SHA,
            run_id=RUN_ID,
            run_attempt=RUN_ATTEMPT,
        )

    def write_manifest(
        self, payload: object, path: Path | None = None
    ) -> Path:
        destination = path or self.manifest_file
        destination.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return destination

    def verify_candidate(self, **overrides: object) -> dict[str, object]:
        arguments: dict[str, object] = {
            "manifest_path": self.manifest_file,
            "platform": "linux",
            "date_tag": DATE_TAG,
            "release_tag": RELEASE_TAG,
            "target_sha": TARGET_SHA,
            "run_id": RUN_ID,
            "run_attempt": RUN_ATTEMPT,
            "asset_name": self.asset_name,
            "asset_file": self.asset_file,
        }
        arguments.update(overrides)
        return provenance.verify_candidate(**arguments)

    def run_verify_cli(
        self, output: Path, *, asset_file: Path | None = None
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "verify-candidate",
                "--manifest",
                str(self.manifest_file),
                "--platform",
                "linux",
                "--date-tag",
                DATE_TAG,
                "--release-tag",
                RELEASE_TAG,
                "--target-sha",
                TARGET_SHA,
                "--run-id",
                str(RUN_ID),
                "--run-attempt",
                str(RUN_ATTEMPT),
                "--asset-name",
                self.asset_name,
                "--asset-file",
                str(asset_file or self.asset_file),
                "--output",
                str(output),
            ],
            check=False,
            capture_output=True,
            text=True,
        )

    def test_builds_and_validates_exact_sorted_inventory(self) -> None:
        payload = self.payload()
        records = self.validate(json.loads(json.dumps(payload)))

        self.assertEqual(
            list(provenance.expected_asset_names("linux", DATE_TAG)),
            list(records),
        )
        self.assertTrue(all(record["sizeBytes"] > 0 for record in records.values()))

    def test_rejects_missing_empty_or_changed_local_asset(self) -> None:
        name = provenance.expected_asset_names("linux", DATE_TAG)[0]
        (self.release_dir / name).unlink()
        with self.assertRaisesRegex(provenance.ProvenanceError, "Missing"):
            self.payload()

        (self.release_dir / name).write_bytes(b"")
        with self.assertRaisesRegex(provenance.ProvenanceError, "empty"):
            self.payload()

    def test_rejects_wrong_run_sha_tag_or_attempt(self) -> None:
        for field, value, message in (
            ("targetSha", "b" * 40, "targetSha"),
            ("releaseTag", "next-2026-08-19.2", "releaseTag"),
            ("workflowRunId", 124, "workflowRunId"),
            ("workflowRunAttempt", 1, "workflowRunAttempt"),
        ):
            with self.subTest(field=field):
                payload = self.payload()
                payload[field] = value
                with self.assertRaisesRegex(provenance.ProvenanceError, message):
                    self.validate(payload)

    def test_rejects_missing_extra_duplicate_or_reordered_assets(self) -> None:
        mutations = []
        missing = self.payload()
        assert isinstance(missing["assets"], list)
        missing["assets"].pop()
        mutations.append(missing)

        extra = self.payload()
        assert isinstance(extra["assets"], list)
        extra["assets"].append(
            {"name": "unexpected.zip", "sizeBytes": 1, "sha256": "0" * 64}
        )
        mutations.append(extra)

        duplicate = self.payload()
        assert isinstance(duplicate["assets"], list)
        duplicate["assets"].append(dict(duplicate["assets"][0]))
        mutations.append(duplicate)

        reordered = self.payload()
        assert isinstance(reordered["assets"], list)
        reordered["assets"].reverse()
        mutations.append(reordered)

        for payload in mutations:
            with self.subTest(assets=payload["assets"]):
                with self.assertRaises(provenance.ProvenanceError):
                    self.validate(payload)

    def test_rejects_non_positive_size_or_malformed_digest(self) -> None:
        for field, value, message in (
            ("sizeBytes", 0, "positive integer"),
            ("sha256", "0" * 63, "sha256"),
        ):
            with self.subTest(field=field):
                payload = self.payload()
                assets = payload["assets"]
                assert isinstance(assets, list) and isinstance(assets[0], dict)
                assets[0][field] = value
                with self.assertRaisesRegex(provenance.ProvenanceError, message):
                    self.validate(payload)

    def test_artifact_name_is_bound_to_platform_and_attempt(self) -> None:
        self.assertEqual(
            "release-asset-provenance-linux-attempt-3",
            provenance.artifact_name("linux", 3),
        )

    def test_verifies_candidate_with_canonical_topology_identity(self) -> None:
        candidate = self.verify_candidate()

        self.assertEqual(candidate, self.verify_candidate())
        self.assertEqual(
            {
                "schemaVersion": 1,
                "targetSha": TARGET_SHA,
                "platform": "linux",
                "architecture": "x86_64",
                "dateTag": DATE_TAG,
                "releaseTag": RELEASE_TAG,
                "workflowRunId": RUN_ID,
                "workflowRunAttempt": RUN_ATTEMPT,
                "artifact": {
                    "key": "linux64",
                    "name": self.asset_name,
                    "class": "linux-product",
                    "sourcePath": str(self.asset_file.resolve()),
                    "sizeBytes": self.asset_file.stat().st_size,
                    "sha256": provenance.sha256_file(self.asset_file),
                },
                "provenance": {
                    "path": str(self.manifest_file.resolve()),
                    "sha256": provenance.sha256_file(self.manifest_file),
                },
            },
            candidate,
        )

    def test_rejects_wrong_name_platform_run_and_source_sha(self) -> None:
        cases = (
            ({"asset_name": "not-a-release-asset.zip"}, "does not belong"),
            ({"platform": "mac-amd64"}, "platform"),
            ({"run_id": RUN_ID + 1}, "workflowRunId"),
            ({"run_attempt": RUN_ATTEMPT + 1}, "workflowRunAttempt"),
            ({"target_sha": "b" * 40}, "targetSha"),
        )
        for overrides, message in cases:
            with self.subTest(overrides=overrides):
                with self.assertRaisesRegex(provenance.ProvenanceError, message):
                    self.verify_candidate(**overrides)

    def test_rejects_tampered_bytes_and_recorded_size(self) -> None:
        original = self.asset_file.read_bytes()
        self.asset_file.write_bytes(b"asset-X")
        with self.assertRaisesRegex(provenance.ProvenanceError, "sha256"):
            self.verify_candidate()

        self.asset_file.write_bytes(original)
        payload = self.payload()
        assets = payload["assets"]
        assert isinstance(assets, list)
        record = next(item for item in assets if item["name"] == self.asset_name)
        record["sizeBytes"] = int(record["sizeBytes"]) + 1
        self.write_manifest(payload)
        with self.assertRaisesRegex(provenance.ProvenanceError, "size"):
            self.verify_candidate()

    def test_rejects_support_rows_but_accepts_core_update(self) -> None:
        windows_dir = self.release_dir / "windows"
        windows_dir.mkdir()
        for index, name in enumerate(
            provenance.expected_asset_names("windows", DATE_TAG), 1
        ):
            (windows_dir / name).write_bytes(f"windows-{index}".encode("ascii"))
        manifest = provenance.build_provenance(
            windows_dir,
            "windows",
            DATE_TAG,
            RELEASE_TAG,
            TARGET_SHA,
            RUN_ID,
            RUN_ATTEMPT,
        )
        manifest_path = self.write_manifest(manifest, windows_dir / "manifest.json")
        common = {
            "manifest_path": manifest_path,
            "platform": "windows",
            "date_tag": DATE_TAG,
            "release_tag": RELEASE_TAG,
            "target_sha": TARGET_SHA,
            "run_id": RUN_ID,
            "run_attempt": RUN_ATTEMPT,
        }
        support_name = "lizzieyzy-next-update-manifest.json"
        with self.assertRaisesRegex(provenance.ProvenanceError, "not an acceptance candidate"):
            provenance.verify_candidate(
                **common,
                asset_name=support_name,
                asset_file=windows_dir / support_name,
            )

        core_name = f"{DATE_TAG}-windows64.core-update.zip"
        candidate = provenance.verify_candidate(
            **common,
            asset_name=core_name,
            asset_file=windows_dir / core_name,
        )
        self.assertEqual("core-update", candidate["artifact"]["class"])

    def test_rejects_repository_source_and_staging_paths(self) -> None:
        original = self.asset_file.read_bytes()
        rejected_locations = (
            (REPO_ROOT / "target", "candidate-"),
            (REPO_ROOT / "dist" / "stage", "candidate-"),
            (REPO_ROOT / "dist" / "windows", "input-candidate-"),
            (REPO_ROOT / "dist" / "windows", "app-image-candidate-"),
            (REPO_ROOT / "dist" / "macos" / "app-image", "candidate-"),
        )
        for parent, prefix in rejected_locations:
            parent.mkdir(parents=True, exist_ok=True)
            with self.subTest(parent=parent, prefix=prefix), tempfile.TemporaryDirectory(
                dir=parent, prefix=prefix
            ) as temporary_directory:
                source = Path(temporary_directory) / self.asset_name
                source.write_bytes(original)
                with self.assertRaisesRegex(provenance.ProvenanceError, "source or staging"):
                    self.verify_candidate(asset_file=source)

        release_parent = REPO_ROOT / "dist" / "release"
        release_parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(
            dir=release_parent, prefix="candidate-"
        ) as temporary_directory:
            final_asset = Path(temporary_directory) / self.asset_name
            final_asset.write_bytes(original)
            candidate = self.verify_candidate(asset_file=final_asset)
            self.assertEqual(str(final_asset.resolve()), candidate["artifact"]["sourcePath"])

    def test_cli_writes_candidate_atomically_only_after_validation(self) -> None:
        output = self.release_dir / "candidate.json"
        valid = self.run_verify_cli(output)
        self.assertEqual(0, valid.returncode, valid.stderr)
        self.assertEqual(self.verify_candidate(), json.loads(output.read_text(encoding="utf-8")))

        output.write_text("sentinel\n", encoding="utf-8")
        self.asset_file.write_bytes(b"asset-X")
        invalid = self.run_verify_cli(output)
        self.assertNotEqual(0, invalid.returncode)
        self.assertIn("sha256", invalid.stderr)
        self.assertEqual("sentinel\n", output.read_text(encoding="utf-8"))

        output.unlink()
        invalid_without_existing_output = self.run_verify_cli(output)
        self.assertNotEqual(0, invalid_without_existing_output.returncode)
        self.assertFalse(output.exists())

    def test_cli_rejects_outputs_that_alias_verified_inputs(self) -> None:
        asset_bytes = self.asset_file.read_bytes()
        manifest_bytes = self.manifest_file.read_bytes()

        for output in (self.asset_file, self.manifest_file):
            with self.subTest(output=output):
                result = self.run_verify_cli(output)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("must not replace", result.stderr)
                self.assertEqual(asset_bytes, self.asset_file.read_bytes())
                self.assertEqual(manifest_bytes, self.manifest_file.read_bytes())

    def test_cli_rejects_output_through_parent_directory_alias(self) -> None:
        alias = self.release_dir / "release-alias"
        try:
            alias.symlink_to(self.release_dir, target_is_directory=True)
        except OSError as exc:
            self.skipTest(f"directory symlink unavailable: {exc}")

        result = self.run_verify_cli(alias / self.asset_name)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("must not replace", result.stderr)




class AcceptanceRecordTest(unittest.TestCase):
    def record(self) -> dict[str, object]:
        return {
            "schemaVersion": 1,
            "scenarioId": "linux-cpu-offline-first-run",
            "status": "PASS",
            "phase": "launch",
            "startedAt": "2026-08-19T10:00:00Z",
            "finishedAt": "2026-08-19T10:05:00Z",
            "expected": {
                "targetSha": TARGET_SHA,
                "platform": "linux",
                "architecture": "x86_64",
                "artifact": {
                    "key": "linux64",
                    "name": f"{DATE_TAG}-linux64.with-katago.zip",
                    "class": "linux-product",
                },
                "scenario": "linux-cpu-offline-first-run",
                "requiredOutcomes": {
                    "phases": ["transfer", "extraction", "launch"],
                    "observations": [
                        {"path": "observed.candidate.sha256", "phase": "transfer"},
                        {"path": "observed.extractionPath", "phase": "extraction"},
                        {"path": "observed.launcher.pid", "phase": "launch"},
                    ],
                    "assertions": [
                        {"path": "assertions.candidateVerified", "phase": "transfer"},
                        {"path": "assertions.extracted", "phase": "extraction"},
                        {"path": "assertions.launcherReady", "phase": "launch"},
                    ],
                    "evidence": [
                        {"path": "evidence.provenance", "phase": "transfer"},
                        {"path": "evidence.extractionLog", "phase": "extraction"},
                        {"path": "evidence.launchLog", "phase": "launch"},
                    ],
                },
            },
            "observed": {
                "candidate": {"sha256": "b" * 64},
                "extractionPath": "/tmp/验收 candidate",
                "launcher": {"pid": 4321},
            },
            "notObserved": {},
            "assertions": {
                "candidateVerified": True,
                "extracted": True,
                "launcherReady": True,
            },
            "evidence": {
                "provenance": "provenance.json",
                "extractionLog": "extract.log",
                "launchLog": "launch.log",
            },
            "cleanup": {"complete": True, "remainingOwnedResources": []},
            "blockedPhase": None,
            "reason": None,
            "failure": None,
        }

    def test_accepts_strict_pass(self) -> None:
        record = self.record()
        self.assertIs(record, provenance.validate_acceptance_record(record))

    def test_rejects_invalid_public_expected_identity(self) -> None:
        mutations = (
            ("missing key", lambda artifact, expected: artifact.pop("key")),
            ("null name", lambda artifact, expected: artifact.update(name=None)),
            (
                "wrong class",
                lambda artifact, expected: artifact.update(**{"class": "dmg-product"}),
            ),
            (
                "wrong platform",
                lambda artifact, expected: expected.update(platform="windows"),
            ),
            (
                "wrong architecture",
                lambda artifact, expected: expected.update(architecture="arm64"),
            ),
            (
                "wrong name",
                lambda artifact, expected: artifact.update(name="renamed-final.zip"),
            ),
        )
        for label, mutate in mutations:
            with self.subTest(label=label):
                record = self.record()
                expected = record["expected"]
                artifact = expected["artifact"]
                mutate(artifact, expected)
                with self.assertRaisesRegex(provenance.ProvenanceError, "artifact(?: identity|\\.name)"):
                    provenance.validate_acceptance_record(record)

    def test_rejects_blocked_record_with_missing_expected_identity(self) -> None:
        record = self.record()
        record["status"] = "BLOCKED"
        record["phase"] = "transfer"
        record["blockedPhase"] = "transfer"
        record["reason"] = "release candidate was not transferred"
        for path in (
            "observed.candidate.sha256",
            "observed.extractionPath",
            "observed.launcher.pid",
            "assertions.candidateVerified",
            "assertions.extracted",
            "assertions.launcherReady",
            "evidence.provenance",
            "evidence.extractionLog",
            "evidence.launchLog",
        ):
            self.set_path(record, path, None)
            record["notObserved"][path] = "blocked before transfer: candidate unavailable"
        del record["expected"]["artifact"]["class"]

        with self.assertRaisesRegex(provenance.ProvenanceError, "artifact identity"):
            provenance.validate_acceptance_record(record)

    def test_accepts_complete_standalone_artifact_identity(self) -> None:
        record = self.record()
        record["scenarioId"] = "standalone-java17-runtime"
        expected = record["expected"]
        expected["scenario"] = "standalone-java17-runtime"
        expected["platform"] = "standalone-java17"
        expected["artifact"] = {
            "key": "NOT_APPLICABLE",
            "name": "NOT_APPLICABLE",
            "class": "NOT_APPLICABLE",
            "sourceSha": TARGET_SHA,
            "buildIdentity": {"jdk": "Temurin 21", "runId": 123},
            "path": "/tmp/LizzieYzy-2.5.3-shaded.jar",
            "sizeBytes": 123,
            "sha256": "d" * 64,
        }

        provenance.validate_acceptance_record(record)

        del expected["artifact"]["buildIdentity"]
        with self.assertRaisesRegex(provenance.ProvenanceError, "standalone artifact identity"):
            provenance.validate_acceptance_record(record)

    def test_accepts_blocked_before_transfer_with_null_reasons(self) -> None:
        record = self.record()
        record.update(
            {
                "status": "BLOCKED",
                "phase": "transfer",
                "blockedPhase": "transfer",
                "reason": "release candidate and matching provenance were not transferred",
            }
        )
        for path in (
            "observed.candidate.sha256",
            "observed.extractionPath",
            "observed.launcher.pid",
            "assertions.candidateVerified",
            "assertions.extracted",
            "assertions.launcherReady",
            "evidence.provenance",
            "evidence.extractionLog",
            "evidence.launchLog",
        ):
            self.set_path(record, path, None)
            record["notObserved"][path] = "blocked before transfer: candidate unavailable"

        provenance.validate_acceptance_record(record)

    def test_accepts_blocked_after_extraction_with_later_null_reasons(self) -> None:
        record = self.record()
        record.update(
            {
                "status": "BLOCKED",
                "phase": "extraction",
                "blockedPhase": "launch",
                "reason": "native display required for packaged launcher is unavailable",
            }
        )
        for path in (
            "observed.launcher.pid",
            "assertions.launcherReady",
            "evidence.launchLog",
        ):
            self.set_path(record, path, None)
            record["notObserved"][path] = "blocked before launch: native display unavailable"

        provenance.validate_acceptance_record(record)

    def test_accepts_fail_during_launch_with_diagnostics(self) -> None:
        record = self.record()
        record.update(
            {
                "status": "FAIL",
                "phase": "launch",
                "failure": {
                    "kind": "assertion",
                    "summary": "packaged launcher exited before readiness",
                    "diagnostics": ["launch.log", "stderr.log"],
                },
            }
        )
        record["observed"]["launcher"]["pid"] = None
        record["assertions"]["launcherReady"] = False
        record["notObserved"]["observed.launcher.pid"] = (
            "launch failed before a stable process identity was observed"
        )

        provenance.validate_acceptance_record(record)

    def test_rejects_pass_with_required_runtime_field_null(self) -> None:
        record = self.record()
        record["observed"]["launcher"]["pid"] = None
        record["notObserved"]["observed.launcher.pid"] = (
            "launcher process was not observed"
        )

        with self.assertRaisesRegex(provenance.ProvenanceError, "PASS requires"):
            provenance.validate_acceptance_record(record)

    def test_rejects_aggregate_required_outcome(self) -> None:
        record = self.record()
        record["expected"]["requiredOutcomes"]["observations"][0]["path"] = (
            "observed.candidate"
        )

        with self.assertRaisesRegex(provenance.ProvenanceError, "leaf value"):
            provenance.validate_acceptance_record(record)

    def test_rejects_missing_required_identity_leaf(self) -> None:
        record = self.record()
        del record["observed"]["candidate"]["sha256"]

        with self.assertRaisesRegex(provenance.ProvenanceError, "Required path is missing"):
            provenance.validate_acceptance_record(record)

    def test_rejects_incomplete_observation_from_completed_phase(self) -> None:
        record = self.record()
        record.update(
            {
                "status": "BLOCKED",
                "phase": "extraction",
                "blockedPhase": "launch",
                "reason": "native display is unavailable",
            }
        )
        record["observed"]["candidate"]["sha256"] = None
        record["notObserved"]["observed.candidate.sha256"] = (
            "candidate identity was not captured"
        )
        for path in (
            "observed.launcher.pid",
            "assertions.launcherReady",
            "evidence.launchLog",
        ):
            self.set_path(record, path, None)
            record["notObserved"][path] = "blocked before launch: native display unavailable"

        with self.assertRaisesRegex(provenance.ProvenanceError, "Completed observation"):
            provenance.validate_acceptance_record(record)

    def test_builds_ordered_acceptance_report(self) -> None:
        passing = self.record()
        blocked = self.record()
        blocked["scenarioId"] = "variant-launch"
        blocked["expected"]["scenario"] = "variant-launch"
        blocked.update(
            status="BLOCKED",
            phase="extraction",
            blockedPhase="launch",
            reason="native display is unavailable",
        )
        for path in (
            "observed.launcher.pid",
            "assertions.launcherReady",
            "evidence.launchLog",
        ):
            self.set_path(blocked, path, None)
            blocked["notObserved"][path] = "blocked before launch: native display unavailable"

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            records = []
            for index, record in enumerate((passing, blocked)):
                path = root / f"acceptance-{index}.json"
                path.write_text(json.dumps(record), encoding="utf-8")
                records.append(path)
            required = [
                "linux/x86_64/linux64/linux-cpu-offline-first-run",
                "linux/x86_64/linux64/variant-launch",
            ]
            report = provenance.build_acceptance_report(records, TARGET_SHA, required)

        self.assertEqual({"PASS": 1, "FAIL": 0, "BLOCKED": 1}, report["summary"])
        self.assertEqual(required, [row["rowId"] for row in report["rows"]])

    def test_acceptance_report_rejects_missing_duplicate_wrong_sha_and_unexpected_rows(self) -> None:
        record = self.record()
        row_id = "linux/x86_64/linux64/linux-cpu-offline-first-run"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "first.json"
            second = root / "second.json"
            first.write_text(json.dumps(record), encoding="utf-8")
            second.write_text(json.dumps(record), encoding="utf-8")
            cases = (
                ([first], TARGET_SHA, [row_id, "linux/x86_64/linux64/missing"], "Missing"),
                ([first, second], TARGET_SHA, [row_id], "Duplicate"),
                ([first], "b" * 40, [row_id], "targetSha"),
                ([first], TARGET_SHA, ["linux/x86_64/linux64/other"], "Unexpected"),
            )
            for paths, target_sha, required, message in cases:
                with self.subTest(message=message):
                    with self.assertRaisesRegex(provenance.ProvenanceError, message):
                        provenance.build_acceptance_report(paths, target_sha, required)

    def test_validate_acceptance_cli_writes_atomic_report(self) -> None:
        record = self.record()
        row_id = "linux/x86_64/linux64/linux-cpu-offline-first-run"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            record_path = root / "acceptance.json"
            output = root / "report.json"
            record_path.write_text(json.dumps(record), encoding="utf-8")
            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "validate-acceptance",
                    "--target-sha",
                    TARGET_SHA,
                    "--require-row",
                    row_id,
                    "--record",
                    str(record_path),
                    "--output",
                    str(output),
                ],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(row_id, json.loads(output.read_text())["rows"][0]["rowId"])
            self.assertFalse(any(root.glob(".report.json.*.tmp")))

    @staticmethod
    def set_path(record: dict[str, object], path: str, value: object) -> None:
        parts = path.split(".")
        current = record
        for part in parts[:-1]:
            child = current[part]
            assert isinstance(child, dict)
            current = child
        current[parts[-1]] = value
if __name__ == "__main__":
    unittest.main()
