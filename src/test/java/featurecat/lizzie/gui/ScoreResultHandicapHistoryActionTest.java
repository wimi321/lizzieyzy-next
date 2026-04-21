package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.ListResourceBundle;
import java.util.Optional;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class ScoreResultHandicapHistoryActionTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;
  private static final ResourceBundle TEST_BUNDLE =
      new ListResourceBundle() {
        @Override
        protected Object[][] getContents() {
          return new Object[][] {
            {"ScoreResult.lblBlackScore", "B:"},
            {"ScoreResult.lblWhiteScore", "W:"},
            {"ScoreResult.blackWin", "B+"},
            {"ScoreResult.whiteWin", "W+"},
            {"ScoreResult.points", ""},
            {"ScoreResult.lblUseTerritoryScoring", "territory"},
            {"ScoreResult.lblUseAreaScoring", "area"}
          };
        }
      };

  @Test
  void setScoreIgnoresSnapshotMarkersForWhiteHandicapBonusN() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board = boardWithHistory(historyWithSnapshotMarkerBeforeFirstWhite());
      Lizzie.leelaz.recentRulesLine = "= {\"whiteHandicapBonus\":\"N\"}";

      ScoreResult result = newScoreResult();
      result.setScore(10, 0, 0, 0, 0, 0, 0.0);

      assertEquals(
          "B:10+0=10.0",
          label(result, "blackScore").getText(),
          "snapshot markers should not reduce black's area score as handicap stones.");
    } finally {
      env.close();
    }
  }

  @Test
  void setScoreIgnoresBlackPassesForWhiteHandicapBonusNMinus1() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board = boardWithHistory(historyWithBlackPassBeforeFirstWhite());
      Lizzie.leelaz.recentRulesLine = "= {\"whiteHandicapBonus\":\"N-1\"}";

      ScoreResult result = newScoreResult();
      result.setScore(10, 0, 0, 0, 0, 0, 0.0);

      assertEquals(
          "B:10+0=10.0",
          label(result, "blackScore").getText(),
          "explicit black passes should not add handicap stones.");
    } finally {
      env.close();
    }
  }

  @Test
  void setScoreIgnoresDummyWhitePassesWhenCountingHandicapStones() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.board = boardWithHistory(historyWithDummyWhitePassBetweenBlackHandicapMoves());
      Lizzie.leelaz.recentRulesLine = "= {\"whiteHandicapBonus\":\"N\"}";

      ScoreResult result = newScoreResult();
      result.setScore(10, 0, 0, 0, 0, 0, 0.0);

      assertEquals(
          "B:8+0=8.0",
          label(result, "blackScore").getText(),
          "dummy white PASS placeholders should not clear black's handicap-stone count.");
    } finally {
      env.close();
    }
  }

  private static BoardHistoryList historyWithSnapshotMarkerBeforeFirstWhite() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(0, 0, Stone.BLACK, 1));
    history.add(snapshotNode(Optional.of(new int[] {1, 1}), Stone.BLACK, false, 1));
    history.add(moveNode(2, 2, Stone.WHITE, 2));
    return history;
  }

  private static BoardHistoryList historyWithBlackPassBeforeFirstWhite() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(0, 0, Stone.BLACK, 1));
    history.add(passNode(Stone.BLACK, false, 2));
    history.add(moveNode(2, 2, Stone.WHITE, 3));
    return history;
  }

  private static BoardHistoryList historyWithDummyWhitePassBetweenBlackHandicapMoves() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveNode(0, 0, Stone.BLACK, 1));
    history.add(dummyPassNode(Stone.WHITE, true, 2));
    history.add(moveNode(1, 0, Stone.BLACK, 3));
    history.add(moveNode(2, 2, Stone.WHITE, 4));
    return history;
  }

  private static Board boardWithHistory(BoardHistoryList history) throws Exception {
    Board board = allocate(Board.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    board.setHistory(history);
    return board;
  }

  private static ScoreResult newScoreResult() throws Exception {
    ScoreResult result = allocate(ScoreResult.class);
    setField(result, "lblRule", new JFontLabel());
    setField(result, "blackScore", new JFontLabel());
    setField(result, "whiteScore", new JFontLabel());
    setField(result, "scoreResult", new JFontLabel());
    return result;
  }

  private static JFontLabel label(ScoreResult result, String fieldName) throws Exception {
    Field field = ScoreResult.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (JFontLabel) field.get(result);
  }

  private static void setField(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static BoardData moveNode(int x, int y, Stone color, int moveNumber) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(x, y)] = color;
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        color == Stone.WHITE,
        zobrist(stones),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static BoardData passNode(Stone color, boolean blackToPlay, int moveNumber) {
    return BoardData.pass(
        emptyStones(),
        color,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private static BoardData dummyPassNode(Stone color, boolean blackToPlay, int moveNumber) {
    BoardData data = passNode(color, blackToPlay, moveNumber);
    data.dummy = true;
    return data;
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
    private final Board previousBoard;
    private final Leelaz previousLeelaz;
    private final Config previousConfig;
    private final ResourceBundle previousResourceBundle;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Board previousBoard,
        Leelaz previousLeelaz,
        Config previousConfig,
        ResourceBundle previousResourceBundle) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousBoard = previousBoard;
      this.previousLeelaz = previousLeelaz;
      this.previousConfig = previousConfig;
      this.previousResourceBundle = previousResourceBundle;
    }

    private static TestEnvironment open() throws Exception {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Board previousBoard = Lizzie.board;
      Leelaz previousLeelaz = Lizzie.leelaz;
      Config previousConfig = Lizzie.config;
      ResourceBundle previousResourceBundle = Lizzie.resourceBundle;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      Config config = allocate(Config.class);
      config.useTerritoryInScore = false;
      Lizzie.config = config;
      Lizzie.leelaz = allocate(Leelaz.class);
      Lizzie.resourceBundle = TEST_BUNDLE;

      return new TestEnvironment(
          previousBoardWidth,
          previousBoardHeight,
          previousBoard,
          previousLeelaz,
          previousConfig,
          previousResourceBundle);
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.config = previousConfig;
      Lizzie.resourceBundle = previousResourceBundle;
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
