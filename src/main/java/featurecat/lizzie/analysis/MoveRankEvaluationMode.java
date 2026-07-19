package featurecat.lizzie.analysis;

import java.util.Locale;

/** Selects which engine metric is authoritative when displaying move-quality ranks. */
public enum MoveRankEvaluationMode {
  AUTO("auto", true, true),
  SCORE("score", false, true),
  WINRATE("winrate", true, false),
  COMBINED("combined", true, true);

  private final String configValue;
  private final boolean usesWinrate;
  private final boolean usesScore;

  MoveRankEvaluationMode(String configValue, boolean usesWinrate, boolean usesScore) {
    this.configValue = configValue;
    this.usesWinrate = usesWinrate;
    this.usesScore = usesScore;
  }

  public String configValue() {
    return configValue;
  }

  public boolean usesWinrate() {
    return usesWinrate;
  }

  public boolean usesScore() {
    return usesScore;
  }

  public static MoveRankEvaluationMode fromConfig(
      String value, boolean legacyUseWinrate, boolean legacyUseScore) {
    if (value != null && !value.isBlank()) {
      String normalized = value.trim().toLowerCase(Locale.ROOT);
      for (MoveRankEvaluationMode mode : values()) {
        if (mode.configValue.equals(normalized)) {
          return mode;
        }
      }
    }
    if (legacyUseWinrate && !legacyUseScore) {
      return WINRATE;
    }
    if (legacyUseScore && !legacyUseWinrate) {
      return SCORE;
    }
    return AUTO;
  }
}
