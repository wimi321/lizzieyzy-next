package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class EngineObservationTest {
  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    LoggingRuntime.resetForTests();
  }

  @Test
  void withoutRuntimeDoesNotEmitEvenWhenLoggersAreEnabled() {
    LoggingRuntime.resetForTests();
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    Logger gtp = (Logger) LoggerFactory.getLogger(LogCategories.GTP);
    Logger trace = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
    engine.setLevel(Level.DEBUG);
    gtp.setLevel(Level.DEBUG);
    trace.setLevel(Level.INFO);
    ListAppender<ILoggingEvent> engineEvents = attach(engine);
    ListAppender<ILoggingEvent> gtpEvents = attach(gtp);
    ListAppender<ILoggingEvent> traceEvents = attach(trace);

    assertFalse(EngineObservation.engineDiagnosticsEnabled());
    assertFalse(EngineObservation.gtpDiagnosticsEnabled());
    assertFalse(EngineObservation.traceEnabled());

    EngineObservation.recordStarted("eng-1", "MAIN_BOARD");
    EngineObservation.recordBootstrap("eng-1", EngineBootstrapFacts.unknown("MAIN_BOARD"));
    EngineObservation.recordQueue("eng-1", 1, 1);
    EngineObservation.recordCommandSent("eng-1", "cmd-1", "play", 0, 1);
    EngineObservation.recordProbeStarted("eng-1");
    EngineObservation.recordProbeCapabilityCheck("eng-1", true);
    EngineObservation.recordProbeFailed("eng-1", "exited");
    EngineObservation.recordProbeStderr("eng-1", "cuda failed");
    EngineObservation.traceRawCommand("eng-1", "cmd-1", "play B D4");

    assertTrue(engineEvents.list.isEmpty(), engineEvents.list.toString());
    assertTrue(gtpEvents.list.isEmpty(), gtpEvents.list.toString());
    assertTrue(traceEvents.list.isEmpty(), traceEvents.list.toString());
  }

  @Test
  void initializedRuntimeWithoutFullTraceDoesNotEmitRawCommands() {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Logger trace = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
    ListAppender<ILoggingEvent> traceEvents = attach(trace);

    assertFalse(EngineObservation.traceEnabled());
    EngineObservation.traceRawCommand("eng-1", "cmd-1", "play B D4");
    runtime.awaitIdle();

    assertTrue(traceEvents.list.isEmpty(), traceEvents.list.toString());
  }

  @Test
  void rawTraceEventIsUtf8BoundedBeforeItReachesTheAppender() {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    Logger trace = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
    ListAppender<ILoggingEvent> traceEvents = attach(trace);

    EngineObservation.traceRawStream("eng-1", "cmd-1", "棋😀".repeat(10_000));

    assertEquals(1, traceEvents.list.size(), traceEvents.list.toString());
    String message = traceEvents.list.get(0).getFormattedMessage();
    String payload = message.substring(message.indexOf('=') + 1);
    assertTrue(
        payload.getBytes(StandardCharsets.UTF_8).length <= ObservationText.RAW_EVENT_MAX_UTF8_BYTES,
        Integer.toString(payload.getBytes(StandardCharsets.UTF_8).length));
    assertTrue(payload.endsWith(" [truncated]"), payload);
  }

  @Test
  void transportFailureUsesBoundedStructuredDimensions() {
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> events = attach(engine);

    EngineObservation.recordTransportFailure(
        "eng-1", "stdout", "io-error", new java.io.IOException("secret raw payload"));

    assertEquals(1, events.list.size(), events.list.toString());
    String message = events.list.get(0).getFormattedMessage();
    assertTrue(message.contains("event=transport-failure"), message);
    assertTrue(message.contains("stream=stdout"), message);
    assertTrue(message.contains("reason=io-error"), message);
    assertTrue(message.contains("errorType=IOException"), message);
    assertFalse(message.contains("secret raw payload"), message);
  }

  @Test
  void bootstrapUsesStructuredFieldsAndOmitsUnknownStages() {
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> events = attach(engine);

    EngineBootstrapFacts facts =
        EngineBootstrapFacts.fromCommand(
            "\"C:\\\\Users\\\\Player\\\\katago.exe\" gtp -model model.bin.gz", "MAIN_BOARD");
    EngineObservation.recordBootstrap("eng-bootstrap", facts);

    assertEquals(1, events.list.size(), events.list.toString());
    String message = events.list.get(0).getFormattedMessage();
    assertTrue(message.contains("event=bootstrap"), message);
    assertTrue(message.contains("engineType=katago"), message);
    assertTrue(message.contains("purpose=MAIN_BOARD"), message);
    assertTrue(message.contains("source=user-configured"), message);
    assertTrue(message.contains("backend=unknown"), message);
    assertTrue(message.contains("onnxProvider=unknown"), message);
    assertTrue(message.contains("model=model.bin.gz"), message);
    assertFalse(message.contains("Player"), message);
    assertFalse(message.contains("process-started="), message);
  }

  @Test
  void readyAndFailedReuseTheSameIdentityAsBootstrap() {
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> events = attach(engine);

    Object owner = new Object();
    String id =
        EngineObservation.ensureStarted(
            owner, "MAIN_BOARD", EngineBootstrapFacts.fromCommand("katago gtp", "MAIN_BOARD"));
    EngineObservation.markStartupStage(id, EngineObservation.STAGE_PROCESS_STARTED);
    EngineObservation.recordReady(id);

    assertEquals(id, EngineObservation.identityFor(owner));
    String readyLogs = formatted(events);
    assertTrue(readyLogs.contains("event=bootstrap"), readyLogs);
    assertTrue(readyLogs.contains("event=started"), readyLogs);
    assertTrue(readyLogs.contains("event=ready"), readyLogs);
    assertTrue(readyLogs.contains("process-started="), readyLogs);

    events.list.clear();
    EngineObservation.discardIdentity(owner);
    String failedId = EngineObservation.mintIdentity(owner);
    EngineObservation.recordBootstrap(
        failedId, EngineBootstrapFacts.fromCommand("katago gtp", "MAIN_BOARD"));
    EngineObservation.recordFailed(failedId, "process start failed");
    String failedLogs = formatted(events);
    assertTrue(failedLogs.contains("event=bootstrap"), failedLogs);
    assertTrue(failedLogs.contains("event=failed reason=process start failed"), failedLogs);
    assertFalse(failedLogs.contains("event=started"), failedLogs);
  }

  @Test
  void probeEventsStayVisibleWithoutEngineDiagnosticsDebugAndBoundStderr() {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> events = attach(engine);

    EngineObservation.recordRecentStderr("eng-1", "debug-only-stderr");
    EngineObservation.recordProbeStarted("eng-1");
    EngineObservation.recordProbeCapabilityCheck("eng-1", false);
    EngineObservation.recordProbeFailed("eng-1", "exited");
    EngineObservation.recordProbeFailed("eng-1", "free-form exception text");
    EngineObservation.recordProbeStderr("eng-1", "x".repeat(100_000));

    String logs = formatted(events);
    assertFalse(logs.contains("debug-only-stderr"), logs);
    assertFalse(logs.contains("engine event=stderr"), logs);
    assertTrue(logs.contains("probe event=started"), logs);
    assertTrue(logs.contains("probe event=capability-check outcome=failure"), logs);
    assertTrue(logs.contains("probe event=failed stage=exited"), logs);
    assertTrue(logs.contains("probe event=failed stage=unknown"), logs);
    assertFalse(logs.contains("free-form exception text"), logs);
    ILoggingEvent stderrEvent = null;
    for (ILoggingEvent event : events.list) {
      if (event.getFormattedMessage().contains("probe event=failed stage=exited")) {
        assertEquals(Level.WARN, event.getLevel(), event.getFormattedMessage());
      }
      if (event.getFormattedMessage().contains("probe event=stderr facts=")) {
        stderrEvent = event;
      }
    }
    assertTrue(stderrEvent != null, logs);
    assertEquals(Level.WARN, stderrEvent.getLevel());
    String stderrMessage = stderrEvent.getFormattedMessage();
    String facts = stderrMessage.substring(stderrMessage.indexOf("facts=") + "facts=".length());
    assertTrue(
        facts.getBytes(StandardCharsets.UTF_8).length <= ObservationText.RAW_EVENT_MAX_UTF8_BYTES,
        Integer.toString(facts.getBytes(StandardCharsets.UTF_8).length));
    assertTrue(facts.endsWith(" [truncated]"), facts);
  }

  @Test
  void bootstrapIsRecordedOncePerIdentity() {
    LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> events = attach(engine);

    String id = EngineObservation.mintIdentity(new Object());
    EngineBootstrapFacts facts = EngineBootstrapFacts.fromCommand("katago gtp", "MAIN_BOARD");
    EngineObservation.recordBootstrap(id, facts);
    EngineObservation.recordBootstrap(id, facts);

    assertEquals(1, events.list.size(), events.list.toString());
    assertTrue(events.list.get(0).getFormattedMessage().contains("event=bootstrap"));
  }

  private static String formatted(ListAppender<ILoggingEvent> events) {
    StringBuilder text = new StringBuilder();
    for (ILoggingEvent event : events.list) {
      text.append(event.getFormattedMessage()).append('\n');
    }
    return text.toString();
  }

  private static ListAppender<ILoggingEvent> attach(Logger logger) {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }
}
