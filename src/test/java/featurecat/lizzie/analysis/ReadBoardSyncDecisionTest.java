package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.BoardNodeKind;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadBoardSyncDecisionTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  private int previousBoardWidth;
  private int previousBoardHeight;

  @BeforeEach
  void setUpFixtureBoardSize() {
    previousBoardWidth = Board.boardWidth;
    previousBoardHeight = Board.boardHeight;
    resetFixtureBoardState();
  }

  @AfterEach
  void tearDownFixtureBoardSize() {
    Board.boardWidth = previousBoardWidth;
    Board.boardHeight = previousBoardHeight;
    Zobrist.init();
  }

  @Test
  void rebuildsImmediatelyOnFirstSyncWhenCurrentPositionHasNoHistory() throws Exception {
    Stone[] initial = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
    Stone[] target = stones(placement(0, 0, Stone.BLACK));
    int[] lastMove = new int[] {0, 0};

    try (SyncHarness harness = SyncHarness.create(true, rootHistory(initial, true))) {
      harness.sync(snapshot(target, Optional.of(lastMove), Stone.BLACK));

      assertTrue(
          harness.frame.refreshCount > 0,
          "first sync rollback without history should refresh immediately.");
      assertStaticSnapshotRoot(harness.board, target, lastMove, Stone.BLACK, 1);
    }
  }

  @Test
  void clearsImmediatelyOnFirstSyncWhenSnapshotJumpsBackToEmptyBoard() throws Exception {
    Stone[] initial = stones(placement(0, 0, Stone.BLACK));

    try (SyncHarness harness = SyncHarness.create(true, rootHistory(initial, false))) {
      harness.sync(snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));

      BoardHistoryNode mainEnd = harness.board.getHistory().getMainEnd();
      BoardData data = mainEnd.getData();

      assertTrue(
          harness.frame.refreshCount > 0,
          "first sync back to an empty board should refresh immediately.");
      assertArrayEquals(emptyStones(), data.stones, "sync should leave an empty board.");
      assertSame(
          mainEnd,
          harness.board.getHistory().getCurrentHistoryNode(),
          "empty-board rebuild should focus the board on the rebuilt root snapshot.");
      assertFalse(
          mainEnd.previous().isPresent(),
          "empty-board rebuild should not synthesize intermediate history.");
      assertFalse(
          data.lastMove.isPresent(), "empty snapshot should not fabricate lastMove metadata.");
      assertTrue(data.blackToPlay, "empty-board rebuild should restore black to play.");
      assertEquals(
          Stone.EMPTY, data.lastMoveColor, "empty snapshot should keep lastMoveColor empty.");
    }
  }

  @Test
  void infersBlackToPlayForPureRollbackWithoutMarker() throws Exception {
    BoardHistoryList history = rootHistory(stones(placement(0, 0, Stone.BLACK)), false);
    SyncSnapshotClassifier classifier = new SyncSnapshotClassifier(BOARD_SIZE, BOARD_SIZE);
    SyncSnapshotClassifier.SnapshotDelta snapshotDelta =
        classifier.summarizeDelta(
            history.getCurrentHistoryNode().getData().stones,
            snapshot(emptyStones(), Optional.empty(), Stone.EMPTY));
    ReadBoard readBoard = allocate(ReadBoard.class);

    assertTrue(
        invokeInferBlackToPlayWithoutMarker(
            readBoard, history.getCurrentHistoryNode(), emptyStones(), snapshotDelta),
        "rolling back from move one to an unmarked empty board should restore black to play.");
  }

  @Test
  void jumpingForwardSeveralMovesWithoutLocalHistoryRebuildsAsStaticSnapshot() throws Exception {
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(0, 1, Stone.BLACK),
            placement(1, 1, Stone.WHITE),
            placement(2, 2, Stone.BLACK));
    int[] lastMove = new int[] {2, 2};

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      harness.sync(snapshot(target, Optional.of(lastMove), Stone.BLACK));

      assertEquals(
          0,
          harness.board.placeForSyncCount,
          "jumping forward without local history should rebuild statically instead of replaying stones.");
      assertStaticSnapshotRoot(harness.board, target, lastMove, Stone.BLACK, 5);
    }
  }

  @Test
  void foxLiveRollbackReusesExactMainTrunkAncestorEvenWithRepeatedStones() throws Exception {
    Stone[] repeatedStones = stones(placement(1, 1, Stone.BLACK));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      HistoryPath path = buildHistory(harness.board, placement(1, 1, Stone.BLACK));
      BoardHistoryNode moveOne = path.nodes.get(0);

      BoardData passData =
          BoardData.pass(
              repeatedStones.clone(),
              Stone.WHITE,
              true,
              zobrist(repeatedStones),
              2,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0);
      harness.board.getHistory().add(passData);
      BoardHistoryNode passNode = harness.board.getHistory().getCurrentHistoryNode();

      harness.board.getHistory().place(0, 0, Stone.BLACK, false);
      BoardHistoryNode mainEnd = harness.board.getHistory().getCurrentHistoryNode();
      setField(harness.readBoard, "lastResolvedSnapshotNode", mainEnd);

      armFoxMoveNumber(harness.readBoard, 1);
      harness.sync(snapshot(repeatedStones, Optional.empty(), Stone.EMPTY));
      assertSame(
          mainEnd,
          harness.board.getHistory().getMainEnd(),
          "rollback to an exact ancestor should keep later local history available.");
      assertSame(
          moveOne,
          harness.board.getHistory().getCurrentHistoryNode(),
          "fox live rollback should move the current view to the matched main-trunk ancestor.");

      harness.leelaz.clearCount = 0;
      armFoxMoveNumber(harness.readBoard, 1);
      harness.sync(snapshot(repeatedStones, Optional.empty(), Stone.EMPTY));

      assertSame(
          mainEnd,
          harness.board.getHistory().getMainEnd(),
          "steady-state rollback frames should keep the preserved future history.");
      assertSame(
          moveOne,
          harness.board.getHistory().getCurrentHistoryNode(),
          "steady-state rollback frames should stay on the matched ancestor.");
      assertEquals(0, harness.leelaz.clearCount, "exact ancestor rollback should not rebuild.");
      assertTrue(
          passNode.previous().isPresent(), "test fixture should keep the pass node in history.");
    }
  }

  @Test
  void foxMoveNumberOddRebuildKeepsMarkerlessSnapshotAndAddsSingleBookkeepingPassWhenParityNeedsIt()
      throws Exception {
    Stone[] target = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      armFoxMoveNumber(harness.readBoard, 57);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      assertEquals(
          0,
          harness.board.placeForSyncCount,
          "fox move-number rebuild should replace the position statically instead of replaying history.");
      assertStaticSnapshotRootWithoutMarker(harness.board, target, 57, false);
      assertEquals(
          1,
          harness.leelaz.sentCommands.size(),
          "markerless snapshot rebuild should use one loadsgf.");
      assertTrue(
          harness.leelaz.sentCommands.get(0).startsWith("loadsgf "),
          "markerless snapshot rebuild should restore the static board exactly.");
      assertTrue(
          harness.leelaz.playedMoves.isEmpty(),
          "exact snapshot restore should avoid replaying static stones as play commands.");
      assertArrayEquals(target, harness.leelaz.copyStones());
      assertFalse(
          harness.leelaz.isBlackToPlay(),
          "exact snapshot restore should preserve the rebuilt side to play directly.");
    }
  }

  @Test
  void foxMoveNumberEvenRebuildKeepsMarkerlessSnapshotWithoutFabricatingPassHistory()
      throws Exception {
    Stone[] target = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      armFoxMoveNumber(harness.readBoard, 58);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      assertStaticSnapshotRootWithoutMarker(harness.board, target, 58, true);
      assertEquals(
          1,
          harness.leelaz.sentCommands.size(),
          "markerless snapshot rebuild should use one loadsgf.");
      assertTrue(
          harness.leelaz.sentCommands.get(0).startsWith("loadsgf "),
          "markerless snapshot rebuild should restore the static board exactly.");
      assertTrue(
          harness.leelaz.playedMoves.isEmpty(),
          "exact snapshot restore should avoid replaying ordinary static stones as play commands.");
      assertArrayEquals(target, harness.leelaz.copyStones());
      assertTrue(harness.leelaz.isBlackToPlay());
    }
  }

  @Test
  void foxMoveNumberJumpFromMidgameStillUsesStaticRebuildInsteadOfReplayingHistory()
      throws Exception {
    Stone[] initial = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(0, 1, Stone.BLACK),
            placement(1, 1, Stone.WHITE),
            placement(2, 2, Stone.BLACK));

    try (SyncHarness harness = SyncHarness.create(false, rootHistory(initial, true))) {
      armFoxMoveNumber(harness.readBoard, 58);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      assertEquals(
          0,
          harness.board.placeForSyncCount,
          "jumping to a remote fox move number should rebuild statically instead of stepping through missing history.");
      assertStaticSnapshotRootWithoutMarker(harness.board, target, 58, true);
      assertEquals(
          1, harness.leelaz.sentCommands.size(), "midgame jump rebuild should use one loadsgf.");
      assertTrue(
          harness.leelaz.sentCommands.get(0).startsWith("loadsgf "),
          "midgame jump rebuild should land the target snapshot exactly.");
      assertTrue(
          harness.leelaz.playedMoves.isEmpty(),
          "exact snapshot restore should avoid replaying the rebuilt static stones.");
      assertArrayEquals(target, harness.leelaz.copyStones());
      assertTrue(harness.leelaz.isBlackToPlay());
    }
  }

  @Test
  void foxLiveAncestorMatchIgnoresMarkerMismatchInsteadOfRebuilding() throws Exception {
    Stone[] ancestorStones = stones(placement(1, 1, Stone.BLACK));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(1, 1, Stone.BLACK),
              placement(0, 0, Stone.WHITE),
              placement(2, 2, Stone.BLACK));
      BoardHistoryNode ancestorNode = path.nodes.get(0);
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);

      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("roomToken 43581号");
      harness.readBoard.parseLine("liveTitleMove 1");
      harness.readBoard.parseLine("foxMoveNumber 1");
      harness.sync(snapshot(ancestorStones, Optional.of(new int[] {1, 1}), Stone.WHITE));

      assertSame(
          mainEnd,
          harness.board.getHistory().getMainEnd(),
          "marker mismatch should not force rebuild when fox live identity already matches an ancestor.");
      assertSame(
          ancestorNode,
          harness.board.getHistory().getCurrentHistoryNode(),
          "fox live ancestor hit should navigate back to the matched ancestor.");
      assertEquals(0, harness.leelaz.clearCount, "ancestor hit should not clear the engine state.");
    }
  }

  @Test
  void foxLiveForwardToExistingNextNodeNavigatesInsteadOfRestoringOldView() throws Exception {
    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode moveOne = path.nodes.get(0);
      BoardHistoryNode moveTwo = path.nodes.get(1);
      BoardHistoryNode mainEnd = path.nodes.get(2);
      harness.board.getHistory().setHead(moveOne);

      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("roomToken 43581号");
      harness.readBoard.parseLine("liveTitleMove 2");
      harness.readBoard.parseLine("foxMoveNumber 2");
      harness.sync(
          snapshot(
              moveTwo.getData().stones,
              moveTwo.getData().lastMove,
              moveTwo.getData().lastMoveColor));

      assertSame(mainEnd, harness.board.getHistory().getMainEnd());
      assertSame(
          moveTwo,
          harness.board.getHistory().getCurrentHistoryNode(),
          "forwarding to an already-existing next node should leave the view on that node.");
      assertEquals(
          0, harness.leelaz.clearCount, "existing-node forward navigation should not rebuild.");
    }
  }

  @Test
  void foxLiveForwardToExistingMainEndNavigatesInsteadOfStallingOnAncestor() throws Exception {
    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode moveOne = path.nodes.get(0);
      BoardHistoryNode mainEnd = path.nodes.get(2);
      harness.board.getHistory().setHead(moveOne);

      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("roomToken 43581号");
      harness.readBoard.parseLine("liveTitleMove 3");
      harness.readBoard.parseLine("foxMoveNumber 3");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      assertSame(mainEnd, harness.board.getHistory().getMainEnd());
      assertSame(
          mainEnd,
          harness.board.getHistory().getCurrentHistoryNode(),
          "forwarding to the already-existing main end should not be treated as a stale steady-state frame.");
      assertEquals(
          0, harness.leelaz.clearCount, "existing main-end navigation should not rebuild.");
    }
  }

  @Test
  void foxLiveRollbackWithinMainlineWindowDoesNotRebuild() throws Exception {
    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK),
              placement(1, 1, Stone.WHITE));
      BoardHistoryNode moveTwo = path.nodes.get(1);
      BoardHistoryNode mainEnd = path.nodes.get(3);
      harness.board.getHistory().setHead(mainEnd);

      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("roomToken 43581号");
      harness.readBoard.parseLine("liveTitleMove 2");
      harness.readBoard.parseLine("foxMoveNumber 2");
      harness.sync(
          snapshot(
              moveTwo.getData().stones,
              moveTwo.getData().lastMove,
              moveTwo.getData().lastMoveColor));

      assertSame(mainEnd, harness.board.getHistory().getMainEnd());
      assertSame(
          moveTwo,
          harness.board.getHistory().getCurrentHistoryNode(),
          "rolling back to an already-existing ancestor inside the retained window should navigate directly.");
      assertEquals(0, harness.leelaz.clearCount, "window-contained rollback should not rebuild.");
    }
  }

  @Test
  void recordViewForwardToExistingNodeNavigatesInsideMainlineWindow() throws Exception {
    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode moveOne = path.nodes.get(0);
      BoardHistoryNode moveTwo = path.nodes.get(1);
      BoardHistoryNode mainEnd = path.nodes.get(2);
      harness.board.getHistory().setHead(moveOne);

      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("recordCurrentMove 2");
      harness.readBoard.parseLine("recordTotalMove 333");
      harness.readBoard.parseLine("recordAtEnd 0");
      harness.readBoard.parseLine("recordTitleFingerprint record-fingerprint");
      harness.readBoard.parseLine("foxMoveNumber 2");
      harness.sync(
          snapshot(
              moveTwo.getData().stones,
              moveTwo.getData().lastMove,
              moveTwo.getData().lastMoveColor));

      assertSame(mainEnd, harness.board.getHistory().getMainEnd());
      assertSame(
          moveTwo,
          harness.board.getHistory().getCurrentHistoryNode(),
          "record-view forward navigation should land on the existing target node instead of restoring the old view.");
      assertEquals(0, harness.leelaz.clearCount, "record-view window hit should not rebuild.");
    }
  }

  @Test
  void forceRebuildWithoutStartedEngineStillRebuildsBoardLocally() throws Exception {
    Stone[] target = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      harness.leelaz.started = false;
      armFoxMoveNumber(harness.readBoard, 57);

      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      assertStaticSnapshotRootWithoutMarker(harness.board, target, 57, false);
      assertTrue(
          harness.leelaz.sentCommands.isEmpty(),
          "board-only rebuild should not send loadsgf when the engine is unavailable.");
    }
  }

  @Test
  void foxConflictAcrossMarkerPresenceJitterOnlyHoldsOnceBeforeRebuild() throws Exception {
    ReadBoard readBoard = allocate(ReadBoard.class);
    setField(readBoard, "conflictTracker", new SyncConflictTracker());

    Stone[] firstMoveStones = stones(placement(0, 0, Stone.BLACK));
    Stone[] secondMoveStones = stones(placement(0, 0, Stone.BLACK), placement(0, 1, Stone.WHITE));
    BoardHistoryNode root =
        new BoardHistoryNode(
            BoardData.snapshot(
                emptyStones(),
                Optional.empty(),
                Stone.EMPTY,
                true,
                zobrist(emptyStones()),
                0,
                new int[BOARD_AREA],
                0,
                0,
                50,
                0));
    BoardHistoryNode firstMove =
        root.add(
            new BoardHistoryNode(
                BoardData.move(
                    firstMoveStones,
                    new int[] {0, 0},
                    Stone.BLACK,
                    false,
                    zobrist(firstMoveStones),
                    1,
                    new int[BOARD_AREA],
                    0,
                    0,
                    50,
                    0)));
    BoardHistoryNode syncStartNode =
        firstMove.add(
            new BoardHistoryNode(
                BoardData.move(
                    secondMoveStones,
                    new int[] {0, 1},
                    Stone.WHITE,
                    true,
                    zobrist(secondMoveStones),
                    2,
                    new int[BOARD_AREA],
                    0,
                    0,
                    50,
                    0)));
    SyncRemoteContext remoteContext =
        SyncRemoteContext.forFoxLive(OptionalInt.of(2), "43581号", OptionalInt.of(2), false);
    Stone[] target = stones(placement(2, 2, Stone.BLACK), placement(1, 1, Stone.WHITE));

    Method shouldHold =
        ReadBoard.class.getDeclaredMethod(
            "shouldHoldConflictingSnapshot",
            BoardHistoryNode.class,
            int[].class,
            SyncRemoteContext.class);
    shouldHold.setAccessible(true);

    boolean firstHold =
        (boolean)
            shouldHold.invoke(
                readBoard,
                syncStartNode,
                snapshot(target, Optional.of(new int[] {1, 1}), Stone.WHITE),
                remoteContext);
    boolean secondHold =
        (boolean)
            shouldHold.invoke(
                readBoard,
                syncStartNode,
                snapshot(target, Optional.empty(), Stone.EMPTY),
                remoteContext);

    assertTrue(firstHold, "the first normalized fox conflict should still HOLD once.");
    assertFalse(secondHold, "marker presence jitter should not create a second HOLD bucket.");
  }

  @Test
  void foxRollbackPastRetainedHistoryWindowRebuildsImmediately() throws Exception {
    Stone[] rootStones = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
    Stone[] target = stones(placement(0, 0, Stone.BLACK));

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(
                rootStones,
                Optional.of(new int[] {1, 0}),
                Stone.WHITE,
                true,
                100,
                BoardNodeKind.SNAPSHOT))) {
      buildHistory(
          harness.board,
          placement(0, 1, Stone.BLACK),
          placement(1, 1, Stone.WHITE),
          placement(2, 2, Stone.BLACK));

      armFoxMoveNumber(harness.readBoard, 99);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      assertEquals(
          0,
          harness.board.placeForSyncCount,
          "rollback past the retained history window should rebuild statically.");
      assertStaticSnapshotRootWithoutMarker(harness.board, target, 99, false);
    }
  }

  @Test
  void foxJumpBeyondRetainedHistoryWindowRebuildsImmediately() throws Exception {
    Stone[] rootStones = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(0, 1, Stone.BLACK),
            placement(1, 1, Stone.WHITE),
            placement(2, 2, Stone.BLACK),
            placement(2, 0, Stone.WHITE));

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(
                rootStones,
                Optional.of(new int[] {1, 0}),
                Stone.WHITE,
                true,
                100,
                BoardNodeKind.SNAPSHOT))) {
      buildHistory(
          harness.board,
          placement(0, 1, Stone.BLACK),
          placement(1, 1, Stone.WHITE),
          placement(2, 2, Stone.BLACK));

      armFoxMoveNumber(harness.readBoard, 106);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      assertEquals(
          0,
          harness.board.placeForSyncCount,
          "jumping beyond the retained history window should rebuild statically.");
      assertStaticSnapshotRootWithoutMarker(harness.board, target, 106, true);
    }
  }

  @Test
  void markerlessSetupAdditionKeepsStaticSnapshotMetadata() throws Exception {
    Stone[] initial = stones(placement(0, 0, Stone.BLACK));
    Stone[] target = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(
                initial, Optional.empty(), Stone.EMPTY, false, 1, BoardNodeKind.SNAPSHOT))) {
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      BoardData data = harness.board.getHistory().getMainEnd().getData();
      assertTrue(data.isSnapshotNode(), "markerless odd-diff rebuild should stay a SNAPSHOT.");
      assertEquals(
          1,
          data.moveNumber,
          "markerless setup additions should keep the snapshot moveNumber static.");
      assertFalse(
          data.blackToPlay, "markerless setup additions should keep the side to move static.");
    }
  }

  @Test
  void markerlessSetupRemovalKeepsStaticSnapshotMetadata() throws Exception {
    Stone[] initial = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
    Stone[] target = stones(placement(0, 0, Stone.BLACK));

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(initial, Optional.empty(), Stone.EMPTY, true, 8, BoardNodeKind.SNAPSHOT))) {
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      BoardData data = harness.board.getHistory().getMainEnd().getData();
      assertTrue(data.isSnapshotNode(), "markerless setup removals should stay a SNAPSHOT.");
      assertEquals(
          8,
          data.moveNumber,
          "markerless setup removals should keep the snapshot moveNumber static.");
      assertTrue(
          data.blackToPlay, "markerless setup removals should keep the side to move static.");
    }
  }

  @Test
  void sameBoardWithDifferentFoxMoveNumberPreservesExplicitMoveHistory() throws Exception {
    Stone[] target = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode originalMainEnd = harness.board.getHistory().getMainEnd();
      harness.board.resetCounters();
      harness.frame.refreshCount = 0;
      harness.leelaz.clearCount = 0;
      harness.leelaz.playedMoves = new ArrayList<>();

      armFoxMoveNumber(harness.readBoard, 58);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      BoardHistoryNode currentMainEnd = harness.board.getHistory().getMainEnd();
      assertSame(
          originalMainEnd,
          currentMainEnd,
          "fox move-number metadata must not downgrade a proven MOVE node when the board is unchanged.");
      assertEquals(
          0,
          harness.board.placeForSyncCount,
          "same-board fox metadata should keep the existing MOVE history untouched.");
      assertTrue(
          currentMainEnd.getData().isMoveNode(),
          "same-board fox metadata must preserve the explicit MOVE node kind.");
      assertEquals(
          2,
          currentMainEnd.getData().moveNumber,
          "same-board fox metadata must preserve the real move number from history.");
      assertEquals(
          0, harness.leelaz.clearCount, "preserving a real MOVE should avoid metadata rebuilds.");
      assertEquals(
          0, harness.frame.refreshCount, "preserving a real MOVE should keep the UI steady.");
    }
  }

  @Test
  void sameBoardFoxMetadataRebuildKeepsSnapshotCaptures() throws Exception {
    Stone[] target = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
    int blackCaptures = 3;
    int whiteCaptures = 5;

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistoryWithCaptures(
                target,
                Optional.empty(),
                Stone.EMPTY,
                true,
                2,
                BoardNodeKind.SNAPSHOT,
                blackCaptures,
                whiteCaptures))) {
      BoardHistoryNode originalMainEnd = harness.board.getHistory().getMainEnd();
      harness.board.resetCounters();
      harness.frame.refreshCount = 0;
      harness.leelaz.clearCount = 0;
      harness.leelaz.playedMoves = new ArrayList<>();

      armFoxMoveNumber(harness.readBoard, 58);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      BoardHistoryNode rebuiltMainEnd = harness.board.getHistory().getMainEnd();
      assertNotSame(
          originalMainEnd,
          rebuiltMainEnd,
          "snapshot metadata should still rebuild when only fox move number changes.");
      assertTrue(
          rebuiltMainEnd.getData().isSnapshotNode(),
          "same-board fox metadata rebuild should keep the node as SNAPSHOT.");
      assertEquals(
          blackCaptures,
          rebuiltMainEnd.getData().blackCaptures,
          "same-board fox metadata rebuild should preserve black captures.");
      assertEquals(
          whiteCaptures,
          rebuiltMainEnd.getData().whiteCaptures,
          "same-board fox metadata rebuild should preserve white captures.");
      assertEquals(
          58,
          rebuiltMainEnd.getData().moveNumber,
          "same-board fox metadata rebuild should still apply the rebuilt fox move number.");
      assertTrue(
          rebuiltMainEnd.getData().blackToPlay,
          "same-board fox metadata rebuild should keep turn parity aligned with fox move number.");
      assertTrue(
          harness.frame.refreshCount > 0, "metadata rebuild should still refresh the board view.");
    }
  }

  @Test
  void sameBoardFoxMetadataRebuildKeepsSnapshotSetupMetadata() throws Exception {
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(2, 2, Stone.BLACK));

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(target, Optional.empty(), Stone.EMPTY, true, 2, BoardNodeKind.SNAPSHOT))) {
      BoardHistoryNode originalMainEnd = harness.board.getHistory().getMainEnd();
      seedSetupMetadata(originalMainEnd);

      armFoxMoveNumber(harness.readBoard, 58);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      BoardHistoryNode rebuiltMainEnd = harness.board.getHistory().getMainEnd();
      assertNotSame(
          originalMainEnd,
          rebuiltMainEnd,
          "same-board fox metadata changes should still rebuild the snapshot node.");
      assertSetupMetadata(
          rebuiltMainEnd,
          "metadata-only rebuild should preserve setup metadata on the new snapshot.");
      assertEquals(
          58,
          rebuiltMainEnd.getData().moveNumber,
          "metadata-only rebuild should still apply the rebuilt fox move number.");
      assertTrue(
          rebuiltMainEnd.getData().blackToPlay,
          "metadata-only rebuild should keep the side to move aligned with fox parity.");
    }
  }

  @Test
  void sameBoardFoxMetadataRebuildHonorsExplicitPlAndMnOnSetupSnapshot() throws Exception {
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(2, 2, Stone.BLACK));

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(target, Optional.empty(), Stone.EMPTY, true, 2, BoardNodeKind.SNAPSHOT))) {
      BoardHistoryNode originalMainEnd = harness.board.getHistory().getMainEnd();
      seedSetupMetadata(originalMainEnd);
      originalMainEnd.getData().addProperty("PL", "W");
      originalMainEnd.getData().addProperty("MN", "93");
      originalMainEnd.getData().blackToPlay = false;

      armFoxMoveNumber(harness.readBoard, 58);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      BoardHistoryNode rebuiltMainEnd = harness.board.getHistory().getMainEnd();
      assertNotSame(
          originalMainEnd,
          rebuiltMainEnd,
          "same-board fox metadata changes should still rebuild the snapshot node.");
      assertEquals("W", rebuiltMainEnd.getData().getProperty("PL"));
      assertFalse(
          rebuiltMainEnd.getData().blackToPlay,
          "explicit PL should continue to control side-to-play after rebuild.");
      assertEquals("93", rebuiltMainEnd.getData().getProperty("MN"));
      assertEquals(
          93,
          rebuiltMainEnd.getData().moveMNNumber,
          "explicit MN should continue to control snapshot move-number metadata after rebuild.");
      assertEquals(
          58,
          rebuiltMainEnd.getData().moveNumber,
          "fox metadata can still update the snapshot move number independently of explicit MN.");

      harness.board.resetCounters();
      harness.frame.refreshCount = 0;
      harness.leelaz.clearCount = 0;
      armFoxMoveNumber(harness.readBoard, 58);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      assertSame(
          rebuiltMainEnd,
          harness.board.getHistory().getMainEnd(),
          "steady-state fox frames should keep the explicit-PL snapshot root.");
      assertEquals(
          0,
          harness.leelaz.clearCount,
          "explicit PL should suppress repeated same-board fox metadata rebuilds.");
      assertEquals(
          0,
          harness.frame.refreshCount,
          "steady-state explicit-PL frames should stay on the NO_CHANGE path.");
    }
  }

  @Test
  void sameBoardRecordViewConflictDoesNotRebuildFromConflictingFoxMoveMetadata() throws Exception {
    Stone[] target = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(target, Optional.empty(), Stone.EMPTY, true, 2, BoardNodeKind.SNAPSHOT))) {
      BoardHistoryNode originalMainEnd = harness.board.getHistory().getMainEnd();
      harness.board.resetCounters();
      harness.frame.refreshCount = 0;
      harness.leelaz.clearCount = 0;

      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("recordCurrentMove 57");
      harness.readBoard.parseLine("recordTotalMove 333");
      harness.readBoard.parseLine("recordAtEnd 0");
      harness.readBoard.parseLine("recordTitleFingerprint record-fingerprint");
      harness.readBoard.parseLine("foxMoveNumber 58");
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      assertSame(
          originalMainEnd,
          harness.board.getHistory().getMainEnd(),
          "conflicting record-view title metadata should suppress fox metadata rebuilds.");
      assertEquals(
          0,
          harness.leelaz.clearCount,
          "conflicting record-view title metadata should stay off the rebuild path.");
      assertEquals(
          0,
          harness.frame.refreshCount,
          "conflicting record-view title metadata should keep the UI on the NO_CHANGE path.");
    }
  }

  @Test
  void sameBoardWithSameFoxMoveNumberDoesNotRebuildTwice() throws Exception {
    Stone[] target = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(target, Optional.empty(), Stone.EMPTY, true, 58, BoardNodeKind.SNAPSHOT))) {
      BoardHistoryNode originalMainEnd = harness.board.getHistory().getMainEnd();
      harness.board.resetCounters();
      harness.frame.refreshCount = 0;
      harness.leelaz.clearCount = 0;
      harness.leelaz.playedMoves = new ArrayList<>();

      armFoxMoveNumber(harness.readBoard, 58);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      assertSame(
          originalMainEnd,
          harness.board.getHistory().getMainEnd(),
          "identical fox move metadata should keep the existing rebuilt root.");
      assertEquals(
          0,
          harness.board.placeForSyncCount,
          "identical fox move metadata should not trigger incremental replay.");
      assertEquals(
          0,
          harness.leelaz.clearCount,
          "identical fox move metadata should not resync the engine.");
      assertEquals(
          0, harness.frame.refreshCount, "identical fox move metadata should not refresh again.");
      assertStaticSnapshotRootWithoutMarker(harness.board, target, 58, true);
    }
  }

  @Test
  void foxMarkerlessOpeningMoveBecomesRealHistoryInsteadOfSnapshotRebuild() throws Exception {
    Stone[] target = stones(placement(0, 0, Stone.BLACK));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      armFoxMoveNumber(harness.readBoard, 1);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      BoardHistoryNode mainEnd = harness.board.getHistory().getMainEnd();
      BoardData data = mainEnd.getData();

      assertEquals(
          1, harness.board.placeForSyncCount, "opening move should be appended incrementally.");
      assertSame(mainEnd, harness.board.getHistory().getCurrentHistoryNode());
      assertTrue(data.isMoveNode(), "first proven Fox move should stay a MOVE node.");
      assertTrue(mainEnd.previous().isPresent(), "opening move should keep explicit history.");
      assertEquals(1, data.moveNumber, "opening move should keep its real move number.");
      assertFalse(
          harness.board.hasStartStone, "incremental opening sync must not rewrite root setup.");
    }
  }

  @Test
  void foxMarkerlessFirstMoveAfterHandicapSetupBecomesRealHistory() throws Exception {
    Stone[] handicapRoot = stones(placement(0, 0, Stone.BLACK));
    Stone[] target = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(
                handicapRoot, Optional.empty(), Stone.EMPTY, false, 0, BoardNodeKind.SNAPSHOT))) {
      armFoxMoveNumber(harness.readBoard, 1);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      BoardHistoryNode root = harness.board.getHistory().root();
      BoardHistoryNode mainEnd = harness.board.getHistory().getMainEnd();

      assertEquals(
          1,
          harness.board.placeForSyncCount,
          "first move after setup should append incrementally.");
      assertTrue(
          mainEnd.getData().isMoveNode(),
          "the move after handicap setup should become a MOVE node.");
      assertTrue(
          root.getData().isSnapshotNode(), "the original setup root should remain a SNAPSHOT.");
      assertEquals(
          1, mainEnd.getData().moveNumber, "the first post-setup move should stay move 1.");
    }
  }

  @Test
  void foxMoveNumberAheadOfVisibleSyncRebuildsMetadataAfterIncrementalMove() throws Exception {
    Stone[] target = stones(placement(0, 0, Stone.BLACK));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      armFoxMoveNumber(harness.readBoard, 3);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      assertStaticSnapshotRootWithoutMarker(harness.board, target, 3, false);
    }
  }

  @Test
  void steadyStateFoxOpeningFrameKeepsRealMoveNodeAfterMarkerlessIncrementalSync()
      throws Exception {
    Stone[] target = stones(placement(0, 0, Stone.BLACK));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      armFoxMoveNumber(harness.readBoard, 1);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      BoardHistoryNode trackedMainEnd = harness.board.getHistory().getMainEnd();
      assertTrue(
          trackedMainEnd.getData().isMoveNode(),
          "first markerless Fox move should no longer rebuild.");

      harness.board.resetCounters();
      harness.frame.refreshCount = 0;
      harness.leelaz.clearCount = 0;
      harness.leelaz.playedMoves = new ArrayList<>();

      armFoxMoveNumber(harness.readBoard, 1);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      assertSame(
          trackedMainEnd,
          harness.board.getHistory().getMainEnd(),
          "steady-state Fox frames should keep the existing MOVE node.");
      assertTrue(
          harness.board.getHistory().getMainEnd().getData().isMoveNode(),
          "steady-state Fox frames should preserve MOVE semantics.");
      assertEquals(
          0, harness.leelaz.clearCount, "steady-state fox frames should not resync the engine.");
      assertEquals(
          0, harness.frame.refreshCount, "steady-state fox frames should not refresh again.");
    }
  }

  @Test
  void firstSyncMarkedOpeningDoesNotFlattenRealMoveIntoRootSetup() throws Exception {
    Stone[] target = stones(placement(0, 0, Stone.BLACK));

    try (SyncHarness harness = SyncHarness.create(true, emptyHistory())) {
      harness.sync(snapshot(target, Optional.of(new int[] {0, 0}), Stone.BLACK));

      BoardHistoryNode mainEnd = harness.board.getHistory().getMainEnd();
      assertTrue(
          mainEnd.getData().isMoveNode(), "first sync incremental move should remain a MOVE node.");
      assertTrue(mainEnd.previous().isPresent(), "first sync should keep explicit history.");
      assertFalse(
          harness.board.hasStartStone,
          "first sync should not convert the prefix into start stones.");
    }
  }

  @Test
  void identicalMarkerSnapshotOnCurrentNodeIsATrueNoOp() throws Exception {
    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode currentNode = path.nodes.get(path.nodes.size() - 1);

      harness.board.resetCounters();
      harness.frame.refreshCount = 0;
      harness.frame.renderVarTreeCount = 0;
      harness.sync(
          snapshot(
              currentNode.getData().stones,
              currentNode.getData().lastMove,
              currentNode.getData().lastMoveColor));

      assertSame(
          currentNode,
          harness.board.getHistory().getCurrentHistoryNode(),
          "identical marker snapshots should stay on the current tracked node.");
      assertEquals(
          0,
          harness.frame.renderVarTreeCount,
          "identical marker snapshots should not rerender the variation tree.");
      assertEquals(
          0, harness.frame.refreshCount, "identical marker snapshots should not refresh the UI.");
    }
  }

  @Test
  void repeatedMarkerSnapshotOnRebuiltRootIsATrueNoOp() throws Exception {
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(0, 1, Stone.BLACK));
    int[] lastMove = new int[] {0, 1};

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      harness.sync(snapshot(target, Optional.of(lastMove), Stone.BLACK));

      BoardHistoryNode rebuiltRoot = harness.board.getHistory().getMainEnd();
      harness.board.resetCounters();
      harness.frame.refreshCount = 0;
      harness.frame.renderVarTreeCount = 0;
      harness.leelaz.clearCount = 0;
      harness.leelaz.playedMoves = new ArrayList<>();

      harness.sync(snapshot(target, Optional.of(lastMove), Stone.BLACK));

      assertSame(
          rebuiltRoot,
          harness.board.getHistory().getMainEnd(),
          "repeating a marked snapshot on a rebuilt root should stay on the current SNAPSHOT node.");
      assertEquals(
          0,
          harness.board.placeForSyncCount,
          "repeating a marked snapshot on a rebuilt root should not replay stones.");
      assertEquals(
          0,
          harness.leelaz.clearCount,
          "repeating a marked snapshot on a rebuilt root should not rebuild the engine board.");
      assertEquals(
          0,
          harness.frame.renderVarTreeCount,
          "repeating a marked snapshot on a rebuilt root should not rerender the variation tree.");
      assertEquals(
          0,
          harness.frame.refreshCount,
          "repeating a marked snapshot on a rebuilt root should not refresh the UI.");
    }
  }

  @Test
  void markerlessCaptureFrameRebuildsSnapshotRootInsteadOfRecoveringMoveHistory() throws Exception {
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

    try (SyncHarness harness = SyncHarness.create(false, rootHistory(beforeCapture, true))) {
      armFoxMoveNumber(harness.readBoard, 1);
      harness.sync(snapshot(afterCapture, Optional.empty(), Stone.EMPTY));

      BoardHistoryNode rebuiltMainEnd = harness.board.getHistory().getMainEnd();
      assertArrayEquals(
          afterCapture,
          rebuiltMainEnd.getData().stones,
          "markerless capture sync should still align the board position.");
      assertTrue(
          rebuiltMainEnd.getData().isSnapshotNode(),
          "markerless capture sync should rebuild a SNAPSHOT node.");
      assertFalse(
          rebuiltMainEnd.previous().isPresent(),
          "markerless capture sync should not recover a synthetic move history.");
      assertEquals(
          0,
          harness.board.placeForSyncCount,
          "markerless capture sync should not create MOVE history through single-move recovery.");
    }
  }

  @Test
  void steadyStateSnapshotSyncDoesNotOverwriteLastResolvedSnapshotNode() throws Exception {
    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode rememberedNode = path.nodes.get(0);
      BoardHistoryNode currentMainEnd = path.nodes.get(path.nodes.size() - 1);
      setField(harness.readBoard, "lastResolvedSnapshotNode", rememberedNode);

      harness.sync(snapshot(currentMainEnd.getData().stones, Optional.empty(), Stone.EMPTY));

      assertSame(
          rememberedNode,
          getField(harness.readBoard, "lastResolvedSnapshotNode"),
          "steady-state sync must keep the last real resolved snapshot node.");
      assertSame(
          currentMainEnd,
          harness.board.getHistory().getMainEnd(),
          "steady-state sync should leave the main history end untouched.");
    }
  }

  @Test
  void staticRebuildFromMidgameResyncsEngineBoardState() throws Exception {
    Stone[] initial = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(0, 1, Stone.BLACK),
            placement(1, 1, Stone.WHITE),
            placement(2, 2, Stone.BLACK));
    int[] lastMove = new int[] {2, 2};

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      harness.board.resetCounters();
      harness.leelaz.clearCount = 0;
      harness.leelaz.playedMoves = new ArrayList<>();
      SyncSnapshotClassifier classifier = new SyncSnapshotClassifier(BOARD_SIZE, BOARD_SIZE);
      int[] snapshotCodes = snapshot(target, Optional.of(lastMove), Stone.BLACK);

      invokeRebuildFromSnapshot(
          harness.readBoard,
          harness.board.getHistory().getCurrentHistoryNode(),
          snapshotCodes,
          classifier.summarizeDelta(initial, snapshotCodes));

      assertEquals(1, harness.leelaz.clearCount, "static rebuild should clear the engine board.");
      assertEquals(
          1, harness.leelaz.sentCommands.size(), "ordinary static rebuild should use one loadsgf.");
      assertTrue(
          harness.leelaz.sentCommands.get(0).startsWith("loadsgf "),
          "ordinary static rebuild should restore the static board exactly.");
      assertTrue(
          harness.leelaz.playedMoves.isEmpty(),
          "ordinary static rebuild should avoid replaying the snapshot stones as play commands.");
      assertArrayEquals(target, harness.leelaz.copyStones());
      assertFalse(harness.leelaz.isBlackToPlay());
    }
  }

  @Test
  void markerlessFoxRebuildWithDeadSnapshotGroupUsesLoadsgfForExactEngineRestore()
      throws Exception {
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.BLACK),
            placement(0, 1, Stone.BLACK),
            placement(1, 1, Stone.WHITE),
            placement(2, 1, Stone.BLACK),
            placement(0, 2, Stone.BLACK),
            placement(1, 2, Stone.BLACK));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      armFoxMoveNumber(harness.readBoard, 58);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      assertStaticSnapshotRootWithoutMarker(harness.board, target, 58, true);
      assertEquals(1, harness.leelaz.clearCount, "snapshot rebuild should still clear the engine.");
      assertTrue(
          harness.leelaz.playedMoves.isEmpty(),
          "dead snapshot restore should avoid replaying static stones as play commands.");
      assertEquals(
          1,
          harness.leelaz.sentCommands.size(),
          "dead snapshot restore should use one exact loadsgf command.");
      assertTrue(
          harness.leelaz.sentCommands.get(0).startsWith("loadsgf "),
          "dead snapshot restore should rebuild the static board through loadsgf.");
      assertTempFileEventuallyDeleted(
          harness.leelaz.lastLoadedSgf(),
          "exact snapshot restore should clean up the temporary SGF file.");
      assertArrayEquals(
          target,
          harness.leelaz.copyStones(),
          "loadsgf restore should reproduce the exact snapshot stones in the engine.");
      assertTrue(
          harness.leelaz.isBlackToPlay(),
          "loadsgf restore should preserve the rebuilt snapshot side to play.");
    }
  }

  @Test
  void forceRebuildCopiesSetupMetadataFromNearestSnapshotAnchor() throws Exception {
    Stone[] anchorStones = stones(placement(0, 0, Stone.BLACK), placement(2, 2, Stone.BLACK));
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(2, 2, Stone.BLACK));

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(
                anchorStones, Optional.empty(), Stone.EMPTY, false, 7, BoardNodeKind.SNAPSHOT))) {
      BoardHistoryNode snapshotAnchor = harness.board.getHistory().getCurrentHistoryNode();
      seedSetupMetadata(snapshotAnchor);
      harness.board.getHistory().place(1, 1, Stone.WHITE, false);
      BoardHistoryNode syncStartNode = harness.board.getHistory().getCurrentHistoryNode();

      SyncSnapshotClassifier classifier = new SyncSnapshotClassifier(BOARD_SIZE, BOARD_SIZE);
      int[] snapshotCodes = snapshot(target, Optional.of(new int[] {1, 0}), Stone.WHITE);

      invokeRebuildFromSnapshot(
          harness.readBoard,
          syncStartNode,
          snapshotCodes,
          classifier.summarizeDelta(syncStartNode.getData().stones, snapshotCodes));

      BoardHistoryNode rebuiltRoot = harness.board.getHistory().getMainEnd();
      assertFalse(
          rebuiltRoot.previous().isPresent(),
          "force rebuild should replace the conflicting segment with a new snapshot root.");
      assertSetupMetadata(
          rebuiltRoot,
          "force rebuild should copy setup metadata from the nearest snapshot anchor.");
      assertTrue(
          rebuiltRoot.getData().lastMove.isPresent(),
          "force rebuild should still preserve the rebuilt snapshot marker.");
      assertArrayEquals(
          new int[] {1, 0},
          rebuiltRoot.getData().lastMove.get(),
          "force rebuild should keep the rebuilt snapshot marker coordinates.");
      assertEquals(
          Stone.WHITE,
          rebuiltRoot.getData().lastMoveColor,
          "force rebuild should keep the rebuilt snapshot marker color.");
    }
  }

  @Test
  void forceRebuildWithoutSnapshotAnchorPreservesRootStartStoneSetup() throws Exception {
    Stone[] rootStones = stones(placement(0, 0, Stone.BLACK));
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(2, 2, Stone.BLACK));

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(
                rootStones,
                Optional.of(new int[] {0, 0}),
                Stone.BLACK,
                false,
                1,
                BoardNodeKind.MOVE))) {
      harness.board.hasStartStone = true;
      harness.board.startStonelist = new ArrayList<>();
      harness.board.startStonelist.add(startStone(0, 0, true));
      harness.board.startStonelist.add(startStone(1, 0, false));
      BoardHistoryNode syncStartNode = harness.board.getHistory().getCurrentHistoryNode();

      SyncSnapshotClassifier classifier = new SyncSnapshotClassifier(BOARD_SIZE, BOARD_SIZE);
      int[] snapshotCodes = snapshot(target, Optional.empty(), Stone.EMPTY);

      invokeRebuildFromSnapshot(
          harness.readBoard,
          syncStartNode,
          snapshotCodes,
          classifier.summarizeDelta(syncStartNode.getData().stones, snapshotCodes));

      assertTrue(
          harness.board.hasStartStone,
          "force rebuild should keep root hasStartStone when no SNAPSHOT anchor exists.");
      assertEquals(
          2,
          harness.board.startStonelist.size(),
          "force rebuild should preserve configured root start stones.");
      assertEquals(0, harness.board.startStonelist.get(0).x);
      assertEquals(0, harness.board.startStonelist.get(0).y);
      assertTrue(harness.board.startStonelist.get(0).isblack);
      assertEquals(1, harness.board.startStonelist.get(1).x);
      assertEquals(0, harness.board.startStonelist.get(1).y);
      assertFalse(harness.board.startStonelist.get(1).isblack);
      assertArrayEquals(
          target,
          harness.board.getHistory().getMainEnd().getData().stones,
          "force rebuild should still land the target snapshot board.");
      assertTrue(
          harness.board.getHistory().getMainEnd().getData().isSnapshotNode(),
          "force rebuild should still rebuild to a SNAPSHOT root.");
    }
  }

  @Test
  void forceRebuildWithRootSnapshotAnchorPreservesRootStartStoneSetup() throws Exception {
    Stone[] rootStones = stones(placement(0, 0, Stone.BLACK));
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(2, 2, Stone.BLACK));

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(
                rootStones, Optional.empty(), Stone.EMPTY, false, 0, BoardNodeKind.SNAPSHOT))) {
      harness.board.hasStartStone = true;
      harness.board.startStonelist = new ArrayList<>();
      harness.board.startStonelist.add(startStone(0, 0, true));
      harness.board.startStonelist.add(startStone(1, 0, false));
      BoardHistoryNode syncStartNode = harness.board.getHistory().getCurrentHistoryNode();

      SyncSnapshotClassifier classifier = new SyncSnapshotClassifier(BOARD_SIZE, BOARD_SIZE);
      int[] snapshotCodes = snapshot(target, Optional.empty(), Stone.EMPTY);

      invokeRebuildFromSnapshot(
          harness.readBoard,
          syncStartNode,
          snapshotCodes,
          classifier.summarizeDelta(syncStartNode.getData().stones, snapshotCodes));

      assertTrue(
          harness.board.hasStartStone,
          "force rebuild should keep root hasStartStone when root SNAPSHOT is the setup anchor.");
      assertEquals(
          2,
          harness.board.startStonelist.size(),
          "force rebuild should preserve root start-stone setup under a root SNAPSHOT anchor.");
      assertEquals(0, harness.board.startStonelist.get(0).x);
      assertEquals(0, harness.board.startStonelist.get(0).y);
      assertTrue(harness.board.startStonelist.get(0).isblack);
      assertEquals(1, harness.board.startStonelist.get(1).x);
      assertEquals(0, harness.board.startStonelist.get(1).y);
      assertFalse(harness.board.startStonelist.get(1).isblack);
      assertArrayEquals(
          target,
          harness.board.getHistory().getMainEnd().getData().stones,
          "force rebuild should still land the target snapshot board.");
      assertTrue(
          harness.board.getHistory().getMainEnd().getData().isSnapshotNode(),
          "force rebuild should still rebuild to a SNAPSHOT root.");
    }
  }

  @Test
  void forceRebuildWithNonRootSnapshotAnchorDoesNotBackfillBoardStartStoneSetup() throws Exception {
    Stone[] rootStones = stones(placement(0, 0, Stone.BLACK));
    Stone[] anchorStones = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(2, 0, Stone.BLACK),
            placement(2, 2, Stone.WHITE));

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(
                rootStones,
                Optional.of(new int[] {0, 0}),
                Stone.BLACK,
                false,
                1,
                BoardNodeKind.MOVE))) {
      BoardHistoryList history = harness.board.getHistory();
      history.addOrGoto(
          createFixtureData(
              anchorStones, Optional.empty(), Stone.EMPTY, true, 2, BoardNodeKind.SNAPSHOT));
      history.place(2, 0, Stone.BLACK, false);

      harness.board.hasStartStone = true;
      harness.board.startStonelist = new ArrayList<>();
      harness.board.startStonelist.add(startStone(0, 0, true));
      harness.board.startStonelist.add(startStone(1, 0, false));
      BoardHistoryNode syncStartNode = harness.board.getHistory().getCurrentHistoryNode();

      SyncSnapshotClassifier classifier = new SyncSnapshotClassifier(BOARD_SIZE, BOARD_SIZE);
      int[] snapshotCodes = snapshot(target, Optional.empty(), Stone.EMPTY);

      invokeRebuildFromSnapshot(
          harness.readBoard,
          syncStartNode,
          snapshotCodes,
          classifier.summarizeDelta(syncStartNode.getData().stones, snapshotCodes));

      assertFalse(
          harness.board.hasStartStone,
          "force rebuild should not backfill board-level root setup from non-root SNAPSHOT anchors.");
      assertTrue(
          harness.board.startStonelist.isEmpty(),
          "non-root SNAPSHOT anchors should not restore board-level startStone list.");
      assertArrayEquals(
          target,
          harness.board.getHistory().getMainEnd().getData().stones,
          "force rebuild should still land the target snapshot board.");
      assertTrue(
          harness.board.getHistory().getMainEnd().getData().isSnapshotNode(),
          "force rebuild should still rebuild to a SNAPSHOT root.");
    }
  }

  @Test
  void markedStaticSnapshotWritesLastMoveIntoMoveNumberList() throws Exception {
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(0, 1, Stone.BLACK));
    int[] lastMove = new int[] {0, 1};

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      harness.sync(snapshot(target, Optional.of(lastMove), Stone.BLACK));

      BoardData data = harness.board.getHistory().getMainEnd().getData();
      assertEquals(
          3,
          data.moveNumberList[stoneIndex(lastMove[0], lastMove[1])],
          "marked static rebuild should write the rebuilt last move number into moveNumberList.");
    }
  }

  @Test
  void markedSnapshotMoveNumberKeepsRealCountWhenSetupStonesExist() throws Exception {
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(2, 0, Stone.BLACK));
    int[] marker = new int[] {2, 0};

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(target, Optional.empty(), Stone.EMPTY, false, 1, BoardNodeKind.SNAPSHOT))) {
      BoardHistoryNode syncStartNode = harness.board.getHistory().getCurrentHistoryNode();
      syncStartNode.getData().moveNumberList[stoneIndex(2, 0)] = 1;
      int[] snapshotCodes = snapshot(target, Optional.of(marker), Stone.BLACK);
      SyncSnapshotClassifier classifier = new SyncSnapshotClassifier(BOARD_SIZE, BOARD_SIZE);

      invokeRebuildFromSnapshot(
          harness.readBoard,
          syncStartNode,
          snapshotCodes,
          classifier.summarizeDelta(syncStartNode.getData().stones, snapshotCodes));

      BoardData rebuilt = harness.board.getHistory().getMainEnd().getData();
      assertEquals(
          1,
          rebuilt.moveNumber,
          "marked snapshot rebuild must preserve real move count instead of counting setup stones.");
      assertTrue(rebuilt.lastMove.isPresent(), "marker rebuild should preserve marker metadata.");
      assertArrayEquals(marker, rebuilt.lastMove.get());
      assertEquals(Stone.BLACK, rebuilt.lastMoveColor);
    }
  }

  @Test
  void transientConflictFrameHoldsBeforeForcingStaticRebuild() throws Exception {
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(0, 1, Stone.BLACK),
            placement(1, 1, Stone.WHITE));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode originalMainEnd = harness.board.getHistory().getMainEnd();
      harness.board.resetCounters();
      harness.frame.refreshCount = 0;
      harness.leelaz.clearCount = 0;
      harness.leelaz.playedMoves = new ArrayList<>();

      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      assertSame(
          originalMainEnd,
          harness.board.getHistory().getMainEnd(),
          "a one-off conflicting snapshot should stay on the current history node.");
      assertEquals(
          0,
          harness.leelaz.clearCount,
          "the first conflicting frame should debounce before rebuild.");
      assertEquals(
          0, harness.frame.refreshCount, "holding a transient conflict should not refresh the UI.");

      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      assertEquals(
          1,
          harness.leelaz.clearCount,
          "the repeated conflicting frame should trigger one static engine rebuild.");
      assertArrayEquals(
          target,
          harness.board.getHistory().getMainEnd().getData().stones,
          "the repeated conflicting frame should rebuild to the remote snapshot.");
      assertFalse(
          harness.board.getHistory().getMainEnd().previous().isPresent(),
          "the repeated conflicting frame should still resolve through a static rebuild root.");
    }
  }

  @Test
  void singleMoveCaptureDoesNotTriggerARebuild() throws Exception {
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

    try (SyncHarness harness = SyncHarness.create(false, rootHistory(beforeCapture, true))) {
      harness.sync(snapshot(afterCapture, Optional.of(new int[] {1, 2}), Stone.BLACK));

      assertEquals(
          0, harness.board.clearCount, "a single capture should be applied without rebuild.");
      assertArrayEquals(
          afterCapture,
          harness.board.getHistory().getMainEnd().getData().stones,
          "capture sync should produce the captured board position.");
    }
  }

  @Test
  void singleMoveRecoveryKeepsViewFocusedOnNewMainEnd() throws Exception {
    Stone[] afterCapture =
        stones(
            placement(0, 1, Stone.BLACK),
            placement(1, 0, Stone.BLACK),
            placement(2, 1, Stone.BLACK),
            placement(1, 2, Stone.BLACK));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      BoardHistoryList history = harness.board.getHistory();
      history.place(0, 1, Stone.BLACK, false);
      history.pass(Stone.WHITE);
      BoardHistoryNode viewedNode = history.getCurrentHistoryNode();
      history.place(1, 0, Stone.BLACK, false);
      history.place(1, 1, Stone.WHITE, false);
      history.place(2, 1, Stone.BLACK, false);
      history.pass(Stone.WHITE);
      BoardHistoryNode previousMainEnd = history.getCurrentHistoryNode();
      history.setHead(viewedNode);

      harness.sync(snapshot(afterCapture, Optional.of(new int[] {1, 2}), Stone.BLACK));

      BoardHistoryNode rebuiltMainEnd = history.getMainEnd();
      assertNotSame(
          previousMainEnd,
          rebuiltMainEnd,
          "single-move recovery should append a new main-end move.");
      assertSame(
          rebuiltMainEnd,
          history.getCurrentHistoryNode(),
          "single-move recovery should leave the view on the recovered main-end move.");
      assertNotSame(
          viewedNode,
          history.getCurrentHistoryNode(),
          "single-move recovery should not restore the old viewed node.");
      assertTrue(
          rebuiltMainEnd.getData().isMoveNode(),
          "single-move recovery should still record a real MOVE node at main end.");
    }
  }

  @Test
  void existingHistoryJumpRebuildsSnapshotRootInsteadOfRewindingHistory() throws Exception {
    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK),
              placement(1, 1, Stone.WHITE),
              placement(2, 2, Stone.BLACK));
      BoardHistoryNode matchedNode = path.nodes.get(1);
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);

      harness.board.resetCounters();
      harness.board.getHistory().setHead(mainEnd);
      harness.frame.refreshCount = 0;
      harness.leelaz.clearCount = 0;
      harness.sync(
          snapshot(
              matchedNode.getData().stones,
              matchedNode.getData().lastMove,
              matchedNode.getData().lastMoveColor));
      assertSame(
          mainEnd,
          harness.board.getHistory().getMainEnd(),
          "the first rollback frame should hold instead of rewinding existing history.");
      assertEquals(
          0, harness.leelaz.clearCount, "holding a rollback frame should not rebuild immediately.");

      harness.sync(
          snapshot(
              matchedNode.getData().stones,
              matchedNode.getData().lastMove,
              matchedNode.getData().lastMoveColor));

      assertEquals(
          1, harness.leelaz.clearCount, "repeated rollback frames should force one rebuild.");
      assertEquals(
          0,
          harness.board.placeForSyncCount,
          "rollback rebuild should not replay stones one by one.");
      assertStaticSnapshotRoot(
          harness.board,
          matchedNode.getData().stones,
          matchedNode.getData().lastMove.get(),
          matchedNode.getData().lastMoveColor,
          matchedNode.getData().moveNumber);
    }
  }

  @Test
  void sameBoardFoxMoveNumberDoesNotOverwriteExplicitPassNode() throws Exception {
    Stone[] repeatedStones = stones(placement(1, 1, Stone.BLACK));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      buildHistory(harness.board, placement(1, 1, Stone.BLACK));
      BoardData passData =
          BoardData.pass(
              repeatedStones.clone(),
              Stone.WHITE,
              true,
              zobrist(repeatedStones),
              2,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0);
      harness.board.getHistory().add(passData);
      BoardHistoryNode passNode = harness.board.getHistory().getCurrentHistoryNode();

      harness.board.resetCounters();
      harness.frame.refreshCount = 0;
      harness.leelaz.clearCount = 0;
      armFoxMoveNumber(harness.readBoard, 58);
      harness.sync(snapshot(repeatedStones, Optional.empty(), Stone.EMPTY));

      assertSame(
          passNode,
          harness.board.getHistory().getMainEnd(),
          "fox metadata should preserve an explicit PASS node when the board already matches.");
      assertTrue(
          harness.board.getHistory().getMainEnd().getData().isPassNode(),
          "the matching PASS node should remain a real history action.");
      assertEquals(0, harness.leelaz.clearCount, "preserving PASS should avoid metadata rebuilds.");
      assertEquals(0, harness.frame.refreshCount, "preserving PASS should keep the UI steady.");
    }
  }

  @Test
  void startAtSameSizeDoesNotClearBoardOrResumeState() throws Exception {
    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(0, 0, Stone.BLACK),
              placement(1, 0, Stone.WHITE),
              placement(0, 1, Stone.BLACK));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);
      SyncResumeState resumeState =
          new SyncResumeState(
              mainEnd,
              SyncRemoteContext.forFoxLive(OptionalInt.of(3), "43581号", OptionalInt.of(3), false));
      setField(harness.readBoard, "resumeState", resumeState);
      setField(harness.readBoard, "lastResolvedSnapshotNode", mainEnd);

      harness.board.resetCounters();
      harness.readBoard.parseLine("start 3 3");

      assertEquals(0, harness.board.clearCount, "same-size start should not clear lizzie board.");
      assertSame(mainEnd, harness.board.getHistory().getMainEnd());
      assertSame(
          resumeState,
          getField(harness.readBoard, "resumeState"),
          "start should keep cross-session resume state.");
      assertSame(
          mainEnd,
          getField(harness.readBoard, "lastResolvedSnapshotNode"),
          "start should keep the remembered resolved node.");
    }
  }

  @Test
  void clearDoesNotClearBoardOrResumeState() throws Exception {
    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);
      SyncResumeState resumeState =
          new SyncResumeState(
              mainEnd,
              SyncRemoteContext.forFoxLive(OptionalInt.of(2), "43581号", OptionalInt.of(2), false));
      setField(harness.readBoard, "resumeState", resumeState);
      setField(harness.readBoard, "lastResolvedSnapshotNode", mainEnd);

      harness.board.resetCounters();
      harness.readBoard.parseLine("clear");

      assertEquals(0, harness.board.clearCount, "readboard clear should not clear lizzie board.");
      assertSame(mainEnd, harness.board.getHistory().getMainEnd());
      assertSame(
          resumeState,
          getField(harness.readBoard, "resumeState"),
          "clear should keep cross-session resume state.");
      assertSame(
          mainEnd,
          getField(harness.readBoard, "lastResolvedSnapshotNode"),
          "clear should keep the remembered resolved node.");
    }
  }

  @Test
  void startFirstFoxJumpFrameBypassesHoldAndRebuildsImmediately() throws Exception {
    Stone[] rootStones = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
    Stone[] target =
        stones(
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(0, 1, Stone.BLACK),
            placement(1, 1, Stone.WHITE),
            placement(2, 2, Stone.BLACK),
            placement(2, 0, Stone.WHITE));

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(
                rootStones,
                Optional.of(new int[] {1, 0}),
                Stone.WHITE,
                true,
                100,
                BoardNodeKind.SNAPSHOT))) {
      buildHistory(
          harness.board,
          placement(0, 1, Stone.BLACK),
          placement(1, 1, Stone.WHITE),
          placement(2, 2, Stone.BLACK));

      harness.readBoard.parseLine("start 3 3");
      armFoxMoveNumber(harness.readBoard, 106);
      harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

      assertEquals(
          0,
          harness.board.placeForSyncCount,
          "the first frame after start should bypass HOLD for fox multi-step jumps.");
      assertStaticSnapshotRootWithoutMarker(harness.board, target, 106, true);
    }
  }

  @Test
  void forceRebuildFlagAppliesToNextFrameOnly() throws Exception {
    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode originalMainEnd = path.nodes.get(path.nodes.size() - 1);
      int[] snapshotCodes =
          snapshot(
              originalMainEnd.getData().stones,
              originalMainEnd.getData().lastMove,
              originalMainEnd.getData().lastMoveColor);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(snapshotCodes);

      BoardHistoryNode rebuiltMainEnd = harness.board.getHistory().getMainEnd();
      assertNotSame(
          originalMainEnd,
          rebuiltMainEnd,
          "forceRebuild should bypass the steady-state NO_CHANGE.");
      assertTrue(rebuiltMainEnd.getData().isSnapshotNode());

      harness.board.resetCounters();
      harness.frame.refreshCount = 0;
      harness.leelaz.clearCount = 0;
      harness.sync(snapshotCodes);

      assertSame(
          rebuiltMainEnd,
          harness.board.getHistory().getMainEnd(),
          "forceRebuild should be consumed after one sync frame.");
      assertEquals(
          0,
          harness.leelaz.clearCount,
          "steady-state frame after forceRebuild should not rebuild again.");
      assertEquals(
          0,
          harness.board.placeForSyncCount,
          "steady-state frame after forceRebuild should not replay stones.");
    }
  }

  @Test
  void roomTokenChangeForcesRebuildInsteadOfReusingEarlierFoxHistory() throws Exception {
    Stone[] ancestorStones = stones(placement(1, 1, Stone.BLACK));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(1, 1, Stone.BLACK),
              placement(0, 0, Stone.WHITE),
              placement(2, 2, Stone.BLACK));
      BoardHistoryNode ancestorNode = path.nodes.get(0);
      setField(
          harness.readBoard,
          "resumeState",
          new SyncResumeState(
              ancestorNode,
              SyncRemoteContext.forFoxLive(OptionalInt.of(1), "43581号", OptionalInt.of(1), false)));
      setField(harness.readBoard, "lastResolvedSnapshotNode", ancestorNode);

      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("roomToken 55667号");
      harness.readBoard.parseLine("liveTitleMove 1");
      harness.readBoard.parseLine("foxMoveNumber 1");
      harness.sync(snapshot(ancestorStones, Optional.empty(), Stone.EMPTY));

      BoardHistoryNode rebuiltMainEnd = harness.board.getHistory().getMainEnd();
      assertNotSame(
          ancestorNode,
          rebuiltMainEnd,
          "changing roomToken should force a rebuild instead of reusing old main-trunk history.");
      assertFalse(
          rebuiltMainEnd.previous().isPresent(),
          "room-token conflict should rebuild to a new snapshot root.");
      assertTrue(rebuiltMainEnd.getData().isSnapshotNode());
      assertArrayEquals(ancestorStones, rebuiltMainEnd.getData().stones);
    }
  }

  @Test
  void recordTitleFingerprintChangeForcesRebuildInsteadOfReusingEarlierFoxHistory()
      throws Exception {
    Stone[] ancestorStones = stones(placement(1, 1, Stone.BLACK));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(1, 1, Stone.BLACK),
              placement(0, 0, Stone.WHITE),
              placement(2, 2, Stone.BLACK));
      BoardHistoryNode ancestorNode = path.nodes.get(0);
      setField(
          harness.readBoard,
          "resumeState",
          new SyncResumeState(
              ancestorNode,
              SyncRemoteContext.forFoxRecord(
                  OptionalInt.of(1),
                  OptionalInt.of(1),
                  OptionalInt.of(333),
                  false,
                  "record-fingerprint-a",
                  false)));
      setField(harness.readBoard, "lastResolvedSnapshotNode", ancestorNode);

      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("recordCurrentMove 1");
      harness.readBoard.parseLine("recordTotalMove 333");
      harness.readBoard.parseLine("recordAtEnd 0");
      harness.readBoard.parseLine("recordTitleFingerprint record-fingerprint-b");
      harness.readBoard.parseLine("foxMoveNumber 1");
      harness.sync(snapshot(ancestorStones, Optional.empty(), Stone.EMPTY));

      BoardHistoryNode rebuiltMainEnd = harness.board.getHistory().getMainEnd();
      assertNotSame(
          ancestorNode,
          rebuiltMainEnd,
          "changing record title fingerprint should force a rebuild instead of reusing old history.");
      assertFalse(
          rebuiltMainEnd.previous().isPresent(),
          "record-title conflict should rebuild to a new snapshot root.");
      assertTrue(rebuiltMainEnd.getData().isSnapshotNode());
      assertArrayEquals(ancestorStones, rebuiltMainEnd.getData().stones);
    }
  }

  @Test
  void recordViewAtEndUsesTotalMoveForAncestorRecovery() throws Exception {
    Stone[] ancestorStones = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));

    try (SyncHarness harness =
        SyncHarness.create(
            false,
            rootHistory(
                ancestorStones,
                Optional.empty(),
                Stone.EMPTY,
                true,
                333,
                BoardNodeKind.SNAPSHOT))) {
      BoardHistoryNode ancestorNode = harness.board.getHistory().getMainEnd();
      HistoryPath path = buildHistory(harness.board, placement(0, 1, Stone.BLACK));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);

      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("recordTotalMove 333");
      harness.readBoard.parseLine("recordAtEnd 1");
      harness.readBoard.parseLine("recordTitleFingerprint record-fingerprint");
      harness.readBoard.parseLine("foxMoveNumber 333");
      harness.sync(snapshot(ancestorStones, Optional.empty(), Stone.EMPTY));

      assertSame(
          mainEnd,
          harness.board.getHistory().getMainEnd(),
          "record-at-end ancestor recovery should keep later local history available.");
      assertSame(
          ancestorNode,
          harness.board.getHistory().getCurrentHistoryNode(),
          "record-at-end title metadata should recover the ancestor snapshot directly.");
      assertEquals(
          0, harness.leelaz.clearCount, "record-at-end ancestor recovery should not rebuild.");
    }
  }

  @Test
  void recordTotalMoveChangeForcesRebuildInsteadOfReusingEarlierFoxHistory() throws Exception {
    Stone[] ancestorStones = stones(placement(1, 1, Stone.BLACK));

    try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
      HistoryPath path =
          buildHistory(
              harness.board,
              placement(1, 1, Stone.BLACK),
              placement(0, 0, Stone.WHITE),
              placement(2, 2, Stone.BLACK));
      BoardHistoryNode ancestorNode = path.nodes.get(0);
      setField(
          harness.readBoard,
          "resumeState",
          new SyncResumeState(
              ancestorNode,
              SyncRemoteContext.forFoxRecord(
                  OptionalInt.of(1),
                  OptionalInt.of(1),
                  OptionalInt.of(333),
                  false,
                  "record-fingerprint",
                  false)));
      setField(harness.readBoard, "lastResolvedSnapshotNode", ancestorNode);

      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("recordCurrentMove 1");
      harness.readBoard.parseLine("recordTotalMove 444");
      harness.readBoard.parseLine("recordAtEnd 0");
      harness.readBoard.parseLine("recordTitleFingerprint record-fingerprint");
      harness.readBoard.parseLine("foxMoveNumber 1");
      harness.sync(snapshot(ancestorStones, Optional.empty(), Stone.EMPTY));

      BoardHistoryNode rebuiltMainEnd = harness.board.getHistory().getMainEnd();
      assertNotSame(
          ancestorNode,
          rebuiltMainEnd,
          "changing record total move should force a rebuild instead of reusing old history.");
      assertFalse(
          rebuiltMainEnd.previous().isPresent(),
          "record-total conflict should rebuild to a new snapshot root.");
      assertTrue(rebuiltMainEnd.getData().isSnapshotNode());
      assertArrayEquals(ancestorStones, rebuiltMainEnd.getData().stones);
    }
  }

  @Test
  void fixtureBuildersResetBoardSizeAndZobristBeforeCreating3x3Data() {
    Board.boardWidth = 2;
    Board.boardHeight = 2;
    Zobrist.init();

    BoardHistoryList history = rootHistory(stones(placement(2, 2, Stone.BLACK)), true);

    BoardData data = history.getCurrentHistoryNode().getData();
    assertEquals(BOARD_SIZE, Board.boardWidth, "fixture builders should restore the 3x3 width.");
    assertEquals(BOARD_SIZE, Board.boardHeight, "fixture builders should restore the 3x3 height.");
    assertEquals(
        Stone.BLACK, data.stones[stoneIndex(2, 2)], "fixture data should keep 3x3 stones.");
  }

  private static void armFoxMoveNumber(ReadBoard readBoard, int moveNumber) {
    readBoard.parseLine("syncPlatform fox");
    readBoard.parseLine("foxMoveNumber " + moveNumber);
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

  private static BoardHistoryList emptyHistory() {
    resetFixtureBoardState();
    return new BoardHistoryList(emptyBoardData(true));
  }

  private static BoardHistoryList rootHistory(Stone[] stones, boolean blackToPlay) {
    return rootHistory(
        stones, Optional.empty(), Stone.EMPTY, blackToPlay, 0, BoardNodeKind.SNAPSHOT);
  }

  private static BoardHistoryList rootHistory(
      Stone[] stones,
      Optional<int[]> lastMove,
      Stone lastMoveColor,
      boolean blackToPlay,
      int moveNumber,
      BoardNodeKind nodeKind) {
    resetFixtureBoardState();
    return new BoardHistoryList(
        createFixtureData(stones, lastMove, lastMoveColor, blackToPlay, moveNumber, nodeKind));
  }

  private static BoardHistoryList rootHistoryWithCaptures(
      Stone[] stones,
      Optional<int[]> lastMove,
      Stone lastMoveColor,
      boolean blackToPlay,
      int moveNumber,
      BoardNodeKind nodeKind,
      int blackCaptures,
      int whiteCaptures) {
    resetFixtureBoardState();
    return new BoardHistoryList(
        createFixtureData(
            stones,
            lastMove,
            lastMoveColor,
            blackToPlay,
            moveNumber,
            nodeKind,
            blackCaptures,
            whiteCaptures));
  }

  private static BoardData emptyBoardData(boolean blackToPlay) {
    resetFixtureBoardState();
    return BoardData.snapshot(
        emptyStones(),
        Optional.empty(),
        Stone.EMPTY,
        blackToPlay,
        new Zobrist(),
        0,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < stones.length; index++) {
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
      int x = index % BOARD_SIZE;
      int y = index / BOARD_SIZE;
      Stone stone = stones[stoneIndex(x, y)];
      snapshot[index] = stone.isBlack() ? 1 : stone.isWhite() ? 2 : 0;
    }
    if (lastMove.isPresent()) {
      int[] coords = lastMove.get();
      snapshot[coords[1] * BOARD_SIZE + coords[0]] = lastMoveColor.isBlack() ? 3 : 4;
    }
    return snapshot;
  }

  private static void assertStaticSnapshotRoot(
      TrackingBoard board,
      Stone[] expectedStones,
      int[] expectedLastMove,
      Stone expectedColor,
      int expectedMoveNumber) {
    BoardHistoryList history = board.getHistory();
    BoardHistoryNode mainEnd = history.getMainEnd();
    BoardData data = mainEnd.getData();

    assertArrayEquals(expectedStones, data.stones, "sync should end on the remote board position.");
    assertSame(
        mainEnd,
        history.getCurrentHistoryNode(),
        "static rebuild should leave the board focused on the rebuilt snapshot.");
    assertFalse(
        mainEnd.previous().isPresent(),
        "static rebuild should keep the remote snapshot as a single root state.");
    assertTrue(data.isSnapshotNode(), "marker snapshots should rebuild as SNAPSHOT nodes.");
    assertFalse(
        data.isHistoryActionNode(),
        "marker metadata should stay board-only and must not turn the snapshot into history.");
    assertTrue(data.lastMove.isPresent(), "snapshot marker should remain as lastMove metadata.");
    assertArrayEquals(
        expectedLastMove, data.lastMove.get(), "lastMove should come from snapshot marker.");
    assertEquals(expectedColor, data.lastMoveColor, "lastMoveColor should match snapshot marker.");
    assertEquals(
        expectedColor == Stone.WHITE,
        data.blackToPlay,
        "blackToPlay should reflect the side to move after the marked last move.");
    assertEquals(
        expectedMoveNumber,
        data.moveNumber,
        "marked static snapshots should infer a consistent move number.");
    assertTrue(
        board.getMoveList().isEmpty(),
        "marked static snapshots should not leak into exported history actions.");
  }

  private static void assertStaticSnapshotRootWithoutMarker(
      TrackingBoard board,
      Stone[] expectedStones,
      int expectedMoveNumber,
      boolean expectedBlackToPlay) {
    BoardHistoryList history = board.getHistory();
    BoardHistoryNode mainEnd = history.getMainEnd();
    BoardData data = mainEnd.getData();

    assertArrayEquals(expectedStones, data.stones, "sync should end on the remote board position.");
    assertSame(
        mainEnd,
        history.getCurrentHistoryNode(),
        "static rebuild should leave the board focused on the rebuilt snapshot.");
    assertFalse(
        mainEnd.previous().isPresent(),
        "static rebuild should keep the remote snapshot as a single root state.");
    assertTrue(data.isSnapshotNode(), "markerless sync should rebuild as a SNAPSHOT node.");
    assertFalse(data.lastMove.isPresent(), "markerless fox sync should stay unmarked.");
    assertEquals(Stone.EMPTY, data.lastMoveColor, "markerless fox sync should not invent a color.");
    assertEquals(
        expectedMoveNumber, data.moveNumber, "fox move number should seed the rebuilt root.");
    assertEquals(
        expectedBlackToPlay,
        data.blackToPlay,
        "fox move parity should decide the side to play when the snapshot lacks a marker.");
    assertTrue(
        board.getMoveList().isEmpty(),
        "markerless fox sync should not fabricate pass history from a static rebuild root.");
  }

  private static void seedSetupMetadata(BoardHistoryNode node) {
    BoardData data = node.getData();
    data.addProperty("AB", "aa");
    data.addProperty("AW", "ba");
    data.addProperty("AE", "cb");
    data.comment = "setup snapshot comment";
    node.addExtraStones(2, 2, true);
    node.setRemovedStone();
  }

  private static void assertSetupMetadata(BoardHistoryNode node, String message) {
    assertEquals("aa", node.getData().getProperty("AB"), message);
    assertEquals("ba", node.getData().getProperty("AW"), message);
    assertEquals("cb", node.getData().getProperty("AE"), message);
    assertEquals("setup snapshot comment", node.getData().comment, message);
    assertTrue(node.hasRemovedStone(), message);
    assertNotNull(node.extraStones, message);
    assertEquals(1, node.extraStones.size(), message);
    assertEquals(2, node.extraStones.get(0).x, message);
    assertEquals(2, node.extraStones.get(0).y, message);
    assertTrue(node.extraStones.get(0).isBlack, message);
  }

  private static Zobrist zobrist(Stone[] stones) {
    resetFixtureBoardState();
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

  private static void resetFixtureBoardState() {
    Board.boardWidth = BOARD_SIZE;
    Board.boardHeight = BOARD_SIZE;
    Zobrist.init();
  }

  private static int stoneIndex(int x, int y) {
    return x * BOARD_SIZE + y;
  }

  private static String moveRecord(Stone color, int x, int y) {
    return color.name() + ":" + Board.convertCoordinatesToName(x, y);
  }

  private static void assertTempFileEventuallyDeleted(Path path, String message)
      throws InterruptedException {
    for (int attempt = 0; attempt < 40; attempt++) {
      if (!Files.exists(path)) {
        return;
      }
      Thread.sleep(50L);
    }
    assertFalse(Files.exists(path), message);
  }

  private static BoardData createFixtureData(
      Stone[] stones,
      Optional<int[]> lastMove,
      Stone lastMoveColor,
      boolean blackToPlay,
      int moveNumber,
      BoardNodeKind nodeKind) {
    return createFixtureData(
        stones, lastMove, lastMoveColor, blackToPlay, moveNumber, nodeKind, 0, 0);
  }

  private static BoardData createFixtureData(
      Stone[] stones,
      Optional<int[]> lastMove,
      Stone lastMoveColor,
      boolean blackToPlay,
      int moveNumber,
      BoardNodeKind nodeKind,
      int blackCaptures,
      int whiteCaptures) {
    if (nodeKind == BoardNodeKind.MOVE) {
      return BoardData.move(
          stones.clone(),
          lastMove.get(),
          lastMoveColor,
          blackToPlay,
          zobrist(stones),
          moveNumber,
          new int[BOARD_AREA],
          blackCaptures,
          whiteCaptures,
          50,
          0);
    }
    if (nodeKind == BoardNodeKind.SNAPSHOT) {
      return BoardData.snapshot(
          stones.clone(),
          lastMove,
          lastMoveColor,
          blackToPlay,
          zobrist(stones),
          moveNumber,
          new int[BOARD_AREA],
          blackCaptures,
          whiteCaptures,
          50,
          0);
    }
    return BoardData.pass(
        stones.clone(),
        lastMoveColor,
        blackToPlay,
        zobrist(stones),
        moveNumber,
        new int[BOARD_AREA],
        blackCaptures,
        whiteCaptures,
        50,
        0);
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

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object getField(Object target, String name) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    return field.get(target);
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

  private static void invokeRebuildFromSnapshot(
      ReadBoard readBoard,
      BoardHistoryNode syncStartNode,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta)
      throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod(
            "rebuildFromSnapshot",
            BoardHistoryNode.class,
            int[].class,
            SyncSnapshotClassifier.SnapshotDelta.class);
    method.setAccessible(true);
    method.invoke(readBoard, syncStartNode, snapshotCodes, snapshotDelta);
  }

  private static boolean invokeInferBlackToPlayWithoutMarker(
      ReadBoard readBoard,
      BoardHistoryNode syncStartNode,
      Stone[] snapshotStones,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta)
      throws Exception {
    Method method =
        ReadBoard.class.getDeclaredMethod(
            "inferBlackToPlayWithoutMarker",
            BoardHistoryNode.class,
            Stone[].class,
            SyncSnapshotClassifier.SnapshotDelta.class);
    method.setAccessible(true);
    return (boolean) method.invoke(readBoard, syncStartNode, snapshotStones, snapshotDelta);
  }

  private static final class SyncHarness implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final Leelaz previousLeelaz;
    private final LizzieFrame previousFrame;
    private final TrackingBoard board;
    private final TrackingFrame frame;
    private final SnapshotTrackingLeelaz leelaz;
    private final ReadBoard readBoard;

    private SyncHarness(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        Leelaz previousLeelaz,
        LizzieFrame previousFrame,
        TrackingBoard board,
        TrackingFrame frame,
        SnapshotTrackingLeelaz leelaz,
        ReadBoard readBoard) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousLeelaz = previousLeelaz;
      this.previousFrame = previousFrame;
      this.board = board;
      this.frame = frame;
      this.leelaz = leelaz;
      this.readBoard = readBoard;
    }

    private static SyncHarness create(boolean firstSync, BoardHistoryList history)
        throws Exception {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Config previousConfig = Lizzie.config;
      Board previousBoard = Lizzie.board;
      Leelaz previousLeelaz = Lizzie.leelaz;
      LizzieFrame previousFrame = Lizzie.frame;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;

      Config config = allocate(Config.class);
      config.alwaysSyncBoardStat = false;
      config.alwaysGotoLastOnLive = false;
      config.newMoveNumberInBranch = true;
      config.noCapture = false;
      config.winrateAlwaysBlack = false;
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

      ReadBoard readBoard = allocate(ReadBoard.class);
      setField(readBoard, "conflictTracker", new SyncConflictTracker());
      setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
      setField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
      setField(readBoard, "tempcount", new ArrayList<Integer>());
      readBoard.firstSync = firstSync;
      frame.readBoard = readBoard;

      return new SyncHarness(
          previousBoardWidth,
          previousBoardHeight,
          previousConfig,
          previousBoard,
          previousLeelaz,
          previousFrame,
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
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.frame = previousFrame;
    }
  }

  private static final class TrackingBoard extends Board {
    private int clearCount;
    private int placeForSyncCount;
    private int moveToAnyPositionCount;
    private int previousMoveCount;

    private void initialize(BoardHistoryList history) {
      setHistory(history);
      hasStartStone = false;
    }

    private void resetCounters() {
      clearCount = 0;
      placeForSyncCount = 0;
      moveToAnyPositionCount = 0;
      previousMoveCount = 0;
    }

    @Override
    public void clearAfterMove() {
      // Keep history-building side effects out of these behavior tests.
    }

    @Override
    public void clear(boolean isEngineGame) {
      clearCount++;
      setHistory(emptyHistory());
      hasStartStone = false;
      if (Lizzie.frame != null && Lizzie.frame.readBoard != null) {
        Lizzie.frame.readBoard.firstSync = true;
      }
    }

    @Override
    public void placeForSync(int x, int y, Stone color, boolean newBranch) {
      placeForSyncCount++;
      getHistory().place(x, y, color, newBranch);
      if (Lizzie.frame != null && Lizzie.frame.readBoard != null) {
        Lizzie.frame.readBoard.lastMovePlayByLizzie = false;
      }
    }

    @Override
    public void moveToAnyPosition(BoardHistoryNode targetNode) {
      moveToAnyPositionCount++;
      getHistory().setHead(targetNode);
    }

    @Override
    public boolean previousMove(boolean needRefresh) {
      previousMoveCount++;
      Optional<BoardData> previous = getHistory().previous();
      return previous.isPresent();
    }

    @Override
    public void addStartListAll() {
      // The tests only care about the synchronized board result, not rendered start stones.
    }

    @Override
    public void flatten() {
      BoardData current = getHistory().getCurrentHistoryNode().getData();
      setHistory(rootHistory(current.stones, current.blackToPlay));
    }
  }

  private static final class TrackingFrame extends LizzieFrame {
    private int refreshCount;
    private int renderVarTreeCount;
    private int lastMoveCount;
    private TrackingBoard board;

    private void initialize(TrackingBoard board) {
      this.board = board;
      bothSync = false;
      syncBoard = false;
      isPlayingAgainstLeelaz = false;
      playerIsBlack = true;
    }

    @Override
    public void refresh() {
      refreshCount++;
    }

    @Override
    public void renderVarTree(int vw, int vh, boolean changeSize, boolean needGetEnd) {
      renderVarTreeCount++;
    }

    @Override
    public void lastMove() {
      lastMoveCount++;
      board.getHistory().setHead(board.getHistory().getMainEnd());
    }

    @Override
    public void clearKataEstimate() {
      // No UI side effects in these tests.
    }

    @Override
    public void resetTitle() {
      // No UI side effects in these tests.
    }

    @Override
    public void clearTryPlay() {
      // No UI side effects in these tests.
    }

    @Override
    public void scheduleResumeAnalysisAfterLoad(int delayMillis) {
      // Avoid background scheduler threads in sync-decision tests.
    }

    @Override
    public void scheduleResumeAnalysisAfterLoad(int delayMillis, Runnable action) {
      // Avoid background scheduler threads in sync-decision tests.
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
