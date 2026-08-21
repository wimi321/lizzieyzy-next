package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.logging.LoggingRuntime;
import java.util.MissingResourceException;

public final class WindowTitleDecorator {
  public static final String DEFAULT_SUFFIX = "[Full Trace]";

  private WindowTitleDecorator() {}

  public static String decorate(String title) {
    boolean active =
        LoggingRuntime.current().map(LoggingRuntime::fullTraceActive).orElse(false);
    return decorate(title, active, localizedSuffix());
  }

  public static String decorate(String title, boolean fullTraceActive, String suffix) {
    String marker = suffix == null || suffix.isBlank() ? DEFAULT_SUFFIX : suffix;
    String base = strip(title, marker);
    if (!fullTraceActive) {
      return base;
    }
    if (base.isEmpty()) {
      return marker;
    }
    return base + " " + marker;
  }

  static String strip(String title, String suffix) {
    if (title == null) {
      return "";
    }
    String marker = suffix == null || suffix.isBlank() ? DEFAULT_SUFFIX : suffix;
    String withSpace = " " + marker;
    if (title.endsWith(withSpace)) {
      return title.substring(0, title.length() - withSpace.length());
    }
    if (title.endsWith(marker)) {
      return title.substring(0, title.length() - marker.length()).stripTrailing();
    }
    return title;
  }

  static String localizedSuffix() {
    try {
      if (Lizzie.resourceBundle != null) {
        return Lizzie.resourceBundle.getString("LizzieFrame.fullTraceTitle");
      }
    } catch (MissingResourceException | NullPointerException ignored) {
    }
    return DEFAULT_SUFFIX;
  }
}
