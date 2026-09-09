import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import katago_asset_catalog


class KataGoAssetCatalogTest(unittest.TestCase):
    def test_catalog_is_valid_and_b11_is_default(self):
        catalog = katago_asset_catalog.load_catalog(katago_asset_catalog.DEFAULT_CATALOG)
        default_model = catalog["models"][catalog["defaultModelId"]]

        self.assertEqual("1.18.1", catalog["katagoVersion"])
        self.assertEqual("kata1-tf3-b11c768-s11500M-d6163M.bin.gz", default_model["fileName"])
        self.assertEqual(211568937, default_model["sizeBytes"])
        self.assertTrue(default_model["bundled"])

    def test_cli_reads_a_scalar_path(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(Path(katago_asset_catalog.__file__)),
                "get",
                "assets.windows-nvidia.runtimeProfile",
            ],
            check=True,
            capture_output=True,
            text=True,
        )

        self.assertEqual("cuda12.8-cudnn9", completed.stdout.strip())

    def test_model_urls_preserve_release_fallback(self):
        catalog = katago_asset_catalog.load_catalog(katago_asset_catalog.DEFAULT_CATALOG)
        self.assertEqual(
            "https://media.katagotraining.org/uploaded/networks/models/kata1/"
            "kata1-tf3-b11c768-s11500M-d6163M.bin.gz",
            katago_asset_catalog.model_download_url(catalog, "b11-flagship"),
        )
        self.assertIn("/v1.17.1/", katago_asset_catalog.model_download_url(catalog, "b10-balanced"))

    def test_validation_rejects_untrusted_model_origin(self):
        catalog = katago_asset_catalog.load_catalog(katago_asset_catalog.DEFAULT_CATALOG)
        catalog["models"]["b11-flagship"]["downloadUrl"] = "https://example.com/model.bin.gz"
        with self.assertRaisesRegex(ValueError, "unsupported official downloadUrl"):
            katago_asset_catalog.validate_catalog(catalog)

    def test_validation_rejects_unpinned_asset(self):
        catalog = katago_asset_catalog.load_catalog(katago_asset_catalog.DEFAULT_CATALOG)
        catalog["assets"]["windows-cpu"]["sha256"] = "missing"
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "catalog.json"
            path.write_text(json.dumps(catalog), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "invalid sha256"):
                katago_asset_catalog.load_catalog(path)

    def test_validation_requires_unified_nvidia_executable_digest(self):
        catalog = katago_asset_catalog.load_catalog(katago_asset_catalog.DEFAULT_CATALOG)
        del catalog["assets"]["windows-nvidia"]["executableSha256"]
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "catalog.json"
            path.write_text(json.dumps(catalog), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "requires executableSha256"):
                katago_asset_catalog.load_catalog(path)


if __name__ == "__main__":
    unittest.main()
