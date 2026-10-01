package featurecat.lizzie.analysis;

import featurecat.lizzie.logging.PersistenceSanitizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SyncDiagnosticsExportSanitizer {
  private static final Pattern SESSION_KEY =
      Pattern.compile(
          "(?<![A-Za-z0-9_-])((?:live-room|unite-board):"
              + "[A-Za-z0-9_~-]+(?:\\.[A-Za-z0-9_~-]+)*)(?![A-Za-z0-9_~-])");
  private static final Pattern YIKE_LIVE_URL =
      Pattern.compile(
          "https?://(?:www\\.)?yikeweiqi\\.com/live/(\\d+)(?![A-Za-z0-9_-])[^\\s,;]*");
  private static final Pattern RAW_URL = Pattern.compile("https?://[^\\s,;]+");
  private static final Pattern SGF_PAYLOAD = Pattern.compile("\\(;.*?\\)", Pattern.DOTALL);
  private static final Pattern TOKEN_PARAMETER =
      Pattern.compile("(?i)\\b(?:roomToken|authToken|token)\\b(?:\\s*[=:]\\s*|\\s+)[^\\s&;,]+");
  private static final Pattern BEARER_CREDENTIAL =
      Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+\\-/]+=*");
  private static final Pattern SENSITIVE_CREDENTIAL_PARAMETER =
      Pattern.compile(
          "(?i)([\\\"']?(?:password|connectPassword|zhizi-account-token|zz-socketio-token|authorization)[\\\"']?\\s*(?:[=:]\\s*|\\s+)[\\\"']?)([^\\\"'\\s,;}&]+)([\\\"']?)");
  private static final Pattern YIKE_ROOM_PARAMETER =
      Pattern.compile(
          "(?i)\\b(?:room|roomId|id)\\b(?:\\s*[=:]\\s*|\\s+)(\\d+)(?![A-Za-z0-9_-])");
  private static final Pattern WSL_MOUNT_USER_PATH =
      Pattern.compile("/mnt/([A-Za-z])/Users/[^\\r\\n,;]+");
  private static final Pattern POSIX_HOME_PATH = Pattern.compile("/home/[^\\r\\n,;]+");
  private static final Pattern MAC_USER_PATH = Pattern.compile("/Users/[^\\r\\n,;]+");
  private static final Pattern POSIX_ABSOLUTE_PATH =
      Pattern.compile(
          "(?<![A-Za-z0-9_.<>-])/(?!mnt/[A-Za-z]/Users/|home/|Users/)[^\\s,;]+(?:/[^\\s,;]*)*");
  private static final Pattern RELATIVE_PARENT_PATH =
      Pattern.compile("(?<!/)\\b(?:[A-Za-z0-9_.-]+/)+([A-Za-z0-9_.-]+)\\b");
  private static final Pattern SECRET_TEXT = Pattern.compile("(?i)secret");
  private static final Pattern TOKEN_TEXT =
      Pattern.compile("(?i)\\b[A-Za-z0-9_-]*token[A-Za-z0-9_-]*\\b");
  private static final String REDACTED_PATH = "<redacted-path>";

  private final Map<String, String> sessionAliases = new LinkedHashMap<>();
  private final Map<String, Integer> routeCounts = new LinkedHashMap<>();
  private final PersistenceSanitizer credentials = new PersistenceSanitizer();

  public String sessionAlias(String sessionKey) {
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

  public Map<String, String> aliases() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(sessionAliases));
  }

  public String text(String value) {
    // Text whitespace belongs to the payload; only identifiers are trimmed by normalize.
    // Redact Windows paths before credential canonicalization can collapse JSON/UNC separators.
    String safe =
        redactEmbeddedWindowsAbsolutePaths(value == null || value.isEmpty() ? "none" : value);
    safe = credentials.sanitize(safe);
    safe = credentials.sanitize(unescapeDiagnosticSeparators(safe));
    safe = SGF_PAYLOAD.matcher(safe).replaceAll("<redacted-sgf>");
    safe = BEARER_CREDENTIAL.matcher(safe).replaceAll("<redacted-credential>");
    safe = SENSITIVE_CREDENTIAL_PARAMETER.matcher(safe).replaceAll("$1<redacted-credential>$3");
    safe = replaceYikeUrls(safe);
    safe = RAW_URL.matcher(safe).replaceAll("<redacted-url>");
    safe = TOKEN_PARAMETER.matcher(safe).replaceAll("<redacted-token>");
    safe = replaceSessionKeys(safe);
    safe = replaceYikeRoomParameters(safe);
    safe = replacePaths(safe);
    safe = TOKEN_TEXT.matcher(safe).replaceAll("<redacted-token>");
    safe = redactSecretLines(safe);
    return credentials.sanitize(safe);
  }

  private static String redactSecretLines(String value) {
    Matcher secret = SECRET_TEXT.matcher(value);
    StringBuilder out = null;
    int copiedThrough = 0;
    int lineStart = 0;
    for (int end = 0; end <= value.length(); end++) {
      if (end < value.length() && value.charAt(end) != '\r' && value.charAt(end) != '\n') {
        continue;
      }
      // Search each line once; a leading greedy wildcard retries every suffix on a miss.
      if (secret.region(lineStart, end).find()) {
        if (out == null) {
          out = new StringBuilder(value.length());
        }
        out.append(value, copiedThrough, lineStart).append("<redacted-secret>");
        copiedThrough = end;
      }
      lineStart = end + 1;
    }
    return out == null ? value : out.append(value, copiedThrough, value.length()).toString();
  }

  public String path(String value) {
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
          matcher.group(0).replace(matcher.group(1), sessionAlias("live-room:" + matcher.group(1)));
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
    String safe = redactEmbeddedWindowsAbsolutePaths(value);
    safe = replaceWslMountUserPaths(safe);
    safe = POSIX_HOME_PATH.matcher(safe).replaceAll("/home/<user>");
    safe = MAC_USER_PATH.matcher(safe).replaceAll("/Users/<user>");
    safe = RELATIVE_PARENT_PATH.matcher(safe).replaceAll("$1");
    safe = POSIX_ABSOLUTE_PATH.matcher(safe).replaceAll(REDACTED_PATH);
    return safe;
  }

  /**
   * Redacts embedded Windows absolute paths without treating every doubled backslash as a UNC
   * path. A UNC candidate must contain both a server and a share; drive paths must contain the
   * rooted separator. A single leading backslash is also treated as rooted because persistence
   * canonicalization in older logs can collapse the leading UNC pair. The same detector handles
   * ordinary, WSL, device/extended, and JSON-escaped spellings so new path families cannot silently
   * bypass a collection of unrelated regexes.
   */
  static String redactEmbeddedWindowsAbsolutePaths(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    StringBuilder safe = null;
    int copiedThrough = 0;
    for (int index = 0; index < value.length(); index++) {
      if (!isEmbeddedPathBoundary(value, index)
          || windowsAbsolutePathPrefixEnd(value, index) < 0) {
        continue;
      }
      int end = windowsAbsolutePathEnd(value, index);
      if (end <= index) {
        continue;
      }
      if (safe == null) {
        safe = new StringBuilder(value.length());
      }
      safe.append(value, copiedThrough, index).append(REDACTED_PATH);
      copiedThrough = end;
      index = end - 1;
    }
    if (safe == null) {
      return value;
    }
    return safe.append(value, copiedThrough, value.length()).toString();
  }

  private static boolean isEmbeddedPathBoundary(String value, int index) {
    if (index <= 0) {
      return true;
    }
    char previous = value.charAt(index - 1);
    if (isPathSeparator(previous)) {
      return false;
    }
    if (startsHttpAuthority(value, index)) {
      return false;
    }
    return !Character.isLetterOrDigit(previous)
        && previous != '_'
        && previous != '-'
        && previous != '.';
  }

  private static boolean startsHttpAuthority(String value, int index) {
    if (index <= 0 || index + 1 >= value.length() || value.charAt(index - 1) != ':') {
      return false;
    }
    char first = value.charAt(index);
    char second = value.charAt(index + 1);
    if (!((first == '/' && second == '/') || (first == '\\' && second == '/'))) {
      return false;
    }
    return (index >= 5 && value.regionMatches(true, index - 5, "http:", 0, 5))
        || (index >= 6 && value.regionMatches(true, index - 6, "https:", 0, 6));
  }

  private static int windowsAbsolutePathPrefixEnd(String value, int start) {
    if (start + 2 < value.length()
        && isAsciiLetter(value.charAt(start))
        && value.charAt(start + 1) == ':'
        && isPathSeparator(value.charAt(start + 2))) {
      return start + 3;
    }
    if (!isPathSeparator(value.charAt(start))
        || (value.charAt(start) != '\\'
            && (start + 1 >= value.length() || !isPathSeparator(value.charAt(start + 1))))) {
      return -1;
    }

    int componentStart = skipPathSeparators(value, start);
    if (componentStart >= value.length()) {
      return -1;
    }
    if (value.charAt(componentStart) == '?') {
      int afterQuestion = skipPathSeparators(value, componentStart + 1);
      if (startsWithIgnoreCase(value, afterQuestion, "UNC")) {
        int afterUnc = afterQuestion + 3;
        if (afterUnc >= value.length() || !isPathSeparator(value.charAt(afterUnc))) {
          return -1;
        }
        componentStart = skipPathSeparators(value, afterUnc);
      } else if (afterQuestion + 2 < value.length()
          && isAsciiLetter(value.charAt(afterQuestion))
          && value.charAt(afterQuestion + 1) == ':'
          && isPathSeparator(value.charAt(afterQuestion + 2))) {
        return afterQuestion + 3;
      }
    }

    int serverEnd = pathComponentEnd(value, componentStart);
    if (serverEnd <= componentStart
        || !isConservativeUncServer(value, componentStart, serverEnd)
        || serverEnd >= value.length()
        || !isPathSeparator(value.charAt(serverEnd))) {
      return -1;
    }
    int shareStart = skipPathSeparators(value, serverEnd);
    int shareEnd = pathComponentEnd(value, shareStart);
    return shareEnd > shareStart ? shareEnd : -1;
  }

  private static boolean isConservativeUncServer(String value, int start, int end) {
    for (int index = start; index < end; index++) {
      char current = value.charAt(index);
      if (!(isAsciiLetter(current)
          || (current >= '0' && current <= '9')
          || current == '.'
          || current == '_'
          || current == '-')) {
        return false;
      }
    }
    return true;
  }

  private static int windowsAbsolutePathEnd(String value, int start) {
    int prefixEnd = windowsAbsolutePathPrefixEnd(value, start);
    if (prefixEnd < 0) {
      return -1;
    }
    char quote = start > 0 && isQuote(value.charAt(start - 1)) ? value.charAt(start - 1) : 0;
    int index = prefixEnd;
    while (index < value.length()) {
      char current = value.charAt(index);
      if (current == '\r'
          || current == '\n'
          || (quote != 0 && current == quote)
          || (quote == 0 && isUnquotedPathDelimiter(current))) {
        break;
      }
      if (quote == 0 && Character.isWhitespace(current) && startsLogField(value, index)) {
        break;
      }
      index++;
    }
    while (index > prefixEnd && Character.isWhitespace(value.charAt(index - 1))) {
      index--;
    }
    return index;
  }

  private static boolean isUnquotedPathDelimiter(char value) {
    return value == ','
        || value == ';'
        || value == '|'
        || value == '<'
        || value == '>'
        || value == ')'
        || value == '}'
        || value == ']'
        || isQuote(value);
  }

  private static boolean startsLogField(String value, int whitespace) {
    int index = whitespace;
    while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
      index++;
    }
    if (index >= value.length() || !isAsciiLetter(value.charAt(index))) {
      return index >= value.length();
    }
    int nameStart = index;
    while (index < value.length()) {
      char current = value.charAt(index);
      if (!(isAsciiLetter(current)
          || Character.isDigit(current)
          || current == '_'
          || current == '-'
          || current == '.')) {
        break;
      }
      index++;
    }
    return index > nameStart
        && index - nameStart <= 64
        && index < value.length()
        && (value.charAt(index) == '=' || value.charAt(index) == ':');
  }

  private static int pathComponentEnd(String value, int start) {
    int index = start;
    while (index < value.length()) {
      char current = value.charAt(index);
      if (isPathSeparator(current)
          || Character.isWhitespace(current)
          || current == '\r'
          || current == '\n'
          || current == ','
          || current == ';'
          || isQuote(current)) {
        break;
      }
      index++;
    }
    return index;
  }

  private static int skipPathSeparators(String value, int start) {
    int index = start;
    while (index < value.length() && isPathSeparator(value.charAt(index))) {
      index++;
    }
    return index;
  }

  private static boolean startsWithIgnoreCase(String value, int start, String expected) {
    return start >= 0
        && start + expected.length() <= value.length()
        && value.regionMatches(true, start, expected, 0, expected.length());
  }

  private static boolean isPathSeparator(char value) {
    return value == '\\' || value == '/';
  }

  private static boolean isQuote(char value) {
    return value == '\"' || value == '\'';
  }

  private static boolean isAsciiLetter(char value) {
    return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
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
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\/", "/")
            .replace("\\u003a", ":")
            .replace("\\u003A", ":")
            .replace("\\u002f", "/")
            .replace("\\u002F", "/")
            .replace("\\u005c", "\\")
            .replace("\\u005C", "\\")
            .replace("\\u0020", " ")
            .replace("\\u0009", " ")
            .replace("\\t", " ")
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
