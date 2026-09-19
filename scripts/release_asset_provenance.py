#!/usr/bin/env python3
"""Create and validate run-bound SHA-256 provenance for release assets."""

from __future__ import annotations

import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile

try:
    from scripts import release_asset_topology as topology
except ModuleNotFoundError:  # Direct execution: python scripts/release_asset_provenance.py
    import release_asset_topology as topology  # type: ignore[no-redef]


SCHEMA_VERSION = 1
PROVENANCE_FILENAME = "release-asset-provenance.json"
ACCEPTANCE_CANDIDATE_CLASSES = frozenset(
    {
        topology.CandidateClass.PORTABLE_PRODUCT,
        topology.CandidateClass.INSTALLER_PRODUCT,
        topology.CandidateClass.LINUX_PRODUCT,
        topology.CandidateClass.DMG_PRODUCT,
        topology.CandidateClass.CORE_UPDATE,
    }
)


class ProvenanceError(RuntimeError):
    """Release asset provenance is missing, ambiguous, or inconsistent."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ProvenanceError(message)


def _positive_integer(value: object, field: str) -> int:
    require(type(value) is int and int(value) > 0, f"{field} must be a positive integer")
    return int(value)


def validate_identity(
    platform: str,
    date_tag: str,
    release_tag: str,
    target_sha: str,
    run_id: int,
    run_attempt: int,
) -> None:
    _require_supported_platform(platform)
    require(
        re.fullmatch(r"\d{4}-\d{2}-\d{2}", date_tag) is not None,
        "dateTag must use YYYY-MM-DD",
    )
    require(
        re.fullmatch(rf"next-{re.escape(date_tag)}\.[1-9][0-9]*", release_tag)
        is not None,
        "releaseTag must exactly match dateTag and use a positive serial",
    )
    require(
        re.fullmatch(r"[0-9a-f]{40}", target_sha) is not None,
        "targetSha must be a full lowercase commit SHA",
    )
    _positive_integer(run_id, "workflowRunId")
    _positive_integer(run_attempt, "workflowRunAttempt")


def _require_supported_platform(platform: str) -> None:
    try:
        topology.release_unit(platform)
    except topology.TopologyError as exc:
        raise ProvenanceError(str(exc)) from exc


def expected_asset_names(platform: str, date_tag: str) -> tuple[str, ...]:
    try:
        return topology.provenance_names(platform, date_tag)
    except topology.TopologyError as exc:
        raise ProvenanceError(str(exc)) from exc


def artifact_name(platform: str, run_attempt: int) -> str:
    _require_supported_platform(platform)
    _positive_integer(run_attempt, "workflowRunAttempt")
    return f"release-asset-provenance-{platform}-attempt-{run_attempt}"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as exc:
        raise ProvenanceError(f"Unable to hash release asset {path.name}: {exc}") from exc
    return digest.hexdigest()


def _file_identity(path: Path, label: str) -> tuple[int, str]:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            metadata = os.fstat(handle.fileno())
            require(stat.S_ISREG(metadata.st_mode), f"{label} must be a regular file")
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as exc:
        raise ProvenanceError(f"Unable to read {label} {path}: {exc}") from exc
    require(metadata.st_size > 0, f"{label} must not be empty")
    return metadata.st_size, digest.hexdigest()


def _resolved_file(path: Path, label: str) -> Path:
    try:
        resolved = path.resolve(strict=True)
    except OSError as exc:
        raise ProvenanceError(f"Unable to resolve {label} {path}: {exc}") from exc
    require(resolved.is_file(), f"{label} must be a regular file: {resolved}")
    return resolved


def _is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
    except ValueError:
        return False
    return True


def _reject_source_or_staging_path(path: Path) -> None:
    repository = Path(__file__).resolve().parent.parent
    rejected_roots = (
        repository / "target",
        repository / "dist" / "stage",
        repository / "dist" / "macos" / "app-image",
    )
    require(
        not any(_is_relative_to(path, root) for root in rejected_roots),
        f"Candidate path is a known source or staging substitute: {path}",
    )
    windows_root = repository / "dist" / "windows"
    if _is_relative_to(path, windows_root):
        relative = path.relative_to(windows_root)
        first_component = relative.parts[0] if relative.parts else ""
        require(
            not first_component.startswith(("input-", "app-image-")),
            f"Candidate path is a known source or staging substitute: {path}",
        )


def _strict_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON field: {key}")
        result[key] = value
    return result


def _load_json(path: Path, label: str) -> tuple[object, str]:
    resolved = _resolved_file(path, label)
    try:
        raw = resolved.read_bytes()
        payload = json.loads(raw.decode("utf-8"), object_pairs_hook=_strict_object)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProvenanceError(f"Unable to parse {label} {resolved}: {exc}") from exc
    return payload, hashlib.sha256(raw).hexdigest()


def build_provenance(
    release_dir: Path,
    platform: str,
    date_tag: str,
    release_tag: str,
    target_sha: str,
    run_id: int,
    run_attempt: int,
) -> dict[str, object]:
    validate_identity(platform, date_tag, release_tag, target_sha, run_id, run_attempt)
    assets: list[dict[str, object]] = []
    for name in expected_asset_names(platform, date_tag):
        path = release_dir / name
        require(path.is_file(), f"Missing release asset for provenance: {name}")
        size = path.stat().st_size
        require(size > 0, f"Release asset is empty: {name}")
        assets.append(
            {
                "name": name,
                "sizeBytes": size,
                "sha256": sha256_file(path),
            }
        )
    return {
        "schemaVersion": SCHEMA_VERSION,
        "platform": platform,
        "dateTag": date_tag,
        "releaseTag": release_tag,
        "targetSha": target_sha,
        "workflowRunId": run_id,
        "workflowRunAttempt": run_attempt,
        "assets": assets,
    }


def validate_provenance(
    payload: object,
    *,
    platform: str,
    date_tag: str,
    release_tag: str,
    target_sha: str,
    run_id: int,
    run_attempt: int,
) -> dict[str, dict[str, object]]:
    validate_identity(platform, date_tag, release_tag, target_sha, run_id, run_attempt)
    require(isinstance(payload, dict), "Provenance manifest must contain a JSON object")
    assert isinstance(payload, dict)
    expected_fields = {
        "schemaVersion",
        "platform",
        "dateTag",
        "releaseTag",
        "targetSha",
        "workflowRunId",
        "workflowRunAttempt",
        "assets",
    }
    require(
        set(payload) == expected_fields,
        "Provenance manifest fields do not exactly match the schema",
    )
    require(payload.get("schemaVersion") == SCHEMA_VERSION, "Unsupported provenance schema")
    require(payload.get("platform") == platform, "Provenance platform does not match the run")
    require(payload.get("dateTag") == date_tag, "Provenance dateTag does not match")
    require(payload.get("releaseTag") == release_tag, "Provenance releaseTag does not match")
    require(payload.get("targetSha") == target_sha, "Provenance targetSha does not match")
    require(payload.get("workflowRunId") == run_id, "Provenance workflowRunId does not match")
    require(
        payload.get("workflowRunAttempt") == run_attempt,
        "Provenance workflowRunAttempt does not match",
    )

    assets = payload.get("assets")
    require(isinstance(assets, list), "Provenance assets must be a list")
    assert isinstance(assets, list)
    records: dict[str, dict[str, object]] = {}
    ordered_names: list[str] = []
    for item in assets:
        require(isinstance(item, dict), "Every provenance asset must be an object")
        assert isinstance(item, dict)
        require(
            set(item) == {"name", "sizeBytes", "sha256"},
            "Provenance asset fields do not exactly match the schema",
        )
        name = item.get("name")
        require(isinstance(name, str) and bool(name), "Provenance asset name is invalid")
        assert isinstance(name, str)
        require(name not in records, f"Duplicate provenance asset: {name}")
        size = _positive_integer(item.get("sizeBytes"), f"sizeBytes for {name}")
        digest = item.get("sha256")
        require(
            isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest) is not None,
            f"sha256 for {name} is invalid",
        )
        records[name] = {"name": name, "sizeBytes": size, "sha256": digest}
        ordered_names.append(name)

    expected_names = list(expected_asset_names(platform, date_tag))
    require(ordered_names == expected_names, "Provenance asset inventory is not exact and sorted")
    return records


def verify_candidate(
    manifest_path: Path,
    platform: str,
    date_tag: str,
    release_tag: str,
    target_sha: str,
    run_id: int,
    run_attempt: int,
    asset_name: str,
    asset_file: Path,
) -> dict[str, object]:
    manifest_resolved = _resolved_file(manifest_path, "provenance manifest")
    payload, manifest_sha256 = _load_json(manifest_resolved, "provenance manifest")
    records = validate_provenance(
        payload,
        platform=platform,
        date_tag=date_tag,
        release_tag=release_tag,
        target_sha=target_sha,
        run_id=run_id,
        run_attempt=run_attempt,
    )
    try:
        asset_identity = topology.asset_for_name(platform, date_tag, asset_name)
    except topology.TopologyError as exc:
        raise ProvenanceError(str(exc)) from exc
    require(
        asset_identity.candidate_class in ACCEPTANCE_CANDIDATE_CLASSES,
        f"Release asset is not an acceptance candidate: {asset_name}",
    )
    require(
        asset_identity.runnable
        or asset_identity.candidate_class is topology.CandidateClass.CORE_UPDATE,
        f"Release asset is non-runnable: {asset_name}",
    )

    supplied_asset_path = Path(os.path.abspath(asset_file))
    _reject_source_or_staging_path(supplied_asset_path)
    asset_resolved = _resolved_file(asset_file, "candidate asset")
    _reject_source_or_staging_path(asset_resolved)
    size_bytes, asset_sha256 = _file_identity(asset_resolved, "candidate asset")
    record = records[asset_name]
    require(record["sizeBytes"] == size_bytes, "Candidate asset size does not match provenance")
    require(record["sha256"] == asset_sha256, "Candidate asset sha256 does not match provenance")

    return {
        "schemaVersion": SCHEMA_VERSION,
        "targetSha": target_sha,
        "platform": platform,
        "architecture": asset_identity.architecture,
        "dateTag": date_tag,
        "releaseTag": release_tag,
        "workflowRunId": run_id,
        "workflowRunAttempt": run_attempt,
        "artifact": {
            "key": asset_identity.key,
            "name": asset_name,
            "class": asset_identity.candidate_class.value,
            "sourcePath": str(asset_resolved),
            "sizeBytes": size_bytes,
            "sha256": asset_sha256,
        },
        "provenance": {
            "path": str(manifest_resolved),
            "sha256": manifest_sha256,
        },
    }


def write_json_atomic(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".tmp",
    )
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_path, path)
    except BaseException:
        temporary_path.unlink(missing_ok=True)
        raise


def _require_distinct_candidate_output(
    output: Path, manifest_path: Path, asset_file: Path
) -> None:
    try:
        resolved_output = output.resolve(strict=False)
        protected_inputs = {
            manifest_path.resolve(strict=True),
            asset_file.resolve(strict=True),
        }
    except OSError as exc:
        raise ProvenanceError(f"Unable to resolve candidate output path: {exc}") from exc
    require(
        resolved_output not in protected_inputs,
        "Candidate output must not replace the provenance manifest or candidate asset",
    )



ACCEPTANCE_RECORD_FIELDS = {
    "schemaVersion",
    "scenarioId",
    "status",
    "phase",
    "startedAt",
    "finishedAt",
    "expected",
    "observed",
    "notObserved",
    "assertions",
    "evidence",
    "cleanup",
    "blockedPhase",
    "reason",
    "failure",
}


def _mapping(value: object, label: str) -> dict[str, object]:
    require(isinstance(value, dict), f"{label} must be an object")
    assert isinstance(value, dict)
    require(all(isinstance(key, str) for key in value), f"{label} keys must be strings")
    return value


def _required_string(value: object, label: str) -> str:
    require(isinstance(value, str) and bool(value.strip()), f"{label} must be a non-empty string")
    assert isinstance(value, str)
    return value


def _timestamp(value: object, label: str) -> datetime:
    text = _required_string(value, label)
    require(text.endswith("Z"), f"{label} must be an RFC 3339 UTC timestamp")
    try:
        result = datetime.fromisoformat(text[:-1] + "+00:00")
    except ValueError as exc:
        raise ProvenanceError(f"{label} must be an RFC 3339 UTC timestamp") from exc
    return result


def _path_value(record: dict[str, object], path: str) -> object:
    current: object = record
    for component in path.split("."):
        require(isinstance(current, dict) and component in current, f"Required path is missing: {path}")
        assert isinstance(current, dict)
        current = current[component]
    return current


def _requirement_entries(
    outcomes: dict[str, object],
    category: str,
    phases: list[str],
) -> list[tuple[str, int]]:
    entries = outcomes.get(category)
    require(isinstance(entries, list), f"requiredOutcomes.{category} must be a list")
    assert isinstance(entries, list)
    result: list[tuple[str, int]] = []
    seen_paths: set[str] = set()
    for entry in entries:
        item = _mapping(entry, f"requiredOutcomes.{category} entry")
        require(
            set(item) == {"path", "phase"},
            f"requiredOutcomes.{category} entries must contain only path and phase",
        )
        path = _required_string(item.get("path"), f"requiredOutcomes.{category}.path")
        phase = _required_string(item.get("phase"), f"requiredOutcomes.{category}.phase")
        root = "observed" if category == "observations" else category
        require(path.startswith(f"{root}."), f"Required path has wrong category: {path}")
        require(path not in seen_paths, f"Duplicate required path: {path}")
        require(phase in phases, f"Required path {path} names an unknown phase")
        seen_paths.add(path)
        result.append((path, phases.index(phase)))
    require(result, f"requiredOutcomes.{category} must not be empty")
    return result


def _has_evidence(value: object) -> bool:
    if value is None:
        return False
    if isinstance(value, (str, list, dict, tuple, set)):
        return bool(value)
    return True


def _null_paths(value: object, prefix: str) -> list[str]:
    if value is None:
        return [prefix]
    if not isinstance(value, dict):
        return []
    result: list[str] = []
    for key, child in value.items():
        child_prefix = f"{prefix}.{key}" if prefix else str(key)
        result.extend(_null_paths(child, child_prefix))
    return result




def _validate_expected_artifact_identity(expected: dict[str, object]) -> None:
    artifact = _mapping(expected.get("artifact"), "expected.artifact")
    identity_fields = {"key", "name", "class"}
    require(
        identity_fields.issubset(artifact),
        "expected artifact identity must include key, name, and class",
    )
    key = _required_string(artifact.get("key"), "expected.artifact.key")
    name = _required_string(artifact.get("name"), "expected.artifact.name")
    candidate_class = _required_string(
        artifact.get("class"), "expected.artifact.class"
    )
    not_applicable = "NOT_APPLICABLE"
    markers = (key, name, candidate_class)
    if any(value == not_applicable for value in markers):
        require(
            all(value == not_applicable for value in markers),
            "standalone artifact identity must mark key, name, and class NOT_APPLICABLE",
        )
        required = {
            "sourceSha",
            "buildIdentity",
            "path",
            "sizeBytes",
            "sha256",
        }
        require(
            required.issubset(artifact),
            "standalone artifact identity is incomplete",
        )
        require(
            artifact.get("sourceSha") == expected.get("targetSha"),
            "standalone artifact identity sourceSha does not match targetSha",
        )
        require(
            bool(_mapping(artifact.get("buildIdentity"), "expected.artifact.buildIdentity")),
            "standalone artifact identity buildIdentity must not be empty",
        )
        path = _required_string(artifact.get("path"), "expected.artifact.path")
        require(Path(path).is_absolute(), "standalone artifact identity path must be absolute")
        _positive_integer(artifact.get("sizeBytes"), "expected.artifact.sizeBytes")
        digest = artifact.get("sha256")
        require(
            isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest) is not None,
            "standalone artifact identity sha256 is invalid",
        )
        return

    try:
        asset_identity = topology.asset(key)
    except topology.TopologyError as exc:
        raise ProvenanceError(f"Expected artifact identity is unknown: {key}") from exc
    require(
        asset_identity.candidate_class in ACCEPTANCE_CANDIDATE_CLASSES,
        "Expected artifact identity is not an acceptance candidate",
    )
    require(
        asset_identity.platform == expected.get("platform")
        and asset_identity.architecture == expected.get("architecture")
        and asset_identity.candidate_class.value == candidate_class,
        "Expected artifact identity does not match canonical topology",
    )
    if asset_identity.filename.kind is topology.FilenameKind.LITERAL:
        canonical_name = asset_identity.filename.value
    else:
        suffix = f"-{asset_identity.filename.value}"
        require(
            name.endswith(suffix),
            "Expected artifact identity name does not match canonical topology",
        )
        date_tag = name[: -len(suffix)]
        try:
            canonical_name = asset_identity.filename.render(date_tag)
        except topology.TopologyError as exc:
            raise ProvenanceError(
                "Expected artifact identity name does not match canonical topology"
            ) from exc
    require(
        name == canonical_name,
        "Expected artifact identity name does not match canonical topology",
    )

def validate_acceptance_record(payload: object) -> dict[str, object]:
    record = _mapping(payload, "Acceptance record")
    require(
        set(record) == ACCEPTANCE_RECORD_FIELDS,
        "Acceptance record fields do not exactly match the schema",
    )
    require(record.get("schemaVersion") == SCHEMA_VERSION, "Unsupported acceptance schema")
    scenario_id = _required_string(record.get("scenarioId"), "scenarioId")
    status = record.get("status")
    require(status in {"PASS", "FAIL", "BLOCKED"}, "status must be PASS, FAIL, or BLOCKED")
    started_at = _timestamp(record.get("startedAt"), "startedAt")
    finished_at = _timestamp(record.get("finishedAt"), "finishedAt")
    require(finished_at >= started_at, "finishedAt must not precede startedAt")

    expected = _mapping(record.get("expected"), "expected")
    require(
        {
            "targetSha",
            "platform",
            "architecture",
            "artifact",
            "scenario",
            "requiredOutcomes",
        }.issubset(expected),
        "expected identity is incomplete",
    )
    require(
        isinstance(expected.get("targetSha"), str)
        and re.fullmatch(r"[0-9a-f]{40}", str(expected.get("targetSha"))) is not None,
        "expected.targetSha must be a full lowercase commit SHA",
    )
    _required_string(expected.get("platform"), "expected.platform")
    _required_string(expected.get("architecture"), "expected.architecture")
    _validate_expected_artifact_identity(expected)
    require(expected.get("scenario") == scenario_id, "expected.scenario must match scenarioId")

    outcomes = _mapping(expected.get("requiredOutcomes"), "expected.requiredOutcomes")
    require(
        set(outcomes) == {"phases", "observations", "assertions", "evidence"},
        "expected.requiredOutcomes fields do not exactly match the schema",
    )
    raw_phases = outcomes.get("phases")
    require(isinstance(raw_phases, list) and bool(raw_phases), "requiredOutcomes.phases must not be empty")
    assert isinstance(raw_phases, list)
    phases = [_required_string(item, "requiredOutcomes phase") for item in raw_phases]
    require(len(phases) == len(set(phases)), "requiredOutcomes phases must be unique")
    phase = _required_string(record.get("phase"), "phase")
    require(phase in phases, "phase is not declared in requiredOutcomes")
    phase_index = phases.index(phase)

    requirements = {
        category: _requirement_entries(outcomes, category, phases)
        for category in ("observations", "assertions", "evidence")
    }
    _mapping(record.get("observed"), "observed")
    _mapping(record.get("assertions"), "assertions")
    _mapping(record.get("evidence"), "evidence")
    for entries in requirements.values():
        for path, _ in entries:
            require(
                not isinstance(_path_value(record, path), dict),
                f"Required outcome must resolve to a leaf value: {path}",
            )
    not_observed = _mapping(record.get("notObserved"), "notObserved")
    for path, reason in not_observed.items():
        require(
            path.startswith(("observed.", "assertions.", "evidence.")),
            f"notObserved path has an invalid root: {path}",
        )
        _required_string(reason, f"notObserved[{path!r}]")
        require(_path_value(record, path) is None, f"notObserved path is not null: {path}")
    for path in _null_paths(record["observed"], "observed"):
        require(path in not_observed, f"Null observation lacks notObserved reason: {path}")

    cleanup = _mapping(record.get("cleanup"), "cleanup")
    require(
        {"complete", "remainingOwnedResources"}.issubset(cleanup),
        "cleanup result is incomplete",
    )
    complete = cleanup.get("complete")
    remaining = cleanup.get("remainingOwnedResources")
    require(type(complete) is bool, "cleanup.complete must be boolean")
    require(isinstance(remaining, list), "cleanup.remainingOwnedResources must be a list")
    assert isinstance(remaining, list)
    require(bool(complete) == (len(remaining) == 0), "cleanup result contradicts remaining resources")

    def require_null(path: str) -> None:
        require(_path_value(record, path) is None, f"Unreached required path must be null: {path}")
        require(path in not_observed, f"Unreached required path lacks notObserved reason: {path}")

    def require_completed(category: str, path: str) -> None:
        value = _path_value(record, path)
        if category == "assertions":
            require(value is True, f"Completed assertion must be true: {path}")
        elif category == "evidence":
            require(_has_evidence(value), f"Completed evidence is missing: {path}")
        else:
            require(value is not None, f"Completed observation is missing: {path}")

    if status == "PASS":
        require(phase_index == len(phases) - 1, "PASS phase must be the final required phase")
        require(record.get("blockedPhase") is None, "PASS must not have blockedPhase")
        require(record.get("reason") is None, "PASS must not have a blocker reason")
        require(record.get("failure") is None, "PASS must not have failure details")
        require(complete is True, "PASS requires complete cleanup")
        for category, entries in requirements.items():
            for path, _ in entries:
                value = _path_value(record, path)
                require(value is not None, f"PASS requires non-null field: {path}")
                require_completed(category, path)
        return record

    if status == "BLOCKED":
        blocked_phase = _required_string(record.get("blockedPhase"), "blockedPhase")
        _required_string(record.get("reason"), "BLOCKED reason")
        require(record.get("failure") is None, "BLOCKED must not have failure details")
        require(blocked_phase in phases, "blockedPhase is not declared in requiredOutcomes")
        blocked_index = phases.index(blocked_phase)
        expected_phase_index = 0 if blocked_index == 0 else blocked_index - 1
        require(phase_index == expected_phase_index, "BLOCKED phase must be the last completed phase")
        require(complete is True, "BLOCKED requires cleanup of acquired resources")
        for category, entries in requirements.items():
            for path, requirement_phase in entries:
                if requirement_phase < blocked_index:
                    require_completed(category, path)
                else:
                    require_null(path)
        return record

    require(record.get("blockedPhase") is None, "FAIL must not have blockedPhase")
    require(record.get("reason") is None, "FAIL must not have a blocker reason")
    failure = _mapping(record.get("failure"), "failure")
    require(
        set(failure) == {"kind", "summary", "diagnostics"},
        "failure fields do not exactly match the schema",
    )
    failure_kind = failure.get("kind")
    require(failure_kind in {"assertion", "error", "timeout"}, "failure.kind is invalid")
    _required_string(failure.get("summary"), "failure.summary")
    diagnostics = failure.get("diagnostics")
    require(
        isinstance(diagnostics, list)
        and bool(diagnostics)
        and all(isinstance(item, str) and bool(item.strip()) for item in diagnostics),
        "failure.diagnostics must contain retained evidence paths",
    )
    failed_assertion = False
    for category, entries in requirements.items():
        for path, requirement_phase in entries:
            if requirement_phase < phase_index:
                require_completed(category, path)
            elif requirement_phase > phase_index:
                require_null(path)
            else:
                value = _path_value(record, path)
                if value is None:
                    require(path in not_observed, f"Failed-phase null lacks reason: {path}")
                elif category == "assertions":
                    require(type(value) is bool, f"Failed-phase assertion must be boolean: {path}")
                    failed_assertion = failed_assertion or value is False
                elif category == "evidence":
                    require(_has_evidence(value), f"Failed-phase evidence is empty: {path}")
    if failure_kind == "assertion":
        require(failed_assertion, "Assertion failure requires a false assertion in the failed phase")
    return record


def acceptance_record_key(record: dict[str, object]) -> str:
    expected = _mapping(record.get("expected"), "expected")
    artifact = _mapping(expected.get("artifact"), "expected.artifact")
    components = (
        _required_string(expected.get("platform"), "expected.platform"),
        _required_string(expected.get("architecture"), "expected.architecture"),
        _required_string(artifact.get("key"), "expected.artifact.key"),
        _required_string(record.get("scenarioId"), "scenarioId"),
    )
    require(
        all("/" not in component for component in components),
        "Acceptance row identity components must not contain '/'",
    )
    return "/".join(components)


def build_acceptance_report(
    record_paths: list[Path], target_sha: str, required_rows: list[str]
) -> dict[str, object]:
    require(
        re.fullmatch(r"[0-9a-f]{40}", target_sha) is not None,
        "targetSha must be a full lowercase commit SHA",
    )
    require(bool(record_paths), "At least one acceptance record is required")
    require(bool(required_rows), "At least one required acceptance row is required")
    require(
        len(required_rows) == len(set(required_rows)),
        "Required acceptance rows must be unique",
    )

    records: dict[str, dict[str, object]] = {}
    for record_path in record_paths:
        payload, digest = _load_json(record_path, "acceptance record")
        record = validate_acceptance_record(payload)
        expected = _mapping(record.get("expected"), "expected")
        require(
            expected.get("targetSha") == target_sha,
            f"Acceptance record targetSha does not match: {record_path}",
        )
        row_id = acceptance_record_key(record)
        require(row_id not in records, f"Duplicate acceptance row: {row_id}")
        require(row_id in required_rows, f"Unexpected acceptance row: {row_id}")
        records[row_id] = {
            "rowId": row_id,
            "status": record["status"],
            "phase": record["phase"],
            "recordPath": str(_resolved_file(record_path, "acceptance record")),
            "recordSha256": digest,
        }

    missing = [row_id for row_id in required_rows if row_id not in records]
    require(not missing, f"Missing required acceptance rows: {', '.join(missing)}")
    rows = [records[row_id] for row_id in required_rows]
    return {
        "schemaVersion": SCHEMA_VERSION,
        "targetSha": target_sha,
        "requiredRows": required_rows,
        "summary": {
            status: sum(row["status"] == status for row in rows)
            for status in ("PASS", "FAIL", "BLOCKED")
        },
        "rows": rows,
    }


def _require_distinct_acceptance_output(output: Path, record_paths: list[Path]) -> None:
    try:
        resolved_output = output.resolve(strict=False)
        protected_inputs = {
            record_path.resolve(strict=True) for record_path in record_paths
        }
    except OSError as exc:
        raise ProvenanceError(f"Unable to resolve acceptance report output path: {exc}") from exc
    require(
        resolved_output not in protected_inputs,
        "Acceptance report output must not replace an acceptance record",
    )

def _provenance_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", required=True, type=Path)
    parser.add_argument("--platform", required=True, choices=topology.platforms())
    parser.add_argument("--date-tag", required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--target-sha", required=True)
    parser.add_argument("--run-id", required=True, type=int)
    parser.add_argument("--run-attempt", required=True, type=int)
    parser.add_argument("--output", required=True, type=Path)
    parser.set_defaults(operation="build-provenance")
    return parser


def _candidate_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Verify one final release candidate")
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--platform", required=True, choices=topology.platforms())
    parser.add_argument("--date-tag", required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--target-sha", required=True)
    parser.add_argument("--run-id", required=True, type=int)
    parser.add_argument("--run-attempt", required=True, type=int)
    parser.add_argument("--asset-name", required=True)
    parser.add_argument("--asset-file", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.set_defaults(operation="verify-candidate")
    return parser


def _acceptance_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Validate and summarize a complete acceptance-record set"
    )
    parser.add_argument("--target-sha", required=True)
    parser.add_argument("--require-row", required=True, action="append")
    parser.add_argument("--record", required=True, action="append", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.set_defaults(operation="validate-acceptance")
    return parser


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments and arguments[0] == "verify-candidate":
        return _candidate_parser().parse_args(arguments[1:])
    if arguments and arguments[0] == "validate-acceptance":
        return _acceptance_parser().parse_args(arguments[1:])
    return _provenance_parser().parse_args(arguments)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.operation == "verify-candidate":
            _require_distinct_candidate_output(
                args.output, args.manifest, args.asset_file
            )
            payload = verify_candidate(
                args.manifest,
                args.platform,
                args.date_tag,
                args.release_tag,
                args.target_sha,
                args.run_id,
                args.run_attempt,
                args.asset_name,
                args.asset_file,
            )
            success_message = f"Wrote verified release candidate: {args.output}"
        elif args.operation == "validate-acceptance":
            _require_distinct_acceptance_output(args.output, args.record)
            payload = build_acceptance_report(
                args.record, args.target_sha, args.require_row
            )
            success_message = f"Wrote acceptance record report: {args.output}"
        else:
            payload = build_provenance(
                args.release_dir,
                args.platform,
                args.date_tag,
                args.release_tag,
                args.target_sha,
                args.run_id,
                args.run_attempt,
            )
            success_message = f"Wrote run-bound release asset provenance: {args.output}"
        write_json_atomic(args.output, payload)
    except (OSError, ProvenanceError) as exc:
        print(f"Release asset provenance failed: {exc}", file=sys.stderr)
        return 1
    print(success_message)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
