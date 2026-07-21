package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class UpdateManifestTest {
  @Test
  void parsesValidManifest() {
    UpdateManifest manifest = UpdateManifest.parse(validManifest().toString());

    assertEquals("next-2026-06-12.1", manifest.releaseTag);
    assertFalse(manifest.prerelease);
    assertEquals(1, manifest.components.size());
    UpdateManifest.Component core = manifest.components.get(0);
    assertEquals("core", core.id);
    assertTrue(core.matches("windows", "opencl"));
  }

  @Test
  void parsesPrereleaseFlagAndKeepsLegacyManifestsStable() {
    JSONObject prerelease = validManifest().put("prerelease", true);
    assertTrue(UpdateManifest.parse(prerelease).prerelease);

    JSONObject legacy = validManifest();
    legacy.remove("prerelease");
    assertFalse(UpdateManifest.parse(legacy).prerelease);
  }

  @Test
  void rejectsBadSha256() {
    JSONObject manifest = validManifest();
    manifest.getJSONArray("components").getJSONObject(0).put("sha256", "bad");

    assertThrows(IllegalArgumentException.class, () -> UpdateManifest.parse(manifest));
  }

  @Test
  void rejectsMissingComponents() {
    JSONObject manifest = validManifest();
    manifest.put("components", new JSONArray());

    assertThrows(IllegalArgumentException.class, () -> UpdateManifest.parse(manifest));
  }

  @Test
  void rejectsUnsafeAssetName() {
    JSONObject traversalManifest = validManifest();
    traversalManifest
        .getJSONArray("components")
        .getJSONObject(0)
        .put("assetName", "../evil.zip");
    assertThrows(IllegalArgumentException.class, () -> UpdateManifest.parse(traversalManifest));

    JSONObject windowsPathManifest = validManifest();
    windowsPathManifest
        .getJSONArray("components")
        .getJSONObject(0)
        .put("assetName", "dir\\evil.zip");
    assertThrows(IllegalArgumentException.class, () -> UpdateManifest.parse(windowsPathManifest));
  }

  static JSONObject validManifest() {
    JSONObject component = new JSONObject();
    component.put("id", "core");
    component.put("platform", "windows");
    component.put("flavor", "all");
    component.put("version", "next-2026-06-12.1");
    component.put("assetName", "2026-06-12-windows64.core-update.zip");
    component.put("downloadUrl", "https://example.test/core.zip");
    component.put("sizeBytes", 123);
    component.put("sha256", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    component.put("installAction", "replace-core");
    component.put("defaultSelectedIfChanged", true);

    JSONObject manifest = new JSONObject();
    manifest.put("schemaVersion", 1);
    manifest.put("releaseTag", "next-2026-06-12.1");
    manifest.put("publishedAt", "2026-06-12T00:00:00Z");
    manifest.put("notesUrl", "https://example.test/release");
    manifest.put("minUpdaterVersion", "1");
    manifest.put("prerelease", false);
    manifest.put("components", new JSONArray().put(component));
    return manifest;
  }
}
