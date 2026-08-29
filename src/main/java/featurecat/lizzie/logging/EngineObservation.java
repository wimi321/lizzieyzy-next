package featurecat.lizzie.logging;

import java.io.IOException;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EngineObservation {
  public static final String STAGE_PROCESS_STARTED = "process-started";
  public static final String STAGE_FIRST_STDERR = "first-stderr";
  public static final String STAGE_GTP_NAME = "gtp-name";
  public static final String STAGE_GTP_VERSION = "gtp-version";
  public static final String STAGE_READY = "ready";
  public static final String STAGE_FAILED = "failed";

  private static final String[] STARTUP_STAGE_ORDER = {
    STAGE_PROCESS_STARTED,
    STAGE_FIRST_STDERR,
    STAGE_GTP_NAME,
    STAGE_GTP_VERSION,
    STAGE_READY,
    STAGE_FAILED
  };

  private static final Logger ENGINE = LoggerFactory.getLogger(LogCategories.ENGINE);
  private static final Logger GTP = LoggerFactory.getLogger(LogCategories.GTP);
  private static final Logger TRACE = LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
  private static final Object IDENTITY_LOCK = new Object();
  private static final WeakHashMap<Object, String> ENGINE_IDS = new WeakHashMap<>();
  private static final ConcurrentHashMap<String, StartupState> STARTUPS = new ConcurrentHashMap<>();

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
    return ensureStarted(owner, purpose, null);
  }

  public static String ensureStarted(Object owner, String purpose, EngineBootstrapFacts facts) {
    String existing = identityFor(owner);
    if (existing != null) {
      return existing;
    }
    return startInstance(owner, purpose, facts);
  }

  public static String restartInstance(Object owner, String purpose) {
    return restartInstance(owner, purpose, null);
  }

  public static String restartInstance(Object owner, String purpose, EngineBootstrapFacts facts) {
    ensureStopped(owner, "replaced");
    return startInstance(owner, purpose, facts);
  }

  public static void ensureStopped(Object owner, String reason) {
    String id = identityFor(owner);
    if (id == null) {
      return;
    }
    recordStopped(id, reason);
    discardIdentity(owner);
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
    return LoggingRuntime.current().map(LoggingRuntime::newCommandIdentity).orElse("cmd-none");
  }

  public static void recordStarted(String engineId, String purpose) {
    try {
      if (!runtimeActive() || !ENGINE.isInfoEnabled()) {
        return;
      }
      inContext(
          engineId,
          null,
          () ->
              ENGINE.info(
                  "engine event=started purpose={}", purpose == null ? "unknown" : purpose));
    } catch (RuntimeException ignored) {
    }
  }

  public static void recordBootstrap(String engineId, EngineBootstrapFacts facts) {
    try {
      if (!runtimeActive() || !ENGINE.isInfoEnabled() || engineId == null) {
        return;
      }
      StartupState state = STARTUPS.computeIfAbsent(engineId, ignored -> new StartupState());
      if (!state.bootstrapRecorded.compareAndSet(false, true)) {
        return;
      }
      EngineBootstrapFacts safe = facts == null ? EngineBootstrapFacts.unknown(null) : facts;
      inContext(engineId, null, () -> ENGINE.info("{}", safe.formatLogLine(state.formatStages())));
    } catch (RuntimeException ignored) {
    }
  }

  public static void markStartupStage(String engineId, String stage) {
    try {
      if (engineId == null || stage == null || stage.isEmpty()) {
        return;
      }
      STARTUPS.computeIfAbsent(engineId, ignored -> new StartupState()).mark(stage);
    } catch (RuntimeException ignored) {
    }
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
    try {
      if (!runtimeActive() || !ENGINE.isInfoEnabled()) {
        return;
      }
      markStartupStage(engineId, STAGE_READY);
      String stages = formatKnownStages(engineId);
      inContext(engineId, null, () -> ENGINE.info("engine event=ready{}", stages));
    } catch (RuntimeException ignored) {
    }
  }

  public static void recordFailed(String engineId, String reason) {
    try {
      if (!runtimeActive() || !ENGINE.isWarnEnabled()) {
        return;
      }
      markStartupStage(engineId, STAGE_FAILED);
      String stages = formatKnownStages(engineId);
      inContext(
          engineId,
          null,
          () ->
              ENGINE.warn(
                  "engine event=failed reason={}{}", reason == null ? "unknown" : reason, stages));
    } catch (RuntimeException ignored) {
    }
  }

  public static void recordTransportFailure(
      String engineId, String stream, String reason, Throwable error) {
    if (!runtimeActive() || !ENGINE.isWarnEnabled()) {
      return;
    }
    String safeStream = safeTransportStream(stream);
    String safeReason =
        switch (reason == null ? "" : reason) {
          case "unexpected-eof", "io-error", "reader-error", "shutdown-error" -> reason;
          default -> "unknown";
        };
    String errorType =
        error == null
            ? "none"
            : error instanceof IOException
                ? "IOException"
                : error instanceof RuntimeException ? "RuntimeException" : "Throwable";
    inContext(
        engineId,
        null,
        () ->
            ENGINE.warn(
                "engine event=transport-failure stream={} reason={} errorType={}",
                safeStream,
                safeReason,
                errorType));
  }

  private static String safeTransportStream(String stream) {
    if ("stdout".equals(stream) || "stderr".equals(stream)) {
      return stream;
    }
    return "unknown";
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

  public static void recordProbeStarted(String engineId) {
    try {
      if (!runtimeActive() || !ENGINE.isInfoEnabled()) {
        return;
      }
      inContext(engineId, null, () -> ENGINE.info("probe event=started"));
    } catch (RuntimeException ignored) {
    }
  }

  public static void recordProbeCapabilityCheck(String engineId, boolean success) {
    try {
      if (!runtimeActive() || !ENGINE.isInfoEnabled()) {
        return;
      }
      inContext(
          engineId,
          null,
          () ->
              ENGINE.info(
                  "probe event=capability-check outcome={}", success ? "success" : "failure"));
    } catch (RuntimeException ignored) {
    }
  }

  public static void recordProbeFailed(String engineId, String stage) {
    try {
      if (!runtimeActive() || !ENGINE.isWarnEnabled()) {
        return;
      }
      String safeStage = safeProbeStage(stage);
      inContext(engineId, null, () -> ENGINE.warn("probe event=failed stage={}", safeStage));
    } catch (RuntimeException ignored) {
    }
  }

  public static void recordProbeStderr(String engineId, String facts) {
    try {
      if (!runtimeActive() || !ENGINE.isWarnEnabled() || facts == null || facts.isEmpty()) {
        return;
      }
      String bounded = ObservationText.boundedRawEvent(facts);
      inContext(engineId, null, () -> ENGINE.warn("probe event=stderr facts={}", bounded));
    } catch (RuntimeException ignored) {
    }
  }

  private static String safeProbeStage(String stage) {
    return switch (stage == null ? "" : stage) {
      case "start",
          "capability",
          "schema",
          "handshake",
          "timeout",
          "exited",
          "apply",
          "interrupted",
          "reader" ->
          stage;
      default -> "unknown";
    };
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
    inContext(engineId, commandId, () -> GTP.debug("gtp command={} outcome={}", name, "sent"));
  }

  public static void recordCommandOutcome(
      String engineId, String commandId, String name, String outcome, long latencyMs) {
    if (!gtpDiagnosticsEnabled()) {
      return;
    }
    inContext(
        engineId,
        commandId,
        () -> GTP.debug("gtp command={} outcome={} latencyMs={}", name, outcome, latencyMs));
  }

  public static void traceRawCommand(String engineId, String commandId, String command) {
    if (!traceEnabled() || command == null) {
      return;
    }
    inContext(
        engineId,
        commandId,
        () -> TRACE.info("gtp raw command={}", ObservationText.boundedRawEvent(command)));
  }

  public static void traceRawResponse(String engineId, String commandId, String response) {
    if (!traceEnabled() || response == null) {
      return;
    }
    inContext(
        engineId,
        commandId,
        () -> TRACE.info("gtp raw response={}", ObservationText.boundedRawEvent(response)));
  }

  public static void traceRawStream(String engineId, String commandId, String line) {
    if (!traceEnabled() || line == null) {
      return;
    }
    inContext(
        engineId,
        commandId,
        () -> TRACE.info("gtp raw stream={}", ObservationText.boundedRawEvent(line)));
  }

  public static void inContext(String engineId, String commandId, Runnable action) {
    if (action == null) {
      return;
    }
    String trace = LoggingRuntime.current().map(LoggingRuntime::currentTraceSessionId).orElse(null);
    try (CorrelationContext.Scope scope =
        CorrelationContext.openScope().installEngine(engineId).installCommand(commandId)) {
      if (trace != null) {
        scope.installTraceSession(trace);
      }
      action.run();
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
    String id = LoggingRuntime.current().map(LoggingRuntime::newEngineIdentity).orElse("eng-none");
    if (owner != null) {
      synchronized (IDENTITY_LOCK) {
        String previous = ENGINE_IDS.put(owner, id);
        if (previous != null && !previous.equals(id)) {
          STARTUPS.remove(previous);
        }
      }
    }
    STARTUPS.put(id, new StartupState());
    return id;
  }

  public static void discardIdentity(Object owner) {
    if (owner == null) {
      return;
    }
    String removed;
    synchronized (IDENTITY_LOCK) {
      removed = ENGINE_IDS.remove(owner);
    }
    if (removed != null) {
      STARTUPS.remove(removed);
    }
  }

  /** Atomically detaches only the expected runtime identity from {@code owner}. */
  public static boolean discardIdentityIfCurrent(Object owner, String expectedIdentity) {
    if (owner == null || expectedIdentity == null) {
      return false;
    }
    synchronized (IDENTITY_LOCK) {
      if (!expectedIdentity.equals(ENGINE_IDS.get(owner))) {
        return false;
      }
      ENGINE_IDS.remove(owner);
    }
    STARTUPS.remove(expectedIdentity);
    return true;
  }

  private static String startInstance(Object owner, String purpose, EngineBootstrapFacts facts) {
    String id = allocateIdentity(owner);
    try {
      EngineBootstrapFacts safe = facts == null ? EngineBootstrapFacts.unknown(purpose) : facts;
      recordBootstrap(id, safe);
      recordStarted(id, purpose);
    } catch (RuntimeException ignored) {
    }
    return id;
  }

  private static String formatKnownStages(String engineId) {
    if (engineId == null) {
      return "";
    }
    StartupState state = STARTUPS.get(engineId);
    return state == null ? "" : state.formatStages();
  }

  private static final class StartupState {
    private final long startNanos = System.nanoTime();
    private final ConcurrentHashMap<String, Long> stages = new ConcurrentHashMap<>();
    private final AtomicBoolean bootstrapRecorded = new AtomicBoolean();

    private void mark(String stage) {
      stages.putIfAbsent(stage, elapsedMs());
    }

    private long elapsedMs() {
      return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startNanos));
    }

    private String formatStages() {
      StringBuilder rendered = new StringBuilder();
      for (String stage : STARTUP_STAGE_ORDER) {
        Long elapsed = stages.get(stage);
        if (elapsed == null) {
          continue;
        }
        rendered.append(' ').append(stage).append('=').append(elapsed).append("ms");
      }
      return rendered.toString();
    }
  }
}
