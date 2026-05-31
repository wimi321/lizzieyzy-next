package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;

public final class SyncDiagnosticsEnvironment {
  private final String appVersion;
  private final String javaVersion;
  private final String osName;
  private final String osVersion;
  private final String osArch;
  private final String userDirSanitized;
  private final long timestampMillis;

  private SyncDiagnosticsEnvironment(
      String appVersion,
      String javaVersion,
      String osName,
      String osVersion,
      String osArch,
      String userDirSanitized,
      long timestampMillis) {
    this.appVersion = SyncDecisionTrace.normalize(appVersion, "unknown");
    this.javaVersion = SyncDecisionTrace.normalize(javaVersion, "unknown");
    this.osName = SyncDecisionTrace.normalize(osName, "unknown");
    this.osVersion = SyncDecisionTrace.normalize(osVersion, "unknown");
    this.osArch = SyncDecisionTrace.normalize(osArch, "unknown");
    this.userDirSanitized = SyncDecisionTrace.normalize(userDirSanitized, "unknown");
    this.timestampMillis = timestampMillis;
  }

  public static SyncDiagnosticsEnvironment capture() {
    return new SyncDiagnosticsEnvironment(
        Lizzie.lizzieVersion,
        System.getProperty("java.version", "unknown"),
        System.getProperty("os.name", "unknown"),
        System.getProperty("os.version", "unknown"),
        System.getProperty("os.arch", "unknown"),
        sanitizePath(System.getProperty("user.dir", "")),
        System.currentTimeMillis());
  }

  static SyncDiagnosticsEnvironment of(
      String appVersion,
      String javaVersion,
      String osName,
      String osVersion,
      String osArch,
      String userDirSanitized,
      long timestampMillis) {
    return new SyncDiagnosticsEnvironment(
        appVersion, javaVersion, osName, osVersion, osArch, userDirSanitized, timestampMillis);
  }

  static String sanitizePath(String path) {
    if (path == null || path.trim().isEmpty()) {
      return "unknown";
    }
    String value = path.trim();
    String normalized = value.replace('\\', '/');

    if (normalized.matches("^[A-Za-z]:/Users/[^/]+(/.*)?$")) {
      return value.substring(0, 1).toUpperCase() + ":\\Users\\<user>";
    }
    if (normalized.matches("^/mnt/[A-Za-z]/Users/[^/]+(/.*)?$")) {
      String drive = normalized.substring("/mnt/".length(), "/mnt/".length() + 1);
      return "/mnt/" + drive + "/Users/<user>";
    }
    if (normalized.matches("^//wsl\\.localhost/[^/]+/home/[^/]+(/.*)?$")) {
      String[] parts = normalized.split("/");
      return "\\\\wsl.localhost\\" + parts[2] + "\\home\\<user>";
    }
    if (normalized.matches("^/home/[^/]+(/.*)?$")) {
      return "/home/<user>";
    }
    if (normalized.matches("^/Users/[^/]+(/.*)?$")) {
      return "/Users/<user>";
    }
    if (isAbsolutePath(value, normalized)) {
      return "<redacted-path>";
    }
    return basename(normalized);
  }

  public String getAppVersion() {
    return appVersion;
  }

  public String getJavaVersion() {
    return javaVersion;
  }

  public String getOsName() {
    return osName;
  }

  public String getOsVersion() {
    return osVersion;
  }

  public String getOsArch() {
    return osArch;
  }

  public String getUserDirSanitized() {
    return userDirSanitized;
  }

  public long getTimestampMillis() {
    return timestampMillis;
  }

  private static boolean isAbsolutePath(String original, String normalized) {
    return normalized.startsWith("/")
        || original.startsWith("\\\\")
        || normalized.matches("^[A-Za-z]:/.*$");
  }

  private static String basename(String normalized) {
    String value = normalized;
    while (value.endsWith("/") && value.length() > 1) {
      value = value.substring(0, value.length() - 1);
    }
    int lastSlash = value.lastIndexOf('/');
    return lastSlash >= 0 ? value.substring(lastSlash + 1) : value;
  }
}
