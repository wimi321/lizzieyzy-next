package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlatformUpdateServiceTest {
  @Test
  void selectsExactArchitectureAndFlavorBeforeAllFallbacks() {
    UpdateManifest manifest =
        manifest(
            packageAsset("macos", "x64", "with-katago", "intel.dmg"),
            packageAsset("macos", "arm64", "all", "arm-all.dmg"),
            packageAsset("macos", "arm64", "with-katago", "arm.dmg"));

    UpdateManifest.PackageAsset selected =
        PlatformUpdateService.selectPackage(manifest, "macos", "arm64", "with-katago");

    assertEquals("arm.dmg", selected.assetName);
  }

  @Test
  void keepsLinuxFlavorAndRejectsWrongArchitecture() {
    UpdateManifest manifest =
        manifest(
            packageAsset("linux", "x64", "opencl", "linux-opencl.zip"),
            packageAsset("linux", "arm64", "opencl", "linux-arm.zip"));

    assertEquals(
        "linux-opencl.zip",
        PlatformUpdateService.selectPackage(manifest, "linux", "x64", "opencl").assetName);
    assertNull(PlatformUpdateService.selectPackage(manifest, "linux", "x64", "nvidia"));
  }

  private UpdateManifest manifest(JSONObject... packages) {
    JSONObject payload = SignedUpdateEnvelopeTest.validPayload();
    payload.put("packages", new JSONArray(List.of(packages)));
    return UpdateManifest.parse(payload);
  }

  private JSONObject packageAsset(String platform, String arch, String flavor, String name) {
    JSONObject json = new JSONObject();
    json.put("platform", platform);
    json.put("arch", arch);
    json.put("flavor", flavor);
    json.put("installMode", "download-archive");
    json.put("assetName", name);
    json.put("sizeBytes", 10L);
    json.put("sha256", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    json.put("downloadUrl", "https://download.goagent.top/" + name);
    json.put("mirrorUrls", new JSONArray());
    return json;
  }
}
