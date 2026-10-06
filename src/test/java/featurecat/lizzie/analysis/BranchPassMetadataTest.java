package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class BranchPassMetadataTest {
  private static final int BOARD_SIZE = 2;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void branchWithoutVariationKeepsExplicitSnapshotKindAndDummy() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardData root = snapshotData(Optional.empty(), Stone.EMPTY, false, 58);
      root.dummy = true;
      Board board = boardWithRoot(root);

      Branch branch = createBranch(board, List.of());

      assertTrue(
          branch.data.isSnapshotNode(), "branch copy should preserve explicit snapshot kind.");
      assertTrue(branch.data.dummy, "branch copy should preserve dummy metadata.");
      assertEquals(0, branch.length, "empty variations should keep branch length at zero.");
    } finally {
      env.close();
    }
  }

  @Test
  void branchPassCreatesExplicitPassNode() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = boardWithRoot(snapshotData(Optional.empty(), Stone.EMPTY, false, 58));

      Branch branch = createBranch(board, List.of("pass"));

      assertTrue(branch.data.isPassNode(), "branch pass should become an explicit PASS node.");
      assertEquals(59, branch.data.moveNumber, "branch pass should advance the total move number.");
      assertEquals(1, branch.length, "branch length should count the rendered pass.");
    } finally {
      env.close();
    }
  }

  @Test
  void branchPassThenMoveCreatesExplicitMoveAndKeepsVariationAlive() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = boardWithRoot(snapshotData(Optional.empty(), Stone.EMPTY, false, 58));

      Branch branch = createBranch(board, List.of("pass", "A1"));

      assertTrue(branch.data.isMoveNode(), "branch should become an explicit MOVE after a stone.");
      assertTrue(branch.data.lastMove.isPresent(), "branch should continue after a pass.");
      assertArrayEquals(
          new int[] {0, 1},
          branch.data.lastMove.get(),
          "branch should keep the move that follows a pass.");
      assertEquals(
          60, branch.data.moveNumber, "branch should advance the move number for every step.");
      assertEquals(
          2,
          branch.data.moveNumberList[Board.getIndex(0, 1)],
          "post-pass moves should keep branch numbering.");
      assertEquals(2, branch.length, "branch length should include pass and follow-up moves.");
    } finally {
      env.close();
    }
  }

  @Test
  void branchResignStopsVariationProcessing() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = boardWithRoot(snapshotData(Optional.empty(), Stone.EMPTY, false, 58));

      BoardData data = board.getData();
      Branch.Position position =
          new Branch.Position(
              Board.boardWidth, Board.boardHeight, data, board.getStones(), data.blackToPlay);
      Branch.Input input =
          new Branch.Input(position, List.of("pass", "resign", "A1"), null, 3, false, false);

      Branch branch = new Branch(input);

      assertEquals(1, branch.length, "resign should stop variation after pass");
      assertTrue(
          branch.data.isPassNode(), "branch node should remain the pass node preceding resign");
      assertEquals(59, branch.data.moveNumber);
      assertTrue(branch.data.blackToPlay);
      assertEquals(0, branch.data.moveNumberList[Board.getIndex(0, 1)]);
      assertFalse(branch.isNewStone[Board.getIndex(0, 1)]);
    } finally {
      env.close();
    }
  }

  @Test
  void branchCaptureMarksCapturedStonesWhenEnabled() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Stone[] stones = new Stone[BOARD_AREA];
      Arrays.fill(stones, Stone.EMPTY);
      stones[Board.getIndex(0, 0)] = Stone.WHITE; // A2
      stones[Board.getIndex(0, 1)] = Stone.BLACK; // A1
      BoardData root =
          BoardData.snapshot(
              stones,
              Optional.empty(),
              Stone.EMPTY,
              true,
              new Zobrist(),
              10,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0);
      Branch.Position position = new Branch.Position(BOARD_SIZE, BOARD_SIZE, root, stones, true);
      Branch.Input input = new Branch.Input(position, List.of("B2"), null, 1, true, false);

      Branch branch = new Branch(input);

      assertEquals(1, branch.length);
      assertTrue(branch.data.isMoveNode());
      assertEquals(Stone.WHITE_CAPTURED, branch.data.stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.BLACK, branch.data.stones[Board.getIndex(1, 0)]);
      assertTrue(branch.isNewStone[Board.getIndex(1, 0)]);
      assertFalse(branch.isNewStone[Board.getIndex(0, 0)]);
      assertEquals(1, branch.data.moveNumberList[Board.getIndex(1, 0)]);
    } finally {
      env.close();
    }
  }

  @Test
  void branchNoCaptureLeavesStonesIntactWhenDisabled() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Stone[] stones = new Stone[BOARD_AREA];
      Arrays.fill(stones, Stone.EMPTY);
      stones[Board.getIndex(0, 0)] = Stone.WHITE; // A2
      stones[Board.getIndex(0, 1)] = Stone.BLACK; // A1
      BoardData root =
          BoardData.snapshot(
              stones,
              Optional.empty(),
              Stone.EMPTY,
              true,
              new Zobrist(),
              10,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0);
      Branch.Position position = new Branch.Position(BOARD_SIZE, BOARD_SIZE, root, stones, true);
      Branch.Input input = new Branch.Input(position, List.of("B2"), null, 1, false, false);

      Branch branch = new Branch(input);

      assertEquals(1, branch.length);
      assertTrue(branch.data.isMoveNode());
      assertEquals(Stone.WHITE, branch.data.stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.BLACK, branch.data.stones[Board.getIndex(1, 0)]);
    } finally {
      env.close();
    }
  }

  @Test
  void branchPvVisitsRecordedWhenEnabledAndMatchingVariationSize() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Stone[] stones = emptyStones();
      BoardData root = snapshotData(Optional.empty(), Stone.EMPTY, true, 10);
      Branch.Position position = new Branch.Position(BOARD_SIZE, BOARD_SIZE, root, stones, true);

      // 1. Visits recorded when enabled and sizes match
      Branch.Input inputWithVisits =
          new Branch.Input(position, List.of("A2", "B1"), List.of("100", "200"), 2, false, true);
      Branch branch = new Branch(inputWithVisits);
      assertEquals(2, branch.length);
      assertEquals(100, branch.pvVisitsList[Board.getIndex(0, 0)]);
      assertEquals(200, branch.pvVisitsList[Board.getIndex(1, 1)]);
      assertEquals(0, branch.pvVisitsList[Board.getIndex(0, 1)]);
      assertEquals(0, branch.pvVisitsList[Board.getIndex(1, 0)]);

      // 2. Visits not recorded when recordPvVisits is false
      Branch.Input inputDisabled =
          new Branch.Input(position, List.of("A2", "B1"), List.of("100", "200"), 2, false, false);
      Branch branchDisabled = new Branch(inputDisabled);
      assertEquals(0, branchDisabled.pvVisitsList[Board.getIndex(0, 0)]);
      assertEquals(0, branchDisabled.pvVisitsList[Board.getIndex(1, 1)]);

      // 3. Visits not recorded when size mismatch
      Branch.Input inputMismatch =
          new Branch.Input(position, List.of("A2", "B1"), List.of("100"), 2, false, true);
      Branch branchMismatch = new Branch(inputMismatch);
      assertEquals(0, branchMismatch.pvVisitsList[Board.getIndex(0, 0)]);
      assertEquals(0, branchMismatch.pvVisitsList[Board.getIndex(1, 1)]);
    } finally {
      env.close();
    }
  }

  @Test
  void branchSmallRectangularBoardUsesCapturedDimensions() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      int width = 2;
      int height = 3;
      int area = width * height;
      Stone[] stones = new Stone[area];
      Arrays.fill(stones, Stone.EMPTY);

      BoardData root =
          BoardData.snapshot(
              stones,
              Optional.empty(),
              Stone.EMPTY,
              true,
              new Zobrist(),
              5,
              new int[area],
              0,
              0,
              50,
              0);

      // Deliberately set global dimensions to something completely different
      Board.boardWidth = 19;
      Board.boardHeight = 19;

      Branch.Position position = new Branch.Position(width, height, root, stones, true);

      // On height 3, row 1 is y = 3 - 1 = 2; column B is x = 1. Coord is (1, 2).
      // Index is x * height + y = 1 * 3 + 2 = 5.
      Branch.Input input = new Branch.Input(position, List.of("B1"), null, 1, false, false);
      Branch branch = new Branch(input);

      assertEquals(1, branch.length);
      assertEquals(area, branch.data.stones.length);
      assertEquals(area, branch.data.moveNumberList.length);
      assertEquals(area, branch.isNewStone.length);
      assertEquals(area, branch.pvVisitsList.length);
      assertTrue(branch.isNewStone[5]);
      assertEquals(Stone.BLACK, branch.data.stones[5]);
      assertEquals(1, branch.data.moveNumberList[5]);
      assertTrue(branch.data.lastMove.isPresent());
      assertArrayEquals(new int[] {1, 2}, branch.data.lastMove.get());
    } finally {
      env.close();
    }
  }

  @Test
  void frozenInputRemainsUnaffectedBySubsequentMutations() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Stone[] liveStones = emptyStones();
      BoardData source = snapshotData(Optional.empty(), Stone.EMPTY, true, 10);
      source.dummy = true;

      Branch.Position position =
          new Branch.Position(BOARD_SIZE, BOARD_SIZE, source, liveStones, true);

      List<String> liveVariation = new ArrayList<>(List.of("A2"));
      List<String> livePvVisits = new ArrayList<>(List.of("999"));

      Branch.Input input = new Branch.Input(position, liveVariation, livePvVisits, 1, true, true);

      // Now mutate live stones, source, lists, configs, globals, and turn
      liveStones[0] = Stone.BLACK;
      liveStones[1] = Stone.WHITE;
      source.moveNumber = 500;
      source.dummy = false;
      source.blackToPlay = false;
      liveVariation.clear();
      liveVariation.add("B1");
      livePvVisits.clear();
      livePvVisits.add("111");
      Board.boardWidth = 99;
      Board.boardHeight = 99;
      Lizzie.config.removeDeadChainInVariation = false;
      Lizzie.config.noCapture = true;
      Lizzie.config.showPvVisitsAllMove = false;
      Lizzie.config.showPvVisitsLastMove = false;

      // Calculate branch with the frozen input
      Branch branch = new Branch(input);

      assertEquals(1, branch.length);
      assertEquals(11, branch.data.moveNumber, "move number should advance from captured 10 to 11");
      assertTrue(branch.data.dummy, "dummy flag should remain preserved from capture time");
      assertEquals(BOARD_AREA, branch.data.stones.length);
      assertEquals(BOARD_AREA, branch.isNewStone.length);
      assertTrue(branch.isNewStone[0], "A2 should be played, not B1");
      assertEquals(Stone.BLACK, branch.data.stones[0]);
      assertEquals(
          Stone.EMPTY, branch.data.stones[1], "mutations to liveStones must not affect position");
      assertEquals(999, branch.pvVisitsList[0], "captured pv visits must remain unaffected");
      assertArrayEquals(new int[] {0, 0}, branch.data.lastMove.get());
    } finally {
      env.close();
    }
  }

  @Test
  void inputReusesSafelyAcrossCalculations() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Stone[] stones = emptyStones();
      BoardData source = snapshotData(Optional.empty(), Stone.EMPTY, true, 10);
      Branch.Position position = new Branch.Position(BOARD_SIZE, BOARD_SIZE, source, stones, true);
      Branch.Input input =
          new Branch.Input(position, List.of("A2", "B1"), List.of("50", "60"), 2, false, true);

      Branch branch1 = new Branch(input);
      Branch branch2 = new Branch(input);

      assertEquals(branch1.length, branch2.length);
      assertEquals(branch1.data.moveNumber, branch2.data.moveNumber);
      assertEquals(branch1.data.lastMoveColor, branch2.data.lastMoveColor);
      assertArrayEquals(branch1.data.lastMove.get(), branch2.data.lastMove.get());
      assertArrayEquals(branch1.data.stones, branch2.data.stones);
      assertArrayEquals(branch1.data.moveNumberList, branch2.data.moveNumberList);
      assertArrayEquals(branch1.isNewStone, branch2.isNewStone);
      assertArrayEquals(branch1.pvVisitsList, branch2.pvVisitsList);
    } finally {
      env.close();
    }
  }

  @Test
  void branchInvalidCoordinateStopsVariation() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = boardWithRoot(snapshotData(Optional.empty(), Stone.EMPTY, true, 10));

      BoardData data = board.getData();
      Branch.Position position =
          new Branch.Position(
              Board.boardWidth, Board.boardHeight, data, board.getStones(), data.blackToPlay);
      Branch.Input input =
          new Branch.Input(position, List.of("A2", "INVALID_COORD", "B1"), null, 3, false, false);

      Branch branch = new Branch(input);

      assertEquals(1, branch.length, "invalid coordinate should terminate variation");
      assertEquals(Stone.BLACK, branch.data.stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.EMPTY, branch.data.stones[Board.getIndex(1, 1)]);
      assertEquals(0, branch.data.moveNumberList[Board.getIndex(1, 1)]);
    } finally {
      env.close();
    }
  }

  private static Branch createBranch(Board board, List<String> variation) {
    BoardData data = board.getData();
    Branch.Position position =
        new Branch.Position(
            Board.boardWidth, Board.boardHeight, data, board.getStones(), data.blackToPlay);
    Branch.Input input =
        new Branch.Input(position, variation, null, variation.size(), false, false);
    return new Branch(input);
  }

  private static Board boardWithRoot(BoardData root) throws Exception {
    Board board = allocate(Board.class);
    board.setHistory(new BoardHistoryList(root));
    return board;
  }

  private static BoardData snapshotData(
      Optional<int[]> lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    Stone[] stones = emptyStones();
    lastMove.ifPresent(coords -> stones[Board.getIndex(coords[0], coords[1])] = lastMoveColor);
    return BoardData.snapshot(
        stones,
        lastMove,
        lastMoveColor,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    Arrays.fill(stones, Stone.EMPTY);
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

    private TestEnvironment(
        int previousBoardWidth, int previousBoardHeight, Config previousConfig) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
    }

    private static TestEnvironment open() throws Exception {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Config previousConfig = Lizzie.config;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      Config config = allocate(Config.class);
      config.removeDeadChainInVariation = false;
      config.noCapture = false;
      config.showPvVisitsAllMove = false;
      config.showPvVisitsLastMove = false;
      config.showHeat = false;
      config.showHeatAfterCalc = false;
      config.persisted = new JSONObject().put("ui-persist", new JSONObject().put("max-alpha", 240));
      Lizzie.config = config;
      return new TestEnvironment(previousBoardWidth, previousBoardHeight, previousConfig);
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
    private static final sun.misc.Unsafe UNSAFE;

    static {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        UNSAFE = (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }
}
