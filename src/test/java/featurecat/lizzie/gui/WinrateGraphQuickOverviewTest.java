package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WinrateGraphQuickOverviewTest {
  private static final int BOARD_SIZE = 2;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void quickOverviewIncludesSnapshotBoundaryNodes() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = graphConfig();
      BoardHistoryNode currentNode = historyWithMoveThenSnapshotMarker().getCurrentHistoryNode();
      WinrateGraph graph = new WinrateGraph();

      List<?> moves = buildQuickOverviewMoves(graph, currentNode);

      assertEquals(2, moves.size(), "quick overview should keep SNAPSHOT as boundary anchor.");
      assertEquals(Board.convertCoordinatesToName(0, 0), moveName(moves.get(0)));
      assertEquals("SNAPSHOT", moveName(moves.get(1)));
      assertEquals(2, moveNumber(moves.get(1)));
      assertFalse(
          connectsToPrevious(moves.get(1)),
          "quick overview should break line segments at SNAPSHOT boundaries.");
    } finally {
      env.close();
    }
  }

  @Test
  void quickOverviewTreatsSnapshotAsSwingBoundary() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = graphConfig();
      BoardHistoryNode currentNode = historyWithMoveSnapshotMove().getCurrentHistoryNode();
      WinrateGraph graph = new WinrateGraph();

      List<?> moves = buildQuickOverviewMoves(graph, currentNode);

      assertEquals(3, moves.size(), "quick overview should keep SNAPSHOT as explicit boundary.");
      assertEquals("SNAPSHOT", moveName(moves.get(1)));
      assertEquals(
          0.0,
          swing(moves.get(1)),
          0.0001,
          "quick overview SNAPSHOT boundary should keep zero swing.");
      assertFalse(
          connectsToPrevious(moves.get(1)),
          "quick overview should break line segments on the SNAPSHOT boundary point.");
      assertEquals(
          5,
          moveNumber(moves.get(2)),
          "quick overview should keep the post-SNAPSHOT move anchored to its real move number.");
      assertEquals(
          0.0, swing(moves.get(2)), 0.0001, "quick overview should reset swing after SNAPSHOT.");
      assertFalse(
          connectsToPrevious(moves.get(2)),
          "quick overview should break line segments at SNAPSHOT boundaries.");
    } finally {
      env.close();
    }
  }

  @Test
  void quickOverviewKeepsContinuousMoveSwingAndLine() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = graphConfig();
      BoardHistoryNode currentNode = historyWithContinuousMoves().getCurrentHistoryNode();
      WinrateGraph graph = new WinrateGraph();

      List<?> moves = buildQuickOverviewMoves(graph, currentNode);

      assertEquals(2, moves.size(), "quick overview should keep continuous MOVE history intact.");
      assertEquals(
          10.0,
          swing(moves.get(1)),
          0.0001,
          "quick overview should keep ordinary MOVE-to-MOVE swing.");
      assertTrue(
          connectsToPrevious(moves.get(1)),
          "quick overview should keep line segments for continuous MOVE history.");
    } finally {
      env.close();
    }
  }

  private static List<?> buildQuickOverviewMoves(WinrateGraph graph, BoardHistoryNode node)
      throws Exception {
    Method method =
        WinrateGraph.class.getDeclaredMethod("buildQuickOverviewMoves", BoardHistoryNode.class);
    method.setAccessible(true);
    return (List<?>) method.invoke(graph, node);
  }

  private static String moveName(Object move) throws Exception {
    Field field = move.getClass().getDeclaredField("moveName");
    field.setAccessible(true);
    return (String) field.get(move);
  }

  private static double swing(Object move) throws Exception {
    Field field = move.getClass().getDeclaredField("swing");
    field.setAccessible(true);
    return field.getDouble(move);
  }

  private static int moveNumber(Object move) throws Exception {
    Field field = move.getClass().getDeclaredField("moveNumber");
    field.setAccessible(true);
    return field.getInt(move);
  }

  private static boolean connectsToPrevious(Object move) throws Exception {
    Field field = move.getClass().getDeclaredField("connectsToPrevious");
    field.setAccessible(true);
    return field.getBoolean(move);
  }

  private static BoardHistoryList historyWithMoveThenSnapshotMarker() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveData(new int[] {0, 0}, Stone.BLACK, false, 1));
    history.add(snapshotData(new int[] {1, 1}, Stone.WHITE, true, 2));
    return history;
  }

  private static BoardHistoryList historyWithMoveSnapshotMove() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveData(new int[] {0, 0}, Stone.BLACK, false, 1, 28));
    history.add(snapshotData(new int[] {1, 0}, Stone.WHITE, true, 4, 64));
    history.add(moveData(new int[] {1, 1}, Stone.WHITE, true, 5, 82));
    return history;
  }

  private static BoardHistoryList historyWithContinuousMoves() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveData(new int[] {0, 0}, Stone.BLACK, false, 1, 28));
    history.add(moveData(new int[] {1, 1}, Stone.WHITE, true, 2, 82));
    return history;
  }

  private static BoardData moveData(
      int[] lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    return moveData(lastMove, lastMoveColor, blackToPlay, moveNumber, 50);
  }

  private static BoardData moveData(
      int[] lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber, double winrate) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(lastMove[0], lastMove[1])] = lastMoveColor;
    return BoardData.move(
        stones,
        lastMove,
        lastMoveColor,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        moveList(lastMove[0], lastMove[1], moveNumber),
        0,
        0,
        winrate,
        1);
  }

  private static BoardData snapshotData(
      int[] lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    return snapshotData(lastMove, lastMoveColor, blackToPlay, moveNumber, 50);
  }

  private static BoardData snapshotData(
      int[] lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber, double winrate) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(lastMove[0], lastMove[1])] = lastMoveColor;
    return BoardData.snapshot(
        stones,
        Optional.of(lastMove),
        lastMoveColor,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        moveList(lastMove[0], lastMove[1], moveNumber),
        0,
        0,
        winrate,
        0);
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

  private static Config graphConfig() throws Exception {
    Config config = allocate(Config.class);
    config.initialMaxScoreLead = 15;
    return config;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;

    private TestEnvironment(
        int previousBoardWidth, int previousBoardHeight, Config previousConfig) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
    }

    private static TestEnvironment open() {
      TestEnvironment env = new TestEnvironment(Board.boardWidth, Board.boardHeight, Lizzie.config);
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      return env;
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
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
