package featurecat.lizzie.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Trusted KataGo release assets shared by runtime setup and release packaging. */
public final class KataGoAssetCatalog {
  private static final String RESOURCE_NAME = "/katago-assets.json";
  private static final KataGoAssetCatalog INSTANCE = loadResource();

  private final String katagoVersion;
  private final String katagoReleaseTag;
  private final String katagoSourceCommit;
  private final String modelReleaseTag;
  private final String defaultModelId;
  private final Map<String, Model> models;
  private final Map<String, Asset> assets;

  private KataGoAssetCatalog(JSONObject root) {
    int schemaVersion = root.getInt("schemaVersion");
    if (schemaVersion != 1) {
      throw new IllegalStateException("Unsupported KataGo asset catalog schema: " + schemaVersion);
    }
    katagoVersion = required(root, "katagoVersion");
    katagoReleaseTag = required(root, "katagoReleaseTag");
    katagoSourceCommit = required(root, "katagoSourceCommit");
    modelReleaseTag = required(root, "modelReleaseTag");
    defaultModelId = required(root, "defaultModelId");
    models = Collections.unmodifiableMap(parseModels(root.getJSONObject("models")));
    assets = Collections.unmodifiableMap(parseAssets(root.getJSONObject("assets")));
    if (!models.containsKey(defaultModelId)) {
      throw new IllegalStateException("Unknown default KataGo model: " + defaultModelId);
    }
    Asset windowsNvidia = assets.get("windows-nvidia");
    if (windowsNvidia == null || windowsNvidia.executableSha256().isEmpty()) {
      throw new IllegalStateException(
          "KataGo asset catalog requires windows-nvidia executableSha256");
    }
    if (!assets.containsKey("windows-tensorrt")) {
      throw new IllegalStateException("KataGo asset catalog requires windows-tensorrt");
    }
  }

  public static KataGoAssetCatalog get() {
    return INSTANCE;
  }

  public String katagoVersion() {
    return katagoVersion;
  }

  public String katagoReleaseTag() {
    return katagoReleaseTag;
  }

  public String katagoSourceCommit() {
    return katagoSourceCommit;
  }

  public String modelReleaseTag() {
    return modelReleaseTag;
  }

  public Model defaultModel() {
    return model(defaultModelId);
  }

  public Model model(String id) {
    Model model = models.get(id);
    if (model == null) {
      throw new IllegalArgumentException("Unknown KataGo model id: " + id);
    }
    return model;
  }

  public Asset asset(String id) {
    Asset asset = assets.get(id);
    if (asset == null) {
      throw new IllegalArgumentException("Unknown KataGo asset id: " + id);
    }
    return asset;
  }

  public Map<String, Model> models() {
    return models;
  }

  public Map<String, Asset> assets() {
    return assets;
  }

  public String modelDownloadUrl(Model model) {
    return model.downloadUrl().isEmpty()
        ? releaseUrl(modelReleaseTag, model.fileName())
        : model.downloadUrl();
  }

  public String assetDownloadUrl(Asset asset) {
    return releaseUrl(katagoReleaseTag, asset.assetName());
  }

  private static String releaseUrl(String tag, String fileName) {
    return "https://github.com/lightvector/KataGo/releases/download/" + tag + "/" + fileName;
  }

  private static Map<String, Model> parseModels(JSONObject values) {
    Map<String, Model> parsed = new LinkedHashMap<>();
    for (String id : values.keySet()) {
      JSONObject value = values.getJSONObject(id);
      parsed.put(
          id,
          new Model(
              id,
              required(value, "fileName"),
              required(value, "displayFamily"),
              required(value, "tier"),
              required(value, "architecture"),
              required(value, "minimumKataGoVersion"),
              value.getLong("sizeBytes"),
              requiredSha256(value, "sha256"),
              value.optBoolean("bundled", false),
              modelDownloadOverride(value),
              value.optString("publishedAt", "2026-07-29")));
    }
    return parsed;
  }

  private static String modelDownloadOverride(JSONObject value) {
    String url = value.optString("downloadUrl", "").trim();
    if (!url.isEmpty()
        && !url.equals(
            "https://media.katagotraining.org/uploaded/networks/models/kata1/"
                + required(value, "fileName"))) {
      throw new IllegalStateException("Unsupported official model download URL");
    }
    return url;
  }

  private static Map<String, Asset> parseAssets(JSONObject values) {
    Map<String, Asset> parsed = new LinkedHashMap<>();
    for (String id : values.keySet()) {
      JSONObject value = values.getJSONObject(id);
      parsed.put(
          id,
          new Asset(
              id,
              required(value, "platform"),
              required(value, "backend"),
              required(value, "assetName"),
              value.getLong("sizeBytes"),
              requiredSha256(value, "sha256"),
              optionalSha256(value, "executableSha256"),
              value.optString("runtimeProfile", ""),
              value.optString("gpuFamily", ""),
              required(value, "releaseTier")));
    }
    return parsed;
  }

  private static String required(JSONObject value, String key) {
    String result = value.optString(key, "").trim();
    if (result.isEmpty()) {
      throw new IllegalStateException("Missing KataGo asset catalog field: " + key);
    }
    return result;
  }

  private static String requiredSha256(JSONObject value, String key) {
    String sha256 = required(value, key).toLowerCase();
    validateSha256(key, sha256);
    return sha256;
  }

  private static String optionalSha256(JSONObject value, String key) {
    String sha256 = value.optString(key, "").trim().toLowerCase();
    if (!sha256.isEmpty()) {
      validateSha256(key, sha256);
    }
    return sha256;
  }

  private static void validateSha256(String key, String sha256) {
    if (!sha256.matches("[0-9a-f]{64}")) {
      throw new IllegalStateException("Invalid KataGo asset catalog SHA-256 for " + key);
    }
  }

  private static KataGoAssetCatalog loadResource() {
    try (InputStream input = KataGoAssetCatalog.class.getResourceAsStream(RESOURCE_NAME)) {
      if (input == null) {
        throw new IllegalStateException("Missing bundled KataGo asset catalog: " + RESOURCE_NAME);
      }
      String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      return new KataGoAssetCatalog(new JSONObject(json));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load bundled KataGo asset catalog", e);
    }
  }

  public record Model(
      String id,
      String fileName,
      String displayFamily,
      String tier,
      String architecture,
      String minimumKataGoVersion,
      long sizeBytes,
      String sha256,
      boolean bundled,
      String downloadUrl,
      String publishedAt) {
    public String modelName() {
      return fileName.endsWith(".bin.gz")
          ? fileName.substring(0, fileName.length() - ".bin.gz".length())
          : fileName;
    }
  }

  public record Asset(
      String id,
      String platform,
      String backend,
      String assetName,
      long sizeBytes,
      String sha256,
      String executableSha256,
      String runtimeProfile,
      String gpuFamily,
      String releaseTier) {}
}
