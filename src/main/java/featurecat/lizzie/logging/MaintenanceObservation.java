package featurecat.lizzie.logging;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared maintenance/lifecycle diagnostics. One sanitized key-value line per stage; recording
 * failures never change the business-flow outcome.
 */
public final class MaintenanceObservation {
  public static final String OPERATION_TENSORRT_SETUP = "tensorrt-setup";
  public static final String OPERATION_WEIGHT_DOWNLOAD = "weight-download";

  public static final String STAGE_LOCK = "lock";
  public static final String STAGE_DOWNLOAD = "download";
  public static final String STAGE_VERIFY = "verify";
  public static final String STAGE_EXTRACT = "extract";
  public static final String STAGE_INSTALL = "install";
  public static final String STAGE_APPLY_CONFIG = "apply-config";
  public static final String STAGE_CACHE_CLEANUP = "cache-cleanup";
  public static final String STAGE_EXISTING_FILE = "existing-file";
  public static final String STAGE_HTTP_DOWNLOAD = "http-download";
  public static final String STAGE_MOVE = "move";

  public static final String OUTCOME_SUCCESS = "success";
  public static final String OUTCOME_FAILED = "failed";

  public static final String REASON_FILE_LOCKED = "file-locked";
  public static final String REASON_CANCELLED = "cancelled";
  public static final String REASON_CHECKSUM_MISMATCH = "checksum-mismatch";
  public static final String REASON_INCOMPLETE = "incomplete";
  public static final String REASON_SIZE_MISMATCH = "size-mismatch";
  public static final String REASON_INTEGRITY_CHECK_FAILED = "integrity-check-failed";
  public static final String REASON_UNKNOWN = "unknown";

  public static final int REASON_MAX_UTF8_BYTES = 256;
  public static final int REASON_MAX_LINES = 1;

  private static final Logger DIAG = LoggerFactory.getLogger(LogCategories.DIAGNOSTICS);
  private static final PersistenceSanitizer SANITIZER = new PersistenceSanitizer();
  private static final String UNKNOWN = "unknown";

  private static final Set<String> OPERATIONS =
      Set.of(OPERATION_TENSORRT_SETUP, OPERATION_WEIGHT_DOWNLOAD);
  private static final Set<String> STAGES =
      Set.of(
          STAGE_LOCK,
          STAGE_DOWNLOAD,
          STAGE_VERIFY,
          STAGE_EXTRACT,
          STAGE_INSTALL,
          STAGE_APPLY_CONFIG,
          STAGE_CACHE_CLEANUP,
          STAGE_EXISTING_FILE,
          STAGE_HTTP_DOWNLOAD,
          STAGE_MOVE);
  private static final Set<String> OUTCOMES = Set.of(OUTCOME_SUCCESS, OUTCOME_FAILED);
  private static final Set<String> CLOSED_REASONS =
      Set.of(
          REASON_FILE_LOCKED,
          REASON_CANCELLED,
          REASON_CHECKSUM_MISMATCH,
          REASON_INCOMPLETE,
          REASON_SIZE_MISMATCH,
          REASON_INTEGRITY_CHECK_FAILED,
          REASON_UNKNOWN);

  private static final Pattern HTTP_REASON = Pattern.compile("http-\\d{3}");
  private static final Pattern HTTP_STATUS = Pattern.compile("(?i)\\bHTTP\\s+(\\d{3})\\b");
  private static final Pattern URL =
      Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://\\S+");
  private static final Pattern WINDOWS_ABS_PATH =
      Pattern.compile(
          "(?:[A-Za-z]:[\\\\/]|\\\\\\\\)(?:[^]<>:\"|?*\\r\\n\\\\/]+[\\\\/])*[^]<>:\"|?*\\r\\n\\\\/\\s]+");
  private static final Pattern UNIX_ABS_PATH =
      Pattern.compile(
          "(?<![A-Za-z0-9:])(/(?:[^]/<>:\"|?*\\r\\n]+/)+[^]/<>:\"|?*\\r\\n\\s]+)");

  private MaintenanceObservation() {}

  @FunctionalInterface
  public interface IoTask {
    void run() throws IOException;
  }

  @FunctionalInterface
  public interface IoCall<T> {
    T get() throws IOException;
  }

  public static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
  }

  public static void runStage(String operation, String stage, IoTask task) throws IOException {
    if (task == null) {
      return;
    }
    long started = System.nanoTime();
    try {
      task.run();
    } catch (IOException e) {
      recordFailure(operation, stage, elapsedMillis(started), e);
      throw e;
    } catch (RuntimeException e) {
      recordFailure(operation, stage, elapsedMillis(started), e);
      throw e;
    }
    record(operation, stage, OUTCOME_SUCCESS, elapsedMillis(started), null);
  }

  public static <T> T callStage(String operation, String stage, IoCall<T> task) throws IOException {
    if (task == null) {
      return null;
    }
    long started = System.nanoTime();
    try {
      T result = task.get();
      record(operation, stage, OUTCOME_SUCCESS, elapsedMillis(started), null);
      return result;
    } catch (IOException e) {
      recordFailure(operation, stage, elapsedMillis(started), e);
      throw e;
    } catch (RuntimeException e) {
      recordFailure(operation, stage, elapsedMillis(started), e);
      throw e;
    }
  }

  public static void recordFailure(
      String operation, String stage, long durationMs, Throwable error) {
    record(operation, stage, OUTCOME_FAILED, durationMs, reasonFrom(error));
  }

  public static void record(
      String operation, String stage, String outcome, long durationMs, String reason) {
    try {
      if (!initialized()) {
        return;
      }
      String safeOperation = tokenOrUnknown(operation, OPERATIONS);
      String safeStage = tokenOrUnknown(stage, STAGES);
      String safeOutcome = tokenOrUnknown(outcome, OUTCOMES);
      long safeDuration = Math.max(0L, durationMs);
      boolean failed = OUTCOME_FAILED.equals(safeOutcome);
      if (failed) {
        if (!DIAG.isWarnEnabled()) {
          return;
        }
        String safeReason = sanitizeReason(reason);
        DIAG.warn(
            "maintenance operation={} stage={} outcome={} durationMs={} reason={}",
            safeOperation,
            safeStage,
            safeOutcome,
            safeDuration,
            safeReason);
        return;
      }
      if (!DIAG.isInfoEnabled()) {
        return;
      }
      DIAG.info(
          "maintenance operation={} stage={} outcome={} durationMs={}",
          safeOperation,
          safeStage,
          safeOutcome,
          safeDuration);
    } catch (RuntimeException ignored) {
    } catch (Error error) {
      if (error instanceof VirtualMachineError) {
        throw error;
      }
    }
  }

  static String reasonFrom(Throwable error) {
    if (error == null) {
      return REASON_UNKNOWN;
    }
    if (error instanceof InterruptedIOException) {
      return REASON_CANCELLED;
    }
    String type = error.getClass().getSimpleName();
    String message = error.getMessage() == null ? "" : error.getMessage();
    Matcher http = HTTP_STATUS.matcher(message);
    if (http.find()) {
      return "http-" + http.group(1);
    }
    if (containsIgnoreCase(message, "already running")
        || containsIgnoreCase(message, "file lock")
        || "OverlappingFileLockException".equals(type)) {
      return REASON_FILE_LOCKED;
    }
    if (containsIgnoreCase(message, "checksum")
        || containsIgnoreCase(message, "sha-256")
        || containsIgnoreCase(message, "sha256")
        || containsIgnoreCase(message, "verification failed")) {
      return REASON_CHECKSUM_MISMATCH;
    }
    if (containsIgnoreCase(message, "incomplete")) {
      return REASON_INCOMPLETE;
    }
    if (containsIgnoreCase(message, "size mismatch")) {
      return REASON_SIZE_MISMATCH;
    }
    if ("WeightIntegrityException".equals(type)
        || "CorruptRuntimePackageDownloadException".equals(type)) {
      return REASON_INTEGRITY_CHECK_FAILED;
    }
    String fallback = type.isEmpty() ? message : type + ": " + message;
    return sanitizeReason(fallback);
  }

  static String sanitizeReason(String reason) {
    if (reason == null || reason.isEmpty()) {
      return REASON_UNKNOWN;
    }
    if (CLOSED_REASONS.contains(reason) || HTTP_REASON.matcher(reason).matches()) {
      return reason;
    }
    try {
      String safe = SANITIZER.sanitize(reason);
      if (safe == null || safe.isEmpty()) {
        return REASON_UNKNOWN;
      }
      if (PersistenceSanitizer.FAILURE_MARKER.equals(safe)) {
        return PersistenceSanitizer.FAILURE_MARKER;
      }
      safe = URL.matcher(safe).replaceAll("<redacted-url>");
      safe = WINDOWS_ABS_PATH.matcher(safe).replaceAll("<redacted-path>");
      safe = UNIX_ABS_PATH.matcher(safe).replaceAll("<redacted-path>");
      safe = safe.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
      safe = safe.replaceAll(" {2,}", " ").trim();
      if (safe.isEmpty()) {
        return REASON_UNKNOWN;
      }
      String bounded = ObservationText.boundedUtf8(safe, REASON_MAX_UTF8_BYTES, REASON_MAX_LINES);
      return bounded == null || bounded.isEmpty() ? REASON_UNKNOWN : bounded;
    } catch (RuntimeException ignored) {
      return REASON_UNKNOWN;
    }
  }

  private static String tokenOrUnknown(String value, Set<String> allowed) {
    if (value != null && allowed.contains(value)) {
      return value;
    }
    return UNKNOWN;
  }

  private static boolean containsIgnoreCase(String text, String needle) {
    if (text == null || needle == null || needle.isEmpty()) {
      return false;
    }
    return text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  private static boolean initialized() {
    return LoggingRuntime.current().filter(runtime -> !runtime.isShutdown()).isPresent();
  }
}
