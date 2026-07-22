#!/usr/bin/env python3
"""Regression tests for the audited pre-release publisher."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


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
    names.extend(
        [
            f"{DATE_TAG}-windows64.nvidia.tensorrt.portable.7z.001",
            "lizzieyzy-next-update-manifest.json",
        ]
    )
    return names


class FakeClient:
    def __init__(self, failed_workflow: str | None = None) -> None:
        self.tag_sha: str | None = None
        self.release: dict[str, object] | None = None
        self.assets: list[str] = []
        self.runs: dict[int, dict[str, object]] = {}
        self.workflow_runs: dict[str, list[dict[str, object]]] = {}
        self.next_run_id = 100
        self.failed_workflow = failed_workflow
        self.dispatched: list[str] = []

    def get_tag_sha(self, _tag: str) -> str | None:
        return self.tag_sha

    def create_tag(self, _tag: str, target_sha: str) -> None:
        self.tag_sha = target_sha

    def get_release(self, _tag: str) -> dict[str, object] | None:
        return dict(self.release) if self.release is not None else None

    def create_draft_release(
        self, request: PUBLISH.ReleaseRequest, _target_sha: str
    ) -> dict[str, object]:
        self.release = {
            "id": 7,
            "tag_name": request.release_tag,
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
        self.release.update(payload)
        self.release["html_url"] = "https://example.invalid/release"
        return dict(self.release)

    def list_release_assets(self, _release_id: int) -> list[str]:
        return list(self.assets)

    def list_workflow_runs(self, workflow_file: str, _tag: str) -> list[dict[str, object]]:
        return list(self.workflow_runs.get(workflow_file, []))

    def dispatch_workflow(
        self, workflow_file: str, _tag: str, _inputs: dict[str, str]
    ) -> int:
        self.next_run_id += 1
        run_id = self.next_run_id
        conclusion = "failure" if workflow_file == self.failed_workflow else "success"
        run = {
            "id": run_id,
            "head_sha": TARGET_SHA,
            "status": "completed",
            "conclusion": conclusion,
            "html_url": f"https://example.invalid/runs/{run_id}",
        }
        self.runs[run_id] = run
        self.workflow_runs.setdefault(workflow_file, []).insert(0, run)
        self.dispatched.append(workflow_file)

        if conclusion == "success":
            for spec in PUBLISH.WORKFLOWS:
                if spec.workflow_file == workflow_file:
                    self.assets.extend(
                        f"{DATE_TAG}-{suffix}" for suffix in spec.exact_suffixes
                    )
                    if spec.platform == "Windows":
                        self.assets.extend(
                            [
                                f"{DATE_TAG}-windows64.nvidia.tensorrt.portable.7z.001",
                                "lizzieyzy-next-update-manifest.json",
                            ]
                        )
        return run_id

    def get_workflow_run(self, run_id: int) -> dict[str, object]:
        return dict(self.runs[run_id])


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


class WorkflowSpecTest(unittest.TestCase):
    def test_complete_asset_set_satisfies_every_platform(self) -> None:
        assets = all_asset_names()

        for spec in PUBLISH.WORKFLOWS:
            self.assertEqual([], spec.missing_assets(assets, DATE_TAG), spec.platform)

    def test_windows_requires_a_tensorrt_volume(self) -> None:
        assets = [
            name
            for name in all_asset_names()
            if "nvidia.tensorrt.portable.7z.001" not in name
        ]

        missing = PUBLISH.WORKFLOWS[0].missing_assets(assets, DATE_TAG)

        self.assertEqual(1, len(missing))
        self.assertIn("nvidia", missing[0])
        self.assertIn("7z", missing[0])


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
        return RELEASE_TAG + "\n" + "\n".join(PUBLISH.LOCALIZED_NOTE_HEADINGS)

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

    def test_publishes_only_after_platforms_assets_and_notes_succeed(self) -> None:
        client = FakeClient()

        release = self.publisher(client).publish()

        self.assertEqual(TARGET_SHA, client.tag_sha)
        self.assertFalse(release["draft"])
        self.assertTrue(release["prerelease"])
        self.assertEqual(
            [spec.workflow_file for spec in PUBLISH.WORKFLOWS],
            client.dispatched,
        )
        self.assertCountEqual(all_asset_names(), client.assets)

    def test_failed_platform_keeps_release_as_draft(self) -> None:
        client = FakeClient(failed_workflow="build-linux-release.yml")

        with self.assertRaisesRegex(PUBLISH.PublishError, "Linux workflow.*failure"):
            self.publisher(client).publish()

        assert client.release is not None
        self.assertTrue(client.release["draft"])
        self.assertTrue(client.release["prerelease"])

    def test_already_published_complete_release_is_idempotent(self) -> None:
        client = FakeClient()
        client.tag_sha = TARGET_SHA
        client.assets = all_asset_names()
        client.release = {
            "id": 7,
            "body": self.release_notes(),
            "draft": False,
            "prerelease": True,
            "html_url": "https://example.invalid/release",
        }

        release = self.publisher(client).publish()

        self.assertFalse(release["draft"])
        self.assertEqual([], client.dispatched)

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


if __name__ == "__main__":
    unittest.main()
