package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.enginegame.EngineGamePlans;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.HumanSlGameController;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.WaitForAnalysis;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AnalysisEngineRequestTest {
  private static final int BOARD_SIZE = 3;
  private final List<Leelaz> createdForegroundEngines = new ArrayList<>();
  private final List<featurecat.lizzie.analysis.remote.ZhiziGtpTransport> createdZhiziTransports = new ArrayList<>();

  @AfterEach
  void closeCreatedForegroundSessions() throws Exception {
    for (Leelaz engine : createdForegroundEngines) {
      closeExclusiveSessionForTest(engine);
    }
    createdForegroundEngines.clear();
    for (var transport : createdZhiziTransports) transport.close();
    createdZhiziTransports.clear();
  }

  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void sharedRestoreRemainsPartOfRequestLifecycleAfterLastResponse() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "sharedForegroundRestoreInProgress", true);
      assertFalse(engine.isAnalysisInProgress());
      assertTrue(engine.hasRequestLifecycleInProgress());
      setField(AnalysisEngine.class, engine, "sharedForegroundRestoreInProgress", false);
      assertFalse(engine.hasRequestLifecycleInProgress());
    }
  }

  @Test
  void localProcessKeepsDiagnosticFragmentsOutOfJsonResponses() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      Lizzie.config.leelazConfig = new JSONObject();
      Lizzie.config.analysisEngineCommand =
          "\""
              + java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
              + "\" -cp \""
              + System.getProperty("java.class.path")
              + "\" "
              + InterleavedAnalysisProcess.class.getName();
      var oldConsole = Lizzie.gtpConsole;
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      AnalysisEngine engine = null;
      try {
        engine = new AnalysisEngine(true);
        assertTrue(engine.sendRequest(history.getStart()));
        AnalysisEngine running = engine;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((int) getField(AnalysisEngine.class, running, "responseCount") == 0
            && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(1, getField(AnalysisEngine.class, engine, "responseCount"));
        assertEquals(200, history.getStart().getData().getPlayouts());
      } finally {
        if (engine != null) {
          Process process = engine.process;
          engine.normalQuit();
          if (process != null) assertTrue(process.waitFor(5, TimeUnit.SECONDS));
          for (String name : List.of("executor", "executorErr")) {
            var executor =
                (java.util.concurrent.ExecutorService) getField(AnalysisEngine.class, engine, name);
            if (executor != null) {
              executor.shutdown();
              assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
          }
        }
        Lizzie.gtpConsole = oldConsole;
      }
    }
  }

  @Test
  void localRequestEntrypointsKeepReadingWhilePhysicalOutputIsBlocked() throws Exception {
    for (int entrypoint = 0; entrypoint < 5; entrypoint++) {
      try (PreparationFixture fixture = new PreparationFixture(false)) {
        AnalysisEngine engine = fixture.engine;
        setField(AnalysisEngine.class, engine, "sharedForegroundEngine", null);
        setField(AnalysisEngine.class, engine, "useRemoteCompute", false);
        Lizzie.config.analysisAlwaysOverride = false;
        fixture.history.setHead(fixture.target);
        for (BoardHistoryNode node = fixture.history.getStart(); node != fixture.target;
            node = node.next().get()) node.getData().setPlayouts(1000000);
        AtomicInteger requestId = new AtomicInteger();
        var written = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
          @Override public synchronized void flush() throws IOException {
            requestId.set(new JSONObject(toString(StandardCharsets.UTF_8).trim()).getInt("id"));
            written.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IOException(e);
            }
          }
        };
        setField(AnalysisEngine.class, engine, "outputStream", new BufferedOutputStream(output));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        int selected = entrypoint;
        var writer = executor.submit(() -> {
          switch (selected) {
            case 0: engine.startRequestAllBranches(false); break;
            case 1: engine.startRequest(1, 99, false); break;
            case 2: engine.startRequestMissingMainline(false); break;
            case 3: engine.startAutomaticQuickAnalysisRequest(fixture.use); break;
            default: engine.startWholeGameRequest(List.of(fixture.target), 500, false);
          }
        });
        try {
          assertTrue(written.await(5, TimeUnit.SECONDS), "request must reach physical output");
          int id = requestId.get();
          var reader = executor.submit(() -> {
            try {
              invokeAnalysisEngineParseLine(engine, analysisResult(id, 500, 62.0));
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
          reader.get(5, TimeUnit.SECONDS);
          assertEquals(1, getField(AnalysisEngine.class, engine, "responseCount"));
          assertEquals(500, fixture.target.getData().getPlayouts());
        } finally {
          release.countDown();
          writer.get(5, TimeUnit.SECONDS);
          executor.shutdownNow();
          assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
          waitForMovelistRefreshThreads();
          javax.swing.SwingUtilities.invokeAndWait(() -> {});
        }
      }
    }
  }

  public static final class InterleavedAnalysisProcess {
    public static void main(String[] args) throws Exception {
      var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
      String request;
      while ((request = input.readLine()) != null) {
        int id = new JSONObject(request).getInt("id");
        System.err.print("diagnostic fragment without newline: ");
        System.err.flush();
        System.out.println(analysisResult(id, 200, 62.0));
        System.out.flush();
        System.err.println("finished");
      }
    }
  }

  @Test
  void automaticSilentAnalysisPausesForegroundAndLeavesResumeToItsCallback() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      PonderTrackingLeelaz foreground = allocate(PonderTrackingLeelaz.class);
      foreground.pondering = true;
      Lizzie.leelaz = foreground;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(
          AnalysisEngine.class,
          engine,
          "purpose",
          AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS);

      invokeAnalysisEnginePrepareRequestState(engine, false);

      assertFalse(foreground.pondering);
      assertEquals(1, foreground.notPonderingCalls);
      assertEquals(1, foreground.nameCommandCalls);
      assertFalse((boolean) getField(AnalysisEngine.class, engine, "shouldRePonder"));
      invokeAnalysisEngineResumeForeground(engine);
      assertEquals(0, foreground.ponderCalls);
    }
  }

  @Test
  void visibleUserAnalysisPausesAndRestoresForegroundExactlyOnce() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      PonderTrackingLeelaz foreground = allocate(PonderTrackingLeelaz.class);
      foreground.pondering = true;
      Lizzie.leelaz = foreground;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(
          AnalysisEngine.class,
          engine,
          "purpose",
          AnalysisResourceCoordinator.Purpose.USER_QUICK_ANALYSIS);

      invokeAnalysisEnginePrepareRequestState(engine, true);
      invokeAnalysisEngineResumeForeground(engine);
      invokeAnalysisEngineResumeForeground(engine);

      assertEquals(1, foreground.notPonderingCalls);
      assertEquals(1, foreground.nameCommandCalls);
      assertEquals(1, foreground.ponderCalls);
      assertTrue(foreground.pondering);
    }
  }

  @Test
  void reuseModeBindsCurrentForegroundKatagoWithoutStartingAProcess() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = true;

      AnalysisEngine engine = new AnalysisEngine(false);

      assertTrue(engine.isLoaded());
      assertNull(engine.process);
      assertTrue(engine.matchesCurrentAnalysisBackend());
    }
  }

  @Test
  void automaticQuickAnalysisReusesRemoteAndBundledNvidiaForegroundWhenAppropriate() {
    assertTrue(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS, true, false, false, false));
    assertFalse(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS, true, false, false, true));
    assertTrue(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS, false, true, true, true));
    assertTrue(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS, false, true, false, false));
    assertFalse(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS, false, true, false, true));
    assertFalse(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.USER_QUICK_ANALYSIS, true, true, true, false));
    assertFalse(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.WHOLE_GAME_ANALYSIS, true, true, true, false));
    assertFalse(
        AnalysisEngine.shouldAutomaticallyReusePrimaryForeground(
            AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS, false, false, false, false));
  }

  @Test
  void automaticQuickAnalysisBindsTheActiveRemoteEngineWithoutChangingUserSettings()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Leelaz foreground = reusableForegroundEngine(true);
      foreground.setEngineCommand(RemoteComputeConfig.COMMAND_ZHIZI);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = false;
      Lizzie.config.quickAnalysisLightweightModelEnabled = false;

      AnalysisEngine engine = AnalysisEngine.createAutomaticQuickAnalysis();

      assertTrue(engine.isLoaded());
      assertTrue(engine.usesSharedForegroundEngine());
      assertTrue(engine.usesAutomaticPrimaryForegroundReuse());
      assertTrue(engine.matchesCurrentAnalysisBackend());
      assertNull(engine.process);
      assertFalse(Lizzie.config.analysisReuseCurrentEngine);
    }
  }

  @Test
  void automaticPrimaryForegroundReuseInvalidatesWhenThePrimaryEngineChanges() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Leelaz firstForeground = reusableForegroundEngine(true);
      Leelaz secondForeground = reusableForegroundEngine(true);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      Lizzie.config.analysisReuseCurrentEngine = false;
      Lizzie.leelaz = firstForeground;
      setField(
          AnalysisEngine.class,
          engine,
          "purpose",
          AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS);
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", firstForeground);
      setField(AnalysisEngine.class, engine, "automaticPrimaryForegroundReuse", true);
      setField(
          AnalysisEngine.class,
          engine,
          "automaticPrimaryForegroundCommand",
          firstForeground.engineCommand());

      assertTrue(engine.matchesCurrentAnalysisBackend());
      assertFalse(Lizzie.config.analysisReuseCurrentEngine);

      Lizzie.leelaz = secondForeground;

      assertFalse(engine.matchesCurrentAnalysisBackend());
    }
  }

  @Test
  void automaticPrimaryForegroundReuseInvalidatesWhenTheSameEngineObjectChangesCommand()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Leelaz foreground = reusableForegroundEngine(true);
      foreground.setEngineCommand("bundled-tensorrt-command");
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      Lizzie.config.analysisReuseCurrentEngine = false;
      Lizzie.leelaz = foreground;
      setField(
          AnalysisEngine.class,
          engine,
          "purpose",
          AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS);
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "automaticPrimaryForegroundReuse", true);
      setField(
          AnalysisEngine.class,
          engine,
          "automaticPrimaryForegroundCommand",
          foreground.engineCommand());

      assertTrue(engine.matchesCurrentAnalysisBackend());

      foreground.setEngineCommand("different-engine-command");

      assertFalse(engine.matchesCurrentAnalysisBackend());
    }
  }

  @Test
  void reuseModeRejectsNonKatagoWithoutFallingBackToDedicatedProcess() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Leelaz foreground = reusableForegroundEngine(false);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = true;

      AnalysisEngine engine = new AnalysisEngine(false);

      assertFalse(engine.isLoaded());
      assertNull(engine.process);
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.NOT_KATAGO, engine.getForegroundLeaseAvailability());
    }
  }

  @Test
  void reuseModeBindsTheForegroundEngineAgainForEachAnalysisTask() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      Leelaz firstForeground = reusableForegroundEngine(true);
      Leelaz secondForeground = reusableForegroundEngine(true);
      Lizzie.leelaz = firstForeground;
      AnalysisEngine firstTask = new AnalysisEngine(false);

      Lizzie.leelaz = secondForeground;
      AnalysisEngine secondTask = new AnalysisEngine(false);

      assertFalse(firstTask.matchesCurrentAnalysisBackend());
      assertTrue(secondTask.matchesCurrentAnalysisBackend());
      assertNull(secondTask.process);
    }
  }

  @Test
  void reuseModeReportsMissingEngineReadinessCapabilitiesAndPlayMode() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      Lizzie.leelaz = null;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.NO_FOREGROUND_ENGINE,
          new AnalysisEngine(false).getForegroundLeaseAvailability());

      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      setField(Leelaz.class, foreground, "endGetCommandList", false);
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY,
          new AnalysisEngine(false).getForegroundLeaseAvailability());

      setField(Leelaz.class, foreground, "endGetCommandList", true);
      foreground.commandLists.remove("kata-analyze");
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.MISSING_CAPABILITY,
          new AnalysisEngine(false).getForegroundLeaseAvailability());

      foreground.commandLists.add("kata-analyze");
      Lizzie.frame.isPlayingAgainstLeelaz = true;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.PLAY_MODE,
          new AnalysisEngine(false).getForegroundLeaseAvailability());
    }
  }

  @Test
  void reuseModeAllowsPonderingButRejectsGenmoveWithoutDedicatedFallback() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      foreground.Pondering();

      AnalysisEngine ponderingTask = new AnalysisEngine(false);
      assertTrue(ponderingTask.isLoaded());

      foreground.isThinking = true;
      AnalysisEngine genmoveTask = new AnalysisEngine(false);
      assertFalse(genmoveTask.isLoaded());
      assertNull(genmoveTask.process);
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.GENMOVE,
          genmoveTask.getForegroundLeaseAvailability());
    }
  }

  @Test
  void reuseModeRejectsEveryReadBoardGmaBusyPhase() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      ReadBoard readBoard = allocate(ReadBoard.class);
      Lizzie.frame.readBoard = readBoard;

      for (String fieldName :
          List.of(
              "readBoardGmaPending",
              "readBoardGmaEngineRestorePending",
              "readBoardGmaEngineRestoreInProgress")) {
        setField(ReadBoard.class, readBoard, fieldName, true);

        assertEquals(
            Leelaz.ExclusiveGtpLeaseAvailability.READBOARD_GMA,
            new AnalysisEngine(false).getForegroundLeaseAvailability(),
            fieldName);

        setField(ReadBoard.class, readBoard, fieldName, false);
      }
    }
  }

  @Test
  void reuseModeRejectsEngineGamePreparationAndExistingLease() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      EngineManager previousManager = Lizzie.engineManager;
      Leelaz dummy = new Leelaz("");
      EngineManager manager = new EngineManager(List.of(foreground, dummy));
      try {
        Lizzie.engineManager = manager;
        EngineManager.resetEngineGameTransactionStateForTest();
        EngineManager.beginEngineGameTransaction(
            manager, EngineGamePlans.harness(0, 1, false), null, true);
        AnalysisEngine gameTask = new AnalysisEngine(false);
        assertEquals(
            Leelaz.ExclusiveGtpLeaseAvailability.ENGINE_GAME,
            gameTask.getForegroundLeaseAvailability());

        EngineManager.resetEngineGameTransactionStateForTest();
        assertEquals(
            Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
            foreground.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));
        AnalysisEngine leasedTask = new AnalysisEngine(false);
        assertFalse(leasedTask.isLoaded());
        assertNull(leasedTask.process);
        assertEquals(
            Leelaz.ExclusiveGtpLeaseAvailability.EXISTING_LEASE,
            leasedTask.getForegroundLeaseAvailability());
      } finally {
        foreground.endExclusiveGtpSession();
        EngineManager.resetEngineGameTransactionStateForTest();
        Lizzie.engineManager = previousManager;
      }
    }
  }

  @Test
  void terminatedSharedForegroundAnalysisEngineIsNotRunning() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      Lizzie.leelaz = reusableForegroundEngine(true);
      AnalysisEngine engine = new AnalysisEngine(false);

      engine.normalQuit();

      assertFalse(engine.isRunning());
    }
  }

  @Test
  void reuseModeRejectsHumanSlAndContributionModes() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisReuseCurrentEngine = true;
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      Lizzie.frame.humanSlGame = allocate(HumanSlGameController.class);

      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.HUMAN_SL_GAME,
          new AnalysisEngine(false).getForegroundLeaseAvailability());

      Lizzie.frame.humanSlGame = null;
      Lizzie.frame.isContributing = true;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.APPLICATION_EXCLUSIVE_MODE,
          new AnalysisEngine(false).getForegroundLeaseAvailability());
    }
  }

  @Test
  void activeFlashLeaseRejectsContributionEntryWithoutSideEffects() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          foreground.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));
      TrackingLizzieFrame frame = (TrackingLizzieFrame) Lizzie.frame;

      frame.startContributeEngine();

      assertEquals(1, frame.foregroundReservationConflictCount);
      assertFalse(frame.isContributing);
      assertNull(frame.contributeEngine);
      assertTrue(foreground.hasExclusiveGtpLease());
      foreground.endExclusiveGtpSession();
    }
  }

  @Test
  void benchmarkSuppressionRejectsLocalContributionBeforeReservedLifecycle() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      TrackingLizzieFrame frame = (TrackingLizzieFrame) Lizzie.frame;
      Lizzie.config.contributeUseCommand = false;
      Lizzie.leelaz = null;
      boolean pauseAccepted = false;
      try {
        KataGoRuntimeHelper.BenchmarkPauseResult pause =
            KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();
        pauseAccepted = pause.accepted();

        frame.startContributeEngine();

        assertTrue(pause.accepted());
        assertEquals(1, frame.contributeBenchmarkConflictCount);
        assertEquals(0, frame.contributionStarts);
        assertFalse(frame.isContributing);
        assertNull(frame.contributeEngine);
      } finally {
        if (pauseAccepted) {
          KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(false);
        }
      }
    }
  }

  @Test
  void rejectedInteractiveGenmoveDoesNotLeaveInputCommandStateBehind() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE,
          foreground.beginExclusiveGtpSession(line -> {}, () -> {}, () -> {}));

      Lizzie.frame.genmove();

      assertFalse(foreground.isInputCommand);
      foreground.endExclusiveGtpSession();
    }
  }

  @Test
  void sharedBatchCompletionWaitsForForegroundRestoreBeforeFinalProgressAndCallback()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      TrackingWaitForAnalysis waitFrame = allocate(TrackingWaitForAnalysis.class);
      AtomicInteger completions = new AtomicInteger();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
      setField(AnalysisEngine.class, engine, "waitFrame", waitFrame);
      setIntField(AnalysisEngine.class, engine, "resultCount", 1);
      setIntField(AnalysisEngine.class, engine, "responseCount", 1);
      setField(
          AnalysisEngine.class,
          engine,
          "analyzeMap",
          new java.util.HashMap<Integer, BoardHistoryNode>(
              java.util.Map.of(1, Lizzie.board.getHistory().getCurrentHistoryNode())));
      List<ForegroundRestoreResult> restores = new CopyOnWriteArrayList<>();
      engine.setCompletionCallback(
          restore -> {
            restores.add(restore);
            completions.incrementAndGet();
          });

      invokeAnalysisEngineSetResult(engine);

      assertEquals(0, waitFrame.progressCalls);
      assertEquals(0, completions.get());
      assertTrue(engine.hasRequestLifecycleInProgress());

      foreground.completeRestore();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, waitFrame.progressCalls);
      assertEquals(1, waitFrame.currentMove);
      assertEquals(1, waitFrame.totalMoves);
      assertEquals(1, completions.get());
      assertEquals(List.of(ForegroundRestoreResult.SUCCEEDED), restores);
      assertFalse(engine.hasRequestLifecycleInProgress());
    }
  }

  @Test
  void onlyAutomaticQuickAnalysisSuppressesForegroundRestoreFailurePopup() {
    assertFalse(
        AnalysisEngine.shouldReportSharedForegroundRestoreFailure(
            AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS));
    assertTrue(
        AnalysisEngine.shouldReportSharedForegroundRestoreFailure(
            AnalysisResourceCoordinator.Purpose.USER_QUICK_ANALYSIS));
    assertTrue(
        AnalysisEngine.shouldReportSharedForegroundRestoreFailure(
            AnalysisResourceCoordinator.Purpose.WHOLE_GAME_ANALYSIS));
  }

  @Test
  void sharedBatchRestoreFailureDoesNotRunFinalProgressOrSuccessCallback() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "silentProgress", true);
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      TrackingWaitForAnalysis waitFrame = allocate(TrackingWaitForAnalysis.class);
      AtomicInteger completions = new AtomicInteger();
      Lizzie.frame.isBatchAnalysisMode = true;
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
      setField(AnalysisEngine.class, engine, "waitFrame", waitFrame);
      setIntField(AnalysisEngine.class, engine, "resultCount", 1);
      setField(
          AnalysisEngine.class,
          engine,
          "analyzeMap",
          new java.util.HashMap<Integer, BoardHistoryNode>(
              java.util.Map.of(1, Lizzie.board.getHistory().getCurrentHistoryNode())));
      engine.setCompletionCallback(restore -> completions.incrementAndGet());
      List<ForegroundRestoreResult> failures = new CopyOnWriteArrayList<>();
      engine.setFailureCallback(failures::add);

      invokeAnalysisEngineSetResult(engine);
      foreground.failRestore();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(0, waitFrame.progressCalls);
      assertEquals(0, completions.get());
      assertFalse(Lizzie.frame.isBatchAnalysisMode);
      assertEquals(List.of(ForegroundRestoreResult.FAILED), failures);
      AtomicInteger successfulQuits = new AtomicInteger();
      AtomicInteger failedQuits = new AtomicInteger();
      engine.normalQuit(successfulQuits::incrementAndGet, failedQuits::incrementAndGet);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertEquals(0, successfulQuits.get());
      assertEquals(1, failedQuits.get());
      assertEquals(List.of(ForegroundRestoreResult.FAILED), failures);
    }
  }

  @Test
  void sharedBatchFailureWaitsForForegroundRestoreBeforeFailureCallback() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      List<ForegroundRestoreResult> failures = new CopyOnWriteArrayList<>();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
      engine.setFailureCallback(failures::add);

      invokeAnalysisEngineFinishFailedRequestDispatch(engine);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertTrue(failures.isEmpty());
      assertTrue(engine.hasRequestLifecycleInProgress());

      foreground.completeRestore();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(List.of(ForegroundRestoreResult.SUCCEEDED), failures);
      assertFalse(engine.hasRequestLifecycleInProgress());
    }
  }

  @Test
  void sharedBatchRestoreFailureDeliversFailureCallbackExactlyOnce() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      List<ForegroundRestoreResult> failures = new CopyOnWriteArrayList<>();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
      engine.setFailureCallback(failures::add);

      invokeAnalysisEngineFinishFailedRequestDispatch(engine);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertTrue(failures.isEmpty());

      foreground.failRestore();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(List.of(ForegroundRestoreResult.FAILED), failures);
      AtomicInteger successfulQuits = new AtomicInteger();
      AtomicInteger failedQuits = new AtomicInteger();
      engine.normalQuit(successfulQuits::incrementAndGet, failedQuits::incrementAndGet);
      invokeAnalysisEngineFinishFailedRequestDispatch(engine);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertEquals(0, successfulQuits.get());
      assertEquals(1, failedQuits.get());
      assertEquals(List.of(ForegroundRestoreResult.FAILED), failures);
    }
  }

  @Test
  void abortedSharedRequestWaitsForRestoreAndDeliversOnlyOnce() throws Exception {
    for (ForegroundRestoreResult restore :
        List.of(ForegroundRestoreResult.SUCCEEDED, ForegroundRestoreResult.FAILED)) {
      try (TestEnvironment env = TestEnvironment.open()) {
        boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
        TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
        DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
        List<ForegroundRestoreResult> failures = new CopyOnWriteArrayList<>();
        AtomicInteger completions = new AtomicInteger();
        setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
        setField(
            AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
        engine.setCompletionCallback(result -> completions.incrementAndGet());
        engine.setFailureCallback(failures::add);
        Method abort = AnalysisEngine.class.getDeclaredMethod("finishAbortedAnalysis");
        abort.setAccessible(true);

        abort.invoke(engine);
        abort.invoke(engine);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        assertTrue(failures.isEmpty());
        assertTrue(engine.hasRequestLifecycleInProgress());
        if (restore == ForegroundRestoreResult.SUCCEEDED) {
          foreground.completeRestore();
        } else {
          foreground.failRestore();
        }
        abort.invoke(engine);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        assertEquals(List.of(restore), failures);
        assertEquals(0, completions.get());
        assertFalse(engine.hasRequestLifecycleInProgress());
      }
    }
  }

  @Test
  void newRequestResetsPreviouslyFailedRestoreOutcome() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
      List<ForegroundRestoreResult> failures = new CopyOnWriteArrayList<>();
      engine.setFailureCallback(failures::add);
      invokeAnalysisEngineFinishFailedRequestDispatch(engine);
      foreground.failRestore();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertEquals(List.of(ForegroundRestoreResult.FAILED), failures);

      List<ForegroundRestoreResult> completions = new CopyOnWriteArrayList<>();
      engine.setCompletionCallback(completions::add);
      engine.startRequest(-1, -1, false);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(List.of(ForegroundRestoreResult.NOT_REQUIRED), completions);
      assertEquals(List.of(ForegroundRestoreResult.FAILED), failures);
    }
  }

  @Test
  void normalQuitReportsActualRestoreResultAndRetainsItForLaterCleanup() throws Exception {
    for (ForegroundRestoreResult expected : ForegroundRestoreResult.values()) {
      try (TestEnvironment env = TestEnvironment.open()) {
        boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
        TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
        DeferredRestoreLeelaz foreground = null;
        if (expected != ForegroundRestoreResult.NOT_REQUIRED) {
          foreground = new DeferredRestoreLeelaz();
          setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
          setField(
              AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
        }
        List<ForegroundRestoreResult> results = new ArrayList<>();

        engine.normalQuitWithRestoreResult(results::add);

        if (foreground != null) {
          assertTrue(results.isEmpty());
          assertTrue(engine.hasRequestLifecycleInProgress());
          if (expected == ForegroundRestoreResult.SUCCEEDED) {
            foreground.completeRestore();
          } else {
            foreground.failRestore();
          }
        }
        assertEquals(List.of(expected), results);
        assertFalse(engine.hasRequestLifecycleInProgress());

        engine.normalQuitWithRestoreResult(results::add);

        assertEquals(List.of(expected, expected), results);
      }
    }
  }

  @Test
  void normalQuitContinuationJoinsAlreadyPendingSharedForegroundRestore() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      AtomicInteger continuations = new AtomicInteger();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
      setField(
          AnalysisEngine.class,
          engine,
          "analyzeMap",
          new java.util.HashMap<Integer, BoardHistoryNode>(
              java.util.Map.of(1, Lizzie.board.getHistory().getCurrentHistoryNode())));

      invokeAnalysisEngineSetResult(engine);
      engine.normalQuit(continuations::incrementAndGet);

      assertEquals(0, continuations.get());
      foreground.completeRestore();
      assertEquals(1, continuations.get());
    }
  }

  @Test
  void normalQuitContinuationJoinsAlreadyPendingSharedForegroundRestoreFailure() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      AtomicInteger completions = new AtomicInteger();
      AtomicInteger failures = new AtomicInteger();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));
      setField(
          AnalysisEngine.class,
          engine,
          "analyzeMap",
          new java.util.HashMap<Integer, BoardHistoryNode>(
              java.util.Map.of(1, Lizzie.board.getHistory().getCurrentHistoryNode())));

      invokeAnalysisEngineSetResult(engine);
      engine.normalQuit(completions::incrementAndGet, failures::incrementAndGet);

      assertEquals(0, completions.get());
      assertEquals(0, failures.get());
      foreground.failRestore();
      assertEquals(0, completions.get());
      assertEquals(1, failures.get());
    }
  }

  @Test
  void normalQuitContinuationWaitsForSharedForegroundRestore() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      AtomicInteger continuations = new AtomicInteger();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));

      engine.normalQuit(continuations::incrementAndGet);

      assertEquals(0, continuations.get());
      foreground.completeRestore();
      assertEquals(1, continuations.get());
    }
  }

  @Test
  void normalQuitContinuationAlsoRunsOnceWhenSharedRestoreFails() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      DeferredRestoreLeelaz foreground = new DeferredRestoreLeelaz();
      AtomicInteger continuations = new AtomicInteger();
      setField(AnalysisEngine.class, engine, "sharedForegroundEngine", foreground);
      setField(AnalysisEngine.class, engine, "sharedForegroundLease", foregroundLease(foreground));

      engine.normalQuit(continuations::incrementAndGet);

      assertEquals(0, continuations.get());
      foreground.failRestore();
      assertEquals(1, continuations.get());
    }
  }

  @Test
  void normalQuitPermanentlyRejectsAWholeGameRestart() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = true;
      AnalysisEngine engine = new AnalysisEngine(false);

      engine.normalQuit();
      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, false);

      assertEquals(-1, requested);
      assertFalse(foreground.hasExclusiveGtpLease());
    }
  }

  @Test
  void foregroundLeaseStopFailureEndsRequestWithoutRunningSuccessContinuation() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = true;
      Lizzie.frame.isBatchAnalysisMode = true;
      AtomicInteger successfulContinuations = new AtomicInteger();
      AnalysisEngine engine = new AnalysisEngine(false);
      setField(AnalysisEngine.class, engine, "silentProgress", true);
      engine.setCompletionCallback(restore -> successfulContinuations.incrementAndGet());

      engine.startRequest(1, -1, false);
      assertTrue(engine.isAnalysisInProgress());
      Lizzie.leelaz = null;
      int stopCommandId = getExclusiveStopCommandId(foreground);
      assertTrue(dispatchExclusiveLine(foreground, "?" + stopCommandId + " cannot stop"));

      assertFalse(engine.isAnalysisInProgress());
      assertEquals(0, successfulContinuations.get());
      assertFalse(Lizzie.frame.isBatchAnalysisMode);
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, engine.getForegroundLeaseAvailability());
    }
  }

  @Test
  void foregroundLeaseSetupSendFailureEndsRequestWithoutRunningSuccessContinuation()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      Leelaz foreground = reusableForegroundEngine(true);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = true;
      Lizzie.frame.isBatchAnalysisMode = true;
      AtomicInteger successfulContinuations = new AtomicInteger();
      AnalysisEngine engine = new AnalysisEngine(false);
      setField(AnalysisEngine.class, engine, "silentProgress", true);
      engine.setCompletionCallback(restore -> successfulContinuations.incrementAndGet());

      engine.startRequest(1, -1, false);
      int stopCommandId = getExclusiveStopCommandId(foreground);
      foreground.installCommandOutputForTest(
          new BufferedOutputStream(
              new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                  throw new IOException("simulated setup send failure");
                }
              }));
      Lizzie.leelaz = null;
      ResourceBundle previousResourceBundle = Lizzie.resourceBundle;
      Lizzie.resourceBundle = null;
      try {
        dispatchExclusiveLine(foreground, "=" + stopCommandId);
        assertTrue(dispatchExclusiveLine(foreground, ""));
      } finally {
        Lizzie.resourceBundle = previousResourceBundle;
      }

      assertFalse(engine.isAnalysisInProgress());
      assertEquals(0, successfulContinuations.get());
      assertFalse(Lizzie.frame.isBatchAnalysisMode);
      assertEquals(
          Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, engine.getForegroundLeaseAvailability());
    }
  }

  @Test
  void automaticZhiziPreparationRefillsBoundedWindowAndWaitsForEveryReply() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true)) {
      fixture.start();
      fixture.reachReplay();
      List<String> replay = fixture.plays();
      assertEquals(8, replay.size());
      BoardHistoryNode browsing = fixture.history.getStart().next().get();
      fixture.history.setHead(browsing);
      fixture.ack(replay.get(7));
      assertEquals(9, fixture.plays().size());
      fixture.ack(replay.get(7));
      assertEquals(9, fixture.plays().size(), "duplicate must not free another slot");
      fixture.reply("=199999999 unrelated");
      assertEquals(9, fixture.plays().size());
      for (int i = 0; i < 7; i++) {
        fixture.ack(replay.get(i));
        assertTrue(fixture.plays().size() - (i + 2) <= 8);
      }
      replay = fixture.plays();
      assertEquals(12, replay.size());
      for (int i = 8; i < 11; i++) fixture.ack(replay.get(i));
      assertFalse(fixture.hasAnalyze());
      assertEquals(0, fixture.target.getData().getPlayouts());
      fixture.ack(replay.get(11));
      assertTrue(fixture.hasAnalyze());
      String analyze = fixture.last();
      fixture.reply("=" + fixture.id(analyze));
      fixture.ack(replay.get(0));
      assertTrue(fixture.engine.isAnalysisInProgress(), "late setup boundary is not analyze completion");
      fixture.reply(remoteGtpInfoLine(16, 61.0, 3.5));
      String stop = fixture.last();
      assertTrue(stop.endsWith(" stop"));
      fixture.reply("");
      fixture.ack(stop);
      assertEquals(16, fixture.target.getData().getPlayouts());
      assertSame(browsing, fixture.history.getCurrentHistoryNode());
    }
  }

  @Test
  void preparationErrorStopsRefillAndLateSuccessCannotConfirmFinalStop() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true)) {
      List<ForegroundRestoreResult> failures = new CopyOnWriteArrayList<>();
      fixture.engine.setFailureCallback(failures::add);
      fixture.start();
      fixture.reachReplay();
      List<String> pending = fixture.plays();
      fixture.reply("?" + fixture.id(pending.get(3)) + " illegal move");
      assertTrue(fixture.last().endsWith(" stop"));
      for (String command : pending) fixture.ack(command);
      assertEquals(8, fixture.plays().size());
      assertFalse(fixture.hasAnalyze());
      assertEquals(0, fixture.target.getData().getPlayouts());
      assertTrue(fixture.foreground.hasExclusiveGtpLease(), "old setup replies cannot settle final stop");
      assertTrue(failures.isEmpty());
      fixture.reply("?" + fixture.id(fixture.last()) + " cannot stop");
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertEquals(List.of(ForegroundRestoreResult.FAILED), failures);
    }
  }

  @Test
  void preparationSendFailureDoesNotAnalyzeOrPublishAndReportsActualRestoreFailure() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true)) {
      List<ForegroundRestoreResult> failures = new CopyOnWriteArrayList<>();
      fixture.engine.setFailureCallback(failures::add);
      fixture.start();
      fixture.reachReplay();
      List<String> pending = fixture.plays();
      fixture.foreground.installCommandOutputForTest(new OutputStream() {
        @Override public void write(int value) throws IOException { throw new IOException("controlled pipeline write failure"); }
      });
      fixture.ack(pending.get(0));
      for (String command : pending) fixture.ack(command);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertEquals(8, fixture.plays().size());
      assertFalse(fixture.hasAnalyze());
      assertEquals(0, fixture.target.getData().getPlayouts());
      assertEquals(List.of(ForegroundRestoreResult.FAILED), failures);
    }
  }

  @Test
  void missingPreparationReplyKeepsItsDeadlineDespiteLaterSuccess() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true);
        ControlledSetupScheduler clock = new ControlledSetupScheduler()) {
      setField(AnalysisEngine.class, fixture.engine, "remoteGtpSetupAckTimeoutExecutor", clock);
      fixture.start();
      fixture.reachReplay();
      List<String> pending = fixture.plays();
      long initialDelay = clock.delays.get(clock.delays.size() - 1);
      for (int i = 1; i < 8; i++) fixture.ack(pending.get(i));
      assertFalse(fixture.hasAnalyze());
      assertTrue(clock.delays.get(clock.delays.size() - 1) <= initialDelay,
          "successful later replies cannot extend the oldest obligation");
      clock.callbacks.get(clock.callbacks.size() - 1).run();
      assertFalse(fixture.engine.isAnalysisInProgress());
      int sent = fixture.commands().size();
      for (String command : fixture.plays()) fixture.ack(command);
      assertEquals(sent, fixture.commands().size());
      assertEquals(0, fixture.target.getData().getPlayouts());
      assertFalse(fixture.hasAnalyze());
    }
  }

  @Test
  void retiredAutomaticUseCannotRefillAndOldTimeoutCannotStopManualSuccessor() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true);
        ControlledSetupScheduler clock = new ControlledSetupScheduler()) {
      setField(AnalysisEngine.class, fixture.engine, "remoteGtpSetupAckTimeoutExecutor", clock);
      fixture.start();
      fixture.reachReplay();
      List<String> pending = fixture.plays();
      List<Runnable> oldTimeouts = List.copyOf(clock.callbacks);
      fixture.engine.retireAutomaticQuickAnalysisRequest(fixture.use);
      int sent = fixture.commands().size();
      for (String command : pending) fixture.ack(command);
      for (Runnable timeout : oldTimeouts) timeout.run();
      assertEquals(sent, fixture.commands().size());
      assertFalse(fixture.hasAnalyze());
      assertEquals(1, fixture.engine.startRequestMissingMainline(false));
      String manualFirst = fixture.last();
      fixture.engine.retireAutomaticQuickAnalysisRequest(fixture.use);
      for (Runnable timeout : oldTimeouts) timeout.run();
      for (String command : pending) fixture.ack(command);
      assertEquals(manualFirst, fixture.last());
      while (!fixture.last().contains(" play ")) fixture.ack(fixture.last());
      assertEquals(9, fixture.plays().size(), "manual use must replay serially, not inherit automatic window");
      fixture.ack(fixture.last());
      assertEquals(10, fixture.plays().size());
      assertTrue(fixture.engine.isAnalysisInProgress());
    }
  }

  @Test
  void localForegroundReuseAndSilentManualZhiziRemainSerial() throws Exception {
    for (boolean zhizi : List.of(false, true)) {
      try (PreparationFixture fixture = new PreparationFixture(zhizi)) {
        if (zhizi) assertEquals(1, fixture.engine.startRequestMissingMainline(false));
        else fixture.start();
        fixture.reachReplay();
        assertEquals(1, fixture.plays().size());
        fixture.ack(fixture.last());
        assertEquals(2, fixture.plays().size());
        assertFalse(fixture.hasAnalyze());
      }
    }
  }

  @Test
  void anotherRemoteBackendDoesNotGainWindowFromZhiziDisplayName() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(false)) {
      Class<?> transportType = featurecat.lizzie.analysis.remote.EngineTransport.class;
      Object transport = java.lang.reflect.Proxy.newProxyInstance(transportType.getClassLoader(),
          new Class<?>[] {transportType}, (proxy, method, args) -> {
            if (method.getName().equals("description")) return "Zhizi";
            if (method.getReturnType() == boolean.class) return true;
            return null;
          });
      Object reader = getField(Leelaz.class, fixture.foreground, "readerStreamBinding");
      setField(reader.getClass(), reader, "remoteTransport", transport);
      fixture.start();
      fixture.reachReplay();
      assertEquals(1, fixture.plays().size());
      fixture.ack(fixture.last());
      assertEquals(2, fixture.plays().size());
      assertFalse(fixture.hasAnalyze());
    }
  }

  @Test
  void staticAnchorMustSucceedBeforeRealMovesAndDummyPassNeverReplays() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true,
        "(;SZ[19]AB[aa][ca]PL[W];W[ba];B[];W[];B[dd];W[pp];B[dp];W[pd];B[cf];W[qc];B[fc];W[qn];B[cn];W[nq])")) {
      BoardHistoryNode dummy = fixture.history.getStart().next().get().next().get().next().get();
      dummy.getData().dummy = true;
      fixture.start();
      while (!fixture.last().contains(" set_position")) fixture.ack(fixture.last());
      assertTrue(fixture.plays().isEmpty());
      assertTrue(fixture.last().contains(" B A19"));
      assertTrue(fixture.last().contains(" B C19"));
      String anchor = fixture.last();
      fixture.reply("=199999999");
      assertTrue(fixture.plays().isEmpty());
      fixture.ack(anchor);
      assertEquals(8, fixture.plays().size());
      List<String> plays = fixture.plays().stream().map(s -> s.substring(s.indexOf(' ') + 1)).toList();
      assertEquals(List.of("play W B19", "play B pass", "play B D16", "play W Q4",
          "play B D4", "play W Q16", "play B C14", "play W R17"), plays);
      assertFalse(fixture.hasAnalyze());
    }
  }

  @Test
  void gameAndForegroundReplacementCannotRefillOldAutomaticWindow() throws Exception {
    for (boolean replaceGame : List.of(true, false)) {
      try (PreparationFixture fixture = new PreparationFixture(true)) {
        fixture.start();
        fixture.reachReplay();
        if (replaceGame) boardWithHistory(new BoardHistoryList(BoardData.empty(19, 19)));
        else Lizzie.leelaz = reusableForegroundEngine(true);
        for (String command : fixture.plays()) fixture.ack(command);
        assertEquals(8, fixture.plays().size());
        assertFalse(fixture.hasAnalyze());
        assertEquals(0, fixture.target.getData().getPlayouts());
        assertEquals(120, fixture.history.getStart().next().get().getData().getPlayouts());
      }
    }
  }

  @Test
  void synchronousPreparationRepliesCannotBeatRegistrationOrSendAnalyzeTwice() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true)) {
      fixture.start();
      fixture.reachReplay();
      List<String> pending = fixture.plays();
      fixture.foreground.installCommandOutputForTest(new ByteArrayOutputStream() {
        @Override public synchronized void flush() throws IOException {
          String command = toString(StandardCharsets.UTF_8).trim();
          reset();
          fixture.output.write((command + "\n").getBytes(StandardCharsets.UTF_8));
          if (command.contains(" play ")) {
            try { fixture.ack(command); } catch (Exception e) { throw new IOException(e); }
          }
        }
      });
      for (String command : pending) fixture.ack(command);
      assertEquals(12, fixture.plays().size());
      assertEquals(1, fixture.commands().stream().filter(s -> s.contains(" kata-analyze ")).count());
    }
  }

  @Test
  void retirementDoesNotWaitForAnAlreadyPermittedPhysicalWrite() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true)) {
      fixture.start();
      fixture.reachReplay();
      String acknowledged = fixture.plays().get(0);
      var entered = new java.util.concurrent.CountDownLatch(1);
      var proceed = new java.util.concurrent.CountDownLatch(1);
      var retired = new java.util.concurrent.CountDownLatch(1);
      var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
      fixture.foreground.installCommandOutputForTest(new ByteArrayOutputStream() {
        @Override public synchronized void flush() throws IOException {
          entered.countDown();
          try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new IOException("write gate not released"); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
          fixture.output.write(toByteArray());
          reset();
        }
      });
      Thread writer = new Thread(() -> {
        try { fixture.ack(acknowledged); } catch (Throwable e) { failure.set(e); }
      });
      writer.start();
      Thread retirement = new Thread(() -> {
        fixture.engine.retireAutomaticQuickAnalysisRequest(fixture.use);
        retired.countDown();
      });
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        retirement.start();
        assertTrue(retired.await(5, TimeUnit.SECONDS), "retirement must not acquire the blocked output/request monitor");
      } finally {
        proceed.countDown();
        writer.join(5000);
        retirement.join(5000);
      }
      assertFalse(writer.isAlive());
      assertNull(failure.get());
      for (String command : fixture.plays()) fixture.ack(command);
      assertEquals(9, fixture.plays().size(), "only the already permitted write may finish");
      assertFalse(fixture.hasAnalyze());
    }
  }

  @Test
  void readerReplacementRejectsOldWindowResponsesWithoutWritingToNewReader() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true)) {
      fixture.start();
      fixture.reachReplay();
      Object oldReader = getField(Leelaz.class, fixture.foreground, "readerStreamBinding");
      var replacementOutput = new ByteArrayOutputStream();
      fixture.foreground.installFreshCommandOutputForTest(replacementOutput);
      Method dispatch = Leelaz.class.getDeclaredMethod("dispatchExclusiveGtpLine", oldReader.getClass(), String.class);
      dispatch.setAccessible(true);
      for (String command : fixture.plays()) {
        assertFalse((boolean) dispatch.invoke(fixture.foreground, oldReader, "=" + fixture.id(command)));
        assertFalse((boolean) dispatch.invoke(fixture.foreground, oldReader, ""));
      }
      assertEquals("", replacementOutput.toString(StandardCharsets.UTF_8));
      assertFalse(fixture.hasAnalyze());
      assertEquals(0, fixture.target.getData().getPlayouts());
    }
  }

  @Test
  void replacementAutomaticRequestIgnoresOldWindowAndTimeouts() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true);
        ControlledSetupScheduler clock = new ControlledSetupScheduler()) {
      setField(AnalysisEngine.class, fixture.engine, "remoteGtpSetupAckTimeoutExecutor", clock);
      fixture.start();
      fixture.reachReplay();
      List<String> old = fixture.plays();
      List<Runnable> oldTimeouts = List.copyOf(clock.callbacks);
      assertEquals(1, fixture.engine.startAutomaticQuickAnalysisRequest(new Object()));
      String first = fixture.last();
      for (String command : old) fixture.ack(command);
      for (Runnable callback : oldTimeouts) callback.run();
      assertEquals(first, fixture.last());
      while (!fixture.last().contains(" play ")) fixture.ack(fixture.last());
      assertEquals(16, fixture.plays().size());
      assertFalse(fixture.hasAnalyze());
      assertTrue(fixture.engine.isAnalysisInProgress());
    }
  }

  private static final class ControlledSetupScheduler extends java.util.concurrent.ScheduledThreadPoolExecutor implements AutoCloseable {
    final List<Runnable> callbacks = new ArrayList<>();
    final List<Long> delays = new ArrayList<>();
    ControlledSetupScheduler() { super(1, runnable -> {
      Thread thread = new Thread(runnable, "controlled-preparation-timeout");
      thread.setDaemon(true);
      return thread;
    }); }
    @Override public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      callbacks.add(command);
      delays.add(unit.toNanos(delay));
      return super.schedule(() -> {}, 1, TimeUnit.DAYS);
    }
    @Override public void close() { shutdownNow(); }
  }

  private void installZhiziOutput(Leelaz foreground, ByteArrayOutputStream output) throws Exception {
    var transport = new featurecat.lizzie.analysis.remote.ZhiziGtpTransport(null, "", "--kata-weight 10b512t");
    createdZhiziTransports.add(transport);
    Class<?> emitterType = Class.forName("featurecat.lizzie.analysis.remote.ZhiziGtpTransport$CommandEmitter");
    Object emitter = java.lang.reflect.Proxy.newProxyInstance(emitterType.getClassLoader(),
        new Class<?>[] {emitterType}, (proxy, method, args) -> {
          if (method.getName().equals("isConnected")) return true;
          if (method.getName().equals("emit")) {
            String command = (String) args[0];
            assertEquals(1, command.lines().count(), "each socket payload must contain one command");
            output.write(command.getBytes(StandardCharsets.UTF_8));
          }
          return null;
        });
    setField(transport.stdin().getClass(), transport.stdin(), "emitter", emitter);
    foreground.installCommandOutputForTest(transport.stdin());
    Object reader = getField(Leelaz.class, foreground, "readerStreamBinding");
    setField(reader.getClass(), reader, "remoteTransport", transport);
  }

  private final class PreparationFixture implements AutoCloseable {
    final TestEnvironment environment;
    final GtpConsolePane oldConsole;
    final BoardHistoryList history;
    final BoardHistoryNode target;
    final Leelaz foreground;
    final ByteArrayOutputStream output;
    final AnalysisEngine engine;
    final Object use = new Object();

    PreparationFixture(boolean zhizi) throws Exception {
      this(zhizi, "(;SZ[19];B[dd];W[pp];B[dp];W[pd];B[cf];W[qc];B[fc];W[qn];B[cn];W[nq];B[jd];W[jp])");
    }

    PreparationFixture(boolean zhizi, String sgf) throws Exception {
      environment = TestEnvironment.open();
      oldConsole = Lizzie.gtpConsole;
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      history = SGFParser.parseSgf(sgf, true);
      boardWithHistory(history);
      BoardHistoryNode node = history.getStart();
      while (node.next().isPresent()) {
        node = node.next().get();
        if (node.next().isPresent()) {
          node.getData().setPlayouts(120);
          node.getData().engineName = "cached-analysis";
          node.getData().analysisHeaderSlots = 3;
        }
      }
      target = node;
      history.setHead(history.getStart());
      foreground = reusableForegroundEngine(true);
      output = installLeelazOutput(foreground);
      Lizzie.leelaz = foreground;
      if (zhizi) {
        installZhiziOutput(foreground, output);
      }
      Lizzie.config.analysisReuseCurrentEngine = true;
      engine = new AnalysisEngine(true);
    }

    void start() { assertEquals(1, engine.startAutomaticQuickAnalysisRequest(use)); }
    List<String> commands() { return output.toString(StandardCharsets.UTF_8).lines().toList(); }
    List<String> plays() { return commands().stream().filter(s -> s.contains(" play ")).toList(); }
    String last() { return commands().get(commands().size() - 1); }
    String id(String command) { return command.substring(0, command.indexOf(' ')); }
    boolean hasAnalyze() { return commands().stream().anyMatch(s -> s.contains(" kata-analyze ")); }
    void reply(String line) throws Exception { dispatchExclusiveLine(foreground, line); }
    void ack(String command) throws Exception {
      reply("=" + id(command) + (command.endsWith("kata-get-rules")
          ? " {\"koRule\":\"POSITIONAL\",\"scoringRule\":\"AREA\",\"taxRule\":\"NONE\"}"
          : command.endsWith("list_commands") ? " kata-analyze stop" : ""));
      reply("");
    }
    void reachReplay() throws Exception {
      for (int i = 0; i < 10 && plays().isEmpty(); i++) {
        assertFalse(hasAnalyze());
        ack(last());
      }
      assertFalse(plays().isEmpty());
    }
    void finiteReady() throws Exception {
      ack(last());
      String query = last();
      assertTrue(query.endsWith(" list_commands"));
      reply("=" + id(query) + " kata-search_analyze_cancellable kata-get-param kata-set-param stop");
      reply("");
      List<String> captures = commands().stream().filter(s -> s.contains(" kata-get-param ")).toList();
      assertEquals(4, captures.size());
      for (String command : captures) {
        reply("=" + id(command) + (command.endsWith("ponderingEnabled") ? " true" : " 1234"));
        reply("");
      }
      List<String> installs = commands().stream().filter(s -> s.contains(" kata-set-param ")).toList();
      assertEquals(4, installs.size());
      for (String command : installs) ack(command);
    }
    void finishPreparation() throws Exception {
      java.util.Set<String> acknowledged = new java.util.HashSet<>();
      for (int round = 0; round < 30; round++) {
        if (last().contains(" kata-search_analyze_cancellable ")) return;
        for (String command : List.copyOf(commands())) {
          if ((command.contains(" kata-get-rules") || command.contains(" boardsize ")
              || command.contains(" clear_board") || command.contains(" komi ")
              || command.contains(" kata-set-rules ") || command.contains(" play ")
              || command.contains(" set_position")) && acknowledged.add(command)) ack(command);
        }
      }
      throw new AssertionError("finite search was not dispatched");
    }
    @Override public void close() throws Exception {
      engine.requestShutdown();
      engine.normalQuit();
      closeExclusiveSessionForTest(foreground);
      Lizzie.gtpConsole = oldConsole;
      environment.close();
    }
  }

  @Test
  void automaticZhiziSelectsFiniteSearchOnlyAfterCompleteCapabilities() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true)) {
      fixture.start();
      fixture.ack(fixture.last());
      String query = fixture.last();
      assertTrue(query.endsWith(" list_commands"));
      fixture.reply("=" + fixture.id(query) + " kata-search_analyze_cancellable");
      fixture.reply("kata-get-param");
      fixture.reply("kata-set-param");
      fixture.reply("stop");
      assertEquals(query, fixture.last());
      fixture.reply("");
      assertEquals(4, fixture.commands().stream().filter(s -> s.contains(" kata-get-param ")).count());
      assertFalse(fixture.commands().stream().anyMatch(s -> s.contains(" kata-set-param ")));
    }
  }

  @Test
  void automaticRequestAcceptsMultipleNodesAndReturnsLease() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true, "(;SZ[19];B[dd];W[pp])")) {
      BoardHistoryNode first = fixture.history.getStart().next().orElseThrow();
      first.getData().setPlayouts(0);
      List<ForegroundRestoreResult> completed = new CopyOnWriteArrayList<>();
      fixture.engine.setCompletionCallback(completed::add);
      assertEquals(2, fixture.engine.startAutomaticQuickAnalysisRequest(fixture.use));
      fixture.finiteReady();
      java.util.Set<String> acknowledged = new java.util.HashSet<>(fixture.commands());
      acknowledged.remove(fixture.last());
      for (BoardHistoryNode target : List.of(first, fixture.target)) {
        for (int round = 0; round < 20; round++) {
          if (fixture.last().contains(" kata-search_analyze_cancellable ")) break;
          for (String command : List.copyOf(fixture.commands())) {
            if (acknowledged.add(command)) fixture.ack(command);
          }
        }
        String search = fixture.last();
        assertTrue(search.contains(" kata-search_analyze_cancellable "));
        acknowledged.add(search);
        fixture.reply("=" + fixture.id(search));
        fixture.reply(remoteGtpInfoLine(3, 17, 61.0, 3.5));
        assertEquals(0, target.getData().getPlayouts(), "info must remain pending");
        assertEquals(search, fixture.last());
        fixture.reply("play pass");
        fixture.reply("");
        assertEquals(17, target.getData().getPlayouts());
      }
      String finalStop = fixture.last();
      assertTrue(finalStop.endsWith(" stop"));
      assertTrue(completed.isEmpty());
      int cursor = fixture.commands().size();
      fixture.ack(finalStop);
      int restoreStart = cursor;
      fixture.history.setHead(first);
      Method response = Leelaz.class.getDeclaredMethod("runPendingResponseHandlerForLine", String.class);
      response.setAccessible(true);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (completed.isEmpty() && System.nanoTime() < deadline) {
        List<String> commands = fixture.commands();
        while (cursor < commands.size()) {
          String command = commands.get(cursor++);
          String token = command.split(" ", 2)[0];
          String reply = "=" + (token.matches("[0-9]+") ? token : "");
          if (!dispatchExclusiveLine(fixture.foreground, reply)) response.invoke(fixture.foreground, reply);
          dispatchExclusiveLine(fixture.foreground, "");
        }
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        Thread.sleep(5);
      }
      assertEquals(List.of(ForegroundRestoreResult.SUCCEEDED), completed, fixture.commands().toString());
      assertFalse(fixture.foreground.hasForegroundAnalysisLeaseWorkInProgress());
      assertEquals(2, fixture.commands().stream().filter(s -> s.endsWith(" stop")).count());
      assertSame(first, fixture.history.getCurrentHistoryNode());
      assertTrue(fixture.commands().subList(restoreStart, fixture.commands().size()).stream()
          .anyMatch(s -> s.endsWith(" play B D16")), fixture.commands().toString());
    }
  }

  @Test
  void retiringDuringParameterWriteRestoresThePermittedWriteWithoutSendingTheRest() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true)) {
      fixture.start();
      fixture.ack(fixture.last());
      fixture.reply("=" + fixture.id(fixture.last())
          + " kata-search_analyze_cancellable kata-get-param kata-set-param stop");
      fixture.reply("");
      List<String> captures = fixture.commands().stream().filter(s -> s.contains(" kata-get-param ")).toList();
      for (int i = 0; i < 3; i++) {
        fixture.reply("=" + fixture.id(captures.get(i)) + " 1234");
        fixture.reply("");
      }
      var entered = new java.util.concurrent.CountDownLatch(1);
      var proceed = new java.util.concurrent.CountDownLatch(1);
      var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
      fixture.foreground.installCommandOutputForTest(new ByteArrayOutputStream() {
        @Override public synchronized void flush() throws IOException {
          entered.countDown();
          try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new IOException("write gate not released"); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
          fixture.output.write(toByteArray());
          reset();
        }
      });
      Thread writer = new Thread(() -> {
        try {
          fixture.reply("=" + fixture.id(captures.get(3)) + " true");
          fixture.reply("");
        } catch (Throwable e) { failure.set(e); }
      });
      writer.start();
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        fixture.engine.retireAutomaticQuickAnalysisRequest(fixture.use);
      } finally {
        proceed.countDown();
        writer.join(5000);
      }
      assertFalse(writer.isAlive());
      assertNull(failure.get());
      assertEquals(1, fixture.commands().stream().filter(s -> s.contains(" kata-set-param ")).count());
      fixture.engine.normalQuit();
      fixture.ack(fixture.last());
      List<String> writes = fixture.commands().stream().filter(s -> s.contains(" kata-set-param ")).toList();
      assertEquals(2, writes.size());
      assertTrue(writes.get(1).endsWith(" maxVisits 1234"));
      assertTrue(fixture.plays().isEmpty());
    }
  }

  @Test
  void replacedReaderCannotReceiveOldFiniteParameterRestoration() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true)) {
      fixture.start();
      fixture.finiteReady();
      Object oldReader = getField(Leelaz.class, fixture.foreground, "readerStreamBinding");
      var replacementOutput = new ByteArrayOutputStream();
      fixture.foreground.installFreshCommandOutputForTest(replacementOutput);
      fixture.engine.normalQuit();
      Method dispatch = Leelaz.class.getDeclaredMethod("dispatchExclusiveGtpLine", oldReader.getClass(), String.class);
      dispatch.setAccessible(true);
      assertFalse((boolean) dispatch.invoke(fixture.foreground, oldReader, "=800000001"));
      assertEquals("", replacementOutput.toString(StandardCharsets.UTF_8));
      assertEquals(0, fixture.target.getData().getPlayouts());
    }
  }

  @Test
  void capabilityErrorsFailWithoutFallbackOrParameterWrites() throws Exception {
    for (String response : List.of("error", "empty", "malformed", "duplicate")) {
      try (PreparationFixture fixture = new PreparationFixture(true)) {
        fixture.start();
        fixture.ack(fixture.last());
        String query = fixture.last();
        fixture.reply((response.equals("error") ? "?" : "=") + fixture.id(query)
            + (response.equals("malformed") ? " {broken}" : ""));
        if (response.equals("duplicate")) fixture.reply("=" + fixture.id(query));
        fixture.reply("");
        assertFalse(fixture.engine.isAnalysisInProgress(), response);
        assertFalse(fixture.commands().stream().anyMatch(s -> s.contains(" kata-get-param ")
            || s.contains(" kata-set-param ") || s.contains(" kata-analyze ")));
      }
    }
  }

  @Test
  void finitePreparationDeadlinesDoNotRenewAfterLaterReplies() throws Exception {
    for (int stage = 0; stage < 3; stage++) {
      try (PreparationFixture fixture = new PreparationFixture(true);
          ControlledSetupScheduler clock = new ControlledSetupScheduler()) {
        setField(AnalysisEngine.class, fixture.engine, "remoteGtpSetupAckTimeoutExecutor", clock);
        fixture.start();
        fixture.ack(fixture.last());
        if (stage > 0) {
          fixture.reply("=" + fixture.id(fixture.last())
              + " kata-search_analyze_cancellable kata-get-param kata-set-param stop");
          fixture.reply("");
          List<String> captures = fixture.commands().stream()
              .filter(s -> s.contains(" kata-get-param ")).toList();
          for (int i = stage == 1 ? 1 : 0; i < 4; i++) {
            fixture.reply("=" + fixture.id(captures.get(i)) + (i == 3 ? " true" : " 1234"));
            fixture.reply("");
          }
        }
        int scheduled = clock.callbacks.size();
        Runnable deadline = clock.callbacks.get(scheduled - 1);
        if (stage == 2) {
          List<String> installs = fixture.commands().stream()
              .filter(s -> s.contains(" kata-set-param ")).toList();
          for (int i = 1; i < 4; i++) fixture.ack(installs.get(i));
        }
        assertEquals(scheduled, clock.callbacks.size());
        deadline.run();
        assertFalse(fixture.engine.isAnalysisInProgress());
        assertEquals(0, fixture.target.getData().getPlayouts());
        assertTrue(fixture.plays().isEmpty());
      }
    }
  }

  @Test
  void parameterStagesRequireEveryCompleteReplyAndRetirementPreservesObligations() throws Exception {
    for (boolean install : List.of(false, true)) {
      try (PreparationFixture fixture = new PreparationFixture(true);
          ControlledSetupScheduler clock = new ControlledSetupScheduler()) {
        setField(AnalysisEngine.class, fixture.engine, "remoteGtpSetupAckTimeoutExecutor", clock);
        fixture.start();
        fixture.ack(fixture.last());
        fixture.reply("=" + fixture.id(fixture.last())
            + " kata-search_analyze_cancellable kata-get-param kata-set-param stop");
        fixture.reply("");
        List<String> captures = fixture.commands().stream().filter(s -> s.contains(" kata-get-param ")).toList();
        assertEquals(4, captures.size());
        for (int i = 1; i < 4; i++) {
          fixture.reply("=" + fixture.id(captures.get(i)) + (i == 3 ? " true" : " 1234"));
          fixture.reply("");
        }
        assertFalse(fixture.commands().stream().anyMatch(s -> s.contains(" kata-set-param ")));
        if (install) {
          fixture.reply("=" + fixture.id(captures.get(0)) + " 1234");
          assertFalse(fixture.commands().stream().anyMatch(s -> s.contains(" kata-set-param ")));
          fixture.reply("");
          List<String> writes = fixture.commands().stream().filter(s -> s.contains(" kata-set-param ")).toList();
          assertEquals(4, writes.size());
          for (int i = 1; i < 4; i++) fixture.ack(writes.get(i));
        }
        assertTrue(fixture.plays().isEmpty());
        fixture.engine.retireAutomaticQuickAnalysisRequest(fixture.use);
        for (Runnable callback : List.copyOf(clock.callbacks)) callback.run();
        fixture.engine.normalQuit();
        if (install) fixture.ack(fixture.last());
        long parameterWrites = fixture.commands().stream().filter(s -> s.contains(" kata-set-param ")).count();
        assertEquals(install ? 8 : 0, parameterWrites);
        assertEquals(0, fixture.target.getData().getPlayouts());
      }
    }
  }

  @Test
  void finiteTerminalRejectsMissingRootInsufficientBudgetAndCancelled() throws Exception {
    for (String variant : List.of("missing", "legacy", "low", "cancelled", "duplicate", "error")) {
      try (PreparationFixture fixture = new PreparationFixture(true)) {
        fixture.start();
        fixture.finiteReady();
        fixture.finishPreparation();
        String search = fixture.last();
        fixture.reply("=" + fixture.id(search));
        if (!variant.equals("missing")) fixture.reply(remoteGtpInfoLine(3, 61.0, 3.5)
            + (variant.equals("legacy") ? "" : " rootInfo visits "
                + (variant.equals("low") ? "15" : "17") + " winrate 0.61 scoreLead 3.5"));
        if (variant.equals("duplicate")) fixture.reply("=" + fixture.id(search));
        else if (variant.equals("error")) fixture.reply("?" + fixture.id(search) + " failed");
        else {
          fixture.reply(variant.equals("cancelled") ? "play cancelled" : "play Q16");
          fixture.reply("");
        }
        assertEquals(0, fixture.target.getData().getPlayouts(), variant);
        assertFalse(fixture.engine.isAnalysisInProgress(), variant);
      }
    }
  }

  @Test
  void finiteSearchWatchdogIgnoresIntermediateInfoAndUnrelatedResponses() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true);
        ControlledSetupScheduler clock = new ControlledSetupScheduler()) {
      setField(AnalysisEngine.class, fixture.engine, "remoteGtpSetupAckTimeoutExecutor", clock);
      fixture.start();
      fixture.finiteReady();
      fixture.finishPreparation();
      String search = fixture.last();
      Runnable deadline = clock.callbacks.get(clock.callbacks.size() - 1);
      fixture.reply("=199999999 unrelated");
      fixture.reply(remoteGtpInfoLine(99, 61.0, 3.5));
      fixture.reply("");
      fixture.reply("=" + fixture.id(search));
      for (int i = 0; i < 4; i++) fixture.reply(remoteGtpInfoLine(3, 17, 61.0, 3.5));
      assertEquals(search, fixture.last());
      deadline.run();
      assertFalse(fixture.engine.isAnalysisInProgress());
      assertEquals(0, fixture.target.getData().getPlayouts());
    }
  }

  @Test
  void cancelledFiniteSearchRestoresAllParametersOnlyAfterBothStopBoundaries() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true)) {
      fixture.start();
      fixture.finiteReady();
      fixture.finishPreparation();
      String search = fixture.last();
      fixture.reply("=" + fixture.id(search));
      fixture.reply(remoteGtpInfoLine(3, 17, 61.0, 3.5));
      fixture.engine.retireAutomaticQuickAnalysisRequest(fixture.use);
      fixture.engine.requestShutdown();
      fixture.engine.normalQuit();
      String stop = fixture.last();
      assertTrue(stop.endsWith(" stop"));
      fixture.reply("play cancelled");
      fixture.reply("");
      assertEquals(stop, fixture.last());
      fixture.reply("=" + fixture.id(stop));
      assertEquals(stop, fixture.last());
      fixture.reply("");
      List<String> restores = fixture.commands().stream().filter(s -> s.contains(" kata-set-param "))
          .skip(4).toList();
      assertEquals(4, restores.size());
      assertTrue(restores.get(0).endsWith(" maxVisits 1234"));
      assertTrue(restores.get(1).endsWith(" maxPlayouts 1234"));
      assertTrue(restores.get(2).endsWith(" maxTime 1234"));
      assertTrue(restores.get(3).endsWith(" ponderingEnabled true"));
      for (int i = 1; i < restores.size(); i++) fixture.ack(restores.get(i));
      assertTrue(fixture.foreground.hasForegroundAnalysisLeaseWorkInProgress());
      fixture.reply("=199999999 name");
      fixture.reply("");
      assertEquals(0, fixture.target.getData().getPlayouts());
      assertTrue(fixture.foreground.hasForegroundAnalysisLeaseWorkInProgress());
      fixture.reply("?" + fixture.id(restores.get(0)) + " restore rejected");
      assertFalse(fixture.foreground.isLoaded());
    }
  }

  @Test
  void finiteSearchUsesTerminalOutputIntervalAndValidatesCapturedCloudBudget() throws Exception {
    // Captured same-session interval1/10000 responses: zhizi-finite-02-interval1/gtp.jsonl:48,62.
    List<String> payloads = List.of(
        "info move D16 visits 2 edgeVisits 2 utility 0.302024 winrate 0.649161 scoreMean 0.942547 scoreStdev 12.9215 scoreLead 0.942547 scoreSelfplay 1.48592 prior 0.303762 lcb 0.109765 utilityLcb -1.20828 weight 6.4315 order 0 pv D16 Q16 rootInfo visits 4 utility 0.300658 winrate 0.64796 scoreMean 0.901886 scoreStdev 12.9778 scoreLead 0.901886 scoreSelfplay 1.47476 weight 12.6758 rawStWrError 0.0385319 rawStScoreError 0.357069 rawVarTimeLeft 57.3263",
        "info move D16 visits 9 edgeVisits 9 utility 0.302071 winrate 0.646977 scoreMean 0.956443 scoreStdev 13.0187 scoreLead 0.956443 scoreSelfplay 1.50381 prior 0.303762 lcb 0.615269 utilityLcb 0.213288 weight 28.8412 order 0 pv D16 C4 Q16 rootInfo visits 17 utility 0.296664 winrate 0.64552 scoreMean 0.930724 scoreStdev 13.0863 scoreLead 0.930724 scoreSelfplay 1.48404 weight 54.6925 rawStWrError 0.0385319 rawStScoreError 0.357069 rawVarTimeLeft 57.3263");
    for (int i = 0; i < payloads.size(); i++) {
      try (PreparationFixture fixture = new PreparationFixture(true)) {
        fixture.start();
        fixture.finiteReady();
        fixture.finishPreparation();
        String search = fixture.last();
        assertEquals("kata-search_analyze_cancellable B 10000 maxmoves 1 rootInfo true",
            search.substring(search.indexOf(' ') + 1));
        fixture.reply("=" + fixture.id(search));
        fixture.reply(payloads.get(i));
        assertEquals(0, fixture.target.getData().getPlayouts());
        assertEquals(search, fixture.last());
        fixture.reply("play D16");
        assertEquals(0, fixture.target.getData().getPlayouts());
        fixture.reply("");
        assertEquals(i == 0 ? 0 : 17, fixture.target.getData().getPlayouts());
        assertFalse(fixture.engine.isAnalysisInProgress());
      }
    }
  }

  @Test
  void finiteSearchWaitsForNaturalTerminalAndUsesRootVisits() throws Exception {
    try (PreparationFixture fixture = new PreparationFixture(true)) {
      fixture.start();
      fixture.finiteReady();
      fixture.finishPreparation();
      String search = fixture.last();
      long stops = fixture.commands().stream().filter(s -> s.endsWith(" stop")).count();
      fixture.reply("=" + fixture.id(search));
      fixture.reply(remoteGtpInfoLine(3, 61.0, 3.5) + " rootInfo visits 17 winrate 0.61 scoreLead 3.5");
      assertEquals(0, fixture.target.getData().getPlayouts());
      assertEquals(search, fixture.last());
      fixture.reply("play pass");
      assertEquals(0, fixture.target.getData().getPlayouts());
      fixture.reply("");
      assertEquals(17, fixture.target.getData().getPlayouts());
      assertEquals(stops + 1, fixture.commands().stream().filter(s -> s.endsWith(" stop")).count(),
          "only lease handback stop remains");
    }
  }

  @Test
  void foregroundReuseCapturesRulesAndAcknowledgesSetupCommandsInOrder() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      Leelaz foreground = reusableForegroundEngine(true);
      ByteArrayOutputStream output = installLeelazOutput(foreground);
      Lizzie.leelaz = foreground;
      Lizzie.config.analysisReuseCurrentEngine = true;
      Lizzie.config.analysisUseCurrentRules = true;
      AnalysisEngine engine = new AnalysisEngine(false);

      engine.startRequest(1, -1, false);
      int leaseStopCommandId = getExclusiveStopCommandId(foreground);
      dispatchExclusiveLine(foreground, "=" + leaseStopCommandId);
      assertTrue(dispatchExclusiveLine(foreground, ""));
      assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("830000000 kata-get-rules\n"));

      String originalRules =
          "{\"koRule\":\"POSITIONAL\",\"scoringRule\":\"AREA\",\"taxRule\":\"NONE\"}";
      assertTrue(dispatchExclusiveLine(foreground, "=830000000 " + originalRules));
      assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("830000001 boardsize 3\n"));

      assertTrue(dispatchExclusiveLine(foreground, "=830000001"));
      assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("830000002 komi 7.5\n"));
      assertTrue(dispatchExclusiveLine(foreground, "=830000002"));
      assertTrue(
          output
              .toString(StandardCharsets.UTF_8)
              .endsWith("830000003 kata-set-rules " + originalRules + "\n"));
    }
  }

  @Test
  void sendRequestExpressesSnapshotRootPositionWithInitialStonesAndPlayer() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
                  Optional.empty(),
                  Stone.EMPTY,
                  false,
                  58));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertTrue(request.has("initialStones"), "snapshot-root request should send initial stones.");
      assertEquals(
          Set.of("B:A3", "W:B3"),
          stoneSet(request.getJSONArray("initialStones")),
          "snapshot-root request should serialize the current board as initial stones.");
      assertEquals("W", request.getString("initialPlayer"));
      assertEquals(List.of(), request.getJSONArray("moves").toList());
      assertEquals(List.of(0), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void foxHandicapSgfAnalysisRequestKeepsSetupStonesAndZeroKomi() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Board parseBoard =
          boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      parseBoard.isLoadingFile = true;
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3]KM[0]HA[2]AB[aa]AB[ca];W[ba])", true);
      boardWithHistory(history);
      history.next();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3", "B:C3"),
          stoneSet(request.getJSONArray("initialStones")),
          "Fox handicap imports must send root handicap stones to KataGo analysis.");
      assertEquals(List.of(List.of("W", "B3")), request.getJSONArray("moves").toList());
      assertEquals(0.0, request.getDouble("komi"), 0.0001);
      assertEquals(2, history.getGameInfo().getHandicap());
    }
  }

  @Test
  void visibleMainlineRequestWithNoPendingPositionsClosesAndRestoresForeground() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAutoQuit = false;
      boardWithHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      PonderTrackingLeelaz foreground = allocate(PonderTrackingLeelaz.class);
      foreground.pondering = true;
      Lizzie.leelaz = foreground;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(
          AnalysisEngine.class,
          engine,
          "purpose",
          AnalysisResourceCoordinator.Purpose.USER_QUICK_ANALYSIS);
      TrackingWaitForAnalysis waitFrame = allocate(TrackingWaitForAnalysis.class);
      waitFrame.setVisible(true);
      engine.waitFrame = waitFrame;
      AtomicInteger completions = new AtomicInteger();
      engine.setCompletionCallback(restore -> completions.incrementAndGet());

      engine.startRequest(-1, -1, true);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(0, engine.requestCount());
      assertEquals(2, waitFrame.visibilityCalls);
      assertFalse(waitFrame.lastVisible);
      assertEquals(1, completions.get());
      assertEquals(1, foreground.notPonderingCalls);
      assertEquals(1, foreground.ponderCalls);
      assertTrue(foreground.pondering);
    }
  }

  @Test
  void startRequestSkipsSnapshotRootOnlyHistory() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
                  Optional.empty(),
                  Stone.EMPTY,
                  false,
                  58));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(-1, -1, false);

      assertEquals(
          0,
          engine.requestCount(),
          "snapshot-root-only history should not emit batch requests from startRequest.");
    }
  }

  @Test
  void wholeGameRequestIncludesRootAndUsesExplicitTargetWithoutOwnership() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      BoardHistoryNode root = history.getStart();
      BoardHistoryNode firstMove = root.next().orElseThrow();

      int requested = engine.startWholeGameRequest(List.of(root, firstMove), 500, false);

      assertEquals(2, requested);
      JSONObject rootRequest = engine.requestAt(0);
      JSONObject moveRequest = engine.requestAt(1);
      assertEquals(500, rootRequest.getInt("maxVisits"));
      assertFalse(rootRequest.getBoolean("includeOwnership"));
      assertEquals(List.of(0), rootRequest.getJSONArray("analyzeTurns").toList());
      assertEquals(List.of(1), moveRequest.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void silentProgressRefreshesGraphWithoutTriggeringFullBoardRefresh() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 1, Stone.WHITE)),
              new int[] {1, 1},
              Stone.WHITE,
              true,
              2));
      boardWithHistory(history);
      SilentProgressTrackingFrame frame = allocate(SilentProgressTrackingFrame.class);
      Lizzie.frame = frame;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      assertEquals(2, engine.startRequestMissingMainline(false));
      engine.parseResult(analysisResult(1, 200, 62.0));

      assertEquals(1, frame.silentProgressRefreshCalls);
      assertEquals(
          0,
          frame.fullRefreshCalls,
          "an intermediate silent result must not rebuild the full board render surface.");
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void silentProgressAlwaysRefreshesTheFinalResultBetweenThrottleIntervals() throws Exception {
    Method method =
        AnalysisEngine.class.getDeclaredMethod("shouldRefreshSilentProgress", int.class, int.class);
    method.setAccessible(true);

    assertTrue((boolean) method.invoke(null, 13, 13));
    assertTrue((boolean) method.invoke(null, 49, 49));
    assertFalse((boolean) method.invoke(null, 49, 50));
    assertTrue((boolean) method.invoke(null, 48, 50));
  }

  @Test
  void wholeGameRequestNeverDowngradesCachedResultsEvenWhenOverrideIsEnabled() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      rootData.setPlayouts(100);
      rootData.winrate = 41.0;
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger progress = new AtomicInteger();
      engine.setProgressListener((completed, total) -> progress.set(completed));

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));
      installCompleteAnalysis(rootData, 800, false);
      rootData.winrate = 44.0;
      engine.parseResult(analysisResult(1, 500, 70.0));

      assertEquals(800, rootData.getPlayouts());
      assertEquals(44.0, rootData.winrate, 0.0001);
      assertFalse(rootData.bestMoves.isEmpty());
      assertEquals(history.getStart(), history.getCurrentHistoryNode());
      assertEquals(
          0,
          progress.get(),
          "a response superseded by a complete higher-visit cache is consumed, not applied");
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void wholeGameResponseIsRejectedWhenKomiChangesDuringTheRequest() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger failures = new AtomicInteger();
      engine.setFailureCallback(restore -> failures.incrementAndGet());

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));
      history.getGameInfo().setKomi(history.getGameInfo().getKomi() + 0.5);
      engine.parseResult(analysisResult(1, 500, 70.0));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, failures.get());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void wholeGameResponseIsRejectedWhenAnalysisRulesChangeDuringTheRequest() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisUseCurrentRules = false;
      Lizzie.config.analysisSpecificRules = "";
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger failures = new AtomicInteger();
      engine.setFailureCallback(restore -> failures.incrementAndGet());

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));
      assertEquals("tromp-taylor", engine.singleRequest().getString("rules"));
      Lizzie.config.analysisSpecificRules = "{\"scoringRule\":\"AREA\"}";
      engine.parseResult(analysisResult(1, 500, 70.0));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, failures.get());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void wholeGameResponseIsRejectedWhenARequestedPositionMutatesInPlace() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger failures = new AtomicInteger();
      engine.setFailureCallback(restore -> failures.incrementAndGet());

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));
      rootData.blackToPlay = !rootData.blackToPlay;
      engine.parseResult(analysisResult(1, 500, 70.0));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, failures.get());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void wholeGameResponseIsRejectedAfterShutdownWasRequested() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger failures = new AtomicInteger();
      engine.setFailureCallback(restore -> failures.incrementAndGet());

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));
      engine.requestShutdown();
      engine.parseResult(analysisResult(1, 500, 70.0));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, failures.get());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void wholeGameRequestSkipsNodesAlreadyAtTheTargetRegardlessOfOverride() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      installCompleteAnalysis(rootData, 500, false);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, false);

      assertEquals(0, requested);
      assertEquals(0, engine.requestCount());
    }
  }

  @Test
  void wholeGameRequestDoesNotMistakeVisitOnlyHeaderForDisplayableAnalysis() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      rootData.setPlayouts(500);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, false);

      assertEquals(1, requested);
      assertEquals(1, engine.requestCount());
    }
  }

  @Test
  void localJsonRequestIsRegisteredBeforeAnImmediateResponseArrives() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger completions = new AtomicInteger();
      engine.synchronousResponse = analysisResult(1, 500, 70.0);
      engine.setCompletionCallback(restore -> completions.incrementAndGet());

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, false);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, requested);
      assertTrue(rootData.hasCompletePrimaryAnalysis(500, false));
      assertEquals(1, completions.get());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void localWholeGameUsesRootVisitsWithoutRequiringTopChildToReachTarget() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.synchronousResponse = analysisResultWithRootVisits(1, 300, 500, 70.0);

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, false);

      assertEquals(1, requested);
      assertEquals(500, rootData.getPlayouts());
      assertEquals(500, rootData.rootVisits);
      assertEquals(300, rootData.bestMoves.get(0).playouts);
      assertTrue(rootData.hasCompletePrimaryAnalysis(500, false));
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void childResearchCannotSatisfyWholeGameRootBudget() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.synchronousResponse = analysisResultWithRootVisits(1, 848, 100, 70.0);
      engine.startWholeGameRequest(List.of(history.getStart()), 500, false);
      assertEquals(0, rootData.getPlayouts());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void emptyJsonPayloadCompletesTheBatchWithoutReportingFalseSuccess() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger progress = new AtomicInteger(-1);
      AtomicInteger completions = new AtomicInteger();
      engine.synchronousResponse = emptyAnalysisResult(1);
      engine.setProgressListener((completed, total) -> progress.set(completed));
      engine.setCompletionCallback(restore -> completions.incrementAndGet());

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, false);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, requested);
      assertEquals(0, progress.get());
      assertEquals(1, completions.get());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void malformedImmediateResponseCannotStrandLaterWholeGameRequests() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      BoardHistoryNode root = history.getStart();
      BoardHistoryNode move = history.getCurrentHistoryNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger failures = new AtomicInteger();
      engine.synchronousEngineLine = "{not-json";
      engine.setFailureCallback(restore -> failures.incrementAndGet());

      int requested = engine.startWholeGameRequest(List.of(root, move), 500, false);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(-1, requested);
      assertEquals(1, engine.requestCount());
      assertEquals(0, engine.pendingRequestCount());
      assertEquals(1, failures.get());
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void malformedAnalysisResultDoesNotMarkStartupReadyBeforeExit() throws Exception {
    try (TestEnvironment env = TestEnvironment.open();
        EngineStartupDiagnostics diagnostics =
            new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      var attempt =
          diagnostics.begin("analysis-invalid", "WHOLE_GAME_ANALYSIS", List.of("engine"), true);
      setField(AnalysisEngine.class, engine, "startupDiagnosticAttempt", attempt);

      invokeAnalysisEngineParseLine(engine, "{not-json");

      assertEquals(
          "startup-exit",
          attempt.fail("startup-exit", "EOF before valid result").toJson().getString("phase"));
      assertEquals("analysis-invalid", attempt.snapshot().engineId());
    }
  }

  @Test
  void invalidMovePayloadDoesNotSuppressStartupExitDiagnostic() throws Exception {
    try (TestEnvironment env = TestEnvironment.open();
        EngineStartupDiagnostics diagnostics =
            new EngineStartupDiagnostics(EngineStartupDiagnostics.Policy.production(), null)) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      var attempt =
          diagnostics.begin("analysis-bad-moves", "WHOLE_GAME_ANALYSIS", List.of("engine"), true);
      setField(AnalysisEngine.class, engine, "startupDiagnosticAttempt", attempt);
      engine.synchronousEngineLine = "{\"id\":\"1\",\"moveInfos\":[{}]}";

      assertEquals(-1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));
      assertEquals(
          "startup-exit",
          attempt.fail("startup-exit", "EOF before valid moves").toJson().getString("phase"));
    }
  }

  @Test
  void requestedLocalOwnershipMustBePresentBeforeResultCountsAsComplete() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.synchronousResponse = analysisResultWithOwnership(1, 500, 70.0);

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, true);

      assertEquals(1, requested);
      assertTrue(engine.singleRequest().getBoolean("includeOwnership"));
      assertTrue(rootData.hasCompletePrimaryAnalysis(500, true));
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void missingRequestedOwnershipIsConsumedButNotReportedAsSuccess() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger progress = new AtomicInteger(-1);
      engine.synchronousResponse = analysisResult(1, 500, 70.0);
      engine.setProgressListener((completed, total) -> progress.set(completed));

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, true);

      assertEquals(1, requested);
      assertEquals(0, progress.get());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, true));
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void ownershipBackfillDoesNotDowngradeHigherVisitPrimaryAnalysis() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      installCompleteAnalysis(rootData, 10_000, false);
      rootData.bestMoves.get(0).coordinate = "A1";
      rootData.winrate = 44.0;
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.synchronousResponse = analysisResultWithOwnership(1, 500, 70.0);

      int requested = engine.startWholeGameRequest(List.of(history.getStart()), 500, true);

      assertEquals(1, requested);
      assertEquals(10_000, rootData.getPlayouts());
      assertEquals("A1", rootData.bestMoves.get(0).coordinate);
      assertEquals(44.0, rootData.winrate, 0.0001);
      assertTrue(rootData.hasCompletePrimaryAnalysis(500, true));
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteWholeGameUsesRootVisitsWithoutRequiringTopChildToReachTarget() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));

      completeRemoteGtpSearch(engine, 120, 500, 62.0, 1.5);

      assertEquals(500, rootData.getPlayouts());
      assertEquals(120, rootData.bestMoves.get(0).playouts);
      assertTrue(rootData.hasCompletePrimaryAnalysis(500, false));
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteWholeGameResponseIsRejectedAfterShutdownDuringStopHandshake() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);
      AtomicInteger failures = new AtomicInteger();
      engine.setFailureCallback(restore -> failures.incrementAndGet());

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 500, false));
      int analyzeCommandId =
          numberedCommandIdStartingWith(lastCommand(engine.sentCommands), "kata-analyze ");
      invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);
      String resultLine = remoteGtpInfoLine(120, 500, 62.0, 1.5);
      invokeAnalysisEngineParseLine(engine, resultLine);
      int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));

      engine.requestShutdown();
      invokeAnalysisEngineParseLine(engine, resultLine);
      invokeAnalysisEngineParseLine(engine, "");
      invokeAnalysisEngineParseLine(engine, "=" + stopCommandId);
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, failures.get());
      assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void remoteWholeGameNormalizesMissingPvToTheCandidateMove() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      BoardHistoryList history = new BoardHistoryList(rootData);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      assertEquals(1, engine.startWholeGameRequest(List.of(history.getStart()), 16, false));

      int analyzeCommandId =
          numberedCommandIdStartingWith(lastCommand(engine.sentCommands), "kata-analyze ");
      invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);
      String resultLine = remoteGtpInfoLineWithoutPv(16, 62.0, 1.5);
      invokeAnalysisEngineParseLine(engine, resultLine);
      int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
      invokeAnalysisEngineParseLine(engine, resultLine);
      invokeAnalysisEngineParseLine(engine, "");
      invokeAnalysisEngineParseLine(engine, "=" + stopCommandId);

      assertEquals(List.of("B2"), rootData.bestMoves.get(0).variation);
      assertTrue(rootData.hasCompletePrimaryAnalysis(16, false));
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void startRequestMissingMainlineAnalyzesOnlyUnanalyzedMainlineMoves() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData analyzed =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      analyzed.setPlayouts(120);
      analyzed.engineName = "cached-analysis";
      analyzed.analysisHeaderSlots = 3;
      history.add(analyzed);
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      int requested = engine.startRequestMissingMainline(false);

      assertEquals(1, requested);
      assertEquals(1, engine.requestCount());
      JSONObject request = engine.singleRequest();
      assertEquals(
          List.of(List.of("B", "A3"), List.of("W", "B3")),
          request.getJSONArray("moves").toList(),
          "Yike curve completion should request only the missing current mainline node.");
      assertEquals(List.of(2), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void missingMainlineCompletionOnPreloadedWorkerPreservesRoot() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryNode move = singleUnanalyzedMoveNode();
      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      Lizzie.board.getHistory().toStart();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "isPreLoad", true);
      setField(AnalysisEngine.class, engine, "persistentPreload", true);
      AtomicInteger completed = new AtomicInteger();
      engine.setCompletionCallback(restore -> completed.incrementAndGet());

      assertEquals(1, engine.startRequestMissingMainline(false));
      engine.parseResult(analysisResult(1, 200, 62.0));
      waitForMovelistRefreshThreads();
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, completed.get());
      assertEquals(200, move.getData().getPlayouts());
      assertSame(root, Lizzie.board.getHistory().getCurrentHistoryNode());
      assertEquals(0, engine.normalQuitCount);
    }
  }

  @Test
  void missingMainlineResponseIsRejectedAfterAnotherKifuLoads() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList firstHistory = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData firstMove =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      firstHistory.add(firstMove);
      boardWithHistory(firstHistory);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      AtomicInteger failures = new AtomicInteger();
      engine.setFailureCallback(restore -> failures.incrementAndGet());

      assertEquals(1, engine.startRequestMissingMainline(false));

      BoardHistoryList secondHistory =
          new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData secondMove =
          moveNode(stones(placement(1, 0, Stone.BLACK)), new int[] {1, 0}, Stone.BLACK, false, 1);
      secondHistory.add(secondMove);
      boardWithHistory(secondHistory);

      engine.parseResult(analysisResult(1, 200, 62.0));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      assertEquals(1, failures.get());
      assertEquals(0, firstMove.getPlayouts(), "a stale response must not mutate the old kifu");
      assertEquals(0, secondMove.getPlayouts(), "a stale response must not leak into the new kifu");
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void startRequestMissingMainlineStillSkipsExistingAnalysisWhenOverrideIsEnabled()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData analyzed =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      analyzed.setPlayouts(120);
      analyzed.engineName = "cached-analysis";
      analyzed.analysisHeaderSlots = 3;
      history.add(analyzed);
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      int requested = engine.startRequestMissingMainline(false);

      assertEquals(
          1,
          requested,
          "missing-mainline completion is a preservation path and should not reanalyze cached nodes.");
      assertEquals(1, engine.requestCount());
      assertEquals(List.of(2), engine.singleRequest().getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void startRequestMissingMainlineSkipsLowVisitExistingAnalysis() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData lowVisits =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      lowVisits.setPlayouts(AnalysisEngine.targetAnalysisVisits() - 1);
      lowVisits.engineName = "partial-analysis";
      lowVisits.analysisHeaderSlots = 3;
      history.add(lowVisits);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      int requested = engine.startRequestMissingMainline(false);

      assertEquals(0, requested);
      assertEquals(0, engine.requestCount());
    }
  }

  @Test
  void startRequestReanalyzesExistingMovesWhenAlwaysOverrideIsEnabled() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData analyzed =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      analyzed.setPlayouts(120);
      history.add(analyzed);
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(-1, -1, false);

      assertEquals(
          2,
          engine.requestCount(),
          "explicit override should still allow users to recalculate the whole selected range.");
      assertEquals(List.of(1), engine.requestAt(0).getJSONArray("analyzeTurns").toList());
      assertEquals(List.of(2), engine.requestAt(1).getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void parseResultHonorsAlwaysOverrideEvenWhenNewVisitsAreLower() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      Lizzie.config.enableLizzieCache = true;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData analyzed =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      analyzed.setPlayouts(120);
      analyzed.winrate = 42.0;
      analyzed.engineName = "cached-analysis";
      history.add(analyzed);
      BoardHistoryNode analyzedNode = history.getCurrentHistoryNode();
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.trackPending(1, analyzedNode);
      engine.trackPending(2, analyzedNode);

      engine.parseResult(analysisResponse(1, 1, 0.77).toString());

      assertEquals(
          1,
          analyzed.getPlayouts(),
          "always override should bypass cache guards even when the new flash result has fewer visits.");
      assertEquals(77.0, analyzed.winrate, 0.0001);
      assertFalse("cached-analysis".equals(analyzed.engineName));
    }
  }

  @Test
  void completedManualAnalysisStillAutoQuitsWhenConfigured() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAutoQuit = true;
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.trackPending(1, node);

      engine.parseResult(analysisResult(1, 200, 62.0));

      assertEquals(1, engine.normalQuitCount);
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void completedPreloadedAnalysisKeepsEngineWarm() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAutoQuit = true;
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "isPreLoad", true);
      setField(AnalysisEngine.class, engine, "persistentPreload", true);
      engine.trackPending(1, node);

      engine.parseResult(analysisResult(1, 200, 62.0));

      assertEquals(0, engine.normalQuitCount);
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void completedAutoQuickAnalysisKeepsEngineWarmForNextKifu() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAutoQuit = true;
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.setKeepAliveAfterCurrentRequest(true);
      engine.trackPending(1, node);

      engine.parseResult(analysisResult(1, 200, 62.0));

      assertEquals(0, engine.normalQuitCount);
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void startRequestMissingMainlineReportsDispatchFailure() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.failSends = true;
      engine.setCompletionCallback(restore -> {});

      int requested = engine.startRequestMissingMainline(false);

      assertEquals(-1, requested);
      assertEquals(0, engine.pendingRequestCount());
      assertFalse(engine.isAnalysisInProgress());
      assertNull(engine.completionCallback());
    }
  }

  @Test
  void stoppedEngineRejectsLateMissingMainlineRequest() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.requestShutdown();

      assertEquals(-1, engine.startRequestMissingMainline(false));
      assertEquals(0, engine.pendingRequestCount());
      assertTrue(engine.sentCommands.isEmpty());
    }
  }

  @Test
  void failedSendRequestDoesNotLeavePendingAnalysis() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.failSends = true;

      engine.sendRequest(history.getCurrentHistoryNode());

      assertEquals(0, engine.pendingRequestCount());
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void failedBatchRequestDoesNotAdvanceToNextFile() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      TrackingLizzieFrame frame = (TrackingLizzieFrame) Lizzie.frame;
      frame.isBatchAnalysisMode = true;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.failSends = true;

      engine.startRequest(1, -1, false);

      assertEquals(0, frame.flashAutoAnaSaveAndLoadCalls);
      assertEquals(0, engine.pendingRequestCount());
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void remoteGtpAnalysisUsesKataAnalyzeCommandsForCurrentPosition() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);

      engine.sendRequest(node);

      assertTrue(engine.sentCommands.contains("boardsize 3"));
      assertTrue(engine.sentCommands.contains("komi 7.5"));
      assertTrue(engine.sentCommands.contains("kata-set-rules tromp-taylor"));
      assertTrue(engine.sentCommands.contains("set_position"));
      assertTrue(engine.sentCommands.contains("play B A3"));
      assertNumberedAnalyzeCommand(lastCommand(engine.sentCommands), "kata-analyze W 50");
      assertEquals(1, engine.pendingRequestCount());
    }
  }

  @Test
  void remoteGtpSnapshotUsesStaticSetPositionBeforeReplayingLaterActions() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Stone[] snapshotStones = stones(placement(0, 0, Stone.BLACK), placement(2, 0, Stone.WHITE));
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, false, 58));
      history.add(passNode(snapshotStones, Stone.WHITE, true, 59));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);

      engine.sendRequest(history.getCurrentHistoryNode());

      assertTrue(engine.sentCommands.contains("set_position B A3 W C3"));
      assertTrue(engine.sentCommands.contains("play W pass"));
      assertFalse(engine.sentCommands.contains("play B A3"));
      assertFalse(engine.sentCommands.contains("play W C3"));
    }
  }

  @Test
  void remoteGtpInfoLineCompletesAnalysisNodeAndWritesScoreLead() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);
      engine.sendRequest(node);
      int analyzeCommandId =
          assertNumberedAnalyzeCommand(lastCommand(engine.sentCommands), "kata-analyze W 50");
      invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);

      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(33, 62.0, 1.5));
      int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
      assertEquals(
          0,
          node.getData().getPlayouts(),
          "the first threshold frame must not be committed before KataGo finishes stopping.");

      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(48, 64.0, 1.7));
      invokeAnalysisEngineParseLine(engine, "");
      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(99, 12.0, -9.0));
      assertEquals(
          0,
          node.getData().getPlayouts(),
          "a late info frame after the numbered analyze boundary must be ignored.");
      invokeAnalysisEngineParseLine(engine, "=" + stopCommandId);

      assertEquals(48, node.getData().getPlayouts());
      assertEquals(64.0, node.getData().winrate, 0.0001);
      assertEquals(1.7, node.getData().scoreMean, 0.0001);
      assertEquals(
          List.of("B2"),
          node.getData().bestMoves.get(0).variation,
          "rootInfo metadata must not leak into the principal variation.");
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteGtpMainlineAnalysisAdvancesIncrementallyAfterFirstPosition() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      history.add(
          moveNode(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.BLACK)),
              new int[] {2, 0},
              Stone.BLACK,
              false,
              3));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      engine.startRequest(-1, -1, false);

      assertEquals(1, countCommand(engine.sentCommands, "set_position"));
      assertTrue(engine.sentCommands.contains("play B A3"));
      assertNumberedAnalyzeCommand(
          lastCommand(engine.sentCommands), "kata-analyze W 1 maxmoves 1 rootInfo true");

      completeRemoteGtpSearch(engine, 16, 62.0, 1.5);

      assertEquals(
          1,
          countCommand(engine.sentCommands, "set_position"),
          "remote mainline analysis should keep the board and avoid O(n^2) replay.");
      assertNumberedAnalyzeCommand(
          lastCommand(engine.sentCommands), "kata-analyze B 1 maxmoves 1 rootInfo true");
      assertTrue(engine.sentCommands.contains("play W B3"));

      completeRemoteGtpSearch(engine, 16, 58.0, 0.7);

      assertEquals(1, countCommand(engine.sentCommands, "set_position"));
      assertNumberedAnalyzeCommand(
          lastCommand(engine.sentCommands), "kata-analyze W 1 maxmoves 1 rootInfo true");
      assertTrue(engine.sentCommands.contains("play B C3"));

      completeRemoteGtpSearch(engine, 16, 54.0, 0.3);

      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteGtpMissingMainlineAnalysisAlsoAdvancesIncrementally() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData analyzed =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      analyzed.winrate = 51.0;
      analyzed.setPlayouts(100);
      MoveData existingMove = new MoveData();
      existingMove.coordinate = "B2";
      existingMove.playouts = 100;
      existingMove.winrate = 50.0;
      existingMove.variation = List.of("B2");
      analyzed.bestMoves = List.of(existingMove);
      history.add(analyzed);
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      history.add(
          moveNode(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.BLACK)),
              new int[] {2, 0},
              Stone.BLACK,
              false,
              3));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      int requested = engine.startRequestMissingMainline(false);

      assertEquals(2, requested);
      assertEquals(1, countCommand(engine.sentCommands, "set_position"));
      assertTrue(engine.sentCommands.contains("play B A3"));
      assertTrue(engine.sentCommands.contains("play W B3"));

      assertNumberedAnalyzeCommand(
          lastCommand(engine.sentCommands), "kata-analyze B 1 maxmoves 1 rootInfo true");
      completeRemoteGtpSearch(engine, 16, 58.0, 0.7);

      assertEquals(1, countCommand(engine.sentCommands, "set_position"));
      assertNumberedAnalyzeCommand(
          lastCommand(engine.sentCommands), "kata-analyze W 1 maxmoves 1 rootInfo true");
      assertTrue(engine.sentCommands.contains("play B C3"));

      completeRemoteGtpSearch(engine, 16, 54.0, 0.3);

      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteGtpSilentQuickCurveUsesStableSearchBudgetEvenWhenManualVisitsAreHigh()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisMaxVisits = 999;
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      engine.startRequest(-1, -1, false);

      String analyzeCommand = lastCommand(engine.sentCommands);
      int analyzeCommandId =
          assertNumberedAnalyzeCommand(analyzeCommand, "kata-analyze W 1 maxmoves 1 rootInfo true");
      invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);
      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(15, 58.0, 0.9));
      assertEquals(
          analyzeCommand,
          lastCommand(engine.sentCommands),
          "remote quick curves should wait for the fixed search budget before stopping.");

      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(16, 62.0, 1.5));
      int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(24, 64.0, 1.7));
      invokeAnalysisEngineParseLine(engine, "");
      invokeAnalysisEngineParseLine(engine, "=" + stopCommandId);

      assertEquals(24, node.getData().getPlayouts());
      assertEquals(64.0, node.getData().winrate, 0.0001);
      assertEquals(1.7, node.getData().scoreMean, 0.0001);
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteGtpStopErrorFailsWithoutCommittingResult() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      engine.startRequest(-1, -1, false);

      int analyzeCommandId =
          assertNumberedAnalyzeCommand(
              lastCommand(engine.sentCommands), "kata-analyze W 1 maxmoves 1 rootInfo true");
      invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);
      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(16, 62.0, 1.5));
      int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
      invokeAnalysisEngineParseLine(engine, "");
      invokeAnalysisEngineParseLine(engine, "?" + stopCommandId + " cannot stop");

      assertEquals(0, node.getData().getPlayouts());
      assertFalse(engine.isAnalysisInProgress());
    }
  }

  @Test
  void remoteGtpMissingStopAckFailsWithoutCommittingOrAdvancing() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      BoardHistoryNode firstNode = history.getCurrentHistoryNode();
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      engine.startRequest(-1, -1, false);

      int firstAnalyzeCommandId =
          assertNumberedAnalyzeCommand(
              lastCommand(engine.sentCommands), "kata-analyze W 1 maxmoves 1 rootInfo true");
      invokeAnalysisEngineParseLine(engine, "=" + firstAnalyzeCommandId);
      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(16, 62.0, 1.5));
      int firstStopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
      invokeAnalysisEngineParseLine(engine, "");

      waitUntil(() -> !engine.isAnalysisInProgress());

      assertEquals(0, firstNode.getData().getPlayouts());
      assertEquals(1, countCommandContaining(engine.sentCommands, " kata-analyze "));
      assertEquals(firstStopCommandId, assertNumberedStopCommand(lastCommand(engine.sentCommands)));
    }
  }

  @Test
  void queuedRemoteGtpSetupSendFailureUsesDispatchFailureCleanup() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      Lizzie.frame.isBatchAnalysisMode = true;
      AtomicInteger successfulContinuations = new AtomicInteger();
      engine.setCompletionCallback(restore -> successfulContinuations.incrementAndGet());

      engine.startRequest(-1, -1, false);
      int analyzeCommandId =
          assertNumberedAnalyzeCommand(lastCommand(engine.sentCommands), "kata-analyze W 1");
      invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);
      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(64, 62.0, 1.5));
      int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
      invokeAnalysisEngineParseLine(engine, "");
      engine.failSends = true;
      invokeAnalysisEngineParseLine(engine, "=" + stopCommandId);

      assertFalse(engine.isAnalysisInProgress());
      assertEquals(0, successfulContinuations.get());
      assertFalse(Lizzie.frame.isBatchAnalysisMode);
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteGtpBatchAnalysisKeepsConfiguredSearchBudget() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.batchAnalysisPlayouts = 64;
      ((TrackingLizzieFrame) Lizzie.frame).isBatchAnalysisMode = true;
      BoardHistoryNode node = singleUnanalyzedMoveNode();
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      engine.startRequest(-1, -1, false);

      int analyzeCommandId =
          assertNumberedAnalyzeCommand(lastCommand(engine.sentCommands), "kata-analyze W 1");
      invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);
      invokeAnalysisEngineParseLine(engine, remoteGtpInfoLine(64, 62.0, 1.5));
      int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
      invokeAnalysisEngineParseLine(engine, "");
      invokeAnalysisEngineParseLine(engine, "=" + stopCommandId);

      assertEquals(64, node.getData().getPlayouts());
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void remoteGtpSilentLongGamePrioritizesOverviewBeforeBackfill() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      Stone[] empty = emptyStones();
      for (int moveNumber = 1; moveNumber <= 25; moveNumber++) {
        boolean blackMove = moveNumber % 2 == 1;
        history.add(passNode(empty, blackMove ? Stone.BLACK : Stone.WHITE, !blackMove, moveNumber));
      }
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      setField(AnalysisEngine.class, engine, "useRemoteCompute", true);
      engine.setKeepAliveAfterCurrentRequest(true);

      engine.startRequest(-1, -1, false);

      assertNumberedAnalyzeCommand(
          lastCommand(engine.sentCommands), "kata-analyze W 1 maxmoves 1 rootInfo true");
      assertEquals(1, countCommandStartingWith(engine.sentCommands, "play "));

      completeRemoteGtpSearch(engine, 16, 62.0, 1.5);

      assertEquals(
          8,
          countCommandStartingWith(engine.sentCommands, "play "),
          "long remote quick curves should jump to sparse overview points first so users see a graph quickly.");
      assertTrue(lastCommand(engine.sentCommands).endsWith("maxmoves 1 rootInfo true"));
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void startRequestCountsOnlyMoveAndPassNodesWhenSelectingRange() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      history.add(
          snapshotNode(
              stones(placement(0, 0, Stone.BLACK)), Optional.empty(), Stone.EMPTY, false, 1));
      history.add(passNode(stones(placement(0, 0, Stone.BLACK)), Stone.WHITE, true, 2));
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.BLACK)),
              new int[] {1, 0},
              Stone.BLACK,
              false,
              3));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(2, 3, false);

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3"),
          stoneSet(request.getJSONArray("initialStones")),
          "range analysis should rebuild from the nearest SNAPSHOT anchor.");
      assertEquals("W", request.getString("initialPlayer"));
      assertEquals(
          List.of(List.of("W", "pass")),
          request.getJSONArray("moves").toList(),
          "range analysis should treat SNAPSHOT as a history boundary.");
      assertEquals(List.of(1), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void sendRequestIgnoresDummyPassWhenCollectingMoves() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Stone[] snapshotStones = stones(placement(0, 0, Stone.BLACK));
      Stone[] finalStones = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.BLACK));
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, false, 1));
      BoardData dummyPass = passNode(snapshotStones, Stone.WHITE, true, 2);
      dummyPass.dummy = true;
      history.add(dummyPass);
      history.add(moveNode(finalStones, new int[] {1, 0}, Stone.BLACK, false, 3));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(List.of(List.of("B", "B3")), request.getJSONArray("moves").toList());
      assertEquals(
          List.of(1),
          request.getJSONArray("analyzeTurns").toList(),
          "dummy PASS should stay out of request turn counting.");
    }
  }

  @Test
  void startRequestAllBranchesSkipsDummyPassNodesAndAnalyzesOnlyRealActions() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Stone[] snapshotStones = stones(placement(0, 0, Stone.BLACK));
      Stone[] finalStones = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.BLACK));
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, false, 1));
      BoardData dummyPass = passNode(snapshotStones, Stone.WHITE, true, 2);
      dummyPass.dummy = true;
      history.add(dummyPass);
      history.add(moveNode(finalStones, new int[] {1, 0}, Stone.BLACK, false, 3));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequestAllBranches(false);

      assertEquals(1, engine.requestCount(), "branch analysis should skip dummy PASS nodes.");
      JSONObject request = engine.singleRequest();
      assertEquals(List.of(List.of("B", "B3")), request.getJSONArray("moves").toList());
      assertEquals(List.of(1), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void startRequestAllBranchesSkipsSnapshotNodesAndAnalyzesOnlyRealActions() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK)), Optional.empty(), Stone.EMPTY, false, 58));
      history.add(passNode(stones(placement(0, 0, Stone.BLACK)), Stone.WHITE, true, 59));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequestAllBranches(false);

      assertEquals(1, engine.requestCount(), "all-branches scan should skip SNAPSHOT nodes.");
      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3"),
          stoneSet(request.getJSONArray("initialStones")),
          "all-branches scan should seed analysis from the snapshot board.");
      assertEquals("W", request.getString("initialPlayer"));
      assertEquals(List.of(List.of("W", "pass")), request.getJSONArray("moves").toList());
      assertEquals(List.of(1), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void startRequestSkipsMovesAtTargetVisitsButContinuesLowerVisits() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      int targetVisits = AnalysisEngine.targetAnalysisVisits();
      BoardData complete =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      complete.setPlayouts(targetVisits);
      history.add(complete);
      BoardData lowVisits =
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2);
      lowVisits.setPlayouts(targetVisits - 1);
      history.add(lowVisits);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(-1, -1, false);

      assertEquals(1, engine.requestCount(), "flash analysis should continue partial results.");
      JSONObject request = engine.singleRequest();
      assertEquals(
          List.of(List.of("B", "A3"), List.of("W", "B3")), request.getJSONArray("moves").toList());
      assertEquals(List.of(2), request.getJSONArray("analyzeTurns").toList());
      assertEquals(targetVisits, request.getInt("maxVisits"));
    }
  }

  @Test
  void startRequestOverrideReanalyzesMovesAtTargetVisits() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisAlwaysOverride = true;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData complete =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      complete.setPlayouts(AnalysisEngine.targetAnalysisVisits());
      history.add(complete);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(-1, -1, false);

      assertEquals(1, engine.requestCount(), "explicit override should still reanalyze.");
      JSONObject request = engine.singleRequest();
      assertEquals(List.of(List.of("B", "A3")), request.getJSONArray("moves").toList());
      assertEquals(List.of(1), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void silentStartRequestDoesNotPauseForegroundPonder() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      leelaz.ponder();
      Lizzie.leelaz = leelaz;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequest(-1, -1, false);

      assertEquals(0, leelaz.togglePonderCount);
      assertTrue(leelaz.isPondering());
      assertEquals(1, engine.requestCount());
    }
  }

  @Test
  void startRequestAllBranchesSkipsOnlyBranchNodesAtTargetVisits() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      int targetVisits = AnalysisEngine.targetAnalysisVisits();
      BoardData complete =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      complete.setPlayouts(targetVisits);
      history.add(complete);
      BoardData lowVisits =
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2);
      lowVisits.setPlayouts(targetVisits - 1);
      history.add(lowVisits);
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequestAllBranches(false);

      assertEquals(
          1, engine.requestCount(), "branch flash analysis should continue partial nodes.");
      JSONObject request = engine.singleRequest();
      assertEquals(
          List.of(List.of("B", "A3"), List.of("W", "B3")), request.getJSONArray("moves").toList());
      assertEquals(List.of(2), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void startRequestAllBranchesSkipsSnapshotRootOnlyHistory() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK)), Optional.empty(), Stone.EMPTY, false, 58));
      boardWithHistory(history);
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.startRequestAllBranches(false);

      assertEquals(
          0,
          engine.requestCount(),
          "snapshot-root-only history should not emit branch scan requests.");
    }
  }

  @Test
  void silentParseResultKeepsForegroundCurrentNodeAnalysis() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData current =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      current.winrate = 41.0;
      current.setPlayouts(12);
      history.add(current);
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      leelaz.ponder();
      Lizzie.leelaz = leelaz;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.startRequest(-1, -1, false);

      engine.parseResult(analysisResult(1, 200, 62.0));

      assertEquals(12, current.getPlayouts());
      assertEquals(41.0, current.winrate, 0.0001);
      assertTrue(current.bestMoves.isEmpty());
      assertFalse(engine.isAnalysisInProgress());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void silentParseResultUpdatesNonCurrentNodesWhileForegroundAnalyzes() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData current =
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1);
      history.add(current);
      BoardHistoryNode currentNode = history.getCurrentHistoryNode();
      BoardData next =
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2);
      next.winrate = 39.0;
      next.setPlayouts(8);
      history.add(next);
      history.setHead(currentNode);
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      leelaz.ponder();
      Lizzie.leelaz = leelaz;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();
      engine.sendRequest(history.getCurrentHistoryNode().next().get());

      engine.parseResult(analysisResult(1, 200, 62.0));

      assertEquals(200, next.getPlayouts());
      assertEquals(62.0, next.winrate, 0.0001);
      assertFalse(next.bestMoves.isEmpty());
      waitForMovelistRefreshThreads();
    }
  }

  @Test
  void sendRequestSetupOnlyStartStonePositionIncludesCurrentPlayer() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      Lizzie.board.hasStartStone = true;
      Lizzie.board.startStonelist = new ArrayList<>();
      Lizzie.board.startStonelist.add(startStone(0, 0, true));
      Lizzie.board.startStonelist.add(startStone(1, 0, false));
      history.getStart().getData().blackToPlay = false;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3", "W:B3"),
          stoneSet(request.getJSONArray("initialStones")),
          "setup-only analysis should send configured start stones.");
      assertEquals(
          "W",
          request.getString("initialPlayer"),
          "setup-only analysis should use the current position side to move.");
      assertEquals(List.of(), request.getJSONArray("moves").toList());
      assertEquals(List.of(0), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void sendRequestRootSnapshotPrefersSnapshotStonesOverConfiguredStartStones() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
                  Optional.empty(),
                  Stone.EMPTY,
                  false,
                  58));
      boardWithHistory(history);
      Lizzie.board.hasStartStone = true;
      Lizzie.board.startStonelist = new ArrayList<>();
      Lizzie.board.startStonelist.add(startStone(2, 2, true));
      Lizzie.board.startStonelist.add(startStone(1, 1, false));
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3", "W:B3"),
          stoneSet(request.getJSONArray("initialStones")),
          "root SNAPSHOT should stay the request anchor even when start stones are configured.");
      assertEquals("W", request.getString("initialPlayer"));
      assertEquals(List.of(), request.getJSONArray("moves").toList());
      assertEquals(List.of(0), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void sendRequestStartStonePositionWithMovesKeepsRootInitialPlayer() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      Stone[] afterFirstMove =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(2, 0, Stone.WHITE));
      history.add(moveNode(afterFirstMove, new int[] {2, 0}, Stone.WHITE, true, 1));
      boardWithHistory(history);
      Lizzie.board.hasStartStone = true;
      Lizzie.board.startStonelist = new ArrayList<>();
      Lizzie.board.startStonelist.add(startStone(0, 0, true));
      Lizzie.board.startStonelist.add(startStone(1, 0, false));
      history.getStart().getData().blackToPlay = false;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3", "W:B3"),
          stoneSet(request.getJSONArray("initialStones")),
          "start-stone analysis with moves should still send configured start stones.");
      assertEquals(
          "W",
          request.getString("initialPlayer"),
          "start-stone analysis should keep root side-to-play even after real moves.");
      assertEquals(List.of(List.of("W", "C3")), request.getJSONArray("moves").toList());
      assertEquals(List.of(1), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void sendRequestWithStartStoneAndMidSnapshotUsesNearestSnapshotAsSharedAnchor() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      Stone[] afterRootMove =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(2, 0, Stone.WHITE));
      Stone[] snapshotStones = stones(placement(0, 0, Stone.BLACK), placement(2, 0, Stone.WHITE));
      Stone[] finalStones =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 1, Stone.WHITE),
              placement(2, 0, Stone.WHITE));
      history.add(moveNode(afterRootMove, new int[] {2, 0}, Stone.WHITE, true, 1));
      history.add(snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, true, 1));
      history.add(passNode(snapshotStones, Stone.BLACK, false, 2));
      history.add(moveNode(finalStones, new int[] {1, 1}, Stone.WHITE, true, 3));
      boardWithHistory(history);
      Lizzie.board.hasStartStone = true;
      Lizzie.board.startStonelist = new ArrayList<>();
      Lizzie.board.startStonelist.add(startStone(0, 0, true));
      Lizzie.board.startStonelist.add(startStone(1, 0, false));
      history.getStart().getData().blackToPlay = false;
      TrackingAnalysisEngine engine = TrackingAnalysisEngine.create();

      engine.sendRequest(history.getCurrentHistoryNode());

      JSONObject request = engine.singleRequest();
      assertEquals(
          Set.of("B:A3", "W:C3"),
          stoneSet(request.getJSONArray("initialStones")),
          "start-stone history with a nearer snapshot should use that snapshot board as anchor.");
      assertEquals(
          "B",
          request.getString("initialPlayer"),
          "start-stone history with a nearer snapshot should use that snapshot side-to-play.");
      assertEquals(
          List.of(List.of("B", "pass"), List.of("W", "B2")),
          request.getJSONArray("moves").toList(),
          "analysis moves should stay scoped to actions after the same snapshot anchor.");
      assertEquals(List.of(2), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void restoreClosedEngineBoardStateReplaysFromLatestSnapshotBoundary() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      history.add(snapshotNode(emptyStones(), Optional.empty(), Stone.EMPTY, false, 1));
      history.add(
          moveNode(stones(placement(1, 0, Stone.WHITE)), new int[] {1, 0}, Stone.WHITE, true, 2));
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();

      leelaz.restoreClosedEngineBoardState(false);

      assertEquals("clear_board", leelaz.sentCommands.get(0));
      assertTrue(
          leelaz.sentCommands.get(1).startsWith("loadsgf "),
          "closed-engine restore should land the snapshot board exactly before later actions.");
      assertEquals(
          List.of("WHITE:B3"),
          leelaz.playedMoves,
          "closed-engine restore should replay only later real actions after the snapshot anchor.");
      assertArrayEquals(
          history.getCurrentHistoryNode().getData().stones,
          leelaz.copyStones(),
          "closed-engine restore should match the current board after the exact snapshot restore.");
    }
  }

  @Test
  void restoreClosedEngineBoardStateUsesLoadsgfForDeadSnapshotThenReplaysRealActions()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Stone[] snapshotStones =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.BLACK),
              placement(0, 1, Stone.BLACK),
              placement(1, 1, Stone.WHITE),
              placement(2, 1, Stone.BLACK),
              placement(0, 2, Stone.BLACK),
              placement(1, 2, Stone.BLACK));
      Stone[] finalStones =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.BLACK),
              placement(0, 1, Stone.BLACK),
              placement(1, 1, Stone.WHITE),
              placement(2, 1, Stone.BLACK),
              placement(0, 2, Stone.BLACK),
              placement(1, 2, Stone.BLACK),
              placement(2, 2, Stone.BLACK));
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, false, 58));
      history.add(passNode(snapshotStones, Stone.WHITE, true, 59));
      history.add(moveNode(finalStones, new int[] {2, 2}, Stone.BLACK, false, 60));
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();

      leelaz.restoreClosedEngineBoardState(false);

      assertEquals(
          "clear_board",
          leelaz.sentCommands.get(0),
          "closed-engine restore should always reset the engine first.");
      assertTrue(
          leelaz.sentCommands.get(1).startsWith("loadsgf "),
          "dead snapshot anchors should restore through loadsgf before replaying actions.");
      assertEquals(
          List.of("WHITE:pass", "BLACK:C1"),
          leelaz.playedMoves,
          "after the static snapshot is restored, only real PASS/MOVE actions should be replayed.");
      assertArrayEquals(
          finalStones,
          leelaz.copyStones(),
          "loadsgf plus real actions should reproduce the final board exactly.");
      assertFalse(
          leelaz.isBlackToPlay(),
          "replaying the real actions after loadsgf should preserve the final side to play.");
    }
  }

  @Test
  void restoreClosedEngineBoardStateForRemovedStoneHistoryStillStartsFromSnapshotBoundary()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Stone[] snapshotStones = stones(placement(0, 0, Stone.BLACK), placement(2, 0, Stone.BLACK));
      Stone[] finalStones =
          stones(
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(2, 0, Stone.BLACK));
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(snapshotStones, Optional.empty(), Stone.EMPTY, false, 58));
      history.getCurrentHistoryNode().setRemovedStone();
      history.add(moveNode(finalStones, new int[] {1, 0}, Stone.WHITE, true, 59));
      boardWithHistory(history);
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      Leelaz previousLeelaz = Lizzie.leelaz;
      Lizzie.leelaz = leelaz;
      try {
        leelaz.restoreClosedEngineBoardState(false);
      } finally {
        Lizzie.leelaz = previousLeelaz;
      }

      assertEquals(
          "clear_board",
          leelaz.sentCommands.get(0),
          "closed-engine restore should still clear the engine before restoring removed-stone history.");
      assertTrue(
          leelaz.sentCommands.get(1).startsWith("loadsgf "),
          "removed-stone history should restore from the latest snapshot boundary through loadsgf.");
      assertEquals(
          List.of("WHITE:B3"),
          leelaz.playedMoves,
          "removed-stone history should replay only the real actions after the snapshot boundary.");
      assertArrayEquals(
          finalStones,
          leelaz.copyStones(),
          "closed-engine restore should keep the final board exact after a removed-stone boundary.");
    }
  }

  private static Board boardWithHistory(BoardHistoryList history) throws Exception {
    Board board = allocate(Board.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    board.setHistory(history);
    Lizzie.board = board;
    return board;
  }

  private static BoardData moveNode(
      Stone[] stones, int[] lastMove, Stone color, boolean blackToPlay, int moveNumber) {
    return BoardData.move(
        stones,
        lastMove,
        color,
        blackToPlay,
        zobrist(stones),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static BoardData passNode(
      Stone[] stones, Stone color, boolean blackToPlay, int moveNumber) {
    return BoardData.pass(
        stones, color, blackToPlay, zobrist(stones), moveNumber, new int[BOARD_AREA], 0, 0, 50, 0);
  }

  private static BoardData snapshotNode(
      Stone[] stones,
      Optional<int[]> lastMove,
      Stone lastMoveColor,
      boolean blackToPlay,
      int moveNumber) {
    return BoardData.snapshot(
        stones,
        lastMove,
        lastMoveColor,
        blackToPlay,
        zobrist(stones),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static Stone[] stones(Placement... placements) {
    Stone[] stones = emptyStones();
    for (Placement placement : placements) {
      stones[Board.getIndex(placement.x, placement.y)] = placement.color;
    }
    return stones;
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static Zobrist zobrist(Stone[] stones) {
    Zobrist zobrist = new Zobrist();
    for (int x = 0; x < BOARD_SIZE; x++) {
      for (int y = 0; y < BOARD_SIZE; y++) {
        Stone stone = stones[Board.getIndex(x, y)];
        if (!stone.isEmpty()) {
          zobrist.toggleStone(x, y, stone);
        }
      }
    }
    return zobrist;
  }

  private static Set<String> stoneSet(JSONArray stones) {
    Set<String> result = new LinkedHashSet<>();
    for (Object entry : stones.toList()) {
      List<?> pair = (List<?>) entry;
      result.add(pair.get(0) + ":" + pair.get(1));
    }
    return result;
  }

  private static JSONObject analysisResponse(int id, int visits, double winrate) {
    JSONObject moveInfo = new JSONObject();
    moveInfo.put("order", 0);
    moveInfo.put("move", "C3");
    moveInfo.put("visits", visits);
    moveInfo.put("winrate", winrate);
    moveInfo.put("lcb", winrate);
    moveInfo.put("prior", 0.25);
    moveInfo.put("scoreLead", 1.5);
    moveInfo.put("scoreStdev", 0.3);
    moveInfo.put("pv", new JSONArray().put("C3"));

    JSONObject response = new JSONObject();
    response.put("id", String.valueOf(id));
    response.put("moveInfos", new JSONArray().put(moveInfo));
    return response;
  }

  private static String analysisResult(int id, int visits, double winrate) {
    JSONObject moveInfo = new JSONObject();
    moveInfo.put("move", "B2");
    moveInfo.put("visits", visits);
    moveInfo.put("winrate", winrate / 100.0);
    moveInfo.put("lcb", winrate / 100.0);
    moveInfo.put("prior", 0.25);
    moveInfo.put("scoreLead", 1.5);
    moveInfo.put("scoreStdev", 0.2);
    moveInfo.put("order", 0);
    moveInfo.put("pv", new JSONArray(List.of("B2")));
    JSONObject result = new JSONObject();
    result.put("id", String.valueOf(id));
    result.put("moveInfos", new JSONArray(List.of(moveInfo)));
    return result.toString();
  }

  private static String analysisResultWithOwnership(int id, int visits, double winrate) {
    JSONObject result = new JSONObject(analysisResult(id, visits, winrate));
    JSONArray ownership = new JSONArray();
    for (int index = 0; index < BOARD_AREA; index++) {
      ownership.put(index % 2 == 0 ? 0.25 : -0.25);
    }
    result.put("ownership", ownership);
    return result.toString();
  }

  private static String analysisResultWithRootVisits(
      int id, int moveVisits, int rootVisits, double winrate) {
    JSONObject result = new JSONObject(analysisResult(id, moveVisits, winrate));
    result.put("rootInfo", new JSONObject().put("visits", rootVisits));
    return result.toString();
  }

  private static String emptyAnalysisResult(int id) {
    return new JSONObject()
        .put("id", String.valueOf(id))
        .put("moveInfos", new JSONArray())
        .toString();
  }

  private static void installCompleteAnalysis(
      BoardData data, int visits, boolean includeOwnership) {
    MoveData move = new MoveData();
    move.coordinate = "B2";
    move.playouts = visits;
    move.winrate = 50.0;
    move.scoreMean = 1.5;
    move.scoreStdev = 0.2;
    move.isKataData = true;
    move.order = 0;
    move.variation = List.of("B2");
    data.setPlayouts(visits);
    data.bestMoves = new ArrayList<>(List.of(move));
    data.estimateArray = includeOwnership ? List.of(0.25) : null;
  }

  private static BoardHistoryNode singleUnanalyzedMoveNode() throws Exception {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(
        moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
    boardWithHistory(history);
    return history.getCurrentHistoryNode();
  }

  private static void waitForMovelistRefreshThreads() throws InterruptedException {
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if ("lizzie-movelist-refresh".equals(thread.getName()) && thread.isAlive()) {
        thread.join(1000);
      }
    }
  }

  private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2000;
    while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "condition was not met before timeout.");
  }

  private static Placement placement(int x, int y, Stone color) {
    return new Placement(x, y, color);
  }

  private static Movelist startStone(int x, int y, boolean isBlack) {
    Movelist move = new Movelist();
    move.x = x;
    move.y = y;
    move.isblack = isBlack;
    move.ispass = false;
    return move;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static void setField(Class<?> owner, Object target, String name, Object value)
      throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object getField(Class<?> owner, Object target, String name) throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static Leelaz.ForegroundAnalysisLease foregroundLease(Leelaz engine) throws Exception {
    java.lang.reflect.Constructor<Leelaz.ForegroundAnalysisLease> constructor =
        Leelaz.ForegroundAnalysisLease.class.getDeclaredConstructor(Leelaz.class);
    constructor.setAccessible(true);
    return constructor.newInstance(engine);
  }

  private static boolean dispatchExclusiveLine(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("dispatchExclusiveGtpLine", String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(engine, line);
  }

  private static void setIntField(Class<?> owner, Object target, String name, int value)
      throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.setInt(target, value);
  }

  private static int getExclusiveStopCommandId(Leelaz engine) throws Exception {
    Field sessionField = Leelaz.class.getDeclaredField("exclusiveGtpSession");
    sessionField.setAccessible(true);
    Object session = sessionField.get(engine);
    Field stopCommandIdField = session.getClass().getDeclaredField("stopCommandId");
    stopCommandIdField.setAccessible(true);
    return stopCommandIdField.getInt(session);
  }

  private static String remoteGtpInfoLine(int visits, double winrate, double scoreLead) {
    return remoteGtpInfoLine(visits, visits, winrate, scoreLead);
  }

  private static String remoteGtpInfoLine(
      int moveVisits, int rootVisits, double winrate, double scoreLead) {
    double normalizedWinrate = winrate / 100.0;
    return "info move B2 visits "
        + moveVisits
        + " winrate "
        + normalizedWinrate
        + " scoreLead "
        + scoreLead
        + " order 0 pv B2 rootInfo visits "
        + rootVisits
        + " winrate "
        + normalizedWinrate
        + " scoreLead "
        + scoreLead;
  }

  private static String remoteGtpInfoLineWithoutPv(int visits, double winrate, double scoreLead) {
    double normalizedWinrate = winrate / 100.0;
    return "info move B2 visits "
        + visits
        + " winrate "
        + normalizedWinrate
        + " scoreLead "
        + scoreLead
        + " order 0 rootInfo visits "
        + visits
        + " winrate "
        + normalizedWinrate
        + " scoreLead "
        + scoreLead;
  }

  private static void completeRemoteGtpSearch(
      TrackingAnalysisEngine engine, int visits, double winrate, double scoreLead)
      throws Exception {
    completeRemoteGtpSearch(engine, visits, visits, winrate, scoreLead);
  }

  private static void completeRemoteGtpSearch(
      TrackingAnalysisEngine engine,
      int moveVisits,
      int rootVisits,
      double winrate,
      double scoreLead)
      throws Exception {
    int analyzeCommandId =
        numberedCommandIdStartingWith(lastCommand(engine.sentCommands), "kata-analyze ");
    invokeAnalysisEngineParseLine(engine, "=" + analyzeCommandId);
    invokeAnalysisEngineParseLine(
        engine, remoteGtpInfoLine(moveVisits, rootVisits, winrate, scoreLead));
    int stopCommandId = assertNumberedStopCommand(lastCommand(engine.sentCommands));
    invokeAnalysisEngineParseLine(
        engine, remoteGtpInfoLine(moveVisits, rootVisits, winrate, scoreLead));
    invokeAnalysisEngineParseLine(engine, "");
    invokeAnalysisEngineParseLine(engine, "=" + stopCommandId);
  }

  private static int assertNumberedStopCommand(String command) {
    return numberedCommandId(command, "stop");
  }

  private static int assertNumberedAnalyzeCommand(String command, String expectedCommand) {
    return numberedCommandId(command, expectedCommand);
  }

  private static int numberedCommandId(String command, String expectedCommand) {
    assertTrue(command != null, "remote GTP command must be present.");
    int separator = command.indexOf(' ');
    assertTrue(separator > 0, "remote GTP commands must use a numeric command id.");
    assertEquals(expectedCommand, command.substring(separator + 1));
    return Integer.parseInt(command.substring(0, separator));
  }

  private static int numberedCommandIdStartingWith(String command, String expectedPrefix) {
    assertTrue(command != null, "remote GTP command must be present.");
    int separator = command.indexOf(' ');
    assertTrue(separator > 0, "remote GTP commands must use a numeric command id.");
    assertTrue(command.substring(separator + 1).startsWith(expectedPrefix));
    return Integer.parseInt(command.substring(0, separator));
  }

  private static void invokeAnalysisEngineParseLine(AnalysisEngine engine, String line)
      throws Exception {
    Method method = AnalysisEngine.class.getDeclaredMethod("parseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void invokeAnalysisEngineSetResult(AnalysisEngine engine) throws Exception {
    Method method = AnalysisEngine.class.getDeclaredMethod("setResult");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static void invokeAnalysisEngineFinishFailedRequestDispatch(AnalysisEngine engine)
      throws Exception {
    Method method =
        AnalysisEngine.class.getDeclaredMethod("finishFailedRequestDispatch", boolean.class);
    method.setAccessible(true);
    method.invoke(engine, false);
  }

  private static void invokeAnalysisEnginePrepareRequestState(
      AnalysisEngine engine, boolean showProgressDialog) throws Exception {
    Method method = AnalysisEngine.class.getDeclaredMethod("prepareRequestState", boolean.class);
    method.setAccessible(true);
    method.invoke(engine, showProgressDialog);
  }

  private static void invokeAnalysisEngineResumeForeground(AnalysisEngine engine) throws Exception {
    Method method = AnalysisEngine.class.getDeclaredMethod("resumeForegroundAnalysisIfRequested");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static int countCommand(List<String> commands, String expected) {
    int count = 0;
    for (String command : commands) {
      if (expected.equals(command)) {
        count++;
      }
    }
    return count;
  }

  private static String lastCommand(List<String> commands) {
    return commands.get(commands.size() - 1);
  }

  private static int countCommandStartingWith(List<String> commands, String prefix) {
    int count = 0;
    for (String command : commands) {
      if (command.startsWith(prefix)) {
        count++;
      }
    }
    return count;
  }

  private static int countCommandContaining(List<String> commands, String fragment) {
    int count = 0;
    for (String command : commands) {
      if (command.contains(fragment)) {
        count++;
      }
    }
    return count;
  }

  private static final class Placement {
    private final int x;
    private final int y;
    private final Stone color;

    private Placement(int x, int y, Stone color) {
      this.x = x;
      this.y = y;
      this.color = color;
    }
  }

  private Leelaz reusableForegroundEngine(boolean katago) throws Exception {
    return reusableForegroundEngine(new Leelaz(""), katago);
  }

  private <T extends Leelaz> T reusableForegroundEngine(T engine, boolean katago) throws Exception {
    engine.isLoaded = true;
    engine.started = true;
    engine.isKatago = katago;
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
    engine.installCommandOutputForTest(new ByteArrayOutputStream());
    Field capabilityDiscovery = Leelaz.class.getDeclaredField("endGetCommandList");
    capabilityDiscovery.setAccessible(true);
    capabilityDiscovery.set(engine, true);
    createdForegroundEngines.add(engine);
    return engine;
  }

  private static ByteArrayOutputStream installLeelazOutput(Leelaz engine) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    engine.installCommandOutputForTest(output);
    return output;
  }

  private static void closeExclusiveSessionForTest(Leelaz engine) throws Exception {
    if (engine == null) {
      return;
    }
    Field field = Leelaz.class.getDeclaredField("exclusiveGtpSession");
    field.setAccessible(true);
    Object session = field.get(engine);
    if (session == null) {
      return;
    }
    Method cancelInitial =
        Leelaz.class.getDeclaredMethod("cancelExclusiveGtpInitialStopTimeout", session.getClass());
    cancelInitial.setAccessible(true);
    cancelInitial.invoke(engine, session);
    Method cancelRelease =
        Leelaz.class.getDeclaredMethod("cancelExclusiveGtpReleaseStopTimeout", session.getClass());
    cancelRelease.setAccessible(true);
    cancelRelease.invoke(engine, session);
    Method method = Leelaz.class.getDeclaredMethod("closeExclusiveGtpSession", session.getClass());
    method.setAccessible(true);
    method.invoke(engine, session);
  }

  private static void completeForegroundRestore(Leelaz engine) throws Exception {
    Field sessionField = Leelaz.class.getDeclaredField("foregroundRestoreSession");
    sessionField.setAccessible(true);
    Object session = sessionField.get(engine);
    assertTrue(session != null, "foreground restore session should be active");
    Class<?> sessionType = session.getClass();
    Field restoreThreadField = sessionType.getDeclaredField("restoreThread");
    restoreThreadField.setAccessible(true);
    Field restoreCompletedField = sessionType.getDeclaredField("restoreCompleted");
    restoreCompletedField.setAccessible(true);
    Method completeMethod =
        Leelaz.class.getDeclaredMethod("completeForegroundRestore", sessionType);
    completeMethod.setAccessible(true);
    for (int attempt = 0; attempt < 5; attempt++) {
      Thread restoreThread = (Thread) restoreThreadField.get(session);
      if (restoreThread != null && restoreThread != Thread.currentThread()) {
        restoreThread.join(2000);
        assertFalse(restoreThread.isAlive(), "foreground restore attempt did not finish");
      }
      completeMethod.invoke(engine, session);
      if ((boolean) restoreCompletedField.get(session)) {
        return;
      }
    }
    assertTrue(false, "foreground restore retries did not converge");
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Leelaz previousLeelaz;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame,
        Leelaz previousLeelaz) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousLeelaz = previousLeelaz;
    }

    private static TestEnvironment open() throws Exception {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Config previousConfig = Lizzie.config;
      Board previousBoard = Lizzie.board;
      LizzieFrame previousFrame = Lizzie.frame;
      Leelaz previousLeelaz = Lizzie.leelaz;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      Config config = allocate(Config.class);
      config.analysisMaxVisits = 32;
      config.batchAnalysisPlayouts = 64;
      config.showPvVisits = false;
      config.showKataGoEstimate = false;
      config.useMovesOwnership = false;
      config.analysisUseCurrentRules = false;
      config.analysisSpecificRules = "";
      config.currentKataGoRules = "";
      config.autoLoadKataRules = false;
      config.kataRules = "";
      config.analysisEnginePreLoad = false;
      config.readKomi = true;
      Lizzie.config = config;

      TrackingLizzieFrame frame = allocate(TrackingLizzieFrame.class);
      frame.isBatchAnalysisMode = false;
      frame.priorityMoveCoords = new ArrayList<>();
      frame.clickOrder = -1;
      frame.selectedorder = -1;
      frame.currentRow = -1;
      frame.clickbadmove = LizzieFrame.outOfBoundCoordinate;
      frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
      frame.suggestionclick = LizzieFrame.outOfBoundCoordinate;
      Lizzie.frame = frame;
      Lizzie.leelaz = allocate(Leelaz.class);

      return new TestEnvironment(
          previousBoardWidth,
          previousBoardHeight,
          previousConfig,
          previousBoard,
          previousFrame,
          previousLeelaz);
    }

    @Override
    public void close() throws Exception {
      closeExclusiveSessionForTest(Lizzie.leelaz);
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousLeelaz;
    }
  }

  private static final class TrackingLizzieFrame extends LizzieFrame {
    private int flashAutoAnaSaveAndLoadCalls;
    private int foregroundReservationConflictCount;
    private int contributionStarts;
    private int contributeBenchmarkConflictCount;
    private int retainedModeConflictCount;

    private TrackingLizzieFrame() {}

    @Override
    public void requestProblemListRefresh() {}

    @Override
    public void flashAutoAnaSaveAndLoad() {
      flashAutoAnaSaveAndLoadCalls++;
    }

    @Override
    public void clearKataEstimate() {}

    @Override
    protected void showForegroundEngineModeReservationConflict() {
      foregroundReservationConflictCount++;
    }

    @Override
    protected void showForegroundEngineLeaseConflict() {
      retainedModeConflictCount++;
    }

    @Override
    protected void startContributeEngineReserved() {
      contributionStarts++;
    }

    @Override
    protected void showContributeBenchmarkConflict() {
      contributeBenchmarkConflictCount++;
    }
  }

  private static final class SilentProgressTrackingFrame extends LizzieFrame {
    private int silentProgressRefreshCalls;
    private int fullRefreshCalls;

    private SilentProgressTrackingFrame() {}

    @Override
    public void refreshSilentAnalysisProgress() {
      silentProgressRefreshCalls++;
    }

    @Override
    public void refresh() {
      fullRefreshCalls++;
    }

    @Override
    public void requestProblemListRefresh() {}
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super(null);
    }

    @Override
    public void addCommand(String command, int commandNumber, String engineName) {}

    @Override
    public void addLine(String line) {}

    @Override
    public void addErrorLine(String line) {}
  }

  private static final class PonderTrackingLeelaz extends Leelaz {
    private boolean pondering;
    private int notPonderingCalls;
    private int nameCommandCalls;
    private int ponderCalls;

    private PonderTrackingLeelaz() throws IOException {
      super("");
    }

    @Override
    public boolean isPondering() {
      return pondering;
    }

    @Override
    public void notPondering() {
      notPonderingCalls++;
      pondering = false;
    }

    @Override
    public void nameCmd() {
      nameCommandCalls++;
    }

    @Override
    public void ponder() {
      ponderCalls++;
      pondering = true;
    }
  }

  private static final class DeferredRestoreLeelaz extends Leelaz {
    private Runnable afterRestore;
    private Runnable afterRestoreFailure;

    private DeferredRestoreLeelaz() throws IOException {
      super("");
    }

    @Override
    public boolean endForegroundAnalysisLease(Object owner, Runnable completion, Runnable failure) {
      afterRestore = completion;
      afterRestoreFailure = failure;
      return true;
    }

    private void completeRestore() {
      Runnable completion = afterRestore;
      afterRestore = null;
      completion.run();
    }

    private void failRestore() {
      Runnable failure = afterRestoreFailure;
      afterRestore = null;
      afterRestoreFailure = null;
      failure.run();
    }
  }

  private static final class TrackingWaitForAnalysis extends WaitForAnalysis {
    private int progressCalls;
    private int currentMove;
    private int totalMoves;
    private int visibilityCalls;
    private boolean lastVisible;

    private TrackingWaitForAnalysis() {}

    @Override
    public void setProgress(int curMove, int allMove) {
      progressCalls++;
      currentMove = curMove;
      totalMoves = allMove;
    }

    @Override
    public void setVisible(boolean visible) {
      visibilityCalls++;
      lastVisible = visible;
    }
  }

  private static final class TrackingAnalysisEngine extends AnalysisEngine {
    private List<String> sentCommands;
    private boolean failSends;
    private String synchronousResponse;
    private String synchronousEngineLine;
    private int normalQuitCount;

    private TrackingAnalysisEngine() throws IOException {
      super(true);
    }

    private static TrackingAnalysisEngine create() throws Exception {
      TrackingAnalysisEngine engine = allocate(TrackingAnalysisEngine.class);
      engine.sentCommands = new CopyOnWriteArrayList<>();
      setField(
          AnalysisEngine.class, engine, "analyzeMap", new java.util.HashMap<Integer, Object>());
      setIntField(AnalysisEngine.class, engine, "globalID", 1);
      setField(AnalysisEngine.class, engine, "waitFrame", null);
      setField(AnalysisEngine.class, engine, "silentProgress", false);
      setField(AnalysisEngine.class, engine, "shouldRePonder", false);
      setField(AnalysisEngine.class, engine, "isLoaded", true);
      setField(
          AnalysisEngine.class,
          engine,
          "foregroundRestoreResult",
          ForegroundRestoreResult.NOT_REQUIRED);
      setField(AnalysisEngine.class, engine, "resourceBundle", Lizzie.resourceBundle);
      return engine;
    }

    @Override
    public boolean sendCommand(String command) {
      if (failSends) {
        return false;
      }
      sentCommands.add(command);
      String response = synchronousResponse;
      synchronousResponse = null;
      if (response != null) {
        parseResult(response);
      }
      String engineLine = synchronousEngineLine;
      synchronousEngineLine = null;
      if (engineLine != null) {
        try {
          invokeAnalysisEngineParseLine(this, engineLine);
        } catch (Exception ex) {
          throw new RuntimeException(ex);
        }
      }
      return true;
    }

    @Override
    public void normalQuit() {
      normalQuitCount++;
    }

    private JSONObject singleRequest() {
      assertEquals(1, sentCommands.size(), "test should capture exactly one analysis request.");
      return new JSONObject(sentCommands.get(0));
    }

    private int requestCount() {
      return sentCommands.size();
    }

    private JSONObject requestAt(int index) {
      return new JSONObject(sentCommands.get(index));
    }

    @SuppressWarnings("unchecked")
    private void trackPending(int id, BoardHistoryNode node) throws Exception {
      Field field = AnalysisEngine.class.getDeclaredField("analyzeMap");
      field.setAccessible(true);
      ((java.util.Map<Integer, BoardHistoryNode>) field.get(this)).put(id, node);
      setField(AnalysisEngine.class, this, "requestDispatchComplete", true);
    }

    private int pendingRequestCount() throws Exception {
      Field field = AnalysisEngine.class.getDeclaredField("analyzeMap");
      field.setAccessible(true);
      return ((java.util.Map<?, ?>) field.get(this)).size();
    }

    private Runnable completionCallback() throws Exception {
      Field field = AnalysisEngine.class.getDeclaredField("completionCallback");
      field.setAccessible(true);
      return (Runnable) field.get(this);
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE;

    static {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        UNSAFE = (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }
}
