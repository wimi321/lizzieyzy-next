#!/usr/bin/env python3
"""Publish an audited multi-platform pre-release request.

The release remains a draft until every platform workflow succeeds, all expected
assets are present, and the localized release notes have been generated.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import time
from typing import Callable, Iterable
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen
import zipfile

try:
    from scripts import release_asset_provenance as provenance
except ModuleNotFoundError:  # Direct execution: python scripts/publish_release_request.py
    import release_asset_provenance as provenance  # type: ignore[no-redef]


API_VERSION = "2026-03-10"
SIGNED_UPDATE_ENVELOPE_ASSET = "lizzieyzy-next-update-envelope.json"
TAG_PATTERN = re.compile(r"^next-(\d{4}-\d{2}-\d{2})\.(\d+)$")
LOCALIZED_NOTE_HEADINGS = (
    "## 中文",
    "## 繁體中文",
    "## English",
    "## 日本語",
    "## 한국어",
    "## ภาษาไทย",
)
DIRECT_DOWNLOAD_SUFFIXES = (
    "windows64.opencl.portable.zip",
    "windows64.core-update.zip",
    "windows64.opencl.installer.exe",
    "windows64.with-katago.portable.zip",
    "windows64.with-katago.installer.exe",
    "windows64.nvidia.portable.zip",
    "windows64.nvidia.installer.exe",
    "windows64.experimental.directml.portable.zip",
    "windows64.experimental.openvino.portable.zip",
    "windows64.experimental.rocm.gfx103x.portable.zip",
    "windows64.experimental.rocm.gfx110x.portable.zip",
    "windows64.experimental.rocm.gfx1151.portable.zip",
    "windows64.experimental.rocm.gfx120x.portable.zip",
    "windows64.nvidia.tensorrt.portable.7z.001",
    "windows64.nvidia.tensorrt.portable.7z.002",
    "windows64.without.engine.portable.zip",
    "windows64.without.engine.installer.exe",
    "mac-apple-silicon.with-katago.dmg",
    "mac-intel.with-katago.dmg",
    "linux64.with-katago.zip",
    "linux64.opencl.zip",
    "linux64.nvidia.zip",
)
ACTIVE_RUN_STATUSES = {"queued", "in_progress", "waiting", "requested", "pending"}
WORKFLOW_IDENTITY_CONVERGENCE_SECONDS = 90
CI_WORKFLOW_FILE = "ci.yml"
UNRESOLVED_NOTE_MARKERS = (
    re.compile(r"\bFULL_TEST_COUNT\b"),
    re.compile(r"\bREAL_GUI_VALIDATION_[A-Z0-9_]*\b"),
    re.compile(
        r"\b(?:TODO|TBD|FIXME|PLACEHOLDER|REPLACE_ME|CHANGEME)\b",
        re.IGNORECASE,
    ),
    re.compile(r"\{\{|\}\}"),
)


class PublishError(RuntimeError):
    """A release invariant or GitHub API operation failed."""


def _localized_note_sections(body: str) -> dict[str, str]:
    matches: list[tuple[str, re.Match[str]]] = []
    for heading in LOCALIZED_NOTE_HEADINGS:
        match = re.search(rf"^{re.escape(heading)}\s*$", body, re.MULTILINE)
        if match is None:
            raise PublishError(f"Reviewed release notes are missing {heading}")
        matches.append((heading, match))
    matches.sort(key=lambda item: item[1].start())

    sections: dict[str, str] = {}
    for index, (heading, match) in enumerate(matches):
        end = matches[index + 1][1].start() if index + 1 < len(matches) else len(body)
        sections[heading] = body[match.start():end]
    return sections


def validate_direct_download_tables(
    body: str,
    date_tag: str,
    release_tag: str,
    repository: str,
) -> None:
    """Require the user-facing 7/19-style direct asset table in every language."""

    for language, section in _localized_note_sections(body).items():
        subsections = list(re.finditer(r"^### .+$", section, re.MULTILINE))
        if len(subsections) < 4:
            raise PublishError(
                f"{language} must keep the standard updates/before/download/why/contact structure"
            )
        download_start = subsections[2].start()
        download_end = subsections[3].start()
        download_section = section[download_start:download_end]
        lines = download_section.splitlines()
        expected_names = [
            f"{date_tag}-{suffix}" for suffix in DIRECT_DOWNLOAD_SUFFIXES
        ]

        for filename in expected_names:
            url = (
                f"https://github.com/{repository}/releases/download/"
                f"{release_tag}/{filename}"
            )
            direct_link = re.compile(
                rf"\[(?:`)?{re.escape(filename)}(?:`)?\]\({re.escape(url)}\)"
            )
            matching_lines = [line for line in lines if direct_link.search(line)]
            if len(matching_lines) != 1:
                raise PublishError(
                    f"{language} download guide must directly link the full filename {filename}"
                )

            if ".tensorrt.portable.7z." not in filename:
                linked_assets = sum(
                    expected_name in matching_lines[0]
                    for expected_name in expected_names
                )
                if linked_assets != 1:
                    raise PublishError(
                        f"{language} download guide must put {filename} on its own row"
                    )


def validate_no_unresolved_note_markers(body: str) -> None:
    """Reject release notes that still contain authoring placeholders."""

    for marker in UNRESOLVED_NOTE_MARKERS:
        match = marker.search(body)
        if match is not None:
            raise PublishError(
                "Reviewed release notes contain an unresolved marker: "
                f"{match.group(0)}"
            )


@dataclass(frozen=True)
class ReleaseRequest:
    date_tag: str
    release_tag: str
    title: str
    prerelease: bool
    notes_file: str

    @classmethod
    def load(cls, path: Path) -> "ReleaseRequest":
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise PublishError(f"Unable to read release request {path}: {exc}") from exc

        required = {"date_tag", "release_tag", "title", "prerelease", "notes_file"}
        missing = sorted(required.difference(payload))
        if missing:
            raise PublishError(f"Release request is missing: {', '.join(missing)}")

        request = cls(
            date_tag=str(payload["date_tag"]).strip(),
            release_tag=str(payload["release_tag"]).strip(),
            title=str(payload["title"]).strip(),
            prerelease=payload["prerelease"],
            notes_file=str(payload["notes_file"]).strip(),
        )
        request.validate()
        return request

    def validate(self) -> None:
        match = TAG_PATTERN.fullmatch(self.release_tag)
        if not match:
            raise PublishError(
                "release_tag must use next-YYYY-MM-DD.N, for example next-2026-07-22.1"
            )
        if match.group(1) != self.date_tag:
            raise PublishError("date_tag must match the date embedded in release_tag")
        if match.group(2).startswith("0"):
            raise PublishError("release serial must be a positive integer without leading zeros")
        if self.prerelease is not True:
            raise PublishError("Automated release requests must explicitly set prerelease to true")
        if self.title != f"LizzieYzy Next {self.release_tag}":
            raise PublishError("title must be exactly 'LizzieYzy Next <release_tag>'")
        expected_notes = f".github/release-notes/{self.release_tag}.md"
        if self.notes_file != expected_notes:
            raise PublishError(f"notes_file must be exactly {expected_notes}")


@dataclass(frozen=True)
class WorkflowSpec:
    platform: str
    workflow_file: str
    exact_suffixes: tuple[str, ...]
    run_name_template: str
    provenance_platform: str
    required_patterns: tuple[re.Pattern[str], ...] = ()
    dispatch_inputs: tuple[tuple[str, str], ...] = ()

    def expected_run_name(
        self, date_tag: str, release_tag: str, prerelease: str = "true"
    ) -> str:
        return self.run_name_template.format(
            date_tag=date_tag,
            release_tag=release_tag,
            prerelease=prerelease,
        )

    def missing_assets(self, asset_names: Iterable[str], date_tag: str) -> list[str]:
        names = set(asset_names)
        missing = [
            f"{date_tag}-{suffix}"
            for suffix in self.exact_suffixes
            if f"{date_tag}-{suffix}" not in names
        ]
        for pattern in self.required_patterns:
            rendered = re.compile(pattern.pattern.format(date=re.escape(date_tag)))
            if not any(rendered.fullmatch(name) for name in names):
                missing.append(pattern.pattern.format(date=date_tag))
        return missing


WORKFLOWS = (
    WorkflowSpec(
        "Windows",
        "build-windows-release.yml",
        (
            "windows64.opencl.installer.exe",
            "windows64.opencl.portable.zip",
            "windows64.nvidia.installer.exe",
            "windows64.nvidia.portable.zip",
            "windows64.experimental.directml.portable.zip",
            "windows64.experimental.openvino.portable.zip",
            "windows64.experimental.rocm.gfx103x.portable.zip",
            "windows64.experimental.rocm.gfx110x.portable.zip",
            "windows64.experimental.rocm.gfx1151.portable.zip",
            "windows64.experimental.rocm.gfx120x.portable.zip",
            "windows64.with-katago.installer.exe",
            "windows64.with-katago.portable.zip",
            "windows64.without.engine.installer.exe",
            "windows64.without.engine.portable.zip",
            "windows64.core-update.zip",
            "windows64.nvidia.tensorrt.portable.README.txt",
            "windows64.nvidia.tensorrt.portable.manifest.json",
            "windows64.nvidia.tensorrt.portable.sha256.txt",
            "windows64.nvidia.tensorrt.portable.7z.001",
            "windows64.nvidia.tensorrt.portable.7z.002",
        ),
        "Windows release {release_tag} | {date_tag} | prerelease={prerelease}",
        "windows",
        dispatch_inputs=(("release_prerelease", "true"),),
    ),
    WorkflowSpec(
        "Linux",
        "build-linux-release.yml",
        ("linux64.with-katago.zip", "linux64.opencl.zip", "linux64.nvidia.zip"),
        "Linux release {release_tag} | {date_tag} | prerelease={prerelease}",
        "linux",
        dispatch_inputs=(("release_prerelease", "true"),),
    ),
    WorkflowSpec(
        "macOS Intel",
        "build-macos-amd64-release.yml",
        ("mac-intel.with-katago.dmg",),
        "macOS Intel release {release_tag} | {date_tag} | prerelease={prerelease}",
        "mac-amd64",
        dispatch_inputs=(("release_prerelease", "true"),),
    ),
    WorkflowSpec(
        "macOS Apple Silicon",
        "build-macos-arm64-release.yml",
        ("mac-apple-silicon.with-katago.dmg",),
        "macOS Apple Silicon release {release_tag} | {date_tag} | prerelease={prerelease}",
        "mac-arm64",
        dispatch_inputs=(("release_prerelease", "true"),),
    ),
)


class GitHubClient:
    def __init__(
        self,
        repository: str,
        token: str,
        api_url: str | None = None,
        *,
        sleep: Callable[[float], None] = time.sleep,
        retry_attempts: int = 5,
    ) -> None:
        if not token:
            raise PublishError("GITHUB_TOKEN is required")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise PublishError("repository must use owner/name format")
        if retry_attempts < 1:
            raise PublishError("retry_attempts must be positive")
        self.repository = repository
        self.token = token
        self.api_url = (api_url or "https://api.github.com").rstrip("/")
        self.sleep = sleep
        self.retry_attempts = retry_attempts

    @staticmethod
    def _retry_delay(exc: HTTPError | None, attempt: int) -> float:
        fallback = float(min(2**attempt, 30))
        if exc is None:
            return fallback
        headers = exc.headers or {}
        retry_after = headers.get("Retry-After")
        if retry_after:
            try:
                return float(max(0, min(int(retry_after), 60)))
            except ValueError:
                pass
        reset = headers.get("X-RateLimit-Reset")
        if reset:
            try:
                return float(max(0, min(int(reset) - int(time.time()) + 1, 60)))
            except ValueError:
                pass
        return fallback

    @staticmethod
    def _is_transient_http_error(exc: HTTPError) -> bool:
        headers = exc.headers or {}
        return (
            exc.code == 429
            or 500 <= exc.code <= 599
            or (
                exc.code == 403
                and (
                    headers.get("Retry-After") is not None
                    or headers.get("X-RateLimit-Remaining") == "0"
                )
            )
        )

    def _request_raw(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None = None,
        expected: tuple[int, ...] = (200,),
        allow_not_found: bool = False,
        *,
        accept: str = "application/vnd.github+json",
        max_bytes: int = 16 * 1024 * 1024,
        retry_transient: bool = True,
    ) -> tuple[int, bytes | None]:
        body = json.dumps(payload).encode("utf-8") if payload is not None else None
        attempts = self.retry_attempts if retry_transient else 1
        for attempt in range(1, attempts + 1):
            request = Request(
                f"{self.api_url}{path}",
                data=body,
                method=method,
                headers={
                    "Accept": accept,
                    "Content-Type": "application/json",
                    "User-Agent": "lizzieyzy-next-release-publisher",
                    "X-GitHub-Api-Version": API_VERSION,
                },
            )
            # GitHub's artifact download endpoint redirects to a short-lived
            # external storage URL. Keep the repository token on the first hop
            # only: urllib copies ordinary headers to redirected requests.
            request.add_unredirected_header(
                "Authorization", f"Bearer {self.token}"
            )
            try:
                with urlopen(request, timeout=60) as response:
                    status = response.status
                    raw = response.read(max_bytes + 1)
            except HTTPError as exc:
                if allow_not_found and exc.code == 404:
                    return 404, None
                detail = exc.read(2000).decode("utf-8", errors="replace")
                if self._is_transient_http_error(exc) and attempt < attempts:
                    delay = self._retry_delay(exc, attempt)
                    print(
                        f"GitHub API {method} {path} returned {exc.code}; "
                        f"retrying in {delay:g}s ({attempt}/{attempts})",
                        file=sys.stderr,
                        flush=True,
                    )
                    self.sleep(delay)
                    continue
                raise PublishError(
                    f"GitHub API {method} {path} failed ({exc.code}): {detail}"
                ) from exc
            except OSError as exc:
                if attempt < attempts:
                    delay = self._retry_delay(None, attempt)
                    print(
                        f"GitHub API {method} {path} failed transiently; "
                        f"retrying in {delay:g}s ({attempt}/{attempts})",
                        file=sys.stderr,
                        flush=True,
                    )
                    self.sleep(delay)
                    continue
                raise PublishError(f"GitHub API {method} {path} failed: {exc}") from exc

            if status not in expected:
                raise PublishError(
                    f"GitHub API {method} {path} returned {status}; expected {expected}"
                )
            if len(raw) > max_bytes:
                raise PublishError(
                    f"GitHub API {method} {path} response exceeds {max_bytes} bytes"
                )
            return status, raw
        raise AssertionError("unreachable GitHub retry loop")

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None = None,
        expected: tuple[int, ...] = (200,),
        allow_not_found: bool = False,
        *,
        retry_transient: bool = True,
    ) -> tuple[int, dict[str, object] | list[object] | None]:
        status, raw = self._request_raw(
            method,
            path,
            payload,
            expected,
            allow_not_found,
            retry_transient=retry_transient,
        )
        if not raw:
            return status, None
        try:
            return status, json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError as exc:
            raise PublishError(f"GitHub API {method} {path} returned invalid JSON") from exc

    def _request_bytes(self, path: str, *, max_bytes: int) -> bytes:
        _status, raw = self._request_raw(
            "GET",
            path,
            # GitHub's artifact archive endpoint is still an API endpoint on the
            # first hop. It requires the JSON media type, then redirects to the
            # short-lived URL that returns the ZIP bytes.
            accept="application/vnd.github+json",
            max_bytes=max_bytes,
        )
        if raw is None:
            raise PublishError(f"GitHub API GET {path} returned an empty response")
        return raw

    def get_tag_sha(self, tag: str) -> str | None:
        path = f"/repos/{self.repository}/git/ref/tags/{quote(tag, safe='')}"
        _status, payload = self._request("GET", path, allow_not_found=True)
        if payload is None:
            return None
        assert isinstance(payload, dict)
        obj = payload.get("object")
        if not isinstance(obj, dict) or obj.get("type") != "commit" or not obj.get("sha"):
            raise PublishError(f"Existing tag {tag} is not a lightweight commit tag")
        return str(obj["sha"])

    def create_tag(self, tag: str, target_sha: str) -> None:
        self._request(
            "POST",
            f"/repos/{self.repository}/git/refs",
            {"ref": f"refs/tags/{tag}", "sha": target_sha},
            expected=(201,),
            # Creating a ref has no idempotency key. If GitHub accepted the
            # request but the response was lost, a retry produces a misleading
            # 422. A publisher rerun discovers and verifies the existing tag.
            retry_transient=False,
        )

    def get_release_by_tag(self, tag: str) -> dict[str, object] | None:
        path = f"/repos/{self.repository}/releases/tags/{quote(tag, safe='')}"
        _status, payload = self._request("GET", path, allow_not_found=True)
        if payload is not None:
            assert isinstance(payload, dict)
            return payload
        return None

    def list_releases(self) -> list[dict[str, object]]:
        releases: list[dict[str, object]] = []
        for page in range(1, 11):
            _status, payload = self._request(
                "GET",
                f"/repos/{self.repository}/releases?per_page=100&page={page}",
            )
            if not isinstance(payload, list) or len(payload) > 100:
                raise PublishError("Release listing returned invalid pagination")
            if any(not isinstance(release, dict) for release in payload):
                raise PublishError("Release listing contains invalid metadata")
            releases.extend(
                release for release in payload if isinstance(release, dict)
            )
            if len(payload) < 100:
                return releases
        raise PublishError("Repository has too many releases to validate safely")

    def get_release(self, tag: str) -> dict[str, object] | None:
        release = self.get_release_by_tag(tag)
        if release is not None:
            return release

        # The tag endpoint can omit drafts. The authenticated release listing includes them.
        for release in self.list_releases():
            if release.get("tag_name") == tag:
                return release
        return None

    def find_orphaned_release(
        self, title: str, target_sha: str
    ) -> dict[str, object] | None:
        candidates = [
            release
            for release in self.list_releases()
            if str(release.get("tag_name") or "").startswith("untagged-")
            and release.get("name") == title
            and release.get("target_commitish") == target_sha
            and release.get("prerelease") is True
        ]
        if len(candidates) > 1:
            raise PublishError(
                f"Found multiple orphaned releases for {title} at {target_sha}"
            )
        return candidates[0] if candidates else None

    def list_detached_tag_aliases(self, target_sha: str) -> list[str]:
        _status, payload = self._request(
            "GET",
            f"/repos/{self.repository}/git/matching-refs/tags/untagged-",
        )
        assert isinstance(payload, list)
        aliases: list[str] = []
        for ref in payload:
            if not isinstance(ref, dict):
                continue
            name = str(ref.get("ref") or "").removeprefix("refs/tags/")
            obj = ref.get("object")
            if (
                re.fullmatch(r"untagged-[0-9a-f]{20}", name)
                and isinstance(obj, dict)
                and obj.get("type") == "commit"
                and obj.get("sha") == target_sha
            ):
                aliases.append(name)
        return aliases

    def delete_tag(self, tag: str) -> None:
        self._request(
            "DELETE",
            f"/repos/{self.repository}/git/refs/tags/{quote(tag, safe='')}",
            expected=(204,),
            # DELETE is idempotent. A retry after a lost successful response may
            # see 404, which means the requested absent state was achieved.
            allow_not_found=True,
        )

    def create_draft_release(
        self, request: ReleaseRequest, target_sha: str
    ) -> dict[str, object]:
        _status, payload = self._request(
            "POST",
            f"/repos/{self.repository}/releases",
            {
                "tag_name": request.release_tag,
                "target_commitish": target_sha,
                "name": request.title,
                "body": "Multi-platform packages are being built and verified.",
                "draft": True,
                "prerelease": True,
                "generate_release_notes": False,
                "make_latest": "false",
            },
            expected=(201,),
            # Release creation has no idempotency key. Recovery is performed by
            # discovering the existing tagged draft or its untagged orphan.
            retry_transient=False,
        )
        assert isinstance(payload, dict)
        return payload

    def update_release(self, release_id: int, payload: dict[str, object]) -> dict[str, object]:
        _status, response = self._request(
            "PATCH",
            f"/repos/{self.repository}/releases/{release_id}",
            payload,
        )
        assert isinstance(response, dict)
        return response

    def list_release_assets(self, release_id: int) -> list[dict[str, object]]:
        assets: list[dict[str, object]] = []
        for page in range(1, 11):
            _status, payload = self._request(
                "GET",
                f"/repos/{self.repository}/releases/{release_id}/assets"
                f"?per_page=100&page={page}",
            )
            if not isinstance(payload, list) or len(payload) > 100:
                raise PublishError("Release asset listing returned invalid pagination")
            if any(not isinstance(asset, dict) for asset in payload):
                raise PublishError("Release asset listing contains invalid metadata")
            assets.extend(asset for asset in payload if isinstance(asset, dict))
            if len(payload) < 100:
                return assets
        raise PublishError("Release has too many assets to validate safely")

    def list_workflow_runs(
        self, workflow_file: str, target_sha: str
    ) -> list[dict[str, object]]:
        workflow = quote(workflow_file, safe="")
        runs: list[dict[str, object]] = []
        seen_ids: set[int] = set()
        expected_total: int | None = None
        for page in range(1, 11):
            query = urlencode(
                {
                    "event": "workflow_dispatch",
                    "head_sha": target_sha,
                    "per_page": 100,
                    "page": page,
                }
            )
            _status, payload = self._request(
                "GET",
                f"/repos/{self.repository}/actions/workflows/{workflow}/runs?{query}",
            )
            if not isinstance(payload, dict):
                raise PublishError("Workflow run listing returned invalid metadata")
            page_runs = payload.get("workflow_runs")
            total_count = payload.get("total_count")
            if (
                not isinstance(page_runs, list)
                or len(page_runs) > 100
                or type(total_count) is not int
                or total_count < 0
                or any(not isinstance(run, dict) for run in page_runs)
            ):
                raise PublishError("Workflow run listing returned invalid pagination")
            if expected_total is None:
                expected_total = total_count
            elif total_count != expected_total:
                raise PublishError("Workflow run listing changed during pagination")
            for run in page_runs:
                assert isinstance(run, dict)
                run_id = run.get("id")
                if type(run_id) is not int or run_id in seen_ids:
                    raise PublishError(
                        "Workflow run listing contains invalid or duplicate run IDs"
                    )
                seen_ids.add(run_id)
                runs.append(run)
            if len(runs) == expected_total:
                return runs
            if len(runs) > expected_total or len(page_runs) < 100:
                raise PublishError("Workflow run listing is truncated or inconsistent")
        raise PublishError("Workflow run listing exceeds the 1,000-run audit limit")

    def list_ci_runs(self, target_sha: str) -> list[dict[str, object]]:
        workflow = quote(CI_WORKFLOW_FILE, safe="")
        query = urlencode(
            {"event": "push", "head_sha": target_sha, "per_page": 20}
        )
        _status, payload = self._request(
            "GET",
            f"/repos/{self.repository}/actions/workflows/{workflow}/runs?{query}",
        )
        assert isinstance(payload, dict)
        runs = payload.get("workflow_runs", [])
        return [run for run in runs if isinstance(run, dict)]

    def dispatch_workflow(
        self, workflow_file: str, tag: str, inputs: dict[str, str]
    ) -> int | None:
        workflow = quote(workflow_file, safe="")
        _status, payload = self._request(
            "POST",
            f"/repos/{self.repository}/actions/workflows/{workflow}/dispatches",
            {"ref": tag, "inputs": inputs},
            expected=(200, 204),
            # workflow_dispatch has no idempotency key. An ambiguous retry can
            # create two runs that race to clobber the same draft assets.
            retry_transient=False,
        )
        if isinstance(payload, dict) and payload.get("workflow_run_id") is not None:
            return int(payload["workflow_run_id"])
        return None

    def get_workflow_run(self, run_id: int) -> dict[str, object]:
        _status, payload = self._request(
            "GET", f"/repos/{self.repository}/actions/runs/{run_id}"
        )
        assert isinstance(payload, dict)
        return payload

    def list_run_artifacts(self, run_id: int) -> list[dict[str, object]]:
        _status, payload = self._request(
            "GET",
            f"/repos/{self.repository}/actions/runs/{run_id}/artifacts?per_page=100",
        )
        assert isinstance(payload, dict)
        artifacts = payload.get("artifacts")
        total_count = payload.get("total_count")
        if not isinstance(artifacts, list) or type(total_count) is not int:
            raise PublishError(f"Workflow run {run_id} returned invalid artifact metadata")
        if total_count != len(artifacts):
            raise PublishError(
                f"Workflow run {run_id} artifact listing is truncated or inconsistent"
            )
        return [artifact for artifact in artifacts if isinstance(artifact, dict)]

    def download_artifact_zip(self, artifact_id: int) -> bytes:
        return self._request_bytes(
            f"/repos/{self.repository}/actions/artifacts/{artifact_id}/zip",
            max_bytes=2 * 1024 * 1024,
        )


class ReleasePublisher:
    def __init__(
        self,
        client: GitHubClient,
        request: ReleaseRequest,
        target_sha: str,
        release_notes: str,
        sleep: Callable[[float], None] = time.sleep,
        poll_seconds: float = 30,
        run_timeout_seconds: float = 4 * 60 * 60 + 45 * 60,
        ci_timeout_seconds: float = 20 * 60,
    ) -> None:
        if not re.fullmatch(r"[0-9a-f]{40}", target_sha):
            raise PublishError("target_sha must be a full 40-character commit SHA")
        self.client = client
        self.request = request
        self.target_sha = target_sha
        self.release_notes = release_notes
        self.sleep = sleep
        self.poll_seconds = poll_seconds
        self.run_timeout_seconds = run_timeout_seconds
        self.ci_timeout_seconds = ci_timeout_seconds
        self.run_urls: dict[str, str] = {}
        if not self._notes_text_complete(release_notes):
            raise PublishError("Reviewed release notes are missing the tag or a language section")
        validate_no_unresolved_note_markers(release_notes)
        validate_direct_download_tables(
            release_notes,
            request.date_tag,
            request.release_tag,
            client.repository,
        )

    def _ensure_tag(self) -> None:
        existing = self.client.get_tag_sha(self.request.release_tag)
        if existing is None:
            self.client.create_tag(self.request.release_tag, self.target_sha)
            print(f"Created tag {self.request.release_tag} at {self.target_sha}", flush=True)
            return
        if existing != self.target_sha:
            raise PublishError(
                f"Tag {self.request.release_tag} points to {existing}, expected {self.target_sha}"
            )
        print(f"Reusing tag {self.request.release_tag} at {existing}", flush=True)

    def _assert_live_tag_identity(self) -> None:
        actual = self.client.get_tag_sha(self.request.release_tag)
        if actual != self.target_sha:
            raise PublishError(
                f"Live tag {self.request.release_tag} changed: "
                f"expected {self.target_sha}, got {actual or '<missing>'}"
            )

    def _assert_release_identity(self, release: dict[str, object]) -> None:
        actual_tag = str(release.get("tag_name") or "")
        if actual_tag != self.request.release_tag:
            raise PublishError(
                "Release tag identity changed: "
                f"expected {self.request.release_tag}, got {actual_tag or '<missing>'}"
            )
        actual_target = str(release.get("target_commitish") or "")
        if actual_target != self.target_sha:
            raise PublishError(
                "Release target changed: "
                f"expected {self.target_sha}, got {actual_target or '<missing>'}"
            )
        actual_title = str(release.get("name") or "")
        if actual_title != self.request.title:
            raise PublishError(
                "Release title changed: "
                f"expected {self.request.title}, got {actual_title or '<missing>'}"
            )

    def _restore_orphaned_release(
        self, release: dict[str, object]
    ) -> dict[str, object]:
        if release.get("draft") is not True:
            raise PublishError(
                "Refusing to bind a public orphaned release to the requested tag; "
                "make it a draft or delete it before recovery"
            )
        release_id = int(release["id"])
        restored = self.client.update_release(
            release_id,
            {
                "tag_name": self.request.release_tag,
                "target_commitish": self.target_sha,
                "name": self.request.title,
                "draft": True,
                "prerelease": True,
                "make_latest": "false",
            },
        )
        self._assert_release_identity(restored)
        print(
            f"Restored orphaned release {release_id} to {self.request.release_tag}",
            flush=True,
        )
        return restored

    def _verify_public_release_identity(
        self, release_id: int
    ) -> dict[str, object]:
        release = self.client.get_release_by_tag(self.request.release_tag)
        if release is None:
            raise PublishError(
                f"Published release is not addressable by tag {self.request.release_tag}"
            )
        if int(release.get("id", -1)) != release_id:
            raise PublishError(
                f"Tag {self.request.release_tag} resolves to a different release"
            )
        self._assert_release_identity(release)
        if release.get("draft") is not False or release.get("prerelease") is not True:
            raise PublishError("Release is not publicly visible as a pre-release")
        self._assert_canonical_notes(release)
        self._assert_live_tag_identity()
        return release

    def _cleanup_detached_tag_aliases(self) -> None:
        for alias in self.client.list_detached_tag_aliases(self.target_sha):
            if self.client.get_release_by_tag(alias) is not None:
                continue
            self.client.delete_tag(alias)
            print(f"Removed detached release tag alias {alias}", flush=True)

    def _ensure_draft_release(self) -> tuple[dict[str, object], bool]:
        release = self.client.get_release(self.request.release_tag)
        if release is None:
            orphaned = self.client.find_orphaned_release(
                self.request.title, self.target_sha
            )
            if orphaned is not None:
                release = self._restore_orphaned_release(orphaned)
            else:
                release = self.client.create_draft_release(self.request, self.target_sha)
                print(f"Created draft pre-release {self.request.release_tag}", flush=True)
                self._assert_release_identity(release)
                return release, False
        self._assert_release_identity(release)
        if release.get("prerelease") is not True:
            raise PublishError("Existing release is not marked as a pre-release")
        if release.get("draft") is False:
            return release, True
        print(f"Reusing draft pre-release {self.request.release_tag}", flush=True)
        return release, False

    def _wait_for_target_ci(self) -> None:
        deadline = time.monotonic() + self.ci_timeout_seconds
        last_state: tuple[str, object] | None = None
        while True:
            matching = [
                run
                for run in self.client.list_ci_runs(self.target_sha)
                if run.get("head_sha") == self.target_sha and run.get("id") is not None
            ]
            run = max(matching, key=lambda item: int(item["id"]), default=None)
            if run is not None:
                run_id = int(run["id"])
                status = str(run.get("status", "unknown"))
                conclusion = run.get("conclusion")
                state = (status, conclusion)
                if state != last_state:
                    print(
                        f"CI: {status} ({conclusion or 'pending'}) on {self.target_sha}",
                        flush=True,
                    )
                    last_state = state
                if run.get("html_url"):
                    self.run_urls["CI"] = str(run["html_url"])
                if status == "completed":
                    if conclusion != "success":
                        raise PublishError(
                            f"CI workflow run {run_id} completed with {conclusion}"
                        )
                    return
            if time.monotonic() >= deadline:
                raise PublishError(
                    f"Timed out waiting for successful CI on target {self.target_sha}"
                )
            self.sleep(self.poll_seconds)

    def _expected_run_name(self, spec: WorkflowSpec) -> str:
        inputs = dict(spec.dispatch_inputs)
        return spec.expected_run_name(
            self.request.date_tag,
            self.request.release_tag,
            inputs.get("release_prerelease", "true"),
        )

    def _run_has_expected_identity(
        self, spec: WorkflowSpec, run: dict[str, object]
    ) -> bool:
        return (
            run.get("head_sha") == self.target_sha
            and run.get("display_title") == self._expected_run_name(spec)
            and run.get("id") is not None
        )

    def _latest_target_run(self, spec: WorkflowSpec) -> dict[str, object] | None:
        matching = [
            run
            for run in self.client.list_workflow_runs(
                spec.workflow_file, self.target_sha
            )
            if self._run_has_expected_identity(spec, run)
        ]
        return max(matching, key=lambda run: int(run["id"]), default=None)

    def _assert_successful_target_run(
        self, spec: WorkflowSpec, run_id: int, run: dict[str, object]
    ) -> None:
        if not self._run_has_expected_identity(spec, run):
            raise PublishError(
                f"{spec.platform} workflow run {run_id} does not match target SHA "
                "and exact release inputs"
            )
        status = str(run.get("status", "unknown"))
        conclusion = run.get("conclusion")
        if status != "completed" or conclusion != "success":
            raise PublishError(
                f"{spec.platform} workflow run {run_id} is not a successful completed run"
            )
        if type(run.get("run_attempt")) is not int or int(run["run_attempt"]) < 1:
            raise PublishError(
                f"{spec.platform} workflow run {run_id} has no valid run attempt"
            )

    def _discover_new_run(
        self, spec: WorkflowSpec, previous_ids: set[int], timeout_seconds: float = 90
    ) -> int:
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            for run in self.client.list_workflow_runs(
                spec.workflow_file, self.target_sha
            ):
                run_id = int(run["id"])
                if run_id not in previous_ids and self._run_has_expected_identity(
                    spec, run
                ):
                    return run_id
            self.sleep(min(self.poll_seconds, 10))
        raise PublishError(
            f"Timed out locating dispatched workflow run for {spec.workflow_file}"
        )

    def _dispatch(self, spec: WorkflowSpec) -> int:
        workflow_file = spec.workflow_file
        existing = self.client.list_workflow_runs(workflow_file, self.target_sha)
        previous_ids = {int(run["id"]) for run in existing if run.get("id") is not None}
        inputs = {
            "date_tag": self.request.date_tag,
            "release_tag": self.request.release_tag,
        }
        inputs.update(spec.dispatch_inputs)
        run_id = self.client.dispatch_workflow(
            workflow_file,
            self.request.release_tag,
            inputs,
        )
        if run_id is None:
            run_id = self._discover_new_run(spec, previous_ids)
        print(f"Dispatched {workflow_file}: run {run_id}", flush=True)
        return run_id

    def _wait_for_runs(self, runs: dict[str, int]) -> None:
        if not runs:
            return
        started_at = time.monotonic()
        deadline = started_at + self.run_timeout_seconds
        identity_deadline = min(
            deadline, started_at + WORKFLOW_IDENTITY_CONVERGENCE_SECONDS
        )
        pending = dict(runs)
        last_state: dict[int, tuple[str, object]] = {}
        identity_confirmed: set[int] = set()
        identity_wait_announced: set[int] = set()
        while pending:
            if time.monotonic() >= deadline:
                names = ", ".join(sorted(pending))
                raise PublishError(f"Timed out waiting for workflows: {names}")
            awaiting_initial_identity = False
            for name, run_id in list(pending.items()):
                spec = next(item for item in WORKFLOWS if item.platform == name)
                run = self.client.get_workflow_run(run_id)
                if not self._run_has_expected_identity(spec, run):
                    if run_id in identity_confirmed:
                        raise PublishError(
                            f"{name} workflow run {run_id} changed identity after "
                            "initial verification"
                        )
                    if time.monotonic() >= identity_deadline:
                        raise PublishError(
                            f"{name} workflow run {run_id} identity did not converge "
                            "to the target SHA and exact release inputs within "
                            f"{WORKFLOW_IDENTITY_CONVERGENCE_SECONDS} seconds"
                        )
                    if run_id not in identity_wait_announced:
                        print(
                            f"{name}: waiting for workflow run {run_id} identity "
                            "metadata to converge",
                            flush=True,
                        )
                        identity_wait_announced.add(run_id)
                    awaiting_initial_identity = True
                    continue
                identity_confirmed.add(run_id)
                if run_id in identity_wait_announced:
                    print(f"{name}: workflow run {run_id} identity confirmed", flush=True)
                    identity_wait_announced.remove(run_id)
                status = str(run.get("status", "unknown"))
                conclusion = run.get("conclusion")
                state = (status, conclusion)
                if last_state.get(run_id) != state:
                    print(f"{name}: {status} ({conclusion or 'pending'})", flush=True)
                    last_state[run_id] = state
                if run.get("html_url"):
                    self.run_urls[name] = str(run["html_url"])
                if status == "completed":
                    if conclusion != "success":
                        raise PublishError(
                            f"{name} workflow run {run_id} completed with {conclusion}"
                        )
                    del pending[name]
            if pending:
                self.sleep(
                    min(self.poll_seconds, 5)
                    if awaiting_initial_identity
                    else self.poll_seconds
                )

    def _require_successful_target_runs(
        self, selected_runs: dict[str, int] | None = None
    ) -> dict[str, dict[str, object]]:
        verified: dict[str, dict[str, object]] = {}
        for spec in WORKFLOWS:
            if selected_runs is not None:
                run_id = selected_runs[spec.platform]
                run = self.client.get_workflow_run(run_id)
            else:
                run = self._latest_target_run(spec)
                if run is None:
                    raise PublishError(
                        f"{spec.platform} has no workflow run for target {self.target_sha}"
                    )
                run_id = int(run["id"])
            self._assert_successful_target_run(spec, run_id, run)
            verified[spec.platform] = run
            if run.get("html_url"):
                self.run_urls[spec.platform] = str(run["html_url"])
        return verified

    def _require_no_active_target_runs(self, selected_runs: dict[str, int]) -> None:
        selected_ids = set(selected_runs.values())
        active: list[str] = []
        for spec in WORKFLOWS:
            for listed_run in self.client.list_workflow_runs(
                spec.workflow_file, self.target_sha
            ):
                if not self._run_has_expected_identity(spec, listed_run):
                    continue
                run_id = int(listed_run["id"])
                if run_id in selected_ids:
                    # The selected runs were just re-read and required to be completed
                    # successfully. Ignore a stale active status in the list endpoint.
                    continue
                listed_status = str(listed_run.get("status", "unknown"))
                if listed_status not in ACTIVE_RUN_STATUSES:
                    continue
                run = self.client.get_workflow_run(run_id)
                if not self._run_has_expected_identity(spec, run):
                    raise PublishError(
                        f"{spec.platform} workflow run {run_id} changed identity "
                        "during the final active-run check"
                    )
                status = str(run.get("status", "unknown"))
                if status in ACTIVE_RUN_STATUSES:
                    active.append(f"{spec.platform} run {run_id} ({status})")
        if active:
            raise PublishError(
                "Refusing to publish while duplicate target workflow runs are active: "
                + ", ".join(active)
            )

    def _load_run_provenance(
        self, spec: WorkflowSpec, run: dict[str, object]
    ) -> dict[str, dict[str, object]]:
        run_id = int(run["id"])
        run_attempt = int(run["run_attempt"])
        expected_artifact_name = provenance.artifact_name(
            spec.provenance_platform, run_attempt
        )
        artifacts = self.client.list_run_artifacts(run_id)
        matches = [
            artifact
            for artifact in artifacts
            if artifact.get("name") == expected_artifact_name
        ]
        if len(matches) != 1:
            raise PublishError(
                f"{spec.platform} run {run_id} must have exactly one "
                f"{expected_artifact_name} artifact"
            )
        artifact = matches[0]
        if artifact.get("expired") is not False:
            raise PublishError(
                f"{spec.platform} run {run_id} provenance artifact is expired"
            )
        artifact_id = artifact.get("id")
        artifact_size = artifact.get("size_in_bytes")
        if type(artifact_id) is not int or int(artifact_id) < 1:
            raise PublishError(f"{spec.platform} provenance artifact id is invalid")
        if (
            type(artifact_size) is not int
            or int(artifact_size) < 1
            or int(artifact_size) > 2 * 1024 * 1024
        ):
            raise PublishError(f"{spec.platform} provenance artifact size is invalid")
        workflow_run = artifact.get("workflow_run")
        if not isinstance(workflow_run, dict) or (
            workflow_run.get("id") != run_id
            or workflow_run.get("head_sha") != self.target_sha
        ):
            raise PublishError(
                f"{spec.platform} provenance artifact is not bound to run {run_id}"
            )
        artifact_digest = artifact.get("digest")
        if not isinstance(artifact_digest, str) or re.fullmatch(
            r"sha256:[0-9a-f]{64}", artifact_digest
        ) is None:
            raise PublishError(
                f"{spec.platform} provenance artifact has no valid SHA-256 digest"
            )

        archive = self.client.download_artifact_zip(int(artifact_id))
        if len(archive) != artifact_size:
            raise PublishError(
                f"{spec.platform} provenance artifact download size does not match metadata"
            )
        archive_digest = "sha256:" + hashlib.sha256(archive).hexdigest()
        if archive_digest != artifact_digest:
            raise PublishError(
                f"{spec.platform} provenance artifact digest does not match its download"
            )
        try:
            with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
                files = [info for info in bundle.infolist() if not info.is_dir()]
                if len(files) != 1 or files[0].filename != provenance.PROVENANCE_FILENAME:
                    raise PublishError(
                        f"{spec.platform} provenance artifact must contain only "
                        f"{provenance.PROVENANCE_FILENAME}"
                    )
                info = files[0]
                if info.flag_bits & 0x1 or info.file_size > 512 * 1024:
                    raise PublishError(
                        f"{spec.platform} provenance manifest is encrypted or oversized"
                    )
                raw_manifest = bundle.read(info)
        except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
            raise PublishError(
                f"{spec.platform} provenance artifact is not a valid ZIP: {exc}"
            ) from exc
        try:
            payload = json.loads(raw_manifest.decode("utf-8"))
            records = provenance.validate_provenance(
                payload,
                platform=spec.provenance_platform,
                date_tag=self.request.date_tag,
                release_tag=self.request.release_tag,
                target_sha=self.target_sha,
                run_id=run_id,
                run_attempt=run_attempt,
            )
        except (UnicodeDecodeError, json.JSONDecodeError, provenance.ProvenanceError) as exc:
            raise PublishError(
                f"{spec.platform} provenance manifest is invalid: {exc}"
            ) from exc

        publisher_names = {
            f"{self.request.date_tag}-{suffix}" for suffix in spec.exact_suffixes
        }
        if spec.provenance_platform == "windows":
            publisher_names.add("lizzieyzy-next-update-manifest.json")
        if set(records) != publisher_names:
            raise PublishError(
                f"{spec.platform} publisher and provenance asset inventories disagree"
            )
        return records

    def _verify_platform_assets(
        self, release_id: int, selected_runs: dict[str, dict[str, object]]
    ) -> list[str]:
        expected_records: dict[str, dict[str, object]] = {}
        for spec in WORKFLOWS:
            records = self._load_run_provenance(spec, selected_runs[spec.platform])
            overlap = set(expected_records).intersection(records)
            if overlap:
                raise PublishError(
                    "Duplicate asset provenance across workflows: "
                    + ", ".join(sorted(overlap))
                )
            expected_records.update(records)

        expected = set(expected_records)
        failure_details: list[str] = []
        for attempt in range(7):
            assets = self.client.list_release_assets(release_id)
            by_name: dict[str, dict[str, object]] = {}
            duplicate_names: list[str] = []
            invalid_metadata: list[str] = []
            for asset in assets:
                name = asset.get("name")
                if not isinstance(name, str) or not name:
                    invalid_metadata.append("<invalid-name>")
                    continue
                if name in by_name:
                    duplicate_names.append(name)
                    continue
                by_name[name] = asset
            actual = set(by_name)
            missing = sorted(expected - actual)
            unexpected = sorted((actual - expected) - {SIGNED_UPDATE_ENVELOPE_ASSET})
            mismatches: list[str] = []
            for name in sorted(expected.intersection(actual)):
                remote = by_name[name]
                trusted = expected_records[name]
                if remote.get("state") != "uploaded":
                    mismatches.append(f"{name}: state is not uploaded")
                size = remote.get("size")
                if type(size) is not int or int(size) <= 0:
                    mismatches.append(f"{name}: size is not positive")
                elif size != trusted["sizeBytes"]:
                    mismatches.append(f"{name}: size differs from run provenance")
                digest = remote.get("digest")
                if not isinstance(digest, str) or re.fullmatch(
                    r"sha256:[0-9a-f]{64}", digest
                ) is None:
                    mismatches.append(f"{name}: SHA-256 digest is missing or invalid")
                elif digest.removeprefix("sha256:") != trusted["sha256"]:
                    mismatches.append(f"{name}: digest differs from run provenance")
            failure_details = []
            if missing:
                failure_details.append("missing assets: " + ", ".join(missing))
            if unexpected:
                failure_details.append("unexpected assets: " + ", ".join(unexpected))
            if duplicate_names:
                failure_details.append(
                    "duplicate assets: " + ", ".join(sorted(set(duplicate_names)))
                )
            if invalid_metadata:
                failure_details.append("release asset metadata contains invalid names")
            if mismatches:
                failure_details.append("metadata/provenance mismatch: " + "; ".join(mismatches))
            if not failure_details:
                return sorted(actual)
            if attempt < 6:
                self.sleep(min(self.poll_seconds, 10))
        raise PublishError("Release has invalid assets: " + "; ".join(failure_details))

    def _notes_complete(self, release: dict[str, object]) -> bool:
        body = str(release.get("body") or "")
        if not self._notes_text_complete(body):
            return False
        try:
            validate_no_unresolved_note_markers(body)
            validate_direct_download_tables(
                body,
                self.request.date_tag,
                self.request.release_tag,
                self.client.repository,
            )
        except PublishError:
            return False
        return True

    def _assert_canonical_notes(self, release: dict[str, object]) -> None:
        if release.get("body") != self.release_notes:
            raise PublishError(
                "Release notes differ from the canonical reviewed release notes"
            )
        if not self._notes_complete(release):
            raise PublishError("Release notes failed canonical content validation")

    def _notes_text_complete(self, body: str) -> bool:
        return self.request.release_tag in body and all(
            heading in body for heading in LOCALIZED_NOTE_HEADINGS
        )

    def _publish_summary(self, release: dict[str, object], assets: list[str]) -> None:
        summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
        if not summary_path:
            return
        lines = [
            f"## Published {self.request.release_tag}",
            "",
            f"- Target: `{self.target_sha}`",
            f"- Assets verified: {len(assets)}",
            f"- Pre-release: `{str(release.get('prerelease')).lower()}`",
            f"- URL: {release.get('html_url', '')}",
            "",
            "### Workflow runs",
        ]
        lines.extend(f"- {name}: {url}" for name, url in sorted(self.run_urls.items()))
        with Path(summary_path).open("a", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")

    def publish(self) -> dict[str, object]:
        self._wait_for_target_ci()
        self._ensure_tag()
        release, already_published = self._ensure_draft_release()
        release_id = int(release["id"])
        if already_published:
            verified_runs = self._require_successful_target_runs()
            assets = self._verify_platform_assets(release_id, verified_runs)
            self._assert_canonical_notes(release)
            release = self._verify_public_release_identity(release_id)
            self._cleanup_detached_tag_aliases()
            print(f"{self.request.release_tag} is already complete", flush=True)
            self._publish_summary(release, assets)
            return release

        runs: dict[str, int] = {}
        for spec in WORKFLOWS:
            existing = self._latest_target_run(spec)
            if existing is not None and (
                str(existing.get("status")) in ACTIVE_RUN_STATUSES
                or (
                    existing.get("status") == "completed"
                    and existing.get("conclusion") == "success"
                )
            ):
                run_id = int(existing["id"])
                print(f"{spec.platform}: verifying existing run {run_id}", flush=True)
            else:
                run_id = self._dispatch(spec)
            runs[spec.platform] = run_id

        self._wait_for_runs(runs)
        verified_runs = self._require_successful_target_runs(runs)
        assets = self._verify_platform_assets(release_id, verified_runs)

        release = self.client.update_release(
            release_id,
            {
                "tag_name": self.request.release_tag,
                "target_commitish": self.target_sha,
                "name": self.request.title,
                "body": self.release_notes,
                "draft": True,
                "prerelease": True,
                "make_latest": "false",
            },
        )
        self._assert_release_identity(release)
        self._assert_canonical_notes(release)

        # Re-read every mutable gate immediately before public visibility. GitHub
        # does not offer an atomic "verify-and-publish" operation, so this second
        # fail-closed pass minimizes the mutation window and prevents a stale first
        # check from authorizing publication.
        self._wait_for_target_ci()
        verified_runs = self._require_successful_target_runs(runs)
        assets = self._verify_platform_assets(release_id, verified_runs)
        self._require_no_active_target_runs(runs)
        current = self.client.get_release(self.request.release_tag)
        if current is None or int(current.get("id", -1)) != release_id:
            raise PublishError("Draft release identity changed before publication")
        self._assert_release_identity(current)
        if current.get("draft") is not True or current.get("prerelease") is not True:
            raise PublishError("Release is no longer the expected draft pre-release")
        self._assert_canonical_notes(current)
        self._assert_live_tag_identity()

        release = self.client.update_release(
            release_id,
            {
                "tag_name": self.request.release_tag,
                "target_commitish": self.target_sha,
                "name": self.request.title,
                "body": self.release_notes,
                "draft": False,
                "prerelease": True,
                "make_latest": "false",
            },
        )
        self._assert_release_identity(release)
        if release.get("draft") is not False or release.get("prerelease") is not True:
            raise PublishError("GitHub did not publish the release as a pre-release")
        self._assert_canonical_notes(release)
        release = self._verify_public_release_identity(release_id)
        self._cleanup_detached_tag_aliases()
        assets = self._verify_platform_assets(release_id, verified_runs)
        self._publish_summary(release, assets)
        print(f"Published pre-release: {release.get('html_url', '')}", flush=True)
        return release


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", required=True, type=Path)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--target-sha", required=True)
    parser.add_argument("--api-url", default=os.environ.get("GITHUB_API_URL"))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        request = ReleaseRequest.load(args.request)
        repository_root = Path(__file__).resolve().parents[1]
        notes_path = repository_root / request.notes_file
        try:
            release_notes = notes_path.read_text(encoding="utf-8")
        except OSError as exc:
            raise PublishError(f"Unable to read reviewed release notes {notes_path}: {exc}") from exc
        client = GitHubClient(
            args.repository,
            os.environ.get("GITHUB_TOKEN", ""),
            api_url=args.api_url,
        )
        ReleasePublisher(client, request, args.target_sha, release_notes).publish()
    except PublishError as exc:
        print(f"release publishing failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
