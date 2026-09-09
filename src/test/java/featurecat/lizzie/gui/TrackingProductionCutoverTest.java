package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.analysis.MoveRankEvaluationMode;
import featurecat.lizzie.analysis.ReadBoard;
import featurecat.lizzie.analysis.TrackingAnalysisController;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrackingProductionCutoverTest {
  @Test
  void userPausedAnalysisDoesNotStartCapabilityProbe() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      Field paused = LizzieFrame.class.getDeclaredField("userAnalysisPaused");
      paused.setAccessible(true);
      paused.setBoolean(environment.frame, true);
      assertFalse(environment.engine.startMoveFocusProbeAfterInitialization());
      assertFalse(environment.engine.ponderIfAnalysisControlAllows());
      assertEquals("", environment.commands());
      assertEquals(Leelaz.MoveFocusCapability.UNKNOWN, environment.engine.moveFocusCapability());
    }
  }

  @Test
  void userPauseDuringRejectedProbeDoesNotRestartOrdinaryAnalysis() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      assertTrue(environment.engine.startMoveFocusProbeAfterInitialization());
      String probe = environment.commands().trim();
      String id = probe.substring(0, probe.indexOf(' '));
      environment.engine.pauseForAnalysisControl(() -> {});
      environment.dispatch("?" + id + " unknown analyze option focus");
      environment.dispatch("");
      environment.respondedCommands = 1;
      environment.settleCommands();
      assertEquals(Leelaz.MoveFocusCapability.UNSUPPORTED, environment.engine.moveFocusCapability());
      assertFalse(environment.engine.isPondering());
      assertEquals(1, environment.commands().lines().filter(line -> line.contains("kata-analyze")).count());
    }
  }

  @Test
  void successfulProbeAfterUserPauseDoesNotRestartOrdinaryAnalysis() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      assertTrue(environment.engine.startMoveFocusProbeAfterInitialization());
      String probe = environment.commands().trim();
      String id = probe.substring(0, probe.indexOf(' '));
      environment.dispatch("=" + id);
      environment.respondedCommands = 1;
      environment.streaming = true;
      environment.engine.pauseForAnalysisControl(() -> {});
      environment.settleCommands();
      assertEquals(Leelaz.MoveFocusCapability.SUPPORTED, environment.engine.moveFocusCapability());
      assertFalse(environment.engine.isPondering());
      assertEquals(1, environment.commands().lines().filter(line -> line.contains("kata-analyze")).count());
    }
  }

  @Test
  void probeCompletionRespectsFramePauseEvenWithoutANewPauseGeneration() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      assertTrue(environment.engine.startMoveFocusProbeAfterInitialization());
      Field paused = LizzieFrame.class.getDeclaredField("userAnalysisPaused");
      paused.setAccessible(true);
      paused.setBoolean(environment.frame, true);
      environment.settleCommands();
      assertEquals(Leelaz.MoveFocusCapability.SUPPORTED, environment.engine.moveFocusCapability());
      assertFalse(environment.engine.isPondering());
      assertEquals(1, environment.commands().lines().filter(line -> line.contains("kata-analyze")).count());
    }
  }

  @Test
  void capabilityProbeCannotGrantSupportBeforeItsFinalResponseTerminates() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      assertTrue(environment.engine.startMoveFocusProbeAfterInitialization());
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
      while (!environment.commands().contains("focus") && System.nanoTime() < deadline) {
        Thread.sleep(1);
      }
      assertEquals(Leelaz.MoveFocusCapability.PROBING, environment.engine.moveFocusCapability());
      String[] commands = environment.commands().trim().split("\n");
      String probe = commands[0];
      assertTrue(probe.contains("focus pass 0"), probe);
      String probeId = probe.substring(0, probe.indexOf(' '));
      environment.dispatch("=" + probeId);
      assertEquals(Leelaz.MoveFocusCapability.PROBING, environment.engine.moveFocusCapability());
      assertEquals(TrackingAnalysisController.AddResult.LEASE_UNAVAILABLE,
          environment.frame.addTrackingPoint("A1"));
      environment.respondedCommands = 1;
      environment.streaming = true;
      environment.settleCommands();
      assertEquals(Leelaz.MoveFocusCapability.SUPPORTED, environment.engine.moveFocusCapability());
    }
  }

  @Test
  void rejectedProbePreservesOrdinaryAnalysisAndIsNotRepeated() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      assertTrue(environment.engine.startMoveFocusProbeAfterInitialization());
      String probe = environment.commands().trim();
      String id = probe.substring(0, probe.indexOf(' '));
      environment.dispatch("?" + id + " unknown analyze option focus");
      assertEquals(Leelaz.MoveFocusCapability.PROBING, environment.engine.moveFocusCapability());
      environment.dispatch("");
      environment.respondedCommands = 1;
      environment.settleCommands();
      assertEquals(Leelaz.MoveFocusCapability.UNSUPPORTED, environment.engine.moveFocusCapability());
      assertEquals(TrackingAnalysisController.AddResult.LEASE_UNAVAILABLE,
          environment.frame.addTrackingPoint("A1"));
      environment.sendOrdinaryInfo("info move A1 visits 40 order 0 winrate 0.51 pv A1");
      assertEquals(40, candidate("A1").playouts);
      environment.engine.ponder();
      environment.settleCommands();
      assertEquals(1, environment.commands().lines().filter(line -> line.contains("focus pass")).count());
    }
  }

  @Test
  void retiredProbeResponseCannotGrantSupportToTheReplacementBinding() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      assertTrue(environment.engine.startMoveFocusProbeAfterInitialization());
      String probe = environment.commands().trim();
      String oldId = probe.substring(0, probe.indexOf(' '));
      Method install = Leelaz.class.getDeclaredMethod("installFreshCommandStreamsForTest",
          java.io.InputStream.class, java.io.OutputStream.class, java.io.InputStream.class);
      install.setAccessible(true);
      install.invoke(environment.engine, java.io.InputStream.nullInputStream(), environment.output,
          java.io.InputStream.nullInputStream());
      environment.output.reset();
      environment.respondedCommands = 0;
      environment.streaming = false;
      environment.dispatch("=" + oldId);
      environment.dispatch("");
      assertEquals(Leelaz.MoveFocusCapability.UNKNOWN, environment.engine.moveFocusCapability());
      environment.startSupportedAnalysis();
      assertEquals(1, environment.commands().lines().filter(line -> line.contains("focus pass")).count());
    }
  }

  @Test
  void localAndStableReadBoardEntriesUseOrdinaryFocusAndKeepAttentionAfterTarget() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      environment.startSupportedAnalysis();
      LizzieFrame frame = environment.frame;
      assertEquals(TrackingAnalysisController.AddResult.ADDED, frame.addTrackingPoint("A1"));
      environment.settleCommands();
      assertEquals(TrackingAnalysisController.AddResult.ADDED, frame.addTrackingPoint("B2"));
      environment.settleCommands();
      environment.sendOrdinaryInfo(payload(50, 110, 120, 0.50, 0.60));
      assertTrue(frame.trackingDisplaySnapshot().activePoints().isEmpty(),
          "active=" + frame.trackingDisplaySnapshot().activePoints() + " visits="
              + frame.trackingDisplaySnapshot().visits() + " root=" + Lizzie.board.getData().rootVisits
              + " commands=" + environment.commands());
      assertEquals(java.util.Set.of("A1", "B2"), frame.trackingDisplaySnapshot().selectedPoints());
      environment.settleCommands();
      assertFalse(frame.trackingDisplaySnapshot().active());
      assertTrue(environment.engine.isPondering());
      assertFalse(environment.commands().contains("allow B"));
      frame.clearTrackingPoints();
      environment.settleCommands();
      environment.installStableReadBoard();
      assertEquals(TrackingAnalysisController.AddResult.ADDED, frame.addTrackingPoint("A1"));
      assertTrue(frame.trackingDisplaySnapshot().context().readBoardContext().isPresent());
    }
  }

  @Test
  void returningToAcceptedReadBoardPositionAllowsNewEvaluationWithoutAnotherFrame() throws Exception {
    BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
    try (TestEnvironment environment = TestEnvironment.open()) {
      LizzieFrame.boardRenderer = new BoardRenderer(false);
      environment.startSupportedAnalysis();
      Lizzie.board.place(0, 0, Stone.BLACK);
      environment.settleCommands();
      environment.installStableReadBoard("re=3,0", "re=0,0");
      ReadBoard helper = environment.frame.readBoard;
      assertEquals(TrackingAnalysisController.AddResult.ADDED,
          environment.frame.addTrackingPoint("A1"));
      environment.settleCommands();
      environment.sendOrdinaryInfo("info move A1 visits 20 order 0 winrate 0.51 pv A1 rootInfo visits 30");
      assertEquals(20, environment.frame.trackingDisplaySnapshot().visits().get("A1"));
      long acceptedRevision = Lizzie.board.getContextRevision();
      assertTrue(Lizzie.board.previousMove(false));
      environment.settleCommands();
      assertFalse(helper.snapshot().stable());
      assertTrue(environment.frame.trackingDisplaySnapshot().selectedPoints().isEmpty());
      assertTrue(Lizzie.board.nextMove(false));
      assertFalse(environment.frame.canStartTrackingAnalysis());
      environment.settleCommands();
      assertTrue(Lizzie.board.getContextRevision() > acceptedRevision);
      assertTrue(helper.snapshot().stable());
      assertTrue(environment.frame.trackingDisplaySnapshot().selectedPoints().isEmpty());
      assertEquals(TrackingAnalysisController.AddResult.ADDED,
          environment.frame.addTrackingPoint("B2"));
    } finally {
      LizzieFrame.boardRenderer = previousRenderer;
    }
  }

  @Test
  void nativeCrlfIsAcceptedButMalformedCellsCannotPublishAnAcceptedFrame() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      environment.installStableReadBoard();
      ReadBoard readBoard = environment.frame.readBoard;
      readBoard.parseLine("re=0,0\r\n");
      assertFalse(readBoard.snapshot().stable());
      readBoard.parseLine("re=0,0\r\n");
      readBoard.parseLine("end\r\n");
      assertTrue(readBoard.snapshot().stable());
      readBoard.parseLine("re=0junk,0");
      readBoard.parseLine("re=0,0");
      readBoard.parseLine("end");
      assertFalse(readBoard.snapshot().stable());
      assertEquals(
          TrackingAnalysisController.AddResult.LEASE_UNAVAILABLE,
          environment.frame.addTrackingPoint("A1"));
    }
  }

  @Test
  void stoppingDiscardsMalformedSamplingBeforeTheFirstResumedFrame() throws Exception {
    for (String boundary : List.of("endsync", "stopsync")) {
      try (TestEnvironment environment = TestEnvironment.open()) {
        environment.installStableReadBoard();
        ReadBoard helper = environment.frame.readBoard;
        helper.parseLine("re=x,0");
        helper.parseLine(boundary);
        assertFalse(environment.frame.canStartTrackingAnalysis());
        helper.parseLine("re=0,0");
        helper.parseLine("re=0,0");
        helper.parseLine("end");
        environment.completeSyncConfirmation();
        assertTrue(environment.frame.canStartTrackingAnalysis(), boundary);
        assertEquals(TrackingAnalysisController.AddResult.ADDED,
            environment.frame.addTrackingPoint("A1"));
      }
    }
  }

  @Test
  void acceptedNodeIdentityDoesNotOverridePositionHistoryOrHelperMismatch() throws Exception {
    List<Runnable> mutations =
        List.of(
            () -> Lizzie.board.getHistory().getData().stones[0] = Stone.BLACK,
            () -> Lizzie.board.getHistory().getData().blackToPlay = false,
            () -> Board.boardWidth = 3,
            () -> Board.boardHeight = 3,
            () -> Lizzie.config.currentKataGoRules = "japanese",
            () -> Lizzie.board.getHistory().getGameInfo().setKomi(0.5),
            () ->
                Lizzie.board.setHistory(new BoardHistoryList(Lizzie.board.getHistory().getData())),
            () -> Lizzie.frame.readBoard = null);
    for (Runnable mutation : mutations) {
      try (TestEnvironment environment = TestEnvironment.open()) {
        environment.installStableReadBoard();
        ReadBoard helper = environment.frame.readBoard;
        mutation.run();
        assertFalse(helper.snapshot().stable());
        if (environment.frame.readBoard != null) {
          assertFalse(environment.frame.canStartTrackingAnalysis());
        }
      }
    }
  }

  @Test
  void stoppedOrIncompleteFrameCannotReadmitAnOldAcceptedPosition() throws Exception {
    for (String boundary : List.of("endsync", "stopsync", "clear", "re=0,0")) {
      try (TestEnvironment environment = TestEnvironment.open()) {
        environment.installStableReadBoard();
        ReadBoard helper = environment.frame.readBoard;
        helper.parseLine(boundary);
        assertFalse(environment.frame.canStartTrackingAnalysis());
        helper.onLocalHistoryNavigation();
        assertFalse(helper.snapshot().stable());
        assertEquals(
            TrackingAnalysisController.AddResult.LEASE_UNAVAILABLE,
            environment.frame.addTrackingPoint("A1"));
      }
    }
  }

  @Test
  void lateFrameAcceptanceCannotReopenAStoppedHelper() throws Exception {
    BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
    try (TestEnvironment environment = TestEnvironment.open()) {
      LizzieFrame.boardRenderer = new BoardRenderer(false);
      environment.installStableReadBoard();
      ReadBoard helper = environment.frame.readBoard;
      java.util.concurrent.atomic.AtomicBoolean reached =
          new java.util.concurrent.atomic.AtomicBoolean();
      ((TrackingBoard) Lizzie.board).afterSyncMove =
          () -> {
            assertFalse(environment.frame.canStartTrackingAnalysis());
            helper.parseLine("endsync");
            reached.set(true);
          };
      helper.parseLine("re=3,0");
      helper.parseLine("re=0,0");
      helper.parseLine("end");
      environment.acknowledgePositionCommands();
      assertTrue(reached.get());
      assertFalse(helper.snapshot().stable());
      assertEquals(
          TrackingAnalysisController.AddResult.LEASE_UNAVAILABLE,
          environment.frame.addTrackingPoint("B2"));
    } finally {
      LizzieFrame.boardRenderer = previousRenderer;
    }
  }

  @Test
  void sameReadBoardFrameRetainsFocusButPendingFrameRejectsNewRequest() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      environment.startSupportedAnalysis();
      environment.installStableReadBoard();
      assertEquals(TrackingAnalysisController.AddResult.ADDED,
          environment.frame.addTrackingPoint("A1"));
      environment.settleCommands();
      String before = environment.commands();
      ReadBoard helper = environment.frame.readBoard;
      helper.parseLine("re=0,0");
      assertFalse(environment.frame.canStartTrackingAnalysis());
      assertEquals(java.util.Set.of("A1"), environment.frame.trackingDisplaySnapshot().selectedPoints());
      helper.parseLine("re=0,0");
      helper.parseLine("end");
      environment.completeSyncConfirmation();
      assertTrue(environment.frame.canStartTrackingAnalysis());
      assertEquals(before, environment.commands());
      assertEquals(java.util.Set.of("A1"), environment.frame.trackingDisplaySnapshot().activePoints());
    }
  }

  @Test
  void explicitFocusAdoptsWholeLowRootPayloadAndCancellationDoesNotRollItBack() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      environment.startSupportedAnalysis();
      Lizzie.config.enableLizzieCache = true;
      environment.sendOrdinaryInfo(payload(10000, 9000, 1000, 0.90, 0.91));
      environment.engine.sendCommand("kata-analyze B 10 rootInfo true");
      environment.settleCommands();
      environment.sendOrdinaryInfo(payload(1000, 20, 30, 0.40, 0.60));
      assertEquals(10000, Lizzie.board.getData().rootVisits);
      assertEquals(TrackingAnalysisController.AddResult.ADDED,
          environment.frame.addTrackingPoint("A1"));
      assertEquals(1000, Lizzie.board.getData().rootVisits);
      assertEquals(20, candidate("A1").playouts);
      environment.settleCommands();
      environment.sendOrdinaryInfo(payload(1000, 100, 40, 0.50, 0.65));
      environment.settleCommands();
      environment.frame.clearTrackingPoints();
      environment.settleCommands();
      environment.sendOrdinaryInfo(payload(1000, 100, 40, 0.55, 0.70));
      assertEquals(1000, Lizzie.board.getData().rootVisits);
      assertEquals(55.0, candidate("A1").winrate, 0.001);
      assertTrue(environment.frame.trackingDisplaySnapshot().selectedPoints().isEmpty());
      String saved = featurecat.lizzie.rules.SGFParser.saveToString(false);
      assertTrue(saved.contains("rootVisits=1000"));
      var loaded = featurecat.lizzie.rules.SGFParser.parseSgf(saved, true);
      assertEquals(1000, loaded.getData().rootVisits);
      assertEquals(100, loaded.getData().bestMoves.stream()
          .filter(move -> move.coordinate.equals("A1")).findFirst().orElseThrow().playouts);
    }
  }

  @Test
  void outlineSwitchDoesNotHideCompletedCandidateOrRestartFocus() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      environment.startSupportedAnalysis();
      environment.sendOrdinaryInfo(payload(200, 120, 130, 0.50, 0.60)
          .replace("edgeVisits 0", "edgeVisits 10"));
      Lizzie.config.showTrackingPointOutline = false;
      BufferedImage ordinary = renderMainBoard();
      String before = environment.commands();
      assertEquals(TrackingAnalysisController.AddResult.ADDED,
          environment.frame.addTrackingPoint("A1"));
      environment.settleCommands();
      assertEquals(before, environment.commands());
      assertTrue(environment.frame.trackingDisplaySnapshot().activePoints().isEmpty());
      BufferedImage attended = renderMainBoard();
      assertTrue(java.util.Arrays.equals(
          ordinary.getRGB(0, 0, 180, 180, null, 0, 180),
          attended.getRGB(0, 0, 180, 180, null, 0, 180)));
      Lizzie.config.showTrackingPointOutline = true;
      assertTrue(hasVisiblePaint(renderTrackingOverlay(configuredRenderer())));
      Lizzie.config.showTrackingPointOutline = false;
      assertFalse(hasVisiblePaint(renderTrackingOverlay(configuredRenderer())));
      assertEquals(before, environment.commands());
      assertTrue(environment.frame.isTrackingPoint("A1"));
    }
  }

  @Test
  void unsupportedEngineModesRejectFocusWithoutWriting() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      environment.startSupportedAnalysis();
      String before = environment.commands();
      environment.engine.useJavaSSH = true;
      assertFalse(environment.frame.canStartTrackingAnalysis());
      environment.engine.useJavaSSH = false;
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      assertFalse(environment.frame.canStartTrackingAnalysis());
      assertEquals(before, environment.commands());
    }
  }
  @Test
  void removingSelectedFocusBeforeItsPhysicalWriteCannotSendTheRetiredPoint() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      environment.startSupportedAnalysis();
      assertEquals(TrackingAnalysisController.AddResult.ADDED,
          environment.frame.addTrackingPoint("A1"));
      Field field = EngineManager.class.getDeclaredField("ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK");
      field.setAccessible(true);
      var admission = (java.util.concurrent.locks.ReentrantLock) field.get(null);
      var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
      Thread writer = new Thread(() -> {
        try { environment.settleCommands(); }
        catch (Throwable thrown) { failure.set(thrown); }
      }, "focus-selected-before-write");
      admission.lock();
      try {
        writer.start();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (!admission.hasQueuedThread(writer) && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue(admission.hasQueuedThread(writer));
        assertTrue(environment.frame.removeTrackingPoint("A1"));
      } finally {
        admission.unlock();
        writer.join(3000);
      }
      assertFalse(writer.isAlive());
      assertEquals(null, failure.get());
      environment.settleCommands();
      assertFalse(environment.commands().contains("focus A1"), environment.commands());
      assertTrue(environment.frame.trackingDisplaySnapshot().selectedPoints().isEmpty());
      assertTrue(environment.engine.isLoaded());
    }
  }

  @Test
  void addingFocusDoesNotRestartTheOrdinaryTimeBudget() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      environment.startSupportedAnalysis();
      Lizzie.config.limitTime = true;
      Lizzie.config.maxAnalyzeTimeMillis = 100;
      Thread.sleep(150);
      assertEquals(TrackingAnalysisController.AddResult.ADDED,
          environment.frame.addTrackingPoint("A1"));
      environment.settleCommands();
      environment.dispatch(payload(200, 20, 130, 0.50, 0.60));
      environment.settleCommands();
      assertTrue(environment.engine.isStopPonderingByLimit());
      assertFalse(environment.frame.trackingDisplaySnapshot().active());
    }
  }

  @Test
  void manualPauseRetainsAttentionAndAnalysisWithoutRestartingGain() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      environment.startSupportedAnalysis();
      assertEquals(TrackingAnalysisController.AddResult.ADDED,
          environment.frame.addTrackingPoint("A1"));
      environment.settleCommands();
      environment.sendOrdinaryInfo(payload(200, 40, 130, 0.50, 0.60));
      environment.engine.pauseForAnalysisControl(() -> {});
      environment.settleCommands();
      assertFalse(environment.engine.isPondering());
      assertEquals(java.util.Set.of("A1"), environment.frame.trackingDisplaySnapshot().selectedPoints());
      assertTrue(environment.frame.trackingDisplaySnapshot().activePoints().isEmpty());
      assertFalse(environment.frame.trackingDisplaySnapshot().cancellationPending());
      assertEquals(40, candidate("A1").playouts);
      String paused = environment.commands();
      environment.dispatch(payload(300, 1000, 140, 0.99, 0.60));
      environment.settleCommands();
      assertEquals(paused, environment.commands());
      assertTrue(environment.frame.trackingDisplaySnapshot().activePoints().isEmpty());
      environment.engine.ponder();
      environment.settleCommands();
      environment.sendOrdinaryInfo(payload(400, 80, 200, 0.55, 0.65));
      assertEquals(400, Lizzie.board.getData().rootVisits);
      assertEquals(80, candidate("A1").playouts);
      assertTrue(environment.frame.trackingDisplaySnapshot().activePoints().isEmpty());
    }
  }

  @Test
  void clearingTheEngineTreeRetiresExplicitAdoptionEvenAtTheSameDisplayNode() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      environment.startSupportedAnalysis();
      Lizzie.config.enableLizzieCache = true;
      assertEquals(TrackingAnalysisController.AddResult.ADDED,
          environment.frame.addTrackingPoint("A1"));
      environment.settleCommands();
      environment.sendOrdinaryInfo(payload(1000, 120, 130, 0.50, 0.60));
      environment.settleCommands();
      environment.engine.sendCommand("clear_board");
      environment.settleCommands();
      environment.engine.sendCommand("kata-analyze B 10 rootInfo true");
      environment.settleCommands();
      environment.dispatch(payload(100, 50000, 80, 0.99, 0.60));
      assertEquals(1000, Lizzie.board.getData().rootVisits);
      assertEquals(120, candidate("A1").playouts);
      assertTrue(environment.frame.trackingDisplaySnapshot().selectedPoints().isEmpty());
    }
  }

  @Test
  void focusedPayloadWithoutRootCannotOverwriteOrCompleteOrdinaryAnalysis() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      environment.startSupportedAnalysis();
      environment.sendOrdinaryInfo(payload(200, 20, 30, 0.50, 0.60));
      assertEquals(TrackingAnalysisController.AddResult.ADDED,
          environment.frame.addTrackingPoint("A1"));
      environment.settleCommands();
      environment.dispatch("info move A1 visits 50000 order 0 winrate 0.99 pv A1");
      environment.settleCommands();
      assertEquals(200, Lizzie.board.getData().rootVisits);
      assertEquals(20, candidate("A1").playouts);
      assertFalse(environment.frame.trackingDisplaySnapshot().active());
    }
  }

  @Test
  void completedEdgeZeroAttentionSurvivesOutlineOffAndRemovalRestoresFiltering() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      environment.startSupportedAnalysis();
      Lizzie.config.limitMaxSuggestion = 1;
      Lizzie.config.showTrackingPointOutline = false;
      environment.sendOrdinaryInfo(payload(200, 120, 130, 0.50, 0.60));
      assertFalse(Lizzie.board.getData().bestMoves.stream().anyMatch(move -> move.coordinate.equals("A1")));
      assertEquals(TrackingAnalysisController.AddResult.ADDED,
          environment.frame.addTrackingPoint("A1"));
      environment.settleCommands();
      assertEquals(0, candidate("A1").edgeVisits);
      assertEquals(10, candidate("A1").order);
      assertTrue(environment.frame.trackingDisplaySnapshot().activePoints().isEmpty());
      assertTrue(environment.frame.removeTrackingPoint("A1"));
      environment.settleCommands();
      assertFalse(Lizzie.board.getData().bestMoves.stream().anyMatch(move -> move.coordinate.equals("A1")));
      assertTrue(Lizzie.board.getData().bestMovesOutOfRange.stream()
          .anyMatch(move -> move.coordinate.equals("A1") && move.playouts == 120));
    }
  }


  @Test
  void cancellingBeforeFirstFocusPayloadPreservesTheOldNodeAnalysis() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      environment.startSupportedAnalysis();
      Lizzie.config.enableLizzieCache = true;
      environment.sendOrdinaryInfo(payload(10000, 9000, 1000, 0.80, 0.90));
      environment.engine.sendCommand("kata-analyze B 10 rootInfo true");
      environment.settleCommands();
      assertEquals(TrackingAnalysisController.AddResult.ADDED,
          environment.frame.addTrackingPoint("A1"));
      environment.frame.clearTrackingPoints();
      environment.settleCommands();
      environment.sendOrdinaryInfo(payload(1000, 50000, 40, 0.40, 0.60));
      assertEquals(10000, Lizzie.board.getData().rootVisits);
      assertEquals(9000, candidate("A1").playouts);
      assertEquals(80.0, candidate("A1").winrate, 0.001);
      assertTrue(environment.frame.trackingDisplaySnapshot().selectedPoints().isEmpty());
    }
  }

  @Test
  void onlyEachPointsOwnNewHighWaterRenewsItsNoProgressDeadline() throws Exception {
    try (TestEnvironment environment = TestEnvironment.open()) {
      environment.startSupportedAnalysis();
      var deadlines = new java.util.ArrayList<Runnable>();
      Class<?> schedulerType = Class.forName(
          "featurecat.lizzie.analysis.TrackingAnalysisController$TimeoutScheduler");
      Class<?> cancellableType = Class.forName(
          "featurecat.lizzie.analysis.TrackingAnalysisController$Cancellable");
      Object scheduler = java.lang.reflect.Proxy.newProxyInstance(
          schedulerType.getClassLoader(), new Class<?>[] {schedulerType}, (proxy, method, args) -> {
            deadlines.add((Runnable) args[1]);
            return java.lang.reflect.Proxy.newProxyInstance(cancellableType.getClassLoader(),
                new Class<?>[] {cancellableType}, (p, m, a) -> null);
          });
      var constructor = TrackingAnalysisController.class.getDeclaredConstructor(schedulerType);
      constructor.setAccessible(true);
      TrackingAnalysisController controller =
          (TrackingAnalysisController) constructor.newInstance(scheduler);
      setField(environment.frame, LizzieFrame.class, "trackingAnalysisController", controller);
      assertEquals(TrackingAnalysisController.AddResult.ADDED,
          environment.frame.addTrackingPoint("A1"));
      environment.settleCommands();
      assertEquals(TrackingAnalysisController.AddResult.ADDED,
          environment.frame.addTrackingPoint("B2"));
      environment.settleCommands();
      environment.sendOrdinaryInfo(payload(40, 20, 30, 0.5, 0.6));
      Runnable a1Deadline = deadlines.get(deadlines.size() - 1);
      environment.sendOrdinaryInfo(payload(50, 10, 40, 0.5, 0.6));
      environment.sendOrdinaryInfo(payload(60, 15, 50, 0.5, 0.6));
      a1Deadline.run();
      assertEquals(java.util.Set.of("B2"), controller.snapshot().activePoints());
      assertEquals(java.util.Set.of("A1", "B2"), controller.snapshot().selectedPoints());
      assertTrue(controller.snapshot().visits().get("A1") < controller.snapshot().targetVisits());
      environment.settleCommands();
      assertEquals(java.util.Set.of("B2"), controller.snapshot().activePoints());
    }
  }

  private static String payload(int root, int a1, int b2, double a1Winrate, double b2Winrate) {
    return "info move B2 visits " + b2 + " edgeVisits 30 order 0 winrate " + b2Winrate
        + " scoreLead 5 pv B2 info move A1 visits " + a1
        + " edgeVisits 0 order 10 winrate " + a1Winrate
        + " scoreLead 2 pv A1 rootInfo visits " + root;
  }

  private static MoveData candidate(String coordinate) {
    return Lizzie.board.getData().bestMoves.stream()
        .filter(move -> move.coordinate.equals(coordinate)).findFirst().orElseThrow();
  }

  private static BoardRenderer configuredRenderer() throws Exception {
    BoardRenderer renderer = new BoardRenderer(false);
    setField(renderer, BoardRenderer.class, "x", 0);
    setField(renderer, BoardRenderer.class, "y", 0);
    setField(renderer, BoardRenderer.class, "scaledMarginWidth", 20);
    setField(renderer, BoardRenderer.class, "scaledMarginHeight", 20);
    setField(renderer, BoardRenderer.class, "squareWidth", 40);
    setField(renderer, BoardRenderer.class, "squareHeight", 40);
    setField(renderer, BoardRenderer.class, "stoneRadius", 16);
    setField(
        renderer,
        BoardRenderer.class,
        "bestMoves",
        new java.util.ArrayList<>(
            Lizzie.board.getHistory().getCurrentHistoryNode().getData().bestMoves));
    return renderer;
  }

  private static BufferedImage renderTrackingOverlay(BoardRenderer renderer) throws Exception {
    BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      Method method =
          BoardRenderer.class.getDeclaredMethod("drawTrackingOverlay", Graphics2D.class);
      method.setAccessible(true);
      method.invoke(renderer, graphics);
    } finally {
      graphics.dispose();
    }
    return image;
  }

  private static boolean hasVisiblePaint(BufferedImage image) {
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if ((image.getRGB(x, y) >>> 24) != 0) {
          return true;
        }
      }
    }
    return false;
  }

  private static BufferedImage renderMainBoard() {
    Font previousUiFont = LizzieFrame.uiFont;
    Font previousWinrateFont = LizzieFrame.winrateFont;
    Font previousPlayoutsFont = LizzieFrame.playoutsFont;
    LizzieFrame.uiFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    LizzieFrame.winrateFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    LizzieFrame.playoutsFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    try {
      BoardRenderer renderer = new BoardRenderer(false);
      renderer.setLocation(0, 0);
      renderer.setBoardLength(180, 180);
      BufferedImage image = new BufferedImage(180, 180, BufferedImage.TYPE_INT_ARGB);
      Graphics2D graphics = image.createGraphics();
      try {
        renderer.draw(graphics);
      } finally {
        graphics.dispose();
      }
      return image;
    } finally {
      LizzieFrame.uiFont = previousUiFont;
      LizzieFrame.winrateFont = previousWinrateFont;
      LizzieFrame.playoutsFont = previousPlayoutsFont;
    }
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final Leelaz previousEngine;
    private final Board previousBoard;
    private final Config previousConfig;
    private final LizzieFrame previousFrame;
    private final Menu previousMenu;
    private GtpConsolePane previousConsole;
    private final BottomToolbar previousToolbar;
    private final boolean previousEmpty;
    private final int previousWidth;
    private final int previousHeight;
    private Object previousZobristTables;
    private final Leelaz engine;
    private final ByteArrayOutputStream output;
    private final LizzieFrame frame;

    private TestEnvironment(
        Leelaz previousEngine,
        Board previousBoard,
        Config previousConfig,
        LizzieFrame previousFrame,
        Menu previousMenu,
        BottomToolbar previousToolbar,
        boolean previousEmpty,
        int previousWidth,
        int previousHeight,
        Leelaz engine,
        ByteArrayOutputStream output,
        LizzieFrame frame) {
      this.previousEngine = previousEngine;
      this.previousBoard = previousBoard;
      this.previousConfig = previousConfig;
      this.previousFrame = previousFrame;
      this.previousMenu = previousMenu;
      this.previousToolbar = previousToolbar;
      this.previousEmpty = previousEmpty;
      this.previousWidth = previousWidth;
      this.previousHeight = previousHeight;
      this.engine = engine;
      this.output = output;
      this.frame = frame;
    }

    static TestEnvironment open() throws Exception {
      Leelaz previousEngine = Lizzie.leelaz;
      Board previousBoard = Lizzie.board;
      Config previousConfig = Lizzie.config;
      LizzieFrame previousFrame = Lizzie.frame;
      Menu previousMenu = LizzieFrame.menu;
      BottomToolbar previousToolbar = LizzieFrame.toolbar;
      boolean previousEmpty = EngineManager.isEmpty;
      int previousWidth = Board.boardWidth;
      int previousHeight = Board.boardHeight;
      Method captureTables = featurecat.lizzie.rules.Zobrist.class.getDeclaredMethod("captureTables");
      captureTables.setAccessible(true);
      Object previousZobristTables = captureTables.invoke(null);

      Board.boardWidth = 2;
      Board.boardHeight = 2;
      featurecat.lizzie.rules.Zobrist.init();
      Board board = allocate(TrackingBoard.class);
      board.setHistory(new BoardHistoryList(BoardData.empty(2, 2)));
      Config config = allocate(Config.class);
      config.analyzeUpdateIntervalCentisec = 10;
      config.trackingAnalysisMaxVisits = 100;
      config.showTrackingPointOutline = true;
      config.trackingPointOutlineOpacityPercent = 92;
      config.currentKataGoRules = "chinese";
      config.extraMode = ExtraMode.Normal;
      config.boardStyle = Config.BOARD_STYLE_JAPANESE;
      config.usePureBoard = true;
      config.pureBoardColor = new Color(198, 178, 148);
      config.usePureStone = true;
      config.showBestMoves = true;
      config.showBlackCandidates = true;
      config.showWhiteCandidates = true;
      config.showWinrateInSuggestion = true;
      config.showPlayoutsInSuggestion = true;
      config.showScoremeanInSuggestion = true;
      config.suggestionInfoWinrate = 1;
      config.suggestionInfoPlayouts = 2;
      config.suggestionInfoScoreLead = 3;
      config.useDefaultInfoRowOrder = true;
      config.moveRankEvaluationMode = MoveRankEvaluationMode.AUTO;
      config.winLossThreshold1 = -1;
      config.winLossThreshold2 = -3;
      config.winLossThreshold3 = -6;
      config.winLossThreshold4 = -12;
      config.winLossThreshold5 = -24;
      config.scoreLossThreshold1 = -0.5;
      config.scoreLossThreshold2 = -1.5;
      config.scoreLossThreshold3 = -3;
      config.scoreLossThreshold4 = -6;
      config.scoreLossThreshold5 = -12;
      config.moveRankMarkLastMove = -1;
      Leelaz engine = new Leelaz("");
      engine.isLoaded = true;
      engine.started = true;
      engine.isKatago = true;
      engine.bestMovesEnginename = "KataGo";
      engine.commandLists.addAll(List.of("stop", "kata-analyze"));
      setField(engine, Leelaz.class, "endGetCommandList", true);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      setField(engine, Leelaz.class, "outputStream", new BufferedOutputStream(output));
      LizzieFrame frame = allocate(TrackingFrame.class);
      frame.priorityMoveCoords = new java.util.ArrayList<>();
      frame.clickbadmove = LizzieFrame.outOfBoundCoordinate;
      frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;

      EngineManager.isEmpty = false;
      Lizzie.board = board;
      Lizzie.config = config;
      Lizzie.leelaz = engine;
      Lizzie.frame = frame;
      LizzieFrame.menu = allocate(SilentMenu.class);
      LizzieFrame.toolbar = allocate(BottomToolbar.class);
      TestEnvironment environment = new TestEnvironment(
          previousEngine,
          previousBoard,
          previousConfig,
          previousFrame,
          previousMenu,
          previousToolbar,
          previousEmpty,
          previousWidth,
          previousHeight,
          engine,
          output,
          frame);
      environment.previousZobristTables = previousZobristTables;
      environment.previousConsole = Lizzie.gtpConsole;
      Lizzie.gtpConsole = allocate(SilentConsole.class);
      return environment;
    }

    void installStableReadBoard(String... rows) throws Exception {
      ReadBoard readBoard = allocate(ReadBoard.class);
      if (engine.moveFocusCapability() == Leelaz.MoveFocusCapability.UNKNOWN) {
        startSupportedAnalysis();
      }
      for (String name :
          List.of("conflictTracker", "historyJumpTracker", "localNavigationTracker")) {
        Field field = ReadBoard.class.getDeclaredField(name);
        field.setAccessible(true);
        boolean navigation = name.equals("localNavigationTracker");
        java.lang.reflect.Constructor<?> constructor =
            navigation
                ? field.getType().getDeclaredConstructor(java.util.function.BooleanSupplier.class)
                : field.getType().getDeclaredConstructor();
        constructor.setAccessible(true);
        field.set(
            readBoard,
            navigation
                ? constructor.newInstance((java.util.function.BooleanSupplier) () -> true)
                : constructor.newInstance());
      }
      setField(readBoard, ReadBoard.class, "tempcount", new java.util.ArrayList<Integer>());
      frame.readBoard = readBoard;
      for (String row : rows.length == 0 ? new String[] {"re=0,0", "re=0,0"} : rows) {
        readBoard.parseLine(row);
      }
      readBoard.parseLine("end");
      completeSyncConfirmation();
      assertTrue(
          readBoard.snapshot().stable(), readBoard.snapshot().reason().name() + " " + commands());
    }

    private int respondedCommands;
    private boolean streaming;

    void startSupportedAnalysis() throws Exception {
      assertTrue(engine.startMoveFocusProbeAfterInitialization());
      settleCommands();
      assertEquals(Leelaz.MoveFocusCapability.SUPPORTED, engine.moveFocusCapability());
    }

    void settleCommands() throws Exception {
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
      int quiet = 0;
      while (System.nanoTime() < deadline) {
        String[] lines = commands().split("\n");
        if (respondedCommands >= lines.length || lines[respondedCommands].isEmpty()) {
          if (++quiet >= 5) return;
          Thread.sleep(1);
          continue;
        }
        quiet = 0;
        String command = lines[respondedCommands++];
        if (streaming) {
          dispatch("");
          streaming = false;
        }
        String id = command.matches("[0-9]+ .*" )
            ? command.substring(0, command.indexOf(' ')) : "";
        String body = id.isEmpty() ? command : command.substring(command.indexOf(' ') + 1);
        dispatch("=" + id);
        if (body.startsWith("kata-analyze")) streaming = true;
        else dispatch("");
      }
      throw new AssertionError("Command exchange did not settle: " + commands());
    }

    private void dispatch(String line) throws Exception {
      Method method = Leelaz.class.getDeclaredMethod("dispatchReaderLineForTest", String.class);
      method.setAccessible(true);
      method.invoke(engine, line);
    }

    private void sendOrdinaryInfo(String line) throws Exception {
      dispatch(line);
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
      while (System.nanoTime() < deadline) {
        var snapshot = frame.trackingDisplaySnapshot();
        boolean delivered = true;
        for (MoveData move : engine.getBestMoves()) {
          if (snapshot.selectedPoints().contains(move.coordinate)
              && snapshot.visits().getOrDefault(move.coordinate, 0) < move.playouts) {
            delivered = false;
          }
        }
        if (delivered) return;
        Thread.sleep(1);
      }
      throw new AssertionError("Focus progress did not consume the accepted ordinary payload");
    }

    void acknowledgePositionCommands() throws Exception {
      settleCommands();
    }

    void completeSyncConfirmation() throws Exception {
      java.util.concurrent.CompletableFuture<Void> confirmation =
          ((TrackingBoard) Lizzie.board).syncConfirmation;
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
      do {
        acknowledgePositionCommands();
        if (confirmation == null || confirmation.isDone()) break;
        Thread.sleep(1);
      } while (System.nanoTime() < deadline);
      if (confirmation != null) confirmation.get(1, java.util.concurrent.TimeUnit.SECONDS);
    }

    String commands() {
      return output.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws Exception {
      completeSyncConfirmation();
      frame.clearTrackingPoints();
      settleCommands();
      Lizzie.leelaz = previousEngine;
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      Lizzie.gtpConsole = previousConsole;
      LizzieFrame.toolbar = previousToolbar;
      EngineManager.isEmpty = previousEmpty;
      Board.boardWidth = previousWidth;
      Board.boardHeight = previousHeight;
      Method restoreTables = featurecat.lizzie.rules.Zobrist.class.getDeclaredMethod(
          "restoreTables", previousZobristTables.getClass());
      restoreTables.setAccessible(true);
      restoreTables.invoke(null, previousZobristTables);
    }
  }

  private static final class TrackingFrame extends LizzieFrame {
    private int analysisRefreshRequests;

    @Override
    public void refresh() {}

    @Override
    public void requestAnalysisRefresh() {
      analysisRefreshRequests++;
    }

    @Override
    public void clearSelectImage() {}
  }

  private static final class TrackingBoard extends Board {
    private Runnable afterSyncMove;
    private java.util.concurrent.CompletableFuture<Void> syncConfirmation;

    @Override
    public java.util.concurrent.CompletableFuture<Void> applyReadBoardSync(
        Runnable localChanges, java.util.function.BooleanSupplier requiresConfirmation) {
      syncConfirmation = super.applyReadBoardSync(localChanges, requiresConfirmation);
      return syncConfirmation;
    }

    @Override
    public void placeForSync(int x, int y, Stone color, boolean newBranch) {
      super.placeForSync(x, y, color, newBranch);
      if (afterSyncMove != null) afterSyncMove.run();
    }

    @Override
    public void clearAfterMove() {}
  }

  private static final class SilentConsole extends GtpConsolePane {
    private SilentConsole() { super((java.awt.Window) null); }
    @Override public boolean isVisible() { return false; }
  }

  private static final class SilentMenu extends Menu {
    @Override
    public void toggleEngineMenuStatus(boolean isPondering, boolean isThinking) {}
  }

  private static void setField(Object target, Class<?> owner, String name, Object value)
      throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }
}
