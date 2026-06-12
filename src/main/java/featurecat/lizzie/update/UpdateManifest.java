package featurecat.lizzie.update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public final class UpdateManifest {
  public static final int SUPPORTED_SCHEMA_VERSION = 1;

  private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");

  public final int schemaVersion;
  public final String releaseTag;
  public final String publishedAt;
  public final String notesUrl;
  public final String minUpdaterVersion;
  public final List<Component> components;

  private UpdateManifest(
      int schemaVersion,
      String releaseTag,
      String publishedAt,
      String notesUrl,
      String minUpdaterVersion,
      List<Component> components) {
    this.schemaVersion = schemaVersion;
    this.releaseTag = releaseTag;
    this.publishedAt = publishedAt;
    this.notesUrl = notesUrl;
    this.minUpdaterVersion = minUpdaterVersion;
    this.components = Collections.unmodifiableList(new ArrayList<>(components));
  }

  public static UpdateManifest parse(String rawJson) {
    if (isBlank(rawJson)) {
      throw new IllegalArgumentException("Update manifest is empty.");
    }
    return parse(new JSONObject(rawJson));
  }

  public static UpdateManifest parse(JSONObject json) {
    if (json == null) {
      throw new IllegalArgumentException("Update manifest is missing.");
    }
    int schemaVersion = json.optInt("schemaVersion", -1);
    if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported update manifest schema: " + schemaVersion);
    }
    String releaseTag = requiredString(json, "releaseTag");
    String publishedAt = requiredString(json, "publishedAt");
    String notesUrl = requiredString(json, "notesUrl");
    String minUpdaterVersion = requiredString(json, "minUpdaterVersion");
    JSONArray rawComponents = json.optJSONArray("components");
    if (rawComponents == null || rawComponents.isEmpty()) {
      throw new IllegalArgumentException("Update manifest must include at least one component.");
    }

    List<Component> components = new ArrayList<>();
    for (int i = 0; i < rawComponents.length(); i++) {
      JSONObject rawComponent = rawComponents.optJSONObject(i);
      if (rawComponent == null) {
        throw new IllegalArgumentException("Update manifest component " + i + " is not an object.");
      }
      components.add(Component.parse(rawComponent));
    }
    return new UpdateManifest(
        schemaVersion, releaseTag, publishedAt, notesUrl, minUpdaterVersion, components);
  }

  public JSONObject toJson() {
    JSONObject json = new JSONObject();
    json.put("schemaVersion", schemaVersion);
    json.put("releaseTag", releaseTag);
    json.put("publishedAt", publishedAt);
    json.put("notesUrl", notesUrl);
    json.put("minUpdaterVersion", minUpdaterVersion);
    JSONArray array = new JSONArray();
    for (Component component : components) {
      array.put(component.toJson());
    }
    json.put("components", array);
    return json;
  }

  static String requiredString(JSONObject json, String key) {
    String value = json.optString(key, "").trim();
    if (value.isEmpty()) {
      throw new IllegalArgumentException("Update manifest is missing " + key + ".");
    }
    return value;
  }

  static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static void validateAssetName(String id, String assetName) {
    if (assetName.contains("/")
        || assetName.contains("\\")
        || ".".equals(assetName)
        || "..".equals(assetName)) {
      throw new IllegalArgumentException("Update component " + id + " has unsafe assetName.");
    }
  }

  public static final class Component {
    public final String id;
    public final String platform;
    public final String flavor;
    public final String version;
    public final String assetName;
    public final String downloadUrl;
    public final long sizeBytes;
    public final String sha256;
    public final String installAction;
    public final boolean defaultSelectedIfChanged;
    public final List<String> mirrorUrls;

    private Component(
        String id,
        String platform,
        String flavor,
        String version,
        String assetName,
        String downloadUrl,
        long sizeBytes,
        String sha256,
        String installAction,
        boolean defaultSelectedIfChanged,
        List<String> mirrorUrls) {
      this.id = id;
      this.platform = platform;
      this.flavor = flavor;
      this.version = version;
      this.assetName = assetName;
      this.downloadUrl = downloadUrl;
      this.sizeBytes = sizeBytes;
      this.sha256 = sha256;
      this.installAction = installAction;
      this.defaultSelectedIfChanged = defaultSelectedIfChanged;
      this.mirrorUrls = Collections.unmodifiableList(new ArrayList<>(mirrorUrls));
    }

    static Component parse(JSONObject json) {
      String id = requiredString(json, "id");
      String platform = requiredString(json, "platform").toLowerCase(Locale.ROOT);
      String flavor = requiredString(json, "flavor").toLowerCase(Locale.ROOT);
      String version = requiredString(json, "version");
      String assetName = requiredString(json, "assetName");
      validateAssetName(id, assetName);
      String downloadUrl = requiredString(json, "downloadUrl");
      long sizeBytes = json.optLong("sizeBytes", -1L);
      if (sizeBytes < 0L) {
        throw new IllegalArgumentException("Update component " + id + " has invalid sizeBytes.");
      }
      String sha256 = requiredString(json, "sha256").toLowerCase(Locale.ROOT);
      if (!SHA256_PATTERN.matcher(sha256).matches()) {
        throw new IllegalArgumentException("Update component " + id + " has invalid sha256.");
      }
      String installAction = requiredString(json, "installAction");
      boolean defaultSelectedIfChanged = json.optBoolean("defaultSelectedIfChanged", false);
      List<String> mirrorUrls = new ArrayList<>();
      JSONArray rawMirrors = json.optJSONArray("mirrorUrls");
      if (rawMirrors != null) {
        for (int i = 0; i < rawMirrors.length(); i++) {
          String mirror = rawMirrors.optString(i, "").trim();
          if (!mirror.isEmpty()) {
            mirrorUrls.add(mirror);
          }
        }
      }
      return new Component(
          id,
          platform,
          flavor,
          version,
          assetName,
          downloadUrl,
          sizeBytes,
          sha256,
          installAction,
          defaultSelectedIfChanged,
          mirrorUrls);
    }

    public boolean matches(String targetPlatform, String targetFlavor) {
      String normalizedPlatform = targetPlatform == null ? "" : targetPlatform.toLowerCase(Locale.ROOT);
      String normalizedFlavor = targetFlavor == null ? "" : targetFlavor.toLowerCase(Locale.ROOT);
      return platform.equals(normalizedPlatform)
          && ("all".equals(flavor) || flavor.equals(normalizedFlavor));
    }

    public JSONObject toJson() {
      JSONObject json = new JSONObject();
      json.put("id", id);
      json.put("platform", platform);
      json.put("flavor", flavor);
      json.put("version", version);
      json.put("assetName", assetName);
      json.put("downloadUrl", downloadUrl);
      json.put("sizeBytes", sizeBytes);
      json.put("sha256", sha256);
      json.put("installAction", installAction);
      json.put("defaultSelectedIfChanged", defaultSelectedIfChanged);
      JSONArray mirrors = new JSONArray();
      for (String mirrorUrl : mirrorUrls) {
        mirrors.put(mirrorUrl);
      }
      json.put("mirrorUrls", mirrors);
      return json;
    }
  }
}
