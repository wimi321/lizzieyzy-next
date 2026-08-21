package featurecat.lizzie.logging;

import java.util.Optional;
import java.util.WeakHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EngineObservation {
  private static final Logger ENGINE = LoggerFactory.getLogger(LogCategories.ENGINE);
  private static final Logger GTP = LoggerFactory.getLogger(LogCategories.GTP);
  private static final Logger TRACE = LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
  private static final Object IDENTITY_LOCK = new Object();
  private static final WeakHashMap<Object, String> ENGINE_IDS = new WeakHashMap<>();

  private EngineObservation() {}

  public static boolean engineDiagnosticsEnabled() {
    return activeRuntime().isPresent() && ENGINE.isDebugEnabled();
  }

  public static boolean gtpDiagnosticsEnabled() {
    return activeRuntime().isPresent() && GTP.isDebugEnabled();
  }

  public static boolean traceEnabled() {
    return activeRuntime().filter(LoggingRuntime::fullTraceActive).isPresent()
        && TRACE.isInfoEnabled();
  }

  public static String identityFor(Object owner) {
    if (owner == null) {
      return null;
    }
    synchronized (IDENTITY_LOCK) {
      return ENGINE_IDS.get(owner);
    }
  }

  public static String ensureStarted(Object owner, String purpose) {
    String existing = identityFor(owner);
    if (existing != null) {
      return existing;
    }
    return startInstance(owner, purpose);
  }

  public static String restartInstance(Object owner, String purpose) {
    ensureStopped(owner, "replaced");
    return startInstance(owner, purpose);
  }

  public static void ensureStopped(Object owner, String reason) {
    String id = identityFor(owner);
    if (id == null) {
      return;
    }
    recordStopped(id, reason);
    synchronized (IDENTITY_LOCK) {
      ENGINE_IDS.remove(owner);
    }
  }

  public static String commandName(String command) {
    if (command == null) {
      return "";
    }
    String trimmed = command.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    int space = trimmed.indexOf(' ');
    String first = space < 0 ? trimmed : trimmed.substring(0, space);
    int i = 0;
    while (i < first.length() && Character.isDigit(first.charAt(i))) {
      i++;
    }
    if (i > 0 && i < first.length()) {
      return first.substring(i);
    }
    return first;
  }

  public static String commandIdentity(int protocolId) {
    if (protocolId >= 0) {
      return Integer.toString(protocolId);
    }
    return LoggingRuntime.current()
        .map(LoggingRuntime::newCommandIdentity)
        .orElse("cmd-none");
  }

  public static void recordStarted(String engineId, String purpose) {
    if (!runtimeActive() || !ENGINE.isInfoEnabled()) {
      return;
    }
    inContext(
        engineId,
        null,
        () -> ENGINE.info("engine event=started purpose={}", purpose == null ? "unknown" : purpose));
  }

  public static void recordStopped(String engineId, String reason) {
    if (!runtimeActive() || !ENGINE.isInfoEnabled()) {
      return;
    }
    inContext(
        engineId,
        null,
        () -> ENGINE.info("engine event=stopped reason={}", reason == null ? "stopped" : reason));
  }

  public static void recordReady(String engineId) {
    if (!runtimeActive() || !ENGINE.isInfoEnabled()) {
      return;
    }
    inContext(engineId, null, () -> ENGINE.info("engine event=ready"));
  }

  public static void recordFailed(String engineId, String reason) {
    if (!runtimeActive() || !ENGINE.isWarnEnabled()) {
      return;
    }
    inContext(
        engineId,
        null,
        () -> ENGINE.warn("engine event=failed reason={}", reason == null ? "unknown" : reason));
  }

  public static void recordQueue(String engineId, int depth, int inFlight) {
    if (!engineDiagnosticsEnabled()) {
      return;
    }
    inContext(
        engineId,
        null,
        () -> ENGINE.debug("engine event=queue depth={} inFlight={}", depth, inFlight));
  }

  public static void recordRecentStderr(String engineId, String facts) {
    if (!engineDiagnosticsEnabled() || facts == null || facts.isEmpty()) {
      return;
    }
    inContext(engineId, null, () -> ENGINE.debug("engine event=stderr facts={}", facts));
  }

  public static void recordThroughput(String engineId, int playouts, double playoutsPerSecond) {
    if (!engineDiagnosticsEnabled()) {
      return;
    }
    inContext(
        engineId,
        null,
        () ->
            ENGINE.debug(
                "engine event=foreground-throughput playouts={} playoutsPerSecond={}",
                playouts,
                playoutsPerSecond));
  }

  public static void recordProcessDetails(
      String engineId, String event, String purpose, long pid, String command) {
    if (!engineDiagnosticsEnabled()) {
      return;
    }
    inContext(
        engineId,
        null,
        () ->
            ENGINE.debug(
                "engine event={} purpose={} pid={} command={}",
                event,
                purpose,
                pid,
                command == null ? "" : command));
  }

  public static void recordCommandSent(
      String engineId, String commandId, String name, int queueDepth, int inFlight) {
    if (engineDiagnosticsEnabled()) {
      recordQueue(engineId, queueDepth, inFlight);
    }
    if (!gtpDiagnosticsEnabled()) {
      return;
    }
    inContext(
        engineId,
        commandId,
        () -> GTP.debug("gtp command={} outcome={}", name, "sent"));
  }

  public static void recordCommandOutcome(
      String engineId, String commandId, String name, String outcome, long latencyMs) {
    if (!gtpDiagnosticsEnabled()) {
      return;
    }
    inContext(
        engineId,
        commandId,
        () ->
            GTP.debug(
                "gtp command={} outcome={} latencyMs={}", name, outcome, latencyMs));
  }

  public static void traceRawCommand(String engineId, String commandId, String command) {
    if (!traceEnabled() || command == null) {
      return;
    }
    inContext(
        engineId, commandId, () -> TRACE.info("gtp raw command={}", command));
  }

  public static void traceRawResponse(String engineId, String commandId, String response) {
    if (!traceEnabled() || response == null) {
      return;
    }
    inContext(
        engineId, commandId, () -> TRACE.info("gtp raw response={}", response));
  }

  public static void traceRawStream(String engineId, String commandId, String line) {
    if (!traceEnabled() || line == null) {
      return;
    }
    inContext(engineId, commandId, () -> TRACE.info("gtp raw stream={}", line));
  }

  public static void inContext(String engineId, String commandId, Runnable action) {
    String trace =
        LoggingRuntime.current().map(LoggingRuntime::currentTraceSessionId).orElse(null);
    try {
      CorrelationContext.installEngine(engineId);
      CorrelationContext.installCommand(commandId);
      if (trace != null) {
        CorrelationContext.installTraceSession(trace);
      }
      action.run();
    } finally {
      CorrelationContext.clearCommand();
      CorrelationContext.clearEngine();
      CorrelationContext.clearTraceSession();
    }
  }

  private static boolean runtimeActive() {
    return activeRuntime().isPresent();
  }

  private static Optional<LoggingRuntime> activeRuntime() {
    return LoggingRuntime.current().filter(runtime -> !runtime.isShutdown());
  }

  public static String allocateIdentity(Object owner) {
    String existing = identityFor(owner);
    if (existing != null) {
      return existing;
    }
    return mintIdentity(owner);
  }

  public static String mintIdentity(Object owner) {
    String id =
        LoggingRuntime.current()
            .map(LoggingRuntime::newEngineIdentity)
            .orElse("eng-none");
    if (owner != null) {
      synchronized (IDENTITY_LOCK) {
        ENGINE_IDS.put(owner, id);
      }
    }
    return id;
  }

  public static void discardIdentity(Object owner) {
    if (owner == null) {
      return;
    }
    synchronized (IDENTITY_LOCK) {
      ENGINE_IDS.remove(owner);
    }
  }

  private static String startInstance(Object owner, String purpose) {
    String id = allocateIdentity(owner);
    recordStarted(id, purpose);
    return id;
  }
}
