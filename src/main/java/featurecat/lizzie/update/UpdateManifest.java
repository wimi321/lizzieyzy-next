package featurecat.lizzie.update;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Parsed update metadata. Schema v1 remains available for old clients and explicit test URLs. */
public final class UpdateManifest {
  public static final int LEGACY_SCHEMA_VERSION = 1;
  public static final int SUPPORTED_SCHEMA_VERSION = 2;

  private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");

  public final int schemaVersion;
  public final String releaseTag;
  public final String publishedAt;
  public final String notesUrl;
  public final String minUpdaterVersion;
  public final boolean prerelease;
  public final List<Component> components;
  public final List<PackageAsset> packages;

  private UpdateManifest(
      int schemaVersion,
      String releaseTag,
      String publishedAt,
      String notesUrl,
      String minUpdaterVersion,
      boolean prerelease,
      List<Component> components,
      List<PackageAsset> packages) {
    this.schemaVersion = schemaVersion;
    this.releaseTag = releaseTag;
    this.publishedAt = publishedAt;
    this.notesUrl = notesUrl;
    this.minUpdaterVersion = minUpdaterVersion;
    this.prerelease = prerelease;
    this.components = Collections.unmodifiableList(new ArrayList<>(components));
    this.packages = Collections.unmodifiableList(new ArrayList<>(packages));
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
    if (schemaVersion != LEGACY_SCHEMA_VERSION && schemaVersion != SUPPORTED_SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported update manifest schema: " + schemaVersion);
    }
    String releaseTag = requiredString(json, "releaseTag");
    String publishedAt = requiredString(json, "publishedAt");
    String notesUrl = requiredUrl(json, "notesUrl");
    String minUpdaterVersion = requiredString(json, "minUpdaterVersion");
    boolean prerelease = json.optBoolean("prerelease", false);

    List<Component> components = parseComponents(json.optJSONArray("components"));
    List<PackageAsset> packages = parsePackages(json.optJSONArray("packages"));
    if (schemaVersion == LEGACY_SCHEMA_VERSION && components.isEmpty()) {
      throw new IllegalArgumentException("Update manifest must include at least one component.");
    }
    if (schemaVersion == SUPPORTED_SCHEMA_VERSION && components.isEmpty() && packages.isEmpty()) {
      throw new IllegalArgumentException("Update manifest must include components or packages.");
    }
    return new UpdateManifest(
        schemaVersion,
        releaseTag,
        publishedAt,
        notesUrl,
        minUpdaterVersion,
        prerelease,
        components,
        packages);
  }

  private static List<Component> parseComponents(JSONArray rawComponents) {
    List<Component> components = new ArrayList<>();
    if (rawComponents == null) {
      return components;
    }
    for (int i = 0; i < rawComponents.length(); i++) {
      JSONObject rawComponent = rawComponents.optJSONObject(i);
      if (rawComponent == null) {
        throw new IllegalArgumentException("Update manifest component " + i + " is not an object.");
      }
      components.add(Component.parse(rawComponent));
    }
    return components;
  }

  private static List<PackageAsset> parsePackages(JSONArray rawPackages) {
    List<PackageAsset> packages = new ArrayList<>();
    if (rawPackages == null) {
      return packages;
    }
    for (int i = 0; i < rawPackages.length(); i++) {
      JSONObject rawPackage = rawPackages.optJSONObject(i);
      if (rawPackage == null) {
        throw new IllegalArgumentException("Update manifest package " + i + " is not an object.");
      }
      packages.add(PackageAsset.parse(rawPackage));
    }
    return packages;
  }

  public JSONObject toJson() {
    JSONObject json = new JSONObject();
    json.put("schemaVersion", schemaVersion);
    json.put("releaseTag", releaseTag);
    json.put("publishedAt", publishedAt);
    json.put("notesUrl", notesUrl);
    json.put("minUpdaterVersion", minUpdaterVersion);
    json.put("prerelease", prerelease);
    JSONArray componentArray = new JSONArray();
    for (Component component : components) {
      componentArray.put(component.toJson());
    }
    json.put("components", componentArray);
    if (schemaVersion >= SUPPORTED_SCHEMA_VERSION || !packages.isEmpty()) {
      JSONArray packageArray = new JSONArray();
      for (PackageAsset packageAsset : packages) {
        packageArray.put(packageAsset.toJson());
      }
      json.put("packages", packageArray);
    }
    return json;
  }

  static String requiredString(JSONObject json, String key) {
    String value = json.optString(key, "").trim();
    if (value.isEmpty()) {
      throw new IllegalArgumentException("Update manifest is missing " + key + ".");
    }
    return value;
  }

  static String requiredUrl(JSONObject json, String key) {
    String value = requiredString(json, key);
    validateUrl(value, key);
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
      throw new IllegalArgumentException("Update asset " + id + " has unsafe assetName.");
    }
  }

  private static void validateUrl(String value, String field) {
    try {
      URI uri = URI.create(value);
      String scheme = uri.getScheme();
      if (uri.getHost() == null
          || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
        throw new IllegalArgumentException();
      }
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Update manifest has invalid " + field + ".", e);
    }
  }

  static List<String> parseMirrors(JSONObject json) {
    List<String> mirrorUrls = new ArrayList<>();
    JSONArray rawMirrors = json.optJSONArray("mirrorUrls");
    if (rawMirrors == null) {
      return mirrorUrls;
    }
    for (int i = 0; i < rawMirrors.length(); i++) {
      String mirror = rawMirrors.optString(i, "").trim();
      if (!mirror.isEmpty()) {
        validateUrl(mirror, "mirrorUrls[" + i + "]");
        mirrorUrls.add(mirror);
      }
    }
    return mirrorUrls;
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
      String downloadUrl = requiredUrl(json, "downloadUrl");
      long sizeBytes = json.optLong("sizeBytes", -1L);
      validateSizeAndSha(id, sizeBytes, requiredString(json, "sha256"));
      String sha256 = requiredString(json, "sha256").toLowerCase(Locale.ROOT);
      String installAction = requiredString(json, "installAction");
      boolean defaultSelectedIfChanged = json.optBoolean("defaultSelectedIfChanged", false);
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
          parseMirrors(json));
    }

    public boolean matches(String targetPlatform, String targetFlavor) {
      String normalizedPlatform = normalize(targetPlatform);
      String normalizedFlavor = normalize(targetFlavor);
      return platform.equals(normalizedPlatform)
          && ("all".equals(flavor) || flavor.equals(normalizedFlavor));
    }

    public List<String> downloadUrls() {
      List<String> urls = new ArrayList<>();
      urls.add(downloadUrl);
      urls.addAll(mirrorUrls);
      return Collections.unmodifiableList(urls);
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
      json.put("mirrorUrls", new JSONArray(mirrorUrls));
      return json;
    }
  }

  public static final class PackageAsset {
    public final String platform;
    public final String arch;
    public final String flavor;
    public final String installMode;
    public final String assetName;
    public final long sizeBytes;
    public final String sha256;
    public final String downloadUrl;
    public final List<String> mirrorUrls;

    private PackageAsset(
        String platform,
        String arch,
        String flavor,
        String installMode,
        String assetName,
        long sizeBytes,
        String sha256,
        String downloadUrl,
        List<String> mirrorUrls) {
      this.platform = platform;
      this.arch = arch;
      this.flavor = flavor;
      this.installMode = installMode;
      this.assetName = assetName;
      this.sizeBytes = sizeBytes;
      this.sha256 = sha256;
      this.downloadUrl = downloadUrl;
      this.mirrorUrls = Collections.unmodifiableList(new ArrayList<>(mirrorUrls));
    }

    static PackageAsset parse(JSONObject json) {
      String platform = requiredString(json, "platform").toLowerCase(Locale.ROOT);
      String arch = requiredString(json, "arch").toLowerCase(Locale.ROOT);
      String flavor = requiredString(json, "flavor").toLowerCase(Locale.ROOT);
      String installMode = requiredString(json, "installMode").toLowerCase(Locale.ROOT);
      String assetName = requiredString(json, "assetName");
      validateAssetName(platform + "/" + arch + "/" + flavor, assetName);
      long sizeBytes = json.optLong("sizeBytes", -1L);
      String sha256 = requiredString(json, "sha256").toLowerCase(Locale.ROOT);
      validateSizeAndSha(assetName, sizeBytes, sha256);
      String downloadUrl = requiredUrl(json, "downloadUrl");
      return new PackageAsset(
          platform,
          arch,
          flavor,
          installMode,
          assetName,
          sizeBytes,
          sha256,
          downloadUrl,
          parseMirrors(json));
    }

    public boolean matches(String targetPlatform, String targetArch, String targetFlavor) {
      return platform.equals(normalize(targetPlatform))
          && ("all".equals(arch) || arch.equals(normalize(targetArch)))
          && ("all".equals(flavor) || flavor.equals(normalize(targetFlavor)));
    }

    public List<String> downloadUrls() {
      List<String> urls = new ArrayList<>();
      urls.add(downloadUrl);
      urls.addAll(mirrorUrls);
      return Collections.unmodifiableList(urls);
    }

    public JSONObject toJson() {
      JSONObject json = new JSONObject();
      json.put("platform", platform);
      json.put("arch", arch);
      json.put("flavor", flavor);
      json.put("installMode", installMode);
      json.put("assetName", assetName);
      json.put("sizeBytes", sizeBytes);
      json.put("sha256", sha256);
      json.put("downloadUrl", downloadUrl);
      json.put("mirrorUrls", new JSONArray(mirrorUrls));
      return json;
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static void validateSizeAndSha(String id, long sizeBytes, String sha256) {
    if (sizeBytes <= 0L) {
      throw new IllegalArgumentException("Update asset " + id + " has invalid sizeBytes.");
    }
    if (!SHA256_PATTERN.matcher(sha256).matches()) {
      throw new IllegalArgumentException("Update asset " + id + " has invalid sha256.");
    }
  }
}
