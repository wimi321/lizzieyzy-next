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

    def test_catalog_uses_github_during_maintenance_and_r2_after_activation(self):
        source = release()
        selected = r2_release.select_r2_assets(source, r2_release.DEFAULT_PUBLIC_BASE)
        stable = r2_release.build_catalog(
            source, selected, r2_release.DEFAULT_PUBLIC_BASE
        )
        maintenance = r2_release.build_catalog(
            source,
            selected,
            r2_release.DEFAULT_PUBLIC_BASE,
            github_primary=True,
        )

        self.assertTrue(
            all(
                entry["downloadUrl"].startswith("https://download.goagent.top/")
                for entry in stable["assets"]
            )
        )
        self.assertTrue(
            all(
                entry["downloadUrl"].startswith("https://github.com/")
                for entry in maintenance["assets"]
            )
        )
        self.assertTrue(all(entry["mirrorUrls"] for entry in stable["assets"]))
        self.assertTrue(
            all(entry["mirrorUrls"] == [] for entry in maintenance["assets"])
        )

    def test_backend_index_is_only_a_lightweight_official_site_redirect(self):
        page = r2_release.render_redirect_index()

        self.assertIn(r2_release.DEFAULT_WEBSITE_DOWNLOAD_URL, page)
        self.assertIn('rel="canonical"', page)
        self.assertIn("window.location.replace", page)
        self.assertNotIn("选择你的版本", page)
        self.assertNotIn("TensorRT", page)
        self.assertNotIn("download-action", page)

    def test_stable_release_body_keeps_github_links_and_recommends_official_page(self):
        source = release()
        selected = r2_release.select_r2_assets(source, r2_release.DEFAULT_PUBLIC_BASE)
        linked = selected[0]
        source["body"] = (
            "# LizzieYzy Next\n\n"
            f"[{linked.name}]({r2_release.r2_url(r2_release.DEFAULT_PUBLIC_BASE, linked)})\n"
        )

        updated = r2_release.stable_release_body(
            source,
            selected,
            r2_release.DEFAULT_PUBLIC_BASE,
            r2_release.DEFAULT_WEBSITE_DOWNLOAD_URL,
        )
        repeated = r2_release.stable_release_body(
            {**source, "body": updated},
            selected,
            r2_release.DEFAULT_PUBLIC_BASE,
            r2_release.DEFAULT_WEBSITE_DOWNLOAD_URL,
        )

        self.assertIn(linked.browser_url, updated)
        self.assertNotIn(r2_release.r2_url(r2_release.DEFAULT_PUBLIC_BASE, linked), updated)
        self.assertIn("国内用户建议从", updated)
        self.assertIn("official download page", updated)
        self.assertNotIn("Cloudflare", updated)
        self.assertNotIn("R2 连接", updated)
        self.assertEqual(1, updated.count(r2_release.RELEASE_NOTE_START))
        self.assertEqual(
            2, updated.count(r2_release.DEFAULT_WEBSITE_DOWNLOAD_URL)
        )
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
            "render_redirect_index",
            return_value="<title>Redirect</title>",
        ), mock.patch.object(
            r2_release,
            "verify_public_download_redirects",
            side_effect=lambda public_base, website_url: events.append(
                ("verify-redirect", public_base, website_url)
            ),
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
                r2_release.DEFAULT_WEBSITE_DOWNLOAD_URL,
                [object()],
                {"tag": TAG},
                {"payload": "signed"},
                skip_public_verify=False,
            )

        self.assertEqual("verify", events[0][0])
        self.assertEqual("verify-redirect", events[1][0])
        self.assertEqual(
            "channels/stable/update-envelope.json",
            [event for event in events if event[0] == "put"][-1][1],
        )
        self.assertEqual("verify-channel", events[-1][0])
        self.assertEqual({"tag": TAG}, json.loads(catalog_body))
        self.assertEqual({"payload": "signed"}, json.loads(envelope_body))

    def test_maintenance_catalog_is_public_before_release_assets_can_change(self):
        source = release()
        selected = r2_release.select_r2_assets(source, r2_release.DEFAULT_PUBLIC_BASE)
        maintenance = r2_release.build_catalog(
            source,
            selected,
            r2_release.DEFAULT_PUBLIC_BASE,
            github_primary=True,
        )
        events = []

        def put(client, bucket, key, body, **kwargs):
            events.append(("put", key, kwargs["cache_control"]))

        with mock.patch.object(r2_release, "put_bytes", side_effect=put), mock.patch.object(
            r2_release,
            "verify_public_download_redirects",
            side_effect=lambda public_base, website_url: events.append(
                ("verify-redirect", public_base, website_url)
            ),
        ), mock.patch.object(
            r2_release,
            "verify_public_catalog",
            side_effect=lambda public_base, body: events.append(
                ("verify-catalog", public_base, json.loads(body))
            ),
        ):
            body = r2_release.publish_maintenance_catalog(
                object(),
                "bucket",
                r2_release.DEFAULT_PUBLIC_BASE,
                r2_release.DEFAULT_WEBSITE_DOWNLOAD_URL,
                maintenance,
                skip_public_verify=False,
            )

        self.assertEqual("channels/stable/catalog.json", events[0][1])
        self.assertEqual("no-store", events[0][2])
        self.assertEqual("index.html", events[1][1])
        self.assertEqual("verify-redirect", events[2][0])
        self.assertEqual("verify-catalog", events[3][0])
        self.assertTrue(
            all(
                entry["downloadUrl"].startswith("https://github.com/")
                for entry in json.loads(body)["assets"]
            )
        )

    def test_public_backend_roots_permanently_redirect_and_preserve_query(self):
        def response_for(url, **kwargs):
            query = url.split("?", 1)[1]
            return mock.Mock(
                status_code=301,
                headers={
                    "Location": r2_release.DEFAULT_WEBSITE_DOWNLOAD_URL + "?" + query
                },
            )

        requests = mock.Mock()
        requests.RequestException = RuntimeError
        requests.get.side_effect = response_for

        with mock.patch.dict(sys.modules, {"requests": requests}), mock.patch.object(
            r2_release.time, "time", return_value=1
        ):
            r2_release.verify_public_download_redirects(
                r2_release.DEFAULT_PUBLIC_BASE,
                r2_release.DEFAULT_WEBSITE_DOWNLOAD_URL,
            )

        self.assertEqual(2, requests.get.call_count)
        self.assertEqual(
            [
                r2_release.DEFAULT_PUBLIC_BASE + "/?r2-verify=1-1",
                r2_release.DEFAULT_PUBLIC_BASE + "/index.html?r2-verify=1-1",
            ],
            [call.args[0] for call in requests.get.call_args_list],
        )
        self.assertTrue(
            all(
                call.kwargs["allow_redirects"] is False
                for call in requests.get.call_args_list
            )
        )

    def test_public_stable_channel_matches_exact_uploaded_bodies(self):
        bodies = {
            r2_release.DEFAULT_PUBLIC_BASE
            + "/channels/stable/catalog.json": b'{"releaseTag":"test"}\n',
            r2_release.DEFAULT_PUBLIC_BASE
            + "/channels/stable/update-envelope.json": b'{"payload":"signed"}\n',
        }

        def response_for(url, **kwargs):
            base_url = url.split("?", 1)[0]
            if base_url.endswith("/") or base_url.endswith("/index.html"):
                query = url.split("?", 1)[1]
                return mock.Mock(
                    status_code=301,
                    headers={
                        "Location": r2_release.DEFAULT_WEBSITE_DOWNLOAD_URL
                        + "?"
                        + query
                    },
                )
            response = mock.Mock(
                status_code=200,
                url=url,
                headers={"Content-Type": "application/json; charset=utf-8"},
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
                website_download_url=r2_release.DEFAULT_WEBSITE_DOWNLOAD_URL,
                catalog_body=bodies[
                    r2_release.DEFAULT_PUBLIC_BASE + "/channels/stable/catalog.json"
                ],
                envelope_body=bodies[
                    r2_release.DEFAULT_PUBLIC_BASE
                    + "/channels/stable/update-envelope.json"
                ],
            )

        self.assertEqual(4, requests.get.call_count)
        self.assertTrue(all("?r2-verify=2-1" in call.args[0] for call in requests.get.call_args_list))

    def test_public_stable_channel_rejects_body_mismatch(self):
        def mismatched_response(url, **kwargs):
            base_url = url.split("?", 1)[0]
            if base_url.endswith("/") or base_url.endswith("/index.html"):
                query = url.split("?", 1)[1]
                return mock.Mock(
                    status_code=301,
                    headers={
                        "Location": r2_release.DEFAULT_WEBSITE_DOWNLOAD_URL
                        + "?"
                        + query
                    },
                )
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
            r2_release.ReleaseError, "stable catalog after 5 attempts"
        ):
            r2_release.verify_public_stable_channel(
                r2_release.DEFAULT_PUBLIC_BASE,
                website_download_url=r2_release.DEFAULT_WEBSITE_DOWNLOAD_URL,
                catalog_body=b"{}\n",
                envelope_body=b"{}\n",
            )

        self.assertEqual(2 + r2_release.PUBLIC_VERIFY_ATTEMPTS, requests.get.call_count)
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
