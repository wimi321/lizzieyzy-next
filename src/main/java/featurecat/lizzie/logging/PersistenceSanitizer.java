package featurecat.lizzie.logging;

import java.util.regex.Pattern;

public class PersistenceSanitizer {
  public static final String FAILURE_MARKER = "[redaction-failed]";

  private static final Pattern CREDENTIAL_PARAMETER =
      Pattern.compile(
          "(?i)([\\\"']?(?:password|passwd|token|secret|authorization|cookie|set-cookie|connectPassword|zhizi-account-token|zz-socketio-token)[\\\"']?\\s*(?:[=:]\\s*|\\s+)[\\\"']?)([^\\\"'\\s,;}&]+)([\\\"']?)");
  private static final Pattern BEARER_CREDENTIAL =
      Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+\\-/]+=*");
  private static final Pattern BASIC_CREDENTIAL =
      Pattern.compile("(?i)\\bBasic\\s+[A-Za-z0-9+/=_-]+");
  private static final Pattern URL_SECRET =
      Pattern.compile("(?i)([?&](?:token|key|secret|password)=)[^&\\s]+");

  public String sanitize(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    String safe = BASIC_CREDENTIAL.matcher(text).replaceAll("Basic <redacted>");
    safe = BEARER_CREDENTIAL.matcher(safe).replaceAll("Bearer <redacted>");
    safe = CREDENTIAL_PARAMETER.matcher(safe).replaceAll("$1<redacted>$3");
    return URL_SECRET.matcher(safe).replaceAll("$1<redacted>");
  }
}
