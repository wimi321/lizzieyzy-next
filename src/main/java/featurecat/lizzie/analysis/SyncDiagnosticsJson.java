package featurecat.lizzie.analysis;

final class SyncDiagnosticsJson {
  private SyncDiagnosticsJson() {}

  static String quote(String value) {
    if (value == null) {
      return "null";
    }
    StringBuilder out = new StringBuilder(value.length() + 2);
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\':
          out.append("\\\\");
          break;
        case '"':
          out.append("\\\"");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
      }
    }
    out.append('"');
    return out.toString();
  }

  static String field(String name, String value) {
    return quote(name) + ":" + quote(value);
  }

  static String field(String name, boolean value) {
    return quote(name) + ":" + value;
  }

  static String field(String name, int value) {
    return quote(name) + ":" + value;
  }

  static String field(String name, long value) {
    return quote(name) + ":" + value;
  }
}
