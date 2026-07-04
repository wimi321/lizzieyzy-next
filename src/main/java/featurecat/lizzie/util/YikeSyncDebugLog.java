package featurecat.lizzie.util;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class YikeSyncDebugLog {
  private static final String LOG_PATH =
      System.getProperty("lizzie.yike.debugLog", "target/yike-sync-debug.log");
  private static final boolean ENABLED =
      isEnabledByProperties(
          System.getProperty("lizzie.yike.debugLog.enabled", "false"),
          System.getProperty("lizzie.yike.geometryProbeDebug.enabled", "false"));

  private YikeSyncDebugLog() {}

  static boolean isEnabledByProperties(String debugLogEnabled, String geometryProbeDebugEnabled) {
    return Boolean.parseBoolean(debugLogEnabled) || Boolean.parseBoolean(geometryProbeDebugEnabled);
  }

  public static void log(String message) {
    if (!ENABLED) return;
    try {
      File file = new File(LOG_PATH);
      File parent = file.getParentFile();
      if (parent != null && !parent.exists()) parent.mkdirs();
      try (FileWriter writer = new FileWriter(file, true)) {
        writer.write(
            "[" + new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()) + "] " + message + "\n");
      }
    } catch (Exception ignored) {
    }
  }
}
