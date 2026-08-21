package featurecat.lizzie.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SgfObservation {
  private static final Logger SGF = LoggerFactory.getLogger(LogCategories.SGF);

  private SgfObservation() {}

  public static void record(String operation, String outcome, String path, Throwable error) {
    if (!initialized()) {
      return;
    }
    String safeOperation = operation == null || operation.isEmpty() ? "unknown" : operation;
    String safeOutcome = outcome == null || outcome.isEmpty() ? "unknown" : outcome;
    String safePath = path == null ? "" : path;
    if (error != null) {
      if (!SGF.isWarnEnabled()) {
        return;
      }
      SGF.warn("sgf operation={} outcome={} path={}", safeOperation, safeOutcome, safePath, error);
      return;
    }
    if (!SGF.isInfoEnabled()) {
      return;
    }
    SGF.info("sgf operation={} outcome={} path={}", safeOperation, safeOutcome, safePath);
  }

  private static boolean initialized() {
    return LoggingRuntime.current().filter(runtime -> !runtime.isShutdown()).isPresent();
  }
}
