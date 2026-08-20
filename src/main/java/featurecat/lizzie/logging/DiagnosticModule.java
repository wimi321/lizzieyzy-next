package featurecat.lizzie.logging;

public enum DiagnosticModule {
  ENGINE("engine", LogCategories.ENGINE),
  GTP_SUMMARY("gtp-summary", LogCategories.GTP),
  READBOARD_YIKE("readboard-yike", LogCategories.READBOARD),
  NETWORK_REMOTE("network-remote", LogCategories.NETWORK);

  private final String wireName;
  private final String loggerName;

  DiagnosticModule(String wireName, String loggerName) {
    this.wireName = wireName;
    this.loggerName = loggerName;
  }

  public String wireName() {
    return wireName;
  }

  public String loggerName() {
    return loggerName;
  }

  public static DiagnosticModule fromWireName(String wireName) {
    for (DiagnosticModule module : values()) {
      if (module.wireName.equals(wireName)) {
        return module;
      }
    }
    throw new IllegalArgumentException("Unknown diagnostic module: " + wireName);
  }
}
