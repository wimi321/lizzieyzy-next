package featurecat.lizzie.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReadBoardObservation {
  private static final Logger DIAG = LoggerFactory.getLogger(LogCategories.READBOARD);
  private static final Logger TRACE = LoggerFactory.getLogger(LogCategories.READBOARD_TRACE);

  private ReadBoardObservation() {}

  public static boolean diagnosticsEnabled() {
    return initialized() && DIAG.isDebugEnabled();
  }

  public static boolean traceEnabled() {
    return LoggingRuntime.current()
            .filter(runtime -> !runtime.isShutdown() && runtime.fullTraceActive())
            .isPresent()
        && TRACE.isInfoEnabled();
  }

  private static boolean initialized() {
    return LoggingRuntime.current().filter(runtime -> !runtime.isShutdown()).isPresent();
  }

  public static void inContext(
      String engineId, String gmaId, String sessionId, Runnable action) {
    if (action == null) {
      return;
    }
    String trace =
        LoggingRuntime.current().map(LoggingRuntime::currentTraceSessionId).orElse(null);
    try {
      CorrelationContext.installEngine(engineId);
      CorrelationContext.installGma(gmaId);
      CorrelationContext.installSyncSession(sessionId);
      if (trace != null) {
        CorrelationContext.installTraceSession(trace);
      }
      action.run();
    } finally {
      CorrelationContext.clearSyncSession();
      CorrelationContext.clearGma();
      CorrelationContext.clearEngine();
      CorrelationContext.clearTraceSession();
    }
  }

  public static void recordDecision(String result, String reason, long epoch, String platform) {
    if (result == null || result.isEmpty()) {
      return;
    }
    String safeReason = reason == null ? "unknown" : reason;
    String safePlatform = platform == null ? "unknown" : platform;
    if (diagnosticsEnabled()) {
      DIAG.debug(
          "readboard event=decision result={} reason={} epoch={} platform={}",
          result,
          safeReason,
          epoch,
          safePlatform);
    }
    if (traceEnabled()) {
      TRACE.info(
          "readboard raw decision result={} reason={} epoch={} platform={}",
          result,
          safeReason,
          epoch,
          safePlatform);
    }
  }

  public static void recordGma(String phase, String outcome) {
    if (!diagnosticsEnabled()) {
      return;
    }
    DIAG.debug(
        "readboard event=gma phase={} outcome={}",
        phase == null || phase.isEmpty() ? "unknown" : phase,
        outcome == null || outcome.isEmpty() ? "unknown" : outcome);
  }

  public static void recordLocalMove(String outcome, String reason) {
    if (!diagnosticsEnabled()) {
      return;
    }
    DIAG.debug(
        "readboard event=local-move outcome={} reason={}",
        outcome == null || outcome.isEmpty() ? "unknown" : outcome,
        reason == null || reason.isEmpty() ? "unknown" : reason);
  }

  public static void recordFailure(String reason, Throwable error) {
    if (!initialized() || !DIAG.isWarnEnabled()) {
      return;
    }
    if (error == null) {
      DIAG.warn("readboard event=failed reason={}", reason == null ? "unknown" : reason);
      return;
    }
    DIAG.warn("readboard event=failed reason={}", reason == null ? "unknown" : reason, error);
  }

  public static void recordLifecycle(String event, String detail) {
    if (!initialized() || !DIAG.isInfoEnabled()) {
      return;
    }
    DIAG.info(
        "readboard event={} detail={}",
        event == null || event.isEmpty() ? "lifecycle" : event,
        detail == null ? "" : detail);
  }

  public static void recordYikeSession(
      String reason,
      String activeSession,
      Boolean syncReady,
      Boolean geometryReady,
      String pendingSession) {
    if (!diagnosticsEnabled()) {
      return;
    }
    DIAG.debug(
        "readboard event=yike-session reason={} active={} pending={} syncReady={} geometryReady={}",
        reason == null || reason.isEmpty() ? "unknown" : reason,
        activeSession == null || activeSession.isEmpty() ? "none" : activeSession,
        pendingSession == null || pendingSession.isEmpty() ? "none" : pendingSession,
        syncReady,
        geometryReady);
  }

  public static void traceProtocol(String summary) {
    if (!traceEnabled() || summary == null) {
      return;
    }
    TRACE.info("readboard raw protocol={}", summary);
  }
}
