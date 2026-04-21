package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SnapshotMoveUiGateTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void setPressStoneInfoIgnoresSnapshotMarkerCoordinates() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(snapshotNode(new int[] {1, 1}, Stone.BLACK, false, 1));
      history.add(moveNode(0, 0, Stone.WHITE, true, 2));
      board.setHistory(history);
      Lizzie.board = board;

      board.setPressStoneInfo(new int[] {1, 1}, true);
      Thread.sleep(120L);

      assertFalse(
          board.isMouseOnStone,
          "click review should ignore snapshot markers because they are not real moves.");
      assertNull(board.mouseOnNode, "snapshot marker should not resolve to a review target.");
      assertArrayEquals(
          LizzieFrame.outOfBoundCoordinate,
          board.mouseOnStoneCoords,
          "ignored snapshot markers should reset hover coordinates.");
    } finally {
      if (Lizzie.board != null) {
        Lizzie.board.isMouseOnStone = false;
      }
      env.close();
    }
  }

  @Test
  void previousBestFilterIgnoresSnapshotLastMoveMarker() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    boolean previousEngineGame = EngineManager.isEngineGame;
    try {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData previousMove = moveNode(0, 0, Stone.BLACK, false, 1);
      previousMove.bestMoves = List.of(bestMove(1, 1));
      history.add(previousMove);
      history.add(snapshotNode(new int[] {1, 1}, Stone.WHITE, true, 1));
      board.setHistory(history);
      Lizzie.board = board;
      EngineManager.isEngineGame = true;

      BoardRenderer renderer = new BoardRenderer(false);
      invokeDrawBranch(renderer);

      assertEquals(
          0,
          bestMoves(renderer).size(),
          "previous-best filtering should ignore snapshot marker coordinates.");
    } finally {
      EngineManager.isEngineGame = previousEngineGame;
      env.close();
    }
  }

  private static void invokeDrawBranch(BoardRenderer renderer) throws Exception {
    Method method = BoardRenderer.class.getDeclaredMethod("drawBranch");
    method.setAccessible(true);
    method.invoke(renderer);
  }

  @SuppressWarnings("unchecked")
  private static List<MoveData> bestMoves(BoardRenderer renderer) throws Exception {
    Field field = BoardRenderer.class.getDeclaredField("bestMoves");
    field.setAccessible(true);
    return (List<MoveData>) field.get(renderer);
  }

  private static MoveData bestMove(int x, int y) {
    MoveData move = new MoveData();
    move.coordinate = Board.convertCoordinatesToName(x, y);
    move.playouts = 100;
    move.order = 0;
    return move;
  }

  private static BoardData snapshotNode(
      int[] lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(lastMove[0], lastMove[1])] = lastMoveColor;
    return BoardData.snapshot(
        stones,
        Optional.of(lastMove),
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
    private final Leelaz previousLeelaz;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame,
        Leelaz previousLeelaz) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousLeelaz = previousLeelaz;
    }

    private static TestEnvironment open() throws Exception {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Config previousConfig = Lizzie.config;
      Board previousBoard = Lizzie.board;
      LizzieFrame previousFrame = Lizzie.frame;
      Leelaz previousLeelaz = Lizzie.leelaz;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      Config config = allocate(Config.class);
      config.enableClickReview = true;
      config.replayBranchIntervalSeconds = 1.0;
      config.showPreviousBestmovesInEngineGame = true;
      config.showPreviousBestmovesOnlyFirstMove = true;
      config.showKataGoEstimate = false;
      Lizzie.config = config;
      Lizzie.frame = allocate(TrackingFrame.class);
      Lizzie.leelaz = allocate(TrackingLeelaz.class);

      return new TestEnvironment(
          previousBoardWidth,
          previousBoardHeight,
          previousConfig,
          previousBoard,
          previousFrame,
          previousLeelaz);
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousLeelaz;
    }
  }

  private static final class TrackingFrame extends LizzieFrame {
    private TrackingFrame() {
      super();
    }

    @Override
    public void requestProblemListRefresh() {}

    @Override
    public void refreshProblemListSnapshot() {}

    @Override
    public void refresh() {}
  }

  private static final class TrackingLeelaz extends Leelaz {
    private TrackingLeelaz() throws Exception {
      super("");
    }

    @Override
    public void clearBestMoves() {}

    @Override
    public void maybeAjustPDA(BoardHistoryNode node) {}
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
