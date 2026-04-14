package featurecat.lizzie.gui;

public enum ProblemListMetric {
  WINRATE_LOSS("winrate"),
  SCORE_LOSS("score");

  private final String configValue;

  ProblemListMetric(String configValue) {
    this.configValue = configValue;
  }

  public String configValue() {
    return configValue;
  }

  public static ProblemListMetric fromConfigValue(String value) {
    if (SCORE_LOSS.configValue.equalsIgnoreCase(value)) {
      return SCORE_LOSS;
    }
    return WINRATE_LOSS;
  }
}
