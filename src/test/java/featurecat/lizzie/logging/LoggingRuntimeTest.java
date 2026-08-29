package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLoggerFactory;
import org.slf4j.spi.SLF4JServiceProvider;

class LoggingRuntimeTest {
  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    LoggingRuntime.resetForTests();
  }

  @Test
  void bootstrapWritesStartupEventToAppLog() throws Exception {
    LoggingRuntime runtime = start();

    runtime.awaitIdle();
    assertEquals(tempDir, runtime.workDirectory());
    assertEquals(tempDir.resolve("logs"), runtime.logsDirectory());
    String appLog = read("logs/app.log");
    assertTrue(appLog.contains("application log session started"));
    assertTrue(appLog.contains(runtime.applicationLogSessionId()));
    assertTrue(Files.isRegularFile(tempDir.resolve("logs/crash.log")));
    assertFalse(Files.exists(tempDir.resolve("logs/engine-trace.log")));
  }

  @Test
  void repeatedInitializeReusesRuntimeWithoutDuplicateStartupEvents() throws Exception {
    LoggingRuntime first = start();
    LoggingRuntime second =
        LoggingRuntime.initialize(new WorkDirectoryResolution(tempDir, List.of()), testLimits());
    first.awaitIdle();

    assertEquals(first, second);
    String appLog = read("logs/app.log");
    assertEquals(1, count(appLog, "application log session started"));
  }

  @Test
  void discoversExactlyOneSlf4jProvider() {
    AtomicInteger providers = new AtomicInteger();
    ServiceLoader.load(SLF4JServiceProvider.class).forEach(provider -> providers.incrementAndGet());
    assertEquals(1, providers.get());
  }

  @Test
  void diagnosticsEnableSelectedModuleImmediately() throws Exception {
    LoggingRuntime runtime = start();
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
    org.slf4j.Logger engine = LoggerFactory.getLogger(LogCategories.ENGINE);
    engine.debug("hidden-before-diagnostics");
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(true));
    engine.debug("visible-after-diagnostics");
    runtime.awaitIdle();

    String appLog = read("logs/app.log");
    assertFalse(appLog.contains("hidden-before-diagnostics"));
    assertTrue(appLog.contains("visible-after-diagnostics"));
  }

  @Test
  void persistFailureRestoresPreviousRuntimePlan() throws Exception {
    LoggingRuntime runtime = start();
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
    LoggingSettings enabled = LoggingSettings.defaults().withDiagnosticsEnabled(true);
    assertThrows(
        IllegalStateException.class,
        () ->
            runtime.applySettings(
                enabled,
                settings -> {
                  throw new IOException("disk full");
                }));
    assertFalse(runtime.settings().diagnosticsEnabled());
    LoggerFactory.getLogger(LogCategories.ENGINE).debug("should-remain-hidden");
    runtime.awaitIdle();
    assertFalse(read("logs/app.log").contains("should-remain-hidden"));
  }

  @Test
  void fullTraceStartStopWritesDistinctSessionsToSelectedStreamsOnly() throws Exception {
    LoggingRuntime runtime = start();
    runtime.applySettings(
        LoggingSettings.defaults().withPreferredTraceScopes(EnumSet.of(TraceScope.ENGINE_GTP)));
    runtime.startFullTrace();
    String first = runtime.currentTraceSessionId();
    LoggerFactory.getLogger(LogCategories.ENGINE_TRACE).info("raw-gtp-1");
    runtime.stopFullTrace();
    runtime.startFullTrace();
    String second = runtime.currentTraceSessionId();
    LoggerFactory.getLogger(LogCategories.ENGINE_TRACE).info("raw-gtp-2");
    runtime.awaitIdle();
    runtime.stopFullTrace();
    runtime.awaitIdle();

    assertNotEquals(first, second);
    String trace = read("logs/engine-trace.log");
    assertTrue(trace.contains("Full Trace session started"), trace);
    assertTrue(trace.contains("Full Trace session stopped"), trace);
    assertTrue(trace.contains(first), "missing first=" + first + " in " + trace);
    assertTrue(trace.contains(second), "missing second=" + second + " in " + trace);
    assertTrue(trace.contains("raw-gtp-1"));
    assertFalse(Files.exists(tempDir.resolve("logs/readboard-trace.log")));
    assertFalse(read("logs/app.log").contains("raw-gtp-1"));
  }

  @Test
  void restartDoesNotActivatePersistedTracePreference() throws Exception {
    Path work = tempDir;
    LoggingRuntime runtime = start();
    runtime.applySettings(
        LoggingSettings.defaults()
            .withPreferredTraceScopes(EnumSet.of(TraceScope.NETWORK_WEBSOCKET)));
    runtime.shutdown();
    LoggingRuntime.resetForTests();
    LoggingRuntime restarted =
        LoggingRuntime.initialize(new WorkDirectoryResolution(work, List.of()), testLimits());
    restarted.applySettings(
        LoggingSettings.defaults()
            .withPreferredTraceScopes(EnumSet.of(TraceScope.NETWORK_WEBSOCKET)));

    assertFalse(restarted.fullTraceActive());
    assertFalse(Files.exists(work.resolve("logs/network-trace.log")));
  }

  @Test
  void rollingCreatesArchiveSegmentsAndKeepsActiveName() throws Exception {
    LoggingRuntime.resetForTests();
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 4000, 400));
    org.slf4j.Logger app = LoggerFactory.getLogger(LogCategories.APP);
    for (int i = 0; i < 40; i++) {
      app.info("rolling-payload-{}", "x".repeat(80) + i);
    }
    runtime.awaitIdle(80);
    assertTrue(Files.isRegularFile(tempDir.resolve("logs/app.log")));
    List<String> archiveNames = awaitAppArchiveNames(tempDir.resolve("logs/archive"));
    assertTrue(
        archiveNames.stream().anyMatch(name -> name.startsWith("app.")),
        "archive entries=" + archiveNames);
  }

  @Test
  void saturatedTraceQueueDoesNotBlockAppOrProducer() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(32, 4, 4, 4, 7, 10000, 1000));
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    CountDownLatch gate = new CountDownLatch(1);
    runtime.blockPersistenceForTests(LogStream.ENGINE_TRACE, gate);
    org.slf4j.Logger trace = LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
    long started = System.nanoTime();
    for (int i = 0; i < 64; i++) {
      trace.info("flood-{}", i);
    }
    long elapsedNanos = System.nanoTime() - started;
    LoggerFactory.getLogger(LogCategories.APP).error("app-while-trace-blocked");
    runtime.awaitIdle();
    gate.countDown();

    assertTrue(elapsedNanos < TimeUnit.SECONDS.toNanos(1));
    assertTrue(read("logs/app.log").contains("app-while-trace-blocked"));
    assertTrue(runtime.status().stream(LogStream.ENGINE_TRACE).orElseThrow().droppedCount() > 0);
    assertEquals(
        "queue saturation",
        runtime.status().stream(LogStream.ENGINE_TRACE).orElseThrow().reason());
  }

  @Test
  void rejectedEventsDoNotGrowCompletionBookkeeping() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(32, 1, 1, 1, 7, 10000, 1000));
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    CountDownLatch gate = new CountDownLatch(1);
    runtime.blockPersistenceForTests(LogStream.ENGINE_TRACE, gate);
    org.slf4j.Logger trace = LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
    for (int i = 0; i < 10_002; i++) {
      trace.info("reject-flood-{}", i);
    }
    try {
      assertTrue(runtime.droppedCountForTests(LogStream.ENGINE_TRACE) >= 10_000L);
      int queued = runtime.queuedCountForTests(LogStream.ENGINE_TRACE);
      long inFlight = runtime.inFlightCountForTests(LogStream.ENGINE_TRACE);
      int bookkeeping = runtime.completionBookkeepingSizeForTests(LogStream.ENGINE_TRACE);
      assertTrue(queued <= 1, "queued=" + queued);
      assertTrue(inFlight <= 1, "inFlight=" + inFlight);
      assertTrue(
          bookkeeping <= queued + inFlight,
          "bookkeeping="
              + bookkeeping
              + " queued="
              + queued
              + " inFlight="
              + inFlight);
    } finally {
      gate.countDown();
    }
  }

  @Test
  void sanitizerRemovesCredentialCanariesFromEveryPersistentStream() throws Exception {
    LoggingRuntime runtime = start();
    runtime.startFullTrace(EnumSet.allOf(TraceScope.class));
    List<String> canaries =
        List.of(
            "CANARY_AUTH_UNKNOWN_01",
            "CANARY_AUTH_TRAILING_01B",
            "CANARY_AUTH_BASIC_01C",
            "CANARY_AUTH_BEARER_01D",
            "CANARY_PROXY_AUTH_02",
            "CANARY_COOKIE_03",
            "CANARY_COOKIE_SECOND_03B",
            "CANARY_SET_COOKIE_04",
            "CANARY_SET_COOKIE_SECOND_04B",
            "CANARY_API_DASH_05",
            "CANARY_API_UNDERSCORE_06",
            "CANARY_X_API_HEADER_06B",
            "CANARY_ACCESS_DASH_07",
            "CANARY_ACCESS_UNDERSCORE_08",
            "CANARY_REFRESH_09",
            "CANARY_CLIENT_SECRET_10",
            "CANARY_URI_USER_11",
            "CANARY_URI_PASSWORD_12",
            "CANARY_QUERY_TOKEN_13",
            "CANARY_THROWABLE_HEADER_14",
            "CANARY_BARE_BEARER_15",
            "CANARY_ESCAPED_PASSWORD_16",
            "CANARY_UNICODE_COLON_17",
            "CANARY_UNICODE_EQUAL_18",
            "CANARY_RAW_QUOTED_SUFFIX_19",
            "CANARY_EMBEDDED_QUOTED_SUFFIX_20",
            "CANARY_UNICODE_NAME_21");
    List<String> payloads =
        List.of(
            "Authorization: UnknownScheme CANARY_AUTH_UNKNOWN_01 CANARY_AUTH_TRAILING_01B",
            "Authorization: Basic CANARY_AUTH_BASIC_01C",
            "Authorization: Bearer CANARY_AUTH_BEARER_01D",
            "Proxy-Authorization=Custom CANARY_PROXY_AUTH_02",
            "Cookie: first=CANARY_COOKIE_03; second=CANARY_COOKIE_SECOND_03B",
            "Set-Cookie: first=CANARY_SET_COOKIE_04; second=CANARY_SET_COOKIE_SECOND_04B",
            "api-key=CANARY_API_DASH_05",
            "{\"api_key\":\"CANARY_API_UNDERSCORE_06\"}",
            "X-Api-Key: CANARY_X_API_HEADER_06B",
            "access-token CANARY_ACCESS_DASH_07",
            "access_token='CANARY_ACCESS_UNDERSCORE_08'",
            "refresh-token=CANARY_REFRESH_09",
            "client_secret: CANARY_CLIENT_SECRET_10",
            "endpoint=https://CANARY_URI_USER_11:CANARY_URI_PASSWORD_12@example.test/path",
            "url=https://example.test/status?access_token=CANARY_QUERY_TOKEN_13&safe=true",
            "Bearer CANARY_BARE_BEARER_15",
            "payload={\\\"password\\\":\\\"CANARY_ESCAPED_PASSWORD_16\\\"}",
            "Authorization\\u003a Bearer CANARY_UNICODE_COLON_17",
            "password\\u003dCANARY_UNICODE_EQUAL_18",
            "payload={\"password\":\"prefix\\\"CANARY_RAW_QUOTED_SUFFIX_19\"}",
            escapedEmbeddedJson("CANARY_EMBEDDED_QUOTED_SUFFIX_20"),
            "passw\\u006frd=CANARY_UNICODE_NAME_21");
    List<org.slf4j.Logger> persistentLoggers =
        List.of(
            LoggerFactory.getLogger(LogCategories.APP),
            LoggerFactory.getLogger(LogCategories.CRASH),
            LoggerFactory.getLogger(LogCategories.ENGINE_TRACE),
            LoggerFactory.getLogger(LogCategories.READBOARD_TRACE),
            LoggerFactory.getLogger(LogCategories.NETWORK_TRACE));
    for (org.slf4j.Logger logger : persistentLoggers) {
      for (String payload : payloads) {
        logger.error("redaction-case {}", payload);
      }
      logger.error(
          "redaction-throwable",
          new IllegalStateException(
              "Authorization: Custom CANARY_THROWABLE_HEADER_14 trailing-secret"));
      logger.error(
          "ordinary path={} url={}",
          "/home/dev/lizzieyzy-next/config.txt",
          "https://example.test/status");
    }
    runtime.awaitIdle();
    runtime.stopFullTrace();
    runtime.awaitIdle();

    for (String relative :
        List.of(
            "logs/app.log",
            "logs/crash.log",
            "logs/engine-trace.log",
            "logs/readboard-trace.log",
            "logs/network-trace.log")) {
      String persisted = read(relative);
      for (String canary : canaries) {
        assertFalse(persisted.contains(canary), relative + " leaked " + canary + "\n" + persisted);
      }
      assertTrue(persisted.contains("/home/dev/lizzieyzy-next/config.txt"), relative);
      assertTrue(persisted.contains("https://example.test/status"), relative);
    }
  }

  @Test
  void nopProviderDoesNotAbortInitialize() {
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      LoggingRuntime.resetForTests();
      LoggingRuntime runtime =
          LoggingRuntime.initialize(
              new WorkDirectoryResolution(tempDir, List.of()),
              testLimits(),
              new NOPLoggerFactory());
      assertFalse(runtime.status().persistenceEnabled());
      LoggerFactory.getLogger(LogCategories.APP).error("must-not-throw");
    } finally {
      System.setErr(original);
    }
    String err = captured.toString(StandardCharsets.UTF_8);
    assertTrue(err.contains(LoggingRuntime.STDERR_PREFIX), err);
    assertEquals(1, count(err, LoggingRuntime.STDERR_PREFIX));
    assertFalse(Files.exists(tempDir.resolve("logs/app.log")));
  }

  @Test
  void illegalAppLogPathRecordsFailureAndContinues() throws Exception {
    Files.createDirectories(tempDir.resolve("logs").resolve("app.log"));
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    LoggingRuntime runtime;
    try {
      runtime = start();
      LoggerFactory.getLogger(LogCategories.APP).error("after-failure");
      LoggerFactory.getLogger(LogCategories.APP).error("after-failure-again");
      runtime.awaitIdle();
    } finally {
      System.setErr(original);
    }
    LoggingStatus.StreamStatus app = runtime.status().stream(LogStream.APP).orElseThrow();
    assertNotNull(app.reason());
    assertNotNull(app.firstOccurrence());
    assertNotNull(app.lastOccurrence());
    assertFalse(runtime.status().persistenceEnabled());
    String err = captured.toString(StandardCharsets.UTF_8);
    assertTrue(count(err, LoggingRuntime.STDERR_PREFIX) >= 1, err);
    assertEquals(1, count(err, "APP:failure"));
  }

  @Test
  void queueSaturationThenSuccessfulWriteMarksRecovered() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(32, 4, 4, 4, 7, 10000, 1000));
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    CountDownLatch gate = new CountDownLatch(1);
    runtime.blockPersistenceForTests(LogStream.ENGINE_TRACE, gate);
    org.slf4j.Logger trace = LoggerFactory.getLogger(LogCategories.ENGINE_TRACE);
    for (int i = 0; i < 64; i++) {
      trace.info("flood-{}", i);
    }
    LoggingStatus.StreamStatus blocked =
        runtime.status().stream(LogStream.ENGINE_TRACE).orElseThrow();
    assertTrue(blocked.droppedCount() > 0);
    assertEquals("queue saturation", blocked.reason());
    assertFalse(blocked.recovered());
    gate.countDown();
    trace.info("after-drain");
    runtime.awaitIdle();
    LoggingStatus.StreamStatus recovered =
        runtime.status().stream(LogStream.ENGINE_TRACE).orElseThrow();
    assertTrue(recovered.recovered());
    assertTrue(recovered.droppedCount() > 0);
    assertEquals("queue saturation", recovered.reason());
    assertNotNull(recovered.firstOccurrence());
  }

  @Test
  void persistedEventsIncludeInstalledCorrelationIdentities() throws Exception {
    LoggingRuntime runtime = start();
    String engine = runtime.newEngineIdentity();
    String command = runtime.newCommandIdentity();
    CorrelationContext.installEngine(engine);
    CorrelationContext.installCommand(command);
    LoggerFactory.getLogger(LogCategories.APP).error("correlated-event");
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    String traceSession = runtime.currentTraceSessionId();
    LoggerFactory.getLogger(LogCategories.ENGINE_TRACE).info("trace-payload");
    runtime.awaitIdle();
    runtime.stopFullTrace();
    runtime.awaitIdle();

    String appLog = read("logs/app.log");
    assertTrue(appLog.contains("session=" + runtime.applicationLogSessionId()), appLog);
    assertTrue(appLog.contains("engine=" + engine), appLog);
    assertTrue(appLog.contains("command=" + command), appLog);
    String traceLog = read("logs/engine-trace.log");
    assertTrue(traceLog.contains("trace=" + traceSession), traceLog);
    assertTrue(traceLog.contains("Full Trace session started"), traceLog);
    CorrelationContext.clearEngine();
    CorrelationContext.clearCommand();
  }

  @Test
  void sanitizerFailureOmitsOriginalEvent() {
    SanitizingEncoder encoder = new SanitizingEncoder();
    LoggerContext context = new LoggerContext();
    encoder.setContext(context);
    encoder.setPattern("%msg%n");
    encoder.setSanitizer(
        new PersistenceSanitizer() {
          @Override
          public String sanitize(String text) {
            throw new IllegalStateException("boom");
          }
        });
    encoder.start();
    LoggingEvent event = new LoggingEvent();
    event.setLoggerName(LogCategories.APP);
    event.setLevel(Level.INFO);
    event.setMessage("secret CANARY_SHOULD_NOT_APPEAR");
    String encoded = new String(encoder.encode(event), StandardCharsets.UTF_8);
    assertEquals(PersistenceSanitizer.FAILURE_MARKER + System.lineSeparator(), encoded);
  }

  @Test
  void encoderBoundsOnePersistentEventWithoutBreakingUtf8() {
    SanitizingEncoder encoder = new SanitizingEncoder();
    LoggerContext context = new LoggerContext();
    encoder.setContext(context);
    encoder.setPattern("%msg%n");
    encoder.start();
    LoggingEvent event = new LoggingEvent();
    event.setLoggerName(LogCategories.APP);
    event.setLevel(Level.ERROR);
    event.setMessage("\uD83D\uDE00".repeat(LoggingLimits.MAX_PERSISTED_EVENT_BYTES / 2));

    byte[] encoded = encoder.encode(event);
    String decoded = new String(encoded, StandardCharsets.UTF_8);

    assertTrue(encoded.length <= LoggingLimits.MAX_PERSISTED_EVENT_BYTES);
    assertFalse(decoded.contains("\uFFFD"), "truncation split a UTF-8 code point");
    assertTrue(
        decoded.endsWith(SanitizingEncoder.TRUNCATION_MARKER),
        decoded.substring(decoded.length() - 64));
  }

  @Test
  void engineAndCommandIdentitiesAreNotReused() {
    LoggingRuntime runtime = start();
    String firstEngine = runtime.newEngineIdentity();
    String secondEngine = runtime.newEngineIdentity();
    assertNotEquals(firstEngine, secondEngine);
    assertNotEquals(runtime.newCommandIdentity(), runtime.newCommandIdentity());
  }

  @Test
  void idleShutdownDoesNotBurnThreeSecondBudget() {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    long started = System.nanoTime();
    runtime.shutdown();
    assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
  }

  @Test
  void shutdownReliablyDrainsEventsAcceptedImmediatelyBeforeStop() throws Exception {
    LoggingRuntime runtime = start();
    org.slf4j.Logger app = LoggerFactory.getLogger(LogCategories.APP);
    for (int i = 0; i < 24; i++) {
      app.error("shutdown-drain-canary-{}", i);
    }

    ShutdownReport report = runtime.shutdown();

    assertEquals(0L, report.unwritten(LogStream.APP));
    String persisted = read("logs/app.log");
    for (int i = 0; i < 24; i++) {
      assertTrue(persisted.contains("shutdown-drain-canary-" + i), persisted);
    }
  }

  @Test
  void producerPausedBeforeAcceptanceCannotCommitAfterShutdown() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch hold = new CountDownLatch(1);
    runtime.pauseBeforeAcceptForTests(LogStream.APP, entered, hold);
    Thread producer =
        new Thread(
            () -> LoggerFactory.getLogger(LogCategories.APP).error("post-shutdown-accept-canary"),
            "pre-accept-producer");
    producer.start();
    ShutdownReport report = null;
    try {
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      report = runtime.shutdown();
    } finally {
      hold.countDown();
      producer.join(4_000);
    }

    assertFalse(producer.isAlive());
    assertNotNull(report);
    assertEquals(0L, report.unwritten(LogStream.APP));
    assertFalse(read("logs/app.log").contains("post-shutdown-accept-canary"));
    assertTrue(runtime.droppedCountForTests(LogStream.APP) > 0L);
  }

  @Test
  void producerPausedAfterAtomicAcceptanceIsDrainedByShutdown() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    CountDownLatch accepted = new CountDownLatch(1);
    CountDownLatch hold = new CountDownLatch(1);
    runtime.pauseAfterAcceptForTests(LogStream.APP, accepted, hold);
    Thread producer =
        new Thread(
            () -> LoggerFactory.getLogger(LogCategories.APP).error("pre-shutdown-accept-canary"),
            "post-accept-producer");
    producer.start();
    ShutdownReport report = null;
    try {
      assertTrue(accepted.await(3, TimeUnit.SECONDS));
      report = runtime.shutdown();
      assertTrue(read("logs/app.log").contains("pre-shutdown-accept-canary"));
    } finally {
      hold.countDown();
      producer.join(4_000);
    }

    assertFalse(producer.isAlive());
    assertNotNull(report);
    assertEquals(0L, report.unwritten(LogStream.APP));
  }

  @Test
  void shutdownCountsInterruptedInFlightExactlyOnce() throws Exception {
    LoggingRuntime runtime = start();
    runtime.awaitIdle();
    CountDownLatch gate = new CountDownLatch(1);
    runtime.blockPersistenceForTests(LogStream.APP, gate);
    LoggerFactory.getLogger(LogCategories.APP).error("late-event");
    long handoffDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (runtime.inFlightCountForTests(LogStream.APP) == 0L
        && System.nanoTime() < handoffDeadline) {
      Thread.sleep(10);
    }
    assertEquals(1L, runtime.inFlightCountForTests(LogStream.APP));
    long started = System.nanoTime();
    ShutdownReport report = runtime.shutdown();
    long elapsed = System.nanoTime() - started;
    assertEquals(1L, report.unwritten(LogStream.APP), "unwritten=" + report.unwritten(LogStream.APP));
    assertTrue(elapsed <= LoggingLimits.SHUTDOWN_BUDGET_NANOS + TimeUnit.MILLISECONDS.toNanos(100));
    assertFalse(runtime.isWorkerAliveForTests(LogStream.APP));
    Path appLog = tempDir.resolve("logs/app.log");
    if (Files.isRegularFile(appLog)) {
      assertFalse(Files.readString(appLog).contains("late-event"));
    }
  }

  @Test
  void shutdownHonorsThreeSecondBudgetWhenNestedStopIsSlow() throws Exception {
    LoggingRuntime runtime = start();
    runtime.pauseNestedStopForTests(LogStream.APP, 5_000);
    runtime.pauseNestedStopForTests(LogStream.CRASH, 5_000);
    long started = System.nanoTime();
    runtime.shutdown();
    long elapsed = System.nanoTime() - started;
    assertTrue(
        elapsed <= LoggingLimits.SHUTDOWN_BUDGET_NANOS + TimeUnit.MILLISECONDS.toNanos(100),
        "elapsedNanos=" + elapsed);
    assertFalse(runtime.isNestedStartedForTests(LogStream.APP));
    assertFalse(runtime.isNestedStartedForTests(LogStream.CRASH));
    Files.delete(tempDir.resolve("logs/app.log"));
    Files.delete(tempDir.resolve("logs/crash.log"));
  }

  @Test
  void failedAppendDoesNotMarkRecoveredUntilLaterSuccess() throws Exception {
    LoggingRuntime runtime = start();
    assertTrue(runtime.status().persistenceEnabled());
    runtime.failWritesForTests(LogStream.APP, true);
    LoggerFactory.getLogger(LogCategories.APP).error("controlled-fail");
    runtime.awaitIdle();
    LoggingStatus.StreamStatus failed = runtime.status().stream(LogStream.APP).orElseThrow();
    assertFalse(failed.recovered());
    assertNotNull(failed.reason());
    assertFalse(runtime.status().persistenceEnabled());
    runtime.failWritesForTests(LogStream.APP, false);
    LoggerFactory.getLogger(LogCategories.APP).error("after-recovery");
    runtime.awaitIdle();
    LoggingStatus.StreamStatus recovered = runtime.status().stream(LogStream.APP).orElseThrow();
    assertTrue(recovered.recovered());
    assertTrue(runtime.status().persistenceEnabled());
    assertTrue(read("logs/app.log").contains("after-recovery"));
  }

  @Test
  void engineTraceWriteFailureDoesNotAttachToAppStream() throws Exception {
    LoggingRuntime runtime = start();
    runtime.startFullTrace(EnumSet.of(TraceScope.ENGINE_GTP));
    runtime.failWritesForTests(LogStream.ENGINE_TRACE, true);
    LoggerFactory.getLogger(LogCategories.ENGINE_TRACE).info("trace-fail");
    runtime.awaitIdle();
    LoggingStatus.StreamStatus trace =
        runtime.status().stream(LogStream.ENGINE_TRACE).orElseThrow();
    assertNotNull(trace.reason());
    assertFalse(trace.recovered());
    LoggingStatus.StreamStatus app = runtime.status().stream(LogStream.APP).orElseThrow();
    assertTrue(app.reason() == null || !app.reason().contains("engine-trace"));
  }

  @Test
  void encoderFailureWritesMarkerAndUpdatesStatus() throws Exception {
    LoggingRuntime runtime = start();
    runtime.replaceSanitizerForTests(
        new PersistenceSanitizer() {
          @Override
          public String sanitize(String text) {
            if (text.contains("BLOW_UP")) {
              throw new IllegalStateException("boom");
            }
            return super.sanitize(text);
          }
        });
    LoggerFactory.getLogger(LogCategories.APP).error("BLOW_UP");
    runtime.awaitIdle();
    LoggingStatus.StreamStatus app = runtime.status().stream(LogStream.APP).orElseThrow();
    assertNotNull(app.reason());
    assertTrue(app.reason().toLowerCase().contains("encoder") || app.reason().contains("redaction"), app.reason());
    assertFalse(app.recovered());
    assertFalse(runtime.status().persistenceEnabled());
    String scanned = scanLogFiles();
    assertTrue(scanned.contains(PersistenceSanitizer.FAILURE_MARKER), scanned);
    assertFalse(scanned.contains("BLOW_UP"), scanned);
  }

  private LoggingRuntime start() {
    LoggingRuntime.resetForTests();
    return LoggingRuntime.initialize(new WorkDirectoryResolution(tempDir, List.of()), testLimits());
  }

  private static LoggingLimits testLimits() {
    return new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000);
  }

  private String read(String relative) throws IOException {
    return Files.readString(tempDir.resolve(relative));
  }

  private String scanLogFiles() throws IOException {
    Path logs = tempDir.resolve("logs");
    if (!Files.exists(logs)) {
      return "";
    }
    StringBuilder scanned = new StringBuilder();
    try (Stream<Path> stream = Files.walk(logs)) {
      stream
          .filter(Files::isRegularFile)
          .forEach(
              path -> {
                try {
                  scanned.append(Files.readString(path));
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }
    return scanned.toString();
  }

  private static List<String> awaitAppArchiveNames(Path archiveDir)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    List<String> names;
    do {
      try (Stream<Path> stream = Files.list(archiveDir)) {
        names = stream.map(path -> path.getFileName().toString()).toList();
      }
      if (names.stream().anyMatch(name -> name.startsWith("app."))
          || System.nanoTime() >= deadline) {
        return names;
      }
      Thread.sleep(10L);
    } while (true);
  }

  private static int count(String text, String token) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(token, index)) >= 0) {
      count++;
      index += token.length();
    }
    return count;
  }

  private static String escapedEmbeddedJson(String canary) {
    String slash = "\\";
    String quote = "\"";
    return "payload={"
        + slash
        + quote
        + "password"
        + slash
        + quote
        + ":"
        + slash
        + quote
        + "prefix"
        + slash
        + slash
        + slash
        + quote
        + canary
        + slash
        + quote
        + "}";
  }
}
