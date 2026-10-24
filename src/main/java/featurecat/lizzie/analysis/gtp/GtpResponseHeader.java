package featurecat.lizzie.analysis.gtp;

/** Pure parsing of GTP response IDs and successful KataGo parameter values. */
public final class GtpResponseHeader {
  private GtpResponseHeader() {}

  public static int commandId(String line, int missing) {
    if (line == null || line.length() < 2) return missing;
    char prefix = line.charAt(0);
    if ((prefix != '=' && prefix != '?') || !isAsciiDigit(line.charAt(1))) return missing;
    int end = 1;
    while (end < line.length() && isAsciiDigit(line.charAt(end))) end++;
    if (end < line.length() && !Character.isWhitespace(line.charAt(end))) return missing;
    try {
      return Integer.parseInt(line.substring(1, end));
    } catch (NumberFormatException invalidId) {
      return missing;
    }
  }

  /** Retains the legacy parameter parser's support for unnumbered numeric values. */
  public static String parameterValue(String line) {
    if (line == null) {
      return "";
    }
    String trimmed = line.trim();
    if (!trimmed.startsWith("=")) {
      return "";
    }
    String value = trimmed.substring(1).trim();
    int separator = value.indexOf(' ');
    if (separator > 0
        && value
            .substring(0, separator)
            .chars()
            .allMatch(character -> character >= '0' && character <= '9')) {
      return value.substring(separator + 1).trim();
    }
    return value;
  }

  private static boolean isAsciiDigit(char value) {
    return value >= '0' && value <= '9';
  }
}
