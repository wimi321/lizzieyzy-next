package featurecat.lizzie.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Captures a small, non-personal GPU/driver summary without blocking Swing startup. */
public final class GraphicsDriverDiagnostics {
  private static final long PROBE_TIMEOUT_SECONDS = 2L;
  private static final AtomicBoolean STARTED = new AtomicBoolean();
  private static final AtomicReference<String> SUMMARY = new AtomicReference<>("not-probed");

  private GraphicsDriverDiagnostics() {}

  public static void startAsync() {
    if (!isLinux() || !STARTED.compareAndSet(false, true)) {
      return;
    }
    Thread probe =
        new Thread(
            () -> {
              String result = probeNvidiaDriver();
              SUMMARY.set(result);
              org.slf4j.LoggerFactory.getLogger(featurecat.lizzie.logging.LogCategories.DIAGNOSTICS)
                  .info("Graphics driver diagnostics: {}", result);
            },
            "linux-graphics-driver-diagnostics");
    probe.setDaemon(true);
    probe.start();
  }

  public static String summary() {
    return SUMMARY.get();
  }

  static String sanitizeSummary(String output) {
    if (output == null) {
      return "unavailable";
    }
    String compact =
        output
            .replace('\r', ' ')
            .replace('\n', ';')
            .replaceAll("[^\\p{L}\\p{N} .,_+;()/-]", "")
            .replaceAll("\\s+", " ")
            .replaceAll(";+", ";")
            .trim()
            .replaceAll("^;+|;+$", "");
    if (compact.isEmpty()) {
      return "unavailable";
    }
    return compact.length() <= 256 ? compact : compact.substring(0, 256);
  }

  private static String probeNvidiaDriver() {
    Process process = null;
    try {
      process =
          new ProcessBuilder(
                  "nvidia-smi",
                  "--query-gpu=name,driver_version",
                  "--format=csv,noheader,nounits")
              .redirectErrorStream(true)
              .start();
      if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return "timeout";
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return process.exitValue() == 0 ? sanitizeSummary(output) : "unavailable";
    } catch (IOException e) {
      return "unavailable";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "interrupted";
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  private static boolean isLinux() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
  }
}
