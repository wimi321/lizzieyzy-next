package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Field;
import java.util.Optional;
import javax.swing.JCheckBox;
import javax.swing.JTextField;
import org.junit.jupiter.api.Test;

class BottomToolbarPassDetectionTest {
  @Test
  void continueWithBestMoveTreatsDefaultRootPlusSinglePassAsNonTerminal() throws Exception {
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    try {
      Config config = allocate(Config.class);
      config.continueWithBestMove = true;
      config.directlyWithBestMove = false;
      Lizzie.config = config;

      TrackingBoard board = allocate(TrackingBoard.class);
      board.setHistory(historyWithDefaultRootAndSinglePass());
      Lizzie.board = board;

      BottomToolbar toolbar = allocate(BottomToolbar.class);
      toolbar.chkAutoMain = new JCheckBox();
      toolbar.chkAutoMain.setSelected(true);
      toolbar.txtAutoMain = new JTextField("0.001");
      LizzieFrame.toolbar = toolbar;

      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.toolbar = toolbar;
      Lizzie.frame = frame;

      toolbar.autoPlayMain(false);
      waitForAutoPlayExit(toolbar);

      assertEquals(
          1,
          frame.playBestMoveCount,
          "default root nodes should not be mistaken for the first pass in a double-pass stop.");
    } finally {
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
    }
  }

  @Test
  void continueWithBestMoveTreatsExplicitSnapshotAsNonTerminalAfterPass() throws Exception {
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    try {
      Config config = allocate(Config.class);
      config.continueWithBestMove = true;
      config.directlyWithBestMove = false;
      Lizzie.config = config;

      TrackingBoard board = allocate(TrackingBoard.class);
      board.setHistory(historyWithPassThenExplicitSnapshot());
      Lizzie.board = board;

      BottomToolbar toolbar = allocate(BottomToolbar.class);
      toolbar.chkAutoMain = new JCheckBox();
      toolbar.chkAutoMain.setSelected(true);
      toolbar.txtAutoMain = new JTextField("0.001");
      LizzieFrame.toolbar = toolbar;

      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.toolbar = toolbar;
      Lizzie.frame = frame;

      toolbar.autoPlayMain(false);
      waitForAutoPlayExit(toolbar);

      assertEquals(
          1,
          frame.playBestMoveCount,
          "explicit SNAPSHOT nodes must not terminate autoplay as a synthetic second pass.");
    } finally {
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
    }
  }

  @Test
  void continueWithBestMoveTreatsDummyPassAsNonTerminalAfterRealPass() throws Exception {
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    BottomToolbar previousToolbar = LizzieFrame.toolbar;
    try {
      Config config = allocate(Config.class);
      config.continueWithBestMove = true;
      config.directlyWithBestMove = false;
      Lizzie.config = config;

      TrackingBoard board = allocate(TrackingBoard.class);
      board.setHistory(historyWithPassThenDummyPass());
      Lizzie.board = board;

      BottomToolbar toolbar = allocate(BottomToolbar.class);
      toolbar.chkAutoMain = new JCheckBox();
      toolbar.chkAutoMain.setSelected(true);
      toolbar.txtAutoMain = new JTextField("0.001");
      LizzieFrame.toolbar = toolbar;

      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.toolbar = toolbar;
      Lizzie.frame = frame;

      toolbar.autoPlayMain(false);
      waitForAutoPlayExit(toolbar);

      assertEquals(
          1,
          frame.playBestMoveCount,
          "dummy PASS placeholders must not terminate autoplay as a second real pass.");
    } finally {
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.toolbar = previousToolbar;
    }
  }

  private static BoardHistoryList historyWithDefaultRootAndSinglePass() {
    int boardArea = Board.boardWidth * Board.boardHeight;
    BoardHistoryList history =
        new BoardHistoryList(BoardData.empty(Board.boardWidth, Board.boardHeight));
    BoardData pass =
        BoardData.pass(
            emptyStones(boardArea),
            Stone.BLACK,
            false,
            new Zobrist(),
            1,
            new int[boardArea],
            0,
            0,
            50,
            0);
    history.add(pass);
    return history;
  }

  private static BoardHistoryList historyWithPassThenExplicitSnapshot() {
    int boardArea = Board.boardWidth * Board.boardHeight;
    BoardHistoryList history =
        new BoardHistoryList(
            BoardData.move(
                emptyStones(boardArea),
                new int[] {0, 0},
                Stone.BLACK,
                false,
                new Zobrist(),
                1,
                moveList(boardArea, 0, 0, 1),
                0,
                0,
                50,
                0));
    BoardData pass =
        BoardData.pass(
            emptyStones(boardArea),
            Stone.WHITE,
            true,
            new Zobrist(),
            2,
            new int[boardArea],
            0,
            0,
            50,
            0);
    BoardData snapshot =
        BoardData.snapshot(
            emptyStones(boardArea),
            Optional.empty(),
            Stone.EMPTY,
            false,
            new Zobrist(),
            3,
            new int[boardArea],
            0,
            0,
            50,
            0);
    history.add(pass);
    history.add(snapshot);
    return history;
  }

  private static BoardHistoryList historyWithPassThenDummyPass() {
    int boardArea = Board.boardWidth * Board.boardHeight;
    BoardHistoryList history =
        new BoardHistoryList(
            BoardData.move(
                emptyStones(boardArea),
                new int[] {0, 0},
                Stone.BLACK,
                false,
                new Zobrist(),
                1,
                moveList(boardArea, 0, 0, 1),
                0,
                0,
                50,
                0));
    BoardData pass =
        BoardData.pass(
            emptyStones(boardArea),
            Stone.WHITE,
            true,
            new Zobrist(),
            2,
            new int[boardArea],
            0,
            0,
            50,
            0);
    BoardData dummyPass =
        BoardData.pass(
            emptyStones(boardArea),
            Stone.BLACK,
            false,
            new Zobrist(),
            3,
            new int[boardArea],
            0,
            0,
            50,
            0);
    dummyPass.dummy = true;
    history.add(pass);
    history.add(dummyPass);
    return history;
  }

  private static Stone[] emptyStones(int boardArea) {
    Stone[] stones = new Stone[boardArea];
    for (int index = 0; index < boardArea; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static int[] moveList(int boardArea, int x, int y, int moveNumber) {
    int[] moveNumberList = new int[boardArea];
    moveNumberList[Board.getIndex(x, y)] = moveNumber;
    return moveNumberList;
  }

  private static void waitForAutoPlayExit(BottomToolbar toolbar) throws InterruptedException {
    for (int i = 0; i < 200; i++) {
      if (!toolbar.chkAutoMain.isSelected()) {
        Thread.sleep(20);
        return;
      }
      Thread.sleep(10);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class TrackingBoard extends Board {
    @Override
    public boolean nextMove(boolean needRefresh) {
      return false;
    }
  }

  private static final class TrackingFrame extends LizzieFrame {
    private BottomToolbar toolbar;
    private int playBestMoveCount;

    @Override
    public void playBestMove() {
      playBestMoveCount++;
      toolbar.chkAutoMain.setSelected(false);
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
