package featurecat.lizzie.analysis.gtp;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Progress belongs to one physical engine stream, never to a replacement process. */
public final class StartupTuningProgress {
  private static final long MAX_TUNING_NANOS = TimeUnit.HOURS.toNanos(1);
  private static final Pattern PROGRESS =
      Pattern.compile("tuning\\s+(?:\\*\\s+)?\\d+/\\d+(?:\\s.*)?");
  private static final Pattern FAILURE = Pattern.compile("\\b(?:failed|error)\\b");
  private static final Pattern LOG_PREFIX =
      Pattern.compile("^(?:\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}[+-]\\d{4})?:\\s*");

  private record Progress(long first, long last) {}

  private final AtomicReference<Progress> progress = new AtomicReference<>();

  public boolean observe(String line, long now) {
    if (!isProgress(line)) return false;
    progress.updateAndGet(
        old ->
            old == null
                ? new Progress(now, now)
                : new Progress(old.first(), Math.max(old.last(), now)));
    return true;
  }

  public long extendDeadline(long original, long now, long idleMillis) {
    Progress value = progress.get();
    if (value == null) return original;
    long idle = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, idleMillis));
    long remaining =
        Math.min(idle - (now - value.last()), MAX_TUNING_NANOS - (now - value.first()));
    return Math.max(original, now + remaining);
  }

  static boolean isProgress(String line) {
    if (line == null) return false;
    String text = LOG_PREFIX.matcher(line.trim()).replaceFirst("").toLowerCase(Locale.ROOT);
    // Reject ordinary diagnostics (including failures), not every mention of "tuning".
    if (FAILURE.matcher(text).find()) return false;
    return text.startsWith("beginning gpu tuning for ")
        || text.equals("performing autotuning")
        || text.startsWith("performing autotuning for ")
        || text.startsWith("started opencl sgemm")
        || text.startsWith("tuning xgemm")
        || text.startsWith("tuning hgemm")
        || text.startsWith("tuning winograd ")
        || text.startsWith("tuning global pooling ")
        || text.startsWith("tuning transformer")
        || text.startsWith("tuning spatialrmsnorm")
        || text.startsWith("tuning pointwise")
        || text.startsWith("tuning addchannelbiases")
        || PROGRESS.matcher(text).matches();
  }
}
