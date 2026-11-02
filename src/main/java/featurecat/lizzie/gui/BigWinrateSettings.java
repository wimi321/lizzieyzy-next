package featurecat.lizzie.gui;

import java.awt.Rectangle;
import org.json.JSONArray;
import org.json.JSONObject;

/** What 黑白胜率大字 remembers between sessions: its style, whether it stays on top, and where it was. */
final class BigWinrateSettings {
  static final String STYLE_KEY = "big-winrate-style";
  static final String ON_TOP_KEY = "big-winrate-on-top";
  static final String BOUNDS_KEY = "big-winrate-bounds";

  private BigWinrateSettings() {}

  static BigWinratePanel.Style style(JSONObject settings) {
    String saved = settings == null ? "" : settings.optString(STYLE_KEY, "");
    for (BigWinratePanel.Style style : BigWinratePanel.Style.values()) {
      if (style.name().equals(saved)) return style;
    }
    return BigWinratePanel.Style.PLAIN;
  }

  static void setStyle(JSONObject settings, BigWinratePanel.Style style) {
    if (settings != null && style != null) settings.put(STYLE_KEY, style.name());
  }

  static boolean onTop(JSONObject settings) {
    return settings != null && settings.optBoolean(ON_TOP_KEY, false);
  }

  static void setOnTop(JSONObject settings, boolean onTop) {
    if (settings != null) settings.put(ON_TOP_KEY, onTop);
  }

  /** The saved window bounds, or null when none were saved or they are too small to use. */
  static Rectangle bounds(JSONObject settings) {
    JSONArray saved = settings == null ? null : settings.optJSONArray(BOUNDS_KEY);
    if (saved == null || saved.length() != 4) return null;
    Rectangle bounds =
        new Rectangle(saved.optInt(0), saved.optInt(1), saved.optInt(2), saved.optInt(3));
    return bounds.width >= 120 && bounds.height >= 60 ? bounds : null;
  }

  static void setBounds(JSONObject settings, Rectangle bounds) {
    if (settings == null || bounds == null) return;
    settings.put(
        BOUNDS_KEY,
        new JSONArray().put(bounds.x).put(bounds.y).put(bounds.width).put(bounds.height));
  }
}
