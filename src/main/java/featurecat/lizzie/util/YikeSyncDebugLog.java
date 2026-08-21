package featurecat.lizzie.util;

public final class YikeSyncDebugLog {
  private YikeSyncDebugLog() {}

  static boolean isEnabledByProperties(String debugLogEnabled, String geometryProbeDebugEnabled) {
    return Boolean.parseBoolean(debugLogEnabled) || Boolean.parseBoolean(geometryProbeDebugEnabled);
  }

  public static void log(String message) {}
}
