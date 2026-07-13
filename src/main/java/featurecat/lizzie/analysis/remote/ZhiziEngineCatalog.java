package featurecat.lizzie.analysis.remote;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
      if (option.name.equals(candidate)) {
        return true;
      }
    }
    return false;
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
        JSONObject item = items.optJSONObject(i);
        if (item == null) {
          continue;
        }
        String name = safeName(item.optString("name", ""));
        if (!name.isEmpty()) {
          weights.add(new Option(name, item.optString("description", "")));
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
      List<Option> weights = new ArrayList<>();
      weights.add(new Option("28bnbt", "weight for 28bnbt"));
      weights.add(new Option("18bnbt", "weight for 18bnbt"));
      weights.add(new Option("fdx", "fdx"));
      return new ZhiziEngineCatalog("", "28bnbt", weights);
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
