package featurecat.lizzie.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class InstalledUpdateState {
  public static final String INSTALLED_MANIFEST_NAME = "lizzieyzy-next-installed-manifest.json";

  public final String releaseTag;
  public final String platform;
  public final String flavor;
  private final Map<String, ComponentState> components;

  private InstalledUpdateState(
      String releaseTag, String platform, String flavor, Map<String, ComponentState> components) {
    this.releaseTag = releaseTag;
    this.platform = platform;
    this.flavor = flavor;
    this.components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
  }

  public static InstalledUpdateState empty(String currentVersion, String platform, String flavor) {
    return new InstalledUpdateState(
        currentVersion, normalize(platform), normalize(flavor), new LinkedHashMap<>());
  }

  public static InstalledUpdateState read(Path appDir, String currentVersion, String fallbackFlavor)
      throws IOException {
    Path manifestPath = appDir == null ? null : appDir.resolve(INSTALLED_MANIFEST_NAME);
    if (manifestPath == null || !Files.isRegularFile(manifestPath)) {
      return empty(currentVersion, "windows", fallbackFlavor);
    }
    String raw = Files.readString(manifestPath, StandardCharsets.UTF_8);
    return parse(new JSONObject(raw), currentVersion, fallbackFlavor);
  }

  public static InstalledUpdateState parse(
      JSONObject json, String currentVersion, String fallbackFlavor) {
    if (json == null) {
      return empty(currentVersion, "windows", fallbackFlavor);
    }
    String releaseTag = json.optString("releaseTag", currentVersion).trim();
    if (releaseTag.isEmpty()) {
      releaseTag = currentVersion;
    }
    String platform = json.optString("platform", "windows");
    String flavor = json.optString("flavor", fallbackFlavor);
    Map<String, ComponentState> components = new LinkedHashMap<>();
    JSONArray rawComponents = json.optJSONArray("components");
    if (rawComponents != null) {
      for (int i = 0; i < rawComponents.length(); i++) {
        JSONObject raw = rawComponents.optJSONObject(i);
        if (raw == null) {
          continue;
        }
        ComponentState component = ComponentState.parse(raw);
        if (component != null) {
          components.put(component.id, component);
        }
      }
    }
    return new InstalledUpdateState(releaseTag, normalize(platform), normalize(flavor), components);
  }

  public ComponentState component(String id) {
    return components.get(id);
  }

  public Map<String, ComponentState> components() {
    return components;
  }

  public JSONObject toJson() {
    JSONObject json = new JSONObject();
    json.put("schemaVersion", UpdateManifest.SUPPORTED_SCHEMA_VERSION);
    json.put("releaseTag", releaseTag);
    json.put("platform", platform);
    json.put("flavor", flavor);
    JSONArray array = new JSONArray();
    for (ComponentState component : components.values()) {
      array.put(component.toJson());
    }
    json.put("components", array);
    return json;
  }

  static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  public static final class ComponentState {
    public final String id;
    public final String version;
    public final String sha256;

    public ComponentState(String id, String version, String sha256) {
      this.id = normalize(id);
      this.version = version == null ? "" : version.trim();
      this.sha256 = sha256 == null ? "" : sha256.trim().toLowerCase(Locale.ROOT);
    }

    static ComponentState parse(JSONObject json) {
      String id = json.optString("id", "").trim();
      if (id.isEmpty()) {
        return null;
      }
      return new ComponentState(
          id, json.optString("version", ""), json.optString("sha256", ""));
    }

    public boolean matches(UpdateManifest.Component component) {
      return component != null
          && id.equals(normalize(component.id))
          && version.equals(component.version)
          && (sha256.isEmpty() || sha256.equalsIgnoreCase(component.sha256));
    }

    JSONObject toJson() {
      JSONObject json = new JSONObject();
      json.put("id", id);
      json.put("version", version);
      json.put("sha256", sha256);
      return json;
    }
  }
}
