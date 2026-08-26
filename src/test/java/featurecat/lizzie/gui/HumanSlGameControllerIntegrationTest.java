package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.ExactSnapshotRestoreProtocolFixture;
import featurecat.lizzie.analysis.HumanSlAnalysisRunner;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import featurecat.lizzie.training.HumanSlTrainingConfig;
import featurecat.lizzie.training.HumanSlTrainingSession;
import featurecat.lizzie.training.TrainingMode;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class HumanSlGameControllerIntegrationTest {
  private static final int BOARD_SIZE = 3;

  @Test
  void passShortcutUsesCoachControllerAndSchedulesTheAiTurn() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      BlockingHumanSlRunner runner = new BlockingHumanSlRunner();
      HumanSlGameController controller = env.startCoach(runner);
      Input input = new Input();
      JPanel source = new JPanel();

      SwingUtilities.invokeAndWait(() -> input.keyPressed(keyPressed(source, KeyEvent.VK_P)));

      assertTrue(runner.awaitRequest(), "the coach pass must schedule HumanSL immediately");
      assertEquals(1, Lizzie.board.getHistory().getMoveNumber());
      assertTrue(controller.isAiThinking());
      assertSame(controller, Lizzie.frame.humanSlGame);
      assertTrue(WinrateGraph.shouldSuppressForActiveHumanSlGame(controller));

      controller.abort();
      assertTrue(controller.isFinished());
    }
  }

  @Test
  void passShortcutDuringTheAiTurnDoesNotChangeTheBoardOrScheduleAnotherRequest() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      BlockingHumanSlRunner runner = new BlockingHumanSlRunner();
      HumanSlGameController controller =
          env.startCoach(runner, HumanSlTrainingConfig.PlayerColor.WHITE);
      Input input = new Input();
      JPanel source = new JPanel();

      assertTrue(runner.awaitRequest(), "the opening HumanSL turn must already be in flight");
      SwingUtilities.invokeAndWait(() -> input.keyPressed(keyPressed(source, KeyEvent.VK_P)));

      assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
      assertEquals(1, runner.requestCount());
      assertTrue(controller.isAiThinking());
      assertSame(controller, Lizzie.frame.humanSlGame);

      controller.abort();
      assertTrue(controller.isFinished());
    }
  }

  @Test
  void staleAiResponseEndsCoachWithoutChangingTheNavigatedPosition() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      BlockingHumanSlRunner runner = new BlockingHumanSlRunner();
      HumanSlGameController controller = env.startCoach(runner);
      BoardHistoryNode root = Lizzie.board.getHistory().getStart();

      controller.humanPass();
      assertTrue(runner.awaitRequest());
      assertEquals(1, root.numberOfChildren());

      assertTrue(Lizzie.board.getHistory().previous().isPresent());
      assertSame(root, Lizzie.board.getHistory().getCurrentHistoryNode());
      runner.releaseResponse();

      assertTrue(awaitCondition(controller::isFinished, Duration.ofSeconds(2)));
      SwingUtilities.invokeAndWait(() -> {});
      assertNull(Lizzie.frame.humanSlGame);
      assertSame(root, Lizzie.board.getHistory().getCurrentHistoryNode());
      assertEquals(
          1,
          root.numberOfChildren(),
          "the stale HumanSL pass must not become a variation on the navigated node");
    }
  }

  @Test
  void unifiedAiModeStopEndsCoachAndReportsThatItStoppedAnActiveMode() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      HumanSlGameController controller = env.startCoach(new BlockingHumanSlRunner());
      CoachFrame frame = (CoachFrame) Lizzie.frame;

      assertTrue(frame.stopAiPlayingAndPolicy());

      assertTrue(controller.isFinished());
      assertNull(frame.humanSlGame);
      assertFalse(frame.isPlayingAgainstLeelaz);
      assertFalse(frame.isAnaPlayingAgainstLeelaz);
    }
  }

  @Test
  void analysisPausedDuringPreparationIsRestoredWhenCoachEnds() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      TrackingLeelaz engine = new TrackingLeelaz();
      Lizzie.leelaz = engine;
      HumanSlGameController controller =
          new HumanSlGameController(
              new BlockingHumanSlRunner(),
              coachConfig(HumanSlTrainingConfig.PlayerColor.BLACK),
              new HumanSlTrainingSession());
      controller.setExitLifecycleForTesting(
          Runnable::run, Runnable::run, () -> true, null);
      engine.pondering = true;
      ForegroundAnalysisPause pause =
          ForegroundAnalysisPause.acquire(
              () -> Lizzie.leelaz == engine,
              engine::isPondering,
              engine::notPondering,
              engine::ponder);

      controller.start(pause.transferRestoreResponsibility());
      assertFalse(engine.pondering);

      controller.abort();

      assertTrue(engine.pondering);
      assertEquals(1, engine.resumeCount.get());
    }
  }

  @Test
  void liveAnalysisResumesThePrimaryAndForwardsMovesWithoutCorrectionDelay() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      TrackingLeelaz engine = new TrackingLeelaz();
      Lizzie.leelaz = engine;
      engine.pondering = true;
      Lizzie.config.showBlackCandidates = false;
      Lizzie.config.showWhiteCandidates = false;
      Lizzie.config.analyzeBlack = false;
      Lizzie.config.analyzeWhite = false;
      ForegroundAnalysisPause pause =
          ForegroundAnalysisPause.acquire(
              () -> Lizzie.leelaz == engine,
              engine::isPondering,
              engine::notPondering,
              engine::ponder);
      BlockingHumanSlRunner runner = new BlockingHumanSlRunner();
      HumanSlGameController controller =
          new HumanSlGameController(
              runner,
              HumanSlTrainingConfig.builder()
                  .mode(TrainingMode.LIVE_ANALYSIS)
                  .playerColor(HumanSlTrainingConfig.PlayerColor.BLACK)
                  .fromCurrentPosition(true)
                  .moveTimeSeconds(2)
                  .build(),
              new HumanSlTrainingSession());
      AtomicInteger resyncs = new AtomicInteger();
      controller.setExitLifecyclePreparationForTesting(
          Runnable::run,
          Runnable::run,
          () -> {
            resyncs.incrementAndGet();
            return () -> true;
          },
          null);

      controller.start(pause.transferRestoreResponsibility());

      assertTrue(engine.pondering);
      assertTrue(controller.isLiveAnalysisMode());
      assertFalse(WinrateGraph.shouldSuppressForActiveHumanSlGame(controller));
      assertEquals(1, engine.resumeCount.get());
      assertEquals(1, resyncs.get());
      assertTrue(Lizzie.config.showBlackCandidates);
      assertTrue(Lizzie.config.showWhiteCandidates);
      assertTrue(Lizzie.config.analyzeBlack);
      assertTrue(Lizzie.config.analyzeWhite);

      controller.humanPass();

      assertTrue(runner.awaitRequest(), "the human move must proceed straight to the AI turn");
      assertEquals(1, engine.forwardedMoves.get());
      assertEquals(1, Lizzie.board.getHistory().getMoveNumber());

      controller.abort();

      assertEquals(2, resyncs.get());
      assertTrue(engine.pondering, "analysis that was active before AI Coach must stay active");
      assertFalse(Lizzie.config.showBlackCandidates);
      assertFalse(Lizzie.config.showWhiteCandidates);
      assertFalse(Lizzie.config.analyzeBlack);
      assertFalse(Lizzie.config.analyzeWhite);
    }
  }

  @Test
  void liveAnalysisStartedForAnIdlePrimaryIsStoppedAgainOnExit() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      TrackingLeelaz engine = new TrackingLeelaz();
      Lizzie.leelaz = engine;
      HumanSlGameController controller =
          new HumanSlGameController(
              new BlockingHumanSlRunner(),
              HumanSlTrainingConfig.builder()
                  .mode(TrainingMode.LIVE_ANALYSIS)
                  .playerColor(HumanSlTrainingConfig.PlayerColor.BLACK)
                  .fromCurrentPosition(true)
                  .moveTimeSeconds(2)
                  .build(),
              new HumanSlTrainingSession());
      controller.setExitLifecycleForTesting(
          Runnable::run, Runnable::run, () -> true, null);

      controller.start(ForegroundAnalysisPause.RestoreLease.inactive());

      assertTrue(engine.pondering);
      assertEquals(1, engine.resumeCount.get());

      controller.abort();

      assertFalse(engine.pondering, "AI Coach must restore an originally idle primary");
    }
  }

  @Test
  void runnerPreparationCapturesUncheckedStartAndReadinessFailuresForEdtCleanup() {
    RuntimeException startFailure = new IllegalStateException("unchecked start failure");
    ThrowingPreparationRunner startRunner = new ThrowingPreparationRunner(startFailure, null);

    NewHumanSlGameDialog.RunnerPreparationOutcome startOutcome =
        NewHumanSlGameDialog.prepareRunner(
            startRunner, null, "rank_1d", Duration.ofSeconds(1));

    assertFalse(startOutcome.ready());
    assertSame(startFailure, startOutcome.failure());
    assertFalse(startRunner.verifyCalled.get());

    AssertionError readinessFailure = new AssertionError("unchecked readiness failure");
    ThrowingPreparationRunner readinessRunner =
        new ThrowingPreparationRunner(null, readinessFailure);

    NewHumanSlGameDialog.RunnerPreparationOutcome readinessOutcome =
        NewHumanSlGameDialog.prepareRunner(
            readinessRunner, null, "rank_1d", Duration.ofSeconds(1));

    assertFalse(readinessOutcome.ready());
    assertSame(readinessFailure, readinessOutcome.failure());
    assertTrue(readinessRunner.verifyCalled.get());
  }

  @Test
  void everyHandoffStageFailureRunsTheWholeCleanupLifecycleWithoutEscaping() {
    for (int failingStage = 0; failingStage < 5; failingStage++) {
      int failureIndex = failingStage;
      List<Integer> handoffStages = new ArrayList<>();
      RuntimeException runtimeFailure =
          new IllegalStateException("handoff stage " + failingStage);
      AssertionError errorFailure = new AssertionError("handoff stage " + failingStage);
      Throwable injected = failingStage % 2 == 0 ? runtimeFailure : errorFailure;
      Runnable[] handoff = new Runnable[5];
      for (int stage = 0; stage < handoff.length; stage++) {
        int currentStage = stage;
        handoff[stage] =
            () -> {
              handoffStages.add(currentStage);
              if (currentStage == failureIndex) {
                ThrowingPreparationRunner.throwUnchecked(injected);
              }
            };
      }

      Throwable captured =
          assertDoesNotThrow(() -> NewHumanSlGameDialog.runHandoffLifecycle(handoff));

      assertSame(injected, captured);
      assertEquals(failingStage + 1, handoffStages.size());
      List<String> cleanupStages = new ArrayList<>();
      NewHumanSlGameDialog.LifecycleAction[] cleanup =
          new NewHumanSlGameDialog.LifecycleAction[6];
      for (int stage = 0; stage < cleanup.length; stage++) {
        int currentStage = stage;
        cleanup[stage] =
            () -> {
              cleanupStages.add("cleanup-" + currentStage);
              if (currentStage == 1) {
                throw new AssertionError("cleanup Error must be aggregated");
              }
              return null;
            };
      }

      Throwable aggregate =
          assertDoesNotThrow(
              () -> NewHumanSlGameDialog.runCleanupLifecycle(captured, cleanup));

      assertSame(injected, aggregate);
      assertEquals(6, cleanupStages.size(), "no cleanup stage may be skipped after an Error");
      assertEquals(1, aggregate.getSuppressed().length);
    }
  }

  @Test
  void cancellationClosesRunnerBeforeForegroundCompletionAndSurvivesDispatchError() {
    List<String> events = new ArrayList<>();
    AtomicReference<Throwable> completionFailure = new AtomicReference<>();

    assertDoesNotThrow(
        () ->
            NewHumanSlGameDialog.closeRunnerBeforeCompletion(
                () -> events.add("runner-close-complete"),
                null,
                failure -> {
                  events.add("restore-foreground");
                  completionFailure.set(failure);
                },
                task -> task.run(),
                task -> {
                  events.add("edt-dispatch");
                  throw new AssertionError("invokeLater rejected");
                }));

    assertEquals(
        List.of("runner-close-complete", "edt-dispatch", "restore-foreground"), events);
    assertNotNull(completionFailure.get());
    assertEquals("invokeLater rejected", completionFailure.get().getMessage());
  }

  @Test
  void closeRequestAndReadinessCompletionCannotBothClaimRunnerCleanup() throws Exception {
    NewHumanSlGameDialog.RunnerCleanupClaim claim =
        new NewHumanSlGameDialog.RunnerCleanupClaim();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger winners = new AtomicInteger();
    Runnable contender =
        () -> {
          ready.countDown();
          try {
            start.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
          }
          if (claim.claim()) {
            winners.incrementAndGet();
          }
        };
    Thread closeRequest = new Thread(contender, "close-request-test");
    Thread readinessCompletion = new Thread(contender, "readiness-completion-test");
    closeRequest.start();
    readinessCompletion.start();
    assertTrue(ready.await(1, TimeUnit.SECONDS));
    start.countDown();
    closeRequest.join(1000L);
    readinessCompletion.join(1000L);

    assertEquals(1, winners.get());
    assertFalse(claim.claim());
    claim.reset();
    assertTrue(claim.claim(), "a later preparation may establish a fresh one-shot claim");
  }

  @Test
  void liveUrlSgfEligibilityIsRejectedBeforePreparation() {
    boolean previous = LizzieFrame.urlSgf;
    try {
      LizzieFrame.urlSgf = true;
      assertTrue(NewHumanSlGameDialog.isLiveUrlSgfSyncActive());
      LizzieFrame.urlSgf = false;
      assertFalse(NewHumanSlGameDialog.isLiveUrlSgfSyncActive());
    } finally {
      LizzieFrame.urlSgf = previous;
    }
  }

  @Test
  void liveUrlSgfRaceAfterDownloadRestoresIdleFormState() {
    HumanSlTrainingSession session = new HumanSlTrainingSession();
    session.setState(HumanSlTrainingSession.State.PREPARING);
    AtomicBoolean formEnabled = new AtomicBoolean();
    AtomicBoolean progressHidden = new AtomicBoolean();

    Throwable failure =
        NewHumanSlGameDialog.resetPreparationAfterEligibilityRejection(
            session, () -> formEnabled.set(true), () -> progressHidden.set(true));

    assertNull(failure);
    assertEquals(HumanSlTrainingSession.State.IDLE, session.state());
    assertTrue(formEnabled.get());
    assertTrue(progressHidden.get());
  }

  @Test
  void rejectedReadinessCompletionDispatchStillTransfersCleanupOwnership() {
    AssertionError rejected = new AssertionError("invokeLater rejected");
    AtomicBoolean ordinaryCompletion = new AtomicBoolean();
    AtomicReference<Throwable> fallbackFailure = new AtomicReference<>();

    assertDoesNotThrow(
        () ->
            NewHumanSlGameDialog.dispatchRunnerPreparationCompletion(
                () -> ordinaryCompletion.set(true),
                fallbackFailure::set,
                task -> {
                  throw rejected;
                }));

    assertFalse(ordinaryCompletion.get());
    assertSame(rejected, fallbackFailure.get());
  }

  @Test
  void failedControllerHandoffClosesRunnerBeforeReturningRetryableLease()
      throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      Lizzie.frame = allocate(ThrowingStartCoachFrame.class);
      List<String> events = new ArrayList<>();
      BlockingHumanSlRunner runner = new BlockingHumanSlRunner(events);
      HumanSlTrainingSession session = new HumanSlTrainingSession();
      session.setState(HumanSlTrainingSession.State.PREPARING);
      AtomicBoolean pondering = new AtomicBoolean(true);
      AtomicBoolean allowResume = new AtomicBoolean();
      AtomicInteger resumeAttempts = new AtomicInteger();
      ForegroundAnalysisPause pause =
          ForegroundAnalysisPause.acquire(
              () -> true,
              pondering::get,
              () -> pondering.set(false),
              () -> {
                events.add("resume-attempt");
                resumeAttempts.incrementAndGet();
                if (!allowResume.get()) {
                  throw new IllegalStateException("persistent resume failure");
                }
                pondering.set(true);
              });
      HumanSlGameController controller =
          new HumanSlGameController(
              runner,
              coachConfig(HumanSlTrainingConfig.PlayerColor.BLACK),
              session);

      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () -> controller.start(pause.transferRestoreResponsibility()));

      assertEquals("training bar failed", failure.getMessage());
      assertEquals(HumanSlTrainingSession.State.PREPARING, session.state());
      assertNull(Lizzie.frame.humanSlGame);
      assertTrue(runner.awaitClosed(), "failed handoff must close its prepared runner");
      ForegroundAnalysisPause recovered =
          ForegroundAnalysisPause.adopt(controller.releaseFailedStartRestoreLease());
      assertTrue(recovered.isRestorePending());
      assertTrue(
          events.indexOf("runner-close-complete") < events.indexOf("resume-attempt"),
          events.toString());
      assertTrue(recovered.isRestorePending());
      assertEquals(2, resumeAttempts.get());

      allowResume.set(true);
      recovered.restore();

      assertTrue(pondering.get());
      assertFalse(recovered.isRestorePending());
      assertEquals(3, resumeAttempts.get());
    }
  }

  @Test
  void failedNewGameStartRestoresTheExactKifuDimensionsMetadataAndZobristState()
      throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      Board.boardWidth = 5;
      Board.boardHeight = 4;
      Zobrist.init();
      Board board = Lizzie.board;
      board.movelistwr = new ArrayList<>();
      board.startStonelist = new ArrayList<>();
      board.tempmovelist = new ArrayList<>();
      board.tempmovelist2 = new ArrayList<>();
      BoardHistoryList originalHistory = new BoardHistoryList(BoardData.empty(5, 4));
      originalHistory.place(1, 1, Stone.BLACK);
      originalHistory.place(3, 2, Stone.WHITE);
      originalHistory.getData().comment = "original current-node comment";
      originalHistory.getGameInfo().setKomiNoMenu(5.5);
      originalHistory.getGameInfo().setHandicap(2);
      originalHistory.getGameInfo().setPlayerBlack("Original Black");
      originalHistory.getGameInfo().setPlayerWhite("Original White");
      board.setHistory(originalHistory);
      board.setSetupMode(true);
      board.setForceRefresh(true);
      board.setForceRefresh2(true);
      board.setBigBranch();
      board.isExtremlySmallBoard = true;
      board.isPkBoard = true;
      board.isGameBoard = true;
      board.isPkBoardKataB = true;
      board.isKataBoard = true;
      board.isTusmegoMode = true;
      board.tsumegoNode = originalHistory.getCurrentHistoryNode();
      LizzieFrame.curFile = Path.of("C:\\棋谱 空格\\original game.sgf").toFile();
      LizzieFrame.fileNameTitle = "原始 棋谱.sgf";

      BoardHistoryNode originalRoot = originalHistory.getStart();
      BoardHistoryNode originalCurrent = originalHistory.getCurrentHistoryNode();
      String originalRootZobrist = originalRoot.getData().zobrist.toString();
      String originalCurrentZobrist = originalCurrent.getData().zobrist.toString();
      Zobrist originalProbe = originalCurrent.getData().zobrist.clone();
      originalProbe.toggleStone(0, 0, Stone.BLACK);
      String originalProbeZobrist = originalProbe.toString();
      List<String> rollbackEvents = new ArrayList<>();
      BlockingHumanSlRunner runner = new BlockingHumanSlRunner(rollbackEvents);
      HumanSlTrainingSession session = new HumanSlTrainingSession();
      session.setState(HumanSlTrainingSession.State.PREPARING);
      Lizzie.frame = allocate(CoachFrame.class);
      HumanSlGameController controller =
          new HumanSlGameController(
              runner,
              HumanSlTrainingConfig.builder()
                  .playerColor(HumanSlTrainingConfig.PlayerColor.BLACK)
                  .fromCurrentPosition(false)
                  .komi(7.5)
                  .moveTimeSeconds(2)
                  .build(),
              session);
      AtomicBoolean foregroundPondering = new AtomicBoolean(true);
      ForegroundAnalysisPause foregroundPause =
          ForegroundAnalysisPause.acquire(
              () -> true,
              foregroundPondering::get,
              () -> foregroundPondering.set(false),
              () -> {
                rollbackEvents.add("resume");
                foregroundPondering.set(true);
              });
      controller.setExitLifecycleForTesting(
          Runnable::run,
          Runnable::run,
          () -> {
            rollbackEvents.add("resync");
            return true;
          },
          null);
      Board.beforeHistoryOverwriteEngineForward =
          () -> {
            Board.boardWidth = 19;
            Board.boardHeight = 19;
            Zobrist.init();
            throw new IllegalStateException("failure after destructive clear");
          };

      try {
        IllegalStateException failure =
            assertThrows(
                IllegalStateException.class,
                () -> controller.start(foregroundPause.transferRestoreResponsibility()));
        assertEquals("failure after destructive clear", failure.getMessage());
      } finally {
        Board.beforeHistoryOverwriteEngineForward = null;
      }

      assertSame(board, Lizzie.board);
      assertSame(originalHistory, board.getHistory());
      assertSame(originalRoot, board.getHistory().getStart());
      assertSame(originalCurrent, board.getHistory().getCurrentHistoryNode());
      assertEquals(5, Board.boardWidth);
      assertEquals(4, Board.boardHeight);
      assertEquals(2, board.getHistory().getMoveNumber());
      assertEquals("original current-node comment", board.getData().comment);
      assertEquals(5.5, board.getHistory().getGameInfo().getKomi(), 0.0001);
      assertEquals(2, board.getHistory().getGameInfo().getHandicap());
      assertEquals("Original Black", board.getHistory().getGameInfo().getPlayerBlack());
      assertEquals("Original White", board.getHistory().getGameInfo().getPlayerWhite());
      assertEquals(originalRootZobrist, originalRoot.getData().zobrist.toString());
      assertEquals(originalCurrentZobrist, originalCurrent.getData().zobrist.toString());
      Zobrist restoredProbe = originalCurrent.getData().zobrist.clone();
      restoredProbe.toggleStone(0, 0, Stone.BLACK);
      assertEquals(
          originalProbeZobrist,
          restoredProbe.toString(),
          "rollback must restore the hash tables, not only the stored node hash values");
      assertTrue(board.isSetupMode());
      assertTrue(board.isForceRefresh());
      assertTrue(board.isForceRefresh2());
      assertTrue(board.hasBigBranch());
      assertTrue(board.isExtremlySmallBoard);
      assertTrue(board.isPkBoard);
      assertTrue(board.isGameBoard);
      assertTrue(board.isPkBoardKataB);
      assertTrue(board.isKataBoard);
      assertTrue(board.isTusmegoMode);
      assertSame(originalCurrent, board.tsumegoNode);
      assertEquals(Path.of("C:\\棋谱 空格\\original game.sgf").toFile(), LizzieFrame.curFile);
      assertEquals("原始 棋谱.sgf", LizzieFrame.fileNameTitle);
      assertEquals(HumanSlTrainingSession.State.PREPARING, session.state());
      assertNull(Lizzie.frame.humanSlGame);
      assertTrue(runner.awaitClosed(), "rollback must close the prepared HumanSL runner");
      assertTrue(
          rollbackEvents.indexOf("runner-close-complete") < rollbackEvents.indexOf("resync"),
          rollbackEvents.toString());
      assertTrue(
          rollbackEvents.indexOf("resync") < rollbackEvents.indexOf("resume"),
          rollbackEvents.toString());
      assertTrue(foregroundPondering.get());
    }
  }

  @Test
  void controllerRaceGuardRejectsLiveUrlSgfBeforeChangingTheNewGameBoard() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      BoardHistoryList originalHistory =
          new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      originalHistory.place(1, 1, Stone.BLACK);
      Lizzie.board.setHistory(originalHistory);
      TrackingOnlineDialog onlineDialog = allocate(TrackingOnlineDialog.class);
      onlineDialog.stopCalls = new AtomicInteger();
      LizzieFrame.onlineDialog = onlineDialog;
      LizzieFrame.urlSgf = true;
      Lizzie.frame = allocate(CoachFrame.class);
      BlockingHumanSlRunner runner = new BlockingHumanSlRunner();
      HumanSlTrainingSession session = new HumanSlTrainingSession();
      session.setState(HumanSlTrainingSession.State.PREPARING);
      HumanSlGameController controller =
          new HumanSlGameController(
              runner,
              HumanSlTrainingConfig.builder()
                  .playerColor(HumanSlTrainingConfig.PlayerColor.BLACK)
                  .fromCurrentPosition(false)
                  .moveTimeSeconds(2)
                  .build(),
              session);

      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () -> controller.start(ForegroundAnalysisPause.RestoreLease.inactive()));

      assertTrue(failure.getMessage().contains("URL-SGF"));
      assertSame(originalHistory, Lizzie.board.getHistory());
      assertEquals(0, onlineDialog.stopCalls.get());
      assertTrue(LizzieFrame.urlSgf, "the guard must leave live sync untouched");
      assertEquals(HumanSlTrainingSession.State.PREPARING, session.state());
      assertTrue(runner.awaitClosed(), "the race guard must close its already-prepared companion");
    }
  }

  @Test
  void controllerRaceGuardAlsoRejectsFromCurrentWithoutStoppingLiveSync() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      TrackingOnlineDialog onlineDialog = allocate(TrackingOnlineDialog.class);
      onlineDialog.stopCalls = new AtomicInteger();
      LizzieFrame.onlineDialog = onlineDialog;
      LizzieFrame.urlSgf = true;
      BoardHistoryList originalHistory = Lizzie.board.getHistory();
      originalHistory.place(1, 1, Stone.BLACK);
      BlockingHumanSlRunner runner = new BlockingHumanSlRunner();
      HumanSlTrainingSession session = new HumanSlTrainingSession();
      session.setState(HumanSlTrainingSession.State.PREPARING);
      HumanSlGameController controller =
          new HumanSlGameController(
              runner,
              HumanSlTrainingConfig.builder()
                  .playerColor(HumanSlTrainingConfig.PlayerColor.BLACK)
                  .fromCurrentPosition(true)
                  .moveTimeSeconds(2)
                  .build(),
              session);

      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () -> controller.start(ForegroundAnalysisPause.RestoreLease.inactive()));

      assertTrue(failure.getMessage().contains("URL-SGF"));
      assertSame(originalHistory, Lizzie.board.getHistory());
      assertEquals(1, originalHistory.getMoveNumber());
      assertEquals(0, onlineDialog.stopCalls.get());
      assertTrue(LizzieFrame.urlSgf);
      assertTrue(runner.awaitClosed());
    }
  }

  @Test
  void switchingEnginesDuringCoachDoesNotResumeTheReplacementOrStaleOriginal() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      TrackingLeelaz original = new TrackingLeelaz();
      TrackingLeelaz replacement = new TrackingLeelaz();
      Lizzie.leelaz = original;
      original.pondering = true;
      ForegroundAnalysisPause pause =
          ForegroundAnalysisPause.acquire(
              () -> Lizzie.leelaz == original,
              original::isPondering,
              original::notPondering,
              original::ponder);
      HumanSlGameController controller =
          new HumanSlGameController(
              new BlockingHumanSlRunner(),
              coachConfig(HumanSlTrainingConfig.PlayerColor.BLACK),
              new HumanSlTrainingSession());
      AtomicInteger resyncs = new AtomicInteger();
      controller.setExitLifecycleForTesting(
          Runnable::run,
          Runnable::run,
          () -> {
            resyncs.incrementAndGet();
            return true;
          },
          null);

      controller.start(pause.transferRestoreResponsibility());
      Lizzie.leelaz = replacement;
      controller.abort();

      assertFalse(original.pondering, "a stale original engine must not be restarted");
      assertEquals(0, original.resumeCount.get());
      assertFalse(replacement.pondering, "the replacement engine was never paused by this lease");
      assertEquals(0, replacement.resumeCount.get());
      assertEquals(1, resyncs.get(), "the current replacement still needs the final board replay");
    }
  }

  @Test
  void idlePrimaryIsResyncedEvenWithoutAPonderRestoreLease() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      List<String> events = new ArrayList<>();
      HumanSlGameController controller =
          new HumanSlGameController(
              new BlockingHumanSlRunner(events),
              coachConfig(HumanSlTrainingConfig.PlayerColor.BLACK),
              new HumanSlTrainingSession());
      controller.setExitLifecycleForTesting(
          Runnable::run,
          Runnable::run,
          () -> {
            events.add("resync");
            return true;
          },
          () -> events.add("ui-complete"));
      controller.start(ForegroundAnalysisPause.RestoreLease.inactive());

      controller.abort();

      assertTrue(events.indexOf("runner-close-complete") < events.indexOf("resync"));
      assertTrue(events.indexOf("resync") < events.indexOf("ui-complete"));
      assertFalse(events.contains("resume"));
    }
  }

  @Test
  void abortAndFinishReturnToBoardCloseThenResyncThenResumeForNewAndCurrentGames()
      throws Exception {
    for (boolean fromCurrent : List.of(false, true)) {
      for (boolean finishFromToolbar : List.of(false, true)) {
        try (CoachEnvironment env = CoachEnvironment.open()) {
          if (fromCurrent) {
            Lizzie.board.getHistory().place(0, 0, Stone.BLACK);
          }
          List<String> events = new ArrayList<>();
          TrackingLeelaz engine = new TrackingLeelaz(events);
          Lizzie.leelaz = engine;
          engine.pondering = true;
          ForegroundAnalysisPause pause =
              ForegroundAnalysisPause.acquire(
                  () -> Lizzie.leelaz == engine,
                  engine::isPondering,
                  engine::notPondering,
                  engine::ponder);
          BlockingHumanSlRunner runner = new BlockingHumanSlRunner(events);
          HumanSlTrainingSession session = new HumanSlTrainingSession();
          List<HumanSlTrainingSession.State> sessionStates = new ArrayList<>();
          session.addListener(sessionStates::add);
          HumanSlGameController controller =
              new HumanSlGameController(
                  runner,
                  HumanSlTrainingConfig.builder()
                      .playerColor(
                          fromCurrent
                              ? HumanSlTrainingConfig.PlayerColor.WHITE
                              : HumanSlTrainingConfig.PlayerColor.BLACK)
                      .fromCurrentPosition(fromCurrent)
                      .moveTimeSeconds(2)
                      .build(),
                  session);
          AtomicInteger resyncedMoveNumber = new AtomicInteger(-1);
          controller.setExitLifecycleForTesting(
              Runnable::run,
              Runnable::run,
              () -> {
                events.add("resync");
                resyncedMoveNumber.set(Lizzie.board.getHistory().getMoveNumber());
                return true;
              },
              () -> events.add("ui-complete"));
          controller.start(pause.transferRestoreResponsibility());
          Stone next =
              Lizzie.board.getHistory().isBlacksTurn() ? Stone.BLACK : Stone.WHITE;
          // Mutate only the history in this headless fixture. Board.place() also touches the real
          // renderer, which is intentionally absent here; the exit contract only needs the exact
          // final position that must be replayed before foreground analysis resumes.
          Lizzie.board.getHistory().place(1, 1, next);

          if (finishFromToolbar) {
            controller.finishAndReturnToBoard();
          } else {
            controller.abort();
          }

          int closeIndex = events.indexOf("runner-close-complete");
          int resyncIndex = events.indexOf("resync");
          int resumeIndex = events.indexOf("resume");
          assertTrue(closeIndex >= 0, events.toString());
          assertTrue(closeIndex < resyncIndex, events.toString());
          assertTrue(resyncIndex < resumeIndex, events.toString());
          assertTrue(resumeIndex < events.indexOf("ui-complete"), events.toString());
          assertEquals(fromCurrent ? 2 : 1, resyncedMoveNumber.get());
          assertTrue(engine.pondering);
          assertFalse(controller.isExitRecoveryPending());
          assertEquals(HumanSlTrainingSession.State.FINISHED, session.state());
          assertFalse(sessionStates.contains(HumanSlTrainingSession.State.REVIEWING));
          assertFalse(sessionStates.contains(HumanSlTrainingSession.State.REPORT_READY));
        }
      }
    }
  }

  @Test
  void legacyResignAliasHasTheSameResultAsFinish() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      HumanSlTrainingSession session = new HumanSlTrainingSession();
      List<HumanSlTrainingSession.State> sessionStates = new ArrayList<>();
      session.addListener(sessionStates::add);
      HumanSlGameController controller =
          new HumanSlGameController(
              new BlockingHumanSlRunner(),
              HumanSlTrainingConfig.builder()
                  .playerColor(HumanSlTrainingConfig.PlayerColor.BLACK)
                  .fromCurrentPosition(false)
                  .moveTimeSeconds(2)
                  .build(),
              session);
      controller.setExitLifecycleForTesting(Runnable::run, Runnable::run, () -> true, null);
      session.setState(HumanSlTrainingSession.State.PLAYING);
      Lizzie.frame.humanSlGame = controller;
      String resultBefore = Lizzie.board.getHistory().getGameInfo().getResult();

      controller.humanResign();

      assertTrue(controller.isFinished());
      assertNull(Lizzie.frame.humanSlGame);
      assertEquals(HumanSlTrainingSession.State.FINISHED, session.state());
      assertTrue(controller.gameResult().isBlank());
      assertEquals(resultBefore, Lizzie.board.getHistory().getGameInfo().getResult());
      assertFalse(sessionStates.contains(HumanSlTrainingSession.State.REVIEWING));
      assertFalse(sessionStates.contains(HumanSlTrainingSession.State.REPORT_READY));
    }
  }

  @Test
  void exitFailuresKeepForegroundLeasePendingAndNeverResumeEarly() throws Exception {
    for (String failingPhase : List.of("close", "resync", "restore")) {
      try (CoachEnvironment env = CoachEnvironment.open()) {
        List<String> events = new ArrayList<>();
        TrackingLeelaz engine = new TrackingLeelaz(events);
        Lizzie.leelaz = engine;
        engine.pondering = true;
        ForegroundAnalysisPause pause =
            ForegroundAnalysisPause.acquire(
                () -> Lizzie.leelaz == engine,
                engine::isPondering,
                engine::notPondering,
                () -> {
                  events.add("resume");
                  if ("restore".equals(failingPhase)) {
                    throw new AssertionError("restore failed");
                  }
                  engine.ponder();
                });
        BlockingHumanSlRunner runner =
            "close".equals(failingPhase)
                ? new ThrowingCloseHumanSlRunner(events)
                : new BlockingHumanSlRunner(events);
        HumanSlGameController controller =
            new HumanSlGameController(
                runner,
                coachConfig(HumanSlTrainingConfig.PlayerColor.BLACK),
                new HumanSlTrainingSession());
        controller.setExitLifecycleForTesting(
            Runnable::run,
            Runnable::run,
            () -> {
              events.add("resync");
              if ("resync".equals(failingPhase)) {
                throw new AssertionError("resync failed");
              }
              return true;
            },
            () -> events.add("ui-complete"));
        controller.start(pause.transferRestoreResponsibility());
        // Avoid opening an error dialog in the failure-injection harness.
        Lizzie.frame = null;

        assertDoesNotThrow(controller::abort);

        assertTrue(controller.isExitRecoveryPending(), failingPhase);
        assertFalse(events.contains("ui-complete"), events.toString());
        if ("close".equals(failingPhase)) {
          assertFalse(events.contains("resync"), events.toString());
          assertFalse(events.contains("resume"), events.toString());
        } else if ("resync".equals(failingPhase)) {
          assertTrue(events.indexOf("runner-close-complete") < events.indexOf("resync"));
          assertFalse(events.contains("resume"), events.toString());
        } else {
          assertTrue(events.indexOf("runner-close-complete") < events.indexOf("resync"));
          assertTrue(events.indexOf("resync") < events.indexOf("resume"));
          assertEquals(2L, events.stream().filter("resume"::equals).count());
        }
      }
    }
  }

  @Test
  void stablePrimaryResyncRecapturesAfterDriftAndStopsAtTheAttemptBound() {
    AtomicInteger executions = new AtomicInteger();
    AtomicInteger recaptures = new AtomicInteger();
    ScriptedPrimaryResyncAttempt settlesOnSecondReplay =
        new ScriptedPrimaryResyncAttempt(
            List.of(false, true), 0, executions, recaptures, true);

    assertTrue(
        HumanSlGameController.executeStablePrimaryEngineResync(settlesOnSecondReplay, 3));
    assertEquals(2, executions.get());
    assertEquals(1, recaptures.get());

    executions.set(0);
    recaptures.set(0);
    ScriptedPrimaryResyncAttempt neverSettles =
        new ScriptedPrimaryResyncAttempt(
            List.of(false, false, false, false), 0, executions, recaptures, true);

    assertFalse(HumanSlGameController.executeStablePrimaryEngineResync(neverSettles, 3));
    assertEquals(3, executions.get());
    assertEquals(2, recaptures.get());
  }

  @Test
  void failedPrimaryReplayKeepsLeasePendingUntilFreshRetrySucceeds() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      List<String> events = new ArrayList<>();
      AtomicInteger preparations = new AtomicInteger();
      TrackingLeelaz engine = new TrackingLeelaz(events);
      Lizzie.leelaz = engine;
      engine.pondering = true;
      ForegroundAnalysisPause pause =
          ForegroundAnalysisPause.acquire(
              () -> Lizzie.leelaz == engine,
              engine::isPondering,
              engine::notPondering,
              engine::ponder);
      HumanSlGameController controller =
          new HumanSlGameController(
              new BlockingHumanSlRunner(events),
              coachConfig(HumanSlTrainingConfig.PlayerColor.BLACK),
              new HumanSlTrainingSession());
      controller.setExitLifecyclePreparationForTesting(
          Runnable::run,
          Runnable::run,
          () -> {
            int preparation = preparations.incrementAndGet();
            return () -> {
              events.add("resync-" + preparation);
              return preparation > 1;
            };
          },
          () -> events.add("ui-complete"));
      controller.start(pause.transferRestoreResponsibility());
      Lizzie.frame = null;

      assertDoesNotThrow(controller::abort);

      assertTrue(controller.isExitRecoveryPending());
      assertEquals(1, preparations.get());
      assertFalse(events.contains("resume"), events.toString());
      assertFalse(events.contains("ui-complete"), events.toString());

      assertDoesNotThrow(controller::abort);

      assertFalse(controller.isExitRecoveryPending());
      assertTrue(controller.isFinished());
      assertEquals(2, preparations.get());
      assertTrue(events.indexOf("resync-2") < events.indexOf("resume"), events.toString());
      assertTrue(events.indexOf("resume") < events.indexOf("ui-complete"), events.toString());
    }
  }

  @Test
  void navigationWhileRunnerCloseIsBlockedReplaysLatestBoardBeforeLeaseAndUi()
      throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      Board board = Lizzie.board;
      List<String> events = new CopyOnWriteArrayList<>();
      List<String> exactSnapshots = new CopyOnWriteArrayList<>();
      ExactReplayTrackingLeelaz engine =
          new ExactReplayTrackingLeelaz(events, exactSnapshots);
      Lizzie.setPrimaryEngine(engine);
      BoardHistoryList history = board.getHistory();
      history.place(0, 0, Stone.BLACK);
      history.place(1, 1, Stone.WHITE);
      engine.pondering = true;
      ForegroundAnalysisPause pause =
          ForegroundAnalysisPause.acquire(
              () -> Lizzie.leelaz == engine,
              engine::isPondering,
              engine::notPondering,
              engine::ponder);
      LatchCloseHumanSlRunner runner = new LatchCloseHumanSlRunner(events);
      HumanSlGameController controller =
          new HumanSlGameController(
              runner,
              coachConfig(HumanSlTrainingConfig.PlayerColor.BLACK),
              new HumanSlTrainingSession());
      AtomicReference<Thread> worker = new AtomicReference<>();
      controller.setExitDispatchersForTesting(
          task -> {
            Thread thread = new Thread(task, "humansl-stable-resync-test");
            worker.set(thread);
            thread.start();
          },
          Runnable::run,
          () -> events.add("ui-complete"));
      controller.start(pause.transferRestoreResponsibility());

      controller.abort();
      try {
        assertTrue(runner.awaitCloseStarted(), "runner close must hold the prepared snapshot");
        assertTrue(board.previousMove(false));
        assertEquals(1, board.getHistory().getMoveNumber());
      } finally {
        runner.releaseClose();
      }

      Thread exitWorker = worker.get();
      assertNotNull(exitWorker);
      exitWorker.join(5000L);
      assertFalse(exitWorker.isAlive());

      assertEquals(2, exactSnapshots.size(), exactSnapshots.toString());
      assertTrue(exactSnapshots.get(0).contains("AB[aa]"));
      assertTrue(exactSnapshots.get(0).contains("AW[bb]"));
      assertTrue(exactSnapshots.get(1).contains("AB[aa]"));
      assertFalse(exactSnapshots.get(1).contains("AW[bb]"));
      assertTrue(events.indexOf("exact-2") < events.indexOf("resume"), events.toString());
      assertTrue(events.indexOf("resume") < events.indexOf("ui-complete"), events.toString());
      assertTrue(controller.isFinished());
      assertTrue(engine.pondering);
    }
  }

  @Test
  void rejectedExitWorkerDispatchCompletesResyncAdmissionAndLeavesLeaseRetryable()
      throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      List<String> events = new ArrayList<>();
      AtomicBoolean admissionActive = new AtomicBoolean();
      AtomicInteger frozenSnapshots = new AtomicInteger();
      TrackingLeelaz engine = new TrackingLeelaz(events);
      Lizzie.leelaz = engine;
      engine.pondering = true;
      ForegroundAnalysisPause pause =
          ForegroundAnalysisPause.acquire(
              () -> Lizzie.leelaz == engine,
              engine::isPondering,
              engine::notPondering,
              engine::ponder);
      HumanSlGameController controller =
          new HumanSlGameController(
              new BlockingHumanSlRunner(events),
              coachConfig(HumanSlTrainingConfig.PlayerColor.BLACK),
              new HumanSlTrainingSession());
      java.util.function.Supplier<BooleanSupplier> freezeWithoutAdmission =
          () -> {
            frozenSnapshots.incrementAndGet();
            events.add("freeze");
            assertFalse(admissionActive.get(), "freezing on the EDT must not hold admission");
            return () -> {
              assertTrue(admissionActive.compareAndSet(false, true));
              try {
                events.add("resync");
                return true;
              } finally {
                admissionActive.set(false);
              }
            };
          };
      controller.setExitLifecyclePreparationForTesting(
          task -> {
            events.add("background-rejected");
            throw new AssertionError("worker dispatch rejected");
          },
          Runnable::run,
          freezeWithoutAdmission,
          () -> events.add("ui-complete"));
      controller.start(pause.transferRestoreResponsibility());
      // Avoid opening an error dialog for the deliberately rejected first dispatch.
      Lizzie.frame = null;

      assertDoesNotThrow(controller::abort);

      assertTrue(controller.isExitRecoveryPending());
      assertFalse(admissionActive.get(), "a rejected worker must not acquire replay admission");
      assertEquals(1, frozenSnapshots.get());
      assertFalse(events.contains("runner-close-complete"));
      assertFalse(events.contains("resync"));
      assertFalse(events.contains("resume"), events.toString());

      controller.setExitLifecyclePreparationForTesting(
          Runnable::run,
          Runnable::run,
          freezeWithoutAdmission,
          () -> events.add("ui-complete"));
      assertDoesNotThrow(controller::abort);

      assertFalse(controller.isExitRecoveryPending());
      assertFalse(admissionActive.get());
      assertEquals(2, frozenSnapshots.get());
      assertTrue(events.lastIndexOf("runner-close-complete") < events.lastIndexOf("resync"));
      assertTrue(events.lastIndexOf("resync") < events.lastIndexOf("resume"));
      assertTrue(events.lastIndexOf("resume") < events.lastIndexOf("ui-complete"));
    }
  }

  @Test
  void closeFailureBeforeResyncDoesNotHoldAdmissionAndSecondAbortCanRecover()
      throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      List<String> events = new ArrayList<>();
      AtomicBoolean admissionActive = new AtomicBoolean();
      TrackingLeelaz engine = new TrackingLeelaz(events);
      Lizzie.leelaz = engine;
      engine.pondering = true;
      ForegroundAnalysisPause pause =
          ForegroundAnalysisPause.acquire(
              () -> Lizzie.leelaz == engine,
              engine::isPondering,
              engine::notPondering,
              engine::ponder);
      HumanSlGameController controller =
          new HumanSlGameController(
              new FailOnceCloseHumanSlRunner(events),
              coachConfig(HumanSlTrainingConfig.PlayerColor.BLACK),
              new HumanSlTrainingSession());
      controller.setExitLifecyclePreparationForTesting(
          Runnable::run,
          Runnable::run,
          () -> {
            events.add("freeze");
            assertFalse(admissionActive.get());
            return () -> {
              assertTrue(admissionActive.compareAndSet(false, true));
              try {
                events.add("resync");
                return true;
              } finally {
                admissionActive.set(false);
              }
            };
          },
          () -> events.add("ui-complete"));
      controller.start(pause.transferRestoreResponsibility());
      Lizzie.frame = null;
      AtomicBoolean continuationRan = new AtomicBoolean();

      assertDoesNotThrow(() -> controller.abortAndThen(() -> continuationRan.set(true)));

      assertTrue(controller.isExitRecoveryPending());
      assertFalse(controller.isFinished(), "a stop request must not publish teardown completion");
      assertFalse(continuationRan.get());
      assertFalse(admissionActive.get(), "close failed before any admission was captured");
      assertFalse(events.contains("resync"), events.toString());
      assertFalse(events.contains("resume"), events.toString());

      assertDoesNotThrow(controller::abort);
      SwingUtilities.invokeAndWait(() -> {});

      assertFalse(controller.isExitRecoveryPending());
      assertTrue(controller.isFinished());
      assertTrue(continuationRan.get());
      assertFalse(admissionActive.get());
      assertTrue(events.lastIndexOf("runner-close-complete") < events.lastIndexOf("resync"));
      assertTrue(events.lastIndexOf("resync") < events.lastIndexOf("resume"));
      assertTrue(events.lastIndexOf("resume") < events.lastIndexOf("ui-complete"));
    }
  }

  @Test
  void newGameTransitionEndsCoachBeforeOpeningTheNextMode() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      HumanSlGameController controller = env.startCoach(new BlockingHumanSlRunner());
      ModeTransitionFrame frame = allocate(ModeTransitionFrame.class);
      frame.events = new CopyOnWriteArrayList<String>();
      frame.newGameDialog = allocate(CancelledNewGameDialog.class);
      frame.humanSlGame = controller;
      Lizzie.frame = frame;
      Lizzie.leelaz = new Leelaz("");

      SwingUtilities.invokeAndWait(frame::startNewGame);

      assertEquals(List.of("stop-old-mode", "end-coach", "new-game-dialog"), frame.events);
      assertTrue(
          controller.isFinished(), "cancelling the new-game dialog must not revive AI Coach");
      assertNull(frame.humanSlGame);
    }
  }

  @Test
  void newGameTransitionWaitsForAsynchronousCoachTeardown() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      HumanSlGameController controller = env.startCoach(new BlockingHumanSlRunner());
      AtomicReference<Runnable> exitWorker = new AtomicReference<>();
      controller.setExitLifecycleForTesting(
          exitWorker::set, Runnable::run, () -> true, null);
      ModeTransitionFrame frame = allocate(ModeTransitionFrame.class);
      frame.events = new CopyOnWriteArrayList<String>();
      frame.newGameDialog = allocate(CancelledNewGameDialog.class);
      frame.humanSlGame = controller;
      Lizzie.frame = frame;
      Lizzie.leelaz = new Leelaz("");

      SwingUtilities.invokeAndWait(frame::startNewGame);

      assertNotNull(exitWorker.get());
      assertFalse(controller.isFinished(), "stop request is not teardown completion");
      assertSame(controller, frame.humanSlGame);
      assertFalse(frame.events.contains("new-game-dialog"));

      Thread worker = new Thread(exitWorker.get(), "humansl-exit-test");
      worker.start();
      worker.join(1000L);
      assertFalse(worker.isAlive());
      SwingUtilities.invokeAndWait(() -> {});

      assertTrue(controller.isFinished());
      assertNull(frame.humanSlGame);
      assertEquals(List.of("stop-old-mode", "end-coach", "new-game-dialog"), frame.events);
    }
  }

  @Test
  void downloadedSgfCompletionWaitsForDeferredCoachTeardownAndActualLoad() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      HumanSlGameController controller = env.startCoach(new BlockingHumanSlRunner());
      AtomicReference<Runnable> exitWorker = new AtomicReference<>();
      controller.setExitLifecycleForTesting(
          exitWorker::set, Runnable::run, () -> true, null);
      SgfCompletionFrame frame = allocate(SgfCompletionFrame.class);
      frame.humanSlGame = controller;
      Lizzie.frame = frame;
      Lizzie.leelaz = new TrackingLeelaz();
      List<Boolean> completions = new CopyOnWriteArrayList<>();
      AtomicBoolean accepted = new AtomicBoolean();

      SwingUtilities.invokeAndWait(
          () ->
              accepted.set(
                  frame.loadDownloadedSgfString(
                      "(;FF[4]SZ[3];B[aa])", 0, false, false, null, completions::add)));

      assertTrue(accepted.get(), "legacy return reports accepted deferred work");
      assertTrue(completions.isEmpty(), "accepted is not actual load completion");
      assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
      assertNotNull(exitWorker.get());

      Thread worker = new Thread(exitWorker.get(), "deferred-sgf-exit-test");
      worker.start();
      worker.join(1000L);
      assertFalse(worker.isAlive());
      SwingUtilities.invokeAndWait(() -> {});

      assertEquals(List.of(true), completions);
      assertEquals(1, Lizzie.board.getHistory().getStart().numberOfChildren());
      assertTrue(controller.isFinished());
    }
  }

  @Test
  void downloadedSgfCompletionRemainsPendingAcrossExitRecoveryRetry() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      HumanSlGameController controller = env.startCoach(new BlockingHumanSlRunner());
      AtomicInteger preparations = new AtomicInteger();
      controller.setExitLifecyclePreparationForTesting(
          Runnable::run,
          Runnable::run,
          () -> {
            int attempt = preparations.incrementAndGet();
            return () -> attempt > 1;
          },
          null);
      SgfCompletionFrame frame = allocate(SgfCompletionFrame.class);
      frame.humanSlGame = controller;
      Lizzie.leelaz = new TrackingLeelaz();
      List<Boolean> completions = new CopyOnWriteArrayList<>();
      AtomicBoolean accepted = new AtomicBoolean();

      // Suppress the deliberately injected first-retry error dialog while retaining the frame
      // instance that owns the deferred load continuation.
      Lizzie.frame = null;
      SwingUtilities.invokeAndWait(
          () ->
              accepted.set(
                  frame.loadDownloadedSgfString(
                      "(;FF[4]SZ[3];B[aa])", 0, false, false, null, completions::add)));

      assertTrue(accepted.get());
      assertTrue(controller.isExitRecoveryPending());
      assertTrue(completions.isEmpty());
      assertEquals(1, preparations.get());

      Lizzie.frame = frame;
      SwingUtilities.invokeAndWait(controller::abort);

      assertEquals(2, preparations.get());
      assertEquals(List.of(true), completions);
      assertEquals(1, Lizzie.board.getHistory().getStart().numberOfChildren());
      assertTrue(controller.isFinished());
    }
  }

  @Test
  void downloadedSgfCompletionFailsWhenAnotherExitContinuationAlreadyOwnsTeardown()
      throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      HumanSlGameController controller = env.startCoach(new BlockingHumanSlRunner());
      AtomicReference<Runnable> exitWorker = new AtomicReference<>();
      controller.setExitLifecycleForTesting(
          exitWorker::set, Runnable::run, () -> true, null);
      SgfCompletionFrame frame = allocate(SgfCompletionFrame.class);
      frame.humanSlGame = controller;
      Lizzie.frame = frame;
      List<Boolean> completions = new CopyOnWriteArrayList<>();

      controller.abortAndThen(() -> {});
      assertNotNull(exitWorker.get());

      SwingUtilities.invokeAndWait(
          () ->
              assertTrue(
                  frame.loadDownloadedSgfString(
                      "(;FF[4]SZ[3];B[aa])",
                      0,
                      false,
                      false,
                      null,
                      completions::add)));

      assertEquals(List.of(false), completions);
      assertEquals(0, Lizzie.board.getHistory().getMoveNumber());

      Thread worker = new Thread(exitWorker.get(), "owned-sgf-exit-test");
      worker.start();
      worker.join(1000L);
      assertFalse(worker.isAlive());
      SwingUtilities.invokeAndWait(() -> {});
      assertEquals(List.of(false), completions, "completion must be single-shot");
    }
  }

  @Test
  void analyzeGameTransitionEndsCoachEvenWhenTheDialogIsCancelled() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      HumanSlGameController controller = env.startCoach(new BlockingHumanSlRunner());
      ModeTransitionFrame frame = allocate(ModeTransitionFrame.class);
      frame.events = new CopyOnWriteArrayList<String>();
      frame.humanSlGame = controller;
      Lizzie.frame = frame;
      Lizzie.leelaz.noAnalyze = false;

      SwingUtilities.invokeAndWait(frame::startAnalyzeGameDialogReserved);

      assertEquals(List.of("stop-old-mode", "end-coach", "analyze-game-dialog"), frame.events);
      assertTrue(controller.isFinished());
      assertNull(frame.humanSlGame);
    }
  }

  @Test
  void continuePlayingTransitionEndsCoachBeforeStartingTheEngineMode() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      HumanSlGameController controller = env.startCoach(new BlockingHumanSlRunner());
      ModeTransitionFrame frame = allocate(ModeTransitionFrame.class);
      frame.events = new CopyOnWriteArrayList<String>();
      frame.expectedCoach = controller;
      frame.continueModeCompleted = new CountDownLatch(1);
      frame.humanSlGame = controller;
      Lizzie.frame = frame;
      Lizzie.config.genmoveGameNoTime = true;

      SwingUtilities.invokeAndWait(
          () -> frame.continueAiPlayingReserved(true, false, true, false));

      assertTrue(
          frame.continueModeCompleted.await(2, TimeUnit.SECONDS),
          "the retained-engine continuation must reach its mode action");
      assertEquals(List.of("continue-playing"), frame.events);
      assertTrue(frame.coachFinishedBeforeContinueMode);
      assertTrue(controller.isFinished());
      assertNull(frame.humanSlGame);
      assertTrue(frame.isPlayingAgainstLeelaz);
    }
  }

  @Test
  void engineGameTransitionEndsCoachBeforeOpeningTheNextMode() throws Exception {
    try (CoachEnvironment env = CoachEnvironment.open()) {
      HumanSlGameController controller = env.startCoach(new BlockingHumanSlRunner());
      ModeTransitionFrame frame = allocate(ModeTransitionFrame.class);
      frame.events = new CopyOnWriteArrayList<String>();
      frame.expectedCoach = controller;
      frame.humanSlGame = controller;
      Lizzie.frame = frame;
      EngineManager.isEngineGame = false;

      SwingUtilities.invokeAndWait(frame::startEngineGameDialogReserved);

      assertEquals(List.of("engine-game-dialog"), frame.events);
      assertTrue(frame.coachFinishedBeforeEngineDialog);
      assertTrue(
          controller.isFinished(), "closing the engine-game dialog must not revive AI Coach");
      assertNull(frame.humanSlGame);
    }
  }

  private static HumanSlTrainingConfig coachConfig(HumanSlTrainingConfig.PlayerColor playerColor) {
    return HumanSlTrainingConfig.builder()
        .playerColor(playerColor)
        .fromCurrentPosition(true)
        .moveTimeSeconds(2)
        .build();
  }

  private static KeyEvent keyPressed(JPanel source, int keyCode) {
    return new KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, 0, keyCode, KeyEvent.CHAR_UNDEFINED);
  }

  private static boolean awaitCondition(BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.sleep(10L);
    }
    return condition.getAsBoolean();
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    unsafeField.setAccessible(true);
    sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
    return (T) unsafe.allocateInstance(type);
  }

  private static class BlockingHumanSlRunner extends HumanSlAnalysisRunner {
    private final CountDownLatch requestStarted = new CountDownLatch(1);
    private final CountDownLatch releaseResponse = new CountDownLatch(1);
    private final AtomicInteger requestCount = new AtomicInteger();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final List<String> lifecycleEvents;

    private BlockingHumanSlRunner() {
      this(null);
    }

    private BlockingHumanSlRunner(List<String> lifecycleEvents) {
      super("katago analysis", Path.of("human.bin"));
      this.lifecycleEvents = lifecycleEvents;
    }

    @Override
    public Optional<String> bestHumanMove(
        BoardHistoryNode positionNode,
        String profile,
        int maxVisits,
        int rootSymmetries,
        Duration timeout) {
      requestCount.incrementAndGet();
      requestStarted.countDown();
      try {
        if (!releaseResponse.await(5, TimeUnit.SECONDS)) {
          return Optional.empty();
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
      return Optional.of("pass");
    }

    @Override
    public void cancelActiveRequests() {
      if (lifecycleEvents != null) {
        lifecycleEvents.add("runner-cancel");
      }
      releaseResponse.countDown();
    }

    @Override
    public void close() {
      if (lifecycleEvents != null) {
        lifecycleEvents.add("runner-close-complete");
      }
      releaseResponse.countDown();
      closed.countDown();
    }

    private boolean awaitRequest() throws InterruptedException {
      return requestStarted.await(2, TimeUnit.SECONDS);
    }

    private void releaseResponse() {
      releaseResponse.countDown();
    }

    private int requestCount() {
      return requestCount.get();
    }

    private boolean awaitClosed() throws InterruptedException {
      return closed.await(2, TimeUnit.SECONDS);
    }
  }

  private static final class LatchCloseHumanSlRunner extends BlockingHumanSlRunner {
    private final CountDownLatch closeStarted = new CountDownLatch(1);
    private final CountDownLatch closeRelease = new CountDownLatch(1);

    private LatchCloseHumanSlRunner(List<String> lifecycleEvents) {
      super(lifecycleEvents);
    }

    @Override
    public void close() {
      closeStarted.countDown();
      try {
        if (!closeRelease.await(3, TimeUnit.SECONDS)) {
          throw new AssertionError("timed out waiting to release runner close");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("runner close interrupted", interrupted);
      }
      super.close();
    }

    private boolean awaitCloseStarted() throws InterruptedException {
      return closeStarted.await(2, TimeUnit.SECONDS);
    }

    private void releaseClose() {
      closeRelease.countDown();
    }
  }

  private static final class ThrowingCloseHumanSlRunner extends BlockingHumanSlRunner {
    private ThrowingCloseHumanSlRunner(List<String> lifecycleEvents) {
      super(lifecycleEvents);
    }

    @Override
    public void close() {
      super.close();
      throw new AssertionError("close failed");
    }
  }

  private static final class FailOnceCloseHumanSlRunner extends BlockingHumanSlRunner {
    private final AtomicInteger closeAttempts = new AtomicInteger();
    private final List<String> lifecycleEvents;

    private FailOnceCloseHumanSlRunner(List<String> lifecycleEvents) {
      super(lifecycleEvents);
      this.lifecycleEvents = lifecycleEvents;
    }

    @Override
    public void close() {
      int attempt = closeAttempts.incrementAndGet();
      lifecycleEvents.add("runner-close-attempt-" + attempt);
      if (attempt == 1) {
        throw new AssertionError("close failed once");
      }
      super.close();
    }
  }

  private static final class ThrowingPreparationRunner extends HumanSlAnalysisRunner {
    private final Throwable startFailure;
    private final Throwable readinessFailure;
    private final AtomicBoolean verifyCalled = new AtomicBoolean();

    private ThrowingPreparationRunner(Throwable startFailure, Throwable readinessFailure) {
      super("katago analysis", Path.of("human.bin"));
      this.startFailure = startFailure;
      this.readinessFailure = readinessFailure;
    }

    @Override
    public synchronized boolean start() {
      throwUnchecked(startFailure);
      return true;
    }

    @Override
    public boolean verifyReady(
        BoardHistoryNode positionNode, String profile, Duration timeout) {
      verifyCalled.set(true);
      throwUnchecked(readinessFailure);
      return true;
    }

    private static void throwUnchecked(Throwable failure) {
      if (failure instanceof RuntimeException) {
        throw (RuntimeException) failure;
      }
      if (failure instanceof Error) {
        throw (Error) failure;
      }
    }
  }

  private static class CoachFrame extends LizzieFrame {
    @Override
    public void clearKataEstimate() {}

    @Override
    public void showHumanSlTrainingBar(HumanSlGameController controller) {}

    @Override
    public void hideHumanSlTrainingBar(HumanSlGameController controller) {}

    @Override
    public void updateHumanSlTrainingBar() {}

    @Override
    public void setMainPanelFocus() {}

    @Override
    public void refresh() {}

    @Override
    public void updateTitle() {}

    @Override
    public void resetTitle() {}

    @Override
    public void clearTryPlay() {}

    @Override
    public void setResult(String result) {}
  }

  private static final class ThrowingStartCoachFrame extends CoachFrame {
    @Override
    public void showHumanSlTrainingBar(HumanSlGameController controller) {
      throw new IllegalStateException("training bar failed");
    }
  }

  private static final class SgfCompletionFrame extends CoachFrame {
    @Override
    public void setVisible(boolean visible) {}

    @Override
    protected void scheduleMovelistRefreshAfterKifuLoad() {}

    @Override
    public void scheduleResumeAnalysisAfterLoad(int delayMillis, Runnable action) {}
  }

  private static final class ScriptedPrimaryResyncAttempt
      implements HumanSlGameController.PrimaryEngineResyncAttempt {
    private final List<Boolean> currentAfterReplay;
    private final int index;
    private final AtomicInteger executions;
    private final AtomicInteger recaptures;
    private final boolean replaySucceeds;

    private ScriptedPrimaryResyncAttempt(
        List<Boolean> currentAfterReplay,
        int index,
        AtomicInteger executions,
        AtomicInteger recaptures,
        boolean replaySucceeds) {
      this.currentAfterReplay = currentAfterReplay;
      this.index = index;
      this.executions = executions;
      this.recaptures = recaptures;
      this.replaySucceeds = replaySucceeds;
    }

    @Override
    public boolean execute() {
      executions.incrementAndGet();
      return replaySucceeds;
    }

    @Override
    public boolean matchesCurrentBoardAndPrimary() {
      return currentAfterReplay.get(index);
    }

    @Override
    public HumanSlGameController.PrimaryEngineResyncAttempt
        recaptureCurrentPositionForSamePrimary() {
      recaptures.incrementAndGet();
      int next = index + 1;
      return next >= currentAfterReplay.size()
          ? null
          : new ScriptedPrimaryResyncAttempt(
              currentAfterReplay, next, executions, recaptures, replaySucceeds);
    }
  }

  private static final class TrackingOnlineDialog extends OnlineDialog {
    private AtomicInteger stopCalls;

    private TrackingOnlineDialog() {
      super((Window) null);
    }

    @Override
    public void stopSync() {
      stopCalls.incrementAndGet();
      LizzieFrame.urlSgf = false;
    }
  }

  private static final class CoachBoard extends Board {
    @Override
    public void clearAfterMove() {}
  }

  private static final class TrackingLeelaz extends Leelaz {
    private final AtomicInteger resumeCount = new AtomicInteger();
    private final AtomicInteger forwardedMoves = new AtomicInteger();
    private final List<String> lifecycleEvents;
    private boolean pondering;

    private TrackingLeelaz() throws IOException {
      this(null);
    }

    private TrackingLeelaz(List<String> lifecycleEvents) throws IOException {
      super("");
      this.lifecycleEvents = lifecycleEvents;
    }

    @Override
    public boolean isStarted() {
      return true;
    }

    @Override
    public boolean isLoaded() {
      return true;
    }

    @Override
    public boolean isPondering() {
      return pondering;
    }

    @Override
    public void ponder() {
      pondering = true;
      resumeCount.incrementAndGet();
      if (lifecycleEvents != null) {
        lifecycleEvents.add("resume");
      }
    }

    @Override
    public void notPondering() {
      pondering = false;
    }

    @Override
    public void playMove(Stone color, String move) {
      forwardedMoves.incrementAndGet();
    }

    @Override
    public boolean forwardBoardClearWithKomi(
        String komiCommand, double komi, boolean applyKomiSideEffects) {
      return true;
    }
  }

  private static final class ExactReplayTrackingLeelaz extends Leelaz {
    private final List<String> events;
    private boolean pondering;

    private ExactReplayTrackingLeelaz(List<String> events, List<String> exactSnapshots)
        throws IOException {
      super("");
      this.events = events;
      ExactSnapshotRestoreProtocolFixture.install(
          this,
          command -> {
            if (command.startsWith("loadsgf ")) {
              Path snapshot = Path.of(command.substring("loadsgf ".length()).trim());
              exactSnapshots.add(Files.readString(snapshot));
              events.add("exact-" + exactSnapshots.size());
            }
            return ExactSnapshotRestoreProtocolFixture.Response.success();
          });
    }

    @Override
    public boolean isStarted() {
      return true;
    }

    @Override
    public boolean isLoaded() {
      return true;
    }

    @Override
    public boolean isPondering() {
      return pondering;
    }

    @Override
    public void notPondering() {
      pondering = false;
      events.add("pause");
    }

    @Override
    public void ponder() {
      pondering = true;
      events.add("resume");
    }
  }

  private static final class ModeTransitionFrame extends CoachFrame {
    private List<String> events;
    private CancelledNewGameDialog newGameDialog;
    private HumanSlGameController expectedCoach;
    private CountDownLatch continueModeCompleted;
    private boolean coachFinishedBeforeContinueMode;
    private boolean coachFinishedBeforeEngineDialog;

    @Override
    public void endHumanSlGameIfActive() {
      events.add("end-coach");
      super.endHumanSlGameIfActive();
    }

    @Override
    public boolean stopAiPlayingAndPolicy() {
      events.add("stop-old-mode");
      boolean wasHumanSlGame = humanSlGame != null && !humanSlGame.isFinished();
      endHumanSlGameIfActive();
      return wasHumanSlGame;
    }

    @Override
    protected NewGameDialog createNewGameDialog() {
      events.add("new-game-dialog");
      return newGameDialog;
    }

    @Override
    protected void showEngineGameDialogAfterModeTransition() {
      coachFinishedBeforeEngineDialog =
          expectedCoach != null && expectedCoach.isFinished() && humanSlGame == null;
      events.add("engine-game-dialog");
    }

    @Override
    protected void continueAiPlayingReserved(
        boolean isGenmove, boolean continueNow, boolean playerIsBlack, boolean fromShortCut) {
      boolean coachFinished =
          expectedCoach != null && expectedCoach.isFinished() && humanSlGame == null;
      super.continueAiPlayingReserved(isGenmove, continueNow, playerIsBlack, fromShortCut);
      if (isPlayingAgainstLeelaz
          && continueModeCompleted != null
          && continueModeCompleted.getCount() > 0L) {
        coachFinishedBeforeContinueMode = coachFinished;
        events.add("continue-playing");
        continueModeCompleted.countDown();
      }
    }

    @Override
    protected void showAnalyzeGameDialogAfterModeTransition(boolean wasPondering) {
      events.add("analyze-game-dialog");
    }
  }

  private static final class SilentBottomToolbar extends BottomToolbar {
    private SilentBottomToolbar() {}

    @Override
    public void setChkShowBlack(boolean show) {}

    @Override
    public void setChkShowWhite(boolean show) {}
  }

  private static final class SilentMenu extends Menu {
    private SilentMenu() {}

    @Override
    public void setChkShowBlack(boolean show) {}

    @Override
    public void setChkShowWhite(boolean show) {}

    @Override
    public void toggleDoubleMenuGameStatus() {}
  }

  private static final class CancelledNewGameDialog extends NewGameDialog {
    private CancelledNewGameDialog() {
      super((Window) null);
    }

    @Override
    public void setVisible(boolean visible) {}

    @Override
    public boolean playerIsBlack() {
      return true;
    }

    @Override
    public boolean isCancelled() {
      return true;
    }

    @Override
    public void dispose() {}
  }

  private static final class CoachEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Leelaz previousEngine;
    private final boolean previousEngineGame;
    private final boolean previousPreEngineGame;
    private final boolean previousEngineEmpty;
    private final BottomToolbar previousToolbar;
    private final Menu previousMenu;
    private final WinrateGraph previousWinrateGraph;
    private final BoardRenderer previousBoardRenderer;
    private final BoardRenderer previousBoardRenderer2;
    private final boolean previousUrlSgf;
    private final OnlineDialog previousOnlineDialog;
    private final java.io.File previousCurrentFile;
    private final String previousFileNameTitle;

    private CoachEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame,
        Leelaz previousEngine,
        boolean previousEngineGame,
        boolean previousPreEngineGame,
        boolean previousEngineEmpty,
        BottomToolbar previousToolbar,
        Menu previousMenu,
        WinrateGraph previousWinrateGraph,
        BoardRenderer previousBoardRenderer,
        BoardRenderer previousBoardRenderer2,
        boolean previousUrlSgf,
        OnlineDialog previousOnlineDialog,
        java.io.File previousCurrentFile,
        String previousFileNameTitle) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousEngine = previousEngine;
      this.previousEngineGame = previousEngineGame;
      this.previousPreEngineGame = previousPreEngineGame;
      this.previousEngineEmpty = previousEngineEmpty;
      this.previousToolbar = previousToolbar;
      this.previousMenu = previousMenu;
      this.previousWinrateGraph = previousWinrateGraph;
      this.previousBoardRenderer = previousBoardRenderer;
      this.previousBoardRenderer2 = previousBoardRenderer2;
      this.previousUrlSgf = previousUrlSgf;
      this.previousOnlineDialog = previousOnlineDialog;
      this.previousCurrentFile = previousCurrentFile;
      this.previousFileNameTitle = previousFileNameTitle;
    }

    private static CoachEnvironment open() throws Exception {
      CoachEnvironment environment =
          new CoachEnvironment(
              Board.boardWidth,
              Board.boardHeight,
              Lizzie.config,
              Lizzie.board,
              Lizzie.frame,
              Lizzie.leelaz,
              EngineManager.isEngineGame,
              EngineManager.isPreEngineGame,
              EngineManager.isEmpty,
              LizzieFrame.toolbar,
              LizzieFrame.menu,
              LizzieFrame.winrateGraph,
              LizzieFrame.boardRenderer,
              LizzieFrame.boardRenderer2,
              LizzieFrame.urlSgf,
              LizzieFrame.onlineDialog,
              LizzieFrame.curFile,
              LizzieFrame.fileNameTitle);
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      Config config = allocate(Config.class);
      config.playSound = false;
      config.newMoveNumberInBranch = false;
      Lizzie.config = config;
      Lizzie.frame = allocate(CoachFrame.class);
      Lizzie.leelaz = allocate(Leelaz.class);
      LizzieFrame.toolbar = allocate(SilentBottomToolbar.class);
      LizzieFrame.menu = allocate(SilentMenu.class);
      LizzieFrame.winrateGraph = allocate(WinrateGraph.class);
      LizzieFrame.boardRenderer = null;
      LizzieFrame.boardRenderer2 = null;
      LizzieFrame.urlSgf = false;
      LizzieFrame.onlineDialog = null;
      Board board = allocate(CoachBoard.class);
      board.movelistwr = new ArrayList<>();
      board.startStonelist = new ArrayList<>();
      board.tempmovelist = new ArrayList<>();
      board.tempmovelist2 = new ArrayList<>();
      board.setHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      Lizzie.board = board;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      EngineManager.isEmpty = false;
      return environment;
    }

    private HumanSlGameController startCoach(BlockingHumanSlRunner runner) {
      return startCoach(runner, HumanSlTrainingConfig.PlayerColor.BLACK);
    }

    private HumanSlGameController startCoach(
        BlockingHumanSlRunner runner, HumanSlTrainingConfig.PlayerColor playerColor) {
      HumanSlGameController controller =
          new HumanSlGameController(runner, coachConfig(playerColor), new HumanSlTrainingSession());
      controller.setExitLifecycleForTesting(
          Runnable::run, Runnable::run, () -> true, null);
      controller.start();
      assertFalse(controller.isFinished());
      return controller;
    }

    @Override
    public void close() throws Exception {
      try {
        HumanSlGameController active = Lizzie.frame == null ? null : Lizzie.frame.humanSlGame;
        if (active != null && !active.isFinished()) {
          active.abort();
        }
        // Every default exit dispatcher in this fixture is synchronous. Drain the one deliberately
        // asynchronous boundary (the EDT continuation) before replacing Lizzie's global objects.
        SwingUtilities.invokeAndWait(() -> {});
      } finally {
        Board.boardWidth = previousBoardWidth;
        Board.boardHeight = previousBoardHeight;
        Zobrist.init();
        Lizzie.config = previousConfig;
        Lizzie.board = previousBoard;
        Lizzie.frame = previousFrame;
        Lizzie.leelaz = previousEngine;
        EngineManager.isEngineGame = previousEngineGame;
        EngineManager.isPreEngineGame = previousPreEngineGame;
        EngineManager.isEmpty = previousEngineEmpty;
        LizzieFrame.toolbar = previousToolbar;
        LizzieFrame.menu = previousMenu;
        LizzieFrame.winrateGraph = previousWinrateGraph;
        LizzieFrame.boardRenderer = previousBoardRenderer;
        LizzieFrame.boardRenderer2 = previousBoardRenderer2;
        LizzieFrame.urlSgf = previousUrlSgf;
        LizzieFrame.onlineDialog = previousOnlineDialog;
        LizzieFrame.curFile = previousCurrentFile;
        LizzieFrame.fileNameTitle = previousFileNameTitle;
      }
    }
  }
}
