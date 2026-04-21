package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HistoryActionDiffGateTest {
  private static final int BOARD_SIZE = 2;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void bestNodeSuppressionIgnoresSnapshotMarkerMetadata() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = historyWithCurrent(snapshotData(new int[] {1, 1}, 2));
      BoardHistoryNode node = history.getCurrentHistoryNode();
      node.isBest = true;

      double[] diff = invokeLastWinrateScoreDiff(allocate(LizzieFrame.class), node);

      assertEquals(-10.0, diff[0], 0.001, "snapshot metadata should not suppress best-node diff.");
      assertEquals(-1.0, diff[1], 0.001, "snapshot metadata should not suppress score diff.");
    } finally {
      env.close();
    }
  }

  @Test
  void bestNodeSuppressionKeepsExplicitPassHistoryAction() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = historyWithCurrent(passData(2));
      BoardHistoryNode node = history.getCurrentHistoryNode();
      node.isBest = true;

      double[] diff = invokeLastWinrateScoreDiff(allocate(LizzieFrame.class), node);

      assertEquals(0.0, diff[0], 0.001, "explicit PASS should remain a history action.");
      assertEquals(0.0, diff[1], 0.001, "explicit PASS should keep best-node suppression.");
    } finally {
      env.close();
    }
  }

  @Test
  void bestNodeSuppressionIgnoresDummyPassPlaceholder() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = historyWithCurrent(dummyPassData(2));
      BoardHistoryNode node = history.getCurrentHistoryNode();
      node.isBest = true;

      double[] diff = invokeLastWinrateScoreDiff(allocate(LizzieFrame.class), node);

      assertEquals(-10.0, diff[0], 0.001, "dummy PASS should keep the real diff value.");
      assertEquals(-1.0, diff[1], 0.001, "dummy PASS should keep the real score diff.");
    } finally {
      env.close();
    }
  }

  private static double[] invokeLastWinrateScoreDiff(LizzieFrame frame, BoardHistoryNode node)
      throws Exception {
    Method method =
        LizzieFrame.class.getDeclaredMethod("lastWinrateScoreDiff", BoardHistoryNode.class);
    method.setAccessible(true);
    return (double[]) method.invoke(frame, node);
  }

  private static BoardHistoryList historyWithCurrent(BoardData current) {
    BoardHistoryList history = new BoardHistoryList(previousData());
    history.add(current);
    return history;
  }

  private static BoardData previousData() {
    BoardData data = moveData(new int[] {0, 0}, 1);
    data.winrate = 60;
    data.scoreMean = 1;
    return data;
  }

  private static BoardData moveData(int[] coords, int moveNumber) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(coords[0], coords[1])] = Stone.BLACK;
    return BoardData.move(
        stones,
        coords,
        Stone.BLACK,
        false,
        new Zobrist(),
        moveNumber,
        moveList(coords[0], coords[1], moveNumber),
        0,
        0,
        60,
        20);
  }

  private static BoardData passData(int moveNumber) {
    BoardData data =
        BoardData.pass(
            emptyStones(),
            Stone.WHITE,
            true,
            new Zobrist(),
            moveNumber,
            new int[BOARD_AREA],
            0,
            0,
            50,
            20);
    data.scoreMean = 0;
    return data;
  }

  private static BoardData dummyPassData(int moveNumber) {
    BoardData data = passData(moveNumber);
    data.dummy = true;
    return data;
  }

  private static BoardData snapshotData(int[] coords, int moveNumber) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(coords[0], coords[1])] = Stone.WHITE;
    BoardData data =
        BoardData.snapshot(
            stones,
            Optional.of(coords),
            Stone.WHITE,
            true,
            new Zobrist(),
            moveNumber,
            moveList(coords[0], coords[1], moveNumber),
            0,
            0,
            50,
            20);
    data.scoreMean = 0;
    return data;
  }

  private static int[] moveList(int x, int y, int moveNumber) {
    int[] moveNumberList = new int[BOARD_AREA];
    moveNumberList[Board.getIndex(x, y)] = moveNumber;
    return moveNumberList;
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
    }

    private static TestEnvironment open() throws Exception {
      TestEnvironment env =
          new TestEnvironment(
              Board.boardWidth, Board.boardHeight, Lizzie.config, Lizzie.board, Lizzie.frame);
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      Lizzie.config = allocate(Config.class);
      return env;
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
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
