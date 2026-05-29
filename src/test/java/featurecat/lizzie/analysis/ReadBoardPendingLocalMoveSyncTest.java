package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReadBoardPendingLocalMoveSyncTest {
  @Test
  void clearsPendingLocalMoveAfterSnapshotCatchesUpToLocalBoard() throws Exception {
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Board.boardWidth = 2;
      Board.boardHeight = 2;
      Zobrist.init();
      ReadBoard readBoard = allocate(ReadBoard.class);
      initializeReadBoardTrackers(readBoard);
      Board board = allocate(Board.class);
      board.setHistory(
          moveHistory(
              stones(Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY),
              new int[] {0, 0},
              Stone.BLACK,
              false,
              1));
      Lizzie.board = board;
      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;
      invokeStartTrackingLocalMove(readBoard);

      boolean acknowledged =
          invokeAcknowledgeLocalMove(
              readBoard,
              new Stone[] {Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY},
              new int[] {1, 0, 0, 0});

      assertFalse(
          readBoard.lastMovePlayByLizzie,
          "once the remote snapshot matches the local board, pending local-move ignore state should be cleared.");
      assertTrue(
          acknowledged,
          "syncBoardStones must stop the current pass once this snapshot acknowledges the pending move.");
    } finally {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void keepsPendingLocalMoveWhileRemoteSnapshotIsStillBehind() throws Exception {
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Board.boardWidth = 2;
      Board.boardHeight = 2;
      Zobrist.init();
      ReadBoard readBoard = allocate(ReadBoard.class);
      initializeReadBoardTrackers(readBoard);
      Board board = allocate(Board.class);
      board.setHistory(
          moveHistory(
              stones(Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY),
              new int[] {0, 0},
              Stone.BLACK,
              false,
              1));
      Lizzie.board = board;
      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;
      invokeStartTrackingLocalMove(readBoard);

      boolean acknowledged =
          invokeAcknowledgeLocalMove(
              readBoard,
              new Stone[] {Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY},
              new int[] {0, 0, 0, 0});

      assertTrue(
          readBoard.lastMovePlayByLizzie,
          "the ignore state must remain while the remote board still has not caught up to the local move.");
      assertFalse(
          acknowledged, "a missing pending move must not short-circuit the rest of the sync pass.");
    } finally {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void placeCompleteWaitsForRemoteSnapshotBeforeClearingPendingMove() throws Exception {
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Board.boardWidth = 2;
      Board.boardHeight = 2;
      Zobrist.init();
      ReadBoard readBoard = allocate(ReadBoard.class);
      initializeReadBoardTrackers(readBoard);
      Board board = allocate(Board.class);
      board.setHistory(
          moveHistory(
              stones(Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY),
              new int[] {0, 0},
              Stone.BLACK,
              false,
              1));
      Lizzie.board = board;
      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;

      invokeStartTrackingLocalMove(readBoard);

      assertTrue(
          invokeShouldIgnoreCurrentLastLocalMove(readBoard),
          "before readboard confirms the injected move, a trailing snapshot may still be waiting to catch up.");

      invokeMarkLocalMoveCommandCompleted(readBoard);

      assertTrue(
          invokeShouldIgnoreCurrentLastLocalMove(readBoard),
          "placeComplete is only a connector hint; the pending move must wait for the remote snapshot.");

      boolean acknowledged =
          invokeAcknowledgeLocalMove(
              readBoard,
              new Stone[] {Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY},
              new int[] {1, 0, 0, 0});

      assertTrue(acknowledged);
      assertFalse(
          invokeShouldIgnoreCurrentLastLocalMove(readBoard),
          "the pending move clears only after the remote snapshot contains the placed stone.");
    } finally {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void externalPlaceCommandIsSkippedWhilePreviousLocalMoveIsPending() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      ReadBoard readBoard = allocate(ReadBoard.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;

      TrackingBufferedOutputStream outputStream = new TrackingBufferedOutputStream();
      setField(readBoard, "usePipe", true);
      setField(readBoard, "outputStream", outputStream);

      invokeStartTrackingLocalMove(readBoard);

      readBoard.sendCommand("place 1 1");

      assertFalse(
          outputStream.writtenText().contains("place 1 1"),
          "a second place command must not be sent while the previous local move is still waiting for readboard.");
    } finally {
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void readBoardControlResetPreservesPendingLocalMoveAwaitingAck() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      ReadBoard readBoard = allocate(ReadBoard.class);
      setField(readBoard, "conflictTracker", new SyncConflictTracker());
      setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());

      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;

      invokeStartTrackingLocalMove(readBoard);

      invokeResetActiveSyncStateForReadBoardControlLine(readBoard);

      assertTrue(
          invokeShouldIgnoreCurrentLastLocalMove(readBoard),
          "readboard start/clear control lines must not clear a local move before placeComplete or error place failed.");
    } finally {
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void doesNotRetryPlaceCommandAfterPlaceCompleteWhileWaitingForSnapshot() throws Exception {
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Board.boardWidth = 2;
      Board.boardHeight = 2;
      Zobrist.init();

      ReadBoard readBoard = allocate(ReadBoard.class);
      initializeReadBoardTrackers(readBoard);
      Board board = allocate(Board.class);
      board.setHistory(
          moveHistory(
              stones(Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY),
              new int[] {0, 0},
              Stone.BLACK,
              false,
              1));
      Lizzie.board = board;

      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;

      TrackingBufferedOutputStream outputStream = new TrackingBufferedOutputStream();
      setField(readBoard, "usePipe", true);
      setField(readBoard, "outputStream", outputStream);

      invokeStartTrackingLocalMove(readBoard);
      invokeMarkLocalMoveCommandCompleted(readBoard);
      setField(readBoard, "lastPendingLocalMoveRetryTimeMs", 0L);

      invokeRetryPendingLocalMoveIfSnapshotStillMissing(readBoard, new int[] {0, 0, 0, 0});

      assertFalse(
          outputStream.writtenText().contains("place 0 0"),
          "a completed click attempt that did not appear in the next snapshot should not keep clicking.");
      assertTrue(
          invokeShouldIgnoreCurrentLastLocalMove(readBoard),
          "placeComplete alone must not confirm the move; a later snapshot still has to contain the stone.");
    } finally {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void keepsPendingLocalMoveWithoutConnectorResultBeforeAckTimeout() throws Exception {
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Board.boardWidth = 2;
      Board.boardHeight = 2;
      Zobrist.init();

      ReadBoard readBoard = allocate(ReadBoard.class);
      initializeReadBoardTrackers(readBoard);
      Board board = allocate(Board.class);
      board.setHistory(
          moveHistory(
              stones(Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY),
              new int[] {0, 0},
              Stone.BLACK,
              false,
              1));
      Lizzie.board = board;

      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;

      TrackingBufferedOutputStream outputStream = new TrackingBufferedOutputStream();
      setField(readBoard, "usePipe", true);
      setField(readBoard, "outputStream", outputStream);

      invokeStartTrackingLocalMove(readBoard);
      setField(readBoard, "lastPendingLocalMoveRetryTimeMs", System.currentTimeMillis());

      boolean stoppedCurrentSyncPass =
          invokeRetryPendingLocalMoveIfSnapshotStillMissing(readBoard, new int[] {0, 0, 0, 0});

      assertFalse(
          stoppedCurrentSyncPass,
          "a recent place attempt should keep waiting without ending the current sync pass.");
      assertFalse(
          outputStream.writtenText().contains("place 0 0"),
          "a missing placeComplete should not trigger another mouse click.");
      assertTrue(
          invokeShouldIgnoreCurrentLastLocalMove(readBoard),
          "without either placeComplete or error place failed, the connector may still be verifying the move.");
    } finally {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void pendingLocalMoveWithoutConnectorResultTimesOutAndStopsHoldingSnapshots() throws Exception {
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Board.boardWidth = 2;
      Board.boardHeight = 2;
      Zobrist.init();

      ReadBoard readBoard = allocate(ReadBoard.class);
      initializeReadBoardTrackers(readBoard);
      Board board = allocate(Board.class);
      board.setHistory(
          moveHistory(
              stones(Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY),
              new int[] {0, 0},
              Stone.BLACK,
              false,
              1));
      Lizzie.board = board;

      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;

      TrackingBufferedOutputStream outputStream = new TrackingBufferedOutputStream();
      setField(readBoard, "usePipe", true);
      setField(readBoard, "outputStream", outputStream);

      invokeStartTrackingLocalMove(readBoard);
      setField(readBoard, "lastPendingLocalMoveRetryTimeMs", System.currentTimeMillis() - 5000L);

      boolean stoppedCurrentSyncPass =
          invokeRetryPendingLocalMoveIfSnapshotStillMissing(readBoard, new int[] {0, 0, 0, 0});

      assertTrue(
          stoppedCurrentSyncPass,
          "a timed-out place attempt rolls back local state, so the current sync pass must stop before using stale local stones.");
      assertFalse(
          outputStream.writtenText().contains("place 0 0"),
          "a missing place result must not trigger another mouse click.");
      assertFalse(
          invokeShouldIgnoreCurrentLastLocalMove(readBoard),
          "after the place-result timeout, the missing local stone must stop being ignored.");
      assertFalse(
          invokeShouldHoldCompleteSnapshotRecoveryForPendingLocalMove(
              readBoard, new int[] {0, 0, 0, 0}),
          "after the timeout clears pending tracking, complete snapshot recovery must not stay held.");
    } finally {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void placementFailureNoRemoteChangeReleasesMoveForFreshAnalysis() throws Exception {
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Board.boardWidth = 2;
      Board.boardHeight = 2;
      Zobrist.init();

      ReadBoard readBoard = allocate(ReadBoard.class);
      initializeReadBoardTrackers(readBoard);
      Config config = allocate(Config.class);
      config.alwaysSyncBoardStat = false;
      Lizzie.config = config;

      Board board = allocate(Board.class);
      Stone[] localStones = stones(Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY);
      board.setHistory(moveHistory(localStones, new int[] {0, 0}, Stone.BLACK, false, 1));
      Lizzie.board = board;

      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;

      invokeStartTrackingLocalMove(readBoard);
      invokeHandleLocalMovePlacementFailed(readBoard, "error place failed");
      invokeRetryPendingLocalMoveIfSnapshotStillMissing(readBoard, new int[] {0, 0, 0, 0});
      setField(readBoard, "failedLocalMoveSuppressionSnapshotKey", "0000");

      assertFalse(
          readBoard.lastMovePlayByLizzie,
          "an explicit connector failure must stop ignoring the missing local move.");
      assertTrue(
          invokeShouldResyncAfterIncrementalSync(readBoard, localStones, new int[] {0, 0, 0, 0}),
          "once the failed move is no longer pending, the one-stone local/remote mismatch should drive resync.");
      assertTrue(
          readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 0, Stone.BLACK),
          "the same local move should be suppressed immediately after readboard rejects it.");
      assertTrue(
          readBoard.shouldSuppressLocalPlaceAfterFailedSync(1, 0, Stone.BLACK),
          "before the remote board is observed, all local moves should be suppressed.");

      invokeUpdateFailedLocalMoveSuppressionForSnapshot(readBoard, new int[] {0, 0, 0, 0});

      assertFalse(
          readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 0, Stone.BLACK),
          "once the remote snapshot proves nothing was added, analysis may choose and send a fresh move.");

      invokeStartTrackingLocalMove(readBoard);
      setField(readBoard, "pendingLocalMoveBaselineKey", "0000");
      invokeRetryPendingLocalMoveIfSnapshotStillMissing(readBoard, new int[] {0, 1, 0, 0});

      invokeUpdateFailedLocalMoveSuppressionForSnapshot(readBoard, new int[] {0, 1, 0, 0});

      assertTrue(
          readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 0, Stone.BLACK),
          "a raw snapshot change should stay suppressed until sync decides whose turn it is.");
      assertTrue(
          readBoard.shouldSuppressLocalPlaceAfterFailedSync(1, 0, Stone.BLACK),
          "a changed snapshot must suppress even a different candidate until the turn has been resolved.");
    } finally {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void placementFailureObservationGuardExpiresWithoutRemoteSnapshot() throws Exception {
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Board.boardWidth = 2;
      Board.boardHeight = 2;
      Zobrist.init();

      ReadBoard readBoard = allocate(ReadBoard.class);
      initializeReadBoardTrackers(readBoard);
      Config config = allocate(Config.class);
      config.alwaysSyncBoardStat = false;
      Lizzie.config = config;

      Board board = allocate(Board.class);
      board.setHistory(
          moveHistory(
              stones(Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY),
              new int[] {0, 0},
              Stone.BLACK,
              false,
              1));
      Lizzie.board = board;

      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;

      invokeStartTrackingLocalMove(readBoard);
      invokeHandleLocalMovePlacementFailed(readBoard, "error place failed");
      invokeRetryPendingLocalMoveIfSnapshotStillMissing(readBoard, new int[] {0, 0, 0, 0});
      setField(readBoard, "failedLocalMoveObservationDeadlineMs", System.currentTimeMillis() - 1L);

      assertFalse(
          readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 0, Stone.BLACK),
          "if readboard does not send another snapshot promptly, the guard must expire and allow a fresh analyzed move.");
      assertFalse(
          readBoard.shouldSuppressLocalPlaceAfterFailedSync(1, 0, Stone.BLACK),
          "the expired guard must not keep blocking different candidate moves either.");
    } finally {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void placementFailureVisibleInNextSnapshotClearsFailedMoveSuppression() throws Exception {
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Board.boardWidth = 2;
      Board.boardHeight = 2;
      Zobrist.init();

      ReadBoard readBoard = allocate(ReadBoard.class);
      initializeReadBoardTrackers(readBoard);
      Config config = allocate(Config.class);
      config.alwaysSyncBoardStat = false;
      Lizzie.config = config;

      Board board = allocate(Board.class);
      board.setHistory(
          moveHistory(
              stones(Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY),
              new int[] {0, 0},
              Stone.BLACK,
              false,
              1));
      Lizzie.board = board;

      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;

      invokeStartTrackingLocalMove(readBoard);
      invokeHandleLocalMovePlacementFailed(readBoard, "error place failed");
      assertTrue(
          readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 0, Stone.BLACK),
          "an explicit connector failure immediately rolls back and briefly guards the failed move.");

      invokeUpdateFailedLocalMoveSuppressionForSnapshot(readBoard, new int[] {3, 0, 0, 0});

      assertFalse(
          readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 0, Stone.BLACK),
          "if the next remote snapshot contains the rejected move, the failure was stale and must not suppress later play.");
    } finally {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void placeCompleteKeepsPendingGuardUntilConfirmedMoveAppearsInSnapshot() throws Exception {
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Board.boardWidth = 2;
      Board.boardHeight = 2;
      Zobrist.init();

      ReadBoard readBoard = allocate(ReadBoard.class);
      initializeReadBoardTrackers(readBoard);
      Board board = allocate(Board.class);
      board.setHistory(
          moveHistory(
              stones(Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY),
              new int[] {0, 0},
              Stone.BLACK,
              false,
              1));
      Lizzie.board = board;

      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;

      invokeStartTrackingLocalMove(readBoard);
      invokeMarkLocalMoveCommandCompleted(readBoard);

      assertTrue(
          invokeShouldHoldCompleteSnapshotRecoveryForPendingLocalMove(
              readBoard, new int[] {0, 0, 0, 0}),
          "a stale snapshot arriving after placeComplete must not recover analysis to the previous move.");

      boolean acknowledged =
          invokeAcknowledgeLocalMove(
              readBoard,
              new Stone[] {Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY},
              new int[] {1, 0, 0, 0});

      assertTrue(acknowledged);
      assertFalse(
          invokeShouldHoldCompleteSnapshotRecoveryForPendingLocalMove(
              readBoard, new int[] {1, 0, 0, 0}),
          "once the confirmed move is visible remotely, normal synchronization may continue.");
    } finally {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void placementFailureWithoutPendingMoveDoesNotCreateSuppression() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      ReadBoard readBoard = allocate(ReadBoard.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;

      invokeHandleLocalMovePlacementFailed(readBoard, "error place failed");

      assertFalse(
          readBoard.shouldSuppressLocalPlaceAfterFailedSync(0, 0, Stone.BLACK),
          "a stale placement failure line should not suppress future local moves when no move is pending.");
    } finally {
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void snapshotContainingPendingLocalMoveClearsIgnoreEvenWithOtherDiffs() throws Exception {
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Board.boardWidth = 2;
      Board.boardHeight = 2;
      Zobrist.init();

      ReadBoard readBoard = allocate(ReadBoard.class);
      Board board = allocate(Board.class);
      board.setHistory(
          moveHistory(
              stones(Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY),
              new int[] {0, 0},
              Stone.BLACK,
              false,
              1));
      Lizzie.board = board;

      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;

      invokeStartTrackingLocalMove(readBoard);

      boolean acknowledged =
          invokeAcknowledgeLocalMove(
              readBoard,
              new Stone[] {Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY},
              new int[] {1, 2, 0, 0});

      assertFalse(
          readBoard.lastMovePlayByLizzie,
          "once the pending local move is visible remotely, later diffs should be handled as normal sync instead of keeping the old move pending.");
      assertTrue(
          acknowledged,
          "a snapshot containing the pending move should be handled in a fresh sync pass even when other intersections differ.");
    } finally {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void snapshotMarkerStillRequiresResyncWhenRemoteSnapshotMissesThatStone() throws Exception {
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Board.boardWidth = 2;
      Board.boardHeight = 2;
      Zobrist.init();

      ReadBoard readBoard = allocate(ReadBoard.class);
      Config config = allocate(Config.class);
      config.alwaysSyncBoardStat = false;
      Lizzie.config = config;

      Board board = allocate(Board.class);
      Stone[] localStones = stones(Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY);
      board.setHistory(snapshotMarkerHistory(localStones, new int[] {0, 0}, Stone.BLACK, false, 1));
      Lizzie.board = board;

      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;
      invokeStartTrackingLocalMove(readBoard);

      boolean shouldResync =
          invokeShouldResyncAfterIncrementalSync(readBoard, localStones, new int[] {0, 0, 0, 0});

      assertTrue(
          shouldResync,
          "a SNAPSHOT marker cannot stand in for a real pending local move, so a missing remote stone must still force resync.");
    } finally {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void snapshotMarkerDoesNotAcknowledgePendingLocalMove() throws Exception {
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Board.boardWidth = 2;
      Board.boardHeight = 2;
      Zobrist.init();

      ReadBoard readBoard = allocate(ReadBoard.class);
      Board board = allocate(Board.class);
      Stone[] localStones = stones(Stone.BLACK, Stone.EMPTY, Stone.EMPTY, Stone.EMPTY);
      board.setHistory(snapshotMarkerHistory(localStones, new int[] {0, 0}, Stone.BLACK, false, 1));
      Lizzie.board = board;

      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.bothSync = true;
      Lizzie.frame = frame;
      invokeStartTrackingLocalMove(readBoard);

      boolean acknowledged =
          invokeAcknowledgeLocalMove(readBoard, localStones, new int[] {1, 0, 0, 0});

      assertTrue(
          readBoard.lastMovePlayByLizzie,
          "snapshot metadata cannot acknowledge a pending local move before a real MOVE node exists.");
      assertFalse(acknowledged, "snapshot metadata must not short-circuit the current sync pass.");
    } finally {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static boolean invokeShouldResyncAfterIncrementalSync(
      ReadBoard readBoard, Stone[] stones, int[] snapshotCodes) throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod(
            "shouldResyncAfterIncrementalSync", Stone[].class, int[].class);
    method.setAccessible(true);
    return (boolean) method.invoke(readBoard, stones, snapshotCodes);
  }

  private static boolean invokeAcknowledgeLocalMove(
      ReadBoard readBoard, Stone[] stones, int[] snapshotCodes) throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod(
            "acknowledgeLocalMoveIfSnapshotCaughtUp", Stone[].class, int[].class);
    method.setAccessible(true);
    return (boolean) method.invoke(readBoard, stones, snapshotCodes);
  }

  private static void invokeStartTrackingLocalMove(ReadBoard readBoard) throws Exception {
    Method method = ReadBoard.class.getDeclaredMethod("startTrackingLocalMoveFromLizzie");
    method.setAccessible(true);
    method.invoke(readBoard);
  }

  private static void invokeMarkLocalMoveCommandCompleted(ReadBoard readBoard) throws Exception {
    Method method = ReadBoard.class.getDeclaredMethod("markLocalMoveCommandCompleted");
    method.setAccessible(true);
    method.invoke(readBoard);
  }

  private static void invokeResetActiveSyncStateForReadBoardControlLine(ReadBoard readBoard)
      throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod("resetActiveSyncStateForReadBoardControlLine");
    method.setAccessible(true);
    method.invoke(readBoard);
  }

  private static boolean invokeRetryPendingLocalMoveIfSnapshotStillMissing(
      ReadBoard readBoard, int[] snapshotCodes) throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod(
            "retryPendingLocalMoveIfSnapshotStillMissing", int[].class);
    method.setAccessible(true);
    return (boolean) method.invoke(readBoard, snapshotCodes);
  }

  private static void invokeHandleLocalMovePlacementFailed(ReadBoard readBoard, String line)
      throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod("handleLocalMovePlacementFailed", String.class);
    method.setAccessible(true);
    method.invoke(readBoard, line);
  }

  private static void invokeUpdateFailedLocalMoveSuppressionForSnapshot(
      ReadBoard readBoard, int[] snapshotCodes) throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod(
            "updateFailedLocalMoveSuppressionForSnapshot", int[].class);
    method.setAccessible(true);
    method.invoke(readBoard, snapshotCodes);
  }

  private static void invokeUpdateConfirmedLocalMoveForSnapshot(
      ReadBoard readBoard, int[] snapshotCodes) throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod("updateConfirmedLocalMoveForSnapshot", int[].class);
    method.setAccessible(true);
    method.invoke(readBoard, snapshotCodes);
  }

  private static boolean invokeShouldHoldSyncForConfirmedLocalMove(
      ReadBoard readBoard, int[] snapshotCodes) throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod("shouldHoldSyncForConfirmedLocalMove", int[].class);
    method.setAccessible(true);
    return (boolean) method.invoke(readBoard, snapshotCodes);
  }

  private static boolean invokeShouldHoldCompleteSnapshotRecoveryForPendingLocalMove(
      ReadBoard readBoard, int[] snapshotCodes) throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod(
            "shouldHoldCompleteSnapshotRecoveryForPendingLocalMove", int[].class);
    method.setAccessible(true);
    return (boolean) method.invoke(readBoard, snapshotCodes);
  }

  private static boolean invokeShouldIgnoreCurrentLastLocalMove(ReadBoard readBoard)
      throws Exception {
    Method method = ReadBoard.class.getDeclaredMethod("shouldIgnoreCurrentLastLocalMove");
    method.setAccessible(true);
    return (boolean) method.invoke(readBoard);
  }

  private static BoardHistoryList snapshotMarkerHistory(
      Stone[] stones, int[] marker, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    return new BoardHistoryList(
        BoardData.snapshot(
            stones.clone(),
            Optional.of(marker),
            lastMoveColor,
            blackToPlay,
            zobrist(stones),
            moveNumber,
            new int[stones.length],
            0,
            0,
            50,
            0));
  }

  private static BoardHistoryList moveHistory(
      Stone[] stones, int[] lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    return new BoardHistoryList(
        BoardData.move(
            stones.clone(),
            lastMove,
            lastMoveColor,
            blackToPlay,
            zobrist(stones),
            moveNumber,
            new int[stones.length],
            0,
            0,
            50,
            0));
  }

  private static Stone[] stones(Stone... placements) {
    Stone[] stones = new Stone[placements.length];
    System.arraycopy(placements, 0, stones, 0, placements.length);
    return stones;
  }

  private static Zobrist zobrist(Stone[] stones) {
    Zobrist zobrist = new Zobrist();
    int boardArea = Board.boardWidth * Board.boardHeight;
    for (int index = 0; index < boardArea; index++) {
      Stone stone = stones[index];
      if (!stone.isEmpty()) {
        int x = index / Board.boardHeight;
        int y = index % Board.boardHeight;
        zobrist.toggleStone(x, y, stone);
      }
    }
    return zobrist;
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void initializeReadBoardTrackers(ReadBoard readBoard) throws Exception {
    setField(readBoard, "conflictTracker", new SyncConflictTracker());
    setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
    setField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
  }

  private static final class TrackingBufferedOutputStream extends BufferedOutputStream {
    private final ByteArrayOutputStream capture;

    private TrackingBufferedOutputStream() {
      this(new ByteArrayOutputStream());
    }

    private TrackingBufferedOutputStream(ByteArrayOutputStream capture) {
      super(capture);
      this.capture = capture;
    }

    private String writtenText() {
      return capture.toString(StandardCharsets.UTF_8);
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
