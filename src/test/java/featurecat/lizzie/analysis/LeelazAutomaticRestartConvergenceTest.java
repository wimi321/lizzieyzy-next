package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.EngineStartupStatus;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.web.WebBoardManager;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import featurecat.lizzie.util.KataGoRuntimeHelper;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Production-entry regression coverage for the automatic restart restore convergence (Issue #223
 * ticket 04): local restart, remote restart and bundled OpenCL recovery all run the frozen restore
 * route first and then keep catching up to the latest navigated Board frame before readiness, the
 * board fence and the captured ponder disposition complete.
 */
class LeelazAutomaticRestartConvergenceTest {

  @Test
  void localAutomaticRestartConvergesWithNavigationDuringRestore() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      ConvergingRestartLeelaz mirror = new ConvergingRestartLeelaz();
      engine.blockFirstLoadSgf = true;
      BoardHistoryList history = snapshotHistoryWithTail(false);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      Lizzie.leelaz2 = mirror;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      mirror.started = true;
      mirror.isLoaded = true;
      CountDownLatch firstLoadSgfReceived = new CountDownLatch(1);
      engine.beforeCommand =
          command -> {
            if (command.startsWith("loadsgf ") && engine.loadSgfCount.get() == 0) {
              firstLoadSgfReceived.countDown();
            }
          };

      engine.started = true;
      engine.isLoaded = true;
      engine.canCheckAlive = true;
      engine.processDead = true;
      engine.Pondering();
      EngineManager manager = new EngineManager(List.of(engine));
      invokeCheckEngineAlive(manager);

      assertTrue(
          firstLoadSgfReceived.await(2, TimeUnit.SECONDS),
          "the frozen restore route must reach the engine first");
      engine.sendCommand("play B Q4");
      assertFalse(
          engine.transport.commands().contains("play B Q4"),
          "ordinary live-board updates must stay fenced during the restore convergence");
      assertTrue(
          board.previousMove(false),
          "navigation must stay available while the frozen restore is in flight");

      invokeLoadSgfResponse(engine);

      assertTrue(
          waitForLoadSgfCount(engine, 2, 2, TimeUnit.SECONDS),
          "frozen route plus one catch-up route must both execute");
      assertTrue(
          waitForLoadSgfCount(mirror, 2, 2, TimeUnit.SECONDS),
          "the captured mirror must execute the frozen and catch-up routes");
      assertTrue(
          waitForRawCommandPrefix(engine.transport, "name", 2, TimeUnit.SECONDS),
          "the board fence must follow the stable convergence");
      assertTrue(
          waitForRawCommandPrefix(mirror.transport, "name", 2, TimeUnit.SECONDS),
          "the captured mirror must receive the final board fence");
      assertEquals(0, engine.analyzeCount.get(), "no analysis before both board fences");
      assertEquals(0, engine.resumeCount.get(), "no ponder resume before both board fences");
      assertEquals(0, engine.readyCount.get(), "ready must wait for both board fences");
      assertFalse(
          engine.hasExclusiveGtpLifecycleTransitionForTest(),
          "the round reservation must be released before the stable frame recheck");
      assertNull(
          engine.beginEngineModeReservation(),
          "the completion gate must reject unrelated engine-mode owners while the fences are"
              + " pending");
      engine.sendCommand("play W D4");
      assertFalse(
          engine.transport.commands().contains("play W D4"),
          "ordinary live-board commands must stay fenced through final fence settlement");

      invokeFenceResponse(engine);

      assertEquals(
          0,
          engine.readyCount.get(),
          "the authority fence alone must not complete while the captured mirror fence is pending");
      invokeFenceResponse(mirror);

      assertTrue(
          waitForCount(engine.readyCount, 1, 1, TimeUnit.SECONDS),
          "the production restart callback must publish ready after both fences");
      assertEquals(1, engine.resumeCount.get(), "ponder must resume exactly once");
      assertEquals(
          1, engine.analyzeCount.get(), "one analysis starts after the stable convergence");
      assertTrue(
          engine.transport.commands().indexOf("kata-analyze 10")
              > engine.transport.commands().lastIndexOf("name"),
          "analysis must start only after the fence");
      assertEquals(2, engine.loadSgfCount.get(), "frozen route plus one catch-up route");
      assertTrue(engine.isLoaded(), "the converged engine must stay available");
      assertFalse(engine.hasUnrestoredReadBoardGmaState());
      assertEngineMatchesBoard(engine, board, 19, 19);
      assertEngineMatchesBoard(mirror, board, 19, 19);
      Leelaz.EngineModeReservation afterFence = engine.beginEngineModeReservation();
      assertNotNull(afterFence, "the reservation must be released after the fence");
      afterFence.close();
    }
  }

  @Test
  void remoteAutomaticRestartConvergesWithNavigationDuringDelayedReadiness() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      engine.useRemoteCompute = true;
      engine.delayReadyAfterStart = true;
      BoardHistoryList history = snapshotHistoryWithTail(false);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      engine.started = true;
      engine.isLoaded = true;

      engine.processDead = true;
      engine.Pondering();
      EngineManager manager = new EngineManager(List.of(engine));
      manager.restartUnresponsiveRemoteEngine(engine, 0);

      assertTrue(
          engine.startCompleted.await(2, TimeUnit.SECONDS),
          "the remote engine restart must reach the startup phase");
      assertTrue(
          board.previousMove(false), "navigation during delayed readiness must stay available");
      engine.publishReady();

      assertTrue(
          waitForLoadSgfCount(engine, 2, 2, TimeUnit.SECONDS),
          "the stale frozen route must be followed by one catch-up route");
      assertTrue(
          waitForRawCommandPrefix(engine.transport, "name", 2, TimeUnit.SECONDS),
          "the board fence must follow the remote convergence");
      assertEquals(0, engine.analyzeCount.get(), "no analysis before the remote board fence");
      assertEquals(0, engine.readyCount.get(), "ready must wait for the remote board fence");
      assertFalse(
          engine.hasExclusiveGtpLifecycleTransitionForTest(),
          "the remote round reservation must be released before the stable frame recheck");
      assertNull(
          engine.beginEngineModeReservation(),
          "the completion gate must reject unrelated engine-mode owners while the remote fence is"
              + " pending");

      history.getCurrentHistoryNode().clearAndSyncBoard(true);
      assertEquals(
          0,
          engine.analyzeCount.get(),
          "SNAPSHOT navigation must not start analysis before the final fence");

      invokeFenceResponse(engine);

      assertTrue(
          waitForCount(engine.readyCount, 1, 1, TimeUnit.SECONDS),
          "the production remote restart must publish ready after the fence");
      assertEquals(1, engine.resumeCount.get(), "remote ponder must resume exactly once");
      assertEquals(1, engine.analyzeCount.get(), "remote analysis starts after convergence");
      assertTrue(engine.isLoaded());
      assertEngineMatchesBoard(engine, board, 19, 19);
      Leelaz.EngineModeReservation afterFence = engine.beginEngineModeReservation();

      assertNotNull(afterFence, "the remote reservation must be released after the fence");
      afterFence.close();
    }
  }

  @Test
  void snapshotTailNavigationCompletesBeforeLifecycleAnalysisAfterFence() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      engine.useRemoteCompute = true;
      engine.delayReadyAfterStart = true;
      BoardHistoryList history = snapshotHistoryWithTail(false);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      engine.started = true;
      engine.isLoaded = true;
      engine.processDead = true;
      engine.Pondering();

      EngineManager manager = new EngineManager(List.of(engine));
      manager.restartUnresponsiveRemoteEngine(engine, 0);
      assertTrue(engine.startCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(board.previousMove(false));
      engine.publishReady();
      assertTrue(waitForLoadSgfCount(engine, 2, 2, TimeUnit.SECONDS));
      assertTrue(waitForRawCommandPrefix(engine.transport, "name", 2, TimeUnit.SECONDS));

      engine.blockThirdLoadSgf = true;
      assertTrue(board.nextMove(false), "snapshot tail navigation must succeed");
      Thread restore =
          new Thread(
              () -> history.getCurrentHistoryNode().clearAndSyncBoard(true),
              "snapshot-tail-navigation-restore");
      restore.start();
      assertTrue(waitForLoadSgfCount(engine, 3, 2, TimeUnit.SECONDS));

      invokeFenceResponse(engine);
      assertEquals(0, engine.analyzeCount.get(), "fence success must wait for the tail restore");

      invokeLoadSgfResponse(engine);
      restore.join(2_000L);
      assertFalse(restore.isAlive(), "snapshot tail restore must finish");
      assertTrue(waitForCount(engine.analyzeCount, 1, 2, TimeUnit.SECONDS));
    }
  }
  @Test
  void automaticRestartPublishesReadyWithoutResumingPonder() throws Exception {
    boolean previousEngineGame = EngineManager.isEngineGame;
    Menu previousMenu = LizzieFrame.menu;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      EngineManager.isEngineGame = false;
      LizzieFrame.menu = allocate(SilentPonderMenu.class);
      LizzieFrame.toolbar = allocate(SilentPonderToolbar.class);
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      engine.productionReadyHandoff = true;
      Board board = boardWithHistory(snapshotHistoryWithTail(false));
      env.publish(engine, board);
      engine.notPondering();
      Lizzie.engineStartupStatus.checking("engine.starting", "controlled restart ready");

      Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(attempt);
      CountDownLatch completed = new CountDownLatch(1);
      attempt.restartClosedEngine(0, completed::countDown);

      assertTrue(waitForRawCommandPrefix(engine.transport, "name", 2, TimeUnit.SECONDS));
      assertEquals(0, engine.readyCount.get(), "ready must wait for the board fence");
      assertNull(
          engine.beginEngineModeReservation(),
          "the completion gate must reject unrelated owners until the fence settles");
      invokeFenceResponse(engine);
      SwingUtilities.invokeAndWait(() -> {});

      assertTrue(completed.await(1, TimeUnit.SECONDS));
      assertEquals(1, engine.readyCount.get(), "the real production handoff must publish ready");
      assertEquals(
          EngineStartupStatus.State.READY,
          Lizzie.engineStartupStatus.snapshot().state,
          "markEngineReady must publish the real startup status");
      assertEquals(0, engine.resumeCount.get(), "no ponder resume without ponder intent");
      assertEquals(0, engine.analyzeCount.get(), "no analysis without ponder intent");
      assertTrue(engine.isLoaded());
      assertEngineMatchesBoard(engine, board, 19, 19);
      Leelaz.EngineModeReservation afterFence = engine.beginEngineModeReservation();
      assertNotNull(afterFence, "unrelated owners work again after the completion gate clears");
      afterFence.close();
    } finally {
      SwingUtilities.invokeAndWait(() -> {});
      EngineManager.isEngineGame = previousEngineGame;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.toolbar = previousToolbar;
      Lizzie.engineStartupStatus.ready();
    }
  }

  @Test
  void completionObserverFailureCannotRetireSuccessfulRestart() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      Board board = boardWithHistory(snapshotHistoryWithTail(false));
      env.publish(engine, board);

      Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(attempt);
      AtomicInteger completionCount = new AtomicInteger();
      attempt.restartClosedEngine(
          0,
          () -> {
            completionCount.incrementAndGet();
            throw new IllegalStateException("controlled completion observer failure");
          });

      assertTrue(waitForRawCommandPrefix(engine.transport, "name", 5, TimeUnit.SECONDS));
      assertDoesNotThrow(() -> invokeFenceResponse(engine));
      assertEquals(1, completionCount.get(), "the failing observer must remain one-shot");
      assertTrue(engine.isLoaded(), "an observer failure must not retire a ready engine");
      assertTrue(
          engineModeAdmissionOpen(engine),
          "an observer failure must not re-block engine-mode admission");
      assertAutomaticRestartRetryAvailable(engine);
    }
  }

  @Test
  void benchmarkSuppressedRestartCompletionObservesReleasedClaimWithoutFence() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      try {
        ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
        Board board = boardWithHistory(snapshotHistoryWithTail(false));
        env.publish(engine, board);
        KataGoRuntimeHelper.BenchmarkPauseResult pause =
            KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();
        assertTrue(pause.accepted(), "the controlled benchmark pause must be admitted");

        Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
        assertNotNull(attempt);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger completionCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        AtomicBoolean admissionOpenInCompletion = new AtomicBoolean(false);
        attempt.restartClosedEngine(
            0,
            () -> {
              completionCount.incrementAndGet();
              admissionOpenInCompletion.set(engineModeAdmissionOpen(engine));
              completed.countDown();
            },
            detail -> failureCount.incrementAndGet());

        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals(1, completionCount.get(), "the no-fence completion must remain one-shot");
        assertEquals(0, failureCount.get(), "the no-fence restart must complete successfully");
        assertTrue(engine.isLoaded(), "the no-fence restart must leave the engine loaded");
        assertTrue(
            admissionOpenInCompletion.get(),
            "the no-fence completion must observe endpoint admission reopened");
        assertFalse(
            waitForNumberedRawCommandPrefix(engine.transport, "name", 200, TimeUnit.MILLISECONDS),
            "benchmark suppression must bypass the final board fence");
        assertEquals(
            0, engine.readyCount.get(), "the no-fence branch must not publish fence ready");
      } finally {
        if (KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed()) {
          KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
        }
      }
    }
  }

  @Test
  void openClRecoveryConvergesWithNavigationDuringRestore() throws Exception {
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    GtpConsolePane previousConsole = Lizzie.gtpConsole;
    String previousOsName = System.getProperty("os.name");
    String previousDriver = System.getProperty("lizzie.opencl.nvidiaDriverVersion");
    Path tempRoot = Files.createTempDirectory("leelaz-opencl-navigation");
    try {
      System.setProperty("os.name", "Windows 11");
      System.setProperty("lizzie.opencl.nvidiaDriverVersion", "566.36");
      Lizzie.config = ConfigTestHelper.createForTests(tempRoot.resolve("runtime-root"));
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      engine.blockFirstLoadSgf = true;
      BoardHistoryList history = snapshotHistoryWithTail(false);
      Board board = boardWithHistory(history);
      Lizzie.board = board;
      Lizzie.leelaz = engine;
      Lizzie.frame = allocate(SilentRestartFrame.class);
      CountDownLatch firstLoadSgfReceived = new CountDownLatch(1);
      engine.beforeCommand =
          command -> {
            if (command.startsWith("loadsgf ") && engine.loadSgfCount.get() == 0) {
              firstLoadSgfReceived.countDown();
            }
          };
      Path enginePath = createOpenClEngine(tempRoot);
      Path modelPath = touch(tempRoot.resolve("weights/current.bin.gz"));
      ExitedProcess process = new ExitedProcess((int) 0xC0000409L);
      setField(engine, "process", process);
      setField(
          engine,
          "inputStream",
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)));
      setField(
          engine,
          "commands",
          List.of(enginePath.toString(), "gtp", "-model", modelPath.toString()));
      engine.started = true;
      engine.isLoaded = true;
      engine.Pondering();

      assertTrue(invokeOpenClRecovery(engine));
      assertTrue(
          firstLoadSgfReceived.await(2, TimeUnit.SECONDS),
          "the frozen OpenCL recovery route must reach the engine first");
      assertTrue(
          board.previousMove(false), "navigation during OpenCL recovery must stay available");
      invokeLoadSgfResponse(engine);

      assertTrue(
          waitForLoadSgfCount(engine, 2, 2, TimeUnit.SECONDS),
          "frozen route plus one catch-up route must both execute");
      assertTrue(
          waitForRawCommandPrefix(engine.transport, "name", 2, TimeUnit.SECONDS),
          "the board fence must follow the OpenCL convergence");
      assertEquals(0, engine.analyzeCount.get(), "no analysis before the OpenCL board fence");
      assertEquals(0, engine.readyCount.get(), "ready must wait for the OpenCL board fence");
      assertFalse(
          engine.hasExclusiveGtpLifecycleTransitionForTest(),
          "the OpenCL round reservation must be released before the stable frame recheck");
      assertNull(
          engine.beginEngineModeReservation(),
          "the completion gate must reject unrelated engine-mode owners while the OpenCL fence is"
              + " pending");

      invokeFenceResponse(engine);

      assertTrue(waitForCount(engine.readyCount, 1, 1, TimeUnit.SECONDS));
      assertTrue(engine.isLoaded(), "the recovered engine must stay available");
      assertEngineMatchesBoard(engine, board, 19, 19);
      Leelaz.EngineModeReservation afterFence = engine.beginEngineModeReservation();
      assertNotNull(afterFence, "the OpenCL reservation must be released after the fence");
      afterFence.close();
    } finally {
      restoreProperty("os.name", previousOsName);
      restoreProperty("lizzie.opencl.nvidiaDriverVersion", previousDriver);
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousConsole;
    }
  }

  @Test
  void catchUpRestoreFailureFailsClosedWithoutAnalysis() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      ConvergingRestartLeelaz mirror = new ConvergingRestartLeelaz();
      engine.blockFirstLoadSgf = true;
      engine.failLoadSgfAt = 2;
      BoardHistoryList history = snapshotHistoryWithTail(false);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      Lizzie.leelaz2 = mirror;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      mirror.started = true;
      mirror.isLoaded = true;
      CountDownLatch firstLoadSgfReceived = new CountDownLatch(1);
      engine.beforeCommand =
          command -> {
            if (command.startsWith("loadsgf ") && engine.loadSgfCount.get() == 0) {
              firstLoadSgfReceived.countDown();
            }
          };

      engine.Pondering();
      Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(attempt, "automatic restart reservation must be admitted");
      CountDownLatch completed = new CountDownLatch(1);
      attempt.restartClosedEngine(0, completed::countDown);

      assertTrue(
          firstLoadSgfReceived.await(2, TimeUnit.SECONDS),
          "the frozen restore route must reach the engine first");
      assertTrue(board.previousMove(false));
      invokeLoadSgfResponse(engine);

      assertTrue(
          completed.await(2, TimeUnit.SECONDS),
          "a failed catch-up must release the reservation and settle the callback");
      assertFalse(engine.isLoaded(), "the failed catch-up target must remain unavailable");
      assertFalse(mirror.isLoaded(), "the captured mirror must fail closed with the authority");
      assertEquals(0, engine.analyzeCount.get(), "no analyze after catch-up failure");
      assertEquals(0, engine.resumeCount.get(), "no ponder resume after catch-up failure");
      assertEquals(
          2,
          engine.loadSgfCount.get(),
          "the frozen route executes before the failed catch-up attempt");
      assertFalse(
          engine.hasExclusiveGtpWorkInProgress(),
          "the failed catch-up must release all reservations");
      Leelaz.EngineModeReservation afterFailure = engine.beginEngineModeReservation();
      assertNotNull(
          afterFailure,
          "a failed catch-up must clear the completion gate and release the lifecycle reservation");
      afterFailure.close();
      assertOrdinaryForwardingReopened(engine);
      assertOrdinaryForwardingReopened(mirror);
      assertAutomaticRestartRetryAvailable(engine);
    }
  }

  @Test
  void frozenRestoreFailureFailsClosedWithoutReadyOrPonder() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      engine.failLoadSgfAt = 1;
      BoardHistoryList history = snapshotHistoryWithTail(false);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      CountDownLatch firstLoadSgfReceived = new CountDownLatch(1);
      engine.beforeCommand =
          command -> {
            if (command.startsWith("loadsgf ") && engine.loadSgfCount.get() == 0) {
              firstLoadSgfReceived.countDown();
            }
          };

      engine.Pondering();
      Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(attempt, "automatic restart reservation must be admitted");
      CountDownLatch completed = new CountDownLatch(1);
      attempt.restartClosedEngine(0, completed::countDown);

      assertTrue(
          firstLoadSgfReceived.await(2, TimeUnit.SECONDS),
          "the frozen restore route must reach the engine first");
      assertTrue(
          completed.await(2, TimeUnit.SECONDS),
          "a frozen restore failure must settle the completion callback");
      assertEquals(
          1, engine.loadSgfCount.get(), "the frozen route fails before any catch-up route");
      assertFalse(engine.isLoaded(), "the failed frozen restore must leave the engine unavailable");
      assertEquals(0, engine.readyCount.get(), "no ready after a frozen restore failure");
      assertEquals(0, engine.resumeCount.get(), "no ponder resume after a frozen restore failure");
      assertEquals(0, engine.analyzeCount.get(), "no analysis after a frozen restore failure");
      assertFalse(
          engine.hasExclusiveGtpWorkInProgress(),
          "a frozen restore failure must release all reservations");
      Leelaz.EngineModeReservation afterFailure = engine.beginEngineModeReservation();
      assertNotNull(
          afterFailure,
          "a frozen restore failure must clear the completion claim and reopen admission");
      afterFailure.close();
      assertOrdinaryForwardingReopened(engine);
      assertAutomaticRestartRetryAvailable(engine);
    }
  }

  @Test
  void readinessFailureFailsCapturedMirrorClosedAndReleasesCompletionClaim() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      ConvergingRestartLeelaz mirror = new ConvergingRestartLeelaz();
      engine.delayReadyAfterStart = true;
      engine.shortStartupTimeout = true;
      Board board = boardWithHistory(snapshotHistoryWithTail(false));
      env.publish(engine, board);
      Lizzie.leelaz2 = mirror;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      mirror.started = true;
      mirror.isLoaded = true;

      Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(attempt);
      CountDownLatch completed = new CountDownLatch(1);
      attempt.restartClosedEngine(0, completed::countDown);

      assertTrue(completed.await(2, TimeUnit.SECONDS));
      assertFalse(engine.isLoaded(), "the authority must fail closed after readiness timeout");
      assertFalse(
          mirror.isLoaded(), "the captured mirror must fail closed after readiness timeout");
      Leelaz.EngineModeReservation afterFailure = engine.beginEngineModeReservation();
      assertNotNull(afterFailure, "readiness failure must release the completion claim");
      afterFailure.close();
      assertOrdinaryForwardingReopened(engine);
      assertOrdinaryForwardingReopened(mirror);
      assertAutomaticRestartRetryAvailable(engine);
    }
  }

  @Test
  void catchUpReservationReacquireRejectionFailsClosed() throws Exception {
    WebBoardManager previousWebBoardManager = Lizzie.webBoardManager;
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      ConvergingRestartLeelaz mirror = new ConvergingRestartLeelaz();
      engine.blockFirstLoadSgf = true;
      BoardHistoryList history = snapshotHistoryWithTail(false);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      Lizzie.leelaz2 = mirror;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      mirror.started = true;
      mirror.isLoaded = true;
      CountDownLatch firstLoadSgfReceived = new CountDownLatch(1);
      engine.beforeCommand =
          command -> {
            if (command.startsWith("loadsgf ") && engine.loadSgfCount.get() == 0) {
              firstLoadSgfReceived.countDown();
            }
          };

      engine.Pondering();
      Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(attempt);
      CountDownLatch completed = new CountDownLatch(1);
      attempt.restartClosedEngine(0, completed::countDown);

      assertTrue(
          firstLoadSgfReceived.await(2, TimeUnit.SECONDS),
          "the frozen restore route must reach the engine first");
      assertTrue(
          board.previousMove(false),
          "navigation must force a catch-up round so the round reservation is reacquired");
      // A trial-excluded web board makes the round reservation reacquire fail while leaving the
      // catch-up admission capture, the fence and the operation's own cleanup untouched.
      Lizzie.webBoardManager = allocate(BusyWebBoardManager.class);
      invokeLoadSgfResponse(engine);

      assertTrue(
          completed.await(2, TimeUnit.SECONDS),
          "a rejected round reservation reacquire must settle the completion callback");
      assertEquals(
          1,
          engine.loadSgfCount.get(),
          "only the frozen route executes before the rejected reacquire");
      assertFalse(engine.isLoaded(), "the rejected reacquire must fail closed");
      assertFalse(mirror.isLoaded(), "a rejected reacquire must fail the captured mirror closed");
      assertEquals(0, engine.readyCount.get(), "no ready after a rejected reacquire");
      assertEquals(0, engine.resumeCount.get(), "no ponder resume after a rejected reacquire");
      assertEquals(0, engine.analyzeCount.get(), "no analysis after a rejected reacquire");
      assertFalse(
          engine.hasExclusiveGtpWorkInProgress(),
          "a rejected reacquire must release all reservations and clear the claim");
      // The busy web board only blocks engine-mode admission while installed; with it removed the
      // rejected reacquire must leave the engine fully available.
      Lizzie.webBoardManager = previousWebBoardManager;
      Leelaz.EngineModeReservation afterFailure = engine.beginEngineModeReservation();
      assertNotNull(
          afterFailure,
          "a rejected reacquire must clear the completion claim and reopen admission");
      afterFailure.close();
      assertOrdinaryForwardingReopened(engine);
      assertOrdinaryForwardingReopened(mirror);
      assertAutomaticRestartRetryAvailable(engine);
    } finally {
      Lizzie.webBoardManager = previousWebBoardManager;
    }
  }

  @Test
  void automaticRestartConvergesAcrossMultipleRoundsWithNavigationDuringCatchUp() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      engine.blockFirstLoadSgf = true;
      engine.blockSecondLoadSgf = true;
      BoardHistoryList history = snapshotHistoryWithTwoMoves();
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      CountDownLatch firstLoadSgfReceived = new CountDownLatch(1);
      CountDownLatch secondLoadSgfReceived = new CountDownLatch(1);
      engine.beforeCommand =
          command -> {
            if (command.startsWith("loadsgf ")) {
              if (engine.loadSgfCount.get() == 0) {
                firstLoadSgfReceived.countDown();
              } else if (engine.loadSgfCount.get() == 1) {
                secondLoadSgfReceived.countDown();
              }
            }
          };

      engine.Pondering();
      Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(attempt);
      CountDownLatch completed = new CountDownLatch(1);
      attempt.restartClosedEngine(0, completed::countDown);

      assertTrue(
          firstLoadSgfReceived.await(2, TimeUnit.SECONDS),
          "the frozen route must reach the engine first");
      assertTrue(
          board.previousMove(false), "navigation during the frozen restore must stay available");
      invokeLoadSgfResponse(engine);

      assertTrue(
          secondLoadSgfReceived.await(2, TimeUnit.SECONDS),
          "the first catch-up route must reach the engine");
      assertTrue(
          board.previousMove(false), "navigation during the catch-up restore must stay available");
      invokeLoadSgfResponse(engine);

      assertTrue(
          waitForLoadSgfCount(engine, 3, 2, TimeUnit.SECONDS),
          "a third round must execute before the board fence");
      assertTrue(
          waitForRawCommandPrefix(engine.transport, "name", 2, TimeUnit.SECONDS),
          "the board fence must follow the third convergent round");
      assertEquals(0, engine.readyCount.get(), "ready must wait for the board fence");
      assertEquals(0, engine.analyzeCount.get(), "no analysis before the board fence");
      assertFalse(
          engine.hasExclusiveGtpLifecycleTransitionForTest(),
          "the round reservation must be released before the stable frame recheck");
      assertNull(
          engine.beginEngineModeReservation(),
          "the completion gate must reject unrelated owners while the fence is pending");

      invokeFenceResponse(engine);

      assertTrue(
          waitForCount(engine.readyCount, 1, 1, TimeUnit.SECONDS),
          "ready must publish after the final board fence");
      assertEquals(3, engine.loadSgfCount.get(), "frozen route plus two catch-up rounds");
      assertEquals(1, engine.resumeCount.get(), "ponder must resume exactly once");
      assertEquals(1, engine.analyzeCount.get(), "one analysis starts after the convergence");
      assertTrue(engine.isLoaded());
      assertEngineMatchesBoard(engine, board, 19, 19);
      Leelaz.EngineModeReservation afterFence = engine.beginEngineModeReservation();
      assertNotNull(afterFence, "unrelated owners work again after the completion gate clears");
      afterFence.close();
    }
  }

  @Test
  void automaticRestartKeepsCatchUpAndFenceOnOriginallyCapturedMirrorWhenGlobalSlotRebinds()
      throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      ConvergingRestartLeelaz mirror = new ConvergingRestartLeelaz();
      ConvergingRestartLeelaz replacement = new ConvergingRestartLeelaz();
      engine.blockFirstLoadSgf = true;
      BoardHistoryList history = snapshotHistoryWithTail(false);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      Lizzie.leelaz2 = mirror;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      mirror.started = true;
      mirror.isLoaded = true;
      CountDownLatch firstLoadSgfReceived = new CountDownLatch(1);
      engine.beforeCommand =
          command -> {
            if (command.startsWith("loadsgf ") && engine.loadSgfCount.get() == 0) {
              firstLoadSgfReceived.countDown();
            }
          };

      engine.Pondering();
      Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(attempt);
      CountDownLatch completed = new CountDownLatch(1);
      attempt.restartClosedEngine(0, completed::countDown);

      assertTrue(
          firstLoadSgfReceived.await(2, TimeUnit.SECONDS),
          "the frozen route must reach the captured engine first");
      assertTrue(
          waitForLoadSgfCount(mirror, 1, 2, TimeUnit.SECONDS),
          "the initially captured mirror must receive the frozen route");
      assertTrue(
          board.previousMove(false), "navigation must stay available before the catch-up round");
      Lizzie.leelaz2 = replacement;
      invokeLoadSgfResponse(engine);

      assertTrue(
          waitForLoadSgfCount(engine, 2, 2, TimeUnit.SECONDS),
          "the catch-up round must execute on the authority");
      assertTrue(
          waitForLoadSgfCount(mirror, 2, 2, TimeUnit.SECONDS),
          "the catch-up must stay on the originally captured mirror");
      assertTrue(
          waitForRawCommandPrefix(engine.transport, "name", 2, TimeUnit.SECONDS),
          "the board fence must follow the convergence");
      assertTrue(
          waitForRawCommandPrefix(mirror.transport, "name", 2, TimeUnit.SECONDS),
          "the board fence must reach the originally captured mirror");
      assertEquals(
          0,
          replacement.transport.commands().size(),
          "the rebound replacement mirror must receive zero commands");
      assertEquals(0, engine.readyCount.get(), "ready must wait for both captured fences");
      assertNull(
          mirror.beginEngineModeReservation(),
          "the captured frozen mirror must reject unrelated owners while its fence is pending");

      invokeFenceResponse(engine);

      assertEquals(
          0,
          engine.readyCount.get(),
          "the authority fence alone must not complete while the frozen mirror fence is pending");
      invokeFenceResponse(mirror);

      assertTrue(
          waitForCount(engine.readyCount, 1, 1, TimeUnit.SECONDS),
          "ready must publish after the captured authority and mirror fences");
      assertEquals(2, engine.loadSgfCount.get(), "frozen route plus one catch-up route");
      assertEquals(2, mirror.loadSgfCount.get(), "the captured mirror executes both rounds");
      assertEquals(
          0,
          countCommandsWithPrefix(replacement.transport.commands(), "loadsgf ")
              + countCommandsWithPrefix(replacement.transport.commands(), "name"),
          "the rebound replacement mirror must receive no restore or fence commands");
      assertTrue(engine.isLoaded());
      assertEngineMatchesBoard(engine, board, 19, 19);
      assertEngineMatchesBoard(mirror, board, 19, 19);
      Leelaz.EngineModeReservation afterFence = engine.beginEngineModeReservation();
      assertNotNull(afterFence, "the completion gate must clear after the captured fences settle");
      afterFence.close();
    }
  }

  @Test
  void capturedMirrorFenceFailureFailsClosedAndClearsCompletionGate() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      ConvergingRestartLeelaz mirror = new ConvergingRestartLeelaz();
      engine.blockFirstLoadSgf = true;
      mirror.failFence = true;
      BoardHistoryList history = snapshotHistoryWithTail(false);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      Lizzie.leelaz2 = mirror;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      mirror.started = true;
      mirror.isLoaded = true;
      CountDownLatch firstLoadSgfReceived = new CountDownLatch(1);
      engine.beforeCommand =
          command -> {
            if (command.startsWith("loadsgf ") && engine.loadSgfCount.get() == 0) {
              firstLoadSgfReceived.countDown();
            }
          };

      engine.Pondering();
      Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(attempt);
      CountDownLatch completed = new CountDownLatch(1);
      attempt.restartClosedEngine(0, completed::countDown);

      assertTrue(
          firstLoadSgfReceived.await(2, TimeUnit.SECONDS),
          "the frozen route must reach the captured engine first");
      assertTrue(
          waitForLoadSgfCount(mirror, 1, 2, TimeUnit.SECONDS),
          "the captured mirror must receive the frozen route");
      assertTrue(board.previousMove(false));
      invokeLoadSgfResponse(engine);
      assertTrue(
          waitForLoadSgfCount(engine, 2, 2, TimeUnit.SECONDS),
          "the catch-up round must execute on the authority");
      assertTrue(
          waitForLoadSgfCount(mirror, 2, 2, TimeUnit.SECONDS),
          "the catch-up must stay on the captured mirror");

      assertTrue(
          completed.await(2, TimeUnit.SECONDS),
          "a failed mirror fence must settle the completion callback");
      assertEquals(0, engine.readyCount.get(), "no ready after a mirror fence failure");
      assertEquals(0, engine.resumeCount.get(), "no ponder resume after a mirror fence failure");
      assertEquals(0, engine.analyzeCount.get(), "no analysis after a mirror fence failure");
      assertFalse(engine.isLoaded(), "the authority must be unavailable after the mirror failure");
      assertFalse(
          mirror.isLoaded(),
          "the failed mirror fence must not leave the captured mirror loaded");

      // Late responses to the retired fence legs must be isolated.
      invokeFenceResponse(engine);
      invokeFenceResponse(mirror);
      assertEquals(0, engine.readyCount.get(), "a late response must not publish ready");
      assertEquals(0, engine.analyzeCount.get(), "a late response must not start analysis");
      assertFalse(engine.isLoaded(), "a late response must not resurrect the authority");
      assertFalse(mirror.isLoaded(), "a late response must not resurrect the mirror");

      Leelaz.EngineModeReservation afterFailure = engine.beginEngineModeReservation();
      assertNotNull(
          afterFailure,
          "a mirror fence failure must clear the completion gate and release reservations");
      afterFailure.close();
      assertOrdinaryForwardingReopened(engine);
      assertOrdinaryForwardingReopened(mirror);
      assertAutomaticRestartRetryAvailable(engine);
    }
  }

  @Test
  void automaticRestartRootReplayConvergesWithNavigationDuringDelayedReadiness() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      engine.delayReadyAfterStart = true;
      BoardHistoryList history = plainHistoryWithMoves();
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      engine.Pondering();

      Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(attempt);
      CountDownLatch completed = new CountDownLatch(1);
      attempt.restartClosedEngine(0, completed::countDown);

      assertTrue(
          engine.startCompleted.await(2, TimeUnit.SECONDS),
          "the root-replay restart must reach the startup phase");
      assertTrue(
          board.previousMove(false),
          "navigation during the delayed readiness must stay available");
      engine.publishReady();

      assertTrue(
          waitForCount(engine.clearBoardCount, 2, 2, TimeUnit.SECONDS),
          "the frozen root replay must be followed by one catch-up root replay");
      assertEquals(
          0,
          engine.loadSgfCount.get(),
          "the root route must not use loadsgf for the frozen or catch-up rounds");
      assertTrue(
          waitForRawCommandPrefix(engine.transport, "name", 2, TimeUnit.SECONDS),
          "the board fence must follow the root convergence");
      assertEquals(0, engine.readyCount.get(), "ready must wait for the board fence");
      assertEquals(0, engine.analyzeCount.get(), "no analysis before the board fence");
      assertFalse(
          engine.hasExclusiveGtpLifecycleTransitionForTest(),
          "the round reservation must be released before the stable frame recheck");
      assertNull(
          engine.beginEngineModeReservation(),
          "the completion gate must reject unrelated owners while the fence is pending");

      invokeFenceResponse(engine);

      assertTrue(
          waitForCount(engine.readyCount, 1, 1, TimeUnit.SECONDS),
          "ready must publish after the root-replay board fence");
      assertEquals(2, engine.clearBoardCount.get(), "frozen root replay plus one catch-up replay");
      assertEquals(1, engine.resumeCount.get(), "ponder must resume exactly once");
      assertEquals(1, engine.analyzeCount.get(), "one analysis starts after the convergence");
      assertTrue(engine.isLoaded());
      assertEngineMatchesBoard(engine, board, 19, 19);
      Leelaz.EngineModeReservation afterFence = engine.beginEngineModeReservation();
      assertNotNull(afterFence, "unrelated owners work again after the completion gate clears");
      afterFence.close();
    }
  }

  @Test
  void rootReplayReconcilesCapturedNonDefaultBoardSizeBeforeReplay() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      Board.boardWidth = 13;
      Board.boardHeight = 13;
      Zobrist.init();
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      ConvergingRestartLeelaz mirror = new ConvergingRestartLeelaz();
      BoardHistoryList history = plainHistoryWithMoves(13);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      Lizzie.leelaz2 = mirror;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      mirror.started = true;
      mirror.isLoaded = true;
      engine.Pondering();

      Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(attempt);
      CountDownLatch completed = new CountDownLatch(1);
      attempt.restartClosedEngine(0, completed::countDown);

      assertTrue(
          waitForRawCommandPrefix(engine.transport, "boardsize 13", 2, TimeUnit.SECONDS),
          "the captured 13x13 frame must be reconciled on the authority before the root replay");
      assertTrue(
          waitForRawCommandPrefix(mirror.transport, "boardsize 13", 2, TimeUnit.SECONDS),
          "the captured 13x13 frame must be reconciled on the captured mirror before the root"
              + " replay");
      assertTrue(
          waitForRawCommandPrefix(engine.transport, "name", 2, TimeUnit.SECONDS),
          "the board fence must follow the root replay convergence");
      assertTrue(
          waitForRawCommandPrefix(mirror.transport, "name", 2, TimeUnit.SECONDS),
          "the captured mirror must receive the final board fence");
      assertEquals(0, engine.readyCount.get(), "ready must wait for the board fence");

      invokeFenceResponse(engine);
      assertEquals(
          0,
          engine.readyCount.get(),
          "the authority fence alone must not complete while the mirror fence is pending");
      invokeFenceResponse(mirror);

      assertTrue(completed.await(1, TimeUnit.SECONDS));
      assertTrue(
          waitForCount(engine.readyCount, 1, 1, TimeUnit.SECONDS),
          "ready must publish after the root replay board fence");
      assertEquals(1, engine.resumeCount.get(), "ponder must resume exactly once");
      assertEquals(1, engine.analyzeCount.get(), "one analysis starts after the convergence");
      assertEquals(1, engine.clearBoardCount.get(), "one root replay round without navigation");
      assertTrue(engine.isLoaded());
      assertEngineMatchesBoard(engine, board, 13, 13);
      assertEngineMatchesBoard(mirror, board, 13, 13);
    }
  }

  @Test
  void duplicateRestartDuringBlockedRestoreKeepsBarriersAndSuppressesOrdinaryCommands()
      throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      ConvergingRestartLeelaz mirror = new ConvergingRestartLeelaz();
      engine.blockFirstLoadSgf = true;
      BoardHistoryList history = snapshotHistoryWithTail(false);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      Lizzie.leelaz2 = mirror;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      mirror.started = true;
      mirror.isLoaded = true;
      CountDownLatch firstLoadSgfReceived = new CountDownLatch(1);
      engine.beforeCommand =
          command -> {
            if (command.startsWith("loadsgf ") && engine.loadSgfCount.get() == 0) {
              firstLoadSgfReceived.countDown();
            }
          };

      engine.Pondering();
      Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(attempt);
      CountDownLatch completed = new CountDownLatch(1);
      AtomicBoolean admissionOpenInCompletion = new AtomicBoolean(false);
      attempt.restartClosedEngine(
          0,
          () -> {
            admissionOpenInCompletion.set(
                engineModeAdmissionOpen(engine) && engineModeAdmissionOpen(mirror));
            completed.countDown();
          });

      assertTrue(
          firstLoadSgfReceived.await(2, TimeUnit.SECONDS),
          "the frozen restore route must reach the engine first");

      assertThrows(
          IllegalStateException.class,
          () -> engine.restartClosedEngine(0),
          "a duplicate restart during the blocked restore must be rejected");
      engine.sendCommand("play B Q4");
      assertFalse(
          engine.transport.commands().contains("play B Q4"),
          "ordinary live-board updates must stay suppressed after the rejected duplicate");
      assertNull(
          engine.beginEngineModeReservation(),
          "the first owner's completion gate must stay active after the rejected duplicate");

      assertTrue(
          board.previousMove(false),
          "navigation must stay available after the rejected duplicate");
      invokeLoadSgfResponse(engine);

      assertTrue(
          waitForLoadSgfCount(engine, 2, 2, TimeUnit.SECONDS),
          "the first owner must still converge with one catch-up route");
      assertTrue(
          waitForRawCommandPrefix(engine.transport, "name", 2, TimeUnit.SECONDS),
          "the board fence must follow the first owner's convergence");
      assertTrue(
          waitForRawCommandPrefix(mirror.transport, "name", 2, TimeUnit.SECONDS),
          "the captured mirror must receive the final board fence");
      invokeFenceResponse(engine);
      assertEquals(
          0,
          engine.readyCount.get(),
          "the authority fence alone must not complete while the mirror fence is pending");
      invokeFenceResponse(mirror);

      assertTrue(
          waitForCount(engine.readyCount, 1, 1, TimeUnit.SECONDS),
          "the first owner must publish ready after both fences");
      assertTrue(completed.await(1, TimeUnit.SECONDS));
      assertEquals(1, engine.resumeCount.get(), "ponder must resume exactly once");
      assertEquals(1, engine.analyzeCount.get(), "one analysis starts after the convergence");
      assertTrue(engine.isLoaded());
      assertTrue(
          admissionOpenInCompletion.get(),
          "the completion callback must observe both endpoints reopened after release");
    }
  }

  @Test
  void staleAttemptCleanupCannotClearSuccessorRestartGate() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      ConvergingRestartLeelaz mirror = new ConvergingRestartLeelaz();
      Board board = boardWithHistory(snapshotHistoryWithTail(false));
      env.publish(engine, board);
      Lizzie.leelaz2 = mirror;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      mirror.started = true;
      mirror.isLoaded = true;
      engine.started = true;
      engine.isLoaded = true;

      // A stale attempt from a previous restart round is captured, settled and then held in a
      // variable so a later stale cleanup can be replayed against the successor.
      Leelaz.AutomaticRestartAttempt staleAttempt =
          engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(staleAttempt);
      staleAttempt.close();

      // The successor attempt owns the lifecycle claim and blocks unrelated engine-mode owners on
      // both endpoints from capture through final fence settlement.
      Leelaz.AutomaticRestartAttempt successorAttempt =
          engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(successorAttempt);
      CountDownLatch completed = new CountDownLatch(1);
      successorAttempt.restartClosedEngine(0, completed::countDown);

      assertTrue(
          waitForRawCommandPrefix(engine.transport, "name", 2, TimeUnit.SECONDS),
          "the successor attempt must reach the final board fence");
      assertTrue(
          waitForRawCommandPrefix(mirror.transport, "name", 2, TimeUnit.SECONDS),
          "the successor attempt must reach the captured mirror fence");
      assertNull(
          engine.beginEngineModeReservation(),
          "the successor claim must reject unrelated owners while its fence is pending");
      assertNull(
          mirror.beginEngineModeReservation(),
          "the successor claim must reject unrelated owners on the frozen mirror");

      // A stale cleanup holding the previous restart's settled attempt must not disturb the
      // successor: close() of an already-settled attempt is inert.
      staleAttempt.close();
      assertNull(
          engine.beginEngineModeReservation(),
          "the successor claim must stay active after a stale cleanup");
      assertNull(
          mirror.beginEngineModeReservation(),
          "the successor claim must stay active on the frozen mirror after a stale cleanup");

      // The successor owner completes its own fence exactly once, then admission reopens.
      invokeFenceResponse(engine);
      invokeFenceResponse(mirror);
      assertTrue(completed.await(1, TimeUnit.SECONDS));
      Leelaz.EngineModeReservation afterClear = engine.beginEngineModeReservation();
      assertNotNull(afterClear, "the successor owner's completion must reopen admission");
      afterClear.close();

      // A repeated completion of the already-settled attempt is a one-shot no-op: close() after
      // start is inert and must not re-block admission.
      successorAttempt.close();
      Leelaz.EngineModeReservation afterRepeat = engine.beginEngineModeReservation();
      assertNotNull(afterRepeat, "a repeated completion must not re-block admission");
      afterRepeat.close();
    }
  }

  @Test
  void authoritySynchronousFenceErrorSendsNoMirrorFence() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      ConvergingRestartLeelaz mirror = new ConvergingRestartLeelaz();
      engine.blockFirstLoadSgf = true;
      engine.failFence = true;
      BoardHistoryList history = snapshotHistoryWithTail(false);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      Lizzie.leelaz2 = mirror;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      mirror.started = true;
      mirror.isLoaded = true;
      CountDownLatch firstLoadSgfReceived = new CountDownLatch(1);
      engine.beforeCommand =
          command -> {
            if (command.startsWith("loadsgf ") && engine.loadSgfCount.get() == 0) {
              firstLoadSgfReceived.countDown();
            }
          };

      engine.Pondering();
      Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(attempt);
      CountDownLatch completed = new CountDownLatch(1);
      attempt.restartClosedEngine(0, completed::countDown);

      assertTrue(
          firstLoadSgfReceived.await(2, TimeUnit.SECONDS),
          "the frozen restore route must reach the engine first");
      assertTrue(board.previousMove(false));
      invokeLoadSgfResponse(engine);

      assertTrue(
          waitForLoadSgfCount(engine, 2, 2, TimeUnit.SECONDS),
          "the catch-up round must execute before the authority fence");
      assertTrue(
          waitForLoadSgfCount(mirror, 2, 2, TimeUnit.SECONDS),
          "the captured mirror must execute the catch-up round");
      assertTrue(
          completed.await(2, TimeUnit.SECONDS),
          "a synchronous authority fence error must settle the completion callback");
      assertFalse(
          waitForNumberedRawCommandPrefix(mirror.transport, "name", 200, TimeUnit.MILLISECONDS),
          "a synchronous authority fence error must not dispatch a mirror fence: "
              + mirror.transport.rawCommands());
      assertEquals(0, engine.readyCount.get(), "no ready after an authority fence error");
      assertEquals(0, engine.resumeCount.get(), "no ponder resume after an authority fence error");
      assertEquals(0, engine.analyzeCount.get(), "no analysis after an authority fence error");
      assertFalse(engine.isLoaded(), "the authority must be unavailable after its fence error");
      assertFalse(
          mirror.isLoaded(),
          "the unconfirmed captured mirror must fail closed with the authority");
      Leelaz.EngineModeReservation afterFailure = engine.beginEngineModeReservation();
      assertNotNull(
          afterFailure,
          "an authority fence error must clear the completion gate and release reservations");
      afterFailure.close();
      assertOrdinaryForwardingReopened(engine);
      assertOrdinaryForwardingReopened(mirror);
      assertAutomaticRestartRetryAvailable(engine);
    }
  }

  @Test
  void synchronousFenceStartFailureReleasesClaimAndFailsBothEndpointsClosed() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      ConvergingRestartLeelaz mirror = new ConvergingRestartLeelaz();
      engine.throwFenceStart = true;
      Board board = boardWithHistory(snapshotHistoryWithTail(false));
      env.publish(engine, board);
      Lizzie.leelaz2 = mirror;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      mirror.started = true;
      mirror.isLoaded = true;

      Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(attempt);
      CountDownLatch completed = new CountDownLatch(1);
      AtomicBoolean admissionOpenInCompletion = new AtomicBoolean(false);
      attempt.restartClosedEngine(
          0,
          () -> {
            admissionOpenInCompletion.set(
                engineModeAdmissionOpen(engine) && engineModeAdmissionOpen(mirror));
            completed.countDown();
          });

      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertFalse(engine.isLoaded());
      assertFalse(mirror.isLoaded());
      assertTrue(
          admissionOpenInCompletion.get(),
          "a synchronous fence start failure must release the claim before completion");
      assertOrdinaryForwardingReopened(engine);
      assertOrdinaryForwardingReopened(mirror);
      assertAutomaticRestartRetryAvailable(engine);
    }
  }

  @Test
  void fenceTimeoutMarksBothEndpointsUnavailableAndRetiresHandlers() throws Exception {
    try (RestartTestEnvironment env = RestartTestEnvironment.open()) {
      ConvergingRestartLeelaz engine = new ConvergingRestartLeelaz();
      ConvergingRestartLeelaz mirror = new ConvergingRestartLeelaz();
      engine.blockFirstLoadSgf = true;
      engine.shortFenceTimeout = true;
      BoardHistoryList history = snapshotHistoryWithTail(false);
      Board board = boardWithHistory(history);
      env.publish(engine, board);
      Lizzie.leelaz2 = mirror;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      mirror.started = true;
      mirror.isLoaded = true;
      CountDownLatch firstLoadSgfReceived = new CountDownLatch(1);
      engine.beforeCommand =
          command -> {
            if (command.startsWith("loadsgf ") && engine.loadSgfCount.get() == 0) {
              firstLoadSgfReceived.countDown();
            }
          };

      engine.Pondering();
      Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
      assertNotNull(attempt);
      CountDownLatch completed = new CountDownLatch(1);
      AtomicBoolean handlersRetiredInCallback = new AtomicBoolean(false);
      attempt.restartClosedEngine(
          0,
          () -> {
            try {
              handlersRetiredInCallback.set(
                  pendingBoardSynchronizationHandlerCount(engine) == 0
                      && pendingBoardSynchronizationHandlerCount(mirror) == 0);
            } catch (Exception failure) {
              throw new IllegalStateException(failure);
            }
            completed.countDown();
          });

      assertTrue(
          firstLoadSgfReceived.await(2, TimeUnit.SECONDS),
          "the frozen restore route must reach the engine first");
      assertTrue(board.previousMove(false));
      invokeLoadSgfResponse(engine);

      assertTrue(
          waitForLoadSgfCount(engine, 2, 2, TimeUnit.SECONDS),
          "the catch-up round must execute before the fences");
      assertTrue(
          waitForLoadSgfCount(mirror, 2, 2, TimeUnit.SECONDS),
          "the captured mirror must execute the catch-up round");
      assertTrue(
          waitForNumberedRawCommandPrefix(engine.transport, "name", 2, TimeUnit.SECONDS),
          "the authority fence must be dispatched");
      assertTrue(
          waitForNumberedRawCommandPrefix(mirror.transport, "name", 2, TimeUnit.SECONDS),
          "the mirror fence must be dispatched");

      assertTrue(
          completed.await(2, TimeUnit.SECONDS),
          "a fence timeout must settle the completion callback");
      assertTrue(
          handlersRetiredInCallback.get(),
          "the failure callback must observe every dispatched fence handler already retired;"
              + " authority="
              + pendingBoardSynchronizationHandlerCount(engine)
              + ", mirror="
              + pendingBoardSynchronizationHandlerCount(mirror));
      assertEquals(0, engine.readyCount.get(), "no ready after the fence timeout");
      assertEquals(0, engine.resumeCount.get(), "no ponder resume after the fence timeout");
      assertEquals(0, engine.analyzeCount.get(), "no analysis after the fence timeout");
      assertFalse(engine.isLoaded(), "the authority must be unavailable after the fence timeout");
      assertFalse(
          mirror.isLoaded(),
          "the unconfirmed captured mirror must be unavailable after the fence timeout");

      // Late responses to the retired fence legs must be isolated.
      invokeFenceResponse(engine);
      invokeFenceResponse(mirror);
      assertEquals(0, engine.readyCount.get(), "a late authority response must not publish ready");
      assertEquals(0, engine.analyzeCount.get(), "a late response must not start analysis");
      assertFalse(engine.isLoaded(), "a late authority response must not resurrect the authority");
      assertFalse(mirror.isLoaded(), "a late mirror response must not resurrect the mirror");

      Leelaz.EngineModeReservation afterTimeout = engine.beginEngineModeReservation();
      assertNotNull(
          afterTimeout,
          "the fence timeout must clear the completion gate and release reservations");
      afterTimeout.close();
      assertOrdinaryForwardingReopened(engine);
      assertOrdinaryForwardingReopened(mirror);
      assertAutomaticRestartRetryAvailable(engine);
    }
  }

  private static void invokeLoadSgfResponse(ConvergingRestartLeelaz engine) throws Exception {
    String response;
    try {
      response = numberedResponseFor(engine.transport.rawCommands(), "loadsgf ");
    } catch (IllegalArgumentException missingLoadSgf) {
      response = numberedResponseFor(engine.transport.rawCommands(), "set_position");
    }
    engine.processCommandResponseLineForTest(response);
  }

  private static void invokeFenceResponse(ConvergingRestartLeelaz engine) throws Exception {
    String response = numberedResponseFor(engine.transport.rawCommands(), "name");
    engine.processCommandResponseLineForTest(response);
  }

  private static void invokeCheckEngineAlive(EngineManager manager) throws Exception {
    Method method = EngineManager.class.getDeclaredMethod("checkEngineAlive");
    method.setAccessible(true);
    method.invoke(manager);
  }

  private static String numberedResponseFor(List<String> rawCommands, String commandPrefix) {
    for (int index = rawCommands.size() - 1; index >= 0; index--) {
      String command = rawCommands.get(index);
      int firstSpace = command.indexOf(' ');
      if (firstSpace > 0
          && command.substring(0, firstSpace).chars().allMatch(Character::isDigit)
          && command.substring(firstSpace + 1).startsWith(commandPrefix)) {
        return "=" + command.substring(0, firstSpace);
      }
    }
    throw new IllegalArgumentException("Missing numbered command prefix " + commandPrefix);
  }

  private static boolean waitForRawCommandPrefix(
      ExactSnapshotRestoreProtocolFixture.Transport transport,
      String prefix,
      long timeout,
      TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      for (String command : transport.commands()) {
        if (command.startsWith(prefix)) {
          return true;
        }
      }
      Thread.sleep(10L);
    }
    return false;
  }

  private static boolean waitForNumberedRawCommandPrefix(
      ExactSnapshotRestoreProtocolFixture.Transport transport,
      String prefix,
      long timeout,
      TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      for (String command : transport.rawCommands()) {
        int separator = command.indexOf(' ');
        if (separator > 0
            && command.substring(0, separator).chars().allMatch(Character::isDigit)
            && command.substring(separator + 1).startsWith(prefix)) {
          return true;
        }
      }
      Thread.sleep(10L);
    }
    return false;
  }

  private static int countCommandsWithPrefix(List<String> commands, String prefix) {
    int count = 0;
    for (String command : commands) {
      if (command.startsWith(prefix)) {
        count++;
      }
    }
    return count;
  }

  private static int pendingBoardSynchronizationHandlerCount(Leelaz engine) throws Exception {
    Field field = Leelaz.class.getDeclaredField("pendingResponseHandlers");
    field.setAccessible(true);
    Object handlers = field.get(engine);
    if (handlers == null) {
      return 0;
    }
    int count = 0;
    for (Object pending : (Collection<?>) handlers) {
      Field responseCommandId = pending.getClass().getDeclaredField("responseCommandId");
      responseCommandId.setAccessible(true);
      if (responseCommandId.getInt(pending) >= 900_000_000) {
        count++;
      }
    }
    return count;
  }


  private static boolean waitForLoadSgfCount(
      ConvergingRestartLeelaz engine, int count, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      if (engine.loadSgfCount.get() >= count) {
        return true;
      }
      Thread.sleep(10L);
    }
    return false;
  }

  private static boolean waitForCount(
      AtomicInteger counter, int count, long timeout, TimeUnit unit) throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      if (counter.get() >= count) {
        return true;
      }
      Thread.sleep(10L);
    }
    return false;
  }

  /** Plain MOVE history without any usable snapshot anchor, forcing the root-replay route. */
  private static BoardHistoryList plainHistoryWithMoves() {
    return plainHistoryWithMoves(19);
  }

  /** Plain MOVE history of the given size without any usable snapshot anchor (root-replay route). */
  private static BoardHistoryList plainHistoryWithMoves(int boardSize) {
    BoardData root = BoardData.empty(boardSize, boardSize);
    BoardHistoryList history = new BoardHistoryList(root);
    history.getGameInfo().setKomiNoMenu(6.5);
    Stone[] firstStones = root.stones.clone();
    firstStones[Board.getIndex(5, 5)] = Stone.BLACK;
    history.add(
        BoardData.move(
            firstStones,
            new int[] {5, 5},
            Stone.BLACK,
            false,
            new Zobrist(55L),
            1,
            new int[boardSize * boardSize],
            0,
            0,
            50,
            0));
    Stone[] secondStones = firstStones.clone();
    secondStones[Board.getIndex(6, 6)] = Stone.WHITE;
    history.add(
        BoardData.move(
            secondStones,
            new int[] {6, 6},
            Stone.WHITE,
            true,
            new Zobrist(66L),
            2,
            new int[boardSize * boardSize],
            0,
            0,
            50,
            0));
    return history;
  }

  /** Snapshot root plus two real moves so two consecutive navigation steps both succeed. */
  private static BoardHistoryList snapshotHistoryWithTwoMoves() {
    BoardHistoryList history = snapshotHistoryWithTail(false);
    BoardData firstTail = history.getData();
    Stone[] secondStones = firstTail.stones.clone();
    secondStones[Board.getIndex(6, 6)] = Stone.WHITE;
    history.add(
        BoardData.move(
            secondStones,
            new int[] {6, 6},
            Stone.WHITE,
            true,
            new Zobrist(89L),
            5,
            new int[19 * 19],
            0,
            0,
            50,
            0));
    return history;
  }

  private static BoardHistoryList snapshotHistoryWithTail(boolean withPass) {
    BoardData root = snapshotRoot();
    BoardHistoryList history = new BoardHistoryList(root);
    history.getGameInfo().setKomiNoMenu(6.5);
    Stone[] tailStones = root.stones.clone();
    tailStones[Board.getIndex(5, 5)] = Stone.BLACK;
    history.add(
        BoardData.move(
            tailStones,
            new int[] {5, 5},
            Stone.BLACK,
            false,
            new Zobrist(77L),
            4,
            new int[19 * 19],
            0,
            0,
            50,
            0));
    if (withPass) {
      Stone[] passStones = tailStones.clone();
      history.add(
          BoardData.pass(
              passStones, Stone.WHITE, true, new Zobrist(88L), 5, new int[19 * 19], 0, 0, 50, 0));
    }
    return history;
  }

  private static BoardData snapshotRoot() {
    Stone[] stones = new Stone[19 * 19];
    Arrays.fill(stones, Stone.EMPTY);
    stones[Board.getIndex(3, 3)] = Stone.BLACK;
    stones[Board.getIndex(4, 4)] = Stone.WHITE;
    int[] moveNumberList = new int[19 * 19];
    moveNumberList[Board.getIndex(3, 3)] = 1;
    moveNumberList[Board.getIndex(4, 4)] = 2;
    return BoardData.snapshot(
        stones,
        Optional.of(new int[] {4, 4}),
        Stone.WHITE,
        false,
        new Zobrist(42L),
        3,
        moveNumberList,
        0,
        0,
        50,
        0);
  }

  private static Board boardWithHistory(BoardHistoryList history) throws Exception {
    Board board = allocate(RestartTestBoard.class);
    board.startStonelist = new ArrayList<>();
    board.movelistwr = new ArrayList<>();
    board.hasStartStone = false;
    board.setHistory(history);
    return board;
  }

  private static void assertOrdinaryForwardingReopened(Leelaz engine) {
    assertTrue(
        engine.submitOrdinaryLiveBoardForwarding(
            EngineManager.OrdinaryLiveBoardForwardingIntent.of(() -> true)),
        "ordinary live-board forwarding must reopen after the barrier ends");
  }

  private static boolean engineModeAdmissionOpen(Leelaz engine) {
    try (Leelaz.EngineModeReservation reservation = engine.beginEngineModeReservation()) {
      return reservation != null;
    }
  }

  private static void assertAutomaticRestartRetryAvailable(ConvergingRestartLeelaz engine) {
    Leelaz.AutomaticRestartAttempt retry = engine.beginAutomaticEngineRestartAttempt();
    assertNotNull(retry, "a later automatic restart must be admissible after fail-closed");
    retry.close();
  }

  private static void assertEngineMatchesBoard(
      ConvergingRestartLeelaz engine, Board board, int expectedWidth, int expectedHeight) {
    BoardData application = board.getHistory().getData();
    assertEquals(expectedWidth, engine.engineBoardWidth, "board width must match");
    assertEquals(expectedHeight, engine.engineBoardHeight, "board height must match");
    assertEquals(
        board.getHistory().getGameInfo().getKomi(), engine.engineKomi, 0.0001, "komi must match");
    assertEquals(application.blackToPlay, engine.engineBlackToPlay, "side-to-play must match");
    for (int x = 0; x < expectedWidth; x++) {
      for (int y = 0; y < expectedHeight; y++) {
        assertEquals(
            application.stones[Board.getIndex(x, y)],
            engine.stoneAt(x, y),
            "stone mismatch at " + x + "," + y);
      }
    }
  }

  private static Path createOpenClEngine(Path tempRoot) throws IOException {
    Path engineDirectory = Files.createDirectories(tempRoot.resolve("engines/katago/windows-x64"));
    Files.writeString(engineDirectory.resolve("lizzieyzy-next-engine-backend.txt"), "opencl");
    return touch(engineDirectory.resolve("katago.exe"));
  }

  private static Path touch(Path path) throws IOException {
    Files.createDirectories(path.getParent());
    return Files.write(path, new byte[0]);
  }

  private static boolean invokeOpenClRecovery(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("tryRecoverBundledOpenClNativeExit");
    method.setAccessible(true);
    return (Boolean) method.invoke(engine);
  }

  private static void setField(Leelaz engine, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(engine, value);
  }

  private static void restoreProperty(String name, String previousValue) {
    if (previousValue == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, previousValue);
    }
  }

  @FunctionalInterface
  private interface CommandHook {
    void onCommand(String command) throws Exception;
  }

  private static final class ConvergingRestartLeelaz extends Leelaz {
    private final AtomicInteger analyzeCount = new AtomicInteger();
    private final AtomicInteger loadSgfCount = new AtomicInteger();
    private final AtomicInteger resumeCount = new AtomicInteger();
    private final AtomicInteger readyCount = new AtomicInteger();
    private final AtomicInteger clearBoardCount = new AtomicInteger();
    private final Map<String, Stone> engineStones = new HashMap<>();
    private final CountDownLatch startCompleted = new CountDownLatch(1);
    private volatile ExactSnapshotRestoreProtocolFixture.Transport transport;
    private CommandHook beforeCommand;
    private int failLoadSgfAt = Integer.MAX_VALUE;
    private boolean delayReadyAfterStart;
    private boolean blockFirstLoadSgf;
    private boolean blockSecondLoadSgf;
    private boolean blockThirdLoadSgf;
    private boolean productionReadyHandoff;
    private boolean failFence;
    private boolean throwFenceStart;
    private boolean shortStartupTimeout;
    private boolean shortFenceTimeout;
    private boolean processDead;
    private int engineBoardWidth = 19;
    private int engineBoardHeight = 19;
    private double engineKomi = -1.0;
    private boolean engineBlackToPlay = true;

    private ConvergingRestartLeelaz() throws Exception {
      super("controlled-engine");
      installProtocol();
    }

    private void installProtocol() {
      transport =
          ExactSnapshotRestoreProtocolFixture.install(
              this,
              command -> {
                if (beforeCommand != null) {
                  beforeCommand.onCommand(command);
                }
                if (command.startsWith("play ")) {
                  String[] parts = command.split("\\s+");
                  if (!"pass".equalsIgnoreCase(parts[2])) {
                    engineStones.put(
                        parts[2].toUpperCase(Locale.ROOT),
                        "B".equalsIgnoreCase(parts[1]) ? Stone.BLACK : Stone.WHITE);
                  }
                  engineBlackToPlay = "W".equalsIgnoreCase(parts[1]);
                } else if (command.equals("clear_board")) {
                  clearBoardCount.incrementAndGet();
                  engineStones.clear();
                  engineBlackToPlay = true;
                } else if (command.startsWith("boardsize ")) {
                  engineBoardWidth =
                      Integer.parseInt(command.substring("boardsize ".length()).trim());
                  engineBoardHeight = engineBoardWidth;
                  engineStones.clear();
                  engineBlackToPlay = true;
                } else if (command.startsWith("rectangular_boardsize ")) {
                  String[] parts = command.split("\\s+");
                  engineBoardWidth = Integer.parseInt(parts[1]);
                  engineBoardHeight = Integer.parseInt(parts[2]);
                  engineStones.clear();
                  engineBlackToPlay = true;
                } else if (command.startsWith("komi ")) {
                  engineKomi = Double.parseDouble(command.substring("komi ".length()).trim());
                } else if (command.startsWith("loadsgf ")
                    || command.equals("set_position")
                    || command.startsWith("set_position ")) {
                  int count = loadSgfCount.incrementAndGet();
                  if (count >= failLoadSgfAt) {
                    return ExactSnapshotRestoreProtocolFixture.Response.error(
                        "controlled catch-up restore failure");
                  }
                  if (command.startsWith("loadsgf ")) {
                    String sgfPath = command.substring("loadsgf ".length()).trim();
                    String sgf = Files.readString(Path.of(sgfPath));
                    applySnapshotSgf(sgf);
                  } else {
                    applySetPosition(command);
                  }
                  if (blockFirstLoadSgf && count == 1) {
                    // Hold the frozen route in flight so the test can navigate deterministically.
                    return null;
                  }
                  if (blockSecondLoadSgf && count == 2) {
                    // Hold the first catch-up route so the test can navigate deterministically.
                    return null;
                  }
                  if (blockThirdLoadSgf && count == 3) {
                    // Hold a navigation tail restore until the lifecycle fence has responded.
                    return null;
                  }
                } else if (command.startsWith("kata-analyze")) {
                  analyzeCount.incrementAndGet();
                } else if (command.equals("name")) {
                  if (failFence) {
                    return ExactSnapshotRestoreProtocolFixture.Response.error(
                        "controlled mirror fence failure");
                  }
                  // The board fence is the final gate; tests settle it explicitly.
                  return null;
                }
                return ExactSnapshotRestoreProtocolFixture.Response.success();
              });
    }

    @Override
    public void startEngine(int index) {
      started = true;
      isLoaded = !delayReadyAfterStart;
      isCheckingName = delayReadyAfterStart;
      isNormalEnd = false;
      isDownWithError = false;
      try {
        setField(this, "endGetCommandList", true);
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
      installProtocol();
      startCompleted.countDown();
    }

    private void publishReady() {
      isLoaded = true;
      isCheckingName = false;
    }

    @Override
    public boolean isProcessDead() {
      return processDead;
    }

    @Override
    public void normalQuit() {
      isNormalEnd = true;
      started = false;
      isLoaded = false;
    }

    @Override
    public void shutdown() {
      started = false;
      isLoaded = false;
      isCheckingName = false;
    }

    @Override
    void resumeClosedEngineAfterBoardSynchronization(boolean resumePonder) {
      readyCount.incrementAndGet();
      if (productionReadyHandoff) {
        // Exercise the real production readiness handoff (markEngineReady / EngineStartupStatus).
        super.resumeClosedEngineAfterBoardSynchronization(resumePonder);
        return;
      }
      if (!resumePonder) {
        return;
      }
      resumeCount.incrementAndGet();
      sendCommand("kata-analyze 10");
    }

    @Override
    public void notPondering() {}

    @Override
    protected long readBoardGmaRestoreResponseTimeoutMillis() {
      return shortFenceTimeout ? 25L : super.readBoardGmaRestoreResponseTimeoutMillis();
    }

    @Override
    long engineStartupSynchronizationTimeoutMillis() {
      return shortStartupTimeout ? 25L : super.engineStartupSynchronizationTimeoutMillis();
    }

    @Override
    void confirmBoardSynchronization(
        Leelaz mirror, Runnable onSuccess, java.util.function.Consumer<String> onFailure) {
      if (throwFenceStart) {
        throw new IllegalStateException("controlled fence start failure");
      }
      super.confirmBoardSynchronization(mirror, onSuccess, onFailure);
    }

    private Stone stoneAt(int x, int y) {
      return engineStones.getOrDefault(
          Board.convertCoordinatesToName(x, y).toUpperCase(Locale.ROOT), Stone.EMPTY);
    }

    private void applySetPosition(String command) {
      engineStones.clear();
      engineBlackToPlay = true;
      String payload =
          command.startsWith("set_position")
              ? command.substring("set_position".length()).trim()
              : "";
      if (payload.isEmpty()) {
        return;
      }
      String[] tokens = payload.split("\\s+");
      for (int index = 0; index + 1 < tokens.length; index += 2) {
        engineStones.put(
            tokens[index + 1].toUpperCase(Locale.ROOT),
            "B".equalsIgnoreCase(tokens[index]) ? Stone.BLACK : Stone.WHITE);
      }
    }

    private void applySnapshotSgf(String sgf) {
      engineStones.clear();
      engineBlackToPlay = true;
      String sizeValue = firstSgfValue(sgf, "SZ");
      if (sizeValue != null && !sizeValue.isEmpty()) {
        String[] parts = sizeValue.split(":");
        engineBoardWidth = Integer.parseInt(parts[0]);
        engineBoardHeight = parts.length > 1 ? Integer.parseInt(parts[1]) : engineBoardWidth;
      }
      String pl = firstSgfValue(sgf, "PL");
      if ("W".equalsIgnoreCase(pl)) {
        engineBlackToPlay = false;
      }
      String km = firstSgfValue(sgf, "KM");
      if (km != null && !km.isEmpty()) {
        engineKomi = Double.parseDouble(km);
      }
      applySgfStones(sgf, "AB", Stone.BLACK);
      applySgfStones(sgf, "AW", Stone.WHITE);
    }

    private void applySgfStones(String sgf, String property, Stone color) {
      for (String coord : sgfPropertyValues(sgf, property)) {
        if (coord == null || coord.length() < 2) {
          continue;
        }
        int x = coord.charAt(0) - 'a';
        int y = coord.charAt(1) - 'a';
        if (x >= 0 && x < 52 && y >= 0 && y < 52) {
          engineStones.put(Board.convertCoordinatesToName(x, y).toUpperCase(Locale.ROOT), color);
        }
      }
    }

    private static String firstSgfValue(String sgf, String property) {
      List<String> values = sgfPropertyValues(sgf, property);
      return values.isEmpty() ? null : values.get(0);
    }

    private static List<String> sgfPropertyValues(String sgf, String property) {
      java.util.regex.Matcher propertyMatcher =
          java.util.regex.Pattern.compile("(" + property + "\\[[^\\]]*\\])+").matcher(sgf);
      if (!propertyMatcher.find()) {
        return List.of();
      }
      java.util.regex.Matcher valueMatcher =
          java.util.regex.Pattern.compile("\\[([^\\]]*)\\]").matcher(propertyMatcher.group());
      List<String> values = new ArrayList<>();
      while (valueMatcher.find()) {
        values.add(valueMatcher.group(1));
      }
      return values;
    }
  }

  private static final class RestartTestEnvironment implements AutoCloseable {
    private final Config previousConfig = Lizzie.config;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final GtpConsolePane previousConsole = Lizzie.gtpConsole;
    private final Leelaz previousPrimary = Lizzie.leelaz;
    private final Leelaz previousSecondary = Lizzie.leelaz2;
    private final Board previousBoard = Lizzie.board;
    private final int previousBoardWidth = Board.boardWidth;
    private final int previousBoardHeight = Board.boardHeight;
    private final boolean previousEngineManagerEmpty = EngineManager.isEmpty;
    private final int previousEngineNo = EngineManager.currentEngineNo;

    private RestartTestEnvironment() throws Exception {
      Lizzie.config = allocate(Config.class);
      Lizzie.frame = allocate(SilentRestartFrame.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      Lizzie.leelaz2 = null;
      Board.boardWidth = 19;
      Board.boardHeight = 19;
      Zobrist.init();
    }

    private static RestartTestEnvironment open() throws Exception {
      return new RestartTestEnvironment();
    }

    private void publish(ConvergingRestartLeelaz engine, Board board) {
      Lizzie.leelaz = engine;
      Lizzie.board = board;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousConsole;
      Lizzie.leelaz = previousPrimary;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.board = previousBoard;
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      EngineManager.isEmpty = previousEngineManagerEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      Zobrist.init();
    }
  }

  private static final class RestartTestBoard extends Board {
    @Override
    public void clearAfterMove() {
      // Avoid headless UI dependencies during navigation-driven restart tests.
    }
  }

  private static final class SilentRestartFrame extends LizzieFrame {
    @Override
    public void refresh() {}

    @Override
    public void prepareQuickAnalysisForPrimaryOpenClRecovery() {}

    @Override
    public void reSetLoc() {}

    @Override
    public boolean resetMovelistFrameandAnalysisFrame() {
      return false;
    }
  }

  private static final class SilentPonderMenu extends Menu {
    @Override
    public void showPda(boolean show) {}

    @Override
    public void updateMenuStatusForEngine() {}

    @Override
    public void toggleEngineMenuStatus(boolean isPondering, boolean isThinking) {}
  }

  private static final class SilentPonderToolbar extends BottomToolbar {
    @Override
    public void reSetButtonLocation() {}
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super((Window) null);
    }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void addCommand(String command, int commandNumber, String engineName) {}

    @Override
    public void addCommandForEngineGame(
        String command, int commandNumber, String engineName, boolean isBlack) {}

    @Override
    public void addLine(String line) {}
  }

  /** Web board that reports an active trial so engine-mode admission is excluded. */
  private static final class BusyWebBoardManager extends WebBoardManager {
    @Override
    public boolean isEngineOperationExcludedByTrial() {
      return true;
    }
  }

  private static final class ExitedProcess extends Process {
    private final int exitCode;
    private final InputStream stdout;

    private ExitedProcess(int exitCode) {
      this.exitCode = exitCode;
      this.stdout = new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return stdout;
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
      return exitCode;
    }

    @Override
    public int exitValue() {
      return exitCode;
    }

    @Override
    public void destroy() {}
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }
}
