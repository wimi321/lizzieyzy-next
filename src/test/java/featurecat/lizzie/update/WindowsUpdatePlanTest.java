package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class WindowsUpdatePlanTest {
  @Test
  void selectsCoreAndChangedMatchingResourceOnly() {
    JSONObject manifestJson = UpdateManifestTest.validManifest();
    JSONArray components = manifestJson.getJSONArray("components");
    components.put(component("katago-opencl", "opencl", "katago-v2", true));
    components.put(component("katago-nvidia", "nvidia", "katago-v2", true));
    UpdateManifest manifest = UpdateManifest.parse(manifestJson);
    InstalledUpdateState installed =
        InstalledUpdateState.parse(
            new JSONObject()
                .put("releaseTag", "next-2026-06-11.1")
                .put("platform", "windows")
                .put("flavor", "opencl")
                .put(
                    "components",
                    new JSONArray()
                        .put(componentState("core", "next-2026-06-11.1"))
                        .put(componentState("katago-opencl", "katago-v1"))),
            "next-2026-06-11.1",
            "opencl");

    WindowsUpdatePlan plan =
        WindowsUpdatePlan.create(manifest, installed, "next-2026-06-11.1", "opencl");

    assertTrue(plan.hasUpdate());
    assertEquals(2, plan.selectedItems().size());
    assertEquals("core", plan.selectedItems().get(0).component.id);
    assertEquals("katago-opencl", plan.selectedItems().get(1).component.id);
  }

  @Test
  void skipsUnchangedResourceComponents() {
    JSONObject manifestJson = UpdateManifestTest.validManifest();
    manifestJson.getJSONArray("components").put(component("katago-opencl", "opencl", "katago-v1", true));
    UpdateManifest manifest = UpdateManifest.parse(manifestJson);
    InstalledUpdateState installed =
        InstalledUpdateState.parse(
            new JSONObject()
                .put("releaseTag", "next-2026-06-11.1")
                .put("platform", "windows")
                .put("flavor", "opencl")
                .put(
                    "components",
                    new JSONArray()
                        .put(componentState("core", "next-2026-06-11.1"))
                        .put(componentState("katago-opencl", "katago-v1"))),
            "next-2026-06-11.1",
            "opencl");

    WindowsUpdatePlan plan =
        WindowsUpdatePlan.create(manifest, installed, "next-2026-06-11.1", "opencl");

    assertEquals(1, plan.selectedItems().size());
    assertEquals("core", plan.selectedItems().get(0).component.id);
  }

  @Test
  void developmentBuildDoesNotPlanUpdate() {
    UpdateManifest manifest = UpdateManifest.parse(UpdateManifestTest.validManifest());

    WindowsUpdatePlan plan =
        WindowsUpdatePlan.create(
            manifest, InstalledUpdateState.empty("next-dev", "windows", "opencl"), "next-dev", "opencl");

    assertFalse(plan.hasUpdate());
  }

  private JSONObject component(String id, String flavor, String version, boolean selected) {
    JSONObject component = new JSONObject();
    component.put("id", id);
    component.put("platform", "windows");
    component.put("flavor", flavor);
    component.put("version", version);
    component.put("assetName", id + ".zip");
    component.put("downloadUrl", "https://example.test/" + id + ".zip");
    component.put("sizeBytes", 100);
    component.put("sha256", "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd");
    component.put("installAction", "replace-app-path");
    component.put("defaultSelectedIfChanged", selected);
    return component;
  }

  private JSONObject componentState(String id, String version) {
    JSONObject state = new JSONObject();
    state.put("id", id);
    state.put("version", version);
    state.put("sha256", "");
    return state;
  }
}
