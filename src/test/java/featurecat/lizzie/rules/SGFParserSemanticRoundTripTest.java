package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SGFParser model-semantic tests and parse → write → parse round-trip. Complements existing
 * root-setup / node-kind SGF coverage; asserts board/history meaning rather than SGF bytes.
 */
class SGFParserSemanticRoundTripTest {
  private static final int SIZE = 5;

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void exactRootAndSparseCandidateMetricsSurviveAdoptAndRepeatedSave(String tag) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      boolean secondary = tag.endsWith("2");
      String sgf = "(;SZ[5]" + tag
          + "[KataGo 45 12.3k 2.5 1.2 0 rootVisits=12345\n"
          + "move C3 visits 50000 winrate 5500 order 10 edgeVisits 0 pv C3 D4])";
      for (int round = 0; round < 3; round++) {
        BoardHistoryList history = SGFParser.parseSgf(sgf, true);
        Lizzie.board.setHistory(history);
        BoardData data = history.getStart().getData();
        assertEquals(12345, secondary ? data.getPlayouts2() : data.getPlayouts());
        assertEquals(12345, secondary ? data.rootVisits2 : data.rootVisits);
        var move = (secondary ? data.bestMoves2 : data.bestMoves).get(0);
        assertEquals(50000, move.playouts);
        assertEquals(10, move.order);
        assertEquals(0, move.edgeVisits);
        assertEquals(List.of("C3", "D4"), move.variation);
        BoardData copy = data.clone();
        assertEquals(12345, secondary ? copy.rootVisits2 : copy.rootVisits);
        sgf = SGFParser.saveToString(false);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"LZ", "LZOP", "LZ2", "LZOP2"})
  void headerOnlyExactRootAndDowngradedLegacyRemainDistinct(String tag) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      boolean secondary = tag.endsWith("2");
      String sgf = "(;SZ[5]" + tag + "[KataGo 45 12.3k 2.5 1.2 0 rootVisits=12345])";
      for (int round = 0; round < 2; round++) {
        BoardHistoryList history = SGFParser.parseSgf(sgf, true);
        Lizzie.board.setHistory(history);
        BoardData data = history.getStart().getData();
        assertEquals(12345, secondary ? data.getPlayouts2() : data.getPlayouts());
        assertEquals(12345, secondary ? data.rootVisits2 : data.rootVisits);
        sgf = SGFParser.saveToString(false);
      }
      BoardData legacy = SGFParser.parseSgf(
          "(;SZ[5]" + tag + "[KataGo 45 12k 2.5 1.2 0])", true).getStart().getData();
      assertEquals(12000, secondary ? legacy.getPlayouts2() : legacy.getPlayouts());
      assertEquals(-1, secondary ? legacy.rootVisits2 : legacy.rootVisits);
    }
  }

  @Test
  void mainlineMovesPreserveOrderColorAndBoard() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history =
          SGFParser.parseSgf("(;SZ[5];B[ba];W[cc];B[de];W[ee])", true);

      assertEquals(List.of("BLACK 1,0", "WHITE 2,2", "BLACK 3,4", "WHITE 4,4"), mainlineMoves(history));
      BoardHistoryNode end = history.getEnd();
      assertEquals(Stone.BLACK, end.getData().stones[Board.getIndex(1, 0)]);
      assertEquals(Stone.WHITE, end.getData().stones[Board.getIndex(2, 2)]);
      assertEquals(4, end.getData().moveNumber);
      assertTrue(end.getData().blackToPlay);
    }
  }

  @Test
  void variationsKeepParentChildStructureAndDistinctBoards() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history =
          SGFParser.parseSgf("(;SZ[5];B[aa](;W[bb];B[cc])(;W[dd];B[ee]))", true);

      BoardHistoryNode root = history.getStart();
      BoardHistoryNode black = root.next().orElseThrow();
      assertEquals(2, black.numberOfChildren());
      BoardHistoryNode mainWhite = black.getVariation(0).orElseThrow();
      BoardHistoryNode varWhite = black.getVariation(1).orElseThrow();
      assertTrue(RulesLayerTestHarness.sameLastMove(mainWhite.getData(), 1, 1));
      assertTrue(RulesLayerTestHarness.sameLastMove(varWhite.getData(), 3, 3));
      assertSame(black, mainWhite.previous().orElseThrow());
      assertSame(black, varWhite.previous().orElseThrow());
      assertEquals(Stone.WHITE, mainWhite.getData().stones[Board.getIndex(1, 1)]);
      assertEquals(Stone.EMPTY, mainWhite.getData().stones[Board.getIndex(3, 3)]);
      assertEquals(Stone.WHITE, varWhite.getData().stones[Board.getIndex(3, 3)]);
      assertEquals(Stone.EMPTY, varWhite.getData().stones[Board.getIndex(1, 1)]);
      assertEquals(List.of("BLACK 0,0", "WHITE 1,1", "BLACK 2,2"), mainlineMoves(history));
    }
  }

  @Test
  void abAwAndPlApplyAsRootSetupNotAsMoves() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history =
          SGFParser.parseSgf("(;SZ[5]AB[aa][ee]AW[cc]PL[W])", true);

      BoardHistoryNode root = history.getStart();
      assertEquals(0, root.numberOfChildren());
      assertEquals(0, root.getData().moveNumber);
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(4, 4)]);
      assertEquals(Stone.WHITE, root.getData().stones[Board.getIndex(2, 2)]);
      assertFalse(root.getData().blackToPlay);
      assertTrue(root.getData().getProperties().containsKey("AB"));
      assertTrue(root.getData().getProperties().containsKey("AW"));
    }
  }

  @Test
  void komiIsAppliedWhenParseSgfFirstFlagIsTrue() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList withKomi = SGFParser.parseSgf("(;SZ[5]KM[6.5];B[aa])", true);
      assertEquals(6.5, withKomi.getGameInfo().getKomi(), 0.0001);

      BoardHistoryList withoutFirstFlag = SGFParser.parseSgf("(;SZ[5]KM[6.5];B[aa])", false);
      assertEquals(
          7.5,
          withoutFirstFlag.getGameInfo().getKomi(),
          0.0001,
          "parseSgf(..., false) currently skips applying the KM tag to GameInfo.");
    }
  }

  @Test
  void handicapPropertySetsGameInfoAndWhiteToPlayOnAbSetup() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history =
          SGFParser.parseSgf("(;SZ[5]HA[2]AB[aa][ee];W[cc])", true);

      assertEquals(2, history.getGameInfo().getHandicap());
      BoardHistoryNode root = history.getStart();
      assertFalse(root.getData().blackToPlay, "handicap AB without PL should leave White to play.");
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(4, 4)]);
      assertEquals(1, root.numberOfChildren());
      assertEquals(Stone.WHITE, root.next().orElseThrow().getData().lastMoveColor);
    }
  }

  @Test
  void passIsEmptyMoveValueAndDoesNotChangeStones() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5];B[cc];W[];B[aa])", true);

      BoardHistoryNode pass = history.getStart().next().orElseThrow().next().orElseThrow();
      assertTrue(pass.getData().isPassNode());
      assertEquals(Stone.WHITE, pass.getData().lastMoveColor);
      assertEquals(Stone.BLACK, pass.getData().stones[Board.getIndex(2, 2)]);
      assertEquals(Stone.EMPTY, pass.getData().stones[Board.getIndex(0, 0)]);
      BoardHistoryNode afterPass = pass.next().orElseThrow();
      assertEquals(Stone.BLACK, afterPass.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(3, afterPass.getData().moveNumber);
    }
  }

  @Test
  void commentsSupportChineseUnicodeEscapedBracketBackslashAndMultiline() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      String sgf =
          "(;SZ[5]C[根注释：中文 and \\] and \\\\ slash\nsecond line];B[aa]C[move \\] and κ])";
      BoardHistoryList history = SGFParser.parseSgf(sgf, true);

      assertEquals(
          "根注释：中文 and ] and \\ slash\nsecond line",
          history.getStart().getData().comment);
      assertEquals(
          "move ] and κ",
          history.getStart().next().orElseThrow().getData().comment);
    }
  }

  @Test
  void unknownNonCriticalPropertiesAreKeptOnTheModel() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history =
          SGFParser.parseSgf("(;SZ[5]XX[keep-me]GN[Friendly];B[aa]LB[aa:A])", true);

      assertEquals("keep-me", history.getStart().getData().getProperty("XX"));
      assertEquals("Friendly", history.getStart().getData().getProperty("GN"));
      assertEquals("aa:A", history.getStart().next().orElseThrow().getData().getProperty("LB"));
    }
  }

  @Test
  void tolerantPrefixSuffixAndOptionalRootSemicolonStillParse() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList wrapped =
          SGFParser.parseSgf("junk before\n(;SZ[5];B[aa]) trailing", true);
      assertNotNull(wrapped);
      assertEquals(List.of("BLACK 0,0"), mainlineMoves(wrapped));

      BoardHistoryList optionalSemicolon = SGFParser.parseSgf("(SZ[5];B[bb])", true);
      assertNotNull(optionalSemicolon);
      assertEquals(List.of("BLACK 1,1"), mainlineMoves(optionalSemicolon));
    }
  }

  @Test
  void emptyAndNonSgfStringsParseWithoutThrowing() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      assertDoesNotThrow(() -> SGFParser.parseSgf("", true));
      assertNull(SGFParser.parseSgf("", true));
      assertDoesNotThrow(() -> SGFParser.parseSgf("not sgf", true));
      assertFalse(SGFParser.isSGF("not sgf"));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "not sgf",
        "(",
        ")",
        ";",
        "B[aa]",
        "(;SZ[5];B[",
        "(;SZ[5];B[aa]"
      })
  void incompleteOrNonSgfInputIsHandledWithoutThrowing(String raw) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList parsed = assertDoesNotThrow(() -> SGFParser.parseSgf(raw, true));
      if (parsed != null) {
        assertTrue(
            parsed.getStart() != null,
            "a parsed tree should have a root even when the input is nonstandard.");
      }
    }
  }

  @Test
  void invalidCoordinatesAreDroppedRatherThanThrown() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history =
          assertDoesNotThrow(() -> SGFParser.parseSgf("(;SZ[5];B[zz];W[aa])", true));
      assertNotNull(history);
      // Current behavior: off-board B[zz] is ignored, so White's move becomes the first child.
      assertEquals(List.of("WHITE 0,0"), mainlineMoves(history));
    }
  }

  @Test
  void parseAppliesCapturesAndMoveNumbers() throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[5];B[ba];W[aa];B[ab])", true);
      BoardHistoryNode capture = history.getEnd();
      assertEquals(Stone.EMPTY, capture.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(1, capture.getData().blackCaptures);
      assertEquals(3, capture.getData().moveNumber);
    }
  }

  @Test
  void representativeSemanticRoundTripPreservesTreeSetupAndText() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      String source =
          "(;SZ[5]KM[6.5]HA[2]PL[W]AB[ee]AW[ce]"
              + "C[根注释：中文 and \\] and \\\\ slash\nsecond line]"
              + "XX[keep-me]"
              + ";W[ba]C[white approach]"
              + ";B[aa]"
              + ";W[ab]C[captures the corner]"
              + ";B[]C[black pass]"
              + ";W[cc]"
              + "(;B[cd]C[mainline]"
              + ";W[dc])"
              + "(;B[db]C[branch unicode κ]))";

      BoardHistoryList first = SGFParser.parseSgf(source, true);
      assertNotNull(first);
      Lizzie.board.setHistory(first);
      String written = SGFParser.saveToString(false);
      BoardHistoryList second = SGFParser.parseSgf(written, true);
      assertNotNull(second);

      assertTreeSemanticsEqual(first, second);
      assertFalse(
          written.equals(source),
          "round-trip is allowed to change whitespace, property order, and wrappers.");
    }
  }

  @Test
  void livePlayCaptureVariationAndCommentRoundTrip() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE)) {
      Board board = env.board();
      board.getHistory().getGameInfo().setKomiNoMenu(0.5);
      board.setupPlaceStone(4, 4, Stone.BLACK);
      board.setupSetSideToPlay(true);
      board.getHistory().getStart().getData().comment = "live root 中文";

      board.place(1, 0, Stone.BLACK);
      board.place(0, 0, Stone.WHITE);
      board.place(0, 1, Stone.BLACK);
      env.current().getData().comment = "captured with ] and \\";
      BoardHistoryNode capture = env.current();
      board.pass(Stone.WHITE);
      board.previousMove(false);
      board.place(2, 2, Stone.WHITE, true);
      env.current().getData().comment = "variation";

      String written = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(written, true);
      assertNotNull(roundTrip);

      assertEquals(0.5, roundTrip.getGameInfo().getKomi(), 0.0001);
      assertEquals(Stone.BLACK, roundTrip.getStart().getData().stones[Board.getIndex(4, 4)]);
      assertTrue(roundTrip.getStart().getData().comment.contains("live root 中文"));

      BoardHistoryNode rtCapture = findCaptureNode(roundTrip.getStart());
      assertNotNull(rtCapture);
      assertEquals(1, rtCapture.getData().blackCaptures);
      assertEquals(Stone.EMPTY, rtCapture.getData().stones[Board.getIndex(0, 0)]);
      assertTrue(rtCapture.getData().comment.contains("captured with ] and \\"));
      assertEquals(2, capture.numberOfChildren());
      assertEquals(2, rtCapture.numberOfChildren());
    }
  }

  private static BoardHistoryNode findCaptureNode(BoardHistoryNode root) {
    ArrayList<BoardHistoryNode> pending = new ArrayList<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      BoardHistoryNode node = pending.remove(pending.size() - 1);
      if (node.getData().blackCaptures > 0 || node.getData().whiteCaptures > 0) {
        return node;
      }
      pending.addAll(node.getVariations());
    }
    return null;
  }

  static List<String> mainlineMoves(BoardHistoryList history) {
    List<String> moves = new ArrayList<>();
    BoardHistoryNode node = history.getStart();
    while (node.next().isPresent()) {
      node = node.next().get();
      moves.add(describeMove(node));
    }
    return moves;
  }

  private static String describeMove(BoardHistoryNode node) {
    BoardData data = node.getData();
    if (data.isPassNode()) {
      return data.lastMoveColor + " pass";
    }
    if (data.lastMove.isPresent()) {
      int[] coord = data.lastMove.get();
      return data.lastMoveColor + " " + coord[0] + "," + coord[1];
    }
    return String.valueOf(data.getNodeKind());
  }

  static void assertTreeSemanticsEqual(BoardHistoryList expected, BoardHistoryList actual) {
    assertEquals(expected.getGameInfo().getKomi(), actual.getGameInfo().getKomi(), 0.0001);
    assertEquals(expected.getGameInfo().getHandicap(), actual.getGameInfo().getHandicap());
    assertNodeSemanticsEqual(expected.getStart(), actual.getStart());
  }

  private static void assertNodeSemanticsEqual(BoardHistoryNode expected, BoardHistoryNode actual) {
    BoardData expectedData = expected.getData();
    BoardData actualData = actual.getData();
    assertEquals(expectedData.getNodeKind(), actualData.getNodeKind());
    assertEquals(expectedData.moveNumber, actualData.moveNumber);
    assertEquals(expectedData.lastMoveColor, actualData.lastMoveColor);
    assertEquals(expectedData.blackToPlay, actualData.blackToPlay);
    assertEquals(expectedData.blackCaptures, actualData.blackCaptures);
    assertEquals(expectedData.whiteCaptures, actualData.whiteCaptures);
    assertEquals(expectedData.dummy, actualData.dummy);
    assertEquals(normalizeComment(expectedData.comment), normalizeComment(actualData.comment));
    assertLastMoveEqual(expectedData.lastMove, actualData.lastMove);
    assertArrayEquals(expectedData.stones, actualData.stones);
    assertPropertyPresentIfOriginalHadIt(expectedData, actualData, "XX");
    assertPropertyPresentIfOriginalHadIt(expectedData, actualData, "AB");
    assertPropertyPresentIfOriginalHadIt(expectedData, actualData, "AW");
    assertEquals(expected.numberOfChildren(), actual.numberOfChildren());
    for (int i = 0; i < expected.numberOfChildren(); i++) {
      assertNodeSemanticsEqual(
          expected.getVariation(i).orElseThrow(), actual.getVariation(i).orElseThrow());
    }
  }

  private static void assertLastMoveEqual(Optional<int[]> expected, Optional<int[]> actual) {
    assertEquals(expected.isPresent(), actual.isPresent());
    expected.ifPresent(value -> assertArrayEquals(value, actual.orElseThrow()));
  }

  private static void assertPropertyPresentIfOriginalHadIt(
      BoardData expected, BoardData actual, String key) {
    String expectedValue = expected.getProperty(key);
    if (expectedValue != null && !expectedValue.isEmpty()) {
      assertNotNull(actual.getProperty(key), "property " + key + " should survive round-trip");
    }
  }

  private static String normalizeComment(String comment) {
    return comment == null ? "" : comment;
  }
}
