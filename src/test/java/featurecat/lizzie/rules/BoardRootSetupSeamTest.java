package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineCommandSink;
import featurecat.lizzie.analysis.EngineFollowController;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.gui.BoardRenderer;
import featurecat.lizzie.gui.HumanSlGameController;
import featurecat.lizzie.gui.Input;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.WinrateGraph;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Focused behavior tests for root starting-position setup and current-position conversion (tickets
 * 01 through 03 of issue 217).
 *
 * <p>The seam mutates the root SNAPSHOT in place: placement, replacement, erase, clear-all and
 * side-to-play changes never create {@code MOVE}/{@code PASS} nodes or variations, never apply
 * capture/ko/suicide rules, and round-trip through SGF as root {@code AB}/{@code AW}/{@code PL}.
 */
class BoardRootSetupSeamTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void emptyGameSetupPlacementStaysRootOnlySnapshot() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      assertTrue(
          Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK),
          "black setup placement on an empty root should succeed.");
      assertTrue(
          Lizzie.board.setupPlaceStone(1, 1, Stone.WHITE),
          "white setup placement on an empty root should succeed.");

      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      assertTrue(root.getData().isSnapshotNode(), "setup edits should keep the root a snapshot.");
      assertEquals(0, root.numberOfChildren(), "setup placement must not create children.");
      assertEquals(0, root.getData().moveNumber, "setup placement must not consume move numbers.");
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.WHITE, root.getData().stones[Board.getIndex(1, 1)]);
    } finally {
      env.close();
    }
  }

  @Test
  void emptyGameSetupStonesDoNotBecomeMovesOrFakeSgfMoves() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK);
      Lizzie.board.setupPlaceStone(1, 1, Stone.WHITE);

      String sgf = SGFParser.saveToString(false);
      assertTrue(sgf.contains("AB[aa]"), "setup black stone should export as root AB[aa].");
      assertTrue(sgf.contains("AW[bb]"), "setup white stone should export as root AW[bb].");
      assertTrue(sgf.contains("PL[B]"), "empty/new setup should default to Black to play.");
      assertFalse(
          sgf.contains(";B[") || sgf.contains(";W["),
          "setup stones must not be saved as ordinary B[]/W[] moves.");

      BoardHistoryList roundTrip = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode root = roundTrip.getStart();
      assertTrue(root.getData().isSnapshotNode(), "saved setup should reopen as a root snapshot.");
      assertEquals(0, root.numberOfChildren(), "saved setup should reopen root-only.");
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.WHITE, root.getData().stones[Board.getIndex(1, 1)]);
    } finally {
      env.close();
    }
  }

  @Test
  void blackToolReplacesWhiteSetupStoneInPlace() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      assertTrue(Lizzie.board.setupPlaceStone(1, 1, Stone.WHITE));
      assertTrue(
          Lizzie.board.setupPlaceStone(1, 1, Stone.BLACK),
          "a black setup tool should replace an existing white setup stone in place.");

      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(1, 1)]);
      assertEquals(0, root.numberOfChildren(), "replacement must not create nodes.");
      assertTrue(root.getData().isSnapshotNode(), "replacement must keep the root a snapshot.");
    } finally {
      env.close();
    }
  }

  @Test
  void setupMutationInvalidatesAnalysisFromThePreviousPosition() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardData rootData = Lizzie.board.getHistory().getStart().getData();
      rootData.setPlayouts(500);
      rootData.winrate = 71.5;
      rootData.scoreMean = 8.25;
      rootData.bestMoves.add(new MoveData());

      assertTrue(Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK));

      assertEquals(0, rootData.getPlayouts());
      assertEquals(50.0, rootData.winrate);
      assertEquals(0.0, rootData.scoreMean);
      assertTrue(rootData.bestMoves.isEmpty());

      rootData.setPlayouts(250);
      rootData.bestMoves.add(new MoveData());
      assertTrue(Lizzie.board.setupSetSideToPlay(false));
      assertEquals(0, rootData.getPlayouts());
      assertTrue(rootData.bestMoves.isEmpty());
    } finally {
      env.close();
    }
  }

  @Test
  void setupEraseRemovesOnlySelectedPoint() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK);
      Lizzie.board.setupPlaceStone(1, 1, Stone.WHITE);
      assertTrue(
          Lizzie.board.setupEraseStone(0, 0), "erase tool should remove the selected setup point.");

      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      assertEquals(Stone.EMPTY, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.WHITE, root.getData().stones[Board.getIndex(1, 1)]);
      assertEquals(0, root.numberOfChildren(), "erase must not create nodes.");
    } finally {
      env.close();
    }
  }

  @Test
  void setupPlacementIgnoresCaptureAndSuicide() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      // Surround the center point with black setup stones on a 3x3 board.
      int[][] ring = {{0, 0}, {1, 0}, {2, 0}, {0, 1}, {2, 1}, {0, 2}, {1, 2}, {2, 2}};
      for (int[] point : ring) {
        assertTrue(Lizzie.board.setupPlaceStone(point[0], point[1], Stone.BLACK));
      }
      assertTrue(
          Lizzie.board.setupPlaceStone(1, 1, Stone.WHITE),
          "setup placement must allow a surrounded stone without capture/suicide logic.");

      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      assertEquals(
          Stone.WHITE,
          root.getData().stones[Board.getIndex(1, 1)],
          "the surrounded white stone should remain part of the starting position.");
      assertEquals(
          Stone.BLACK,
          root.getData().stones[Board.getIndex(0, 0)],
          "setup placement must not capture the surrounding black stones.");
      assertEquals(0, root.numberOfChildren(), "setup placement must stay root-only.");
    } finally {
      env.close();
    }
  }

  @Test
  void setupClearAllPreservesGameInfoAndSideToPlay() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK);
      Lizzie.board.setupPlaceStone(1, 1, Stone.WHITE);
      assertTrue(
          Lizzie.board.setupSetSideToPlay(false), "side-to-play should be switchable to White.");
      Lizzie.board.getHistory().getGameInfo().setKomi(6.5);

      assertTrue(Lizzie.board.setupClearAll(), "clear-all should reset the starting position.");

      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      for (int index = 0; index < BOARD_AREA; index++) {
        assertEquals(Stone.EMPTY, root.getData().stones[index], "clear-all should empty the root.");
      }
      assertFalse(root.getData().blackToPlay, "clear-all must preserve the chosen side-to-play.");
      assertEquals(
          6.5,
          Lizzie.board.getHistory().getGameInfo().getKomi(),
          "clear-all must preserve game metadata such as komi.");
      assertEquals(0, root.numberOfChildren(), "clear-all must keep the tree root-only.");
    } finally {
      env.close();
    }
  }

  @Test
  void emptyGameDefaultsToBlackToPlay() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      assertTrue(
          Lizzie.board.getHistory().getData().blackToPlay,
          "empty/new setup should default to Black to play.");
      Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK);
      String sgf = SGFParser.saveToString(false);
      assertTrue(sgf.contains("PL[B]"), "default side-to-play should save as PL[B].");
    } finally {
      env.close();
    }
  }

  @Test
  void setupSideToPlayRoundTripsAsPl() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK);
      assertTrue(Lizzie.board.setupSetSideToPlay(false));

      String sgf = SGFParser.saveToString(false);
      assertTrue(sgf.contains("PL[W]"), "white side-to-play should save as PL[W].");

      BoardHistoryList roundTrip = SGFParser.parseSgf(sgf, false);
      assertFalse(
          roundTrip.getStart().getData().blackToPlay, "reopened SGF should restore White to play.");
      assertEquals(0, roundTrip.getStart().numberOfChildren(), "round-trip must stay root-only.");
    } finally {
      env.close();
    }
  }

  @Test
  void rootSetupRoundTripIsStableAcrossRepeatedSaves() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK);
      Lizzie.board.setupPlaceStone(1, 1, Stone.WHITE);
      Lizzie.board.setupSetSideToPlay(false);

      String firstSave = SGFParser.saveToString(false);
      BoardHistoryList parsed = SGFParser.parseSgf(firstSave, false);
      Lizzie.board.setHistory(parsed);
      String secondSave = SGFParser.saveToString(false);

      assertTrue(
          secondSave.contains("AB[aa]") && secondSave.contains("AW[bb]"),
          "repeated saves must keep the root setup stones.");
      assertTrue(secondSave.contains("PL[W]"), "repeated saves must keep the side-to-play.");
      assertFalse(
          secondSave.contains(";B[") || secondSave.contains(";W["),
          "repeated saves must not invent ordinary moves.");
      assertEquals(
          SGFParser.parseSgf(firstSave, false).getStart().numberOfChildren(),
          SGFParser.parseSgf(secondSave, false).getStart().numberOfChildren(),
          "repeated save cycles must not incrementally generate setup child nodes.");

      BoardHistoryList secondParse = SGFParser.parseSgf(secondSave, false);
      BoardHistoryNode root = secondParse.getStart();
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.WHITE, root.getData().stones[Board.getIndex(1, 1)]);
      assertFalse(root.getData().blackToPlay, "side-to-play must survive repeated saves.");
      assertEquals(0, root.numberOfChildren(), "repeated saves must stay root-only.");
    } finally {
      env.close();
    }
  }

  @Test
  void setupSeamRefusesWhenRootHasRealHistory() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3];B[aa])", false);
      Lizzie.board.setHistory(history);

      assertFalse(
          Lizzie.board.setupPlaceStone(1, 1, Stone.BLACK),
          "setup edits must be refused when the root already has real moves.");
      assertEquals(
          1,
          history.getStart().numberOfChildren(),
          "the refused edit must not disturb the existing tree.");
      assertEquals(
          Stone.EMPTY,
          history.getStart().getData().stones[Board.getIndex(1, 1)],
          "the refused edit must not mutate the root.");
    } finally {
      env.close();
    }
  }

  @Test
  void existingRootSetupSgfIsEditableInPlace() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3]AB[aa]AW[bb]PL[W])", false);
      Lizzie.board.setHistory(history);

      assertTrue(
          Lizzie.board.setupPlaceStone(1, 1, Stone.BLACK),
          "a black setup tool should replace an existing root setup white stone.");
      assertTrue(
          Lizzie.board.setupEraseStone(0, 0),
          "the erase tool should remove an existing root setup stone.");
      assertTrue(
          Lizzie.board.setupSetSideToPlay(true),
          "side-to-play should be changeable on an existing root setup SGF.");

      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      assertEquals(Stone.EMPTY, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(1, 1)]);
      assertEquals(0, root.numberOfChildren(), "edits must not create child nodes.");

      String sgf = SGFParser.saveToString(false);
      assertTrue(sgf.contains("AB[bb]"), "edited black stone should export as AB[bb].");
      assertTrue(sgf.contains("PL[B]"), "edited side-to-play should export as PL[B].");
      assertFalse(
          sgf.contains("AW[") || sgf.contains(";B[") || sgf.contains(";W["),
          "edited setup must not drift into AW properties or ordinary moves.");

      BoardHistoryList roundTrip = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode roundTripRoot = roundTrip.getStart();
      assertEquals(Stone.BLACK, roundTripRoot.getData().stones[Board.getIndex(1, 1)]);
      assertTrue(roundTripRoot.getData().blackToPlay, "reopened SGF should restore Black to play.");
      assertEquals(0, roundTripRoot.numberOfChildren(), "reopened SGF must stay root-only.");
    } finally {
      env.close();
    }
  }
  @Test
  void currentPositionConversionPreservesSetupStateAndGameMetadata() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history =
          SGFParser.parseSgf(
              "(;SZ[3]RU[Japanese]KM[6.5]PB[Black]PW[White]RE[W+R]"
                  + ";B[aa](;W[])(;W[cc]))",
              false);
      history.toStart();
      history.next();
      history.next();
      Lizzie.board.setHistory(history);
      GameInfo gameInfo = history.getGameInfo();
      gameInfo.setKomi(6.5);
      gameInfo.setPlayerBlack("Black");
      gameInfo.setPlayerWhite("White");
      gameInfo.setResult("W+R");

      assertTrue(
          Lizzie.board.hasRealMoveOrPassHistory(),
          "the source tree should require destructive-conversion confirmation.");
      assertTrue(Lizzie.board.convertCurrentPositionToStartingPosition());

      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      assertTrue(root.getData().isSnapshotNode(), "conversion should produce a root snapshot.");
      assertEquals(0, root.numberOfChildren(), "conversion should discard moves and variations.");
      assertEquals(0, root.getData().moveNumber, "the converted root should have no move count.");
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertTrue(root.getData().blackToPlay, "conversion should preserve the displayed side-to-play.");
      assertEquals(
          "Japanese",
          root.getData().getProperty("RU"),
          "root game properties such as rules should survive conversion.");
      assertTrue(
          Lizzie.board.getHistory().getGameInfo() == gameInfo,
          "conversion should preserve the existing GameInfo object.");
      assertFalse(
          Lizzie.board.hasRealMoveOrPassHistory(),
          "the converted root-only tree should contain no real history actions.");

      String saved = SGFParser.saveToString(false);
      assertTrue(saved.contains("AB[aa]"), "the displayed black stone should save as root setup.");
      assertTrue(saved.contains("PL[B]"), "the displayed side-to-play should save as root PL.");
      assertTrue(saved.contains("RU[Japanese]"), "rules metadata should remain in the root.");
      assertTrue(saved.contains("KM[6.5]"), "komi should survive conversion.");
      assertTrue(saved.contains("PB[Black]") && saved.contains("PW[White]"));
      assertTrue(saved.contains("RE[W+R]"), "result metadata should survive conversion.");
      assertFalse(
          saved.contains(";B[") || saved.contains(";W["),
          "converted setup stones must not save as ordinary moves or passes.");

      BoardHistoryList reopened = SGFParser.parseSgf(saved, false);
      assertEquals(0, reopened.getStart().numberOfChildren(), "reopened SGF should remain root-only.");
      assertEquals(Stone.BLACK, reopened.getStart().getData().stones[Board.getIndex(0, 0)]);
      assertTrue(reopened.getStart().getData().blackToPlay);
    } finally {
      env.close();
    }
  }

  @Test
  void conversionDropsBoardFlagsDerivedOnlyFromDiscardedHistory() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3];B[aa])", false);
      history.toStart();
      history.next();
      history.getData().isKataData = true;
      Lizzie.board.setHistory(history);
      assertTrue(Lizzie.board.isKataBoard, "the source child payload should classify the board.");

      assertTrue(Lizzie.board.convertCurrentPositionToStartingPosition());

      assertFalse(
          Lizzie.board.isKataBoard,
          "conversion should rederive board flags after discarding descendant analysis.");
      assertFalse(
          SGFParser.saveToString(false).contains("DZ[G]"),
          "discarded descendant analysis must not create root Kata metadata.");
    } finally {
      env.close();
    }
  }

  @Test
  void conversionCommandConfirmsBeforeDiscardingRealHistory() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      EngineManager.isEmpty = true;
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3];B[aa];W[bb])", false);
      Lizzie.board.setHistory(history);
      TrackingFrame frame = (TrackingFrame) Lizzie.frame;
      BoardHistoryNode originalRoot = history.getStart();

      frame.confirmStartingPositionConversion = false;
      assertFalse(
          frame.convertCurrentPositionToStartingPositionCommand(),
          "canceling the warning should leave the game tree untouched.");
      assertEquals(1, frame.startingPositionConversionConfirmations);
      assertTrue(
          Lizzie.board.getHistory().getStart() == originalRoot,
          "canceling should preserve the original history identity.");
      assertTrue(originalRoot.numberOfChildren() > 0, "canceling should preserve real moves.");

      frame.confirmStartingPositionConversion = true;
      assertTrue(frame.convertCurrentPositionToStartingPositionCommand());
      assertEquals(2, frame.startingPositionConversionConfirmations);
      assertEquals(0, Lizzie.board.getHistory().getStart().numberOfChildren());

      assertTrue(
          frame.convertCurrentPositionToStartingPositionCommand(),
          "root-only positions should convert without a destructive-history warning.");
      assertEquals(
          2,
          frame.startingPositionConversionConfirmations,
          "the warning should only be requested when real MOVE/PASS history exists.");
    } finally {
      env.close();
    }
  }

  @Test
  void setupModeEntryCancellationPreservesRealHistory() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    boolean previousEmpty = EngineManager.isEmpty;
    try {
      EngineManager.isEmpty = true;
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3];B[aa];W[bb])", false);
      Lizzie.board.setHistory(history);
      BoardHistoryNode originalRoot = history.getStart();
      TrackingFrame frame = (TrackingFrame) Lizzie.frame;
      frame.confirmStartingPositionConversion = false;

      assertFalse(
          frame.enterSetupMode(),
          "canceling setup entry should not discard the existing game history.");
      assertFalse(Lizzie.board.isSetupMode(), "canceled setup entry must remain inactive.");
      assertEquals(1, frame.startingPositionConversionConfirmations);
      assertTrue(
          Lizzie.board.getHistory().getStart() == originalRoot,
          "canceling setup entry should preserve the original history identity.");
      assertTrue(originalRoot.numberOfChildren() > 0, "canceling should preserve real moves.");
    } finally {
      EngineManager.isEmpty = previousEmpty;
      env.close();
    }
  }

  @Test
  void setupModeEntryConfirmationFlattensDisplayedPosition() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    boolean previousEmpty = EngineManager.isEmpty;
    try {
      EngineManager.isEmpty = true;
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3];B[aa];W[bb])", false);
      history.toStart();
      history.next();
      history.next();
      Lizzie.board.setHistory(history);
      TrackingFrame frame = (TrackingFrame) Lizzie.frame;
      frame.confirmStartingPositionConversion = true;

      assertTrue(
          frame.enterSetupMode(),
          "confirming setup entry should flatten the displayed position and activate setup mode.");
      assertTrue(Lizzie.board.isSetupMode(), "confirmed setup entry should be active.");
      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      assertTrue(root.getData().isSnapshotNode(), "setup entry should produce a root snapshot.");
      assertEquals(0, root.numberOfChildren(), "setup entry should discard moves and variations.");
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.WHITE, root.getData().stones[Board.getIndex(1, 1)]);
      assertFalse(
          Lizzie.board.hasRealMoveOrPassHistory(),
          "the active setup tree should contain no real move/pass history.");
      assertEquals(1, frame.startingPositionConversionConfirmations);
    } finally {
      EngineManager.isEmpty = previousEmpty;
      env.close();
    }
  }

  @Test
  void setupModeEntryRejectsActiveEngine() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    boolean previousEmpty = EngineManager.isEmpty;
    try {
      EngineManager.isEmpty = false;

      assertFalse(
          Lizzie.frame.enterSetupMode(),
          "setup mode must be unavailable while a foreground engine is open.");
      assertFalse(Lizzie.board.isSetupMode(), "a rejected setup entry must leave setup inactive.");
    } finally {
      EngineManager.isEmpty = previousEmpty;
      env.close();
    }
  }

  @Test
  void passOutsideSetupModeKeepsNormalHistoryAndEnginePlay() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingLeelaz leelaz = (TrackingLeelaz) Lizzie.leelaz;

      Lizzie.board.setSetupMode(false);
      Lizzie.board.pass(Stone.BLACK);

      BoardHistoryNode pass = Lizzie.board.getHistory().getCurrentHistoryNode();
      assertTrue(pass.getData().isPassNode(), "ordinary PASS must remain a PASS history node.");
      assertFalse(pass.getData().dummy, "ordinary PASS must remain a real history action.");
      assertEquals(1, Lizzie.board.getHistory().getStart().numberOfChildren());
      assertEquals(
          List.of("BLACK pass"),
          leelaz.playedMoves,
          "ordinary PASS must retain its normal engine command.");
    } finally {
      env.close();
    }
  }

  @Test
  void setupModePassIsIgnoredWithoutHistoryOrEnginePlay() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingLeelaz leelaz = (TrackingLeelaz) Lizzie.leelaz;
      Lizzie.board.setSetupMode(true);

      Lizzie.board.pass(Stone.BLACK);

      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      assertEquals(0, root.numberOfChildren(), "setup mode PASS must not create a history child.");
      assertFalse(
          Lizzie.board.hasRealMoveOrPassHistory(),
          "setup mode PASS must not create a real history action.");
      assertEquals(
          List.of(), leelaz.playedMoves, "setup mode PASS must not send an engine play command.");
    } finally {
      env.close();
    }
  }

  @Test
  void setupModeExitResyncsFinalRootSnapshotWithoutReplayingSetupStones() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    EngineFollowController previousController = Lizzie.engineFollowController;
    try {
      RecordingEngineCommandSink sink = new RecordingEngineCommandSink();
      EngineFollowController controller = new EngineFollowController(sink);
      Lizzie.engineFollowController = controller;

      Lizzie.board.setSetupMode(true);
      Lizzie.board.setupPlaceStone(0, 0, Stone.BLACK);
      Lizzie.board.setupPlaceStone(1, 1, Stone.WHITE);
      Lizzie.board.setupSetSideToPlay(false);

      assertTrue(
          sink.calls.isEmpty(), "setup edits must not send engine commands while the mode is active.");

      BoardHistoryNode finalSetup = Lizzie.board.getHistory().getStart();
      sink.blockResync = true;
      CompletableFuture<Void> exitCall =
          CompletableFuture.runAsync(Lizzie.frame::exitSetupMode);
      try {
        assertTrue(
            sink.resyncStarted.await(1, TimeUnit.SECONDS),
            "setup-mode exit should dispatch the engine resync.");
        assertDoesNotThrow(
            () -> exitCall.get(1, TimeUnit.SECONDS),
            "setup-mode exit must not block its caller on the engine restore.");
      } finally {
        sink.allowResync.countDown();
        exitCall.get(2, TimeUnit.SECONDS);
      }
      controller.awaitIdle();

      assertFalse(Lizzie.board.isSetupMode());
      assertEquals(
          List.of("resync", "clearBestMoves"),
          sink.calls,
          "mode exit should use one history resync instead of ordinary play replay.");
      assertTrue(sink.resyncTarget == finalSetup, "the final root snapshot should be synchronized.");
      assertEquals(Stone.BLACK, sink.resyncTarget.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.WHITE, sink.resyncTarget.getData().stones[Board.getIndex(1, 1)]);
      assertFalse(
          sink.resyncTarget.getData().blackToPlay,
          "snapshot synchronization must preserve White to play.");
    } finally {
      Lizzie.engineFollowController = previousController;
      env.close();
    }
  }

  @Test
  void setupModeRoutesMainBoardLeftClickThroughSeam() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
      LizzieFrame.boardRenderer = new FakeBoardRenderer();
      try {
        Lizzie.board.setSetupMode(true);
        Lizzie.frame.setupTool = LizzieFrame.SETUP_TOOL_BLACK;
        Lizzie.frame.onClicked(0, 0);

        BoardHistoryNode root = Lizzie.board.getHistory().getStart();
        assertEquals(
            Stone.BLACK,
            root.getData().stones[Board.getIndex(1, 1)],
            "setup-mode main board click should place the selected black tool stone.");
        assertEquals(0, root.numberOfChildren(), "setup-mode click must not create move nodes.");

        Lizzie.frame.setupTool = LizzieFrame.SETUP_TOOL_WHITE;
        Lizzie.frame.onClicked(0, 0);
        assertEquals(
            Stone.WHITE,
            root.getData().stones[Board.getIndex(1, 1)],
            "a white tool click should replace the black setup stone in place.");
        assertEquals(0, root.numberOfChildren(), "replacement must stay root-only.");
      } finally {
        LizzieFrame.boardRenderer = previousRenderer;
      }
    } finally {
      env.close();
    }
  }

  @Test
  void setupModeRoutesMainBoardRightClickThroughSeam() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
      LizzieFrame.boardRenderer = new FakeBoardRenderer();
      try {
        Lizzie.board.setSetupMode(true);
        Lizzie.frame.setupTool = LizzieFrame.SETUP_TOOL_BLACK;
        assertTrue(
            Lizzie.frame.onClickedRight(0, 0),
            "setup-mode right click should be consumed by the setup seam.");

        BoardHistoryNode root = Lizzie.board.getHistory().getStart();
        assertEquals(
            Stone.WHITE,
            root.getData().stones[Board.getIndex(1, 1)],
            "right click with the black tool should place the opposite white setup stone.");
        assertEquals(0, root.numberOfChildren(), "setup-mode right click must stay root-only.");
      } finally {
        LizzieFrame.boardRenderer = previousRenderer;
      }
    } finally {
      env.close();
    }
  }

  @Test
  void activeHumanSlGameRoutingPrecedesStaleSetupMode() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
      LizzieFrame.boardRenderer = new FakeBoardRenderer();
      Lizzie.frame.humanSlGame = allocate(HumanSlGameController.class);
      try {
        Lizzie.board.setSetupMode(true);
        Lizzie.frame.setupTool = LizzieFrame.SETUP_TOOL_BLACK;
        Lizzie.frame.onClicked(0, 0);

        BoardHistoryNode root = Lizzie.board.getHistory().getStart();
        assertEquals(
            Stone.EMPTY,
            root.getData().stones[Board.getIndex(1, 1)],
            "an active HumanSL game must keep a stale setup-mode flag from rewriting its frozen root.");
        assertEquals(
            0,
            root.numberOfChildren(),
            "the rejected HumanSL click must not create a move or a setup mutation.");

        assertTrue(
            Lizzie.frame.onClickedRight(0, 0),
            "an active HumanSL game must consume right clicks on the owned board.");
        assertEquals(
            Stone.EMPTY,
            root.getData().stones[Board.getIndex(1, 1)],
            "a right click must not bypass HumanSL ownership through the setup seam.");
        assertEquals(0, root.numberOfChildren(), "the right click must not create a move node.");
      } finally {
        Lizzie.frame.humanSlGame = null;
        LizzieFrame.boardRenderer = previousRenderer;
      }
    } finally {
      env.close();
    }
  }

  @Test
  void activeHumanSlGameOwnsInputMousePressBeforeStaleSetupMode() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    boolean previousTempDrag = Input.tempDrag;
    try {
      BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
      LizzieFrame.boardRenderer = new FakeBoardRenderer();
      Lizzie.frame.humanSlGame = allocate(HumanSlGameController.class);
      try {
        Lizzie.board.setSetupMode(true);
        Lizzie.frame.setupTool = LizzieFrame.SETUP_TOOL_BLACK;
        Input.tempDrag = true;
        Input input = new Input();
        javax.swing.JComponent source = new javax.swing.JPanel();

        input.mousePressed(
            new MouseEvent(
                source,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                0,
                10,
                10,
                1,
                false,
                MouseEvent.BUTTON1));

        BoardHistoryNode root = Lizzie.board.getHistory().getStart();
        assertFalse(Input.tempDrag, "HumanSL routing must cancel stale drag ownership.");
        assertEquals(
            Stone.EMPTY,
            root.getData().stones[Board.getIndex(1, 1)],
            "the real left-button input path must not rewrite HumanSL's frozen root.");
        assertEquals(0, root.numberOfChildren(), "the left press must not create a move node.");

        input.mousePressed(
            new MouseEvent(
                source,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                0,
                10,
                10,
                1,
                false,
                MouseEvent.BUTTON3));

        assertEquals(
            Stone.EMPTY,
            root.getData().stones[Board.getIndex(1, 1)],
            "the real right-button input path must not enter the setup seam.");
        assertEquals(0, root.numberOfChildren(), "the right press must not create a move node.");
      } finally {
        Lizzie.frame.humanSlGame = null;
        LizzieFrame.boardRenderer = previousRenderer;
      }
    } finally {
      Input.tempDrag = previousTempDrag;
      env.close();
    }
  }

  @Test
  void setupModeInputMousePressedRoutesThroughSeam() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
      LizzieFrame.boardRenderer = new FakeBoardRenderer();
      try {
        Lizzie.board.setSetupMode(true);
        Lizzie.frame.setupTool = LizzieFrame.SETUP_TOOL_BLACK;
        Input.tempDrag = true;
        Input input = new Input();
        javax.swing.JComponent source = new javax.swing.JPanel();
        MouseEvent press =
            new MouseEvent(
                source,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                0,
                10,
                10,
                1,
                false,
                MouseEvent.BUTTON1);
        input.mousePressed(press);
        assertFalse(
            Input.tempDrag,
            "setup-mode interception must cancel a pending drag so it cannot fire after exit.");
        Input.tempDrag = false;

        BoardHistoryNode root = Lizzie.board.getHistory().getStart();
        assertEquals(
            Stone.BLACK,
            root.getData().stones[Board.getIndex(1, 1)],
            "setup-mode mouse press should route through the setup seam.");
        assertEquals(0, root.numberOfChildren(), "setup-mode mouse press must stay root-only.");
      } finally {
        LizzieFrame.boardRenderer = previousRenderer;
      }
    } finally {
      env.close();
    }
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Menu previousMenu;
    private final WinrateGraph previousWinrateGraph;
    private final Leelaz previousLeelaz;
    private final Leelaz previousLeelaz2;
    private final EngineManager previousEngineManager;
    private final int previousCurrentEngineNo;
    private final int previousCurrentEngineNo2;
    private final boolean previousEngineEmpty;
    private final Config previousConfig;
    private final EngineFollowController previousEngineFollowController;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Board previousBoard,
        LizzieFrame previousFrame,
        Menu previousMenu,
        WinrateGraph previousWinrateGraph,
        Leelaz previousLeelaz,
        Leelaz previousLeelaz2,
        EngineManager previousEngineManager,
        int previousCurrentEngineNo,
        int previousCurrentEngineNo2,
        boolean previousEngineEmpty,
        Config previousConfig,
        EngineFollowController previousEngineFollowController) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousMenu = previousMenu;
      this.previousWinrateGraph = previousWinrateGraph;
      this.previousLeelaz = previousLeelaz;
      this.previousLeelaz2 = previousLeelaz2;
      this.previousEngineManager = previousEngineManager;
      this.previousCurrentEngineNo = previousCurrentEngineNo;
      this.previousCurrentEngineNo2 = previousCurrentEngineNo2;
      this.previousEngineEmpty = previousEngineEmpty;
      this.previousConfig = previousConfig;
      this.previousEngineFollowController = previousEngineFollowController;
    }

    private static TestEnvironment open() throws Exception {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Board previousBoard = Lizzie.board;
      LizzieFrame previousFrame = Lizzie.frame;
      Menu previousMenu = LizzieFrame.menu;
      WinrateGraph previousWinrateGraph = LizzieFrame.winrateGraph;
      Leelaz previousLeelaz = Lizzie.leelaz;
      Leelaz previousLeelaz2 = Lizzie.leelaz2;
      EngineManager previousEngineManager = Lizzie.engineManager;
      int previousCurrentEngineNo = EngineManager.currentEngineNo;
      int previousCurrentEngineNo2 = EngineManager.currentEngineNo2;
      boolean previousEngineEmpty = EngineManager.isEmpty;
      Config previousConfig = Lizzie.config;
      EngineFollowController previousEngineFollowController = Lizzie.engineFollowController;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      TrackingBoard board = allocate(TrackingBoard.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.movelistwr = new ArrayList<>();
      board.setHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      Lizzie.board = board;
      Lizzie.frame = allocate(TrackingFrame.class);
      LizzieFrame.menu = allocate(Menu.class);
      LizzieFrame.menu.txtKomi = new javax.swing.JTextField();
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      EngineManager fixtureEngineManager = createFixtureEngineManager(leelaz);
      // Ordinary board forwarding is gated by this complete foreground-engine authority tuple.
      EngineManager.currentEngineNo = 0;
      EngineManager.currentEngineNo2 = -1;
      EngineManager.isEmpty = false;
      Lizzie.setPrimaryEngine(leelaz);
      Lizzie.setEngineManager(fixtureEngineManager);
      Lizzie.leelaz2 = null;
      Config config = allocate(Config.class);
      config.newMoveNumberInBranch = false;
      config.playSound = false;
      Lizzie.engineFollowController = null;
      config.initialMaxScoreLead = 10;
      Lizzie.config = config;
      LizzieFrame.winrateGraph = allocate(WinrateGraph.class);
      return new TestEnvironment(
          previousBoardWidth,
          previousBoardHeight,
          previousBoard,
          previousFrame,
          previousMenu,
          previousWinrateGraph,
          previousLeelaz,
          previousLeelaz2,
          previousEngineManager,
          previousCurrentEngineNo,
          previousCurrentEngineNo2,
          previousEngineEmpty,
          previousConfig,
          previousEngineFollowController);
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      LizzieFrame.winrateGraph = previousWinrateGraph;
      Lizzie.setPrimaryEngine(previousLeelaz);
      Lizzie.setEngineManager(previousEngineManager);
      Lizzie.leelaz2 = previousLeelaz2;
      EngineManager.currentEngineNo = previousCurrentEngineNo;
      EngineManager.currentEngineNo2 = previousCurrentEngineNo2;
      EngineManager.isEmpty = previousEngineEmpty;
      Lizzie.config = previousConfig;
      Lizzie.engineFollowController = previousEngineFollowController;
    }
  }

  private static EngineManager createFixtureEngineManager(Leelaz primaryEngine) throws Exception {
    java.lang.reflect.Constructor<EngineManager> constructor =
        EngineManager.class.getDeclaredConstructor(List.class);
    constructor.setAccessible(true);
    return constructor.newInstance(new ArrayList<>(List.of(primaryEngine)));
  }

  private static final class FakeBoardRenderer extends BoardRenderer {
    private FakeBoardRenderer() {
      super(false);
    }

    @Override
    public Optional<int[]> convertScreenToCoordinates(int x, int y) {
      return Optional.of(new int[] {1, 1});
    }
  }

  private static final class RecordingEngineCommandSink implements EngineCommandSink {
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile BoardHistoryNode resyncTarget;
    private final CountDownLatch resyncStarted = new CountDownLatch(1);
    private final CountDownLatch allowResync = new CountDownLatch(1);
    private volatile boolean blockResync;

    @Override
    public void playMove(Stone color, String coord) {
      calls.add("play");
    }

    @Override
    public void undo() {
      calls.add("undo");
    }

    @Override
    public void clear() {
      calls.add("clear");
    }

    @Override
    public void clearBestMoves() {
      calls.add("clearBestMoves");
    }

    @Override
    public void resyncFromCurrentHistory(BoardHistoryNode target) {
      calls.add("resync");
      resyncTarget = target;
      resyncStarted.countDown();
      if (blockResync) {
        try {
          allowResync.await();
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Interrupted while blocking test resync", ex);
        }
      }
    }
  }

  private static final class TrackingBoard extends Board {
    private TrackingBoard() {
      super();
    }

    @Override
    public void clear(boolean isEngineGame) {
      if (startStonelist == null) {
        startStonelist = new ArrayList<>();
      } else {
        startStonelist.clear();
      }
      if (movelistwr == null) {
        movelistwr = new ArrayList<>();
      } else {
        movelistwr.clear();
      }
      hasStartStone = false;
      setHistory(new BoardHistoryList(BoardData.empty(Board.boardWidth, Board.boardHeight)));
    }

    @Override
    public void clearforedit() {
      clear(false);
    }

    @Override
    public void clearAfterMove() {}
  }

  private static final class TrackingFrame extends LizzieFrame {
    private boolean confirmStartingPositionConversion;
    private int startingPositionConversionConfirmations;

    private TrackingFrame() {
      super();
    }

    @Override
    public void requestProblemListRefresh() {}

    @Override
    public void refreshProblemListSnapshot() {}

    @Override
    public void refresh() {}

    @Override
    public void setPlayers(String whitePlayer, String blackPlayer) {}

    @Override
    public void resetTitle() {}

    @Override
    public void tryToResetByoTime() {}

    @Override
    protected boolean confirmStartingPositionConversion() {
      startingPositionConversionConfirmations++;
      return confirmStartingPositionConversion;
    }
  }

  private static final class TrackingLeelaz extends Leelaz {
    private final List<String> playedMoves = new CopyOnWriteArrayList<>();

    private TrackingLeelaz() throws IOException {
      super("");
    }

    @Override
    public void clear() {}

    @Override
    public void clearBestMoves() {}

    @Override
    public void maybeAjustPDA(BoardHistoryNode node) {}

    @Override
    public void playMove(Stone color, String move) {
      playedMoves.add(color + " " + move);
    }
    @Override
    public boolean isLoaded() {
      return true;
    }

    @Override
    public boolean isStarted() {
      return true;
    }

    @Override
    public void playMove(Stone color, String move, boolean addPlayer, boolean blackToPlay) {
      playedMoves.add(color + " " + move);
    }

    @Override
    public void loadSgf(Path sgfFile) {}

    @Override
    public void sendCommand(String command) {}
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    if (Leelaz.class.isAssignableFrom(type)) {
      java.lang.reflect.Constructor<T> constructor = type.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    }
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
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
