package featurecat.lizzie.update;

import featurecat.lizzie.Lizzie;
import java.io.IOException;
import org.json.JSONObject;

/** User-selected 更新通道. Switching the channel does not change installed files. */
public enum UpdateChannel {
  STABLE("stable"),
  BETA("beta");

  public static final String CONFIG_KEY = "update-channel";

  public final String configValue;

  UpdateChannel(String configValue) {
    this.configValue = configValue;
  }

  public static UpdateChannel fromConfigValue(String raw) {
    if (raw != null && BETA.configValue.equalsIgnoreCase(raw.trim())) {
      return BETA;
    }
    return STABLE;
  }

  public static UpdateChannel fromUiConfig(JSONObject uiConfig) {
    if (uiConfig == null) {
      return STABLE;
    }
    return fromConfigValue(uiConfig.optString(CONFIG_KEY, ""));
  }

  public static UpdateChannel current() {
    if (Lizzie.config == null) {
      return STABLE;
    }
    return fromUiConfig(Lizzie.config.uiConfig);
  }

  public static void persist(UpdateChannel channel) {
    if (Lizzie.config == null || Lizzie.config.uiConfig == null || channel == null) {
      return;
    }
    Lizzie.config.uiConfig.put(CONFIG_KEY, channel.configValue);
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
