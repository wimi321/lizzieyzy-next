package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AnalysisRequestBuilderTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @ParameterizedTest
  @CsvSource({
    "false, false, false",
    "true, false, false",
    "false, true, false",
    "false, false, true",
    "true, true, false",
    "true, false, true",
    "false, true, true",
    "true, true, true"
  })
  void booleanFlagsAreCopiedIndependentlyIntoTheRequest(
      boolean includePVVisits, boolean includeOwnership, boolean includeMovesOwnership)
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryNode root = emptyHistoryRoot();

      JSONObject request =
          payload(
              AnalysisRequestBuilder.buildRequest(
                  "flags",
                  root,
                  50,
                  includePVVisits,
                  includeOwnership,
                  includeMovesOwnership));

      assertEquals("flags", request.getString("id"));
      assertEquals(50, request.getInt("maxVisits"));
      assertEquals(includePVVisits, request.getBoolean("includePVVisits"));
      assertEquals(includeOwnership, request.getBoolean("includeOwnership"));
      assertEquals(includeMovesOwnership, request.getBoolean("includeMovesOwnership"));
      assertFalse(
          includeOwnership && request.getBoolean("includeMovesOwnership") != includeMovesOwnership,
          "ownership and moves-ownership are independent builder arguments");
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, Integer.MAX_VALUE, -1})
  void maxVisitsIsEmittedAsGivenIncludingBoundaries(int maxVisits) throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      JSONObject request =
          payload(
              AnalysisRequestBuilder.buildRequest(
                  "visits", emptyHistoryRoot(), maxVisits, false, false, false));

      assertEquals(maxVisits, request.getInt("maxVisits"));
    }
  }

  @Test
  void emptyBoardOmitsOptionalSetupFieldsAndAnalyzesTurnZero() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      JSONObject request =
          payload(
              AnalysisRequestBuilder.buildRequest(
                  "empty", emptyHistoryRoot(), 10, false, false, false));

      assertFalse(request.has("initialStones"));
      assertFalse(request.has("initialPlayer"));
      assertEquals(List.of(), request.getJSONArray("moves").toList());
      assertEquals(List.of(0), request.getJSONArray("analyzeTurns").toList());
      assertEquals(BOARD_SIZE, request.getInt("boardXSize"));
      assertEquals(BOARD_SIZE, request.getInt("boardYSize"));
      assertEquals(GameInfo.DEFAULT_KOMI, request.getDouble("komi"), 0.0001);
      assertEquals("tromp-taylor", request.get("rules"));
      assertEquals(
          "SIDETOMOVE",
          request.getJSONObject("overrideSettings").getString("reportAnalysisWinratesAs"));
    }
  }

  @Test
  void snapshotRootSendsInitialStonesAndPlayerWithoutHistoryMoves() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history =
          new BoardHistoryList(
              snapshotNode(
                  stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
                  Optional.empty(),
                  Stone.EMPTY,
                  false,
                  58));
      boardWithHistory(history);

      JSONObject request =
          payload(
              AnalysisRequestBuilder.buildRequest(
                  "snapshot", history.getCurrentHistoryNode(), 20, false, false, false));

      assertEquals(Set.of(List.of("B", "A3"), List.of("W", "B3")), stonePairs(request));
      assertEquals("W", request.getString("initialPlayer"));
      assertEquals(List.of(), request.getJSONArray("moves").toList());
      assertEquals(List.of(0), request.getJSONArray("analyzeTurns").toList());
    }
  }

  @Test
  void configuredStartStonesAreUsedWhenTheRootIsNotASnapshotAnchor() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      boardWithHistory(history);
      Lizzie.board.hasStartStone = true;
      Lizzie.board.startStonelist = new ArrayList<>();
      Lizzie.board.startStonelist.add(startStone(0, 0, true));
      Lizzie.board.startStonelist.add(startStone(1, 0, false));
      history.getStart().getData().blackToPlay = false;

      JSONObject request =
          payload(
              AnalysisRequestBuilder.buildRequest(
                  "start-stones", history.getStart(), 8, false, false, false));

      assertEquals(Set.of(List.of("B", "A3"), List.of("W", "B3")), stonePairs(request));
      assertEquals("W", request.getString("initialPlayer"));
      assertEquals(List.of(), request.getJSONArray("moves").toList());
    }
  }

  @Test
  void historyMovesAndPassesAreSerializedInPlayOrder() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      history.add(
          passNode(stones(placement(0, 0, Stone.BLACK)), Stone.WHITE, true, 2));
      boardWithHistory(history);

      JSONObject request =
          payload(
              AnalysisRequestBuilder.buildRequest(
                  "history", history.getCurrentHistoryNode(), 16, false, false, false));

      assertEquals(
          List.of(List.of("B", "A3"), List.of("W", "pass")), pairs(request.getJSONArray("moves")));
      assertEquals(List.of(2), request.getJSONArray("analyzeTurns").toList());
      assertFalse(request.has("initialStones"));
      assertFalse(request.has("initialPlayer"));
    }
  }

  @Test
  void laterPositionMovesDoNotLeakIntoAnEarlierNodeRequest() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(
          moveNode(stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
      history.add(
          moveNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
      boardWithHistory(history);
      BoardHistoryNode root = history.getStart();
      BoardHistoryNode first = root.next().orElseThrow();
      BoardHistoryNode second = history.getCurrentHistoryNode();

      JSONObject leafFirst =
          payload(AnalysisRequestBuilder.buildRequest("leaf", second, 4, true, true, true));
      JSONObject rootAfterLeaf =
          payload(AnalysisRequestBuilder.buildRequest("root", root, 9, false, false, false));
      JSONObject firstMove =
          payload(AnalysisRequestBuilder.buildRequest("first", first, 9, false, false, false));

      assertEquals(
          List.of(List.of("B", "A3"), List.of("W", "B3")),
          pairs(leafFirst.getJSONArray("moves")));
      assertEquals(List.of(), rootAfterLeaf.getJSONArray("moves").toList());
      assertEquals(List.of(0), rootAfterLeaf.getJSONArray("analyzeTurns").toList());
      assertEquals(List.of(List.of("B", "A3")), pairs(firstMove.getJSONArray("moves")));
      assertEquals(List.of(1), firstMove.getJSONArray("analyzeTurns").toList());
      assertFalse(rootAfterLeaf.getBoolean("includePVVisits"));
      assertFalse(rootAfterLeaf.getBoolean("includeOwnership"));
      assertFalse(rootAfterLeaf.getBoolean("includeMovesOwnership"));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rulesModes")
  void rulesModeSelectsSpecificObjectCurrentEngineAutoLoadOrDefault(
      String name, RuleConfig rules, Object expectedRules) throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      rules.apply();

      JSONObject request =
          payload(
              AnalysisRequestBuilder.buildRequest(
                  name, emptyHistoryRoot(), 5, false, false, false));

      if (expectedRules instanceof JSONObject) {
        JSONObject expected = (JSONObject) expectedRules;
        JSONObject actual = request.getJSONObject("rules");
        for (String key : expected.keySet()) {
          assertEquals(expected.get(key), actual.get(key), key);
        }
        assertEquals(expected.length(), actual.length());
      } else {
        assertEquals(expectedRules, request.get("rules"));
      }
    }
  }

  static Stream<Arguments> rulesModes() {
    return Stream.of(
        Arguments.of(
            "specific-rules",
            RuleConfig.specific("{\"koRule\":\"SIMPLE\",\"scoringRule\":\"AREA\"}"),
            new JSONObject("{\"koRule\":\"SIMPLE\",\"scoringRule\":\"AREA\"}")),
        Arguments.of("specific-rules-blank", RuleConfig.specific(""), "tromp-taylor"),
        Arguments.of(
            "current-engine-rules",
            RuleConfig.currentEngine("= {\"scoringRule\":\"TERRITORY\"}"),
            new JSONObject("{\"scoringRule\":\"TERRITORY\"}")),
        Arguments.of(
            "autoload-kata-rules",
            RuleConfig.autoLoad("{\"taxRule\":\"NONE\"}"),
            new JSONObject("{\"taxRule\":\"NONE\"}")),
        Arguments.of(
            "autoload-disabled",
            new RuleConfig(true, "", "", false, "{\"taxRule\":\"SEVEN\"}"),
            "tromp-taylor"),
        Arguments.of(
            "current-engine-blank-falls-through",
            new RuleConfig(true, "", "", true, ""),
            "tromp-taylor"));
  }

  @Test
  void komiAndBoardSizeComeFromTheLiveGameNotFromPriorRequests() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryNode root = emptyHistoryRoot();
      Lizzie.board.getHistory().getGameInfo().setKomiNoMenu(0.5);
      JSONObject first =
          payload(AnalysisRequestBuilder.buildRequest("k1", root, 3, false, false, false));

      Lizzie.board.getHistory().getGameInfo().setKomiNoMenu(-6.5);
      Board.boardWidth = 5;
      Board.boardHeight = 4;
      JSONObject second =
          payload(AnalysisRequestBuilder.buildRequest("k2", root, 3, false, false, false));

      assertEquals(0.5, first.getDouble("komi"), 0.0001);
      assertEquals(BOARD_SIZE, first.getInt("boardXSize"));
      assertEquals(BOARD_SIZE, first.getInt("boardYSize"));
      assertEquals(-6.5, second.getDouble("komi"), 0.0001);
      assertEquals(5, second.getInt("boardXSize"));
      assertEquals(4, second.getInt("boardYSize"));
      assertEquals("k1", first.getString("id"));
      assertEquals("k2", second.getString("id"));
    }
  }

  @Test
  void returnedRequestObjectsDoNotShareOverrideSettings() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      BoardHistoryNode root = emptyHistoryRoot();
      JSONObject first = AnalysisRequestBuilder.buildRequest("a", root, 1, false, false, false);
      first.getJSONObject("overrideSettings").put("reportAnalysisWinratesAs", "BLACK");
      first.put("includePolicy", true);

      JSONObject second =
          payload(AnalysisRequestBuilder.buildRequest("b", root, 1, false, false, false));

      assertEquals(
          "SIDETOMOVE",
          second.getJSONObject("overrideSettings").getString("reportAnalysisWinratesAs"));
      assertFalse(second.has("includePolicy"));
      assertEquals("b", second.getString("id"));
    }
  }

  @Test
  void nullIdOmitsTheIdFieldFromTheSerializedRequest() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      JSONObject request =
          payload(
              AnalysisRequestBuilder.buildRequest(
                  null, emptyHistoryRoot(), 1, false, false, false));

      assertFalse(request.has("id"), "org.json omits a null id rather than sending JSON null");
    }
  }

  @Test
  void emptyIdIsSerializedAsAnEmptyString() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      JSONObject request =
          payload(
              AnalysisRequestBuilder.buildRequest("", emptyHistoryRoot(), 1, false, false, false));

      assertEquals("", request.getString("id"));
    }
  }

  @Test
  void nullAnalyzeNodeFailsFast() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      assertThrows(
          NullPointerException.class,
          () -> AnalysisRequestBuilder.buildRequest("n", null, 1, false, false, false));
    }
  }

  @Test
  void malformedSpecificRulesAreRejectedByTheJsonParser() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisUseCurrentRules = false;
      Lizzie.config.analysisSpecificRules = "{";

      assertThrows(
          JSONException.class,
          () ->
              AnalysisRequestBuilder.buildRequest(
                  "bad-rules", emptyHistoryRoot(), 1, false, false, false));
    }
  }

  @Test
  void nullSpecificRulesNpeWhenNotUsingCurrentEngineRules() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisUseCurrentRules = false;
      Lizzie.config.analysisSpecificRules = null;

      assertThrows(
          NullPointerException.class,
          () ->
              AnalysisRequestBuilder.buildRequest(
                  "null-rules", emptyHistoryRoot(), 1, false, false, false));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "=", "x", "=x"})
  void currentEngineRulesThatAreTooShortOrNotJsonFailAsToday(String currentRules) throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisUseCurrentRules = true;
      Lizzie.config.currentKataGoRules = currentRules;
      Lizzie.config.autoLoadKataRules = false;
      Lizzie.config.kataRules = "";

      if (currentRules.isEmpty()) {
        JSONObject request =
            payload(
                AnalysisRequestBuilder.buildRequest(
                    "short-rules", emptyHistoryRoot(), 1, false, false, false));
        assertEquals("tromp-taylor", request.get("rules"));
        return;
      }

      Class<? extends Exception> expected =
          currentRules.length() < 2 ? StringIndexOutOfBoundsException.class : JSONException.class;
      assertThrows(
          expected,
          () ->
              AnalysisRequestBuilder.buildRequest(
                  "short-rules", emptyHistoryRoot(), 1, false, false, false));
    }
  }

  @Test
  void currentEngineRulesWithoutTheEqualsSpacePrefixAreParsedFromTheThirdCharacter()
      throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config.analysisUseCurrentRules = true;
      Lizzie.config.currentKataGoRules = "{\"scoringRule\":\"AREA\"}";

      assertThrows(
          JSONException.class,
          () ->
              AnalysisRequestBuilder.buildRequest(
                  "unprefixed", emptyHistoryRoot(), 1, false, false, false),
          "addRules uses substring(2), so a raw JSON object is not accepted");
    }
  }

  private static JSONObject payload(JSONObject request) {
    return new JSONObject(request.toString());
  }

  private static Set<List<String>> stonePairs(JSONObject request) {
    return Set.copyOf(pairs(request.getJSONArray("initialStones")));
  }

  private static List<List<String>> pairs(JSONArray array) {
    List<List<String>> result = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONArray pair = array.getJSONArray(i);
      List<String> item = new ArrayList<>();
      for (int j = 0; j < pair.length(); j++) {
        item.add(pair.getString(j));
      }
      result.add(List.copyOf(item));
    }
    return result;
  }

  private static BoardHistoryNode emptyHistoryRoot() throws Exception {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    boardWithHistory(history);
    return history.getStart();
  }

  private static Board boardWithHistory(BoardHistoryList history) throws Exception {
    Board board = allocate(Board.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    board.setHistory(history);
    Lizzie.board = board;
    return board;
  }

  private static BoardData moveNode(
      Stone[] stones, int[] lastMove, Stone color, boolean blackToPlay, int moveNumber) {
    return BoardData.move(
        stones,
        lastMove,
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

  private static BoardData passNode(
      Stone[] stones, Stone color, boolean blackToPlay, int moveNumber) {
    return BoardData.pass(
        stones, color, blackToPlay, zobrist(stones), moveNumber, new int[BOARD_AREA], 0, 0, 50, 0);
  }

  private static BoardData snapshotNode(
      Stone[] stones,
      Optional<int[]> lastMove,
      Stone lastMoveColor,
      boolean blackToPlay,
      int moveNumber) {
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

  private static final class RuleConfig {
    private final boolean useCurrent;
    private final String specific;
    private final String currentEngine;
    private final boolean autoLoad;
    private final String kataRules;

    private RuleConfig(
        boolean useCurrent,
        String specific,
        String currentEngine,
        boolean autoLoad,
        String kataRules) {
      this.useCurrent = useCurrent;
      this.specific = specific;
      this.currentEngine = currentEngine;
      this.autoLoad = autoLoad;
      this.kataRules = kataRules;
    }

    private static RuleConfig specific(String rules) {
      return new RuleConfig(false, rules, "", false, "");
    }

    private static RuleConfig currentEngine(String rules) {
      return new RuleConfig(true, "", rules, false, "");
    }

    private static RuleConfig autoLoad(String rules) {
      return new RuleConfig(true, "", "", true, rules);
    }

    private void apply() {
      Lizzie.config.analysisUseCurrentRules = useCurrent;
      Lizzie.config.analysisSpecificRules = specific;
      Lizzie.config.currentKataGoRules = currentEngine;
      Lizzie.config.autoLoadKataRules = autoLoad;
      Lizzie.config.kataRules = kataRules;
    }
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
      TestEnvironment environment =
          new TestEnvironment(
              Board.boardWidth, Board.boardHeight, Lizzie.config, Lizzie.board, Lizzie.frame);
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      Config config = allocate(Config.class);
      config.analysisUseCurrentRules = false;
      config.analysisSpecificRules = "";
      config.currentKataGoRules = "";
      config.autoLoadKataRules = false;
      config.kataRules = "";
      Lizzie.config = config;
      Lizzie.frame = allocate(LizzieFrame.class);
      return environment;
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
