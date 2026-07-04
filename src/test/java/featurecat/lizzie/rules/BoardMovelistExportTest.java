package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoardMovelistExportTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void staticSnapshotRootMarkerDoesNotExportAsHistoryMove() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(new BoardHistoryList(staticSnapshotRoot()));

      assertTrue(
          board.getallmovelist().isEmpty(), "all movelist should skip static snapshot roots.");
      assertTrue(
          board.getmovelistForSaveLoad().isEmpty(),
          "save/load movelist should skip static snapshot roots.");
      assertTrue(
          board.getmovelistWithOutStartStone().isEmpty(),
          "movelist without start stones should skip static snapshot roots.");
      assertTrue(
          board.getmovelist(Optional.of(board.getHistory().getCurrentHistoryNode())).isEmpty(),
          "node-specific movelist should skip static snapshot roots.");
    } finally {
      env.close();
    }
  }

  @Test
  void normalHistoryMoveStillExports() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(regularMoveNode());
      board.setHistory(history);

      ArrayList<Movelist> movelist = board.getallmovelist();

      assertEquals(1, movelist.size(), "regular history should still export the played move.");
      assertEquals(1, movelist.get(0).x, "exported move should keep its coordinates.");
      assertEquals(1, movelist.get(0).y, "exported move should keep its coordinates.");
      assertEquals(1, movelist.get(0).movenum, "exported move should keep its move number.");
    } finally {
      env.close();
    }
  }

  @Test
  void snapshotNodesAreSkippedButRealPassesRemainAcrossExportHelpers() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(snapshotNode(Optional.of(new int[] {2, 2}), Stone.BLACK, true, 1));
      history.getCurrentHistoryNode().addExtraStones(0, 2, true);
      history.getCurrentHistoryNode().addExtraStones(2, 0, false);
      history.add(passNode(Stone.WHITE, false, 2));
      board.setHistory(history);

      assertPassOnly(board.getallmovelist(), "all movelist should keep only the real pass.");
      assertPassOnly(
          board.getmovelistForSaveLoad(), "save/load movelist should keep only the real pass.");
      assertPassOnly(
          board.getmovelistWithOutStartStone(),
          "movelist without start stones should keep only the real pass.");
      assertPassOnly(
          board.getmovelist(Optional.of(board.getHistory().getCurrentHistoryNode())),
          "node-specific movelist should keep only the real pass.");

      board.addStartListAll();
      assertEquals(1, board.startStonelist.size(), "start-list replay should skip snapshots.");
      assertTrue(board.startStonelist.get(0).ispass, "start-list replay should keep passes.");

      assertEquals(
          List.of("WHITE:pass"),
          flattenAllMovelist(board.getAllMovelist(0)),
          "all-movelist tree should skip snapshots and keep the real pass.");
    } finally {
      env.close();
    }
  }

  @Test
  void restoreMoveNumberSendsLeadingPassEvenWhenSnapshotPrecedesIt() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(snapshotNode(Optional.of(new int[] {1, 1}), Stone.BLACK, true, 1));
      history.add(passNode(Stone.WHITE, false, 2));
      board.setHistory(history);

      TrackingLeelaz engine = allocate(TrackingLeelaz.class);
      board.restoreMoveNumber(board.getMoveList(), false, engine, false);

      assertEquals(
          List.of("play W pass"),
          engine.commands,
          "engine restore should emit only the real pass and ignore the snapshot.");
    } finally {
      env.close();
    }
  }

  @Test
  void resendMoveToEngineLoadEngineRestoresEditedCurrentMoveAsStaticBoard() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    Leelaz previousLeelaz = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Menu previousMenu = LizzieFrame.menu;
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(moveNode(0, 0, Stone.BLACK, false, 1));
      board.setHistory(history);
      Lizzie.board = board;
      Lizzie.config = minimalConfig();
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      Lizzie.frame = allocate(TrackingFrame.class);
      LizzieFrame.menu = allocate(TrackingMenu.class);

      board.removeStone(0, 0, Stone.BLACK);

      RuleAwareFakeLeelaz engine = allocate(RuleAwareFakeLeelaz.class);
      board.resendMoveToEngine(engine, true);

      assertEquals("clear_board", engine.recordedCommands().get(0));
      assertTrue(
          engine.recordedCommands().get(1).startsWith("loadsgf "),
          "load-engine resend should land the edited current board through exact snapshot restore.");
      assertEquals("time_settings 0 0 1", engine.recordedCommands().get(2));
      assertArrayEquals(
          board.getHistory().getCurrentHistoryNode().getData().stones,
          engine.copyStones(),
          "load-engine resend should restore the edited current board exactly.");
      assertEquals(
          board.getHistory().getCurrentHistoryNode().getData().blackToPlay,
          engine.isBlackToPlay(),
          "load-engine resend should preserve the edited current side to play.");
      assertTempFileEventuallyDeleted(
          engine.lastLoadedSgf(),
          "load-engine resend should clean up the temporary SGF file after exact restore.");
    } finally {
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      env.close();
    }
  }

  @Test
  void restoreMoveNumberLoadEngineRestoresEditedCurrentMoveAsStaticBoard() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    Leelaz previousLeelaz = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Menu previousMenu = LizzieFrame.menu;
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(moveNode(0, 0, Stone.BLACK, false, 1));
      board.setHistory(history);
      Lizzie.board = board;
      Lizzie.config = minimalConfig();
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      Lizzie.frame = allocate(TrackingFrame.class);
      LizzieFrame.menu = allocate(TrackingMenu.class);

      board.removeStone(0, 0, Stone.BLACK);

      RuleAwareFakeLeelaz engine = allocate(RuleAwareFakeLeelaz.class);
      board.restoreMoveNumber(board.getMoveList(), false, engine, true);

      assertEquals("clear_board", engine.recordedCommands().get(0));
      assertTrue(
          engine.recordedCommands().get(1).startsWith("loadsgf "),
          "load-engine restore should land the edited current board through exact snapshot restore.");
      assertEquals("time_settings 0 0 1", engine.recordedCommands().get(2));
      assertArrayEquals(
          board.getHistory().getCurrentHistoryNode().getData().stones,
          engine.copyStones(),
          "load-engine restore should restore the edited current board exactly.");
      assertEquals(
          board.getHistory().getCurrentHistoryNode().getData().blackToPlay,
          engine.isBlackToPlay(),
          "load-engine restore should preserve the edited current side to play.");
    } finally {
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      env.close();
    }
  }

  @Test
  void exactSnapshotRestoreDeletesTempFileWhenLoadSgfThrows() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      ThrowingLoadSgfLeelaz engine = allocate(ThrowingLoadSgfLeelaz.class);

      assertThrows(
          IllegalStateException.class,
          () ->
              SnapshotEngineRestore.restoreExactSnapshotIfNeeded(
                  engine, snapshotRootNeedingBookkeepingPass()));

      assertTempFileEventuallyDeleted(
          engine.lastAttemptedSgf(),
          "failed exact restores should still clean up the temporary SGF file.");
    } finally {
      env.close();
    }
  }

  @Test
  void exactSnapshotRestoreKeepsTempFileUntilDelayedLoadSgfConsumptionFinishes() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      DelayedLoadSgfLeelaz engine = allocate(DelayedLoadSgfLeelaz.class);
      engine.readyToConsume = new CountDownLatch(1);
      engine.allowConsume = new CountDownLatch(1);
      engine.consumed = new CountDownLatch(1);

      SnapshotEngineRestore.restoreExactSnapshotIfNeeded(
          engine, snapshotRootNeedingBookkeepingPass());

      assertTrue(
          engine.awaitReadyToConsume(),
          "exact snapshot restore should keep the temp SGF alive while the delayed consumer is waiting to read it.");
      assertTrue(
          Files.exists(engine.pendingSgf()),
          "the temp SGF should remain on disk until the delayed consumer is allowed to read it.");
      engine.allowConsume();
      assertTrue(
          engine.awaitConsumption(),
          "exact snapshot restore should keep the temp SGF alive until a delayed loadsgf consumer reads it.");
      assertTrue(
          engine.fileExistedDuringConsumption(),
          "the delayed loadsgf consumer should still see the temp SGF on disk.");
      assertTempFileEventuallyDeleted(
          engine.lastConsumedSgf(),
          "exact snapshot restore should still clean up the temporary SGF after delayed consumption.");
    } finally {
      env.close();
    }
  }

  @Test
  void resendMoveToEngineReplaysSnapshotRootBoardStateAndTurn() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Board previousBoard = Lizzie.board;
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(new BoardHistoryList(snapshotRootNeedingBookkeepingPass()));
      Lizzie.board = board;

      RuleAwareFakeLeelaz engine = allocate(RuleAwareFakeLeelaz.class);
      board.resendMoveToEngine(engine, false);

      assertEquals(
          List.of("clear_board"),
          engine.recordedCommands().subList(0, 1),
          "snapshot-root resend should still clear the engine before exact restore.");
      assertTrue(
          engine.recordedCommands().get(1).startsWith("loadsgf "),
          "snapshot-root resend should restore ordinary static snapshots through loadsgf.");
      assertArrayEquals(
          board.getHistory().getCurrentHistoryNode().getData().stones,
          engine.copyStones(),
          "snapshot-root resend should restore the exact snapshot board.");
      assertEquals(
          board.getHistory().getCurrentHistoryNode().getData().blackToPlay,
          engine.isBlackToPlay(),
          "snapshot-root resend should preserve the snapshot side to play.");
      assertTempFileEventuallyDeleted(
          engine.lastLoadedSgf(),
          "snapshot-root resend should clean up the temporary SGF file after exact restore.");
    } finally {
      Lizzie.board = previousBoard;
      env.close();
    }
  }

  @Test
  void resendMoveToEngineUsesSnapshotHistoryBoardSizeWhenGlobalBoardSizeDiffers() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Board previousBoard = Lizzie.board;
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(new BoardHistoryList(rectangularSnapshotRoot()));
      Lizzie.board = board;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      SnapshotSgfAwareFakeLeelaz engine = allocate(SnapshotSgfAwareFakeLeelaz.class);
      board.resendMoveToEngine(engine, false);

      BoardData snapshot = board.getHistory().getCurrentHistoryNode().getData();
      assertEquals(
          List.of("clear_board"),
          engine.recordedCommands().subList(0, 1),
          "exact restore should still clear engine before loading snapshot SGF.");
      assertTrue(
          engine.recordedCommands().get(1).startsWith("loadsgf "),
          "exact restore should still restore snapshot through loadsgf.");
      assertEquals(5, engine.loadedBoardWidth(), "snapshot restore should use history width.");
      assertEquals(7, engine.loadedBoardHeight(), "snapshot restore should use history height.");
      assertTrue(
          engine.loadedSgf().contains("SZ[5:7]"),
          "temporary SGF should materialize the 5x7 SZ from history.");
      assertTrue(
          engine.loadedSgf().contains("AW[eg]"),
          "temporary SGF should encode coordinates against the 5x7 history board.");
      assertArrayEquals(
          snapshot.stones,
          engine.copyStones(),
          "fake engine should restore the exact 5x7 snapshot stones even when global size is 3x3.");
      assertEquals(
          snapshot.blackToPlay,
          engine.isBlackToPlay(),
          "fake engine should restore snapshot side-to-play exactly.");
    } finally {
      Lizzie.board = previousBoard;
      env.close();
    }
  }

  @Test
  void restoreMoveNumberLoadEngineUsesSnapshotHistoryBoardSizeWhenGlobalBoardSizeDiffers()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    Leelaz previousLeelaz = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    Menu previousMenu = LizzieFrame.menu;
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(new BoardHistoryList(rectangularSnapshotRoot()));
      Lizzie.board = board;
      Lizzie.config = minimalConfig();
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      Lizzie.frame = allocate(TrackingFrame.class);
      LizzieFrame.menu = allocate(TrackingMenu.class);

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      SnapshotSgfAwareFakeLeelaz engine = allocate(SnapshotSgfAwareFakeLeelaz.class);
      board.restoreMoveNumber(board.getMoveList(), false, engine, true);

      BoardData snapshot = board.getHistory().getCurrentHistoryNode().getData();
      assertEquals(
          List.of("clear_board"),
          engine.recordedCommands().subList(0, 1),
          "load-engine restore should still clear engine before loading snapshot SGF.");
      assertTrue(
          engine.recordedCommands().get(1).startsWith("loadsgf "),
          "load-engine restore should still restore snapshot through loadsgf.");
      assertEquals("time_settings 0 0 1", engine.recordedCommands().get(2));
      assertEquals(5, engine.loadedBoardWidth(), "snapshot restore should use history width.");
      assertEquals(7, engine.loadedBoardHeight(), "snapshot restore should use history height.");
      assertTrue(
          engine.loadedSgf().contains("SZ[5:7]"),
          "temporary SGF should materialize the 5x7 SZ from history.");
      assertTrue(
          engine.loadedSgf().contains("AW[eg]"),
          "temporary SGF should encode coordinates against the 5x7 history board.");
      assertArrayEquals(
          snapshot.stones,
          engine.copyStones(),
          "fake engine should restore the exact 5x7 snapshot stones even when global size is 3x3.");
      assertEquals(
          snapshot.blackToPlay,
          engine.isBlackToPlay(),
          "fake engine should restore snapshot side-to-play exactly.");
    } finally {
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      env.close();
    }
  }

  @Test
  void resendMoveToEngineUsesSnapshotBoardSizeWhenSnapshotSzIsStaleAndGlobalShrinks()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Board previousBoard = Lizzie.board;
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(new BoardHistoryList(rectangularSnapshotRootWithStaleSzTag()));
      Lizzie.board = board;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      SnapshotSgfAwareFakeLeelaz engine = allocate(SnapshotSgfAwareFakeLeelaz.class);
      board.resendMoveToEngine(engine, false);

      BoardData snapshot = board.getHistory().getCurrentHistoryNode().getData();
      assertEquals(5, engine.loadedBoardWidth(), "snapshot restore should keep history width.");
      assertEquals(7, engine.loadedBoardHeight(), "snapshot restore should keep history height.");
      assertTrue(
          engine.loadedSgf().contains("SZ[5:7]"),
          "temporary SGF should materialize the 5x7 board size from snapshot stones.");
      assertTrue(
          engine.loadedSgf().contains("AW[eg]"),
          "temporary SGF should encode coordinates against the 5x7 history board.");
      assertArrayEquals(
          snapshot.stones,
          engine.copyStones(),
          "fake engine should restore the 5x7 snapshot stones after global size shrinks.");
      assertEquals(
          snapshot.blackToPlay,
          engine.isBlackToPlay(),
          "fake engine should keep snapshot side-to-play after global size shrinks.");
    } finally {
      Lizzie.board = previousBoard;
      env.close();
    }
  }

  @Test
  void resendMoveToEngineUsesHistoryBoardSizeWhenSnapshotSzIsMissingOnWideRectBoard()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Board previousBoard = Lizzie.board;
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(wideRectHistoryWithSnapshotMissingSz());
      Lizzie.board = board;

      SnapshotSgfAwareFakeLeelaz engine = allocate(SnapshotSgfAwareFakeLeelaz.class);
      board.resendMoveToEngine(engine, false);

      BoardData snapshot = board.getHistory().getCurrentHistoryNode().getData();
      assertEquals(7, engine.loadedBoardWidth(), "snapshot restore should keep 7x5 history width.");
      assertEquals(
          5, engine.loadedBoardHeight(), "snapshot restore should keep 7x5 history height.");
      assertTrue(
          engine.loadedSgf().contains("SZ[7:5]"),
          "temporary SGF should keep the 7x5 board size when snapshot SZ is missing.");
      assertTrue(
          engine.loadedSgf().contains("AW[ge]"),
          "temporary SGF should keep the wide-board coordinate mapping when snapshot SZ is missing.");
      assertArrayEquals(
          snapshot.stones,
          engine.copyStones(),
          "fake engine should restore the exact 7x5 snapshot stones.");
      assertEquals(
          snapshot.blackToPlay,
          engine.isBlackToPlay(),
          "fake engine should restore snapshot side-to-play on 7x5 boards.");
    } finally {
      Lizzie.board = previousBoard;
      env.close();
    }
  }

  @Test
  void resendMoveToEngineReplaysFromNearestSnapshotBoundary() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Board previousBoard = Lizzie.board;
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(moveNode(0, 0, Stone.BLACK, false, 1));
      history.add(moveNode(1, 0, Stone.WHITE, true, 2));
      history.add(midgameSnapshotAnchor());
      history.add(moveNode(0, 1, Stone.WHITE, true, 3));
      board.setHistory(history);
      Lizzie.board = board;

      RuleAwareFakeLeelaz engine = allocate(RuleAwareFakeLeelaz.class);
      board.resendMoveToEngine(engine, false);

      assertEquals(
          List.of("clear_board", "play W " + Board.convertCoordinatesToName(0, 1)),
          List.of(engine.recordedCommands().get(0), engine.recordedCommands().get(2)),
          "resendMoveToEngine should replay only later real actions after the exact snapshot restore.");
      assertTrue(
          engine.recordedCommands().get(1).startsWith("loadsgf "),
          "nearest-snapshot resend should restore the static anchor through loadsgf.");
    } finally {
      Lizzie.board = previousBoard;
      env.close();
    }
  }

  @Test
  void resendMoveToEnginePrefersNearestSetupSnapshotAfterRootSetup() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Board previousBoard = Lizzie.board;
    Leelaz previousLeelaz = Lizzie.leelaz;
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      Board board = allocate(TrackingBoard.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      Lizzie.board = board;
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      Lizzie.config = minimalConfig();
      Lizzie.frame = allocate(LizzieFrame.class);

      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3]AB[aa]AW[ca];B[ba]AB[bb];W[])", false);
      while (history.next().isPresent()) {}
      board.setHistory(history);

      RuleAwareFakeLeelaz engine = allocate(RuleAwareFakeLeelaz.class);
      board.resendMoveToEngine(engine, false);

      assertFalse(board.hasStartStone, "root setup should stay on the snapshot path.");
      assertEquals(
          List.of("clear_board", "play W pass"),
          List.of(engine.recordedCommands().get(0), engine.recordedCommands().get(2)),
          "resendMoveToEngine should replay only later real actions after the setup snapshot restore.");
      assertTrue(
          engine.recordedCommands().get(1).startsWith("loadsgf "),
          "setup snapshot resend should restore the static anchor through loadsgf.");
      assertArrayEquals(
          board.getHistory().getCurrentHistoryNode().getData().stones,
          engine.copyStones(),
          "setup snapshot resend should match the current board after replay.");
    } finally {
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      env.close();
    }
  }

  @Test
  void resendMoveToEngineLoadEngineRebuildsFromRemovedSetupSnapshotBoundary() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    Leelaz previousLeelaz = Lizzie.leelaz;
    Menu previousMenu = LizzieFrame.menu;
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(removedSetupSnapshotAnchor());
      history.getCurrentHistoryNode().setRemovedStone();
      history.add(passNode(Stone.BLACK, false, 3));
      board.setHistory(history);
      Lizzie.board = board;
      Lizzie.config = minimalConfig();
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      LizzieFrame.menu = allocate(TrackingMenu.class);

      RuleAwareFakeLeelaz engine = allocate(RuleAwareFakeLeelaz.class);
      board.resendMoveToEngine(engine, true);

      assertEquals(
          List.of("clear_board", "play B pass", "time_settings 0 0 1"),
          List.of(
              engine.recordedCommands().get(0),
              engine.recordedCommands().get(2),
              engine.recordedCommands().get(3)),
          "load-engine replay should replay only later real actions after the removed-stone snapshot restore.");
      assertTrue(
          engine.recordedCommands().get(1).startsWith("loadsgf "),
          "load-engine replay should restore the removed-stone setup snapshot through loadsgf.");
    } finally {
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousLeelaz;
      LizzieFrame.menu = previousMenu;
      env.close();
    }
  }

  @Test
  void resendMoveToEngineLoadEngineUsesNearestRemovedSetupSnapshotBoundary() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    Leelaz previousLeelaz = Lizzie.leelaz;
    Menu previousMenu = LizzieFrame.menu;
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(moveNode(0, 0, Stone.BLACK, false, 1));
      history.add(moveNode(1, 0, Stone.WHITE, true, 2));
      history.add(removedMidgameSetupSnapshotAnchor());
      history.getCurrentHistoryNode().setRemovedStone();
      history.add(moveNode(0, 1, Stone.WHITE, true, 3));
      board.setHistory(history);
      Lizzie.board = board;
      Lizzie.config = minimalConfig();
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      LizzieFrame.menu = allocate(TrackingMenu.class);

      RuleAwareFakeLeelaz engine = allocate(RuleAwareFakeLeelaz.class);
      board.resendMoveToEngine(engine, true);

      assertEquals(
          List.of(
              "clear_board",
              "play W " + Board.convertCoordinatesToName(0, 1),
              "time_settings 0 0 1"),
          List.of(
              engine.recordedCommands().get(0),
              engine.recordedCommands().get(2),
              engine.recordedCommands().get(3)),
          "load-engine replay should replay only later real actions after the nearest removed-stone snapshot restore.");
      assertTrue(
          engine.recordedCommands().get(1).startsWith("loadsgf "),
          "load-engine replay should restore the nearest removed-stone snapshot through loadsgf.");
    } finally {
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousLeelaz;
      LizzieFrame.menu = previousMenu;
      env.close();
    }
  }

  @Test
  void resendMoveToEngineKeepsExactSnapshotBoardForStaticCaptureShapes() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Board previousBoard = Lizzie.board;
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.setHistory(new BoardHistoryList(capturedCenterSnapshotAnchor()));
      Lizzie.board = board;

      RuleAwareFakeLeelaz engine = allocate(RuleAwareFakeLeelaz.class);
      board.resendMoveToEngine(engine, false);

      assertArrayEquals(
          board.getHistory().getCurrentHistoryNode().getData().stones,
          engine.copyStones(),
          "static snapshot restore should keep the exact snapshot board in the engine.");
      assertEquals(
          board.getHistory().getCurrentHistoryNode().getData().blackToPlay,
          engine.isBlackToPlay(),
          "static snapshot restore should keep the snapshot side to play.");
    } finally {
      Lizzie.board = previousBoard;
      env.close();
    }
  }

  @Test
  void resendMoveToEngineSnapshotRestoreUsesCurrentEngineKomiNotLoadedGameInfoKomi()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Board previousBoard = Lizzie.board;
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardData snapshot = capturedCenterSnapshotAnchor();
      snapshot.komi = 7.5;
      BoardHistoryList history = new BoardHistoryList(snapshot);
      history.getGameInfo().setKomiNoMenu(7.5);
      board.setHistory(history);
      Lizzie.board = board;

      SnapshotSgfAwareFakeLeelaz engine = allocate(SnapshotSgfAwareFakeLeelaz.class);
      engine.komi = 6.5f;
      engine.orikomi = 6.5f;
      board.resendMoveToEngine(engine, false);

      assertTrue(
          engine.loadedSgf().contains("KM[6.5]"),
          "snapshot restore should keep the current engine komi in KataGo loadsgf.");
      assertFalse(
          engine.loadedSgf().contains("KM[7.5]"),
          "loaded SGF/game-info komi must not overwrite the current engine komi.");
    } finally {
      Lizzie.board = previousBoard;
      env.close();
    }
  }

  @Test
  void restoreMoveNumberLoadEngineRebuildsNearestSnapshotBoundaryBeforeRealPass() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Board previousBoard = Lizzie.board;
    Config previousConfig = Lizzie.config;
    Leelaz previousLeelaz = Lizzie.leelaz;
    Menu previousMenu = LizzieFrame.menu;
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardData snapshot = capturedCenterSnapshotAnchor();
      BoardHistoryList history = new BoardHistoryList(snapshot);
      history.add(passNode(snapshot.stones.clone(), Stone.BLACK, false, 1));
      board.setHistory(history);
      Lizzie.board = board;
      Lizzie.config = minimalConfig();
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      LizzieFrame.menu = allocate(TrackingMenu.class);

      RuleAwareFakeLeelaz engine = allocate(RuleAwareFakeLeelaz.class);
      board.restoreMoveNumber(board.getMoveList(), false, engine, true);

      assertArrayEquals(
          board.getHistory().getCurrentHistoryNode().getData().stones,
          engine.copyStones(),
          "load-engine restore should rebuild the nearest snapshot board before replaying real actions.");
      assertEquals(
          board.getHistory().getCurrentHistoryNode().getData().blackToPlay,
          engine.isBlackToPlay(),
          "load-engine restore should keep the side to play after replaying later real actions.");
    } finally {
      Lizzie.board = previousBoard;
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousLeelaz;
      LizzieFrame.menu = previousMenu;
      env.close();
    }
  }

  @Test
  void nextMoveSkipsSnapshotMarkersButStillReplaysRealPasses() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Board previousBoard = Lizzie.board;
    Leelaz previousLeelaz = Lizzie.leelaz;
    Config previousConfig = Lizzie.config;
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(snapshotNode(Optional.of(new int[] {1, 1}), Stone.BLACK, false, 1));
      history.add(passNode(Stone.WHITE, true, 2));
      while (history.previous().isPresent()) {}
      board.setHistory(history);
      Lizzie.board = board;
      TrackingLeelaz engine = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = engine;
      Lizzie.config = minimalConfig();

      assertTrue(board.nextMove(false), "history should still advance onto the snapshot anchor.");
      assertEquals(
          List.of(),
          engine.recordedCommands(),
          "snapshot markers should not be replayed as engine moves during navigation.");

      assertTrue(board.nextMove(false), "history should still advance onto the real pass.");
      assertEquals(
          List.of("play W pass"),
          engine.recordedCommands(),
          "explicit passes should keep replaying after snapshot anchors.");
    } finally {
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.config = previousConfig;
      env.close();
    }
  }

  @Test
  void getBoardHistoryNodeByCoordsSkipsSnapshotMarkers() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(snapshotNode(Optional.of(new int[] {1, 1}), Stone.BLACK, false, 1));
      history.add(regularMoveNode());
      board.setHistory(history);

      BoardHistoryNode current = board.getHistory().getCurrentHistoryNode();
      BoardHistoryNode resolved = board.getBoardHistoryNodeByCoords(new int[] {1, 1});

      assertSame(
          current,
          resolved,
          "coordinate lookup should ignore snapshot markers and leave real history selection alone.");
      assertTrue(current.getData().isMoveNode(), "current node should remain the real move.");
    } finally {
      env.close();
    }
  }

  @Test
  void saveLoadMovelistSkipsSetupExtraStonesAndReplayKeepsRealHistoryOnly() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(moveNode(0, 0, Stone.BLACK, false, 1));

      Stone[] setupStones = emptyStones();
      setupStones[Board.getIndex(0, 0)] = Stone.BLACK;
      setupStones[Board.getIndex(1, 1)] = Stone.BLACK;
      setupStones[Board.getIndex(2, 2)] = Stone.WHITE;
      history.add(
          BoardData.snapshot(
              setupStones,
              Optional.empty(),
              Stone.EMPTY,
              false,
              zobrist(setupStones),
              1,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0));
      history.getCurrentHistoryNode().addExtraStones(1, 1, true);
      history.getCurrentHistoryNode().addExtraStones(2, 2, false);
      history.add(passNode(Stone.WHITE, true, 2));
      board.setHistory(history);

      ArrayList<Movelist> movelist = board.getmovelistForSaveLoad();
      TrackingLeelaz engine = allocate(TrackingLeelaz.class);
      board.restoreMoveNumber(movelist, false, engine, false);

      assertEquals(
          List.of("WHITE:pass", "BLACK:" + Board.convertCoordinatesToName(0, 0)),
          stringifyMoves(movelist),
          "save/load movelist should keep only the real move and pass.");
      assertEquals(
          List.of("play B " + Board.convertCoordinatesToName(0, 0), "play W pass"),
          engine.recordedCommands(),
          "save/load replay should ignore setup stones and keep real history only.");
    } finally {
      env.close();
    }
  }

  private static BoardData staticSnapshotRoot() {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(2, 2)] = Stone.BLACK;
    return BoardData.snapshot(
        stones,
        Optional.of(new int[] {2, 2}),
        Stone.BLACK,
        false,
        zobrist(stones),
        5,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static BoardData snapshotRootNeedingBookkeepingPass() {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(0, 0)] = Stone.BLACK;
    stones[Board.getIndex(1, 0)] = Stone.WHITE;
    int[] moveNumberList = new int[BOARD_AREA];
    moveNumberList[Board.getIndex(0, 0)] = 1;
    moveNumberList[Board.getIndex(1, 0)] = 2;
    return BoardData.snapshot(
        stones,
        Optional.of(new int[] {1, 0}),
        Stone.WHITE,
        false,
        zobrist(stones),
        3,
        moveNumberList,
        0,
        0,
        50,
        0);
  }

  private static BoardData removedSetupSnapshotAnchor() {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(0, 0)] = Stone.BLACK;
    stones[Board.getIndex(1, 0)] = Stone.WHITE;
    int[] moveNumberList = new int[BOARD_AREA];
    moveNumberList[Board.getIndex(0, 0)] = 1;
    moveNumberList[Board.getIndex(1, 0)] = 2;
    BoardData snapshot =
        BoardData.snapshot(
            stones,
            Optional.empty(),
            Stone.EMPTY,
            true,
            zobrist(stones),
            2,
            moveNumberList,
            0,
            0,
            50,
            0);
    snapshot.addProperty("AB", "aa");
    snapshot.addProperty("AW", "ba");
    snapshot.addProperty("AE", "cc");
    return snapshot;
  }

  private static BoardData regularMoveNode() {
    return moveNode(1, 1, Stone.BLACK, false, 1);
  }

  private static BoardData moveNode(
      int x, int y, Stone color, boolean blackToPlay, int moveNumber) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(x, y)] = color;
    return BoardData.move(
        stones,
        new int[] {x, y},
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

  private static BoardData midgameSnapshotAnchor() {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(0, 0)] = Stone.BLACK;
    stones[Board.getIndex(1, 0)] = Stone.WHITE;
    stones[Board.getIndex(2, 0)] = Stone.BLACK;
    int[] moveNumberList = new int[BOARD_AREA];
    moveNumberList[Board.getIndex(0, 0)] = 1;
    moveNumberList[Board.getIndex(1, 0)] = 2;
    return BoardData.snapshot(
        stones,
        Optional.of(new int[] {2, 0}),
        Stone.BLACK,
        false,
        zobrist(stones),
        2,
        moveNumberList,
        0,
        0,
        50,
        0);
  }

  private static BoardData removedMidgameSetupSnapshotAnchor() {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(0, 0)] = Stone.BLACK;
    stones[Board.getIndex(1, 0)] = Stone.WHITE;
    stones[Board.getIndex(2, 0)] = Stone.BLACK;
    int[] moveNumberList = new int[BOARD_AREA];
    moveNumberList[Board.getIndex(0, 0)] = 1;
    moveNumberList[Board.getIndex(1, 0)] = 2;
    BoardData snapshot =
        BoardData.snapshot(
            stones,
            Optional.empty(),
            Stone.EMPTY,
            false,
            zobrist(stones),
            2,
            moveNumberList,
            0,
            0,
            50,
            0);
    snapshot.addProperty("AB", "ca");
    snapshot.addProperty("AE", "cc");
    return snapshot;
  }

  private static BoardData capturedCenterSnapshotAnchor() {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(1, 0)] = Stone.BLACK;
    stones[Board.getIndex(0, 1)] = Stone.BLACK;
    stones[Board.getIndex(1, 1)] = Stone.WHITE;
    stones[Board.getIndex(2, 1)] = Stone.BLACK;
    stones[Board.getIndex(1, 2)] = Stone.BLACK;
    BoardData snapshot =
        BoardData.snapshot(
            stones,
            Optional.empty(),
            Stone.EMPTY,
            true,
            zobrist(stones),
            0,
            new int[BOARD_AREA],
            0,
            0,
            50,
            0);
    snapshot.addProperty("AB", "ba");
    snapshot.addProperty("AB", "ab");
    snapshot.addProperty("AW", "bb");
    snapshot.addProperty("AB", "cb");
    snapshot.addProperty("AB", "bc");
    return snapshot;
  }

  private static BoardData rectangularSnapshotRoot() {
    Stone[] stones = new Stone[5 * 7];
    for (int index = 0; index < stones.length; index++) {
      stones[index] = Stone.EMPTY;
    }
    stones[boardIndex(0, 0, 7)] = Stone.BLACK;
    stones[boardIndex(4, 6, 7)] = Stone.WHITE;
    BoardData snapshot =
        BoardData.snapshot(
            stones,
            Optional.empty(),
            Stone.EMPTY,
            false,
            new Zobrist(),
            0,
            new int[stones.length],
            0,
            0,
            50,
            0);
    snapshot.addProperty("SZ", "5:7");
    return snapshot;
  }

  private static BoardData rectangularSnapshotRootWithStaleSzTag() {
    Stone[] stones = new Stone[5 * 7];
    for (int index = 0; index < stones.length; index++) {
      stones[index] = Stone.EMPTY;
    }
    stones[boardIndex(0, 0, 7)] = Stone.BLACK;
    stones[boardIndex(4, 6, 7)] = Stone.WHITE;
    BoardData snapshot =
        BoardData.snapshot(
            stones,
            Optional.empty(),
            Stone.EMPTY,
            false,
            new Zobrist(),
            0,
            new int[stones.length],
            0,
            0,
            50,
            0);
    snapshot.addProperty("SZ", "3");
    return snapshot;
  }

  private static BoardHistoryList wideRectHistoryWithSnapshotMissingSz() {
    BoardHistoryList history = new BoardHistoryList(wideRectRootSnapshotWithSzTag());
    history.add(wideRectSnapshotWithoutSzTag());
    return history;
  }

  private static BoardData wideRectRootSnapshotWithSzTag() {
    Stone[] stones = new Stone[7 * 5];
    for (int index = 0; index < stones.length; index++) {
      stones[index] = Stone.EMPTY;
    }
    stones[boardIndex(0, 0, 5)] = Stone.BLACK;
    BoardData snapshot =
        BoardData.snapshot(
            stones,
            Optional.empty(),
            Stone.EMPTY,
            false,
            new Zobrist(),
            0,
            new int[stones.length],
            0,
            0,
            50,
            0);
    snapshot.addProperty("SZ", "7:5");
    return snapshot;
  }

  private static BoardData wideRectSnapshotWithoutSzTag() {
    Stone[] stones = new Stone[7 * 5];
    for (int index = 0; index < stones.length; index++) {
      stones[index] = Stone.EMPTY;
    }
    stones[boardIndex(0, 0, 5)] = Stone.BLACK;
    stones[boardIndex(6, 4, 5)] = Stone.WHITE;
    return BoardData.snapshot(
        stones,
        Optional.empty(),
        Stone.EMPTY,
        false,
        new Zobrist(),
        0,
        new int[stones.length],
        0,
        0,
        50,
        0);
  }

  private static BoardData snapshotNode(
      Optional<int[]> lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    Stone[] stones = emptyStones();
    lastMove.ifPresent(coords -> stones[Board.getIndex(coords[0], coords[1])] = lastMoveColor);
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

  private static BoardData passNode(Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    return passNode(emptyStones(), lastMoveColor, blackToPlay, moveNumber);
  }

  private static BoardData passNode(
      Stone[] stones, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    return BoardData.pass(
        stones,
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

  private static void assertPassOnly(ArrayList<Movelist> movelist, String message) {
    assertEquals(1, movelist.size(), message);
    assertTrue(movelist.get(0).ispass, message);
    assertFalse(movelist.get(0).isblack, "the remaining pass should belong to white.");
  }

  private static List<String> flattenAllMovelist(AllMovelist root) {
    List<String> moves = new ArrayList<>();
    AllMovelist current = root;
    while (!current.variations.isEmpty()) {
      current = current.variations.get(0);
      moves.add((current.isblack ? Stone.BLACK : Stone.WHITE).name() + ":" + moveName(current));
    }
    return moves;
  }

  private static String moveName(AllMovelist move) {
    return move.ispass ? "pass" : Board.convertCoordinatesToName(move.x, move.y);
  }

  private static int boardIndex(int x, int y, int boardHeight) {
    return x * boardHeight + y;
  }

  private static List<String> stringifyMoves(ArrayList<Movelist> movelist) {
    List<String> moves = new ArrayList<>();
    for (Movelist move : movelist) {
      String color = move.isblack ? Stone.BLACK.name() : Stone.WHITE.name();
      String coordinate = move.ispass ? "pass" : Board.convertCoordinatesToName(move.x, move.y);
      moves.add(color + ":" + coordinate);
    }
    return moves;
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

  private static Config minimalConfig() throws Exception {
    Config config = allocate(Config.class);
    config.playSound = false;
    config.appendWinrateToComment = false;
    config.showWinrateGraph = false;
    config.showMouseOverWinrateGraph = false;
    return config;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;

    private TestEnvironment(int previousBoardWidth, int previousBoardHeight) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
    }

    private static TestEnvironment open() {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      return new TestEnvironment(previousBoardWidth, previousBoardHeight);
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
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

  private static final class TrackingBoard extends Board {
    private TrackingBoard() {
      super();
    }

    @Override
    public void clearAfterMove() {}
  }

  private static final class TrackingFrame extends LizzieFrame {
    private TrackingFrame() {
      super();
    }

    @Override
    public void refresh() {}
  }

  private static final class TrackingLeelaz extends Leelaz {
    private List<String> commands;

    private TrackingLeelaz() throws Exception {
      super("");
    }

    @Override
    public void sendCommand(String command) {
      recordedCommands().add(command);
    }

    @Override
    public void playMove(Stone color, String move) {
      sendCommand("play " + (color.isBlack() ? "B" : "W") + " " + move);
    }

    @Override
    public void playMove(Stone color, String move, boolean addPlayer, boolean blackToPlay) {
      playMove(color, move);
    }

    @Override
    public void modifyStart() {}

    @Override
    public void setModifyEnd() {}

    @Override
    public boolean isPondering() {
      return false;
    }

    @Override
    public void clearPonderLimit() {}

    private List<String> recordedCommands() {
      if (commands == null) {
        commands = new ArrayList<>();
      }
      return commands;
    }
  }

  private static final class ThrowingLoadSgfLeelaz extends Leelaz {
    private Path lastAttemptedSgf;

    private ThrowingLoadSgfLeelaz() throws Exception {
      super("");
    }

    @Override
    public void loadSgf(Path sgfFile) {
      lastAttemptedSgf = sgfFile;
      throw new IllegalStateException("simulated loadsgf failure");
    }

    private Path lastAttemptedSgf() {
      return lastAttemptedSgf;
    }
  }

  private static final class DelayedLoadSgfLeelaz extends Leelaz {
    private CountDownLatch readyToConsume;
    private CountDownLatch allowConsume;
    private CountDownLatch consumed;
    private Path pendingSgf;
    private Path lastConsumedSgf;
    private boolean fileExistedDuringConsumption;

    private DelayedLoadSgfLeelaz() throws Exception {
      super("");
    }

    @Override
    public void loadSgf(Path sgfFile) {
      consumeLater(sgfFile, null);
    }

    @Override
    public void loadSgf(Path sgfFile, Runnable afterConsumed) {
      consumeLater(sgfFile, afterConsumed);
    }

    private void consumeLater(Path sgfFile, Runnable afterConsumed) {
      Thread worker =
          new Thread(
              () -> {
                try {
                  pendingSgf = sgfFile;
                  readyToConsume.countDown();
                  if (!allowConsume.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to consume delayed SGF");
                  }
                  lastConsumedSgf = sgfFile;
                  fileExistedDuringConsumption = Files.exists(sgfFile);
                  if (fileExistedDuringConsumption) {
                    Files.readString(sgfFile);
                  }
                } catch (Exception ex) {
                  throw new IllegalStateException("Failed to consume delayed SGF fixture", ex);
                } finally {
                  if (afterConsumed != null) {
                    afterConsumed.run();
                  }
                  consumed.countDown();
                }
              },
              "delayed-loadsgf-consumer");
      worker.setDaemon(true);
      worker.start();
    }

    private boolean awaitConsumption() throws InterruptedException {
      return consumed.await(2, TimeUnit.SECONDS);
    }

    private boolean awaitReadyToConsume() throws InterruptedException {
      return readyToConsume.await(2, TimeUnit.SECONDS);
    }

    private void allowConsume() {
      allowConsume.countDown();
    }

    private Path pendingSgf() {
      return pendingSgf;
    }

    private boolean fileExistedDuringConsumption() {
      return fileExistedDuringConsumption;
    }

    private Path lastConsumedSgf() {
      return lastConsumedSgf;
    }
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

  private static final class TrackingMenu extends Menu {
    private TrackingMenu() throws Exception {
      super();
    }

    @Override
    public void showPda(boolean show) {}

    @Override
    public void updateMenuStatusForEngine() {}
  }

  private static final class SnapshotSgfAwareFakeLeelaz extends Leelaz {
    private List<String> commands;
    private Stone[] stones;
    private boolean blackToPlay = true;
    private int boardWidth = 0;
    private int boardHeight = 0;
    private String loadedSgf = "";

    private SnapshotSgfAwareFakeLeelaz() throws Exception {
      super("");
    }

    @Override
    public void sendCommand(String command) {
      recordedCommands().add(command);
      if ("clear_board".equals(command)) {
        clearBoardState();
      }
    }

    @Override
    public void loadSgf(Path sgfFile) {
      recordedCommands().add("loadsgf " + sgfFile.toAbsolutePath());
      try {
        parseLoadedSgf(Files.readString(sgfFile));
      } catch (Exception ex) {
        throw new IllegalStateException("Failed to parse snapshot SGF in fake engine", ex);
      }
    }

    private void parseLoadedSgf(String sgfContent) {
      loadedSgf = sgfContent;
      int[] size = parseBoardSize(sgfContent);
      boardWidth = size[0];
      boardHeight = size[1];
      clearBoardState();
      String player = readSingleProperty(sgfContent, "PL");
      blackToPlay = !"W".equalsIgnoreCase(player);
      placeSetupStones(sgfContent, "AB", Stone.BLACK);
      placeSetupStones(sgfContent, "AW", Stone.WHITE);
    }

    private int[] parseBoardSize(String sgfContent) {
      String value = readSingleProperty(sgfContent, "SZ");
      if (value == null) {
        throw new IllegalStateException("SGF missing SZ");
      }
      String normalized = value.trim();
      int split = normalized.indexOf(':');
      if (split < 0) {
        int size = Integer.parseInt(normalized);
        if (size <= 0) {
          throw new IllegalStateException("Invalid square SZ value: " + normalized);
        }
        return new int[] {size, size};
      }
      int width = Integer.parseInt(normalized.substring(0, split));
      int height = Integer.parseInt(normalized.substring(split + 1));
      if (width <= 0 || height <= 0) {
        throw new IllegalStateException("Invalid rectangular SZ value: " + normalized);
      }
      return new int[] {width, height};
    }

    private void placeSetupStones(String sgfContent, String property, Stone color) {
      int from = 0;
      String token = property + "[";
      while (true) {
        int start = sgfContent.indexOf(token, from);
        if (start < 0) {
          return;
        }
        int valueStart = start + token.length();
        int valueEnd = sgfContent.indexOf(']', valueStart);
        if (valueEnd < 0) {
          throw new IllegalStateException("Unclosed setup property: " + property);
        }
        int[] point = parseCoord(sgfContent.substring(valueStart, valueEnd));
        stones[boardIndex(point[0], point[1], boardHeight)] = color;
        from = valueEnd + 1;
      }
    }

    private int[] parseCoord(String value) {
      if (value.isEmpty()) {
        throw new IllegalStateException("Unexpected pass coordinate in setup property");
      }
      int[] point = new int[2];
      if (value.contains("_")) {
        String[] parts = value.split("_");
        if (parts.length != 2) {
          throw new IllegalStateException("Invalid extended coordinate: " + value);
        }
        point[0] = Integer.parseInt(parts[0]);
        point[1] = Integer.parseInt(parts[1]);
      } else {
        point[0] = value.charAt(0) - 'a';
        point[1] = value.charAt(1) - 'a';
      }
      if (point[0] < 0 || point[1] < 0 || point[0] >= boardWidth || point[1] >= boardHeight) {
        throw new IllegalStateException("Coordinate outside parsed SZ board: " + value);
      }
      return point;
    }

    private String readSingleProperty(String sgfContent, String property) {
      int start = sgfContent.indexOf(property + "[");
      if (start < 0) {
        return null;
      }
      int valueStart = start + property.length() + 1;
      int valueEnd = sgfContent.indexOf(']', valueStart);
      if (valueEnd < 0) {
        throw new IllegalStateException("Unclosed property: " + property);
      }
      return sgfContent.substring(valueStart, valueEnd);
    }

    private void clearBoardState() {
      int width = boardWidth > 0 ? boardWidth : BOARD_SIZE;
      int height = boardHeight > 0 ? boardHeight : BOARD_SIZE;
      stones = new Stone[width * height];
      for (int index = 0; index < stones.length; index++) {
        stones[index] = Stone.EMPTY;
      }
      blackToPlay = true;
    }

    private List<String> recordedCommands() {
      if (commands == null) {
        commands = new ArrayList<>();
      }
      return commands;
    }

    private Stone[] copyStones() {
      return stones.clone();
    }

    private boolean isBlackToPlay() {
      return blackToPlay;
    }

    private int loadedBoardWidth() {
      return boardWidth;
    }

    private int loadedBoardHeight() {
      return boardHeight;
    }

    private String loadedSgf() {
      return loadedSgf;
    }
  }
}
