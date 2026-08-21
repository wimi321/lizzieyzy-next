package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkRemoteSgfLoggingTest {
  private static final String SENSITIVE_CANARY = "T05_SENSITIVE_BODY_CANARY";
  private static final String PROTOCOL_CANARY = "T05_WS_PAYLOAD_CANARY";
  private static final String SGF_CANARY = "T05_SGF_CANARY (;SZ[19]C[secret-game]B[pd])";

  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    LoggingRuntime.resetForTests();
  }

  @Test
  void selectedDiagnosticsRecordSafeMetadataWithoutSecrets() throws Exception {
    LoggingRuntime runtime = startDiagnostics();
    String requestId = runtime.newRequestIdentity();

    NetworkObservation.recordRemote(
        "POST",
        "https://www.zhizigo.com/api/cluster/account/login?token=query-secret",
        NetworkEndpointCategory.AUTHENTICATION,
        401,
        12L,
        "failed",
        requestId);
    runtime.awaitIdle();

    String app = readApp();
    assertTrue(app.contains("remote event=http"), app);
    assertTrue(app.contains("method=POST"), app);
    assertTrue(app.contains("host=www.zhizigo.com"), app);
    assertTrue(app.contains("category=authentication"), app);
    assertTrue(app.contains("status=401"), app);
    assertTrue(app.contains("latencyMs=12"), app);
    assertTrue(app.contains("outcome=failed"), app);
    assertTrue(app.contains("request=" + requestId), app);
    assertFalse(app.contains("query-secret"), app);
    assertFalse(app.contains("/api/cluster/account/login"), app);
    assertFalse(app.contains("Authorization"), app);
    assertFalse(app.contains("password"), app);
  }

  @Test
  void sensitiveBodiesAreNeverConstructedEvenUnderFullTrace() throws Exception {
    LoggingRuntime runtime = startDiagnostics();
    runtime.startFullTrace(EnumSet.of(TraceScope.NETWORK_WEBSOCKET));
    AtomicBoolean constructed = new AtomicBoolean();

    NetworkObservation.tracePayload(
        NetworkEndpointCategory.AUTHENTICATION,
        "send",
        () -> {
          constructed.set(true);
          return SENSITIVE_CANARY;
        });
    NetworkObservation.tracePayload(
        NetworkEndpointCategory.ACCOUNT,
        "recv",
        () -> {
          constructed.set(true);
          return SENSITIVE_CANARY;
        });
    NetworkObservation.tracePayload(
        NetworkEndpointCategory.PAYMENT,
        "send",
        () -> {
          constructed.set(true);
          return SENSITIVE_CANARY;
        });
    NetworkObservation.tracePayload(
        NetworkEndpointCategory.CREDENTIAL,
        "recv",
        () -> {
          constructed.set(true);
          return SENSITIVE_CANARY;
        });
    runtime.awaitIdle();
    runtime.stopFullTrace();
    runtime.awaitIdle();

    assertFalse(constructed.get());
    String scanned = scanLogs();
    assertFalse(scanned.contains(SENSITIVE_CANARY), scanned);
  }

  @Test
  void selectedFullTraceWritesEligiblePayloadOnlyToNetworkTrace() throws Exception {
    LoggingRuntime runtime = startDiagnostics();
    NetworkObservation.tracePayload(
        NetworkEndpointCategory.PROTOCOL, "send", () -> PROTOCOL_CANARY);
    runtime.awaitIdle();
    assertFalse(Files.exists(tempDir.resolve("logs/network-trace.log")));

    runtime.startFullTrace(EnumSet.of(TraceScope.NETWORK_WEBSOCKET));
    NetworkObservation.tracePayload(
        NetworkEndpointCategory.PROTOCOL, "send", () -> PROTOCOL_CANARY);
    runtime.awaitIdle();
    runtime.stopFullTrace();
    runtime.awaitIdle();

    String app = readApp();
    assertFalse(app.contains(PROTOCOL_CANARY), app);
    assertFalse(app.contains("network raw"), app);

    String trace = Files.readString(tempDir.resolve("logs/network-trace.log"));
    assertTrue(trace.contains("Full Trace session started"), trace);
    assertTrue(trace.contains("scope=network-websocket"), trace);
    assertTrue(trace.contains("network raw direction=send payload=" + PROTOCOL_CANARY), trace);
    assertTrue(trace.contains("Full Trace session stopped"), trace);
  }

  @Test
  void disabledTraceDoesNotSerializePayloads() throws Exception {
    startRuntime();
    AtomicBoolean constructed = new AtomicBoolean();
    NetworkObservation.tracePayload(
        NetworkEndpointCategory.PROTOCOL,
        "send",
        () -> {
          constructed.set(true);
          return PROTOCOL_CANARY;
        });
    assertFalse(constructed.get());
    assertFalse(Files.exists(tempDir.resolve("logs/network-trace.log")));
  }

  @Test
  void stalledNetworkTraceQueueDoesNotBlockProducer() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(32, 1, 1, 1, 7, 10_000, 1_000));
    runtime.applySettings(LoggingSettings.defaults().withDiagnosticsEnabled(true));
    runtime.startFullTrace(EnumSet.of(TraceScope.NETWORK_WEBSOCKET));
    CountDownLatch gate = new CountDownLatch(1);
    runtime.blockPersistenceForTests(LogStream.NETWORK_TRACE, gate);
    org.slf4j.LoggerFactory.getLogger(LogCategories.NETWORK_TRACE).info("block-one");
    long began = System.nanoTime();
    NetworkObservation.tracePayload(
        NetworkEndpointCategory.PROTOCOL, "send", () -> PROTOCOL_CANARY);
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
    gate.countDown();
    assertTrue(elapsed < 500, "producer blocked for " + elapsed + "ms");
  }

  @Test
  void sgfEventsRecordOutcomeWithoutGameContent() throws Exception {
    LoggingRuntime runtime = startRuntime();
    SgfObservation.record("open", "ok", "/tmp/game.sgf", null);
    SgfObservation.record("save", "failed", "/home/dev/game.sgf", new java.io.IOException("disk"));
    SgfObservation.record("import", "ok", null, null);
    SgfObservation.record("export", "ok", "clipboard", null);
    runtime.awaitIdle();

    String app = readApp();
    assertTrue(app.contains("sgf operation=open outcome=ok"), app);
    assertTrue(app.contains("sgf operation=save outcome=failed"), app);
    assertTrue(app.contains("sgf operation=import outcome=ok"), app);
    assertTrue(app.contains("sgf operation=export outcome=ok"), app);
    assertTrue(app.contains("/home/dev/game.sgf"), app);
    assertFalse(app.contains(SGF_CANARY), app);
    assertFalse(app.contains("C[secret-game]"), app);
    assertFalse(app.contains("B[pd]"), app);
  }

  @Test
  void engineModuleDoesNotEmitNetworkDiagnostics() throws Exception {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.ENGINE)));
    NetworkObservation.recordNetwork(
        "GET", "example.test", NetworkEndpointCategory.PROTOCOL, 200, 3L, "ok", "req-hidden");
    runtime.awaitIdle();
    assertFalse(readApp().contains("network event=http"), readApp());
  }

  private LoggingRuntime startRuntime() {
    LoggingRuntime.resetForTests();
    return LoggingRuntime.initialize(
        new WorkDirectoryResolution(tempDir, List.of()),
        new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
  }

  private LoggingRuntime startDiagnostics() {
    LoggingRuntime runtime = startRuntime();
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.NETWORK_REMOTE)));
    return runtime;
  }

  private String readApp() throws Exception {
    Path file = tempDir.resolve("logs/app.log");
    return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
  }

  private String scanLogs() throws Exception {
    Path logs = tempDir.resolve("logs");
    if (!Files.exists(logs)) {
      return "";
    }
    StringBuilder scanned = new StringBuilder();
    try (var stream = Files.walk(logs)) {
      stream
          .filter(Files::isRegularFile)
          .forEach(
              path -> {
                try {
                  scanned.append(Files.readString(path));
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
    }
    return scanned.toString();
  }
}
