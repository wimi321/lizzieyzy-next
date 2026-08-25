package featurecat.lizzie.update;

import featurecat.lizzie.Lizzie;
import java.io.IOException;
import org.json.JSONObject;

/** User-selected 更新源 for the official channel. */
public enum UpdateSource {
  OFFICIAL_SITE("official"),
  GITHUB("github");

  public static final String CONFIG_KEY = "update-source";

  public final String configValue;

  UpdateSource(String configValue) {
    this.configValue = configValue;
  }

  public static UpdateSource fromConfigValue(String raw) {
    if (raw != null && GITHUB.configValue.equalsIgnoreCase(raw.trim())) {
      return GITHUB;
    }
    return OFFICIAL_SITE;
  }

  public static UpdateSource fromUiConfig(JSONObject uiConfig) {
    if (uiConfig == null) {
      return OFFICIAL_SITE;
    }
    return fromConfigValue(uiConfig.optString(CONFIG_KEY, ""));
  }

  public static UpdateSource current() {
    if (Lizzie.config == null) {
      return OFFICIAL_SITE;
    }
    return fromUiConfig(Lizzie.config.uiConfig);
  }

  public static void persist(UpdateSource source) {
    if (Lizzie.config == null || Lizzie.config.uiConfig == null || source == null) {
      return;
    }
    Lizzie.config.uiConfig.put(CONFIG_KEY, source.configValue);
    if (Lizzie.config.config == null) {
      return;
    }
    try {
      Lizzie.config.save();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
