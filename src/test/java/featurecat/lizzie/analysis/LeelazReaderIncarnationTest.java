package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGamePlans;
import featurecat.lizzie.analysis.remote.EngineTransport;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.HtmlMessage;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.logging.DiagnosticModule;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import featurecat.lizzie.rules.Board;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeelazReaderIncarnationTest {
  @TempDir Path loggingDirectory;

  @AfterEach
  void resetLogging() {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
  }

  @Test
  void staleReadersDoNotConsumeOrTerminateReboundStreams() throws Exception {
    try (GlobalState ignored = GlobalState.install()) {
      Leelaz engine = new Leelaz("");
      RecordingProcess oldProcess = new RecordingProcess();
      BlockingInputStream oldStdout = new BlockingInputStream("\n");
      BlockingInputStream oldStderr = new BlockingInputStream("", true);
      setField(engine, "process", oldProcess);
      initializeStreams(engine, oldStdout, oldStderr);
      Object oldBinding = getField(engine, "readerStreamBinding");
      engine.started = true;
      engine.isLoaded = true;
      engine.isNormalEnd = true;

      AtomicReference<Throwable> stdoutFailure = new AtomicReference<>();
      AtomicReference<Throwable> stderrFailure = new AtomicReference<>();
      Thread stdoutThread = invokeInThread(engine, "read", stdoutFailure);
      Thread stderrThread = invokeInThread(engine, "readError", stderrFailure);
      assertTrue(oldStdout.awaitRead());
      assertTrue(oldStderr.awaitRead());

      RecordingProcess newProcess = new RecordingProcess();
      ByteArrayInputStream newStdout =
          new ByteArrayInputStream("new-stdout\n".getBytes(StandardCharsets.UTF_8));
      ByteArrayInputStream newStderr =
          new ByteArrayInputStream("new-stderr\n".getBytes(StandardCharsets.UTF_8));
      setField(engine, "process", newProcess);
      initializeStreams(engine, newStdout, newStderr);
      Lizzie.leelaz = engine;
      engine.isKatago = true;
      engine.commandLists.addAll(
          java.util.List.of(
              "stop",
              "boardsize",
              "komi",
              "kata-get-rules",
              "kata-set-rules",
              "clear_board",
              "play",
              "set_position",
              "kata-analyze"));
      setField(engine, "endGetCommandList", true);
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(new Object(), line -> {}, () -> {}, () -> {}));
      invokeTerminal(engine, oldBinding);

      oldStdout.release();
      oldStderr.release();
      stdoutThread.join(1000L);
      stderrThread.join(1000L);

      assertFalse(stdoutThread.isAlive());
      assertFalse(stderrThread.isAlive());
      assertEquals(null, stdoutFailure.get());
      assertEquals(null, stderrFailure.get());
      assertEquals(0, newProcess.destroyCount);
      assertTrue(engine.isStarted());
      assertTrue(engine.isLoaded());
      assertTrue(engine.hasExclusiveGtpLease());
      assertEquals("new-stdout", currentReader(engine, "inputStream").readLine());
      assertEquals("new-stderr", currentReader(engine, "errorStream").readLine());
      assertFalse(recentLines(engine, "recentStdoutLines").contains(""));
      processCommandResponse(engine, "=800000000");
      assertTrue(dispatchExclusiveLine(engine, ""));
      engine.endExclusiveGtpSession();
    }
  }

  @Test
  void currentStdoutTerminalWaitsForInProgressStderrLine() throws Exception {
    try (GlobalState ignored = GlobalState.install()) {
      Leelaz engine = new Leelaz("");
      RecordingProcess process = new RecordingProcess();
      BlockingGtpConsole console = allocate(BlockingGtpConsole.class);
      console.initialize();
      Lizzie.gtpConsole = console;
      setField(engine, "process", process);
      initializeStreams(engine, bytes(""), bytes("held-stderr-line\n"));
      Lizzie.leelaz = engine;
      engine.started = true;
      engine.isLoaded = true;
      engine.isNormalEnd = true;

      AtomicReference<Throwable> stderrFailure = new AtomicReference<>();
      Thread stderrThread = invokeInThread(engine, "readError", stderrFailure);
      assertTrue(console.awaitLine());

      AtomicReference<Throwable> stdoutFailure = new AtomicReference<>();
      Thread stdoutThread = invokeInThread(engine, "read", stdoutFailure);
      stdoutThread.join(1000L);

      assertFalse(stdoutThread.isAlive());
      assertEquals(null, stdoutFailure.get());
      assertEquals(0, process.destroyCount);
      assertTrue(engine.isStarted());

      console.releaseLine();
      stderrThread.join(1000L);

      assertFalse(stderrThread.isAlive());
      assertEquals(null, stderrFailure.get());
      assertEquals(1, process.destroyCount);
      assertFalse(engine.isStarted());
    }
  }

  @Test
  void transportShutdownFailureStillCompletesTerminalCleanupOnce() throws Exception {
    try (GlobalState ignored = GlobalState.install()) {
      CleanupRecordingLeelaz engine = new CleanupRecordingLeelaz();
      RecordingProcess process = new ThrowingProcess();
      BlockingGtpConsole console = allocate(BlockingGtpConsole.class);
      console.initialize();
      Lizzie.gtpConsole = console;
      setField(engine, "process", process);
      initializeStreams(engine, bytes("held-stdout-line\n"), bytes(""));
      Object binding = getField(engine, "readerStreamBinding");
      Lizzie.leelaz = engine;
      engine.started = true;
      engine.isLoaded = true;
      engine.isNormalEnd = true;
      engine.isKatago = true;
      engine.commandLists.addAll(
          java.util.List.of(
              "stop",
              "boardsize",
              "komi",
              "kata-get-rules",
              "kata-set-rules",
              "clear_board",
              "play",
              "set_position",
              "kata-analyze"));
      setField(engine, "endGetCommandList", true);

      AtomicReference<Throwable> stdoutFailure = new AtomicReference<>();
      Thread stdoutThread = invokeInThread(engine, "read", stdoutFailure);
      assertTrue(console.awaitLine());
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(new Object(), line -> {}, () -> {}, () -> {}));
      assertTrue(engine.hasExclusiveGtpLease());
      invokeTerminal(engine, binding);

      console.releaseLine();
      stdoutThread.join(1000L);

      assertFalse(stdoutThread.isAlive());
      assertEquals(null, stdoutFailure.get());
      assertEquals(0, readerLinesInProgress(binding));
      assertFalse(engine.isStarted());
      assertFalse(engine.hasExclusiveGtpLease());
      assertEquals(1, engine.readBoardCleanupCount);
      assertEquals(1, process.destroyCount);

      invokeTerminal(engine, binding);
      assertEquals(1, engine.readBoardCleanupCount);
      assertEquals(1, process.destroyCount);
    }
  }

  @Test
  void recoveryRequestedRemoteTransportSkipsCrashDialogAndDispatchesSessionRebuild()
      throws Exception {
    try (GlobalState ignored = GlobalState.install()) {
      Leelaz engine = new Leelaz("");
      RecoveryTransport transport = new RecoveryTransport();
      RecoveryRecordingManager manager = new RecoveryRecordingManager(engine);
      engine.useRemoteCompute = true;
      setField(engine, "remoteTransport", transport);
      initializeStreams(engine, bytes(""), bytes(""));
      Object binding = getField(engine, "readerStreamBinding");
      Lizzie.leelaz = engine;
      Lizzie.engineManager = manager;
      engine.started = true;
      engine.isLoaded = true;
      engine.isNormalEnd = false;

      invokeTerminal(engine, binding);

      assertEquals(1, transport.closeCount);
      assertEquals(1, manager.restartCount);
      assertFalse(engine.isStarted());
      SwingUtilities.invokeAndWait(() -> {});
      assertFalse(
          java.util.Arrays.stream(Window.getWindows())
              .anyMatch(window -> window instanceof HtmlMessage && window.isDisplayable()));
    }
  }

  @Test
  void unexpectedStdoutEofIsRecordedAsStructuredTransportFailure() throws Exception {
    LoggingRuntime runtime = startEngineDiagnostics();
    try (GlobalState ignored = GlobalState.install()) {
      Leelaz engine = new Leelaz("");
      RecoveryTransport transport = new RecoveryTransport();
      engine.useRemoteCompute = true;
      setField(engine, "remoteTransport", transport);
      initializeStreams(engine, bytes(""), bytes(""));
      Object binding = getField(engine, "readerStreamBinding");
      Lizzie.leelaz = engine;
      Lizzie.engineManager = new RecoveryRecordingManager(engine);
      engine.started = true;
      engine.isLoaded = true;

      invokeTerminal(engine, binding);
      awaitLogs(runtime);

      String app = Files.readString(loggingDirectory.resolve("logs/app.log"));
      assertTrue(app.contains("engine event=transport-failure"), app);
      assertTrue(app.contains("stream=stdout"), app);
      assertTrue(app.contains("reason=unexpected-eof"), app);
      assertTrue(app.contains("errorType=none"), app);
    }
  }

  @Test
  void stdoutIOExceptionIsStructuredWithoutPersistingItsMessage() throws Exception {
    LoggingRuntime runtime = startEngineDiagnostics();
    try (GlobalState ignored = GlobalState.install()) {
      Leelaz engine = new Leelaz("");
      RecoveryTransport transport = new RecoveryTransport();
      engine.useRemoteCompute = true;
      setField(engine, "remoteTransport", transport);
      initializeStreams(engine, bytes(""), bytes(""));
      Object binding = getField(engine, "readerStreamBinding");
      Lizzie.leelaz = engine;
      Lizzie.engineManager = new RecoveryRecordingManager(engine);
      engine.started = true;
      engine.isLoaded = true;

      invokeTerminal(engine, binding, new IOException("secret transport payload"));
      awaitLogs(runtime);

      String app = Files.readString(loggingDirectory.resolve("logs/app.log"));
      assertTrue(app.contains("reason=io-error"), app);
      assertTrue(app.contains("errorType=IOException"), app);
      assertFalse(app.contains("secret transport payload"), app);
    }
  }

  @Test
  void abnormalReaderCleanupSuppressesStaleDiagnosticAfterAnEdtRebind() throws Exception {
    try (GlobalState ignored = GlobalState.install()) {
      DeferredDiagnosticLeelaz engine = new DeferredDiagnosticLeelaz();
      RecordingProcess process = new RecordingProcess();
      setField(engine, "process", process);
      initializeStreams(engine, bytes(""), bytes(""));
      Object binding = getField(engine, "readerStreamBinding");
      int previousCurrentEngineNo = EngineManager.currentEngineNo;
      Lizzie.engineManager =
          new EngineManager(new java.util.ArrayList<>(List.of(engine)));
      EngineManager.currentEngineNo = 0;
      Lizzie.setPrimaryEngine(engine);
      engine.bindCurrentPrimaryEngineGeneration();
      boolean previousFirstLaunch = forceFirstLaunchSession(false);
      engine.started = true;
      engine.isLoaded = true;
      engine.isNormalEnd = false;

      AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
      AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
      AtomicReference<Thread> edtThread = new AtomicReference<>();
      CountDownLatch rebindEntered = new CountDownLatch(1);
      CountDownLatch rebindFinished = new CountDownLatch(1);
      Thread terminalThread =
          new Thread(
              () -> {
                try {
                  invokeTerminal(
                      engine, binding, new IOException("controlled reader terminal failure"));
                } catch (Throwable failure) {
                  terminalFailure.set(failure);
                }
              },
              "test-reader-terminal-cleanup");
      terminalThread.setDaemon(true);

      try {
        terminalThread.start();
        assertTrue(engine.cleanupReached.await(5, TimeUnit.SECONDS));
        assertTrue((boolean) getField(engine, "readerTerminalCleanupInProgress"));

        SwingUtilities.invokeLater(
            () -> {
              edtThread.set(Thread.currentThread());
              rebindEntered.countDown();
              try {
                initializeStreams(engine, bytes(""), bytes(""));
              } catch (Throwable failure) {
                rebindFailure.set(failure);
              } finally {
                rebindFinished.countDown();
              }
            });
        assertTrue(rebindEntered.await(5, TimeUnit.SECONDS));
        assertTrue(awaitThreadState(edtThread, Thread.State.WAITING, 5, TimeUnit.SECONDS));

        engine.allowCleanupToFinish.countDown();
        terminalThread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(terminalThread.isAlive(), "reader cleanup must not wait for the EDT");
        assertTrue(rebindFinished.await(5, TimeUnit.SECONDS), "EDT rebind must be notified");
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(null, terminalFailure.get());
        assertEquals(null, rebindFailure.get());
        assertEquals(null, engine.diagnosticFailure.get());
        assertEquals(
            0,
            engine.diagnosticCount.get(),
            "a terminal diagnostic from the retired reader must not overwrite the rebound runtime");
        assertFalse((boolean) getField(engine, "readerTerminalCleanupInProgress"));
      } finally {
        engine.allowCleanupToFinish.countDown();
        if (terminalThread.isAlive()) {
          terminalThread.interrupt();
          terminalThread.join(TimeUnit.SECONDS.toMillis(5));
        }
        SwingUtilities.invokeAndWait(() -> {});
        forceFirstLaunchSession(previousFirstLaunch);
        EngineManager.currentEngineNo = previousCurrentEngineNo;
      }
    }
  }

  @Test
  void parserTriggeredTerminalCleansUpOnceAfterCurrentLineIsReleased() throws Exception {
    try (GlobalState ignored = GlobalState.install()) {
      BottomToolbar previousToolbar = LizzieFrame.toolbar;
      try {
        Lizzie.frame = allocate(SilentFrame.class);
        LizzieFrame.toolbar = allocate(BottomToolbar.class);
        Leelaz engine = new Leelaz("");
        RecordingProcess process = new RecordingProcess();
        setField(engine, "process", process);
        initializeStreams(engine, bytes(""), bytes("info parser-triggered shutdown\n"));
        Object binding = getField(engine, "readerStreamBinding");
        process.observeReaderLines(engine);
        Lizzie.leelaz = engine;
        Lizzie.board = new Board();
        engine.started = true;
        engine.isLoaded = true;
        engine.isNormalEnd = true;
        engine.isZen = true;
        engine.sendOrdinaryAnalysisCommandForTest("lz-analyze 1");

        Method readError = Leelaz.class.getDeclaredMethod("readError");
        readError.setAccessible(true);
        readError.invoke(engine);

        assertEquals(1, process.destroyCount);
        assertEquals(0, process.readerLinesInProgressAtDestroy);
        assertFalse(engine.isStarted());

        invokeTerminal(engine, binding);
        assertEquals(1, process.destroyCount);
        SwingUtilities.invokeAndWait(() -> {});
        assertFalse(
            java.util.Arrays.stream(Window.getWindows())
                .anyMatch(window -> window instanceof HtmlMessage && window.isDisplayable()));
      } finally {
        LizzieFrame.toolbar = previousToolbar;
      }
    }
  }

  @Test
  void currentStderrEofDoesNotDiscardAlreadyArrivedStdoutTail() throws Exception {
    try (GlobalState ignored = GlobalState.install()) {
      Leelaz engine = new Leelaz("");
      RecordingProcess process = new RecordingProcess();
      setField(engine, "process", process);
      initializeStreams(engine, bytes("tail-frame\n"), bytes(""));
      Lizzie.leelaz = engine;
      engine.started = true;
      engine.isLoaded = true;
      engine.isNormalEnd = true;

      Method readError = Leelaz.class.getDeclaredMethod("readError");
      readError.setAccessible(true);
      readError.invoke(engine);

      assertEquals(0, process.destroyCount);
      assertTrue(engine.isStarted());

      Method read = Leelaz.class.getDeclaredMethod("read");
      read.setAccessible(true);
      read.invoke(engine);

      assertTrue(recentLines(engine, "recentStdoutLines").contains("tail-frame"));
      assertEquals(1, process.destroyCount);
      assertFalse(engine.isStarted());
    }
  }

  @Test
  void currentStderrReadFailureOnlyEndsStderrReader() throws Exception {
    try (GlobalState ignored = GlobalState.install()) {
      Leelaz engine = new Leelaz("");
      RecordingProcess process = new RecordingProcess();
      InputStream failingStderr =
          new InputStream() {
            @Override
            public int read() throws IOException {
              throw new IOException("controlled current stderr failure");
            }
          };
      setField(engine, "process", process);
      initializeStreams(engine, bytes(""), failingStderr);
      engine.started = true;
      engine.isNormalEnd = true;

      Method readError = Leelaz.class.getDeclaredMethod("readError");
      readError.setAccessible(true);
      readError.invoke(engine);

      assertEquals(0, process.destroyCount);
      assertTrue(engine.isStarted());
    }
  }

  @Test
  void genmovePkPassingContinuationDoesNotReadInsideParser() throws Exception {
    try (GlobalState ignored = GlobalState.install()) {
      Leelaz engine = new Leelaz("");
      Leelaz dummy = new Leelaz("");
      EngineManager manager = new EngineManager(List.of(engine, dummy));
      BufferedReader reboundReader =
          new BufferedReader(new InputStreamReader(bytes("D4\n"), StandardCharsets.UTF_8));
      setField(engine, "inputStream", reboundReader);
      setField(engine, "currentEngineN", 0);
      Lizzie.leelaz = engine;
      Lizzie.engineManager = manager;
      EngineManager.resetEngineGameTransactionStateForTest();
      assertNotNull(
          EngineManager.beginEngineGameTransaction(
              manager, EngineGamePlans.harness(0, 1, true), null, true));
      Lizzie.board = new Board();
      BufferedReader capturedReader =
          new BufferedReader(
              new InputStreamReader(bytes("not-a-coordinate\n"), StandardCharsets.UTF_8));

      Method method =
          Leelaz.class.getDeclaredMethod(
              "parseLineForGenmovePk", String.class, BufferedReader.class, Object.class);
      method.setAccessible(true);
      method.invoke(engine, "= Passing", capturedReader, getField(engine, "readerStreamBinding"));

      assertEquals("D4", reboundReader.readLine());
      assertEquals("not-a-coordinate", capturedReader.readLine());
    }
  }

  private static Thread invokeInThread(
      Leelaz engine, String methodName, AtomicReference<Throwable> failure) {
    Thread thread =
        new Thread(
            () -> {
              try {
                Method method = Leelaz.class.getDeclaredMethod(methodName);
                method.setAccessible(true);
                method.invoke(engine);
              } catch (Throwable throwable) {
                failure.set(throwable);
              }
            },
            "test-" + methodName);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static void initializeStreams(Leelaz engine, InputStream stdout, InputStream stderr)
      throws Exception {
    Method method =
        Leelaz.class.getDeclaredMethod(
            "initializeStreams", InputStream.class, OutputStream.class, InputStream.class);
    method.setAccessible(true);
    method.invoke(engine, stdout, new ByteArrayOutputStream(), stderr);
  }

  private LoggingRuntime startEngineDiagnostics() {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(loggingDirectory, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.ENGINE)));
    return runtime;
  }

  private static void awaitLogs(LoggingRuntime runtime) throws Exception {
    Method method = LoggingRuntime.class.getDeclaredMethod("awaitIdle");
    method.setAccessible(true);
    method.invoke(runtime);
  }

  private static void invokeTerminal(Leelaz engine, Object binding) throws Exception {
    invokeTerminal(engine, binding, null);
  }

  private static void invokeTerminal(Leelaz engine, Object binding, Throwable failure)
      throws Exception {
    Method method =
        Leelaz.class.getDeclaredMethod(
            "terminateReaderIncarnation", binding.getClass(), Throwable.class);
    method.setAccessible(true);
    method.invoke(engine, binding, failure);
  }

  private static BufferedReader currentReader(Leelaz engine, String name) throws Exception {
    return (BufferedReader) getField(engine, name);
  }

  private static int readerLinesInProgress(Object binding) throws Exception {
    Field field = binding.getClass().getDeclaredField("linesInProgress");
    field.setAccessible(true);
    return field.getInt(binding);
  }

  private static void processCommandResponse(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("processCommandResponseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static boolean dispatchExclusiveLine(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("dispatchExclusiveGtpLine", String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(engine, line);
  }

  @SuppressWarnings("unchecked")
  private static ArrayDeque<String> recentLines(Leelaz engine, String name) throws Exception {
    return (ArrayDeque<String>) getField(engine, name);
  }

  private static Object getField(Leelaz engine, String name) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(engine);
  }

  private static boolean awaitThreadState(
      AtomicReference<Thread> thread, Thread.State expected, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    do {
      Thread current = thread.get();
      if (current != null && current.getState() == expected) {
        return true;
      }
      Thread.sleep(5L);
    } while (System.nanoTime() < deadline);
    Thread current = thread.get();
    return current != null && current.getState() == expected;
  }

  private static boolean forceFirstLaunchSession(boolean value) throws Exception {
    Field field = Lizzie.class.getDeclaredField("firstLaunchSession");
    field.setAccessible(true);
    boolean previous = field.getBoolean(null);
    field.setBoolean(null, value);
    return previous;
  }

  private static void setField(Leelaz engine, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(engine, value);
  }

  private static ByteArrayInputStream bytes(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  private static <T> T allocate(Class<T> type) throws InstantiationException {
    return type.cast(UnsafeHolder.UNSAFE.allocateInstance(type));
  }

  private static final class GlobalState implements AutoCloseable {
    private final Config previousConfig;
    private final Leelaz previousLeelaz;
    private final GtpConsolePane previousGtpConsole;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final EngineManager previousEngineManager;

    private GlobalState(
        Config previousConfig,
        Leelaz previousLeelaz,
        GtpConsolePane previousGtpConsole,
        Board previousBoard,
        LizzieFrame previousFrame,
        EngineManager previousEngineManager) {
      this.previousConfig = previousConfig;
      this.previousLeelaz = previousLeelaz;
      this.previousGtpConsole = previousGtpConsole;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousEngineManager = previousEngineManager;
    }

    private static GlobalState install() throws Exception {
      GlobalState state =
          new GlobalState(
              Lizzie.config,
              Lizzie.leelaz,
              Lizzie.gtpConsole,
              Lizzie.board,
              Lizzie.frame,
              Lizzie.engineManager);
      Lizzie.config =
          ConfigTestHelper.createForTests(Files.createTempDirectory("leelaz-reader-incarnation"));
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      Lizzie.frame = null;
      EngineManager.resetEngineGameTransactionStateForTest();
      return state;
    }

    @Override
    public void close() {
      EngineManager.resetEngineGameTransactionStateForTest();
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.gtpConsole = previousGtpConsole;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.engineManager = previousEngineManager;
    }
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super(null);
    }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void addLine(String line) {}

    @Override
    public void addErrorLine(String line) {}
  }

  private static final class SilentFrame extends LizzieFrame {
    private SilentFrame() {}

    @Override
    public boolean isDisplayable() {
      return false;
    }
  }

  private static final class BlockingGtpConsole extends GtpConsolePane {
    private CountDownLatch lineEntered;
    private CountDownLatch lineReleased;

    private BlockingGtpConsole() {
      super(null);
    }

    private void initialize() {
      lineEntered = new CountDownLatch(1);
      lineReleased = new CountDownLatch(1);
    }

    @Override
    public boolean isVisible() {
      return true;
    }

    @Override
    public void addLine(String line) {
      blockLine();
    }

    @Override
    public void addErrorLine(String line) {
      blockLine();
    }

    private void blockLine() {
      lineEntered.countDown();
      try {
        if (!lineReleased.await(2, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out waiting to release parser line");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }

    private boolean awaitLine() throws InterruptedException {
      return lineEntered.await(1, TimeUnit.SECONDS);
    }

    private void releaseLine() {
      lineReleased.countDown();
    }
  }

  private static final class CleanupRecordingLeelaz extends Leelaz {
    private int readBoardCleanupCount;

    private CleanupRecordingLeelaz() throws IOException {
      super("");
    }

    @Override
    public void failReadBoardGmaEngineRestore(String detail) {
      readBoardCleanupCount++;
      super.failReadBoardGmaEngineRestore(detail);
    }
  }

  private static final class DeferredDiagnosticLeelaz extends Leelaz {
    private final CountDownLatch cleanupReached = new CountDownLatch(1);
    private final CountDownLatch allowCleanupToFinish = new CountDownLatch(1);
    private final CountDownLatch diagnosticShown = new CountDownLatch(1);
    private final AtomicInteger diagnosticCount = new AtomicInteger();
    private final AtomicBoolean diagnosticOnEdt = new AtomicBoolean();
    private final AtomicBoolean diagnosticRanWithNullConfig = new AtomicBoolean();
    private final AtomicReference<Throwable> diagnosticFailure = new AtomicReference<>();
    private volatile boolean diagnosticModal = true;

    private DeferredDiagnosticLeelaz() throws IOException {
      super("");
    }

    @Override
    public void failReadBoardGmaEngineRestore(String detail) {
      cleanupReached.countDown();
      try {
        if (!allowCleanupToFinish.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out waiting to finish reader cleanup");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }

    @Override
    public void tryToDignostic(String message, boolean isModal) {
      Config config = Lizzie.config;
      try {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
          Lizzie.config = null;
          diagnosticRanWithNullConfig.set(true);
          super.tryToDignostic(message, isModal);
        }
      } catch (Throwable failure) {
        diagnosticFailure.set(failure);
      } finally {
        Lizzie.config = config;
        diagnosticCount.incrementAndGet();
        diagnosticOnEdt.set(SwingUtilities.isEventDispatchThread());
        diagnosticModal = isModal;
        diagnosticShown.countDown();
      }
    }
  }

  private static final class RecoveryRecordingManager extends EngineManager {
    private int restartCount;

    private RecoveryRecordingManager(Leelaz engine) {
      super(java.util.List.of(engine));
    }

    @Override
    void restartUnresponsiveRemoteEngine(Leelaz engine, int index) {
      restartCount++;
    }
  }

  private static final class RecoveryTransport implements EngineTransport {
    private int closeCount;

    @Override
    public void start() {}

    @Override
    public InputStream stdout() {
      return bytes("");
    }

    @Override
    public OutputStream stdin() {
      return new ByteArrayOutputStream();
    }

    @Override
    public InputStream stderr() {
      return bytes("");
    }

    @Override
    public boolean isOpen() {
      return false;
    }

    @Override
    public boolean isRecoveryRequested() {
      return true;
    }

    @Override
    public String description() {
      return "test recovery transport";
    }

    @Override
    public void close() {
      closeCount++;
    }

    @Override
    public void abort() {
      closeCount++;
    }
  }

  private static final class BlockingInputStream extends InputStream {
    private final byte[] payload;
    private final boolean fail;
    private final CountDownLatch reading = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private int offset;

    private BlockingInputStream(String payload) {
      this(payload, false);
    }

    private BlockingInputStream(String payload, boolean fail) {
      this.payload = payload.getBytes(StandardCharsets.UTF_8);
      this.fail = fail;
    }

    @Override
    public int read(byte[] buffer, int targetOffset, int length) throws IOException {
      reading.countDown();
      try {
        if (!released.await(2, TimeUnit.SECONDS)) {
          throw new IOException("timed out waiting to release test reader");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException(interrupted);
      }
      if (fail) {
        throw new IOException("controlled stale reader failure");
      }
      if (offset >= payload.length) {
        return -1;
      }
      int copied = Math.min(length, payload.length - offset);
      System.arraycopy(payload, offset, buffer, targetOffset, copied);
      offset += copied;
      return copied;
    }

    @Override
    public int read() throws IOException {
      byte[] single = new byte[1];
      return read(single, 0, 1) < 0 ? -1 : single[0] & 0xff;
    }

    private boolean awaitRead() throws InterruptedException {
      return reading.await(1, TimeUnit.SECONDS);
    }

    private void release() {
      released.countDown();
    }
  }

  private static class RecordingProcess extends Process {
    private int destroyCount;
    private Leelaz readerOwner;
    private int readerLinesInProgressAtDestroy = -1;

    private void observeReaderLines(Leelaz owner) {
      readerOwner = owner;
    }

    @Override
    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return bytes("");
    }

    @Override
    public InputStream getErrorStream() {
      return bytes("");
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {
      destroyCount++;
      if (readerOwner != null) {
        try {
          Object binding = getField(readerOwner, "readerStreamBinding");
          Field linesInProgress = binding.getClass().getDeclaredField("linesInProgress");
          linesInProgress.setAccessible(true);
          readerLinesInProgressAtDestroy = linesInProgress.getInt(binding);
        } catch (Exception reflectionFailure) {
          throw new AssertionError(reflectionFailure);
        }
      }
    }

    @Override
    public boolean isAlive() {
      return true;
    }
  }

  private static final class ThrowingProcess extends RecordingProcess {
    @Override
    public void destroy() {
      super.destroy();
      throw new IllegalStateException("controlled process destroy failure");
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Failed to access Unsafe", ex);
      }
    }
  }
}
