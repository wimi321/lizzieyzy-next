package featurecat.lizzie.analysis.gtp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Protocol decisions that do not depend on an engine's product name. */
public final class GtpEngineProtocol {
  private static final Pattern KATA_VERSION =
      Pattern.compile("([0-9]+)\\.([0-9]+)(?:\\.([0-9]+))?(?:[-+ ].*)?");

  private GtpEngineProtocol() {}

  public record KataVersion(boolean movesOwnership, boolean rootInfo) {}

  public static KataVersion kataVersion(String version) {
    Matcher parts = KATA_VERSION.matcher(version == null ? "" : version);
    if (!parts.matches()) {
      return new KataVersion(false, false);
    }
    try {
      int major = Integer.parseInt(parts.group(1));
      int minor = Integer.parseInt(parts.group(2));
      int patch = parts.group(3) == null ? 0 : Integer.parseInt(parts.group(3));
      return new KataVersion(
          major > 1 || (major == 1 && minor > 10),
          major > 1 || (major == 1 && (minor > 16 || (minor == 16 && patch >= 4))));
    } catch (NumberFormatException invalid) {
      return new KataVersion(false, false);
    }
  }
}
