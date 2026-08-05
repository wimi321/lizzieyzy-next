import base64
import json
import sys
import unittest
from unittest import mock

from scripts import r2_release


TAG = "next-2026-08-03.1"
DATE = "2026-08-03"
SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"


def asset(name, size=1000):
    return {
        "name": name,
        "size": size,
        "digest": f"sha256:{SHA}",
        "url": f"https://api.github.com/assets/{name}",
        "browser_download_url": f"https://github.com/example/releases/download/{TAG}/{name}",
    }


def release():
    names = [
        f"{DATE}-windows64.opencl.portable.zip",
        f"{DATE}-windows64.with-katago.portable.zip",
        f"{DATE}-windows64.nvidia.portable.zip",
        f"{DATE}-windows64.nvidia50.cuda.portable.zip",
        f"{DATE}-windows64.without.engine.portable.zip",
        f"{DATE}-windows64.core-update.zip",
        f"{DATE}-mac-apple-silicon.with-katago.dmg",
        f"{DATE}-mac-intel.with-katago.dmg",
        f"{DATE}-windows64.nvidia.tensorrt.portable.7z.001",
        f"{DATE}-windows64.nvidia.tensorrt.portable.7z.002",
        f"{DATE}-windows64.nvidia.tensorrt.portable.README.txt",
        f"{DATE}-windows64.nvidia.tensorrt.portable.manifest.json",
        f"{DATE}-windows64.nvidia.tensorrt.portable.sha256.txt",
        f"{DATE}-linux64.with-katago.zip",
        f"{DATE}-linux64.opencl.zip",
        f"{DATE}-linux64.nvidia.zip",
        f"{DATE}-windows64.opencl.installer.exe",
    ]
    return {
        "tag_name": TAG,
        "published_at": "2026-08-03T00:00:00Z",
        "html_url": f"https://github.com/example/releases/tag/{TAG}",
        "body": "# LizzieYzy Next\n\nRelease notes\n",
        "assets": [asset(name) for name in names],
    }


class R2ReleaseTest(unittest.TestCase):
    def test_whitelist_is_exact_and_excludes_installers_and_linux(self):
        selected = r2_release.select_r2_assets(release(), r2_release.DEFAULT_PUBLIC_BASE)

        self.assertEqual(13, len(selected))
        self.assertEqual(13_000, sum(entry.size for entry in selected))
        self.assertFalse(any(entry.name.endswith("installer.exe") for entry in selected))
        self.assertFalse(any("linux64" in entry.name for entry in selected))

    def test_missing_asset_and_size_limit_fail_closed(self):
        missing = release()
        missing["assets"] = [
            entry for entry in missing["assets"] if not entry["name"].endswith(".7z.002")
        ]
        with self.assertRaises(r2_release.ReleaseError):
            r2_release.select_r2_assets(missing, r2_release.DEFAULT_PUBLIC_BASE)

        oversized = release()
        oversized["assets"][0]["size"] = r2_release.R2_SIZE_LIMIT
        with self.assertRaises(r2_release.ReleaseError):
            r2_release.select_r2_assets(oversized, r2_release.DEFAULT_PUBLIC_BASE)

    def test_stale_release_keys_require_an_exact_inventory(self):
        keep = {
            f"releases/{TAG}/{DATE}-windows64.core-update.zip",
            f"releases/{TAG}/{DATE}-mac-apple-silicon.with-katago.dmg",
        }
        existing = [
            *keep,
            f"releases/{TAG}/obsolete-same-tag.zip",
            "releases/next-2026-07-01.1/old.zip",
        ]

        self.assertEqual(
            [
                "releases/next-2026-07-01.1/old.zip",
                f"releases/{TAG}/obsolete-same-tag.zip",
            ],
            r2_release.stale_release_keys(existing, keep),
        )

    def test_manifest_uses_r2_primary_github_mirror_and_linux_github_only(self):
        source = release()
        selected = r2_release.select_r2_assets(source, r2_release.DEFAULT_PUBLIC_BASE)
        manifest = r2_release.build_manifest(source, selected, r2_release.DEFAULT_PUBLIC_BASE)

        self.assertEqual(2, manifest["schemaVersion"])
        self.assertFalse(manifest["prerelease"])
        core = manifest["components"][0]
        self.assertTrue(core["downloadUrl"].startswith("https://download.goagent.top/"))
        self.assertTrue(core["mirrorUrls"][0].startswith("https://github.com/"))
        mac_arm = next(
            package
            for package in manifest["packages"]
            if package["platform"] == "macos" and package["arch"] == "arm64"
        )
        self.assertEqual("open-dmg", mac_arm["installMode"])
        linux = next(package for package in manifest["packages"] if package["platform"] == "linux")
        self.assertTrue(linux["downloadUrl"].startswith("https://github.com/"))
        self.assertEqual([], linux["mirrorUrls"])

    def test_legacy_manifest_stays_github_only(self):
        source = release()
        selected = r2_release.select_r2_assets(source, r2_release.DEFAULT_PUBLIC_BASE)
        v2 = r2_release.build_manifest(source, selected, r2_release.DEFAULT_PUBLIC_BASE)
        legacy = r2_release.build_legacy_manifest(v2)

        self.assertEqual(1, legacy["schemaVersion"])
        self.assertTrue(legacy["components"][0]["downloadUrl"].startswith("https://github.com/"))
        self.assertEqual([], legacy["components"][0]["mirrorUrls"])

    def test_signing_covers_exact_payload(self):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

        key = Ed25519PrivateKey.generate()
        private_pem = key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
        manifest = {"schemaVersion": 2, "releaseTag": TAG}
        envelope = r2_release.sign_manifest(manifest, private_pem, "test-key")
        payload = base64.b64decode(envelope["payload"])
        signature = base64.b64decode(envelope["signature"])

        key.public_key().verify(signature, payload)
        self.assertEqual(manifest, json.loads(payload))

    def test_download_page_has_beginner_friendly_direct_downloads(self):
        source = release()
        selected = r2_release.select_r2_assets(source, r2_release.DEFAULT_PUBLIC_BASE)
        catalog = r2_release.build_catalog(source, selected, r2_release.DEFAULT_PUBLIC_BASE)
        page = r2_release.render_index(catalog)
        maintenance = r2_release.render_index(catalog, maintenance=True)

        self.assertIn("选择你的版本", page)
        self.assertIn("NVIDIA 显卡", page)
        self.assertIn("RTX 50 CUDA", page)
        self.assertIn("TensorRT 高性能版", page)
        self.assertIn("两个分卷都要下载", page)
        self.assertIn("分卷 1", page)
        self.assertIn("分卷 2", page)
        self.assertIn("CPU 通用版", page)
        self.assertIn("OpenCL 兼容版", page)
        self.assertIn("下载小更新", page)
        self.assertIn("data:image/png;base64,", page)
        self.assertIn("data:image/webp;base64,", page)
        self.assertIn("data:image/svg+xml;base64,", page)
        self.assertEqual(10, page.count('class="download-action"'))
        self.assertEqual(1, page.count('class="volume-actions"'))
        self.assertEqual(1, page.count(".7z.001"))
        self.assertEqual(1, page.count(".7z.002"))
        self.assertNotIn("Cloudflare", page)
        self.assertNotIn("GitHub 下载量", page)
        self.assertNotIn("README.txt", page)
        self.assertNotIn("manifest.json", page)
        self.assertNotIn("sha256.txt", page)
        self.assertNotIn("<details", page)
        for hidden_suffix in ("README.txt", "manifest.json", "sha256.txt"):
            hidden_entry = next(
                entry for entry in catalog["assets"] if entry["name"].endswith(hidden_suffix)
            )
            self.assertNotIn(hidden_entry["downloadUrl"], page)
        self.assertIn("下载页面正在更新，当前下载仍可正常使用", maintenance)

    def test_stable_release_body_uses_r2_and_keeps_one_github_fallback(self):
        source = release()
        selected = r2_release.select_r2_assets(source, r2_release.DEFAULT_PUBLIC_BASE)
        linked = selected[0]
        source["body"] = (
            "# LizzieYzy Next\n\n"
            f"[{linked.name}]({linked.browser_url})\n"
        )

        updated = r2_release.stable_release_body(
            source, selected, r2_release.DEFAULT_PUBLIC_BASE
        )
        repeated = r2_release.stable_release_body(
            {**source, "body": updated}, selected, r2_release.DEFAULT_PUBLIC_BASE
        )

        self.assertIn(r2_release.r2_url(r2_release.DEFAULT_PUBLIC_BASE, linked), updated)
        self.assertNotIn(linked.browser_url, updated)
        self.assertEqual(1, updated.count(r2_release.RELEASE_NOTE_START))
        self.assertEqual(1, updated.count(source["html_url"]))
        self.assertEqual(updated, repeated)

    def test_public_assets_are_verified_before_envelope_activation(self):
        events = []

        def verify(public_base, assets):
            events.append(("verify", public_base, len(list(assets))))

        def put(client, bucket, key, body, **kwargs):
            events.append(("put", key))

        with mock.patch.object(
            r2_release, "verify_public_objects", side_effect=verify
        ), mock.patch.object(
            r2_release,
            "render_index",
            return_value="<title>LizzieYzy Next</title>",
        ), mock.patch.object(
            r2_release,
            "verify_public_homepage",
            side_effect=lambda public_base: events.append(("verify-home", public_base)),
        ), mock.patch.object(
            r2_release,
            "verify_public_stable_channel",
            side_effect=lambda public_base, **kwargs: events.append(
                ("verify-channel", public_base)
            ),
        ), mock.patch.object(r2_release, "put_bytes", side_effect=put):
            catalog_body, envelope_body = r2_release.verify_and_activate_stable_channel(
                object(),
                "bucket",
                r2_release.DEFAULT_PUBLIC_BASE,
                [object()],
                {"tag": TAG},
                {"payload": "signed"},
                skip_public_verify=False,
            )

        self.assertEqual("verify", events[0][0])
        self.assertEqual("verify-home", events[1][0])
        self.assertEqual(
            "channels/stable/update-envelope.json",
            [event for event in events if event[0] == "put"][-1][1],
        )
        self.assertEqual("verify-channel", events[-1][0])
        self.assertEqual({"tag": TAG}, json.loads(catalog_body))
        self.assertEqual({"payload": "signed"}, json.loads(envelope_body))

    def test_public_homepage_route_accepts_cache_busting_query(self):
        response = mock.Mock(
            status_code=200,
            url=r2_release.DEFAULT_PUBLIC_BASE + "/?r2-verify=1-1",
            headers={"Content-Type": "text/html; charset=utf-8"},
        )
        response.iter_content.return_value = iter([b"<title>LizzieYzy Next</title>"])
        requests = mock.Mock()
        requests.RequestException = RuntimeError
        requests.get.return_value = response

        with mock.patch.dict(sys.modules, {"requests": requests}), mock.patch.object(
            r2_release.time, "time", return_value=1
        ):
            r2_release.verify_public_homepage(r2_release.DEFAULT_PUBLIC_BASE)

        requested_url = requests.get.call_args.args[0]
        self.assertEqual(
            r2_release.DEFAULT_PUBLIC_BASE + "/?r2-verify=1-1", requested_url
        )
        self.assertTrue(requests.get.call_args.kwargs["stream"])
        response.close.assert_called_once_with()

    def test_public_stable_channel_matches_exact_uploaded_bodies(self):
        bodies = {
            r2_release.DEFAULT_PUBLIC_BASE + "/": b"<title>LizzieYzy Next</title>",
            r2_release.DEFAULT_PUBLIC_BASE + "/index.html": b"<title>LizzieYzy Next</title>",
            r2_release.DEFAULT_PUBLIC_BASE
            + "/channels/stable/catalog.json": b'{"releaseTag":"test"}\n',
            r2_release.DEFAULT_PUBLIC_BASE
            + "/channels/stable/update-envelope.json": b'{"payload":"signed"}\n',
        }

        def response_for(url, **kwargs):
            base_url = url.split("?", 1)[0]
            response = mock.Mock(
                status_code=200,
                url=url,
                headers={
                    "Content-Type": (
                        "text/html; charset=utf-8"
                        if base_url.endswith("/") or base_url.endswith("index.html")
                        else "application/json; charset=utf-8"
                    )
                },
            )
            response.iter_content.return_value = iter([bodies[base_url]])
            return response

        requests = mock.Mock()
        requests.RequestException = RuntimeError
        requests.get.side_effect = response_for
        with mock.patch.dict(sys.modules, {"requests": requests}), mock.patch.object(
            r2_release.time, "time", return_value=2
        ):
            r2_release.verify_public_stable_channel(
                r2_release.DEFAULT_PUBLIC_BASE,
                index_body=bodies[r2_release.DEFAULT_PUBLIC_BASE + "/"],
                catalog_body=bodies[
                    r2_release.DEFAULT_PUBLIC_BASE + "/channels/stable/catalog.json"
                ],
                envelope_body=bodies[
                    r2_release.DEFAULT_PUBLIC_BASE
                    + "/channels/stable/update-envelope.json"
                ],
            )

        self.assertEqual(4, requests.get.call_count)
        self.assertTrue(
            all("?r2-verify=2-1" in call.args[0] for call in requests.get.call_args_list)
        )

    def test_public_stable_channel_rejects_body_mismatch(self):
        def mismatched_response(url, **kwargs):
            response = mock.Mock(
                status_code=200,
                url=url,
                headers={"Content-Type": "text/html; charset=utf-8"},
            )
            response.iter_content.return_value = iter([b"stale homepage"])
            return response

        requests = mock.Mock()
        requests.RequestException = RuntimeError
        requests.get.side_effect = mismatched_response
        with mock.patch.dict(sys.modules, {"requests": requests}), mock.patch.object(
            r2_release.time, "time", return_value=3
        ), mock.patch.object(r2_release.time, "sleep") as sleep, self.assertRaisesRegex(
            r2_release.ReleaseError, "download homepage after 5 attempts"
        ):
            r2_release.verify_public_stable_channel(
                r2_release.DEFAULT_PUBLIC_BASE,
                index_body=b"fresh homepage",
                catalog_body=b"{}\n",
                envelope_body=b"{}\n",
            )

        self.assertEqual(r2_release.PUBLIC_VERIFY_ATTEMPTS, requests.get.call_count)
        self.assertEqual(r2_release.PUBLIC_VERIFY_ATTEMPTS - 1, sleep.call_count)

    def test_public_range_verification_retries_transient_failure(self):
        selected = r2_release.select_r2_assets(
            release(), r2_release.DEFAULT_PUBLIC_BASE
        )[:1]
        entry = selected[0]
        head = mock.Mock(
            status_code=200,
            headers={
                "Content-Length": str(entry.size),
                "Accept-Ranges": "bytes",
                "Cache-Control": "public, max-age=31536000, immutable",
                "Content-Disposition": f'attachment; filename="{entry.name}"',
            },
        )
        transient = mock.Mock(status_code=503, headers={})
        success = mock.Mock(
            status_code=206,
            headers={
                "Content-Length": "1",
                "Content-Range": f"bytes 0-0/{entry.size}",
            },
        )
        success.iter_content.return_value = iter([b"x"])
        requests = mock.Mock()
        requests.RequestException = RuntimeError
        requests.head.return_value = head
        requests.get.side_effect = [transient, success]

        with mock.patch.dict(sys.modules, {"requests": requests}), mock.patch.object(
            r2_release.time, "sleep"
        ) as sleep:
            r2_release.verify_public_objects(
                r2_release.DEFAULT_PUBLIC_BASE, selected
            )

        self.assertEqual(2, requests.get.call_count)
        self.assertTrue(requests.get.call_args.kwargs["stream"])
        self.assertEqual(
            {"Accept-Encoding": "identity"},
            requests.head.call_args.kwargs["headers"],
        )
        transient.iter_content.assert_not_called()
        transient.close.assert_called_once_with()
        success.close.assert_called_once_with()
        sleep.assert_called_once_with(2)

    def test_public_range_verification_fails_after_retry_budget(self):
        selected = r2_release.select_r2_assets(
            release(), r2_release.DEFAULT_PUBLIC_BASE
        )[:1]
        entry = selected[0]
        head = mock.Mock(
            status_code=200,
            headers={
                "Content-Length": str(entry.size),
                "Accept-Ranges": "bytes",
                "Cache-Control": "public, max-age=31536000, immutable",
                "Content-Disposition": f'attachment; filename="{entry.name}"',
            },
        )
        requests = mock.Mock()
        requests.RequestException = RuntimeError
        requests.head.return_value = head
        failed_range = mock.Mock(status_code=503, headers={})
        requests.get.return_value = failed_range

        with mock.patch.dict(sys.modules, {"requests": requests}), mock.patch.object(
            r2_release.time, "sleep"
        ) as sleep, self.assertRaisesRegex(
            r2_release.ReleaseError, "after 5 attempts"
        ):
            r2_release.verify_public_objects(
                r2_release.DEFAULT_PUBLIC_BASE, selected
            )

        self.assertEqual(r2_release.PUBLIC_VERIFY_ATTEMPTS, requests.get.call_count)
        failed_range.iter_content.assert_not_called()
        self.assertEqual(r2_release.PUBLIC_VERIFY_ATTEMPTS, failed_range.close.call_count)
        self.assertEqual(r2_release.PUBLIC_VERIFY_ATTEMPTS - 1, sleep.call_count)


if __name__ == "__main__":
    unittest.main()
