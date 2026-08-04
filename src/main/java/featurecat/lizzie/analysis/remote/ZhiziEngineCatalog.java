package featurecat.lizzie.analysis.remote;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Available Zhizi engine resources together with their discovery provenance. */
public final class ZhiziEngineCatalog {
  private static final Pattern SAFE_OPTION_NAME =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
  private static final int MAX_DESCRIPTION_LENGTH = 180;
  private static final List<Option> OFFICIAL_DOCUMENTED_WEIGHTS =
      List.of(
          new Option("18bnbt", "weight for 18bnbt", DiscoverySource.OFFICIAL_DOCUMENTED),
          new Option("28bnbt", "weight for 28bnbt", DiscoverySource.OFFICIAL_DOCUMENTED),
          new Option("fdx", "40B NBT extra-large weight", DiscoverySource.OFFICIAL_DOCUMENTED));
  private static final List<String> LEGACY_COMPATIBLE_WEIGHTS = List.of("60b", "40b", "20b");

  private final String serverVersion;
  private final String defaultWeight;
  private final List<Option> weights;

  public ZhiziEngineCatalog(String serverVersion, String defaultWeight, List<Option> weights)
      throws IOException {
    Map<String, Option> unique = new LinkedHashMap<>();
    if (weights != null) {
      for (Option option : weights) {
        if (option != null && isSafeOptionName(option.name)) {
          unique.putIfAbsent(option.name, option);
        }
      }
    }
    String preferred = safeName(defaultWeight);
    if (!preferred.isEmpty() && !unique.containsKey(preferred)) {
      unique.put(preferred, new Option(preferred, "", DiscoverySource.USER_PRESERVED));
    }
    if (unique.isEmpty()) {
      throw new IOException("Zhizi did not report any usable KataGo weights.");
    }
    this.serverVersion = cleanText(serverVersion);
    this.defaultWeight = preferred.isEmpty() ? unique.keySet().iterator().next() : preferred;
    this.weights = Collections.unmodifiableList(new ArrayList<>(unique.values()));
  }

  public String serverVersion() {
    return serverVersion;
  }

  public String defaultWeight() {
    return defaultWeight;
  }

  public List<Option> weights() {
    return weights;
  }

  public boolean containsWeight(String name) {
    String candidate = safeName(name);
    for (Option option : weights) {
      if (option.name.equalsIgnoreCase(candidate)) {
        return true;
      }
    }
    return false;
  }

  /** Adds the public-document baseline while retaining compatible cached or user choices. */
  public ZhiziEngineCatalog withDocumentedWeights() {
    Map<String, Option> reported = new LinkedHashMap<>();
    for (Option option : weights) {
      reported.putIfAbsent(option.name.toLowerCase(Locale.ROOT), option);
    }
    Option reportedDefault = reported.get(safeName(defaultWeight).toLowerCase(Locale.ROOT));

    List<Option> merged = new ArrayList<>();
    for (Option documented : OFFICIAL_DOCUMENTED_WEIGHTS) {
      Option cachedOption = reported.remove(documented.name.toLowerCase(Locale.ROOT));
      merged.add(
          cachedOption == null
              ? documented
              : new Option(
                  documented.name,
                  cachedOption.description.isEmpty()
                      ? documented.description
                      : cachedOption.description,
                  DiscoverySource.OFFICIAL_DOCUMENTED));
    }
    for (Option option : reported.values()) {
      DiscoverySource source = option.source;
      if (source == DiscoverySource.OFFICIAL_DOCUMENTED) {
        source = DiscoverySource.CACHED_LEGACY;
      }
      merged.add(new Option(option.name, option.description, source));
    }

    String preferred =
        reportedDefault != null
                && reportedDefault.source == DiscoverySource.SERVER_CAPABILITIES
                && isSelectableWeight(defaultWeight)
            ? canonicalDocumentedName(defaultWeight)
            : "28bnbt";
    try {
      return new ZhiziEngineCatalog(serverVersion, preferred, merged);
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public JSONObject toJson() {
    JSONObject json = new JSONObject();
    json.put("serverVersion", serverVersion);
    json.put("defaultKataWeight", defaultWeight);
    JSONArray items = new JSONArray();
    for (Option weight : weights) {
      items.put(weight.toJson());
    }
    json.put("supportKataWeights", items);
    return json;
  }

  public static ZhiziEngineCatalog fromJson(JSONObject json) throws IOException {
    if (json == null) {
      throw new IOException("Zhizi engine catalog is missing.");
    }
    JSONArray items = json.optJSONArray("supportKataWeights");
    List<Option> weights = new ArrayList<>();
    if (items != null) {
      for (int i = 0; i < items.length(); i++) {
        Object rawItem = items.opt(i);
        JSONObject item = rawItem instanceof JSONObject ? (JSONObject) rawItem : null;
        String name =
            safeName(
                item == null
                    ? (rawItem instanceof String ? (String) rawItem : "")
                    : item.optString("name", ""));
        if (!name.isEmpty()) {
          weights.add(
              new Option(
                  name,
                  item == null ? "" : item.optString("description", ""),
                  item == null
                      ? DiscoverySource.CACHED_LEGACY
                      : DiscoverySource.fromStoredValue(item.optString("source", ""))));
        }
      }
    }
    return new ZhiziEngineCatalog(
        json.optString("serverVersion", ""), json.optString("defaultKataWeight", ""), weights);
  }

  public static ZhiziEngineCatalog fromJson(String json) throws IOException {
    try {
      return fromJson(new JSONObject(json == null ? "" : json));
    } catch (RuntimeException e) {
      throw new IOException("Zhizi engine catalog is not valid JSON.", e);
    }
  }

  public static ZhiziEngineCatalog fallback() {
    try {
      return new ZhiziEngineCatalog("", "28bnbt", OFFICIAL_DOCUMENTED_WEIGHTS);
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static boolean isSafeOptionName(String value) {
    return value != null && SAFE_OPTION_NAME.matcher(value.trim()).matches();
  }

  public static boolean isDocumentedWeight(String value) {
    String safeValue = safeName(value);
    for (Option option : OFFICIAL_DOCUMENTED_WEIGHTS) {
      if (option.name.equalsIgnoreCase(safeValue)) {
        return true;
      }
    }
    return false;
  }

  public static boolean isLegacyCompatibleWeight(String value) {
    String safeValue = safeName(value);
    for (String option : LEGACY_COMPATIBLE_WEIGHTS) {
      if (option.equalsIgnoreCase(safeValue)) {
        return true;
      }
    }
    return false;
  }

  public static boolean isSelectableWeight(String value) {
    return isDocumentedWeight(value) || isLegacyCompatibleWeight(value);
  }

  private static String safeName(String value) {
    String name = value == null ? "" : value.trim();
    return isSafeOptionName(name) ? name : "";
  }

  private static String canonicalDocumentedName(String value) {
    String safeValue = safeName(value);
    for (Option documented : OFFICIAL_DOCUMENTED_WEIGHTS) {
      if (documented.name.equalsIgnoreCase(safeValue)) {
        return documented.name;
      }
    }
    return safeValue;
  }

  private static String cleanText(String value) {
    String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
    return text.length() <= MAX_DESCRIPTION_LENGTH
        ? text
        : text.substring(0, MAX_DESCRIPTION_LENGTH);
  }

  public enum DiscoverySource {
    OFFICIAL_DOCUMENTED,
    SERVER_CAPABILITIES,
    CACHED_LEGACY,
    USER_PRESERVED;

    private static DiscoverySource fromStoredValue(String value) {
      try {
        return value == null || value.isBlank()
            ? CACHED_LEGACY
            : valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
        return CACHED_LEGACY;
      }
    }
  }

  public static final class Option {
    private final String name;
    private final String description;
    private final DiscoverySource source;

    public Option(String name, String description) {
      this(name, description, DiscoverySource.CACHED_LEGACY);
    }

    public Option(String name, String description, DiscoverySource source) {
      this.name = safeName(name);
      this.description = cleanText(description);
      this.source = source == null ? DiscoverySource.CACHED_LEGACY : source;
    }

    public String name() {
      return name;
    }

    public String description() {
      return description;
    }

    public DiscoverySource source() {
      return source;
    }

    private JSONObject toJson() {
      JSONObject json = new JSONObject();
      json.put("name", name);
      json.put("description", description);
      json.put("source", source.name());
      return json;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Option)) {
        return false;
      }
      Option option = (Option) other;
      return name.equals(option.name)
          && description.equals(option.description)
          && source == option.source;
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, description, source);
    }
  }
}
