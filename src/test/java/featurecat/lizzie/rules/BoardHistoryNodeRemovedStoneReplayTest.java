package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.LizzieFrame;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BoardHistoryNodeRemovedStoneReplayTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void snapshotRootWithoutRealActionsDoesNotReplaySyntheticPass() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryNode current =
          removedStoneNode(
              boardData(
                  stones(placement(0, 0, Stone.BLACK)),
                  Optional.empty(),
                  Stone.EMPTY,
                  true,
                  58,
                  BoardNodeKind.SNAPSHOT));

      assertTrue(current.checkForRemovedStone(), "removed-stone path should trigger replay.");
      assertSingleExactSnapshotRestore(env.leelaz.recordedCommands(), "snapshot roots should restore through loadsgf.");
      assertArrayEquals(
          current.getData().stones,
          env.leelaz.copyStones(),
          "snapshot roots should restore the exact static board.");
      assertEquals(
          current.getData().blackToPlay,
          env.leelaz.isBlackToPlay(),
          "snapshot roots should keep the static side to play.");
    } finally {
      env.close();
    }
  }

  @Test
  void snapshotRootDoesNotReplaySyntheticPass() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryNode current =
          currentNode(
              boardData(
                  stones(placement(0, 0, Stone.BLACK)),
                  Optional.empty(),
                  Stone.EMPTY,
                  true,
                  58,
                  BoardNodeKind.SNAPSHOT),
              boardData(
                  stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
                  Optional.of(new int[] {1, 0}),
                  Stone.WHITE,
                  true,
                  59,
                  BoardNodeKind.MOVE));

      assertTrue(current.checkForRemovedStone(), "removed-stone path should trigger replay.");
      assertSingleExactSnapshotRestore(env.leelaz.recordedCommands(), "removed-stone replay should land the current board through one exact snapshot restore.");
      assertArrayEquals(
          current.getData().stones,
          env.leelaz.copyStones(),
          "removed-stone replay should prefer the current board over replaying old snapshot actions.");
    } finally {
      env.close();
    }
  }

  @Test
  void removedStoneCurrentMoveKeepsCurrentBoardWhenEarlierPassExists() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryNode current =
          currentNode(
              boardData(
                  stones(placement(0, 0, Stone.BLACK)),
                  Optional.empty(),
                  Stone.WHITE,
                  true,
                  58,
                  BoardNodeKind.PASS),
              boardData(
                  stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.BLACK)),
                  Optional.of(new int[] {1, 0}),
                  Stone.BLACK,
                  false,
                  59,
                  BoardNodeKind.MOVE));

      assertTrue(current.checkForRemovedStone(), "removed-stone path should trigger replay.");
      assertSingleExactSnapshotRestore(env.leelaz.recordedCommands(), "removed-stone replay should keep earlier pass history behind the current static board.");
      assertArrayEquals(
          current.getData().stones,
          env.leelaz.copyStones(),
          "removed-stone replay should restore the current board exactly even when earlier passes exist.");
    } finally {
      env.close();
    }
  }

  @Test
  void removedStoneCurrentMoveDoesNotReplayTheRemovedMove() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryNode current =
          currentNode(
              boardData(
                  stones(placement(0, 0, Stone.BLACK)),
                  Optional.of(new int[] {0, 0}),
                  Stone.BLACK,
                  false,
                  1,
                  BoardNodeKind.MOVE),
              boardData(
                  stones(placement(0, 0, Stone.BLACK)),
                  Optional.of(new int[] {1, 0}),
                  Stone.WHITE,
                  true,
                  2,
                  BoardNodeKind.MOVE));

      assertTrue(current.checkForRemovedStone(), "removed-stone path should trigger replay.");
      assertSingleExactSnapshotRestore(env.leelaz.recordedCommands(), "removed current moves should collapse to one exact snapshot restore.");
      assertArrayEquals(
          current.getData().stones,
          env.leelaz.copyStones(),
          "removed current moves should restore the edited board without replaying the stale move.");
      assertEquals(
          current.getData().blackToPlay,
          env.leelaz.isBlackToPlay(),
          "removed current moves should preserve the edited side to play.");
    } finally {
      env.close();
    }
  }

  @Test
  void removedStoneSnapshotReplayKeepsExactStaticBoardForCaptureShapes() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    Leelaz previousLeelaz = Lizzie.leelaz;
    try {
      RuleAwareFakeLeelaz leelaz = allocate(RuleAwareFakeLeelaz.class);
      Lizzie.leelaz = leelaz;
      BoardHistoryNode current = removedStoneNode(capturedCenterSnapshotData());

      assertTrue(current.checkForRemovedStone(), "removed-stone path should trigger replay.");
      assertArrayEquals(
          current.getData().stones,
          leelaz.copyStones(),
          "removed-stone snapshot replay should keep the exact snapshot board.");
      assertEquals(
          current.getData().blackToPlay,
          leelaz.isBlackToPlay(),
          "removed-stone snapshot replay should keep the snapshot side to play.");
    } finally {
      Lizzie.leelaz = previousLeelaz;
      env.close();
    }
  }

  private static BoardHistoryNode currentNode(BoardData rootData, BoardData currentData) {
    BoardHistoryNode root = new BoardHistoryNode(rootData);
    BoardHistoryNode current = root.add(new BoardHistoryNode(currentData));
    current.setRemovedStone();
    return current;
  }

  private static BoardHistoryNode removedStoneNode(BoardData data) {
    BoardHistoryNode current = new BoardHistoryNode(data);
    current.setRemovedStone();
    return current;
  }

  private static BoardData boardData(
      Stone[] stones,
      Optional<int[]> lastMove,
      Stone lastMoveColor,
      boolean blackToPlay,
      int moveNumber,
      BoardNodeKind nodeKind) {
    if (nodeKind == BoardNodeKind.MOVE) {
      return BoardData.move(
          stones,
          lastMove.orElseThrow(),
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
    if (nodeKind == BoardNodeKind.PASS) {
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

  private static Stone[] stones(Placement... placements) {
    Stone[] stones = emptyStones();
    for (Placement placement : placements) {
      stones[Board.getIndex(placement.x, placement.y)] = placement.color;
    }
    return stones;
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

  private static String moveRecord(Stone color, int x, int y) {
    return color.name() + ":" + Board.convertCoordinatesToName(x, y);
  }

  private static void assertSingleExactSnapshotRestore(List<String> commands, String message) {
    assertEquals(2, commands.size(), message);
    assertEquals("clear_board", commands.get(0), message);
    assertTrue(commands.get(1).startsWith("loadsgf "), message);
  }

  private static BoardData capturedCenterSnapshotData() {
    Stone[] stones =
        stones(
            placement(1, 0, Stone.BLACK),
            placement(0, 1, Stone.BLACK),
            placement(1, 1, Stone.WHITE),
            placement(2, 1, Stone.BLACK),
            placement(1, 2, Stone.BLACK));
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

  private static Placement placement(int x, int y, Stone color) {
    return new Placement(x, y, color);
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

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Leelaz previousLeelaz;
    private final Config previousConfig;
    private final LizzieFrame previousFrame;
    private final RuleAwareFakeLeelaz leelaz;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Leelaz previousLeelaz,
        Config previousConfig,
        LizzieFrame previousFrame,
        RuleAwareFakeLeelaz leelaz) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousLeelaz = previousLeelaz;
      this.previousConfig = previousConfig;
      this.previousFrame = previousFrame;
      this.leelaz = leelaz;
    }

    private static TestEnvironment open() throws Exception {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Leelaz previousLeelaz = Lizzie.leelaz;
      Config previousConfig = Lizzie.config;
      LizzieFrame previousFrame = Lizzie.frame;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      Lizzie.config = allocate(Config.class);
      Lizzie.frame = allocate(LizzieFrame.class);

      RuleAwareFakeLeelaz leelaz = allocate(RuleAwareFakeLeelaz.class);
      Lizzie.leelaz = leelaz;
      return new TestEnvironment(
          previousBoardWidth,
          previousBoardHeight,
          previousLeelaz,
          previousConfig,
          previousFrame,
          leelaz);
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.leelaz = previousLeelaz;
      Lizzie.config = previousConfig;
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
