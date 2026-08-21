package featurecat.lizzie.logging;

public enum TraceScope {
  ENGINE_GTP("engine-gtp", LogCategories.ENGINE_TRACE, LogStream.ENGINE_TRACE, "engine-trace.log"),
  READBOARD_YIKE(
      "readboard-yike",
      LogCategories.READBOARD_TRACE,
      LogStream.READBOARD_TRACE,
      "readboard-trace.log"),
  NETWORK_WEBSOCKET(
      "network-websocket",
      LogCategories.NETWORK_TRACE,
      LogStream.NETWORK_TRACE,
      "network-trace.log");

  private final String wireName;
  private final String loggerName;
  private final LogStream stream;
  private final String fileName;

  TraceScope(String wireName, String loggerName, LogStream stream, String fileName) {
    this.wireName = wireName;
    this.loggerName = loggerName;
    this.stream = stream;
    this.fileName = fileName;
  }

  public String wireName() {
    return wireName;
  }

  public String loggerName() {
    return loggerName;
  }

  public LogStream stream() {
    return stream;
  }

  public String fileName() {
    return fileName;
  }

  public static TraceScope fromWireName(String wireName) {
    for (TraceScope scope : values()) {
      if (scope.wireName.equals(wireName)) {
        return scope;
      }
    }
    throw new IllegalArgumentException("Unknown Full Trace scope: " + wireName);
  }
}
