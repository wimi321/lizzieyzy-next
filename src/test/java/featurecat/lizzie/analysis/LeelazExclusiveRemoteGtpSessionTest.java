package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LeelazExclusiveRemoteGtpSessionTest {

  @Test
  void exclusiveRemoteSessionWaitsForStopThenRoutesOnlyQuickCurveTraffic() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    ByteArrayOutputStream bytes = installOutput(engine);
    List<String> received = new ArrayList<>();
    AtomicInteger ready = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(
            received::add, ready::incrementAndGet, closed::incrementAndGet));
    assertEquals("800000000 stop\n", bytes.toString(StandardCharsets.UTF_8));
    assertTrue(dispatch(engine, "info move D4 visits 10"));
    assertEquals(0, ready.get());

    assertFalse(dispatch(engine, "="));
    processCommandResponse(engine, "=");
    assertEquals(0, ready.get(), "the analyze terminator is not the numbered stop response.");
    assertFalse(dispatch(engine, "=800000000"));
    processCommandResponse(engine, "=800000000");

    assertEquals(1, ready.get());
    assertTrue(dispatch(engine, ""), "the trailing stop boundary must be consumed once.");
    assertTrue(engine.sendExclusiveGtpCommand("kata-raw-nn 0"));
    assertEquals("800000000 stop\nkata-raw-nn 0\n", bytes.toString(StandardCharsets.UTF_8));

    assertTrue(dispatch(engine, "= symmetry 0"));
    assertTrue(dispatch(engine, "whiteWin 0.61"));
    assertEquals(List.of("= symmetry 0", "whiteWin 0.61"), received);

    engine.endExclusiveGtpSession();
    assertFalse(engine.sendExclusiveGtpCommand("kata-raw-nn 0"));
    assertEquals(0, closed.get());
  }

  @Test
  void exclusiveRemoteSessionAbortsCleanlyOnStopError() throws Exception {
    Leelaz engine = reusableKatagoEngine(true, false);
    installOutput(engine);
    AtomicInteger closed = new AtomicInteger();

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(line -> {}, () -> {}, closed::incrementAndGet));
    assertTrue(
        dispatch(engine, "?800000000 cannot stop"),
        "the matching stop error must be consumed before the normal response path sees it.");

    assertEquals(1, closed.get());
    assertFalse(engine.sendExclusiveGtpCommand("kata-raw-nn 0"));
    assertTrue(
        engine.isLoaded(), "the stop error itself must not mark the engine restore as failed.");
  }

  @Test
  void exclusiveSessionIgnoresUnrelatedStopResponsesAndRejectsSecondLease() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    AtomicInteger ready = new AtomicInteger();

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(line -> {}, ready::incrementAndGet, () -> {}));
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.EXISTING_LEASE,
        engine.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));

    assertFalse(dispatch(engine, "=800000001"));
    processCommandResponse(engine, "=800000001");
    assertFalse(dispatch(engine, "?800000001 unrelated failure"));
    assertEquals(0, ready.get());
    assertTrue(engine.hasExclusiveGtpLease());

    assertFalse(dispatch(engine, "=800000000"));
    processCommandResponse(engine, "=800000000");
    assertEquals(1, ready.get());
    engine.endExclusiveGtpSession();
  }

  @Test
  void exclusiveSessionUsesSameTransportNeutralEntryPointForJavaSsh() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, true);
    installOutput(engine);

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));
  }

  @Test
  void exclusiveSessionRejectsNonKatagoAndMissingCapabilities() throws Exception {
    Leelaz nonKatago = reusableKatagoEngine(false, false);
    nonKatago.isKatago = false;
    installOutput(nonKatago);
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.NOT_KATAGO,
        nonKatago.previewExclusiveGtpLeaseAvailability());

    Leelaz missingCapability = reusableKatagoEngine(false, false);
    missingCapability.commandLists.remove("kata-analyze");
    installOutput(missingCapability);
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.MISSING_CAPABILITY,
        missingCapability.previewExclusiveGtpLeaseAvailability());

    Leelaz missingSetPosition = reusableKatagoEngine(false, false);
    missingSetPosition.commandLists.remove("set_position");
    installOutput(missingSetPosition);
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.MISSING_CAPABILITY,
        missingSetPosition.previewExclusiveGtpLeaseAvailability());

    Leelaz missingRulesQuery = reusableKatagoEngine(false, false);
    missingRulesQuery.commandLists.remove("kata-get-rules");
    installOutput(missingRulesQuery);
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.MISSING_CAPABILITY,
        missingRulesQuery.previewExclusiveGtpLeaseAvailability());
  }

  @Test
  void exclusiveSessionWaitsForCapabilityDiscovery() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    setCapabilityDiscoveryComplete(engine, false);
    installOutput(engine);

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY,
        engine.previewExclusiveGtpLeaseAvailability());
  }

  @Test
  void lifecycleTransitionAndLeaseAcquisitionShareOneAtomicGate() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);

    assertTrue(engine.beginExclusiveGtpLifecycleTransition());
    assertTrue(
        engine.beginExclusiveGtpLifecycleTransition(),
        "nested mode transitions on the same UI thread must remain atomic and usable.");
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE,
        engine.previewExclusiveGtpLeaseAvailability());
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE,
        engine.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));
    engine.endExclusiveGtpLifecycleTransition();
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE,
        engine.previewExclusiveGtpLeaseAvailability());
    engine.endExclusiveGtpLifecycleTransition();

    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));
    assertFalse(engine.beginExclusiveGtpLifecycleTransition());
    engine.endExclusiveGtpSession();
  }

  @Test
  void explicitLifecycleReservationCanBeReleasedBySynchronizationThread() throws Exception {
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);

    Leelaz.ExclusiveGtpLifecycleReservation reservation =
        engine.beginExclusiveGtpLifecycleReservation();
    assertTrue(reservation != null);
    assertTrue(engine.hasExclusiveGtpWorkInProgress());

    Thread synchronizationThread = new Thread(reservation::close);
    synchronizationThread.start();
    synchronizationThread.join();

    assertFalse(engine.hasExclusiveGtpWorkInProgress());
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
        engine.previewExclusiveGtpLeaseAvailability());
  }

  @Test
  void foregroundLeaseCanOnlyBeReleasedByItsAcquisitionOwner() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    boolean previousEngineGame = EngineManager.isEngineGame;
    boolean previousPreEngineGame = EngineManager.isPreEngineGame;
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    Object owner = new Object();
    AtomicInteger restoreCompletions = new AtomicInteger();
    try {
      Lizzie.leelaz = engine;
      Lizzie.frame = null;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(owner, line -> {}, () -> {}, () -> {}));
      processCommandResponse(engine, "=800000000");
      assertTrue(dispatch(engine, ""));

      engine.endForegroundAnalysisLease(new Object());
      assertTrue(engine.hasExclusiveGtpLeaseOwnedBy(owner));

      Lizzie.leelaz = null;
      assertTrue(engine.endForegroundAnalysisLease(owner, restoreCompletions::incrementAndGet));
      assertTrue(dispatch(engine, "=800000001"));
      assertTrue(dispatch(engine, ""));
      assertFalse(engine.hasExclusiveGtpLease());
      assertEquals(1, restoreCompletions.get());
      assertFalse(engine.endForegroundAnalysisLease(owner, restoreCompletions::incrementAndGet));
      assertEquals(1, restoreCompletions.get());
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.isPreEngineGame = previousPreEngineGame;
    }
  }

  @Test
  void foregroundLeaseRestoresLatestBoardAndPreviousPonderingOnlyAfterRestoreResponse()
      throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Config previousConfig = Lizzie.config;
    GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
    boolean previousEngineGame = EngineManager.isEngineGame;
    boolean previousPreEngineGame = EngineManager.isPreEngineGame;
    RecordingRestoreLeelaz engine = recordingRestoreEngine();
    installOutput(engine);
    RecordingRestoreBoard board = allocate(RecordingRestoreBoard.class);
    Object owner = new Object();
    AtomicInteger completions = new AtomicInteger();
    try {
      Lizzie.leelaz = engine;
      Lizzie.board = board;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      board.currentMarker = 1;
      engine.Pondering();
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(owner, line -> {}, () -> {}, () -> {}));
      processCommandResponse(engine, "=800000000");
      assertTrue(dispatch(engine, ""));

      board.currentMarker = 2;
      assertTrue(engine.endForegroundAnalysisLease(owner, completions::incrementAndGet));
      assertTrue(dispatch(engine, "=800000001"));
      assertTrue(dispatch(engine, ""));
      waitUntil(() -> board.resendCount == 1);

      assertEquals(2, board.restoredMarker);
      assertEquals(0, engine.ponderCount);
      assertEquals(0, completions.get());

      completeForegroundRestore(engine);
      waitUntil(() -> completions.get() == 1);

      assertEquals(1, engine.ponderCount);
      assertTrue(engine.lifecycleBusyDuringPonder);
      assertEquals(1, completions.get());
    } finally {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.isPreEngineGame = previousPreEngineGame;
    }
  }

  @Test
  void foregroundRestoreRepeatsWhenSuppressedBoardCommandInvalidatesFirstAttempt()
      throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    try {
      harness.board.currentMarker = 1;
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet));
      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> harness.board.resendCount == 1);

      harness.board.currentMarker = 2;
      harness.engine.sendCommand("play B D4");
      completeForegroundRestore(harness.engine);
      waitUntil(() -> harness.board.resendCount == 2);

      assertEquals(2, harness.board.restoredMarker);
      assertEquals(0, harness.completions.get());
      completeForegroundRestore(harness.engine);
      waitUntil(() -> harness.completions.get() == 1);
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseReleaseStopsActiveAnalysisBeforeRestoringBoard() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    try {
      harness.output.reset();
      assertTrue(harness.engine.sendExclusiveGtpCommand("kata-analyze B 50"));
      harness.output.reset();

      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet));

      assertEquals("800000001 stop\n", harness.output.toString(StandardCharsets.UTF_8));
      assertEquals(0, harness.board.resendCount);
      assertEquals(0, harness.completions.get());

      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> harness.board.resendCount == 1);
      completeForegroundRestore(harness.engine);
      waitUntil(() -> harness.completions.get() == 1);
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseRestoresCapturedRulesBeforeRestoringBoard() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    String originalRules =
        "{\"koRule\":\"POSITIONAL\",\"scoringRule\":\"AREA\",\"taxRule\":\"NONE\"}";
    try {
      assertTrue(
          harness.engine.setForegroundAnalysisLeaseRestoreRules(harness.owner, originalRules));
      harness.output.reset();

      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet));
      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> harness.board.resendCount == 1);

      assertTrue(
          harness
              .output
              .toString(StandardCharsets.UTF_8)
              .contains("\nkomi 7.5\nkata-set-rules " + originalRules + "\n"));
      completeForegroundRestore(harness.engine);
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseDropsNormalCommandsInsteadOfReplayingThemAfterRestore() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    try {
      harness.output.reset();
      harness.engine.sendCommand("play B D4");

      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet));
      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> harness.board.resendCount == 1);
      completeForegroundRestore(harness.engine);

      assertFalse(harness.output.toString(StandardCharsets.UTF_8).contains("play B D4"));
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseRejectsKomiAndBoardSizeBeforeMutatingApplicationState() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    try {
      float originalKomi = harness.engine.komi;
      int originalWidth = harness.engine.width;
      int originalHeight = harness.engine.height;
      harness.output.reset();

      harness.engine.komi(5.5);
      harness.engine.boardSize(13, 13);

      assertEquals(originalKomi, harness.engine.komi);
      assertEquals(originalWidth, harness.engine.width);
      assertEquals(originalHeight, harness.engine.height);
      assertEquals("", harness.output.toString(StandardCharsets.UTF_8));
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseReleaseTimeoutRestoresBoardBeforeReportingFailure()
      throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    AtomicInteger failures = new AtomicInteger();
    try {
      harness.engine.releaseStopTimeoutMillis = 25L;

      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));
      waitUntil(() -> harness.board.resendCount == 1);
      completeForegroundRestore(harness.engine);
      waitUntil(() -> failures.get() == 1);

      assertTrue(harness.engine.isLoaded());
      assertEquals(1, harness.board.resendCount);
      assertEquals(0, harness.completions.get());
      assertEquals(1, failures.get());
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseReleaseStopErrorRestoresBoardBeforeReportingFailure() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    AtomicInteger failures = new AtomicInteger();
    try {
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));
      assertTrue(dispatch(harness.engine, "?800000001 cannot stop"));
      waitUntil(() -> harness.board.resendCount == 1);
      completeForegroundRestore(harness.engine);
      waitUntil(() -> failures.get() == 1);

      assertTrue(harness.engine.isLoaded());
      assertEquals(1, harness.board.resendCount);
      assertEquals(0, harness.completions.get());
      assertEquals(1, failures.get());
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundRestoreFailureDoesNotRunSuccessCompletion() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    try {
      harness.board.failResend = true;
      Lizzie.frame = null;

      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet));
      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> !harness.engine.isLoaded());

      assertEquals(0, harness.completions.get());
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundRestoreWriteFailureRunsFailureCleanupInsteadOfSuccess() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    AtomicInteger failures = new AtomicInteger();
    try {
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet, failures::incrementAndGet));
      installFailOnceOutput(harness.engine);
      Lizzie.frame = null;

      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));
      waitUntil(() -> harness.board.resendCount == 1 || failures.get() == 1);
      if (failures.get() == 0) {
        completeForegroundRestore(harness.engine);
      }
      waitUntil(() -> failures.get() == 1 || harness.completions.get() == 1);

      assertFalse(harness.engine.isLoaded());
      assertEquals(0, harness.completions.get());
      assertFalse(harness.engine.hasExclusiveGtpWorkInProgress());
    } finally {
      harness.close();
    }
  }

  @Test
  void exclusiveLineConsumerFailureCannotEscapeReadCleanup() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz engine = reusableKatagoEngine(false, false);
    installOutput(engine);
    try {
      Lizzie.leelaz = engine;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(
              new Object(),
              line -> {
                throw new IllegalStateException("simulated parser failure");
              },
              () -> {},
              () -> {}));
      processCommandResponse(engine, "=800000000");
      assertTrue(dispatch(engine, ""));
      installInput(engine, "info move D4 visits 1\n");
      Lizzie.leelaz = null;
      engine.isNormalEnd = true;

      assertDoesNotThrow(() -> invokeRead(engine));

      assertFalse(engine.hasExclusiveGtpLease());
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
      assertFalse(engine.isStarted());
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void foregroundLeaseDoesNotResumePonderWhenItWasNotPreviouslyPondering() throws Exception {
    RestoreHarness harness = RestoreHarness.open(false, false);
    try {
      harness.finishRestore();

      assertEquals(1, harness.board.resendCount);
      assertEquals(0, harness.engine.ponderCount);
      assertEquals(1, harness.completions.get());
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseDoesNotRestoreOrPonderAfterForegroundEngineSwitch() throws Exception {
    RestoreHarness harness = RestoreHarness.open(true, false);
    try {
      Lizzie.leelaz = new Leelaz("");
      assertTrue(
          harness.engine.endForegroundAnalysisLease(
              harness.owner, harness.completions::incrementAndGet));
      assertTrue(dispatch(harness.engine, "=800000001"));
      assertTrue(dispatch(harness.engine, ""));

      assertEquals(0, harness.board.resendCount);
      assertEquals(0, harness.engine.ponderCount);
      assertEquals(1, harness.completions.get());
    } finally {
      harness.close();
    }
  }

  @Test
  void foregroundLeaseRestoresBoardButDoesNotResumePonderAfterEnteringPlayMode() throws Exception {
    RestoreHarness harness = RestoreHarness.open(true, true);
    try {
      harness.finishRestore();

      assertEquals(1, harness.board.resendCount);
      assertEquals(0, harness.engine.ponderCount);
      assertEquals(1, harness.completions.get());
    } finally {
      harness.close();
    }
  }

  private static Leelaz reusableKatagoEngine(boolean remoteCompute, boolean javaSsh)
      throws Exception {
    Leelaz engine = new Leelaz("");
    engine.useRemoteCompute = remoteCompute;
    engine.useJavaSSH = javaSsh;
    engine.isLoaded = true;
    engine.started = true;
    engine.isKatago = true;
    engine.commandLists.addAll(
        List.of(
            "stop",
            "boardsize",
            "komi",
            "kata-get-rules",
            "kata-set-rules",
            "clear_board",
            "play",
            "set_position",
            "kata-analyze"));
    setCapabilityDiscoveryComplete(engine, true);
    return engine;
  }

  private static RecordingRestoreLeelaz recordingRestoreEngine() throws Exception {
    RecordingRestoreLeelaz engine = new RecordingRestoreLeelaz();
    engine.isLoaded = true;
    engine.started = true;
    engine.isKatago = true;
    engine.commandLists.addAll(
        List.of(
            "stop",
            "boardsize",
            "komi",
            "kata-get-rules",
            "kata-set-rules",
            "clear_board",
            "play",
            "set_position",
            "kata-analyze"));
    setCapabilityDiscoveryComplete(engine, true);
    return engine;
  }

  private static void setCapabilityDiscoveryComplete(Leelaz engine, boolean complete)
      throws Exception {
    Field field = Leelaz.class.getDeclaredField("endGetCommandList");
    field.setAccessible(true);
    field.set(engine, complete);
  }

  private static ByteArrayOutputStream installOutput(Leelaz engine) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Field field = Leelaz.class.getDeclaredField("outputStream");
    field.setAccessible(true);
    field.set(engine, new BufferedOutputStream(bytes));
    return bytes;
  }

  private static void installFailOnceOutput(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("outputStream");
    field.setAccessible(true);
    field.set(
        engine,
        Leelaz.createCommandOutputStream(
            new OutputStream() {
              private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
              private boolean failed;

              @Override
              public void write(int value) throws IOException {
                if (!failed) {
                  failed = true;
                  throw new IOException("simulated restore write failure");
                }
                bytes.write(value);
              }

              @Override
              public void write(byte[] buffer, int offset, int length) throws IOException {
                if (!failed) {
                  failed = true;
                  throw new IOException("simulated restore write failure");
                }
                bytes.write(buffer, offset, length);
              }
            }));
  }

  private static void installInput(Leelaz engine, String input) throws Exception {
    Field field = Leelaz.class.getDeclaredField("inputStream");
    field.setAccessible(true);
    field.set(engine, new BufferedReader(new StringReader(input)));
  }

  private static void invokeRead(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("read");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static boolean dispatch(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("dispatchExclusiveGtpLine", String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(engine, line);
  }

  private static void processCommandResponse(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("processCommandResponseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void completeForegroundRestore(Leelaz engine) throws Exception {
    Field sessionField = Leelaz.class.getDeclaredField("foregroundRestoreSession");
    sessionField.setAccessible(true);
    Object session = sessionField.get(engine);
    Method method = Leelaz.class.getDeclaredMethod("completeForegroundRestore", session.getClass());
    method.setAccessible(true);
    method.invoke(engine, session);
  }

  private static void waitUntil(Check condition) throws Exception {
    long deadline = System.currentTimeMillis() + 3000L;
    while (!condition.get() && System.currentTimeMillis() < deadline) {
      Thread.sleep(10L);
    }
    assertTrue(condition.get(), "timed out waiting for foreground restore lifecycle");
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  @FunctionalInterface
  private interface Check {
    boolean get() throws Exception;
  }

  private static final class RecordingRestoreBoard extends Board {
    private volatile int currentMarker;
    private volatile int restoredMarker;
    private volatile int resendCount;
    private volatile boolean failResend;

    private RecordingRestoreBoard() {
      super();
    }

    @Override
    public void resendMoveToEngine(Leelaz leelaz, boolean loadEngine) {
      if (failResend) {
        throw new IllegalStateException("simulated restore failure");
      }
      restoredMarker = currentMarker;
      resendCount++;
    }
  }

  private static final class RecordingRestoreLeelaz extends Leelaz {
    private volatile int ponderCount;
    private volatile boolean lifecycleBusyDuringPonder;
    private volatile long releaseStopTimeoutMillis = 8000L;

    private RecordingRestoreLeelaz() throws Exception {
      super("");
    }

    @Override
    public void ponder() {
      ponderCount++;
      lifecycleBusyDuringPonder = hasExclusiveGtpWorkInProgress();
      Pondering();
    }

    @Override
    protected long foregroundReleaseStopTimeoutMillis() {
      return releaseStopTimeoutMillis;
    }
  }

  private static final class RestoreHarness implements AutoCloseable {
    private final Leelaz previousEngine;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Config previousConfig;
    private final GtpConsolePane previousGtpConsole;
    private final boolean previousEngineGame;
    private final boolean previousPreEngineGame;
    private final RecordingRestoreLeelaz engine;
    private final RecordingRestoreBoard board;
    private final ByteArrayOutputStream output;
    private final Object owner;
    private final AtomicInteger completions = new AtomicInteger();

    private RestoreHarness(
        Leelaz previousEngine,
        Board previousBoard,
        LizzieFrame previousFrame,
        Config previousConfig,
        GtpConsolePane previousGtpConsole,
        boolean previousEngineGame,
        boolean previousPreEngineGame,
        RecordingRestoreLeelaz engine,
        RecordingRestoreBoard board,
        ByteArrayOutputStream output,
        Object owner) {
      this.previousEngine = previousEngine;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousConfig = previousConfig;
      this.previousGtpConsole = previousGtpConsole;
      this.previousEngineGame = previousEngineGame;
      this.previousPreEngineGame = previousPreEngineGame;
      this.engine = engine;
      this.board = board;
      this.output = output;
      this.owner = owner;
    }

    private static RestoreHarness open(boolean wasPondering, boolean enterPlayMode)
        throws Exception {
      Leelaz previousEngine = Lizzie.leelaz;
      Board previousBoard = Lizzie.board;
      LizzieFrame previousFrame = Lizzie.frame;
      Config previousConfig = Lizzie.config;
      GtpConsolePane previousGtpConsole = Lizzie.gtpConsole;
      boolean previousEngineGame = EngineManager.isEngineGame;
      boolean previousPreEngineGame = EngineManager.isPreEngineGame;
      RecordingRestoreLeelaz engine = recordingRestoreEngine();
      RecordingRestoreBoard board = allocate(RecordingRestoreBoard.class);
      ByteArrayOutputStream output = installOutput(engine);
      Lizzie.leelaz = engine;
      Lizzie.board = board;
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.config = allocate(Config.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      if (wasPondering) {
        engine.Pondering();
      }
      Object owner = new Object();
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          engine.beginForegroundAnalysisLease(owner, line -> {}, () -> {}, () -> {}));
      processCommandResponse(engine, "=800000000");
      assertTrue(dispatch(engine, ""));
      if (enterPlayMode) {
        Lizzie.frame.isPlayingAgainstLeelaz = true;
      }
      return new RestoreHarness(
          previousEngine,
          previousBoard,
          previousFrame,
          previousConfig,
          previousGtpConsole,
          previousEngineGame,
          previousPreEngineGame,
          engine,
          board,
          output,
          owner);
    }

    private void finishRestore() throws Exception {
      assertTrue(engine.endForegroundAnalysisLease(owner, completions::incrementAndGet));
      assertTrue(dispatch(engine, "=800000001"));
      assertTrue(dispatch(engine, ""));
      waitUntil(() -> board.resendCount == 1);
      completeForegroundRestore(engine);
      waitUntil(() -> completions.get() == 1);
    }

    @Override
    public void close() {
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.config = previousConfig;
      Lizzie.gtpConsole = previousGtpConsole;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.isPreEngineGame = previousPreEngineGame;
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
  }
}
