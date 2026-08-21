package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.logging.DiagnosticModule;
import featurecat.lizzie.logging.EngineObservation;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisResourceCoordinatorTest {
  @TempDir Path tempDir;

  @Test
  void foregroundReleasesOnlyIdleOrAutomaticDedicatedProcesses() {
    assertEquals(
        AnalysisResourceCoordinator.ForegroundDecision.SHARED_ENGINE,
        AnalysisResourceCoordinator.decideForegroundStart(true, false, true, true));
    assertEquals(
        AnalysisResourceCoordinator.ForegroundDecision.NONE,
        AnalysisResourceCoordinator.decideForegroundStart(false, false, true, true));
    assertEquals(
        AnalysisResourceCoordinator.ForegroundDecision.RELEASE_IDLE_SECONDARY,
        AnalysisResourceCoordinator.decideForegroundStart(false, true, false, false));
    assertEquals(
        AnalysisResourceCoordinator.ForegroundDecision.PREEMPT_AUTOMATIC_SECONDARY,
        AnalysisResourceCoordinator.decideForegroundStart(false, true, true, true));
    assertEquals(
        AnalysisResourceCoordinator.ForegroundDecision.KEEP_USER_TASK,
        AnalysisResourceCoordinator.decideForegroundStart(false, true, true, false));
  }

  @Test
  void diagnosticCommandsRedactCredentialsAndKeepTuningParameters() {
    String redacted =
        AnalysisResourceCoordinator.redactCommand(
            "remote --token=secret-token --password hunter2 wss://host/path?token=query-token");

    assertFalse(redacted.contains("secret-token"));
    assertFalse(redacted.contains("hunter2"));
    assertFalse(redacted.contains("query-token"));
    assertTrue(redacted.contains("<redacted>"));

    String diagnosticCommand =
        AnalysisResourceCoordinator.diagnosticCommand(
            "\"C:\\\\Users\\\\Player\\\\KataGo\\\\katago.exe\" gtp "
                + "-model \"C:\\\\Users\\\\Player\\\\weights\\\\model.bin.gz\" "
                + "-config \"C:\\\\Users\\\\Player\\\\configs\\\\gtp.cfg\" "
                + "-override-config homeDataDir=C:\\\\Users\\\\Player\\\\cache");
    assertFalse(diagnosticCommand.contains("Player"));
    assertTrue(diagnosticCommand.startsWith("katago.exe gtp"));
    assertTrue(diagnosticCommand.contains("-model model.bin.gz"));
    assertTrue(diagnosticCommand.contains("-config gtp.cfg"));
    assertTrue(diagnosticCommand.contains("homeDataDir=<local-path>"));

    Map<String, String> parameters =
        AnalysisResourceCoordinator.parseDynamicParameters(
            List.of(
                "kata-set-param numSearchThreads 12", "kata-set-param nnMaxBatchSize 64", "name"));
    assertEquals(Map.of("numSearchThreads", "12", "nnMaxBatchSize", "64"), parameters);
  }

  @Test
  void configHashIsStableAndMissingFilesStayEmpty() throws Exception {
    Path config = tempDir.resolve("gtp.cfg");
    Files.writeString(config, "numSearchThreads = 12\n", StandardCharsets.UTF_8);

    assertEquals(
        AnalysisResourceCoordinator.fileSha256(config),
        AnalysisResourceCoordinator.fileSha256(config));
    assertEquals(64, AnalysisResourceCoordinator.fileSha256(config).length());
    assertEquals("", AnalysisResourceCoordinator.fileSha256(tempDir.resolve("missing.cfg")));
  }

  @Test
  void throughputElapsedTimeUsesAStableNanosecondWindow() {
    assertEquals(0.25, AnalysisResourceCoordinator.elapsedSeconds(1_000L, 250_001_000L));
    assertEquals(0.0, AnalysisResourceCoordinator.elapsedSeconds(2_000L, 1_000L));
  }

  @Test
  void foregroundThroughputUsesNamedPlayoutFields() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.ENGINE)));
    Object owner = new Object();
    AnalysisResourceCoordinator.processStarted(
        owner, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, "katago gtp", null);
    AnalysisResourceCoordinator.foregroundPlayoutSample(owner, 40);
    Thread.sleep(260L);
    AnalysisResourceCoordinator.foregroundPlayoutSample(owner, 90);
    Method awaitIdle = LoggingRuntime.class.getDeclaredMethod("awaitIdle");
    awaitIdle.setAccessible(true);
    awaitIdle.invoke(runtime);
    String app = Files.readString(tempDir.resolve("logs/app.log"), StandardCharsets.UTF_8);
    assertTrue(app.contains("engine event=foreground-throughput playouts=90"), app);
    assertTrue(app.contains("playoutsPerSecond="), app);
    int throughput = app.indexOf("engine event=foreground-throughput");
    String line = app.substring(throughput, app.indexOf('\n', throughput));
    assertFalse(line.contains("pid="), line);
    runtime.shutdown();
  }

  @Test
  void localComputeRegistryTracksAliveProcessesWithoutDiagnostics() {
    Object owner = new Object();
    ControllableProcess process = new ControllableProcess();
    int baseline = AnalysisResourceCoordinator.activeLocalComputeProcessCount();

    AnalysisResourceCoordinator.processStarted(
        owner, AnalysisResourceCoordinator.Purpose.OTHER, "katago analysis", process);
    assertEquals(baseline + 1, AnalysisResourceCoordinator.activeLocalComputeProcessCount());
    assertTrue(AnalysisResourceCoordinator.hasActiveLocalComputeProcess());

    AnalysisResourceCoordinator.processStopped(
        owner, AnalysisResourceCoordinator.Purpose.OTHER, process);
    assertEquals(
        baseline + 1,
        AnalysisResourceCoordinator.activeLocalComputeProcessCount(),
        "a shutdown request must not hide a child that is still alive");

    process.destroy();
    assertEquals(baseline, AnalysisResourceCoordinator.activeLocalComputeProcessCount());
  }

  @Test
  void optInDiagnosticsAreStructuredAndNeverPersistSecrets() throws Exception {
    Path jsonl = tempDir.resolve("analysis-resource-diagnostics.jsonl");
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.ENGINE)));
    Object owner = new Object();
    AnalysisResourceCoordinator.processStarted(
        owner,
        AnalysisResourceCoordinator.Purpose.MAIN_BOARD,
        "/Users/private-user/KataGo/katago gtp --token=private-value "
            + "-model /Users/private-user/weights/model.bin.gz",
        null);
    AnalysisResourceCoordinator.commandSent(
        owner,
        AnalysisResourceCoordinator.Purpose.MAIN_BOARD,
        "kata-set-param numSearchThreads 12");
    AnalysisResourceCoordinator.foregroundPausedForAuxiliary(
        owner, AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS);
    AnalysisResourceCoordinator.processStopped(
        owner, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, null);
    Method awaitIdle = LoggingRuntime.class.getDeclaredMethod("awaitIdle");
    awaitIdle.setAccessible(true);
    awaitIdle.invoke(runtime);

    String app = Files.readString(tempDir.resolve("logs/app.log"), StandardCharsets.UTF_8);
    assertTrue(app.contains("engine event=started"), app);
    assertTrue(app.contains("engine event=process-started"), app);
    assertTrue(app.contains("engine event=dynamic-parameter"), app);
    assertTrue(app.contains("numSearchThreads=12"), app);
    assertTrue(app.contains("engine event=foreground-paused"), app);
    assertTrue(app.contains("AUTO_QUICK_ANALYSIS"), app);
    assertTrue(app.contains("engine event=stopped"), app);
    assertFalse(app.contains("private-value"), app);
    assertFalse(app.contains("private-user"), app);
    assertFalse(Files.exists(jsonl));
    runtime.shutdown();
  }

  @Test
  void remoteStartThenPonderKeepsIdentity() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.ENGINE)));
    Object owner = new Object();
    String id = EngineObservation.ensureStarted(owner, "MAIN_BOARD");
    AnalysisResourceCoordinator.processStarted(
        owner, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, "ssh katago", null);
    AnalysisResourceCoordinator.processStarted(
        owner, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, "ssh katago", null);
    Method awaitIdle = LoggingRuntime.class.getDeclaredMethod("awaitIdle");
    awaitIdle.setAccessible(true);
    awaitIdle.invoke(runtime);
    assertEquals(id, EngineObservation.identityFor(owner));
    String app = Files.readString(tempDir.resolve("logs/app.log"), StandardCharsets.UTF_8);
    assertFalse(app.contains("reason=replaced"), app);
    runtime.shutdown();
  }

  @Test
  void staleProcessStopCannotRemoveReplacementDiagnosticsRegistration() throws Exception {
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.ENGINE)));
    Object owner = new Object();
    ControllableProcess retired = new ControllableProcess(101L);
    ControllableProcess replacement = new ControllableProcess(202L);
    try {
      AnalysisResourceCoordinator.processStarted(
          owner, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, "retired", retired);
      AnalysisResourceCoordinator.processStarted(
          owner, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, "replacement", replacement);

      retired.destroy();
      AnalysisResourceCoordinator.processStopped(
          owner, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, retired);
      replacement.destroy();
      AnalysisResourceCoordinator.processStopped(
          owner, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, replacement);
      Method awaitIdle = LoggingRuntime.class.getDeclaredMethod("awaitIdle");
      awaitIdle.setAccessible(true);
      awaitIdle.invoke(runtime);

      String app = Files.readString(tempDir.resolve("logs/app.log"), StandardCharsets.UTF_8);
      assertTrue(app.contains("pid=202"), app);
      assertFalse(app.contains("event=process-stopped purpose=MAIN_BOARD pid=101"), app);
    } finally {
      retired.destroy();
      replacement.destroy();
      AnalysisResourceCoordinator.activeLocalComputeProcessCount();
      runtime.shutdown();
    }
  }

  private static void restoreSystemProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }

  private static final class ControllableProcess extends Process {
    private volatile boolean alive = true;
    private final CompletableFuture<Process> exit = new CompletableFuture<>();
    private final long pid;

    private ControllableProcess() {
      this(-1L);
    }

    private ControllableProcess(long pid) {
      this.pid = pid;
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      alive = false;
      exit.complete(this);
      return 0;
    }

    @Override
    public int exitValue() {
      if (alive) {
        throw new IllegalThreadStateException("process is still alive");
      }
      return 0;
    }

    @Override
    public void destroy() {
      alive = false;
      exit.complete(this);
    }

    @Override
    public boolean isAlive() {
      return alive;
    }

    @Override
    public CompletableFuture<Process> onExit() {
      return exit;
    }

    @Override
    public long pid() {
      return pid;
    }
  }
}
