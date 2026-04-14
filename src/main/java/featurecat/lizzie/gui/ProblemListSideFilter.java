package featurecat.lizzie.gui;

public enum ProblemListSideFilter {
  ALL("all"),
  BLACK("black"),
  WHITE("white");

  private final String configValue;

  ProblemListSideFilter(String configValue) {
    this.configValue = configValue;
  }

  public String configValue() {
    return configValue;
  }

  public boolean allows(boolean isBlack) {
    return this == ALL || (this == BLACK && isBlack) || (this == WHITE && !isBlack);
  }

  public static ProblemListSideFilter fromConfigValue(String value) {
    if (WHITE.configValue.equalsIgnoreCase(value)) {
      return WHITE;
    }
    return BLACK;
  }
}
