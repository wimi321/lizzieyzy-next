package featurecat.lizzie.util.katago;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.function.Function;
import org.json.JSONObject;

/** Checks the actual bundled engine receipt, not the Java runtime's minimum macOS version. */
public final class MacOsEngineCompatibility {
  private MacOsEngineCompatibility() {}

  public static void requireSupported(Path executable, Function<String, String> messages) {
    requireSupported(
        executable,
        System.getProperty("os.name", ""),
        System.getProperty("os.version", ""),
        messages);
  }

  static void requireSupported(
      Path executable, String os, String version, Function<String, String> messages) {
    if (!os.startsWith("Mac") || executable == null || executable.getParent() == null) return;
    Path receipt = executable.getParent().resolve("source-build.json");
    String minimum;
    try {
      if (!Files.isRegularFile(receipt) || Files.size(receipt) > 65536) return;
      JSONObject data = new JSONObject(Files.readString(receipt));
      if (!"project-source-build".equals(data.optString("origin"))) return;
      minimum = data.optString("macOSMinimumVersion");
    } catch (IOException | RuntimeException unreadable) {
      // Older/custom engines without a verified receipt still use normal process validation.
      return;
    }
    if (isOlder(version, minimum)) {
      throw new IllegalStateException(
          MessageFormat.format(
              messages.apply("KataGoSetup.macos.minimumVersion"), minimum, version));
    }
  }

  static boolean isOlder(String actual, String required) {
    if (!actual.matches("\\d{1,3}(?:\\.\\d{1,3}){0,2}")
        || !required.matches("\\d{1,3}(?:\\.\\d{1,3}){0,2}")) return false;
    String[] left = actual.split("\\.");
    String[] right = required.split("\\.");
    for (int i = 0; i < 3; i++) {
      int a = i < left.length ? Integer.parseInt(left[i]) : 0;
      int b = i < right.length ? Integer.parseInt(right[i]) : 0;
      if (a != b) return a < b;
    }
    return false;
  }
}
