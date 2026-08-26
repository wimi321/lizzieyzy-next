package featurecat.lizzie.gui;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/** Formats the continuous player-strength score as a human Go rank. */
final class PlayerStrengthRankFormatter {
  private static final double MIN_RANK_VALUE = -18.0;
  private static final double MAX_RANK_VALUE = 12.0;

  private PlayerStrengthRankFormatter() {}

  static String format(double rankValue, ResourceBundle resources) {
    if (!Double.isFinite(rankValue)) {
      return "-";
    }

    double value = Math.max(MIN_RANK_VALUE, Math.min(MAX_RANK_VALUE, rankValue));
    if (value >= 12.0) {
      return specialRank(value, resources, "PlayerStrengthEstimate.rank.scale.ai", "Semi-god/AI");
    }
    if (value >= 11.0) {
      return specialRank(
          value, resources, "PlayerStrengthEstimate.rank.scale.topPro", "Top professional");
    }
    if (value >= 10.0) {
      return specialRank(
          value, resources, "PlayerStrengthEstimate.rank.scale.pro", "Professional");
    }
    if (value >= 1.0) {
      return amateurRank(
          value, resources, "PlayerStrengthEstimate.rank.danSingle", "%s dan");
    }

    // Go ranks have no 0 dan: the point immediately below 1 dan is 1 kyu.
    double kyuValue = Math.max(1.0, 2.0 - value);
    return amateurRank(
        kyuValue, resources, "PlayerStrengthEstimate.rank.kyuSingle", "%s kyu");
  }

  private static String specialRank(
      double value, ResourceBundle resources, String resourceKey, String fallback) {
    return String.format(Locale.US, "%.1f %s", value, text(resources, resourceKey, fallback));
  }

  private static String amateurRank(
      double value, ResourceBundle resources, String resourceKey, String fallback) {
    String numericValue = String.format(Locale.US, "%.1f", value);
    return String.format(Locale.US, text(resources, resourceKey, fallback), numericValue);
  }

  private static String text(ResourceBundle resources, String key, String fallback) {
    if (resources == null) {
      return fallback;
    }
    try {
      String value = resources.getString(key);
      return value == null || value.trim().isEmpty() ? fallback : value;
    } catch (MissingResourceException e) {
      return fallback;
    }
  }
}
