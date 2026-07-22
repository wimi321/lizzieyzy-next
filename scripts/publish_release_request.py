#!/usr/bin/env python3
"""Publish an audited multi-platform pre-release request.

The release remains a draft until every platform workflow succeeds, all expected
assets are present, and the localized release notes have been generated.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
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


API_VERSION = "2026-03-10"
TAG_PATTERN = re.compile(r"^next-(\d{4}-\d{2}-\d{2})\.(\d+)$")
LOCALIZED_NOTE_HEADINGS = (
    "## 中文",
    "## 繁體中文",
    "## English",
    "## 日本語",
    "## 한국어",
    "## ภาษาไทย",
)
ACTIVE_RUN_STATUSES = {"queued", "in_progress", "waiting", "requested", "pending"}


class PublishError(RuntimeError):
    """A release invariant or GitHub API operation failed."""


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
    required_patterns: tuple[re.Pattern[str], ...] = ()
    dispatch_inputs: tuple[tuple[str, str], ...] = ()

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
            "windows64.nvidia50.cuda.installer.exe",
            "windows64.nvidia50.cuda.portable.zip",
            "windows64.with-katago.installer.exe",
            "windows64.with-katago.portable.zip",
            "windows64.without.engine.installer.exe",
            "windows64.without.engine.portable.zip",
            "windows64.core-update.zip",
            "windows64.nvidia.tensorrt.portable.README.txt",
            "windows64.nvidia.tensorrt.portable.manifest.json",
            "windows64.nvidia.tensorrt.portable.sha256.txt",
        ),
        (re.compile(r"^{date}-windows64\.nvidia\.tensorrt\.portable\.7z\.\d{{3}}$"),),
        (("release_prerelease", "true"),),
    ),
    WorkflowSpec(
        "Linux",
        "build-linux-release.yml",
        ("linux64.with-katago.zip", "linux64.opencl.zip", "linux64.nvidia.zip"),
    ),
    WorkflowSpec(
        "macOS Intel",
        "build-macos-amd64-release.yml",
        ("mac-intel.with-katago.dmg",),
    ),
    WorkflowSpec(
        "macOS Apple Silicon",
        "build-macos-arm64-release.yml",
        ("mac-apple-silicon.with-katago.dmg",),
    ),
)


class GitHubClient:
    def __init__(self, repository: str, token: str, api_url: str | None = None) -> None:
        if not token:
            raise PublishError("GITHUB_TOKEN is required")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise PublishError("repository must use owner/name format")
        self.repository = repository
        self.token = token
        self.api_url = (api_url or "https://api.github.com").rstrip("/")

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None = None,
        expected: tuple[int, ...] = (200,),
        allow_not_found: bool = False,
    ) -> tuple[int, dict[str, object] | list[object] | None]:
        body = json.dumps(payload).encode("utf-8") if payload is not None else None
        request = Request(
            f"{self.api_url}{path}",
            data=body,
            method=method,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
                "User-Agent": "lizzieyzy-next-release-publisher",
                "X-GitHub-Api-Version": API_VERSION,
            },
        )
        try:
            with urlopen(request, timeout=60) as response:
                status = response.status
                raw = response.read()
        except HTTPError as exc:
            if allow_not_found and exc.code == 404:
                return 404, None
            detail = exc.read().decode("utf-8", errors="replace")[:2000]
            raise PublishError(f"GitHub API {method} {path} failed ({exc.code}): {detail}") from exc
        except OSError as exc:
            raise PublishError(f"GitHub API {method} {path} failed: {exc}") from exc

        if status not in expected:
            raise PublishError(
                f"GitHub API {method} {path} returned {status}; expected {expected}"
            )
        if not raw:
            return status, None
        try:
            return status, json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError as exc:
            raise PublishError(f"GitHub API {method} {path} returned invalid JSON") from exc

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
        )

    def get_release_by_tag(self, tag: str) -> dict[str, object] | None:
        path = f"/repos/{self.repository}/releases/tags/{quote(tag, safe='')}"
        _status, payload = self._request("GET", path, allow_not_found=True)
        if payload is not None:
            assert isinstance(payload, dict)
            return payload
        return None

    def list_releases(self) -> list[dict[str, object]]:
        _status, payload = self._request(
            "GET", f"/repos/{self.repository}/releases?per_page=100"
        )
        assert isinstance(payload, list)
        return [release for release in payload if isinstance(release, dict)]

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

    def list_release_assets(self, release_id: int) -> list[str]:
        _status, payload = self._request(
            "GET",
            f"/repos/{self.repository}/releases/{release_id}/assets?per_page=100",
        )
        assert isinstance(payload, list)
        return [str(asset["name"]) for asset in payload if isinstance(asset, dict)]

    def list_workflow_runs(self, workflow_file: str, tag: str) -> list[dict[str, object]]:
        workflow = quote(workflow_file, safe="")
        query = urlencode({"event": "workflow_dispatch", "branch": tag, "per_page": 50})
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


class ReleasePublisher:
    def __init__(
        self,
        client: GitHubClient,
        request: ReleaseRequest,
        target_sha: str,
        release_notes: str,
        sleep: Callable[[float], None] = time.sleep,
        poll_seconds: float = 30,
        run_timeout_seconds: float = 5 * 60 * 60 + 30 * 60,
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
        self.run_urls: dict[str, str] = {}
        if not self._notes_text_complete(release_notes):
            raise PublishError("Reviewed release notes are missing the tag or a language section")
        if "{{" in release_notes or "}}" in release_notes:
            raise PublishError("Reviewed release notes contain an unresolved template token")

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

    def _restore_orphaned_release(
        self, release: dict[str, object]
    ) -> dict[str, object]:
        release_id = int(release["id"])
        restored = self.client.update_release(
            release_id,
            {
                "tag_name": self.request.release_tag,
                "target_commitish": self.target_sha,
                "name": self.request.title,
                "draft": release.get("draft") is True,
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

    def _matching_active_run(self, workflow_file: str) -> dict[str, object] | None:
        for run in self.client.list_workflow_runs(workflow_file, self.request.release_tag):
            if run.get("head_sha") != self.target_sha:
                continue
            if str(run.get("status")) in ACTIVE_RUN_STATUSES:
                return run
        return None

    def _discover_new_run(
        self, workflow_file: str, previous_ids: set[int], timeout_seconds: float = 180
    ) -> int:
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            for run in self.client.list_workflow_runs(workflow_file, self.request.release_tag):
                run_id = int(run["id"])
                if run_id not in previous_ids and run.get("head_sha") == self.target_sha:
                    return run_id
            self.sleep(min(self.poll_seconds, 10))
        raise PublishError(f"Timed out locating dispatched workflow run for {workflow_file}")

    def _dispatch(self, spec: WorkflowSpec) -> int:
        workflow_file = spec.workflow_file
        existing = self.client.list_workflow_runs(workflow_file, self.request.release_tag)
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
            run_id = self._discover_new_run(workflow_file, previous_ids)
        print(f"Dispatched {workflow_file}: run {run_id}", flush=True)
        return run_id

    def _wait_for_runs(self, runs: dict[str, int]) -> None:
        if not runs:
            return
        deadline = time.monotonic() + self.run_timeout_seconds
        pending = dict(runs)
        last_state: dict[int, tuple[str, object]] = {}
        while pending:
            if time.monotonic() >= deadline:
                names = ", ".join(sorted(pending))
                raise PublishError(f"Timed out waiting for workflows: {names}")
            for name, run_id in list(pending.items()):
                run = self.client.get_workflow_run(run_id)
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
                self.sleep(self.poll_seconds)

    def _verify_platform_assets(self, release_id: int) -> list[str]:
        missing: list[str] = []
        for attempt in range(7):
            assets = self.client.list_release_assets(release_id)
            missing = []
            for spec in WORKFLOWS:
                missing.extend(spec.missing_assets(assets, self.request.date_tag))
            if "lizzieyzy-next-update-manifest.json" not in assets:
                missing.append("lizzieyzy-next-update-manifest.json")
            if not missing:
                return assets
            if attempt < 6:
                self.sleep(min(self.poll_seconds, 10))
        raise PublishError("Release is missing assets: " + ", ".join(missing))

    def _notes_complete(self, release: dict[str, object]) -> bool:
        return self._notes_text_complete(str(release.get("body") or ""))

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
        self._ensure_tag()
        release, already_published = self._ensure_draft_release()
        release_id = int(release["id"])
        if already_published:
            assets = self._verify_platform_assets(release_id)
            if not self._notes_complete(release):
                raise PublishError("Published pre-release does not contain all six language sections")
            release = self._verify_public_release_identity(release_id)
            self._cleanup_detached_tag_aliases()
            print(f"{self.request.release_tag} is already complete", flush=True)
            self._publish_summary(release, assets)
            return release

        current_assets = self.client.list_release_assets(release_id)
        runs: dict[str, int] = {}
        for spec in WORKFLOWS:
            missing = spec.missing_assets(current_assets, self.request.date_tag)
            if not missing and (
                spec.platform != "Windows" or "lizzieyzy-next-update-manifest.json" in current_assets
            ):
                print(f"{spec.platform}: required assets already present", flush=True)
                continue
            active = self._matching_active_run(spec.workflow_file)
            if active is not None:
                run_id = int(active["id"])
                print(f"{spec.platform}: waiting for existing run {run_id}", flush=True)
            else:
                run_id = self._dispatch(spec)
            runs[spec.platform] = run_id

        self._wait_for_runs(runs)
        assets = self._verify_platform_assets(release_id)

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
        if not self._notes_complete(release):
            raise PublishError("GitHub did not retain the reviewed six-language release notes")

        release = self.client.update_release(
            release_id,
            {
                "tag_name": self.request.release_tag,
                "target_commitish": self.target_sha,
                "name": self.request.title,
                "draft": False,
                "prerelease": True,
                "make_latest": "false",
            },
        )
        self._assert_release_identity(release)
        if release.get("draft") is not False or release.get("prerelease") is not True:
            raise PublishError("GitHub did not publish the release as a pre-release")
        release = self._verify_public_release_identity(release_id)
        self._cleanup_detached_tag_aliases()
        assets = self._verify_platform_assets(release_id)
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
