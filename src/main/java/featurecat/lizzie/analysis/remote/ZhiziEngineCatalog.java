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

/** Available engine resources reported by the Zhizi/iKataGo service. */
public final class ZhiziEngineCatalog {
  private static final Pattern SAFE_OPTION_NAME =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
  private static final int MAX_DESCRIPTION_LENGTH = 180;
  private static final List<Option> CONFIRMED_WEIGHTS =
      List.of(
          new Option("18bnbt", "weight for 18bnbt"),
          new Option("28bnbt", "weight for 28bnbt"),
          new Option("fdx", "40B NBT extra-large weight"),
          new Option("60b", "60B weight"),
          new Option("40b", "40B weight"),
          new Option("20b", "20B weight, commonly used for handicap games"));

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
      unique.put(preferred, new Option(preferred, ""));
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

  /**
   * Completes a live or cached server catalog with Zhizi's confirmed public weights.
   *
   * <p>The server remains authoritative for descriptions and may add new entries. Confirmed entries
   * use a stable order so a failed refresh or an older three-item cache never hides valid choices.
   */
  public ZhiziEngineCatalog withConfirmedWeights() {
    Map<String, Option> reported = new LinkedHashMap<>();
    for (Option option : weights) {
      reported.putIfAbsent(option.name.toLowerCase(Locale.ROOT), option);
    }

    List<Option> merged = new ArrayList<>();
    for (Option confirmed : CONFIRMED_WEIGHTS) {
      Option serverOption = reported.remove(confirmed.name.toLowerCase(Locale.ROOT));
      merged.add(
          serverOption == null
              ? confirmed
              : new Option(
                  confirmed.name,
                  serverOption.description.isEmpty()
                      ? confirmed.description
                      : serverOption.description));
    }
    merged.addAll(reported.values());

    String preferred = canonicalConfirmedName(defaultWeight);
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
          weights.add(new Option(name, item == null ? "" : item.optString("description", "")));
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
      return new ZhiziEngineCatalog("", "28bnbt", CONFIRMED_WEIGHTS);
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static boolean isSafeOptionName(String value) {
    return value != null && SAFE_OPTION_NAME.matcher(value.trim()).matches();
  }

  private static String safeName(String value) {
    String name = value == null ? "" : value.trim();
    return isSafeOptionName(name) ? name : "";
  }

  private static String canonicalConfirmedName(String value) {
    String safeValue = safeName(value);
    for (Option confirmed : CONFIRMED_WEIGHTS) {
      if (confirmed.name.equalsIgnoreCase(safeValue)) {
        return confirmed.name;
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

  public static final class Option {
    private final String name;
    private final String description;

    public Option(String name, String description) {
      this.name = safeName(name);
      this.description = cleanText(description);
    }

    public String name() {
      return name;
    }

    public String description() {
      return description;
    }

    private JSONObject toJson() {
      JSONObject json = new JSONObject();
      json.put("name", name);
      json.put("description", description);
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
      return name.equals(option.name) && description.equals(option.description);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, description);
    }
  }
}
