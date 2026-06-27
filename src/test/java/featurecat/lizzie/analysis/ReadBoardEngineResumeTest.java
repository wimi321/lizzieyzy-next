package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.BoardRenderer;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.JCheckBox;
import javax.swing.JTextField;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadBoardEngineResumeTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  private int previousBoardWidth;
  private int previousBoardHeight;

  @BeforeEach
  void setUpFixtureBoardSize() {
    previousBoardWidth = Board.boardWidth;
    previousBoardHeight = Board.boardHeight;
    Board.boardWidth = BOARD_SIZE;
    Board.boardHeight = BOARD_SIZE;
    Zobrist.init();
  }

  @AfterEach
  void tearDownFixtureBoardSize() {
    Board.boardWidth = previousBoardWidth;
    Board.boardHeight = previousBoardHeight;
    Zobrist.init();
  }

  @Test
  void forceRebuildSchedulesResumeAnalysis() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      assertEquals(1, harness.frame.scheduleResumeAnalysisCount);
      assertNotNull(harness.frame.lastScheduledResumeAction);
    }
  }

  @Test
  void forceRebuildStopsPonderBeforeLoadingSnapshot() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.leelaz.isKatago = true;
      harness.leelaz.Pondering();
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      assertEquals(
          0,
          harness.leelaz.clearCount,
          "snapshot rebuild should not use clear(), which restarts ponder.");
      assertEquals("stop", harness.leelaz.sentCommands.get(0));
      assertEquals("clear_board", harness.leelaz.sentCommands.get(1));
      assertTrue(harness.leelaz.sentCommands.get(2).startsWith("loadsgf "));
      assertFalse(
          harness.leelaz.isPondering(),
          "analysis should stay stopped until the scheduled resume runs.");

      harness.frame.lastScheduledResumeAction.run();
      assertEquals(1, harness.leelaz.ponderCount);
    }
  }

  @Test
  void forceRebuildContinuesPlayingAgainstLeelazGenmove() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isPlayingAgainstLeelaz = true;
      harness.frame.playerIsBlack = true;
      setField(harness.readBoard, "needGenmove", true);
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      assertEquals(1, harness.leelaz.genmoveCount);
      assertEquals("W", harness.leelaz.lastGenmoveColor);
      assertEquals(0, harness.frame.scheduleResumeAnalysisCount);
    }
  }

  @Test
  void forceRebuildResumesAutoPlayAfterRemoteChangesFollowingFailedLocalMove() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      buildHistory(
          harness.board,
          placement(0, 0, Stone.BLACK),
          placement(1, 0, Stone.WHITE),
          placement(0, 1, Stone.BLACK));
      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.WHITE)),
              Optional.of(new int[] {2, 0}),
              Stone.WHITE));

      assertEquals(1, harness.leelaz.ponderCount);
      assertEquals(0, harness.leelaz.genmoveCount);
      assertEquals(0, harness.frame.scheduleResumeAnalysisCount);
      assertFalse(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));
    }
  }

  @Test
  void forceRebuildRegeneratesFailedEngineMoveWhenPlayingAgainstLeelaz() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isPlayingAgainstLeelaz = true;
      harness.frame.playerIsBlack = false;
      buildHistory(
          harness.board,
          placement(0, 0, Stone.BLACK),
          placement(1, 0, Stone.WHITE),
          placement(0, 1, Stone.BLACK));
      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.WHITE)),
              Optional.of(new int[] {2, 0}),
              Stone.WHITE));

      assertEquals(1, harness.leelaz.genmoveCount);
      assertEquals("B", harness.leelaz.lastGenmoveColor);
      assertEquals(0, harness.frame.scheduleResumeAnalysisCount);
      assertFalse(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));
    }
  }

  @Test
  void placementFailureRollsBackFailedMainEndAndResumesAnalysisWithPlacementGuard()
      throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode remoteNode = path.nodes.get(1);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);

      assertSame(
          remoteNode,
          harness.board.getHistory().getCurrentHistoryNode(),
          "the local failed engine move must be rolled back to the last remote-confirmed node.");
      assertSame(
          remoteNode,
          harness.board.getHistory().getMainEnd(),
          "the failed child must be removed so the next engine move cannot be consumed as history-next.");
      assertFalse(
          remoteNode.next().isPresent(),
          "the rejected local move should not remain as the next mainline child.");
      assertEquals(
          Stone.EMPTY,
          harness.leelaz.copyStones()[stoneIndex(0, 1)],
          "the engine process must be restored to the rolled-back board before analysis resumes.");
      assertTrue(
          harness.leelaz.sentCommands.stream().anyMatch(command -> command.startsWith("loadsgf ")),
          "rollback should reload an exact snapshot into the engine rather than leaving the failed play applied.");
      assertEquals(
          1,
          harness.leelaz.ponderCount,
          "analysis should resume immediately after rollback; only physical placement is guarded briefly.");
      assertEquals(0, harness.leelaz.genmoveCount);
      assertTrue(
          getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"),
          "auto-play must keep the failed move guard until the remote board is observed or the guard expires.");
      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveAwaitingRemoteObservation"));
      assertTrue(
          harness.readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 1, Stone.BLACK),
          "no local place should be sent during the short remote-observation guard.");

      harness.board.place(0, 1, Stone.BLACK, false, false, false);

      assertSame(
          remoteNode,
          harness.board.getHistory().getCurrentHistoryNode(),
          "an immediate repeat of the failed move should be swallowed before it re-enters the mainline.");
      assertFalse(
          remoteNode.next().isPresent(),
          "an immediate repeat of the failed move should not recreate the rejected child.");
    }
  }

  @Test
  void pendingLocalMoveAckTimeoutRollsBackAndResumesAnalysisWithPlacementGuard() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode remoteNode = path.nodes.get(1);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      setField(
          harness.readBoard, "lastPendingLocalMoveRetryTimeMs", System.currentTimeMillis() - 5000L);
      harness.sync(
          snapshot(
              remoteNode.getData().stones,
              remoteNode.getData().lastMove,
              remoteNode.getData().lastMoveColor));

      assertSame(
          remoteNode,
          harness.board.getHistory().getCurrentHistoryNode(),
          "a pending place without any readboard result must roll back to the last remote-confirmed node.");
      assertSame(
          remoteNode,
          harness.board.getHistory().getMainEnd(),
          "the unconfirmed local child must be removed after the place-result timeout.");
      assertFalse(remoteNode.next().isPresent());
      assertFalse(harness.readBoard.lastMovePlayByLizzie);
      assertFalse(getBooleanField(harness.readBoard, "waitingForReadBoardLocalMoveAck"));
      assertEquals(
          Stone.EMPTY,
          harness.leelaz.copyStones()[stoneIndex(0, 1)],
          "the engine process must not keep the timed-out local move applied.");
      assertTrue(
          harness.leelaz.sentCommands.stream().anyMatch(command -> command.startsWith("loadsgf ")),
          "timeout recovery should reload the rolled-back board into the engine.");
      assertEquals(1, harness.leelaz.ponderCount);
      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));
      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveAwaitingRemoteObservation"));
      assertTrue(
          harness.readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 1, Stone.BLACK),
          "physical placement must still be guarded briefly after timeout recovery.");
    }
  }

  @Test
  void pendingLocalMoveAckTimeoutWithoutSyncFrameRollsBackAndResumesAnalysis() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode remoteNode = path.nodes.get(1);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      setField(
          harness.readBoard, "lastPendingLocalMoveRetryTimeMs", System.currentTimeMillis() - 5000L);

      assertTrue(invokeAckTimeoutWithoutSnapshot(harness.readBoard));

      assertSame(
          remoteNode,
          harness.board.getHistory().getMainEnd(),
          "a pending place must not wait forever when readboard sends no follow-up board frame.");
      assertFalse(harness.readBoard.lastMovePlayByLizzie);
      assertFalse(getBooleanField(harness.readBoard, "waitingForReadBoardLocalMoveAck"));
      assertEquals(1, harness.leelaz.ponderCount);
      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));
      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveAwaitingRemoteObservation"));
    }
  }

  @Test
  void placementFailureLineBypassesStalePlaceCompleteQuarantine() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode remoteNode = path.nodes.get(1);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      setField(harness.readBoard, "ignoreReadBoardPlaceResultsForCurrentPending", true);

      invokePlacementFailedLine(harness.readBoard);

      assertSame(
          remoteNode,
          harness.board.getHistory().getMainEnd(),
          "the stale-placeComplete quarantine must not swallow the real error place failed for the current command.");
      assertFalse(harness.readBoard.lastMovePlayByLizzie);
      assertFalse(getBooleanField(harness.readBoard, "waitingForReadBoardLocalMoveAck"));
      assertEquals(1, harness.leelaz.ponderCount);
    }
  }

  @Test
  void pendingLocalMoveRemoteChangeWithoutTargetRollsBackBeforeAckTimeout() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode remoteNode = path.nodes.get(1);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      setField(harness.readBoard, "lastPendingLocalMoveRetryTimeMs", System.currentTimeMillis());

      int[] misplacedSnapshot =
          snapshot(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.BLACK)),
              Optional.of(new int[] {2, 0}),
              Stone.BLACK);

      harness.sync(misplacedSnapshot);

      assertSame(
          remoteNode,
          harness.board.getHistory().getMainEnd(),
          "if the remote board changed but never contains the pending target, the local pending move must be released immediately.");
      assertFalse(harness.readBoard.lastMovePlayByLizzie);
      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));

      harness.sync(misplacedSnapshot);

      assertEquals(3, harness.board.getHistory().getMainEnd().getData().moveNumber);
      assertTrue(
          getBooleanField(harness.readBoard, "failedLocalMoveWaitingForOurTurnAfterRemoteChange"));
    }
  }

  @Test
  void boardPlaceWhileReadBoardPendingDoesNotMutateMainline() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode pendingNode = path.nodes.get(2);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);

      harness.board.place(2, 2, Stone.WHITE, false, false, false);

      assertSame(
          pendingNode,
          harness.board.getHistory().getMainEnd(),
          "a new local place while readboard is still confirming the previous move must not rewrite the pending target.");
      assertFalse(
          pendingNode.next().isPresent(),
          "the suppressed local place must not create a new mainline child.");
    }
  }

  @Test
  void placementFailureWithUnchangedRemoteSnapshotResumesAnalysisForOurTurn() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode remoteNode = path.nodes.get(1);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);
      harness.sync(
          snapshot(
              remoteNode.getData().stones,
              remoteNode.getData().lastMove,
              remoteNode.getData().lastMoveColor));

      assertEquals(
          1,
          harness.leelaz.ponderCount,
          "if the remote board did not change, it is still our turn and analysis can resume.");
      assertFalse(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));
      assertFalse(getBooleanField(harness.readBoard, "failedLocalMoveAwaitingRemoteObservation"));
      assertFalse(
          getBooleanField(harness.readBoard, "failedLocalMoveWaitingForOurTurnAfterRemoteChange"));
      assertFalse(harness.readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 1, Stone.BLACK));
    }
  }

  @Test
  void placementFailureObservationSurvivesReadBoardControlReset() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode remoteNode = path.nodes.get(1);

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);

      harness.readBoard.parseLine("stopsync");

      assertTrue(
          harness.readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 1, Stone.BLACK),
          "readboard control lines must preserve the short failed-place observation gate.");

      harness.readBoard.parseLine("play");

      assertTrue(
          harness.readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 1, Stone.BLACK),
          "readboard play control lines must not clear the short failed-place observation gate.");

      harness.board.place(0, 1, Stone.BLACK, false, false, false);

      assertSame(remoteNode, harness.board.getHistory().getCurrentHistoryNode());
      assertFalse(remoteNode.next().isPresent());
    }
  }

  @Test
  void autoPlaySideChangeClearsStaleFailedPlacementRecovery() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);

      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));

      invokeClearFailedLocalMoveStateIfAutoPlaySideChanged(harness.readBoard, Stone.WHITE);

      assertTrue(
          getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"),
          "same-side play control should keep the failed-place guard.");

      invokeClearFailedLocalMoveStateIfAutoPlaySideChanged(harness.readBoard, Stone.BLACK);

      assertFalse(
          getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"),
          "switching auto-play from the failed side must drop stale failed-place recovery.");
      assertFalse(getBooleanField(harness.readBoard, "failedLocalMoveSuppressionActive"));
      assertFalse(harness.readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 1, Stone.BLACK));
    }
  }

  @Test
  void placementFailureWithRemoteChangeToOpponentTurnDoesNotResumeAutoPlace() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);
      harness.sync(
          snapshot(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.BLACK)),
              Optional.of(new int[] {2, 0}),
              Stone.BLACK));

      assertEquals(
          1,
          harness.leelaz.ponderCount,
          "the rollback may resume analysis, but the remote-change state must not trigger another local place.");
      assertTrue(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));
      assertFalse(getBooleanField(harness.readBoard, "failedLocalMoveAwaitingRemoteObservation"));
      assertTrue(
          getBooleanField(harness.readBoard, "failedLocalMoveWaitingForOurTurnAfterRemoteChange"));
      assertTrue(
          harness.readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 2, Stone.BLACK),
          "while the remote board says it is the opponent's turn, even a different local move must wait.");
      assertEquals(3, harness.board.getHistory().getMainEnd().getData().moveNumber);
    }
  }

  @Test
  void placementFailureResumesAnalysisAfterOpponentRepliesAndItIsOurTurnAgain() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.isAnaPlayingAgainstLeelaz = true;
      harness.frame.bothSync = true;
      buildHistory(
          harness.board,
          placement(0, 0, Stone.BLACK),
          placement(1, 0, Stone.WHITE),
          placement(0, 1, Stone.BLACK));

      markPendingLocalMoveAwaitingReadBoard(harness.readBoard);
      invokePlacementFailed(harness.readBoard);
      harness.sync(
          snapshot(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.BLACK)),
              Optional.of(new int[] {2, 0}),
              Stone.BLACK));

      assertEquals(
          1,
          harness.leelaz.ponderCount,
          "after the misplaced local stone, analysis may already be running, but no local place should resume yet.");

      harness.sync(
          snapshot(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(2, 0, Stone.BLACK),
                  placement(2, 1, Stone.WHITE)),
              Optional.of(new int[] {2, 1}),
              Stone.WHITE));

      assertEquals(
          1,
          harness.leelaz.ponderCount,
          "if the observed remote board is back to our turn, analyze first and let the next move come from analysis.");
      assertFalse(getBooleanField(harness.readBoard, "failedLocalMoveRecoveryActive"));
      assertFalse(
          getBooleanField(harness.readBoard, "failedLocalMoveWaitingForOurTurnAfterRemoteChange"));
      assertEquals(4, harness.board.getHistory().getMainEnd().getData().moveNumber);
    }
  }

  @Test
  void firstNoChangeFrameAfterRestartSchedulesResumeAnalysis() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);

      harness.readBoard.parseLine("start 3 3");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      assertEquals(1, harness.frame.scheduleResumeAnalysisCount);
      assertNotNull(harness.frame.lastScheduledResumeAction);
    }
  }

  @Test
  void singleMoveRecoverySchedulesResumeAnalysis() throws Exception {
    Stone[] beforeCapture =
        stones(
            placement(0, 1, Stone.BLACK),
            placement(1, 0, Stone.BLACK),
            placement(1, 1, Stone.WHITE),
            placement(2, 1, Stone.BLACK));
    Stone[] afterCapture =
        stones(
            placement(0, 1, Stone.BLACK),
            placement(1, 0, Stone.BLACK),
            placement(2, 1, Stone.BLACK),
            placement(1, 2, Stone.BLACK));

    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(beforeCapture, true))) {
      harness.sync(snapshot(afterCapture, Optional.of(new int[] {1, 2}), Stone.BLACK));

      assertEquals(1, harness.frame.scheduleResumeAnalysisCount);
      assertNotNull(harness.frame.lastScheduledResumeAction);
    }
  }

  @Test
  void syncCommandDoesNotStartPonderWhenEngineIsNotStarted() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.leelaz.started = false;

      harness.readBoard.parseLine("sync");

      assertEquals(0, harness.leelaz.ponderCount);
      assertEquals(true, harness.frame.syncBoard);
    }
  }

  @Test
  void noponderDoesNotStartPonderWhenAlreadyStopped() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.leelaz.notPondering();

      harness.readBoard.parseLine("noponder");

      assertEquals(0, harness.leelaz.togglePonderCount);
      assertEquals(0, harness.leelaz.ponderCount);
      assertFalse(harness.leelaz.isPondering());
    }
  }

  @Test
  void noponderDoesNotRestartPonderAfterAutoPlayStopHandlesEngine() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.leelaz.Pondering();
      harness.frame.isAnaPlayingAgainstLeelaz = true;

      harness.readBoard.parseLine("noponder");

      assertEquals(1, harness.frame.stopAiPlayingAndPolicyCount);
      assertEquals(0, harness.leelaz.togglePonderCount);
      assertEquals(0, harness.leelaz.ponderCount);
      assertFalse(harness.leelaz.isPondering());
    }
  }

  @Test
  void noponderStopsActivePonderWhenNoGameStopHandledIt() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.leelaz.Pondering();

      harness.readBoard.parseLine("noponder");

      assertEquals(1, harness.leelaz.togglePonderCount);
      assertEquals(1, harness.leelaz.nameCmdCount);
      assertEquals(0, harness.leelaz.ponderCount);
      assertFalse(harness.leelaz.isPondering());
    }
  }

  @Test
  void boardOnlyForceRebuildDoesNotScheduleResumeAnalysis() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);

      harness.leelaz.started = false;
      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      assertEquals(0, harness.frame.scheduleResumeAnalysisCount);
      assertNull(harness.frame.lastScheduledResumeAction);

      harness.leelaz.started = true;
      assertEquals(0, harness.leelaz.ponderCount);
    }
  }

  @Test
  void scheduledResumeAnalysisDoesNotRunAfterUserNavigatesAwayFromTarget() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);
      BoardHistoryNode root = harness.board.getHistory().getStart();

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      Runnable scheduledAction = harness.frame.lastScheduledResumeAction;
      assertNotNull(scheduledAction, "sync should bind a target-aware resume action.");

      harness.board.moveToAnyPosition(root);
      scheduledAction.run();

      assertEquals(0, harness.leelaz.ponderCount);
    }
  }

  @Test
  void newerSyncInvalidatesOlderScheduledResumeAction() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      HistoryPath firstPath = buildHistory(harness.board, placement(0, 0, Stone.BLACK));
      BoardHistoryNode moveOne = firstPath.nodes.get(firstPath.nodes.size() - 1);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              moveOne.getData().stones,
              moveOne.getData().lastMove,
              moveOne.getData().lastMoveColor));

      Runnable firstScheduledAction = harness.frame.lastScheduledResumeAction;
      assertNotNull(firstScheduledAction, "first sync should bind a resume action.");

      HistoryPath secondPath = buildHistory(harness.board, placement(1, 0, Stone.WHITE));
      BoardHistoryNode moveTwo = secondPath.nodes.get(secondPath.nodes.size() - 1);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              moveTwo.getData().stones,
              moveTwo.getData().lastMove,
              moveTwo.getData().lastMoveColor));

      Runnable secondScheduledAction = harness.frame.lastScheduledResumeAction;
      assertNotNull(secondScheduledAction, "second sync should replace the resume action.");

      firstScheduledAction.run();
      assertEquals(0, harness.leelaz.ponderCount, "stale resume action should be ignored.");

      secondScheduledAction.run();
      assertEquals(1, harness.leelaz.ponderCount, "latest resume action should still run.");
    }
  }

  @Test
  void syncSpecificResumeSkipsAutoQuickAnalyzeLoadedGame() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      Lizzie.config.autoQuickAnalyzeOnLoad = true;
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      harness.board.moveToAnyPosition(path.nodes.get(path.nodes.size() - 1));

      boolean resumed = harness.frame.ensureAnalysisResumedAfterSyncLoad();

      assertEquals(true, resumed);
      assertEquals(1, harness.leelaz.ponderCount);
      assertEquals(0, harness.frame.flashAnalyzeGameCount);
    }
  }

  @Test
  void readBoardGmaPlayLineWaitsForSyncedBoardBeforeStartingEngineDecision() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      HistoryPath path = buildHistory(harness.board, placement(0, 0, Stone.BLACK));
      BoardHistoryNode staleWhiteTurnNode = path.nodes.get(0);
      harness.board.moveToAnyPosition(staleWhiteTurnNode);

      harness.readBoard.parseLine("play>white>0 0 0 gma");

      assertEquals(
          0,
          harness.leelaz.readBoardGmaCount,
          "play> only arms GMA autoplay; it must not start before the next synced board frame.");

      Stone[] blackToPlayRemoteStones = staleWhiteTurnNode.getData().stones.clone();
      blackToPlayRemoteStones[stoneIndex(1, 0)] = Stone.WHITE;
      harness.sync(snapshot(blackToPlayRemoteStones, Optional.of(new int[] {1, 0}), Stone.WHITE));

      assertEquals(
          0,
          harness.leelaz.readBoardGmaCount,
          "after sync, configured white autoplay must wait because the synced board is black to play.");
    }
  }

  @Test
  void readBoardGmaStartsAfterTrustedFoxCornerMarkerShowsConfiguredSideToMove() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("lastMoveSource foxCornerFlip");
      assertEquals(0, harness.leelaz.readBoardGmaCount);

      Stone[] whiteToPlayRemoteStones = emptyStones();
      whiteToPlayRemoteStones[stoneIndex(0, 0)] = Stone.BLACK;
      harness.sync(snapshot(whiteToPlayRemoteStones, Optional.of(new int[] {0, 0}), Stone.BLACK));

      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("W", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaStartsAfterTrustedRedBlueMarkerShowsConfiguredSideToMove() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();

      harness.readBoard.parseLine("play>black>0 0 0 gma");
      harness.readBoard.parseLine("lastMoveSource redBlueMarker");

      Stone[] blackToPlayRemoteStones = emptyStones();
      blackToPlayRemoteStones[stoneIndex(0, 0)] = Stone.WHITE;
      harness.sync(snapshot(blackToPlayRemoteStones, Optional.of(new int[] {0, 0}), Stone.WHITE));

      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("B", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaSkipsUntrustedHeuristicTurnEvenWhenConfiguredSideMatches()
      throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      harness.board.hasStartStone = true;

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("foxMoveNumber 1");
      harness.readBoard.parseLine("lastMoveSource stoneCount");

      Stone[] whiteToPlayRemoteStones = emptyStones();
      whiteToPlayRemoteStones[stoneIndex(0, 0)] = Stone.BLACK;
      harness.sync(snapshot(whiteToPlayRemoteStones, Optional.empty(), Stone.EMPTY));

      assertEquals(0, harness.leelaz.readBoardGmaCount);
      assertFalse(getBooleanField(harness.readBoard, "readBoardGmaPending"));
    }
  }

  @Test
  void readBoardGmaSkipsMissingLastMoveSourceWhenSetupRiskExists() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      harness.board.hasStartStone = true;

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("foxMoveNumber 1");

      Stone[] whiteToPlayRemoteStones = emptyStones();
      whiteToPlayRemoteStones[stoneIndex(0, 0)] = Stone.BLACK;
      harness.sync(snapshot(whiteToPlayRemoteStones, Optional.empty(), Stone.EMPTY));

      assertEquals(0, harness.leelaz.readBoardGmaCount);
      assertFalse(getBooleanField(harness.readBoard, "readBoardGmaPending"));
    }
  }

  @Test
  void readBoardGmaStartsAfterMarkerlessOrdinaryFoxSyncTrustsTurn() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();

      harness.readBoard.parseLine("play>white>0 0 0 gma");
      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("foxMoveNumber 1");
      harness.readBoard.parseLine("lastMoveSource none");

      Stone[] whiteToPlayRemoteStones = emptyStones();
      whiteToPlayRemoteStones[stoneIndex(0, 0)] = Stone.BLACK;
      harness.sync(snapshot(whiteToPlayRemoteStones, Optional.empty(), Stone.EMPTY));

      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("W", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void readBoardGmaStartsWhenSourceOnlyUpdateRefreshesTurnTrust() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      harness.leelaz.enableReadBoardGmaSupport();
      harness.readBoard.parseLine("play>black>0 0 0 gma");

      Stone[] blackToPlayRemoteStones = emptyStones();
      blackToPlayRemoteStones[stoneIndex(0, 0)] = Stone.WHITE;
      int[] snapshot =
          snapshot(blackToPlayRemoteStones, Optional.of(new int[] {0, 0}), Stone.WHITE);

      harness.readBoard.parseLine("lastMoveSource stoneCount");
      harness.sync(snapshot);
      assertEquals(0, harness.leelaz.readBoardGmaCount);
      assertFalse(getBooleanField(harness.readBoard, "readBoardGmaPending"));

      harness.readBoard.parseLine("lastMoveSource foxCornerFlip");
      harness.sync(snapshot);

      assertEquals(1, harness.leelaz.readBoardGmaCount);
      assertEquals("B", harness.leelaz.lastReadBoardGmaColor);
    }
  }

  @Test
  void gmaFinalMoveReplayingExistingNextNodeStillSendsReadBoardPlace() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.frame.bothSync = true;
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode beforeEngineMove = path.nodes.get(1);
      BoardHistoryNode existingEngineMove = path.nodes.get(2);
      harness.board.moveToAnyPosition(beforeEngineMove);

      ByteArrayOutputStream readBoardBytes = new ByteArrayOutputStream();
      harness.readBoard.process = new AliveProcess();
      setField(harness.readBoard, "usePipe", true);
      setField(harness.readBoard, "outputStream", new BufferedOutputStream(readBoardBytes));
      setField(harness.readBoard, "readBoardGmaPending", true);
      setField(harness.readBoard, "readBoardGmaAutoPlayActive", true);
      setField(harness.readBoard, "readBoardGmaAutoPlayColor", Stone.BLACK);

      boolean consumed =
          harness.readBoard.handleReadBoardGmaEnginePlay(Board.convertCoordinatesToName(0, 1));

      assertTrue(consumed);
      assertSame(
          existingEngineMove,
          harness.board.getHistory().getCurrentHistoryNode(),
          "GMA final move should replay the existing next node instead of creating a duplicate.");
      assertTrue(
          new String(readBoardBytes.toByteArray(), StandardCharsets.UTF_8).contains("place 0 1\n"),
          "GMA final move must still click ReadBoard when local history already contains that next move.");
      assertTrue(
          harness.leelaz.playedMoves.isEmpty(),
          "GMA final move comes from KataGo and must not be echoed back as a normal GTP play.");
    }
  }

  private static HistoryPath buildHistory(TrackingBoard board, Placement... moves) {
    BoardHistoryList history = board.getHistory();
    List<BoardHistoryNode> nodes = new ArrayList<>();
    for (Placement move : moves) {
      history.place(move.x, move.y, move.color, false);
      nodes.add(history.getCurrentHistoryNode());
    }
    return new HistoryPath(nodes);
  }

  private static BoardHistoryList rootHistory(Stone[] stones, boolean blackToPlay) {
    Board.boardWidth = BOARD_SIZE;
    Board.boardHeight = BOARD_SIZE;
    Zobrist.init();
    return new BoardHistoryList(
        BoardData.snapshot(
            stones.clone(),
            Optional.empty(),
            Stone.EMPTY,
            blackToPlay,
            zobrist(stones),
            0,
            new int[BOARD_AREA],
            0,
            0,
            50,
            0));
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static Stone[] stones(Placement... placements) {
    Stone[] stones = emptyStones();
    for (Placement placement : placements) {
      stones[stoneIndex(placement.x, placement.y)] = placement.color;
    }
    return stones;
  }

  private static int[] snapshot(Stone[] stones, Optional<int[]> lastMove, Stone lastMoveColor) {
    int[] snapshot = new int[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      snapshot[index] = normalize(stones[stoneIndex(index % BOARD_SIZE, index / BOARD_SIZE)]);
    }
    if (lastMove.isPresent()) {
      int[] coords = lastMove.get();
      snapshot[coords[1] * BOARD_SIZE + coords[0]] = lastMoveColor == Stone.BLACK ? 3 : 4;
    }
    return snapshot;
  }

  private static int normalize(Stone stone) {
    if (stone == Stone.BLACK || stone == Stone.BLACK_RECURSED) {
      return 1;
    }
    if (stone == Stone.WHITE || stone == Stone.WHITE_RECURSED) {
      return 2;
    }
    return 0;
  }

  private static Zobrist zobrist(Stone[] stones) {
    Zobrist zobrist = new Zobrist();
    for (int x = 0; x < BOARD_SIZE; x++) {
      for (int y = 0; y < BOARD_SIZE; y++) {
        Stone stone = stones[stoneIndex(x, y)];
        if (!stone.isEmpty()) {
          zobrist.toggleStone(x, y, stone);
        }
      }
    }
    return zobrist;
  }

  private static int stoneIndex(int x, int y) {
    return x * BOARD_SIZE + y;
  }

  private static Placement placement(int x, int y, Stone color) {
    return new Placement(x, y, color);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static BottomToolbar minimalToolbar() throws Exception {
    BottomToolbar toolbar = allocate(BottomToolbar.class);
    toolbar.chkAutoPlay = new JCheckBox();
    toolbar.chkAutoPlayBlack = new JCheckBox();
    toolbar.chkAutoPlayWhite = new JCheckBox();
    toolbar.chkAutoPlayTime = new JCheckBox();
    toolbar.chkAutoPlayPlayouts = new JCheckBox();
    toolbar.chkAutoPlayFirstPlayouts = new JCheckBox();
    toolbar.txtAutoPlayTime = new JTextField("0");
    toolbar.txtAutoPlayPlayouts = new JTextField("0");
    toolbar.txtAutoPlayFirstPlayouts = new JTextField("0");
    return toolbar;
  }

  private static boolean getBooleanField(Object target, String name) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    return field.getBoolean(target);
  }

  private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
    Class<?> current = type;
    while (current != null) {
      try {
        return current.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        current = current.getSuperclass();
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static void invokeSyncBoardStones(ReadBoard readBoard) throws Exception {
    Method method = ReadBoard.class.getDeclaredMethod("syncBoardStones", boolean.class);
    method.setAccessible(true);
    method.invoke(readBoard, false);
  }

  private static void markPendingLocalMoveAwaitingReadBoard(ReadBoard readBoard) throws Exception {
    Method method = ReadBoard.class.getDeclaredMethod("startTrackingLocalMoveFromLizzie");
    method.setAccessible(true);
    method.invoke(readBoard);
  }

  private static void invokePlacementFailed(ReadBoard readBoard) throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod("handlePendingLocalMovePlacementFailure", String.class);
    method.setAccessible(true);
    method.invoke(readBoard, "test placement failed");
  }

  private static void invokePlacementFailedLine(ReadBoard readBoard) throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod("handleLocalMovePlacementFailed", String.class);
    method.setAccessible(true);
    method.invoke(readBoard, "error place failed");
  }

  private static boolean invokeAckTimeoutWithoutSnapshot(ReadBoard readBoard) throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod("failPendingLocalMoveIfAckTimedOutWithoutSnapshot");
    method.setAccessible(true);
    return (boolean) method.invoke(readBoard);
  }

  private static void invokeClearFailedLocalMoveStateIfAutoPlaySideChanged(
      ReadBoard readBoard, Stone autoPlayColor) throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod(
            "clearFailedLocalMoveStateIfAutoPlaySideChanged", Stone.class);
    method.setAccessible(true);
    method.invoke(readBoard, autoPlayColor);
  }

  private static final class EngineResumeHarness implements AutoCloseable {
    private final Config previousConfig;
    private final Board previousBoard;
    private final Leelaz previousLeelaz;
    private final LizzieFrame previousFrame;
    private final BoardRenderer previousBoardRenderer;
    private final BottomToolbar previousToolbar;
    private final TrackingBoard board;
    private final TrackingFrame frame;
    private final SnapshotTrackingLeelaz leelaz;
    private final ReadBoard readBoard;

    private EngineResumeHarness(
        Config previousConfig,
        Board previousBoard,
        Leelaz previousLeelaz,
        LizzieFrame previousFrame,
        BoardRenderer previousBoardRenderer,
        BottomToolbar previousToolbar,
        TrackingBoard board,
        TrackingFrame frame,
        SnapshotTrackingLeelaz leelaz,
        ReadBoard readBoard) {
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousLeelaz = previousLeelaz;
      this.previousFrame = previousFrame;
      this.previousBoardRenderer = previousBoardRenderer;
      this.previousToolbar = previousToolbar;
      this.board = board;
      this.frame = frame;
      this.leelaz = leelaz;
      this.readBoard = readBoard;
    }

    private static EngineResumeHarness create(BoardHistoryList history) throws Exception {
      Config previousConfig = Lizzie.config;
      Board previousBoard = Lizzie.board;
      Leelaz previousLeelaz = Lizzie.leelaz;
      LizzieFrame previousFrame = Lizzie.frame;
      BoardRenderer previousBoardRenderer = LizzieFrame.boardRenderer;
      BottomToolbar previousToolbar = LizzieFrame.toolbar;

      Config config = allocate(Config.class);
      config.alwaysSyncBoardStat = false;
      config.alwaysGotoLastOnLive = false;
      config.newMoveNumberInBranch = true;
      config.noCapture = false;
      config.readBoardPonder = true;
      config.winrateAlwaysBlack = false;
      config.leelazConfig = new JSONObject().put("max-game-thinking-time-seconds", 2);
      Lizzie.config = config;

      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      leelaz.canSuicidal = false;
      Lizzie.leelaz = leelaz;

      TrackingBoard board = allocate(TrackingBoard.class);
      board.initialize(history);
      Lizzie.board = board;

      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.initialize(board);
      Lizzie.frame = frame;
      LizzieFrame.boardRenderer = new BoardRenderer(false);
      LizzieFrame.toolbar = minimalToolbar();

      ReadBoard readBoard = allocate(ReadBoard.class);
      setField(readBoard, "conflictTracker", new SyncConflictTracker());
      setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
      setField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
      setField(readBoard, "tempcount", new ArrayList<Integer>());
      readBoard.firstSync = false;
      frame.readBoard = readBoard;

      return new EngineResumeHarness(
          previousConfig,
          previousBoard,
          previousLeelaz,
          previousFrame,
          previousBoardRenderer,
          previousToolbar,
          board,
          frame,
          leelaz,
          readBoard);
    }

    private void sync(int[] snapshotCodes) throws Exception {
      ArrayList<Integer> counts = new ArrayList<>(snapshotCodes.length);
      for (int code : snapshotCodes) {
        counts.add(code);
      }
      setField(readBoard, "tempcount", counts);
      invokeSyncBoardStones(readBoard);
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.frame = previousFrame;
      LizzieFrame.boardRenderer = previousBoardRenderer;
      LizzieFrame.toolbar = previousToolbar;
    }
  }

  private static final class TrackingBoard extends Board {
    private void initialize(BoardHistoryList history) {
      setHistory(history);
      hasStartStone = false;
    }

    @Override
    public void clearAfterMove() {}

    @Override
    public void clear(boolean isEngineGame) {
      setHistory(rootHistory(emptyStones(), true));
      hasStartStone = false;
      if (Lizzie.frame != null && Lizzie.frame.readBoard != null) {
        Lizzie.frame.readBoard.firstSync = true;
      }
    }

    @Override
    public void placeForSync(int x, int y, Stone color, boolean newBranch) {
      getHistory().place(x, y, color, newBranch);
      if (Lizzie.frame != null && Lizzie.frame.readBoard != null) {
        Lizzie.frame.readBoard.lastMovePlayByLizzie = false;
      }
    }

    @Override
    public void moveToAnyPosition(BoardHistoryNode targetNode) {
      getHistory().setHead(targetNode);
    }

    @Override
    public boolean previousMove(boolean needRefresh) {
      Optional<BoardData> previous = getHistory().previous();
      return previous.isPresent();
    }

    @Override
    public void addStartListAll() {}

    @Override
    public void flatten() {}
  }

  private static final class TrackingFrame extends LizzieFrame {
    private int scheduleResumeAnalysisCount;
    private int stopAiPlayingAndPolicyCount;
    private int flashAnalyzeGameCount;
    private Runnable lastScheduledResumeAction;
    private TrackingBoard board;

    private void initialize(TrackingBoard board) {
      this.board = board;
      bothSync = false;
      syncBoard = false;
      isPlayingAgainstLeelaz = false;
      playerIsBlack = true;
    }

    @Override
    public void refresh() {}

    @Override
    public void flashAnalyzeGame(boolean isAllGame, boolean isAllBranches, boolean silentAnalyze) {
      flashAnalyzeGameCount++;
    }

    @Override
    public void renderVarTree(int vw, int vh, boolean changeSize, boolean needGetEnd) {}

    @Override
    public void lastMove() {
      board.getHistory().setHead(board.getHistory().getMainEnd());
    }

    @Override
    public void clearKataEstimate() {}

    @Override
    public void resetTitle() {}

    @Override
    public void clearTryPlay() {}

    @Override
    public boolean stopAiPlayingAndPolicy() {
      stopAiPlayingAndPolicyCount++;
      boolean wasGaming = isPlayingAgainstLeelaz || isAnaPlayingAgainstLeelaz;
      isPlayingAgainstLeelaz = false;
      isAnaPlayingAgainstLeelaz = false;
      if (Lizzie.leelaz != null) {
        Lizzie.leelaz.notPondering();
        Lizzie.leelaz.isThinking = false;
      }
      return wasGaming;
    }

    @Override
    public void scheduleResumeAnalysisAfterLoad(int delayMillis) {
      scheduleResumeAnalysisCount++;
      lastScheduledResumeAction = null;
    }

    public void scheduleResumeAnalysisAfterLoad(int delayMillis, Runnable action) {
      scheduleResumeAnalysisCount++;
      lastScheduledResumeAction = action;
    }
  }

  private static final class HistoryPath {
    private final List<BoardHistoryNode> nodes;

    private HistoryPath(List<BoardHistoryNode> nodes) {
      this.nodes = nodes;
    }
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

  private static final class AliveProcess extends Process {
    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
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
    public void destroy() {}

    @Override
    public boolean isAlive() {
      return true;
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
