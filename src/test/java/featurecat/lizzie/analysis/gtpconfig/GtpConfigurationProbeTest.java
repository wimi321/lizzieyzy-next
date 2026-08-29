package featurecat.lizzie.analysis.gtpconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import featurecat.lizzie.analysis.AnalysisResourceCoordinator;
import featurecat.lizzie.analysis.AnalysisResourceCoordinatorTestAccess;
import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
import featurecat.lizzie.logging.ObservationText;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class GtpConfigurationProbeTest {
  private static final long REAL_PROCESS_HANDSHAKE_TIMEOUT_SECONDS = 10L;

  @TempDir Path tempDir;

  @Test
  void reportsUnsupportedEngineWithoutRequestingSchema() throws Exception {
    ScriptedFactory factory = new ScriptedFactory(response(true, "false"));

    GtpConfigurationProbe.Inspection inspection =
        new GtpConfigurationProbe(factory).inspect("fake-engine", Duration.ofSeconds(1));

    assertFalse(inspection.supported());
    assertEquals(List.of("known_command zengtp_config_schema"), factory.commands);
    assertTrue(factory.closed);
  }

  @Test
  void discoversSchemaAndAppliesAtomicProfile() throws Exception {
    String schema = GtpConfigurationSchemaTest.schemaPayload().toString();
    ScriptedFactory inspectFactory =
        new ScriptedFactory(response(true, "true"), response(true, schema));
    GtpConfigurationProbe.Inspection inspection =
        new GtpConfigurationProbe(inspectFactory).inspect("fake-engine", Duration.ofSeconds(1));
    assertTrue(inspection.supported());
    assertEquals("zengtp-config", inspection.schema().protocol());

    JSONObject profile =
        new JSONObject()
            .put("mode", "fixed-time")
            .put("rankPreset", "9d")
            .put("maxTimeSeconds", 5.0)
            .put("threads", 8);
    JSONObject savePayload =
        new JSONObject()
            .put("profile", new JSONObject(profile.toString()))
            .put("state", new JSONObject().put("selected", new JSONObject(profile.toString())));
    ScriptedFactory applyFactory =
        new ScriptedFactory(
            response(true, "true"),
            response(true, schema),
            response(true, new JSONObject().put("operation", "set").toString()),
            response(true, savePayload.toString()));

    GtpConfigurationProbe.ApplyResult result =
        new GtpConfigurationProbe(applyFactory)
            .applyProfile("fake-engine", profile, Duration.ofSeconds(1));

    assertEquals("fixed-time", result.profile().getString("mode"));
    assertEquals(
        List.of(
            "known_command zengtp_config_schema",
            "zengtp_config_schema",
            "zengtp_config_set " + profile,
            "zengtp_config_save"),
        applyFactory.commands);
  }

  @Test
  void surfacesStructuredEngineErrorsAndRejectsInvalidClientValues() {
    String schema = GtpConfigurationSchemaTest.schemaPayload().toString();
    JSONObject error =
        new JSONObject()
            .put(
                "error",
                new JSONObject()
                    .put("code", "invalid_value")
                    .put("parameter", "threads")
                    .put("message", "threads must be positive"));
    ScriptedFactory rejectedFactory =
        new ScriptedFactory(
            response(true, "true"), response(true, schema), response(false, error.toString()));

    IOException rejected =
        assertThrows(
            IOException.class,
            () ->
                new GtpConfigurationProbe(rejectedFactory)
                    .applyProfile(
                        "fake-engine", new JSONObject().put("threads", 2), Duration.ofSeconds(1)));
    assertTrue(rejected.getMessage().contains("invalid_value"));
    assertTrue(rejected.getMessage().contains("threads"));

    ScriptedFactory invalidFactory =
        new ScriptedFactory(response(true, "true"), response(true, schema));
    IOException invalid =
        assertThrows(
            IOException.class,
            () ->
                new GtpConfigurationProbe(invalidFactory)
                    .applyProfile(
                        "fake-engine", new JSONObject().put("threads", 0), Duration.ofSeconds(1)));
    assertTrue(invalid.getMessage().contains("threads"));
    assertEquals(2, invalidFactory.commands.size());
  }

  @Test
  void exchangesNumberedGtpMessagesWithARealChildProcessAndTimesOutCleanly() throws Exception {
    String command = fakeEngineCommand(false);
    GtpConfigurationProbe probe = new GtpConfigurationProbe();

    GtpConfigurationProbe.Inspection inspection = probe.inspect(command, Duration.ofSeconds(2));
    JSONObject profile =
        new JSONObject()
            .put("mode", "fixed-time")
            .put("rankPreset", "9d")
            .put("maxTimeSeconds", 3.5)
            .put("threads", 6);
    GtpConfigurationProbe.ApplyResult result =
        probe.applyProfile(command, profile, Duration.ofSeconds(2));

    assertTrue(inspection.supported());
    assertEquals("fixed-time", result.profile().getString("mode"));
    assertEquals(6, result.profile().getInt("threads"));

    IOException timeout =
        assertThrows(
            IOException.class,
            () -> probe.inspect(fakeEngineCommand(true), Duration.ofMillis(200)));
    assertTrue(timeout.getMessage().contains("Timed out"));
  }

  @Test
  void successfulRealProbeLeavesStructuredEventsWithoutChangingResult() throws Exception {
    LoggingRuntime runtime = startProbeDiagnostics();
    try {
      ListAppender<ILoggingEvent> events = attachEngine();
      String command = fakeEngineCommand();
      GtpConfigurationProbe probe = new GtpConfigurationProbe();

      GtpConfigurationProbe.Inspection inspection = probe.inspect(command, Duration.ofSeconds(2));

      assertTrue(inspection.supported());
      assertEquals("zengtp-config", inspection.schema().protocol());
      String logs = formatted(events);
      assertTrue(logs.contains("probe event=started"), logs);
      assertTrue(logs.contains("probe event=capability-check outcome=success"), logs);
      assertFalse(logs.contains("probe event=failed"), logs);
      assertFalse(logs.contains("probe event=stderr"), logs);
      awaitLogs(runtime);
    } finally {
      runtime.shutdown();
    }
  }

  @Test
  void stderrOnSuccessfulProbeDoesNotChangeInspectResult() throws Exception {
    LoggingRuntime runtime = startProbeDiagnostics();
    try {
      ListAppender<ILoggingEvent> events = attachEngine();
      GtpConfigurationProbe.Inspection inspection =
          new GtpConfigurationProbe().inspect(fakeEngineCommand("stderr"), Duration.ofSeconds(2));

      assertTrue(inspection.supported());
      String logs = formatted(events);
      assertTrue(logs.contains("probe event=started"), logs);
      assertTrue(logs.contains("probe event=capability-check outcome=success"), logs);
      assertFalse(logs.contains("probe event=failed"), logs);
      awaitLogs(runtime);
    } finally {
      runtime.shutdown();
    }
  }

  @Test
  void hugeStderrStaysBoundedAndDoesNotBlockSuccessfulProbe() throws Exception {
    GtpConfigurationProbe.ProbeStderrTail tail = new GtpConfigurationProbe.ProbeStderrTail();
    for (int i = 0; i < 50_000; i++) {
      tail.add("line-" + i + "-" + "x".repeat(80));
    }
    tail.add("y".repeat(1_000_000));
    assertTrue(tail.lineCount() <= GtpConfigurationProbe.ProbeStderrTail.MAX_LINES);
    assertTrue(tail.utf8ByteCount() <= ObservationText.RAW_EVENT_MAX_UTF8_BYTES);
    assertTrue(
        tail.snapshot().getBytes(StandardCharsets.UTF_8).length
            <= ObservationText.RAW_EVENT_MAX_UTF8_BYTES + 64);

    LoggingRuntime runtime = startProbeDiagnostics();
    try {
      ListAppender<ILoggingEvent> events = attachEngine();
      GtpConfigurationProbe.Inspection inspection =
          new GtpConfigurationProbe()
              .inspect(fakeEngineCommand("stderr-huge"), Duration.ofSeconds(5));

      assertTrue(inspection.supported());
      String logs = formatted(events);
      assertTrue(logs.contains("probe event=capability-check outcome=success"), logs);
      assertFalse(logs.contains("probe event=failed"), logs);
      awaitLogs(runtime);
    } finally {
      runtime.shutdown();
    }
  }

  @Test
  void earlyExitStillThrowsAndRecordsBoundedStderrSummary() throws Exception {
    LoggingRuntime runtime = startProbeDiagnostics();
    try {
      ListAppender<ILoggingEvent> events = attachEngine();

      IOException exited =
          assertThrows(
              IOException.class,
              () ->
                  new GtpConfigurationProbe()
                      .inspect(fakeEngineCommand("stderr", "exit"), Duration.ofSeconds(2)));

      assertTrue(
          exited.getMessage().contains("Engine exited before configuration completed"),
          exited.getMessage());
      assertFalse(exited.getMessage().contains("probe-stderr"), exited.getMessage());
      assertFalse(exited.getMessage().contains("probe-secret-token"), exited.getMessage());
      String logs = formatted(events);
      assertTrue(logs.contains("probe event=started"), logs);
      assertTrue(logs.contains("probe event=failed stage=exited"), logs);
      assertTrue(logs.contains("probe event=stderr facts="), logs);
      assertTrue(logs.contains("probe-stderr"), logs);
      awaitLogs(runtime);
      String app = Files.readString(tempDir.resolve("logs/app.log"), StandardCharsets.UTF_8);
      assertFalse(app.contains("probe-secret-token"), app);
      assertTrue(app.contains("probe event=stderr"), app);
    } finally {
      runtime.shutdown();
    }
  }

  @Test
  void probeTimeoutStillThrowsAndRecordsStderrSummary() throws Exception {
    LoggingRuntime runtime = startProbeDiagnostics();
    try {
      ListAppender<ILoggingEvent> events = attachEngine();

      IOException timeout =
          assertThrows(
              IOException.class,
              () ->
                  new GtpConfigurationProbe()
                      .inspect(fakeEngineCommand("stderr", "hang"), Duration.ofMillis(200)));

      assertTrue(
          timeout.getMessage().contains("Timed out waiting for the engine configuration response"),
          timeout.getMessage());
      assertFalse(timeout.getMessage().contains("probe-stderr"), timeout.getMessage());
      String logs = formatted(events);
      assertTrue(logs.contains("probe event=started"), logs);
      assertTrue(logs.contains("probe event=failed stage=timeout"), logs);
      assertTrue(logs.contains("probe event=stderr facts="), logs);
      assertTrue(logs.contains("probe-stderr"), logs);
      awaitLogs(runtime);
    } finally {
      runtime.shutdown();
    }
  }

  @Test
  void stderrReaderFailureDoesNotPropagateToCaller() throws Exception {
    LoggingRuntime runtime = startProbeDiagnostics();
    try {
      ListAppender<ILoggingEvent> events = attachEngine();
      BufferedReader throwing =
          new BufferedReader(
              new Reader() {
                @Override
                public int read(char[] cbuf, int off, int len) {
                  throw new IllegalStateException("stderr exploded");
                }

                @Override
                public void close() {}
              });

      GtpConfigurationProbe.drainProbeStderr(
          throwing, new GtpConfigurationProbe.ProbeStderrTail(), () -> "eng-test");

      GtpConfigurationProbe.Inspection inspection =
          new GtpConfigurationProbe(new ScriptedFactory(response(true, "false")))
              .inspect("fake-engine", Duration.ofSeconds(1));
      assertFalse(inspection.supported());
      String logs = formatted(events);
      assertTrue(logs.contains("engine event=transport-failure"), logs);
      assertTrue(logs.contains("stream=stderr"), logs);
      assertTrue(logs.contains("reason=reader-error"), logs);
      assertFalse(logs.contains("stderr exploded"), logs);
      awaitLogs(runtime);
    } finally {
      runtime.shutdown();
    }
  }

  @Test
  void realProbeChildParticipatesInLocalComputeIsolationRegistry() throws Exception {
    assertTrue(
        awaitRawRegistryEmpty(REAL_PROCESS_HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "the real-process registry test requires an isolated raw zero-process baseline");
    Path schemaGate = tempDir.resolve("probe-registry-schema-gate");
    Path childPid = childPidPath(schemaGate);
    GtpConfigurationProbe probe = new GtpConfigurationProbe();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch workerFinished = new CountDownLatch(1);
    AtomicBoolean workerStarted = new AtomicBoolean(false);
    AtomicReference<Throwable> workerFailure = new AtomicReference<>();
    Future<?> worker = null;
    ProcessHandle child = null;
    try (WatchService gateWatcher = FileSystems.getDefault().newWatchService()) {
      tempDir.register(gateWatcher, StandardWatchEventKinds.ENTRY_CREATE);
      worker =
          executor.submit(
              () -> {
                workerStarted.set(true);
                try {
                  probe.inspect(
                      fakeEngineCommand(schemaGate),
                      Duration.ofSeconds(REAL_PROCESS_HANDSHAKE_TIMEOUT_SECONDS));
                } catch (Throwable failure) {
                  workerFailure.set(failure);
                } finally {
                  workerFinished.countDown();
                }
              });
      awaitSchemaGate(gateWatcher, schemaGate);
      child = requireVerifiedGatedChild(childPid, schemaGate);

      assertTrue(child.isAlive(), "the schema gate must hold the real probe child open");
      assertEquals(
          1,
          AnalysisResourceCoordinatorTestAccess.rawLocalComputeProcessCount(),
          "the probe must be present in the raw registry at its schema gate");

      assertTrue(worker.cancel(true), "the gated probe worker must accept cancellation");
      Future<?> cancelledWorker = worker;
      assertThrows(CancellationException.class, cancelledWorker::get);
      assertTrue(
          workerFinished.await(REAL_PROCESS_HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "the cancelled probe worker must finish its ProcessSession.close path");
      Throwable cancellationFailure = workerFailure.get();
      assertTrue(
          cancellationFailure instanceof IOException
              && cancellationFailure.getMessage() != null
              && cancellationFailure.getMessage().contains("Interrupted"),
          "cancellation must interrupt the blocked probe request");
      assertTrue(
          awaitExactChildExit(child, REAL_PROCESS_HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "the cancelled probe must terminate its exact child without test-side process killing");
      assertTrue(
          awaitRawRegistryEmpty(REAL_PROCESS_HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "processStopped/onExit must remove the child from the raw registry before test cleanup");
    } finally {
      cleanupRealProbeChild(
          schemaGate, childPid, worker, workerStarted, workerFinished, child, executor);
    }

    assertEquals(
        0,
        AnalysisResourceCoordinatorTestAccess.rawLocalComputeProcessCount(),
        "the raw registry must remain empty after the cancelled child has exited");
    assertFalse(
        AnalysisResourceCoordinator.hasActiveLocalComputeProcess(),
        "the registry must be empty after the cancelled child has exited");
  }

  private static void cleanupRealProbeChild(
      Path schemaGate,
      Path childPid,
      Future<?> worker,
      AtomicBoolean workerStarted,
      CountDownLatch workerFinished,
      ProcessHandle capturedChild,
      ExecutorService executor)
      throws Exception {
    CleanupState cleanup = new CleanupState();
    ProcessHandle exactChild = capturedChild;
    if (exactChild == null) {
      try {
        exactChild = findVerifiedGatedChild(childPid, schemaGate);
      } catch (Exception pidFailure) {
        cleanup.record(pidFailure);
      }
    }
    cleanup.run(() -> Files.deleteIfExists(schemaGate));
    cleanup.run(
        () -> {
          if (worker != null) {
            worker.cancel(true);
          }
        });
    if (workerStarted.get()) {
      cleanup.call(() -> workerFinished.await(2L, TimeUnit.SECONDS));
    }

    ProcessHandle verifiedChild = exactChild;
    if (verifiedChild != null && cleanup.call(verifiedChild::isAlive)) {
      cleanup.run(() -> verifiedChild.destroy());
      boolean childExited =
          cleanup.call(() -> awaitExactChildExit(verifiedChild, 2L, TimeUnit.SECONDS));
      if (!childExited) {
        cleanup.run(() -> verifiedChild.destroyForcibly());
        childExited =
            cleanup.call(
                () ->
                    awaitExactChildExit(
                        verifiedChild, REAL_PROCESS_HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
      cleanup.require(childExited, "the exact gated probe child survived forced cleanup");
    }

    if (workerStarted.get() && workerFinished.getCount() != 0L) {
      cleanup.require(
          cleanup.call(
              () -> workerFinished.await(REAL_PROCESS_HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)),
          "the cancelled real probe worker did not terminate");
    }

    cleanup.run(() -> executor.shutdownNow());
    cleanup.require(
        cleanup.call(
            () ->
                executor.awaitTermination(
                    REAL_PROCESS_HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)),
        "the real probe executor did not terminate during cleanup");
    cleanup.run(() -> Files.deleteIfExists(schemaGate));
    cleanup.run(() -> Files.deleteIfExists(childPid));

    boolean rawRegistryEmpty =
        cleanup.call(
            () -> awaitRawRegistryEmpty(REAL_PROCESS_HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    cleanup.require(
        rawRegistryEmpty,
        "the real probe raw registry remained populated after exact-child cleanup");
    if (!rawRegistryEmpty) {
      cleanup.run(() -> AnalysisResourceCoordinator.hasActiveLocalComputeProcess());
    }
    cleanup.finish();
  }

  private static ProcessHandle requireVerifiedGatedChild(Path childPid, Path schemaGate)
      throws IOException {
    ProcessHandle child = findVerifiedGatedChild(childPid, schemaGate);
    if (child == null) {
      throw new AssertionError("the gated probe identity did not match its live child");
    }
    return child;
  }

  private static ProcessHandle findVerifiedGatedChild(Path childPid, Path schemaGate)
      throws IOException {
    if (!Files.exists(schemaGate) || !Files.isRegularFile(childPid)) {
      return null;
    }
    List<String> identity = Files.readAllLines(childPid, StandardCharsets.UTF_8);
    if (identity.size() != 4
        || !FakeGtpEngine.class.getName().equals(identity.get(2))
        || !schemaGate.toAbsolutePath().toString().equals(identity.get(3))) {
      return null;
    }
    long pid;
    Instant expectedStart;
    try {
      pid = Long.parseLong(identity.get(0));
      expectedStart = Instant.parse(identity.get(1));
    } catch (RuntimeException invalidIdentity) {
      return null;
    }
    ProcessHandle child = ProcessHandle.of(pid).orElse(null);
    return child != null
            && child.isAlive()
            && child.info().startInstant().filter(expectedStart::equals).isPresent()
        ? child
        : null;
  }

  private static boolean awaitRawRegistryEmpty(long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (AnalysisResourceCoordinatorTestAccess.rawLocalComputeProcessCount() != 0
        && System.nanoTime() < deadline) {
      Thread.sleep(10L);
    }
    return AnalysisResourceCoordinatorTestAccess.rawLocalComputeProcessCount() == 0;
  }

  private static boolean awaitExactChildExit(ProcessHandle child, long timeout, TimeUnit unit)
      throws InterruptedException {
    if (child == null || !child.isAlive()) {
      return true;
    }
    try {
      child.onExit().get(timeout, unit);
    } catch (ExecutionException | TimeoutException failure) {
      return !child.isAlive();
    }
    return !child.isAlive();
  }

  private static void awaitSchemaGate(WatchService watcher, Path schemaGate)
      throws InterruptedException {
    long deadline =
        System.nanoTime() + TimeUnit.SECONDS.toNanos(REAL_PROCESS_HANDSHAKE_TIMEOUT_SECONDS);
    while (!Files.exists(schemaGate)) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0L) {
        break;
      }
      WatchKey key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
      if (key == null) {
        break;
      }
      for (WatchEvent<?> event : key.pollEvents()) {
        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
            && schemaGate.getFileName().equals(event.context())) {
          return;
        }
      }
      if (!key.reset()) {
        break;
      }
    }
    assertTrue(Files.exists(schemaGate), "the real probe child never reached its schema gate");
  }

  private static Path childPidPath(Path schemaGate) {
    return schemaGate.resolveSibling(schemaGate.getFileName() + ".pid");
  }

  @FunctionalInterface
  private interface CleanupAction {
    void run() throws Exception;
  }

  @FunctionalInterface
  private interface CleanupCondition {
    boolean test() throws Exception;
  }

  private static final class CleanupState {
    private Throwable failure;
    private boolean interrupted;

    private void run(CleanupAction action) {
      call(
          () -> {
            action.run();
            return true;
          });
    }

    private boolean call(CleanupCondition condition) {
      try {
        return condition.test();
      } catch (InterruptedException interruption) {
        interrupted = true;
        record(interruption);
      } catch (Exception cleanupFailure) {
        record(cleanupFailure);
      }
      return false;
    }

    private void require(boolean satisfied, String message) {
      if (!satisfied) {
        record(new AssertionError(message));
      }
    }

    private void record(Throwable next) {
      if (failure == null) {
        failure = next;
      } else {
        failure.addSuppressed(next);
      }
    }

    private void finish() throws Exception {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      if (failure instanceof Exception exception) {
        throw exception;
      }
      if (failure instanceof Error error) {
        throw error;
      }
    }
  }

  private static String fakeEngineCommand(boolean hangOnSchema) {
    return hangOnSchema ? fakeEngineCommand("hang") : fakeEngineCommand();
  }

  private static String fakeEngineCommand(String... extras) {
    String executable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
            .toAbsolutePath()
            .toString();
    String classes = Path.of("target", "test-classes").toAbsolutePath().toString();
    StringBuilder command =
        new StringBuilder()
            .append(quote(executable))
            .append(" -cp ")
            .append(quote(classes))
            .append(' ')
            .append(FakeGtpEngine.class.getName());
    for (String extra : extras) {
      if (extra != null && !extra.isEmpty()) {
        command.append(' ').append(extra);
      }
    }
    return command.toString();
  }

  private static String fakeEngineCommand(Path schemaGate) {
    return fakeEngineCommand(false) + " gate " + quote(schemaGate.toAbsolutePath().toString());
  }

  private static String quote(String value) {
    return "\"" + value + "\"";
  }

  private static GtpConfigurationProbe.Response response(boolean success, String payload) {
    return new GtpConfigurationProbe.Response(success, payload);
  }

  private LoggingRuntime startProbeDiagnostics() {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(false));
    return runtime;
  }

  private static ListAppender<ILoggingEvent> attachEngine() {
    Logger engine = (Logger) LoggerFactory.getLogger(LogCategories.ENGINE);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    engine.addAppender(appender);
    return appender;
  }

  private static String formatted(ListAppender<ILoggingEvent> events) {
    StringBuilder text = new StringBuilder();
    for (ILoggingEvent event : events.list) {
      text.append(event.getFormattedMessage()).append('\n');
    }
    return text.toString();
  }

  private static void awaitLogs(LoggingRuntime runtime) throws Exception {
    Method awaitIdle = LoggingRuntime.class.getDeclaredMethod("awaitIdle");
    awaitIdle.setAccessible(true);
    awaitIdle.invoke(runtime);
  }

  private static final class ScriptedFactory implements GtpConfigurationProbe.SessionFactory {
    private final Queue<GtpConfigurationProbe.Response> responses;
    private final List<String> commands = new ArrayList<String>();
    private boolean closed;

    private ScriptedFactory(GtpConfigurationProbe.Response... responses) {
      this.responses = new ArrayDeque<GtpConfigurationProbe.Response>(Arrays.asList(responses));
    }

    @Override
    public GtpConfigurationProbe.Session open(String engineCommand, Duration timeout) {
      return new GtpConfigurationProbe.Session() {
        @Override
        public GtpConfigurationProbe.Response request(String command) throws IOException {
          commands.add(command);
          GtpConfigurationProbe.Response response = responses.poll();
          if (response == null) {
            throw new IOException("Unexpected command: " + command);
          }
          return response;
        }

        @Override
        public void close() {
          closed = true;
        }
      };
    }
  }

  public static final class FakeGtpEngine {
    private static final String SCHEMA =
        "{\"protocol\":\"zengtp-config\",\"version\":1,\"persistenceOwner\":\"client\","
            + "\"batchSemantics\":\"atomic\",\"fields\":["
            + "{\"name\":\"mode\",\"type\":\"string\",\"group\":\"basic\","
            + "\"defaultValue\":\"rank\",\"enumValues\":[\"rank\",\"fixed-time\",\"advanced\"],"
            + "\"apply\":\"next-search\",\"requiresRestart\":false},"
            + "{\"name\":\"rankPreset\",\"type\":\"string\",\"group\":\"basic\","
            + "\"defaultValue\":\"9d\",\"enumValues\":[\"6k\",\"1d\",\"9d\"],"
            + "\"activeWhen\":\"mode=rank\",\"apply\":\"next-search\",\"requiresRestart\":false},"
            + "{\"name\":\"maxTimeSeconds\",\"type\":\"number\",\"group\":\"advanced\","
            + "\"defaultValue\":60.0,\"minimum\":0,\"activeWhen\":\"mode=fixed-time|advanced\","
            + "\"apply\":\"next-search\",\"requiresRestart\":false},"
            + "{\"name\":\"threads\",\"type\":\"integer\",\"group\":\"advanced\","
            + "\"defaultValue\":4,\"minimum\":1,\"apply\":\"next-search\","
            + "\"requiresRestart\":false}],\"state\":{\"selected\":{\"mode\":\"rank\","
            + "\"rankPreset\":\"9d\",\"maxTimeSeconds\":60.0,\"threads\":4},"
            + "\"effective\":{\"mode\":\"rank\",\"rankPreset\":\"9d\","
            + "\"maxTimeSeconds\":60.0,\"threads\":4}}}";

    private FakeGtpEngine() {}

    public static void main(String[] args) throws Exception {
      boolean hangOnSchema = false;
      boolean exitBeforeHandshake = false;
      Path schemaGate = null;
      int stderrLines = 0;
      for (int i = 0; i < args.length; i++) {
        String arg = args[i];
        if ("hang".equals(arg)) {
          hangOnSchema = true;
        } else if ("exit".equals(arg)) {
          exitBeforeHandshake = true;
        } else if ("stderr".equals(arg)) {
          stderrLines = Math.max(stderrLines, 4);
        } else if ("stderr-huge".equals(arg)) {
          stderrLines = Math.max(stderrLines, 20_000);
        } else if ("gate".equals(arg) && i + 1 < args.length) {
          schemaGate = Path.of(args[++i]);
        }
      }
      if (stderrLines > 0) {
        PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
        err.println("CUDA error: fake backend failed token=probe-secret-token");
        for (int n = 0; n < stderrLines; n++) {
          err.println("probe-stderr " + n);
        }
        err.flush();
      }
      if (exitBeforeHandshake) {
        return;
      }
      String profile = "{}";
      try (BufferedReader input =
              new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
          PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8)) {
        String line;
        while ((line = input.readLine()) != null) {
          if ("quit".equals(line.trim())) {
            return;
          }
          int separator = line.indexOf(' ');
          if (separator <= 0) {
            continue;
          }
          String id = line.substring(0, separator);
          String command = line.substring(separator + 1);
          if ("known_command zengtp_config_schema".equals(command)) {
            respond(output, id, "true");
          } else if ("zengtp_config_schema".equals(command)) {
            if (hangOnSchema) {
              Thread.sleep(10_000);
            } else {
              if (schemaGate != null) {
                Path childPid =
                    schemaGate.resolveSibling(schemaGate.getFileName().toString() + ".pid");
                ProcessHandle current = ProcessHandle.current();
                Instant start =
                    current
                        .info()
                        .startInstant()
                        .orElseThrow(
                            () ->
                                new IllegalStateException(
                                    "The child start instant is unavailable"));
                Files.write(
                    childPid,
                    List.of(
                        Long.toString(current.pid()),
                        start.toString(),
                        FakeGtpEngine.class.getName(),
                        schemaGate.toAbsolutePath().toString()),
                    StandardCharsets.UTF_8);
                Files.createFile(schemaGate);
                while (Files.exists(schemaGate)) {
                  Thread.sleep(10L);
                }
              }
              respond(output, id, SCHEMA);
            }
          } else if (command.startsWith("zengtp_config_set ")) {
            profile = command.substring("zengtp_config_set ".length());
            respond(output, id, "{\"operation\":\"set\"}");
          } else if ("zengtp_config_save".equals(command)) {
            respond(output, id, "{\"profile\":" + profile + ",\"state\":{}}");
          } else {
            output.println("?" + id + " unknown command");
            output.println();
          }
        }
      }
    }

    private static void respond(PrintWriter output, String id, String payload) {
      output.println("=" + id + " " + payload);
      output.println();
    }
  }
}
