#!/usr/bin/env python3
"""Regression tests for the audited pre-release publisher."""

from __future__ import annotations

import importlib.util
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock
import zipfile

from scripts import release_asset_provenance as provenance


SCRIPT_PATH = Path(__file__).with_name("publish_release_request.py")
SPEC = importlib.util.spec_from_file_location("publish_release_request", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
PUBLISH = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = PUBLISH
SPEC.loader.exec_module(PUBLISH)


DATE_TAG = "2026-07-22"
RELEASE_TAG = f"next-{DATE_TAG}.1"
TARGET_SHA = "a" * 40


def request_payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "date_tag": DATE_TAG,
        "release_tag": RELEASE_TAG,
        "title": f"LizzieYzy Next {RELEASE_TAG}",
        "prerelease": True,
        "notes_file": f".github/release-notes/{RELEASE_TAG}.md",
    }
    payload.update(overrides)
    return payload


def all_asset_names() -> list[str]:
    names: list[str] = []
    for spec in PUBLISH.WORKFLOWS:
        names.extend(f"{DATE_TAG}-{suffix}" for suffix in spec.exact_suffixes)
    names.append("lizzieyzy-next-update-manifest.json")
    return names


def fake_asset_bytes(name: str) -> bytes:
    return f"verified-release-asset:{name}".encode("utf-8")


def fake_asset_metadata(name: str) -> dict[str, object]:
    content = fake_asset_bytes(name)
    return {
        "id": abs(hash(name)) + 1,
        "name": name,
        "state": "uploaded",
        "size": len(content),
        "digest": "sha256:" + hashlib.sha256(content).hexdigest(),
    }


class FakeClient:
    repository = "wimi321/lizzieyzy-next"

    def __init__(
        self,
        failed_workflow: str | None = None,
        detach_on_publish: bool = False,
        hide_public_by_tag: bool = False,
    ) -> None:
        self.tag_sha: str | None = None
        self.release: dict[str, object] | None = None
        self.assets: list[str | dict[str, object]] = []
        self.runs: dict[int, dict[str, object]] = {}
        self.workflow_runs: dict[str, list[dict[str, object]]] = {}
        self.next_run_id = 100
        self.failed_workflow = failed_workflow
        self.detach_on_publish = detach_on_publish
        self.hide_public_by_tag = hide_public_by_tag
        self.dispatched: list[str] = []
        self.dispatched_inputs: dict[str, dict[str, str]] = {}
        self.update_payloads: list[dict[str, object]] = []
        self.create_tag_calls = 0
        self.create_draft_release_calls = 0
        self.detached_tag_aliases: dict[str, str] = {}
        self.deleted_tags: list[str] = []
        self.protected_release_tags: set[str] = set()
        self.run_workflows: dict[int, str] = {}
        self.artifact_archives: dict[int, bytes] = {}
        self.artifact_metadata_overrides: dict[int, dict[str, object]] = {}
        self.provenance_payload_overrides: dict[int, dict[str, object]] = {}
        self.ci_run_snapshots: list[list[dict[str, object]]] = [
            [
                {
                    "id": 50,
                    "head_sha": TARGET_SHA,
                    "status": "completed",
                    "conclusion": "success",
                    "html_url": "https://example.invalid/runs/50",
                }
            ]
        ]
        self.ci_list_calls = 0

    def get_tag_sha(self, _tag: str) -> str | None:
        return self.tag_sha

    def create_tag(self, _tag: str, target_sha: str) -> None:
        self.create_tag_calls += 1
        self.tag_sha = target_sha

    def get_release_by_tag(self, tag: str) -> dict[str, object] | None:
        if tag in self.protected_release_tags:
            return {
                "id": 8,
                "tag_name": tag,
                "target_commitish": TARGET_SHA,
                "draft": False,
                "prerelease": True,
            }
        if self.release is None or self.release.get("tag_name") != tag:
            return None
        if self.hide_public_by_tag and self.release.get("draft") is False:
            return None
        return dict(self.release)

    def get_release(self, tag: str) -> dict[str, object] | None:
        return self.get_release_by_tag(tag)

    def find_orphaned_release(
        self, title: str, target_sha: str
    ) -> dict[str, object] | None:
        if self.release is None:
            return None
        if (
            str(self.release.get("tag_name") or "").startswith("untagged-")
            and self.release.get("name") == title
            and self.release.get("target_commitish") == target_sha
            and self.release.get("prerelease") is True
        ):
            return dict(self.release)
        return None

    def list_detached_tag_aliases(self, target_sha: str) -> list[str]:
        return [
            tag for tag, sha in self.detached_tag_aliases.items() if sha == target_sha
        ]

    def delete_tag(self, tag: str) -> None:
        self.detached_tag_aliases.pop(tag)
        self.deleted_tags.append(tag)

    def create_draft_release(
        self, request: PUBLISH.ReleaseRequest, _target_sha: str
    ) -> dict[str, object]:
        self.create_draft_release_calls += 1
        self.release = {
            "id": 7,
            "tag_name": request.release_tag,
            "target_commitish": _target_sha,
            "name": request.title,
            "body": "building",
            "draft": True,
            "prerelease": True,
            "html_url": "https://example.invalid/draft",
        }
        return dict(self.release)

    def update_release(
        self, _release_id: int, payload: dict[str, object]
    ) -> dict[str, object]:
        assert self.release is not None
        self.update_payloads.append(dict(payload))
        self.release.update(payload)
        if payload.get("draft") is False and self.detach_on_publish:
            self.release["tag_name"] = "untagged-detached-release"
        self.release["html_url"] = "https://example.invalid/release"
        return dict(self.release)

    def list_release_assets(self, _release_id: int) -> list[dict[str, object]]:
        return [
            dict(item) if isinstance(item, dict) else fake_asset_metadata(item)
            for item in self.assets
        ]

    def list_workflow_runs(
        self, workflow_file: str, _target_sha: str
    ) -> list[dict[str, object]]:
        return list(self.workflow_runs.get(workflow_file, []))

    def list_ci_runs(self, _target_sha: str) -> list[dict[str, object]]:
        self.ci_list_calls += 1
        if len(self.ci_run_snapshots) > 1:
            return [dict(run) for run in self.ci_run_snapshots.pop(0)]
        return [dict(run) for run in self.ci_run_snapshots[0]]

    def dispatch_workflow(
        self, workflow_file: str, _tag: str, inputs: dict[str, str]
    ) -> int:
        conclusion = "failure" if workflow_file == self.failed_workflow else "success"
        run_id = self.seed_workflow_run(
            workflow_file,
            conclusion=conclusion,
            display_title=self.expected_run_name(workflow_file, inputs),
        )
        self.dispatched.append(workflow_file)
        self.dispatched_inputs[workflow_file] = dict(inputs)

        if conclusion == "success":
            for spec in PUBLISH.WORKFLOWS:
                if spec.workflow_file == workflow_file:
                    names = [f"{DATE_TAG}-{suffix}" for suffix in spec.exact_suffixes]
                    if spec.platform == "Windows":
                        names.append("lizzieyzy-next-update-manifest.json")
                    replace_names = set(names)
                    self.assets = [
                        item
                        for item in self.assets
                        if (
                            item.get("name") if isinstance(item, dict) else item
                        )
                        not in replace_names
                    ]
                    self.assets.extend(names)
        return run_id

    def expected_run_name(
        self, workflow_file: str, inputs: dict[str, str] | None = None
    ) -> str:
        spec = next(
            item for item in PUBLISH.WORKFLOWS if item.workflow_file == workflow_file
        )
        values = dict(spec.dispatch_inputs)
        if inputs is not None:
            values.update(inputs)
        return spec.expected_run_name(
            values.get("date_tag", DATE_TAG),
            values.get("release_tag", RELEASE_TAG),
            values.get("release_prerelease", "true"),
        )

    def seed_workflow_run(
        self,
        workflow_file: str,
        *,
        conclusion: str = "success",
        head_sha: str = TARGET_SHA,
        status: str = "completed",
        display_title: str | None = None,
    ) -> int:
        self.next_run_id += 1
        run_id = self.next_run_id
        run = {
            "id": run_id,
            "head_sha": head_sha,
            "status": status,
            "conclusion": conclusion if status == "completed" else None,
            "run_attempt": 1,
            "display_title": display_title or self.expected_run_name(workflow_file),
            "html_url": f"https://example.invalid/runs/{run_id}",
        }
        self.runs[run_id] = run
        self.run_workflows[run_id] = workflow_file
        self.workflow_runs.setdefault(workflow_file, []).insert(0, run)
        return run_id

    def get_workflow_run(self, run_id: int) -> dict[str, object]:
        return dict(self.runs[run_id])

    def provenance_payload(self, run_id: int) -> dict[str, object]:
        if run_id in self.provenance_payload_overrides:
            return json.loads(json.dumps(self.provenance_payload_overrides[run_id]))
        workflow_file = self.run_workflows[run_id]
        spec = next(
            item for item in PUBLISH.WORKFLOWS if item.workflow_file == workflow_file
        )
        run = self.runs[run_id]
        names = provenance.expected_asset_names(spec.provenance_platform, DATE_TAG)
        return {
            "schemaVersion": provenance.SCHEMA_VERSION,
            "platform": spec.provenance_platform,
            "dateTag": DATE_TAG,
            "releaseTag": RELEASE_TAG,
            "targetSha": TARGET_SHA,
            "workflowRunId": run_id,
            "workflowRunAttempt": run["run_attempt"],
            "assets": [
                {
                    "name": name,
                    "sizeBytes": len(fake_asset_bytes(name)),
                    "sha256": hashlib.sha256(fake_asset_bytes(name)).hexdigest(),
                }
                for name in names
            ],
        }

    def provenance_archive(self, run_id: int) -> bytes:
        if run_id not in self.artifact_archives:
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as bundle:
                bundle.writestr(
                    provenance.PROVENANCE_FILENAME,
                    json.dumps(self.provenance_payload(run_id), sort_keys=True),
                )
            self.artifact_archives[run_id] = buffer.getvalue()
        return self.artifact_archives[run_id]

    def list_run_artifacts(self, run_id: int) -> list[dict[str, object]]:
        run = self.runs[run_id]
        spec = next(
            item
            for item in PUBLISH.WORKFLOWS
            if item.workflow_file == self.run_workflows[run_id]
        )
        archive = self.provenance_archive(run_id)
        artifact: dict[str, object] = {
            "id": 1000 + run_id,
            "name": provenance.artifact_name(
                spec.provenance_platform, int(run["run_attempt"])
            ),
            "size_in_bytes": len(archive),
            "expired": False,
            "digest": "sha256:" + hashlib.sha256(archive).hexdigest(),
            "workflow_run": {"id": run_id, "head_sha": run["head_sha"]},
        }
        artifact.update(self.artifact_metadata_overrides.get(run_id, {}))
        return [artifact]

    def download_artifact_zip(self, artifact_id: int) -> bytes:
        return self.provenance_archive(artifact_id - 1000)


class ReleaseRequestTest(unittest.TestCase):
    def load(self, payload: dict[str, object]) -> PUBLISH.ReleaseRequest:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "request.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            return PUBLISH.ReleaseRequest.load(path)

    def test_loads_valid_pre_release_request(self) -> None:
        request = self.load(request_payload())

        self.assertEqual(DATE_TAG, request.date_tag)
        self.assertEqual(RELEASE_TAG, request.release_tag)
        self.assertTrue(request.prerelease)

    def test_rejects_mismatched_date(self) -> None:
        with self.assertRaisesRegex(PUBLISH.PublishError, "date_tag must match"):
            self.load(request_payload(date_tag="2026-07-21"))

    def test_rejects_non_pre_release(self) -> None:
        with self.assertRaisesRegex(PUBLISH.PublishError, "prerelease"):
            self.load(request_payload(prerelease=False))

    def test_rejects_unreviewable_title(self) -> None:
        with self.assertRaisesRegex(PUBLISH.PublishError, "title must be exactly"):
            self.load(request_payload(title="Surprise release"))


class GitHubClientTagAliasTest(unittest.TestCase):
    class Response:
        status = 200

        def __init__(self, payload: bytes) -> None:
            self.payload = payload

        def __enter__(self) -> "GitHubClientTagAliasTest.Response":
            return self

        def __exit__(self, *_args: object) -> None:
            return None

        def read(self, _limit: int | None = None) -> bytes:
            return self.payload

    def test_only_returns_synthetic_lightweight_aliases_for_the_release_target(self) -> None:
        client = PUBLISH.GitHubClient("owner/repository", "test-token")
        client._request = lambda *_args, **_kwargs: (  # type: ignore[method-assign]
            200,
            [
                {
                    "ref": "refs/tags/untagged-0123456789abcdefabcd",
                    "object": {"type": "commit", "sha": TARGET_SHA},
                },
                {
                    "ref": "refs/tags/untagged-human-label",
                    "object": {"type": "commit", "sha": TARGET_SHA},
                },
                {
                    "ref": "refs/tags/untagged-fedcba9876543210abcd",
                    "object": {"type": "commit", "sha": "b" * 40},
                },
                {
                    "ref": "refs/tags/untagged-abcdef0123456789abcd",
                    "object": {"type": "tag", "sha": TARGET_SHA},
                },
            ],
        )

        self.assertEqual(
            ["untagged-0123456789abcdefabcd"],
            client.list_detached_tag_aliases(TARGET_SHA),
        )

    def test_ci_lookup_is_scoped_to_push_event_and_exact_head_sha(self) -> None:
        client = PUBLISH.GitHubClient("owner/repository", "test-token")
        requested_paths: list[str] = []

        def request(method: str, path: str, **_kwargs: object) -> tuple[int, object]:
            self.assertEqual("GET", method)
            requested_paths.append(path)
            return 200, {"total_count": 0, "workflow_runs": []}

        client._request = request  # type: ignore[method-assign]

        self.assertEqual([], client.list_ci_runs(TARGET_SHA))
        self.assertEqual(1, len(requested_paths))
        self.assertIn("/actions/workflows/ci.yml/runs?", requested_paths[0])
        self.assertIn("event=push", requested_paths[0])
        self.assertIn(f"head_sha={TARGET_SHA}", requested_paths[0])

    def test_platform_lookup_uses_exact_sha_not_branch_filter(self) -> None:
        client = PUBLISH.GitHubClient("owner/repository", "test-token")
        requested_paths: list[str] = []

        def request(method: str, path: str, **_kwargs: object) -> tuple[int, object]:
            self.assertEqual("GET", method)
            requested_paths.append(path)
            return 200, {"total_count": 0, "workflow_runs": []}

        client._request = request  # type: ignore[method-assign]

        self.assertEqual(
            [], client.list_workflow_runs("build-windows-release.yml", TARGET_SHA)
        )
        self.assertEqual(1, len(requested_paths))
        self.assertIn("event=workflow_dispatch", requested_paths[0])
        self.assertIn(f"head_sha={TARGET_SHA}", requested_paths[0])
        self.assertNotIn("branch=", requested_paths[0])

    def test_platform_lookup_reads_every_page(self) -> None:
        client = PUBLISH.GitHubClient("owner/repository", "test-token")
        requested_paths: list[str] = []
        pages: list[dict[str, object]] = [
            {
                "total_count": 101,
                "workflow_runs": [{"id": index} for index in range(1, 101)],
            },
            {"total_count": 101, "workflow_runs": [{"id": 101}]},
        ]

        def request(method: str, path: str, **_kwargs: object) -> tuple[int, object]:
            self.assertEqual("GET", method)
            requested_paths.append(path)
            return 200, pages.pop(0)

        client._request = request  # type: ignore[method-assign]

        runs = client.list_workflow_runs(
            "build-windows-release.yml", TARGET_SHA
        )

        self.assertEqual(101, len(runs))
        self.assertEqual(2, len(requested_paths))
        self.assertIn("per_page=100", requested_paths[0])
        self.assertIn("page=1", requested_paths[0])
        self.assertIn("page=2", requested_paths[1])

    def test_platform_lookup_fails_closed_if_pagination_changes(self) -> None:
        client = PUBLISH.GitHubClient("owner/repository", "test-token")
        pages: list[dict[str, object]] = [
            {
                "total_count": 101,
                "workflow_runs": [{"id": index} for index in range(1, 101)],
            },
            {"total_count": 102, "workflow_runs": [{"id": 101}, {"id": 102}]},
        ]
        client._request = (  # type: ignore[method-assign]
            lambda *_args, **_kwargs: (200, pages.pop(0))
        )

        with self.assertRaisesRegex(PUBLISH.PublishError, "changed during pagination"):
            client.list_workflow_runs("build-windows-release.yml", TARGET_SHA)

    def test_transient_api_failures_retry_for_429_5xx_and_rate_limit(self) -> None:
        for status, headers in (
            (429, {"Retry-After": "0"}),
            (503, {}),
            (403, {"X-RateLimit-Remaining": "0", "Retry-After": "0"}),
        ):
            with self.subTest(status=status):
                delays: list[float] = []
                client = PUBLISH.GitHubClient(
                    "owner/repository",
                    "test-token",
                    sleep=delays.append,
                    retry_attempts=2,
                )
                failure = PUBLISH.HTTPError(
                    "https://api.github.test/example",
                    status,
                    "transient",
                    headers,
                    io.BytesIO(b"try again"),
                )
                response = self.Response(b'{"ok": true}')
                with mock.patch.object(
                    PUBLISH, "urlopen", side_effect=[failure, response]
                ) as request:
                    _status, payload = client._request("GET", "/example")
                self.assertEqual({"ok": True}, payload)
                self.assertEqual(2, request.call_count)
                self.assertEqual(1, len(delays))

    def test_artifact_download_uses_json_api_accept_and_strips_token_on_redirect(
        self,
    ) -> None:
        token = "dummy-artifact-token"
        first_hop_authorization: list[str | None] = []
        first_hop_accept: list[str | None] = []
        first_hop_path: list[str] = []
        second_hop_authorization: list[str | None] = []

        class ArchiveHandler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802 - stdlib callback name
                second_hop_authorization.append(self.headers.get("Authorization"))
                payload = b"provenance archive"
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *_args: object) -> None:
                return

        archive_server = ThreadingHTTPServer(("127.0.0.1", 0), ArchiveHandler)
        redirect_server: ThreadingHTTPServer | None = None
        archive_thread: threading.Thread | None = None
        redirect_thread: threading.Thread | None = None

        def stop_server(
            server: ThreadingHTTPServer | None,
            thread: threading.Thread | None,
            label: str,
        ) -> None:
            if server is None:
                return
            try:
                if thread is not None and thread.is_alive():
                    server.shutdown()
            finally:
                server.server_close()
            if thread is not None:
                thread.join(timeout=5)
                self.assertFalse(
                    thread.is_alive(), f"{label} test server thread did not stop"
                )

        try:
            class RedirectHandler(BaseHTTPRequestHandler):
                def do_GET(self) -> None:  # noqa: N802 - stdlib callback name
                    first_hop_authorization.append(
                        self.headers.get("Authorization")
                    )
                    first_hop_accept.append(self.headers.get("Accept"))
                    first_hop_path.append(self.path)
                    if self.headers.get("Accept") != "application/vnd.github+json":
                        payload = json.dumps(
                            {
                                "message": (
                                    "Unsupported 'Accept' header. Must accept "
                                    "'application/json'."
                                )
                            }
                        ).encode("utf-8")
                        self.send_response(415)
                        self.send_header("Content-Type", "application/json")
                        self.send_header("Content-Length", str(len(payload)))
                        self.end_headers()
                        self.wfile.write(payload)
                        return
                    self.send_response(302)
                    self.send_header(
                        "Location",
                        f"http://127.0.0.1:{archive_server.server_port}/archive.zip",
                    )
                    self.end_headers()

                def log_message(self, *_args: object) -> None:
                    return

            redirect_server = ThreadingHTTPServer(
                ("127.0.0.1", 0), RedirectHandler
            )
            archive_thread = threading.Thread(
                target=archive_server.serve_forever, daemon=True
            )
            redirect_thread = threading.Thread(
                target=redirect_server.serve_forever, daemon=True
            )
            archive_thread.start()
            redirect_thread.start()
            client = PUBLISH.GitHubClient(
                "owner/repository",
                token,
                api_url=f"http://127.0.0.1:{redirect_server.server_port}",
            )

            diagnostics = io.StringIO()
            with mock.patch.object(PUBLISH.sys, "stderr", diagnostics):
                payload = client.download_artifact_zip(123)

            self.assertEqual(b"provenance archive", payload)
            self.assertEqual(
                ["/repos/owner/repository/actions/artifacts/123/zip"],
                first_hop_path,
            )
            self.assertEqual(["application/vnd.github+json"], first_hop_accept)
            self.assertTrue(
                len(first_hop_authorization) == 1
                and first_hop_authorization[0] == f"Bearer {token}",
                "The API first hop did not receive the expected bearer authorization",
            )
            self.assertTrue(
                second_hop_authorization == [None],
                "The redirected artifact hop received repository authorization",
            )
            self.assertFalse(
                token in diagnostics.getvalue(),
                "GitHub API diagnostics exposed the bearer token",
            )
        finally:
            try:
                stop_server(redirect_server, redirect_thread, "Redirect")
            finally:
                stop_server(archive_server, archive_thread, "Artifact")

    def test_workflow_dispatch_does_not_retry_an_ambiguous_failure(self) -> None:
        delays: list[float] = []
        client = PUBLISH.GitHubClient(
            "owner/repository",
            "test-token",
            sleep=delays.append,
            retry_attempts=3,
        )
        with mock.patch.object(
            PUBLISH,
            "urlopen",
            side_effect=[OSError("connection reset"), self.Response(b"")],
        ) as request:
            with self.assertRaisesRegex(PUBLISH.PublishError, "connection reset"):
                client.dispatch_workflow("build-windows-release.yml", RELEASE_TAG, {})

        self.assertEqual(1, request.call_count)
        self.assertEqual([], delays)

    def test_non_idempotent_creation_posts_do_not_retry_ambiguous_failures(self) -> None:
        release_request = PUBLISH.ReleaseRequest(
            DATE_TAG,
            RELEASE_TAG,
            f"LizzieYzy Next {RELEASE_TAG}",
            True,
            f".github/release-notes/{RELEASE_TAG}.md",
        )
        for operation_name in ("tag", "draft release"):
            with self.subTest(operation=operation_name):
                delays: list[float] = []
                client = PUBLISH.GitHubClient(
                    "owner/repository",
                    "test-token",
                    sleep=delays.append,
                    retry_attempts=3,
                )
                with mock.patch.object(
                    PUBLISH,
                    "urlopen",
                    side_effect=[
                        OSError("response lost"),
                        self.Response(b'{"id": 7}'),
                    ],
                ) as request:
                    with self.assertRaisesRegex(PUBLISH.PublishError, "response lost"):
                        if operation_name == "tag":
                            client.create_tag(RELEASE_TAG, TARGET_SHA)
                        else:
                            client.create_draft_release(release_request, TARGET_SHA)

                self.assertEqual(1, request.call_count)
                self.assertEqual([], delays)

    def test_release_patch_keeps_transient_retry(self) -> None:
        delays: list[float] = []
        client = PUBLISH.GitHubClient(
            "owner/repository",
            "test-token",
            sleep=delays.append,
            retry_attempts=2,
        )
        with mock.patch.object(
            PUBLISH,
            "urlopen",
            side_effect=[OSError("response lost"), self.Response(b'{"id": 7}')],
        ) as request:
            release = client.update_release(7, {"draft": False})

        self.assertEqual({"id": 7}, release)
        self.assertEqual(2, request.call_count)
        self.assertEqual(1, len(delays))

    def test_delete_tag_retries_lost_response_and_accepts_missing_alias(self) -> None:
        delays: list[float] = []
        alias_present = True
        calls = 0

        def delete_then_report_missing(
            _request: object, *, timeout: int
        ) -> object:
            nonlocal alias_present, calls
            self.assertEqual(60, timeout)
            calls += 1
            if calls == 1:
                alias_present = False
                raise OSError("delete response lost")
            raise PUBLISH.HTTPError(
                "https://api.github.test/tag",
                404,
                "not found",
                {},
                io.BytesIO(b'{"message":"Not Found"}'),
            )

        client = PUBLISH.GitHubClient(
            "owner/repository",
            "test-token",
            sleep=delays.append,
            retry_attempts=2,
        )
        diagnostics = io.StringIO()
        with mock.patch.object(PUBLISH, "urlopen", side_effect=delete_then_report_missing):
            with mock.patch.object(PUBLISH.sys, "stderr", diagnostics):
                client.delete_tag("untagged-0123456789abcdefabcd")

        self.assertFalse(alias_present)
        self.assertEqual(2, calls)
        self.assertEqual(1, len(delays))

    def test_delete_tag_still_rejects_non_not_found_errors(self) -> None:
        client = PUBLISH.GitHubClient(
            "owner/repository", "test-token", retry_attempts=3
        )
        failure = PUBLISH.HTTPError(
            "https://api.github.test/tag",
            422,
            "unprocessable",
            {},
            io.BytesIO(b'{"message":"cannot delete"}'),
        )
        with mock.patch.object(PUBLISH, "urlopen", side_effect=failure) as request:
            with self.assertRaisesRegex(PUBLISH.PublishError, "422"):
                client.delete_tag("untagged-0123456789abcdefabcd")

        self.assertEqual(1, request.call_count)

    def test_release_asset_listing_reads_every_page(self) -> None:
        client = PUBLISH.GitHubClient("owner/repository", "test-token")
        requested_paths: list[str] = []
        pages: list[list[dict[str, object]]] = [
            [{"name": f"asset-{index}"} for index in range(100)],
            [{"name": "asset-100"}],
        ]

        def request(method: str, path: str, **_kwargs: object) -> tuple[int, object]:
            self.assertEqual("GET", method)
            requested_paths.append(path)
            return 200, pages.pop(0)

        client._request = request  # type: ignore[method-assign]

        self.assertEqual(101, len(client.list_release_assets(7)))
        self.assertEqual(2, len(requested_paths))
        self.assertIn("page=1", requested_paths[0])
        self.assertIn("page=2", requested_paths[1])


class WorkflowSpecTest(unittest.TestCase):
    def test_complete_asset_set_satisfies_every_platform(self) -> None:
        assets = all_asset_names()

        for spec in PUBLISH.WORKFLOWS:
            self.assertEqual([], spec.missing_assets(assets, DATE_TAG), spec.platform)

    def test_windows_requires_both_fixed_tensorrt_volumes(self) -> None:
        for suffix in (".001", ".002"):
            with self.subTest(suffix=suffix):
                assets = [
                    name
                    for name in all_asset_names()
                    if not name.endswith(f"portable.7z{suffix}")
                ]

                missing = PUBLISH.WORKFLOWS[0].missing_assets(assets, DATE_TAG)

                self.assertEqual(1, len(missing))
                self.assertTrue(missing[0].endswith(suffix))


class ReviewedReleaseNotesTest(unittest.TestCase):
    def test_notes_are_complete_localized_and_link_to_all_platforms(self) -> None:
        path = SCRIPT_PATH.parents[1] / ".github" / "release-notes" / f"{RELEASE_TAG}.md"
        notes = path.read_text(encoding="utf-8")

        for heading in PUBLISH.LOCALIZED_NOTE_HEADINGS:
            self.assertEqual(1, notes.count(heading), heading)
        for marker in (
            "windows64.opencl.portable.zip",
            "windows64.nvidia50.cuda.portable.zip",
            "windows64.nvidia.tensorrt.portable.README.txt",
            "mac-apple-silicon.with-katago.dmg",
            "mac-intel.with-katago.dmg",
            "linux64.with-katago.zip",
            "linux64.opencl.zip",
            "linux64.nvidia.zip",
        ):
            self.assertIn(marker, notes)
        self.assertIn(RELEASE_TAG, notes)
        self.assertIn("PR #127–#133", notes)
        self.assertIn("1484", notes)
        self.assertNotIn("知子", notes)
        self.assertNotIn("{{", notes)
        self.assertNotIn("}}", notes)


class ReleaseWorkflowResilienceTest(unittest.TestCase):
    def test_release_retry_uses_the_existing_requested_tag_target(self) -> None:
        workflow = (
            SCRIPT_PATH.parents[1]
            / ".github"
            / "workflows"
            / "publish-requested-pre-release.yml"
        ).read_text(encoding="utf-8")

        self.assertIn('git rev-parse "refs/tags/${release_tag}^{commit}"', workflow)
        self.assertIn(
            'git show-ref --verify --quiet "refs/tags/$release_tag"', workflow
        )
        self.assertIn('target_sha="$TARGET_SHA"', workflow)
        self.assertIn("Manual recovery requires an existing release tag", workflow)
        self.assertIn('echo "target_sha=$target_sha" >> "$GITHUB_OUTPUT"', workflow)
        self.assertIn('echo "request_sha256=$request_sha256" >> "$GITHUB_OUTPUT"', workflow)
        self.assertIn('echo "release_tag=$release_tag" >> "$GITHUB_OUTPUT"', workflow)
        self.assertIn("- name: Checkout exact target SHA", workflow)
        self.assertIn("ref: ${{ steps.request.outputs.target_sha }}", workflow)
        self.assertIn('test "$(git rev-parse HEAD)" = "$RELEASE_TARGET_SHA"', workflow)
        self.assertIn(
            'if [[ "$actual_request_sha256" != "$EXPECTED_REQUEST_SHA256" ]]',
            workflow,
        )
        self.assertIn(
            'if [[ "$actual_release_tag" != "$EXPECTED_RELEASE_TAG" ]]',
            workflow,
        )
        self.assertIn(
            "RELEASE_TARGET_SHA: ${{ steps.request.outputs.target_sha }}", workflow
        )
        self.assertIn('--target-sha "$RELEASE_TARGET_SHA"', workflow)
        self.assertNotIn('--target-sha "${{ github.sha }}"', workflow)

    def test_publisher_step_env_isolates_untrusted_step_outputs(self) -> None:
        workflow = (
            SCRIPT_PATH.parents[1]
            / ".github"
            / "workflows"
            / "publish-requested-pre-release.yml"
        ).read_text(encoding="utf-8")
        publish_step = workflow[workflow.index("Build, verify, and publish the pre-release"):]
        self.assertIn(
            "RELEASE_REQUEST_FILE: ${{ steps.request.outputs.request_file }}",
            publish_step,
        )
        self.assertIn(
            "RELEASE_TARGET_SHA: ${{ steps.request.outputs.target_sha }}",
            publish_step,
        )
        self.assertIn('RELEASE_REPOSITORY: ${{ github.repository }}', publish_step)
        run_body = publish_step[publish_step.index("run: |") :]
        self.assertNotIn("${{ steps.", run_body)
        self.assertNotIn("${{ github.repository }}", run_body)
        self.assertIn('--request "$RELEASE_REQUEST_FILE"', run_body)
        self.assertIn('--target-sha "$RELEASE_TARGET_SHA"', run_body)
        self.assertIn(
            r'^\.github/release-requests/[A-Za-z0-9._-]+\.json$', workflow
        )

    def test_windows_draft_release_metadata_is_explicit_and_fail_closed(self) -> None:
        workflow = (
            SCRIPT_PATH.parents[1] / ".github" / "workflows" / "build-windows-release.yml"
        ).read_text(encoding="utf-8")

        self.assertIn("release_prerelease:", workflow)
        self.assertIn("required: true", workflow)
        self.assertIn("validate_release_workflow_identity.py", workflow)
        self.assertNotIn("default: 2026-", workflow)
        self.assertNotIn("gh api --paginate", workflow)
        self.assertIn('LIZZIE_RELEASE_PRERELEASE="$release_prerelease"', workflow)

    def test_every_platform_serializes_by_workflow_and_release_tag(self) -> None:
        for workflow_name in (
            "build-windows-release.yml",
            "build-linux-release.yml",
            "build-macos-amd64-release.yml",
            "build-macos-arm64-release.yml",
        ):
            with self.subTest(workflow=workflow_name):
                workflow = (
                    SCRIPT_PATH.parents[1] / ".github" / "workflows" / workflow_name
                ).read_text(encoding="utf-8")
                self.assertIn("concurrency:", workflow)
                self.assertIn(
                    "group: release-${{ github.workflow }}-${{ inputs.release_tag }}",
                    workflow,
                )
                self.assertIn("cancel-in-progress: true", workflow)

    def test_ci_runs_publisher_tests_as_an_importable_module(self) -> None:
        workflow = (
            SCRIPT_PATH.parents[1] / ".github" / "workflows" / "ci.yml"
        ).read_text(encoding="utf-8")

        self.assertIn(
            "python3 -m unittest scripts.test_publish_release_request", workflow
        )
        self.assertNotIn("python3 scripts/test_publish_release_request.py", workflow)

    @unittest.skipIf(os.name == "nt", "behavior test runs with native bash in CI")
    def test_macos_signing_retries_transient_failures(self) -> None:
        bash = shutil.which("bash")
        if bash is None:
            self.skipTest("bash is unavailable")

        helper = SCRIPT_PATH.with_name("sign_macos_release_with_retry.sh")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            attempts = root / "attempts.txt"
            fake_signer = root / "fake-sign.sh"
            fake_signer.write_text(
                "#!/usr/bin/env bash\n"
                "set -euo pipefail\n"
                "count=0\n"
                '[[ ! -f "$ATTEMPT_FILE" ]] || count="$(cat "$ATTEMPT_FILE")"\n'
                "count=$((count + 1))\n"
                'printf \'%s\' "$count" > "$ATTEMPT_FILE"\n'
                '[[ "$count" -ge 3 ]]\n',
                encoding="utf-8",
            )
            env = os.environ.copy()
            env.update(
                {
                    "ATTEMPT_FILE": str(attempts),
                    "MACOS_SIGN_SCRIPT": str(fake_signer),
                    "MACOS_SIGN_MAX_ATTEMPTS": "3",
                    "MACOS_SIGN_RETRY_DELAY_SECONDS": "0",
                }
            )

            completed = subprocess.run(
                [bash, str(helper), "unused", "mac-arm64"],
                check=False,
                capture_output=True,
                text=True,
                env=env,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual("3", attempts.read_text(encoding="utf-8"))
            self.assertEqual(2, completed.stderr.count("retrying"))


class ReleasePublisherTest(unittest.TestCase):
    def request(self) -> PUBLISH.ReleaseRequest:
        return PUBLISH.ReleaseRequest(
            DATE_TAG,
            RELEASE_TAG,
            f"LizzieYzy Next {RELEASE_TAG}",
            True,
            f".github/release-notes/{RELEASE_TAG}.md",
        )

    def release_notes(self) -> str:
        blocks: list[str] = [f"# LizzieYzy Next {RELEASE_TAG}"]
        for heading in PUBLISH.LOCALIZED_NOTE_HEADINGS:
            rows = [
                (
                    f"| Test platform | [`{DATE_TAG}-{suffix}`]"
                    f"(https://github.com/wimi321/lizzieyzy-next/releases/download/"
                    f"{RELEASE_TAG}/{DATE_TAG}-{suffix}) |"
                )
                for suffix in PUBLISH.DIRECT_DOWNLOAD_SUFFIXES
            ]
            blocks.append(
                "\n".join(
                    [
                        heading,
                        "",
                        "### Updates",
                        "",
                        "- Reviewed",
                        "",
                        "### Before Downloading",
                        "",
                        "- Choose the matching platform",
                        "",
                        "### Download Guide",
                        "",
                        "| Platform | Direct download |",
                        "| --- | --- |",
                        *rows,
                        "",
                        "### Why",
                        "",
                        "- Stable",
                        "",
                        "### Contact",
                        "",
                        "- Community",
                    ]
                )
            )
        return "\n\n---\n\n".join(blocks)

    def publisher(self, client: FakeClient) -> PUBLISH.ReleasePublisher:
        return PUBLISH.ReleasePublisher(
            client,
            self.request(),
            TARGET_SHA,
            self.release_notes(),
            sleep=lambda _seconds: None,
            poll_seconds=0,
            run_timeout_seconds=30,
        )

    def seed_successful_workflows(self, client: FakeClient) -> None:
        for spec in PUBLISH.WORKFLOWS:
            client.seed_workflow_run(spec.workflow_file)

    def prepared_successful_client(self) -> FakeClient:
        client = FakeClient()
        client.assets = [fake_asset_metadata(name) for name in all_asset_names()]
        self.seed_successful_workflows(client)
        return client

    def test_publishes_only_after_platforms_assets_and_notes_succeed(self) -> None:
        client = FakeClient()

        release = self.publisher(client).publish()

        self.assertEqual(TARGET_SHA, client.tag_sha)
        self.assertFalse(release["draft"])
        self.assertTrue(release["prerelease"])
        self.assertEqual(RELEASE_TAG, release["tag_name"])
        self.assertEqual(TARGET_SHA, release["target_commitish"])
        self.assertEqual(
            [spec.workflow_file for spec in PUBLISH.WORKFLOWS],
            client.dispatched,
        )
        self.assertEqual(
            "true",
            client.dispatched_inputs["build-windows-release.yml"]["release_prerelease"],
        )
        for workflow_file, inputs in client.dispatched_inputs.items():
            self.assertEqual("true", inputs["release_prerelease"], workflow_file)
        self.assertCountEqual(all_asset_names(), client.assets)
        self.assertTrue(client.update_payloads)
        for payload in client.update_payloads:
            self.assertEqual(RELEASE_TAG, payload["tag_name"])
            self.assertEqual(TARGET_SHA, payload["target_commitish"])
        self.assertEqual(self.release_notes(), client.update_payloads[-1]["body"])

    def test_rerun_reuses_tag_created_before_a_lost_response(self) -> None:
        client = self.prepared_successful_client()
        create_tag = client.create_tag

        def create_tag_then_lose_response(tag: str, target_sha: str) -> None:
            create_tag(tag, target_sha)
            raise PUBLISH.PublishError("simulated lost create-tag response")

        client.create_tag = create_tag_then_lose_response  # type: ignore[method-assign]
        with self.assertRaisesRegex(PUBLISH.PublishError, "lost create-tag"):
            self.publisher(client).publish()

        self.assertEqual(TARGET_SHA, client.tag_sha)
        self.assertEqual(1, client.create_tag_calls)
        self.assertIsNone(client.release)

        client.create_tag = create_tag  # type: ignore[method-assign]
        release = self.publisher(client).publish()

        self.assertFalse(release["draft"])
        self.assertEqual(1, client.create_tag_calls)
        self.assertEqual(1, client.create_draft_release_calls)

    def test_rerun_reuses_draft_created_before_a_lost_response(self) -> None:
        client = self.prepared_successful_client()
        create_draft_release = client.create_draft_release

        def create_draft_then_lose_response(
            request: PUBLISH.ReleaseRequest, target_sha: str
        ) -> dict[str, object]:
            create_draft_release(request, target_sha)
            raise PUBLISH.PublishError("simulated lost create-release response")

        client.create_draft_release = (  # type: ignore[method-assign]
            create_draft_then_lose_response
        )
        with self.assertRaisesRegex(PUBLISH.PublishError, "lost create-release"):
            self.publisher(client).publish()

        assert client.release is not None
        self.assertTrue(client.release["draft"])
        self.assertEqual(RELEASE_TAG, client.release["tag_name"])
        self.assertEqual(1, client.create_tag_calls)
        self.assertEqual(1, client.create_draft_release_calls)

        client.create_draft_release = create_draft_release  # type: ignore[method-assign]
        release = self.publisher(client).publish()

        self.assertFalse(release["draft"])
        self.assertEqual(1, client.create_tag_calls)
        self.assertEqual(1, client.create_draft_release_calls)

    def test_rerun_restores_untagged_draft_created_before_lost_response(self) -> None:
        client = self.prepared_successful_client()
        create_draft_release = client.create_draft_release

        def create_orphan_then_lose_response(
            request: PUBLISH.ReleaseRequest, target_sha: str
        ) -> dict[str, object]:
            create_draft_release(request, target_sha)
            assert client.release is not None
            client.release["tag_name"] = "untagged-0123456789abcdefabcd"
            raise PUBLISH.PublishError("simulated lost orphan response")

        client.create_draft_release = (  # type: ignore[method-assign]
            create_orphan_then_lose_response
        )
        with self.assertRaisesRegex(PUBLISH.PublishError, "lost orphan"):
            self.publisher(client).publish()

        assert client.release is not None
        self.assertTrue(client.release["draft"])
        self.assertTrue(str(client.release["tag_name"]).startswith("untagged-"))
        self.assertEqual(1, client.create_tag_calls)
        self.assertEqual(1, client.create_draft_release_calls)

        client.create_draft_release = create_draft_release  # type: ignore[method-assign]
        release = self.publisher(client).publish()

        self.assertFalse(release["draft"])
        self.assertEqual(RELEASE_TAG, release["tag_name"])
        self.assertEqual(1, client.create_tag_calls)
        self.assertEqual(1, client.create_draft_release_calls)

    def test_failed_platform_keeps_release_as_draft(self) -> None:
        client = FakeClient(failed_workflow="build-linux-release.yml")

        with self.assertRaisesRegex(PUBLISH.PublishError, "Linux workflow.*failure"):
            self.publisher(client).publish()

        assert client.release is not None
        self.assertTrue(client.release["draft"])
        self.assertTrue(client.release["prerelease"])

    def test_wait_for_runs_allows_initial_identity_metadata_to_converge(self) -> None:
        client = FakeClient()
        spec = PUBLISH.WORKFLOWS[0]
        run_id = client.seed_workflow_run(spec.workflow_file)
        expected = client.get_workflow_run(run_id)
        snapshots = [
            {**expected, "head_sha": "b" * 40},
            {**expected, "display_title": "temporarily stale run title"},
            expected,
        ]
        calls = 0

        def get_workflow_run(_run_id: int) -> dict[str, object]:
            nonlocal calls
            self.assertEqual(run_id, _run_id)
            calls += 1
            return dict(snapshots.pop(0))

        client.get_workflow_run = get_workflow_run  # type: ignore[method-assign]
        publisher = self.publisher(client)

        publisher._wait_for_runs({spec.platform: run_id})

        self.assertEqual(3, calls)
        self.assertEqual(expected["html_url"], publisher.run_urls[spec.platform])

    def test_wait_for_runs_fails_when_identity_never_converges(self) -> None:
        client = FakeClient()
        spec = PUBLISH.WORKFLOWS[0]
        run_id = client.seed_workflow_run(spec.workflow_file)
        stale = client.get_workflow_run(run_id)
        stale["display_title"] = "permanently stale run title"
        now = [0.0]
        calls = 0

        def get_workflow_run(_run_id: int) -> dict[str, object]:
            nonlocal calls
            self.assertEqual(run_id, _run_id)
            calls += 1
            return dict(stale)

        def advance(seconds: float) -> None:
            now[0] += seconds

        client.get_workflow_run = get_workflow_run  # type: ignore[method-assign]
        publisher = PUBLISH.ReleasePublisher(
            client,
            self.request(),
            TARGET_SHA,
            self.release_notes(),
            sleep=advance,
            poll_seconds=2,
            run_timeout_seconds=30,
        )

        with (
            mock.patch.object(PUBLISH, "WORKFLOW_IDENTITY_CONVERGENCE_SECONDS", 5),
            mock.patch.object(PUBLISH.time, "monotonic", side_effect=lambda: now[0]),
            self.assertRaisesRegex(PUBLISH.PublishError, "identity did not converge"),
        ):
            publisher._wait_for_runs({spec.platform: run_id})

        self.assertEqual(4, calls)
        self.assertEqual(6.0, now[0])
        self.assertNotIn(spec.platform, publisher.run_urls)

    def test_wait_for_runs_fails_if_confirmed_identity_changes(self) -> None:
        client = FakeClient()
        spec = PUBLISH.WORKFLOWS[0]
        run_id = client.seed_workflow_run(spec.workflow_file, status="in_progress")
        expected = client.get_workflow_run(run_id)
        snapshots = [expected, {**expected, "head_sha": "b" * 40}]
        calls = 0

        def get_workflow_run(_run_id: int) -> dict[str, object]:
            nonlocal calls
            self.assertEqual(run_id, _run_id)
            calls += 1
            return dict(snapshots.pop(0))

        client.get_workflow_run = get_workflow_run  # type: ignore[method-assign]

        with self.assertRaisesRegex(
            PUBLISH.PublishError, "changed identity after initial verification"
        ):
            self.publisher(client)._wait_for_runs({spec.platform: run_id})

        self.assertEqual(2, calls)

    def test_waits_for_target_ci_before_creating_release(self) -> None:
        client = FakeClient()
        client.ci_run_snapshots = [
            [
                {
                    "id": 50,
                    "head_sha": TARGET_SHA,
                    "status": "in_progress",
                    "conclusion": None,
                }
            ],
            [
                {
                    "id": 50,
                    "head_sha": TARGET_SHA,
                    "status": "completed",
                    "conclusion": "success",
                }
            ],
        ]

        release = self.publisher(client).publish()

        self.assertFalse(release["draft"])
        self.assertGreaterEqual(client.ci_list_calls, 2)

    def test_failed_target_ci_prevents_tag_release_and_dispatch(self) -> None:
        client = FakeClient()
        client.ci_run_snapshots[0][0]["conclusion"] = "failure"

        with self.assertRaisesRegex(PUBLISH.PublishError, "CI workflow.*failure"):
            self.publisher(client).publish()

        self.assertIsNone(client.tag_sha)
        self.assertIsNone(client.release)
        self.assertEqual([], client.dispatched)

    def test_ci_is_rechecked_immediately_before_publication(self) -> None:
        client = FakeClient()
        client.ci_run_snapshots = [
            [
                {
                    "id": 50,
                    "head_sha": TARGET_SHA,
                    "status": "completed",
                    "conclusion": "success",
                }
            ],
            [
                {
                    "id": 51,
                    "head_sha": TARGET_SHA,
                    "status": "completed",
                    "conclusion": "failure",
                }
            ],
        ]

        with self.assertRaisesRegex(PUBLISH.PublishError, "CI workflow.*failure"):
            self.publisher(client).publish()

        assert client.release is not None
        self.assertTrue(client.release["draft"])

    def test_existing_assets_do_not_hide_latest_failed_platform_run(self) -> None:
        client = FakeClient()
        client.assets = all_asset_names()
        self.seed_successful_workflows(client)
        client.seed_workflow_run("build-windows-release.yml", conclusion="failure")

        release = self.publisher(client).publish()

        self.assertFalse(release["draft"])
        self.assertEqual(["build-windows-release.yml"], client.dispatched)

    def test_existing_assets_and_current_successful_runs_are_reused(self) -> None:
        client = FakeClient()
        client.assets = all_asset_names()
        self.seed_successful_workflows(client)

        release = self.publisher(client).publish()

        self.assertFalse(release["draft"])
        self.assertEqual([], client.dispatched)

    def test_duplicate_active_target_run_blocks_publication(self) -> None:
        client = FakeClient()
        client.assets = [fake_asset_metadata(name) for name in all_asset_names()]
        client.seed_workflow_run(
            "build-windows-release.yml", status="in_progress"
        )
        self.seed_successful_workflows(client)

        with self.assertRaisesRegex(
            PUBLISH.PublishError, "duplicate target workflow runs are active"
        ):
            self.publisher(client).publish()

        assert client.release is not None
        self.assertTrue(client.release["draft"])

    def test_stale_active_list_state_for_selected_run_does_not_block(self) -> None:
        client = FakeClient()
        client.assets = [fake_asset_metadata(name) for name in all_asset_names()]
        self.seed_successful_workflows(client)
        original_list_workflow_runs = client.list_workflow_runs

        def list_with_stale_selected_status(
            workflow_file: str, target_sha: str
        ) -> list[dict[str, object]]:
            runs = [
                dict(run)
                for run in original_list_workflow_runs(workflow_file, target_sha)
            ]
            if workflow_file == "build-windows-release.yml" and runs:
                runs[0]["status"] = "in_progress"
                runs[0]["conclusion"] = None
            return runs

        client.list_workflow_runs = (  # type: ignore[method-assign]
            list_with_stale_selected_status
        )

        release = self.publisher(client).publish()

        self.assertFalse(release["draft"])

    def test_successful_runs_for_another_sha_do_not_satisfy_platform_gate(self) -> None:
        client = FakeClient()
        client.assets = all_asset_names()
        for spec in PUBLISH.WORKFLOWS:
            client.seed_workflow_run(spec.workflow_file, head_sha="b" * 40)

        release = self.publisher(client).publish()

        self.assertFalse(release["draft"])
        self.assertEqual(
            [spec.workflow_file for spec in PUBLISH.WORKFLOWS],
            client.dispatched,
        )

    def test_successful_run_with_wrong_input_attestation_is_not_reused(self) -> None:
        client = FakeClient()
        client.assets = all_asset_names()
        for spec in PUBLISH.WORKFLOWS:
            if spec.platform != "Windows":
                client.seed_workflow_run(spec.workflow_file)
        client.seed_workflow_run(
            "build-windows-release.yml",
            display_title=(
                f"Windows release {RELEASE_TAG} | 2026-07-21 | prerelease=true"
            ),
        )

        release = self.publisher(client).publish()

        self.assertFalse(release["draft"])
        self.assertEqual(["build-windows-release.yml"], client.dispatched)

    def test_any_unexpected_release_asset_fails_closed(self) -> None:
        client = FakeClient()
        client.assets = all_asset_names() + [f"{DATE_TAG}-windows64-install.txt"]
        self.seed_successful_workflows(client)

        with self.assertRaisesRegex(PUBLISH.PublishError, "unexpected assets"):
            self.publisher(client).publish()

        assert client.release is not None
        self.assertTrue(client.release["draft"])

    def test_signed_update_envelope_is_ignored_by_public_asset_inventory(self) -> None:
        client = FakeClient()
        client.assets = all_asset_names() + ["lizzieyzy-next-update-envelope.json"]
        self.seed_successful_workflows(client)

        release = self.publisher(client).publish()

        self.assertFalse(release["draft"])
        self.assertTrue(release["prerelease"])
        self.assertIn("lizzieyzy-next-update-envelope.json", client.assets)


    def test_missing_expected_release_asset_fails_closed(self) -> None:
        client = FakeClient()
        client.assets = [
            name
            for name in all_asset_names()
            if name != "lizzieyzy-next-update-manifest.json"
        ]
        self.seed_successful_workflows(client)

        with self.assertRaisesRegex(PUBLISH.PublishError, "missing assets"):
            self.publisher(client).publish()

        assert client.release is not None
        self.assertTrue(client.release["draft"])

    def test_same_named_incomplete_release_asset_fails_closed(self) -> None:
        for field, value, message in (
            ("state", "starter", "state is not uploaded"),
            ("size", 0, "size is not positive"),
            ("digest", None, "digest is missing or invalid"),
        ):
            with self.subTest(field=field):
                client = self.prepared_successful_client()
                asset = client.assets[0]
                assert isinstance(asset, dict)
                asset[field] = value
                with self.assertRaisesRegex(PUBLISH.PublishError, message):
                    self.publisher(client).publish()
                assert client.release is not None
                self.assertTrue(client.release["draft"])

    def test_same_named_replacement_asset_must_match_run_provenance(self) -> None:
        for field, value, message in (
            ("size", 999, "size differs from run provenance"),
            (
                "digest",
                "sha256:" + hashlib.sha256(b"replacement payload").hexdigest(),
                "digest differs from run provenance",
            ),
        ):
            with self.subTest(field=field):
                client = self.prepared_successful_client()
                asset = client.assets[0]
                assert isinstance(asset, dict)
                asset[field] = value
                with self.assertRaisesRegex(PUBLISH.PublishError, message):
                    self.publisher(client).publish()

    def test_selected_run_rejects_provenance_from_an_older_successful_run(self) -> None:
        client = FakeClient()
        client.assets = [fake_asset_metadata(name) for name in all_asset_names()]
        old_windows_run = client.seed_workflow_run("build-windows-release.yml")
        old_payload = client.provenance_payload(old_windows_run)
        selected_windows_run = client.seed_workflow_run("build-windows-release.yml")
        client.provenance_payload_overrides[selected_windows_run] = old_payload
        for spec in PUBLISH.WORKFLOWS:
            if spec.platform != "Windows":
                client.seed_workflow_run(spec.workflow_file)

        with self.assertRaisesRegex(PUBLISH.PublishError, "workflowRunId"):
            self.publisher(client).publish()

    def test_provenance_artifact_metadata_and_download_are_fail_closed(self) -> None:
        mutations = (
            ({"expired": True}, "expired"),
            ({"workflow_run": {"id": 999, "head_sha": TARGET_SHA}}, "not bound"),
            ({"digest": "sha256:" + "0" * 64}, "digest does not match"),
            ({"size_in_bytes": 1}, "download size does not match"),
        )
        for override, message in mutations:
            with self.subTest(override=override):
                client = self.prepared_successful_client()
                windows_run = int(
                    client.workflow_runs["build-windows-release.yml"][0]["id"]
                )
                client.artifact_metadata_overrides[windows_run] = override
                with self.assertRaisesRegex(PUBLISH.PublishError, message):
                    self.publisher(client).publish()

    def test_already_published_complete_release_is_idempotent(self) -> None:
        client = FakeClient()
        self.seed_successful_workflows(client)
        client.tag_sha = TARGET_SHA
        client.assets = all_asset_names()
        client.release = {
            "id": 7,
            "tag_name": RELEASE_TAG,
            "target_commitish": TARGET_SHA,
            "name": self.request().title,
            "body": self.release_notes(),
            "draft": False,
            "prerelease": True,
            "html_url": "https://example.invalid/release",
        }

        release = self.publisher(client).publish()

        self.assertFalse(release["draft"])
        self.assertEqual([], client.dispatched)

    def test_idempotent_release_requires_exact_canonical_notes(self) -> None:
        client = FakeClient()
        self.seed_successful_workflows(client)
        client.tag_sha = TARGET_SHA
        client.assets = all_asset_names()
        client.release = {
            "id": 7,
            "tag_name": RELEASE_TAG,
            "target_commitish": TARGET_SHA,
            "name": self.request().title,
            "body": self.release_notes().replace("- Stable", "- Edited", 1),
            "draft": False,
            "prerelease": True,
            "html_url": "https://example.invalid/release",
        }

        with self.assertRaisesRegex(PUBLISH.PublishError, "canonical reviewed"):
            self.publisher(client).publish()

    def test_canonical_notes_are_reread_immediately_before_publication(self) -> None:
        client = self.prepared_successful_client()
        original_get_release = client.get_release

        def get_release_with_tamper(tag: str) -> dict[str, object] | None:
            release = original_get_release(tag)
            if (
                release is not None
                and release.get("draft") is True
                and release.get("body") == self.release_notes()
            ):
                assert client.release is not None
                client.release["body"] = self.release_notes() + "\nexternal edit\n"
                return dict(client.release)
            return release

        client.get_release = get_release_with_tamper  # type: ignore[method-assign]

        with self.assertRaisesRegex(PUBLISH.PublishError, "canonical reviewed"):
            self.publisher(client).publish()

        assert client.release is not None
        self.assertTrue(client.release["draft"])

    def test_canonical_notes_are_reread_after_publication(self) -> None:
        client = self.prepared_successful_client()
        original_get_release_by_tag = client.get_release_by_tag

        def get_release_by_tag_with_tamper(tag: str) -> dict[str, object] | None:
            release = original_get_release_by_tag(tag)
            if release is not None and release.get("draft") is False:
                assert client.release is not None
                client.release["body"] = self.release_notes() + "\nexternal edit\n"
                return dict(client.release)
            return release

        client.get_release_by_tag = (  # type: ignore[method-assign]
            get_release_by_tag_with_tamper
        )

        with self.assertRaisesRegex(PUBLISH.PublishError, "canonical reviewed"):
            self.publisher(client).publish()

        assert client.release is not None
        self.assertFalse(client.release["draft"])

    def test_live_tag_is_reread_immediately_before_publication(self) -> None:
        client = self.prepared_successful_client()
        calls = 0

        def moving_tag(_tag: str) -> str:
            nonlocal calls
            calls += 1
            return TARGET_SHA if calls == 1 else "b" * 40

        client.get_tag_sha = moving_tag  # type: ignore[method-assign]

        with self.assertRaisesRegex(PUBLISH.PublishError, "Live tag.*changed"):
            self.publisher(client).publish()

        assert client.release is not None
        self.assertTrue(client.release["draft"])

    def test_live_tag_is_reread_after_publication(self) -> None:
        client = self.prepared_successful_client()
        client.tag_sha = TARGET_SHA
        original_update_release = client.update_release

        def update_and_move_tag(
            release_id: int, payload: dict[str, object]
        ) -> dict[str, object]:
            release = original_update_release(release_id, payload)
            if payload.get("draft") is False:
                client.tag_sha = "b" * 40
            return release

        client.update_release = update_and_move_tag  # type: ignore[method-assign]

        with self.assertRaisesRegex(PUBLISH.PublishError, "Live tag.*changed"):
            self.publisher(client).publish()

        assert client.release is not None
        self.assertFalse(client.release["draft"])

    def test_idempotent_path_rereads_live_tag(self) -> None:
        client = FakeClient()
        self.seed_successful_workflows(client)
        client.assets = all_asset_names()
        client.release = {
            "id": 7,
            "tag_name": RELEASE_TAG,
            "target_commitish": TARGET_SHA,
            "name": self.request().title,
            "body": self.release_notes(),
            "draft": False,
            "prerelease": True,
            "html_url": "https://example.invalid/release",
        }
        calls = 0

        def moving_tag(_tag: str) -> str:
            nonlocal calls
            calls += 1
            return TARGET_SHA if calls == 1 else "b" * 40

        client.get_tag_sha = moving_tag  # type: ignore[method-assign]

        with self.assertRaisesRegex(PUBLISH.PublishError, "Live tag.*changed"):
            self.publisher(client).publish()

    def test_cleans_unbound_synthetic_tag_alias_for_published_release(self) -> None:
        client = FakeClient()
        self.seed_successful_workflows(client)
        client.tag_sha = TARGET_SHA
        client.assets = all_asset_names()
        client.detached_tag_aliases = {
            "untagged-0123456789abcdefabcd": TARGET_SHA,
        }
        client.release = {
            "id": 7,
            "tag_name": RELEASE_TAG,
            "target_commitish": TARGET_SHA,
            "name": self.request().title,
            "body": self.release_notes(),
            "draft": False,
            "prerelease": True,
            "html_url": "https://example.invalid/release",
        }

        self.publisher(client).publish()

        self.assertEqual(["untagged-0123456789abcdefabcd"], client.deleted_tags)
        self.assertEqual({}, client.detached_tag_aliases)

    def test_preserves_synthetic_tag_alias_when_a_release_still_uses_it(self) -> None:
        alias = "untagged-0123456789abcdefabcd"
        client = FakeClient()
        self.seed_successful_workflows(client)
        client.tag_sha = TARGET_SHA
        client.assets = all_asset_names()
        client.detached_tag_aliases = {alias: TARGET_SHA}
        client.protected_release_tags = {alias}
        client.release = {
            "id": 7,
            "tag_name": RELEASE_TAG,
            "target_commitish": TARGET_SHA,
            "name": self.request().title,
            "body": self.release_notes(),
            "draft": False,
            "prerelease": True,
            "html_url": "https://example.invalid/release",
        }

        self.publisher(client).publish()

        self.assertEqual([], client.deleted_tags)
        self.assertEqual({alias: TARGET_SHA}, client.detached_tag_aliases)

    def test_refuses_to_rebind_public_orphan_before_release_gates(self) -> None:
        client = FakeClient()
        self.seed_successful_workflows(client)
        client.tag_sha = TARGET_SHA
        client.assets = all_asset_names()
        client.release = {
            "id": 7,
            "tag_name": "untagged-detached-release",
            "target_commitish": TARGET_SHA,
            "name": self.request().title,
            "body": self.release_notes(),
            "draft": False,
            "prerelease": True,
            "html_url": "https://example.invalid/orphaned",
        }

        with self.assertRaisesRegex(PUBLISH.PublishError, "public orphaned release"):
            self.publisher(client).publish()

        assert client.release is not None
        self.assertEqual("untagged-detached-release", client.release["tag_name"])
        self.assertFalse(client.release["draft"])
        self.assertEqual([], client.dispatched)
        self.assertEqual([], client.update_payloads)

    def test_restores_only_draft_orphan_then_publishes_after_all_gates(self) -> None:
        client = FakeClient()
        self.seed_successful_workflows(client)
        client.tag_sha = TARGET_SHA
        client.assets = all_asset_names()
        client.release = {
            "id": 7,
            "tag_name": "untagged-detached-release",
            "target_commitish": TARGET_SHA,
            "name": self.request().title,
            "body": self.release_notes(),
            "draft": True,
            "prerelease": True,
            "html_url": "https://example.invalid/orphaned",
        }

        release = self.publisher(client).publish()

        self.assertEqual(RELEASE_TAG, release["tag_name"])
        self.assertFalse(release["draft"])
        self.assertTrue(client.update_payloads[0]["draft"])
        self.assertFalse(client.update_payloads[-1]["draft"])

    def test_already_published_release_requires_exact_title(self) -> None:
        client = FakeClient()
        self.seed_successful_workflows(client)
        client.tag_sha = TARGET_SHA
        client.assets = all_asset_names()
        client.release = {
            "id": 7,
            "tag_name": RELEASE_TAG,
            "target_commitish": TARGET_SHA,
            "name": "Stale release title",
            "body": self.release_notes(),
            "draft": False,
            "prerelease": True,
            "html_url": "https://example.invalid/release",
        }

        with self.assertRaisesRegex(PUBLISH.PublishError, "Release title changed"):
            self.publisher(client).publish()

    def test_fails_closed_when_github_detaches_the_release_tag(self) -> None:
        client = FakeClient(detach_on_publish=True)

        with self.assertRaisesRegex(PUBLISH.PublishError, "tag identity changed"):
            self.publisher(client).publish()

        assert client.release is not None
        self.assertEqual("untagged-detached-release", client.release["tag_name"])

    def test_fails_closed_when_published_tag_is_not_publicly_addressable(self) -> None:
        client = FakeClient(hide_public_by_tag=True)

        with self.assertRaisesRegex(PUBLISH.PublishError, "not addressable by tag"):
            self.publisher(client).publish()

    def test_rejects_incomplete_notes_before_creating_a_tag(self) -> None:
        client = FakeClient()

        with self.assertRaisesRegex(PUBLISH.PublishError, "missing the tag or a language"):
            PUBLISH.ReleasePublisher(
                client,
                self.request(),
                TARGET_SHA,
                "## 中文\nIncomplete",
            )

        self.assertIsNone(client.tag_sha)

    def test_rejects_reference_style_download_labels(self) -> None:
        client = FakeClient()
        notes = self.release_notes().replace(
            f"[`{DATE_TAG}-windows64.opencl.portable.zip`]"
            f"(https://github.com/wimi321/lizzieyzy-next/releases/download/"
            f"{RELEASE_TAG}/{DATE_TAG}-windows64.opencl.portable.zip)",
            "[portable][win-opencl-portable]",
        )

        with self.assertRaisesRegex(
            PUBLISH.PublishError,
            "must directly link the full filename",
        ):
            PUBLISH.ReleasePublisher(
                client,
                self.request(),
                TARGET_SHA,
                notes,
            )

    def test_rejects_two_regular_assets_on_one_row(self) -> None:
        client = FakeClient()
        first = (
            f"[`{DATE_TAG}-windows64.opencl.portable.zip`]"
            f"(https://github.com/wimi321/lizzieyzy-next/releases/download/"
            f"{RELEASE_TAG}/{DATE_TAG}-windows64.opencl.portable.zip)"
        )
        second = (
            f"[`{DATE_TAG}-windows64.core-update.zip`]"
            f"(https://github.com/wimi321/lizzieyzy-next/releases/download/"
            f"{RELEASE_TAG}/{DATE_TAG}-windows64.core-update.zip)"
        )
        notes = self.release_notes().replace(
            f"| Test platform | {first} |\n| Test platform | {second} |",
            f"| Test platform | {first} / {second} |",
        )

        with self.assertRaisesRegex(PUBLISH.PublishError, "on its own row"):
            PUBLISH.ReleasePublisher(
                client,
                self.request(),
                TARGET_SHA,
                notes,
            )

    def test_rejects_unresolved_release_note_markers(self) -> None:
        for marker in (
            "FULL_TEST_COUNT",
            "REAL_GUI_VALIDATION_",
            "REAL_GUI_VALIDATION_EN",
            "TODO",
            "TBD",
            "FIXME",
            "PLACEHOLDER",
            "{{ test_count }}",
        ):
            with self.subTest(marker=marker):
                with self.assertRaisesRegex(PUBLISH.PublishError, "unresolved marker"):
                    PUBLISH.ReleasePublisher(
                        FakeClient(),
                        self.request(),
                        TARGET_SHA,
                        self.release_notes() + f"\n{marker}\n",
                    )


if __name__ == "__main__":
    unittest.main()
