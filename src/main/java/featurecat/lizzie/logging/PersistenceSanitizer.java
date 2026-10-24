package featurecat.lizzie.logging;

import java.util.regex.Pattern;

public class PersistenceSanitizer {
  public static final String FAILURE_MARKER = "[redaction-failed]";
  private static final int MAX_CANONICALIZATION_PASSES = 16;
  private static final int MAX_PERCENT_ENCODING_LAYERS = 4;
  private static final int MAX_PERCENT_ENCODING_SCAN_LAYERS = 64;
  private static final int MAX_CREDENTIAL_NAME_CHARS = 64;

  // Headers can contain schemes and whitespace that a token-oriented expression cannot safely
  // enumerate. Once one of these names is present, redact the complete remaining header value.
  // This deliberately prefers over-redaction to persisting a credential under an unknown scheme.
  private static final Pattern SENSITIVE_HEADER =
      Pattern.compile(
          "(?i)(?<![A-Za-z0-9_-])([\\\"']?(?:proxy-authorization|authorization|set-cookie|cookie)[\\\"']?\\s*(?:[:=]\\s*|\\s+))[^\\r\\n]*");
  private static final String CREDENTIAL_NAME =
      "(?:pw|password|passwd|token|secret|connectPassword|zhizi-account-token|zz-socketio-token|machine[-_]?key|(?:x[-_]?)?api[-_]?key|(?:x[-_]?)?access[-_]?token|refresh[-_]?token|client[-_]?secret)";
  private static final Pattern EXACT_CREDENTIAL_NAME =
      Pattern.compile("(?i)^" + CREDENTIAL_NAME + "$");
  private static final Pattern EXACT_SENSITIVE_HEADER_NAME =
      Pattern.compile("(?i)^(?:proxy-authorization|authorization|set-cookie|cookie)$");

  static boolean isCredentialName(String name) {
    if (name == null || name.isEmpty()) {
      return false;
    }
    return EXACT_CREDENTIAL_NAME.matcher(name).matches()
        || EXACT_SENSITIVE_HEADER_NAME.matcher(name).matches();
  }

  // Utils.splitCommand separates arguments on literal spaces; tabs can belong to a password.
  // Keep command values whole instead of applying JSON/query-string punctuation boundaries.
  private static final Pattern COMMAND_CREDENTIAL_PARAMETER =
      Pattern.compile(
          "(?i)((?<![A-Za-z0-9_-])[\"']?(?:--?|/)"
              + CREDENTIAL_NAME
              + "[\"']?\\s+)(?>(?:\"(?>(?:\\\\[\\s\\S])|[^\"\\\\])*(?:\"|$))"
              + "|(?:'(?>(?:\\\\[\\s\\S])|[^'\\\\])*(?:'|$))"
              + "|\\\\[\\s\\S]|[^ \"'\\\\])+");
  private static final Pattern QUOTED_ASSIGNMENT_CREDENTIAL_PARAMETER =
      Pattern.compile(
          "(?i)(?<![A-Za-z0-9_-])([\"'])((?:--?|/)?"
              + CREDENTIAL_NAME
              + "\\s*=\\s*)"
              + "(?>(?:\\\\[\\s\\S])|(?!\\1)[^,\\r\\n])*"
              + "(\\1|,|$)");
  private static final Pattern COMMA_CREDENTIAL_PARAMETER =
      Pattern.compile(
          "(?i)(?:(?<![A-Za-z0-9_-])((?:--?|/)?"
              + CREDENTIAL_NAME
              + "\\s*=\\s*)(?>(?:\\\\[\\s\\S])|[^\\\"',\\r\\n])+(?=,)"
              + "|(?<=,)((?:--?|/)?"
              + CREDENTIAL_NAME
              + "\\s*=\\s*)(?>(?:\\\\[\\s\\S])|[^\\\"',\\r\\n])+(?=[,\\\"']|$))");
  private static final Pattern QUOTED_CREDENTIAL_PARAMETER =
      Pattern.compile(
          "(?i)((?<![A-Za-z0-9_-])(?:--?|/)?[\\\"']?"
              + CREDENTIAL_NAME
              + "[\\\"']?(?![A-Za-z0-9_-])\\s*(?:[=:]\\s*|\\s+))"
              + "([\\\"'])(?>(?:\\\\[\\s\\S])|(?!\\2)[\\s\\S])*(?:\\2|$)");
  private static final Pattern CREDENTIAL_PARAMETER =
      Pattern.compile(
          "(?i)((?<![A-Za-z0-9_-])(?:--?|/)?[\\\"']?"
              + CREDENTIAL_NAME
              + "[\\\"']?(?![A-Za-z0-9_-])\\s*(?:[=:]\\s*|\\s+))([^\\\"'\\s,;}&]+)");
  private static final Pattern URL_SECRET =
      Pattern.compile(
          "(?i)([?&](?:token|key|secret|password|(?:x[-_]?)?api[-_]?key|(?:x[-_]?)?access[-_]?token|refresh[-_]?token|client[-_]?secret)=)[^&\\s]+");
  private static final Pattern URI_USERINFO =
      Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://)[^/@\\s]+@");
  private static final Pattern STANDALONE_BEARER =
      Pattern.compile("(?i)(?<![A-Za-z0-9_-])(bearer\\s+)[^\\s,;\\\"'<>}\\]]+");
  private static final Pattern STANDALONE_BASIC =
      Pattern.compile("(?i)(?<![A-Za-z0-9_-])(basic\\s+)[A-Za-z0-9+/=_-]+");
  // Zhizi startup diagnostics embed the account identifier in this provider-specific field.
  private static final Pattern ZHIZI_DIAGNOSTIC_ACCOUNT =
      Pattern.compile("(?i)((?<![A-Za-z0-9_-])User\\s*:\\s*\\[)zz-[^\\]\\r\\n]*");

  public String sanitize(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    String safe = text;
    for (int pass = 0; pass < MAX_CANONICALIZATION_PASSES; pass++) {
      safe = sanitizeCanonical(safe);
      if (!containsEncodedSyntax(safe)) {
        return safe;
      }
      String canonical = canonicalizeEncodedSyntax(safe);
      if (canonical.equals(safe)) {
        return safe;
      }
      safe = canonical;
    }
    return FAILURE_MARKER;
  }

  private static String sanitizeCanonical(String text) {
    String safe = COMMAND_CREDENTIAL_PARAMETER.matcher(text).replaceAll("$1<redacted>");
    safe = redactPercentEncodedCredentials(safe);
    safe = SENSITIVE_HEADER.matcher(safe).replaceAll("$1<redacted>");
    safe = QUOTED_ASSIGNMENT_CREDENTIAL_PARAMETER.matcher(safe).replaceAll("$1$2<redacted>$3");
    safe =
        COMMA_CREDENTIAL_PARAMETER
            .matcher(safe)
            .replaceAll(mr -> (mr.group(1) != null ? mr.group(1) : mr.group(2)) + "<redacted>");
    safe = QUOTED_CREDENTIAL_PARAMETER.matcher(safe).replaceAll("$1$2<redacted>$2");
    safe = CREDENTIAL_PARAMETER.matcher(safe).replaceAll("$1<redacted>");
    safe = URL_SECRET.matcher(safe).replaceAll("$1<redacted>");
    safe = URI_USERINFO.matcher(safe).replaceAll("$1<redacted>@");
    safe = STANDALONE_BEARER.matcher(safe).replaceAll("$1<redacted>");
    safe = STANDALONE_BASIC.matcher(safe).replaceAll("$1<redacted>");
    return ZHIZI_DIAGNOSTIC_ACCOUNT.matcher(safe).replaceAll("$1<redacted>");
  }

  private static String redactPercentEncodedCredentials(String text) {
    if (text.indexOf('%') < 0) {
      return text;
    }
    // Decode only into a bounded, in-memory view of a possible credential name and assignment.
    // The original prefix is preserved and the value is replaced directly, so decoded secret
    // bytes can never be copied into persisted output.
    StringBuilder redacted = null;
    int copiedThrough = 0;
    int index = 0;
    while (index < text.length()) {
      if (text.charAt(index) == '%' && decodedCharacterAt(text, index).scanLimitExceeded) {
        // The final decoded byte is deliberately not inspected past the absolute bound. It may
        // be a credential boundary, name, or assignment delimiter, so persisting any part of the
        // event would be fail-open.
        return FAILURE_MARKER;
      }
      PercentCredentialPrefix prefix = percentCredentialPrefixAt(text, index);
      if (prefix == null) {
        index++;
        continue;
      }
      if (prefix.failClosed) {
        return FAILURE_MARKER;
      }
      int valueEnd = credentialValueEnd(text, prefix);
      if (valueEnd < 0) {
        index++;
        continue;
      }
      if (redacted == null) {
        redacted = new StringBuilder(text.length());
      }
      redacted.append(text, copiedThrough, prefix.end);
      if (!prefix.header && text.charAt(prefix.end) == '\"') {
        redacted.append('\"').append("<redacted>").append('\"');
      } else if (!prefix.header && text.charAt(prefix.end) == '\'') {
        redacted.append('\'').append("<redacted>").append('\'');
      } else {
        redacted.append("<redacted>");
      }
      copiedThrough = valueEnd;
      index = valueEnd;
    }
    return redacted == null ? text : redacted.append(text, copiedThrough, text.length()).toString();
  }

  private static PercentCredentialPrefix percentCredentialPrefixAt(String text, int start) {
    CredentialBoundary boundary = credentialBoundaryAt(text, start);
    if (!boundary.valid) {
      return null;
    }
    int index = start;
    boolean encoded = boundary.encoded;
    DecodedCharacter next = decodedCharacterAt(text, index);
    if (next.scanLimitExceeded) {
      return PercentCredentialPrefix.FAIL_CLOSED;
    }
    if (next.value == '\"' || next.value == '\'') {
      encoded |= next.encoded;
      index = next.end;
    }

    StringBuilder name = new StringBuilder();
    while (index < text.length() && name.length() < MAX_CREDENTIAL_NAME_CHARS) {
      next = decodedCharacterAt(text, index);
      if (next.scanLimitExceeded) {
        return PercentCredentialPrefix.FAIL_CLOSED;
      }
      if (!isCredentialNameCharacter(next.value)) {
        break;
      }
      name.append(next.value);
      encoded |= next.encoded;
      index = next.end;
    }
    if (name.isEmpty()) {
      return null;
    }
    next = decodedCharacterAt(text, index);
    if (next.scanLimitExceeded) {
      return PercentCredentialPrefix.FAIL_CLOSED;
    }
    if (isCredentialNameCharacter(next.value)) {
      return null;
    }
    if (next.value == '\"' || next.value == '\'') {
      encoded |= next.encoded;
      index = next.end;
    }

    String decodedName = name.toString();
    boolean sensitiveHeader = EXACT_SENSITIVE_HEADER_NAME.matcher(decodedName).matches();
    boolean credential = EXACT_CREDENTIAL_NAME.matcher(decodedName).matches();
    if (!sensitiveHeader && !credential) {
      return null;
    }

    boolean hadWhitespace = false;
    while (index < text.length()) {
      next = decodedCharacterAt(text, index);
      if (next.scanLimitExceeded) {
        return PercentCredentialPrefix.FAIL_CLOSED;
      }
      if (!Character.isWhitespace(next.value)) {
        break;
      }
      hadWhitespace = true;
      encoded |= next.encoded;
      index = next.end;
    }
    next = decodedCharacterAt(text, index);
    if (next.scanLimitExceeded) {
      return PercentCredentialPrefix.FAIL_CLOSED;
    }
    if (next.value == '=' || next.value == ':') {
      encoded |= next.encoded;
      index = next.end;
      while (index < text.length()) {
        next = decodedCharacterAt(text, index);
        if (next.scanLimitExceeded) {
          return PercentCredentialPrefix.FAIL_CLOSED;
        }
        if (!Character.isWhitespace(next.value)) {
          break;
        }
        encoded |= next.encoded;
        index = next.end;
      }
    } else if (!hadWhitespace) {
      return null;
    }
    if (!encoded) {
      return null;
    }

    if (sensitiveHeader) {
      return new PercentCredentialPrefix(index, true);
    }
    if (credential) {
      return new PercentCredentialPrefix(index, false);
    }
    return null;
  }

  private static int credentialValueEnd(String text, PercentCredentialPrefix prefix) {
    int start = prefix.end;
    if (start >= text.length() || text.charAt(start) == '\r' || text.charAt(start) == '\n') {
      return -1;
    }
    if (prefix.header) {
      int end = start;
      while (end < text.length() && text.charAt(end) != '\r' && text.charAt(end) != '\n') {
        end++;
      }
      return end;
    }

    char first = text.charAt(start);
    if (first == '\"' || first == '\'') {
      for (int end = start + 1; end < text.length(); end++) {
        char current = text.charAt(end);
        if (current == '\\' && end + 1 < text.length()) {
          end++;
        } else if (current == first) {
          return end + 1;
        }
      }
      return text.length();
    }
    if (isUnquotedCredentialDelimiter(first)) {
      return -1;
    }
    int end = start;
    while (end < text.length() && !isUnquotedCredentialDelimiter(text.charAt(end))) {
      end++;
    }
    return end;
  }

  private static CredentialBoundary credentialBoundaryAt(String text, int start) {
    if (start <= 0) {
      return CredentialBoundary.RAW;
    }
    char previous = text.charAt(start - 1);
    if (!isCredentialNameCharacter(previous)) {
      return CredentialBoundary.RAW;
    }
    for (int layers = 1; layers <= MAX_PERCENT_ENCODING_SCAN_LAYERS; layers++) {
      int encodedLength = 1 + (layers * 2);
      int encodedStart = start - encodedLength;
      if (encodedStart < 0) {
        break;
      }
      DecodedCharacter decoded = decodedCharacterAt(text, encodedStart);
      if (decoded.encoded && decoded.end == start) {
        return isCredentialNameCharacter(decoded.value)
            ? CredentialBoundary.NONE
            : CredentialBoundary.ENCODED;
      }
    }
    return CredentialBoundary.NONE;
  }

  private static DecodedCharacter decodedCharacterAt(String text, int index) {
    if (index < 0 || index >= text.length()) {
      return DecodedCharacter.END;
    }
    if (text.charAt(index) != '%') {
      return new DecodedCharacter(text.charAt(index), index + 1, false);
    }
    int digits = index + 1;
    int layers = 1;
    // Nested percent encoding grows by one "25" pair per layer, so this is a bounded linear
    // scan over the original text. The decoded byte is only a structural view and is never
    // appended to persisted output.
    while (digits + 3 < text.length() && hexByte(text, digits) == '%') {
      if (layers >= MAX_PERCENT_ENCODING_SCAN_LAYERS) {
        return DecodedCharacter.SCAN_LIMIT_EXCEEDED;
      }
      digits += 2;
      layers++;
    }
    int decoded = hexByte(text, digits);
    if (decoded < 0 || decoded > 0x7f) {
      return new DecodedCharacter('%', index + 1, false);
    }
    return new DecodedCharacter(
        (char) decoded, digits + 2, true, layers > MAX_PERCENT_ENCODING_LAYERS, false);
  }

  private static int hexByte(String text, int digits) {
    if (digits < 0 || digits + 2 > text.length()) {
      return -1;
    }
    int high = Character.digit(text.charAt(digits), 16);
    int low = Character.digit(text.charAt(digits + 1), 16);
    return high < 0 || low < 0 ? -1 : (high << 4) | low;
  }

  private static boolean isCredentialNameCharacter(char value) {
    return (value >= 'A' && value <= 'Z')
        || (value >= 'a' && value <= 'z')
        || (value >= '0' && value <= '9')
        || value == '_'
        || value == '-';
  }

  private static boolean isUnquotedCredentialDelimiter(char value) {
    return value == '\"'
        || value == '\''
        || Character.isWhitespace(value)
        || value == ','
        || value == ';'
        || value == '}'
        || value == '&';
  }

  private static final class PercentCredentialPrefix {
    static final PercentCredentialPrefix FAIL_CLOSED = new PercentCredentialPrefix(-1, false, true);

    final int end;
    final boolean header;
    final boolean failClosed;

    PercentCredentialPrefix(int end, boolean header) {
      this(end, header, false);
    }

    PercentCredentialPrefix(int end, boolean header, boolean failClosed) {
      this.end = end;
      this.header = header;
      this.failClosed = failClosed;
    }
  }

  private static final class CredentialBoundary {
    static final CredentialBoundary NONE = new CredentialBoundary(false, false);
    static final CredentialBoundary RAW = new CredentialBoundary(true, false);
    static final CredentialBoundary ENCODED = new CredentialBoundary(true, true);

    final boolean valid;
    final boolean encoded;

    CredentialBoundary(boolean valid, boolean encoded) {
      this.valid = valid;
      this.encoded = encoded;
    }
  }

  private static final class DecodedCharacter {
    static final DecodedCharacter END = new DecodedCharacter((char) 0, -1, false, false, false);
    static final DecodedCharacter SCAN_LIMIT_EXCEEDED =
        new DecodedCharacter((char) 0, -1, true, true, true);

    final char value;
    final int end;
    final boolean encoded;
    final boolean overDepth;
    final boolean scanLimitExceeded;

    DecodedCharacter(char value, int end, boolean encoded) {
      this(value, end, encoded, false, false);
    }

    DecodedCharacter(
        char value, int end, boolean encoded, boolean overDepth, boolean scanLimitExceeded) {
      this.value = value;
      this.end = end;
      this.encoded = encoded;
      this.overDepth = overDepth;
      this.scanLimitExceeded = scanLimitExceeded;
    }
  }

  private static boolean containsEncodedSyntax(String text) {
    int slash = text.indexOf('\\');
    while (slash >= 0 && slash + 1 < text.length()) {
      char next = text.charAt(slash + 1);
      if (next == '\"' || next == '\'' || next == 'u' || next == 'U') {
        return true;
      }
      slash = text.indexOf('\\', slash + 1);
    }
    return false;
  }

  private static String canonicalizeEncodedSyntax(String text) {
    StringBuilder canonical = new StringBuilder(text.length());
    for (int index = 0; index < text.length(); ) {
      char current = text.charAt(index);
      if (current != '\\') {
        canonical.append(current);
        index++;
        continue;
      }

      int escapeEnd = index;
      while (escapeEnd < text.length() && text.charAt(escapeEnd) == '\\') {
        escapeEnd++;
      }
      int slashCount = escapeEnd - index;
      canonical.append("\\".repeat(slashCount / 2));
      if ((slashCount & 1) == 0) {
        index = escapeEnd;
        continue;
      }
      if (escapeEnd < text.length()
          && (text.charAt(escapeEnd) == '\"' || text.charAt(escapeEnd) == '\'')) {
        canonical.append(text.charAt(escapeEnd));
        index = escapeEnd + 1;
        continue;
      }
      if (escapeEnd + 5 <= text.length()
          && (text.charAt(escapeEnd) == 'u' || text.charAt(escapeEnd) == 'U')) {
        char decoded = decodeStructuralEscape(text, escapeEnd + 1);
        if (decoded != 0) {
          canonical.append(decoded);
          index = escapeEnd + 5;
          continue;
        }
      }
      canonical.append('\\');
      index = escapeEnd;
    }
    return canonical.toString();
  }

  private static char decodeStructuralEscape(String text, int digits) {
    if (digits + 4 > text.length()) {
      return 0;
    }
    int value = 0;
    for (int index = digits; index < digits + 4; index++) {
      int digit = Character.digit(text.charAt(index), 16);
      if (digit < 0) {
        return 0;
      }
      value = value * 16 + digit;
    }
    char structural =
        switch (value) {
          case 0x0009, 0x000a, 0x000d, 0x0020 -> ' ';
          case 0x0022 -> '\"';
          case 0x0027 -> '\'';
          case 0x003a -> ':';
          case 0x003d -> '=';
          case 0x005c -> '\\';
          default -> 0;
        };
    if (structural != 0) {
      return structural;
    }
    char decoded = (char) value;
    return value <= 0x7f && (Character.isLetterOrDigit(decoded) || decoded == '_' || decoded == '-')
        ? decoded
        : 0;
  }
}
