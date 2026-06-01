package featurecat.lizzie.analysis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SyncDiagnosticsExportSanitizer {
  private static final Pattern SESSION_KEY =
      Pattern.compile("\\b([A-Za-z][A-Za-z0-9-]*):(\\d+)\\b");
  private static final Pattern YIKE_LIVE_URL =
      Pattern.compile("https?://(?:www\\.)?yikeweiqi\\.com/live/(\\d+)\\b[^\\s,;]*");
  private static final Pattern RAW_URL = Pattern.compile("https?://[^\\s,;]+");
  private static final Pattern SGF_PAYLOAD = Pattern.compile("\\(;[^\\r\\n]*?\\)");
  private static final Pattern TOKEN_PARAMETER =
      Pattern.compile("(?i)\\b(?:roomToken|authToken|token)\\s*=\\s*[^\\s&;,]+");
  private static final Pattern YIKE_ROOM_PARAMETER =
      Pattern.compile("(?i)\\b(?:room|roomId|id)\\s*=\\s*(\\d+)\\b");
  private static final Pattern WINDOWS_USER_PATH =
      Pattern.compile("(?i)\\b([A-Z]):\\\\Users\\\\[^\\\\\\s,;]+(?:\\\\[^\\s,;]*)*");
  private static final Pattern WINDOWS_ABSOLUTE_PATH =
      Pattern.compile("(?i)\\b[A-Z]:\\\\(?!Users\\\\)[^\\s,;]+");
  private static final Pattern WSL_UNC_USER_PATH =
      Pattern.compile(
          "\\\\\\\\wsl\\.localhost\\\\([^\\\\\\s]+)\\\\home\\\\[^\\\\\\s,;]+(?:\\\\[^\\s,;]*)*");
  private static final Pattern WSL_MOUNT_USER_PATH =
      Pattern.compile("/mnt/([A-Za-z])/Users/[^\\s,;]+(?:/[^\\s,;]*)*");
  private static final Pattern POSIX_HOME_PATH = Pattern.compile("/home/[^\\s,;]+(?:/[^\\s,;]*)*");
  private static final Pattern MAC_USER_PATH = Pattern.compile("/Users/[^\\s,;]+(?:/[^\\s,;]*)*");
  private static final Pattern POSIX_ABSOLUTE_PATH =
      Pattern.compile(
          "(?<![A-Za-z0-9_.<>-])/(?!mnt/[A-Za-z]/Users/|home/|Users/)[^\\s,;]+(?:/[^\\s,;]*)*");
  private static final Pattern RELATIVE_PARENT_PATH =
      Pattern.compile("(?<!/)\\b(?:[A-Za-z0-9_.-]+/)+([A-Za-z0-9_.-]+)\\b");
  private static final Pattern SECRET_TEXT = Pattern.compile("(?i)[^\\n\\r]*secret[^\\n\\r]*");
  private static final Pattern TOKEN_TEXT =
      Pattern.compile("(?i)\\b[A-Za-z0-9_-]*token[A-Za-z0-9_-]*\\b");

  private final Map<String, String> sessionAliases = new LinkedHashMap<>();
  private final Map<String, Integer> routeCounts = new LinkedHashMap<>();

  String sessionAlias(String sessionKey) {
    String value = normalize(sessionKey, "none");
    if ("none".equals(value)) {
      return "none";
    }
    return sessionAliases.computeIfAbsent(
        value,
        key -> {
          String route = routeOf(key);
          int index = routeCounts.getOrDefault(route, 0) + 1;
          routeCounts.put(route, index);
          return route + "#" + index;
        });
  }

  String text(String value) {
    String safe = unescapeDiagnosticSeparators(normalize(value, "none"));
    safe = SGF_PAYLOAD.matcher(safe).replaceAll("<redacted-sgf>");
    safe = replaceYikeUrls(safe);
    safe = RAW_URL.matcher(safe).replaceAll("<redacted-url>");
    safe = TOKEN_PARAMETER.matcher(safe).replaceAll("<redacted-token>");
    safe = replaceYikeRoomParameters(safe);
    safe = replaceSessionKeys(safe);
    safe = replacePaths(safe);
    safe = TOKEN_TEXT.matcher(safe).replaceAll("<redacted-token>");
    safe = SECRET_TEXT.matcher(safe).replaceAll("<redacted-secret>");
    return safe;
  }

  String path(String value) {
    return SyncDiagnosticsEnvironment.sanitizePath(value);
  }

  private String replaceYikeUrls(String value) {
    Matcher matcher = YIKE_LIVE_URL.matcher(value);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(
          out, Matcher.quoteReplacement(sessionAlias("live-room:" + matcher.group(1))));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private String replaceYikeRoomParameters(String value) {
    Matcher matcher = YIKE_ROOM_PARAMETER.matcher(value);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      String replacement =
          matcher
              .group(0)
              .replace(matcher.group(1), sessionAlias("live-room:" + matcher.group(1)));
      matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private String replaceSessionKeys(String value) {
    Matcher matcher = SESSION_KEY.matcher(value);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(out, Matcher.quoteReplacement(sessionAlias(matcher.group())));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private String replacePaths(String value) {
    String safe = replaceWindowsUserPaths(value);
    safe = replaceWslUncUserPaths(safe);
    safe = replaceWslMountUserPaths(safe);
    safe = POSIX_HOME_PATH.matcher(safe).replaceAll("/home/<user>");
    safe = MAC_USER_PATH.matcher(safe).replaceAll("/Users/<user>");
    safe = WINDOWS_ABSOLUTE_PATH.matcher(safe).replaceAll("<redacted-path>");
    safe = RELATIVE_PARENT_PATH.matcher(safe).replaceAll("$1");
    safe = POSIX_ABSOLUTE_PATH.matcher(safe).replaceAll("<redacted-path>");
    return safe;
  }

  private String replaceWindowsUserPaths(String value) {
    Matcher matcher = WINDOWS_USER_PATH.matcher(value);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(
          out, Matcher.quoteReplacement(matcher.group(1).toUpperCase() + ":\\Users\\<user>"));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private String replaceWslUncUserPaths(String value) {
    Matcher matcher = WSL_UNC_USER_PATH.matcher(value);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(
          out,
          Matcher.quoteReplacement("\\\\wsl.localhost\\" + matcher.group(1) + "\\home\\<user>"));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private String replaceWslMountUserPaths(String value) {
    Matcher matcher = WSL_MOUNT_USER_PATH.matcher(value);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(
          out, Matcher.quoteReplacement("/mnt/" + matcher.group(1) + "/Users/<user>"));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static String routeOf(String sessionKey) {
    int separator = sessionKey.indexOf(':');
    String route = separator > 0 ? sessionKey.substring(0, separator) : "none";
    return normalize(route, "none");
  }

  private static String unescapeDiagnosticSeparators(String value) {
    String safe =
        value
        .replace("\\/", "/")
        .replace("\\u003a", ":")
        .replace("\\u003A", ":")
        .replace("\\u002f", "/")
        .replace("\\u002F", "/")
        .replace("\\u005c", "\\")
        .replace("\\u005C", "\\")
        .replace("\\u003d", "=")
        .replace("\\u003D", "=")
        .replace("\\u0026", "&")
        .replace("\\u003f", "?")
        .replace("\\u003F", "?");
    return unescapeWindowsPathSeparators(safe);
  }

  private static String unescapeWindowsPathSeparators(String value) {
    StringBuilder out = new StringBuilder(value.length());
    boolean inWindowsDrivePath = false;
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (isWindowsDriveStart(value, i)) {
        inWindowsDrivePath = true;
        out.append(current);
        continue;
      }
      if (inWindowsDrivePath
          && current == '\\'
          && i + 1 < value.length()
          && value.charAt(i + 1) == '\\') {
        out.append('\\');
        i++;
        continue;
      }
      out.append(current);
      if (inWindowsDrivePath
          && (Character.isWhitespace(current) || current == ',' || current == ';')) {
        inWindowsDrivePath = false;
      }
    }
    return out.toString();
  }

  private static boolean isWindowsDriveStart(String value, int index) {
    return index + 2 < value.length()
        && Character.isLetter(value.charAt(index))
        && value.charAt(index + 1) == ':'
        && value.charAt(index + 2) == '\\'
        && (index == 0 || !Character.isLetterOrDigit(value.charAt(index - 1)));
  }

  private static String normalize(String value, String fallback) {
    if (value == null || value.trim().isEmpty()) {
      return fallback;
    }
    return value.trim();
  }
}
