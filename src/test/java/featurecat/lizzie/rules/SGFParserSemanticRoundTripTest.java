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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SGFParser model-semantic tests and parse → write → parse round-trip. Complements existing
 * root-setup / node-kind SGF coverage; asserts board/history meaning rather than SGF bytes.
 */
class SGFParserSemanticRoundTripTest {
  private static final int SIZE = 5;
  @TempDir Path tempDirectory;

  @ParameterizedTest
  @ValueSource(strings = {"\n", "\\n", "\\r\\n"})
  void detachedAndLiveImportsKeepResultSeparateFromComments(String newline) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      String sgf = "(;SZ[5]RE[B+R]" + newline + ";B[aa](;W[bb])(;W[cc]))";
      BoardHistoryList live = Lizzie.board.getHistory();
      live.getGameInfo().setResult("W+R");
      RulesLayerTestHarness.TrackingFrame frame =
          (RulesLayerTestHarness.TrackingFrame) Lizzie.frame;
      int publications = frame.resultPublications;
      var titleField = featurecat.lizzie.gui.LizzieFrame.class.getDeclaredField("resultTitle");
      titleField.setAccessible(true);
      Object title = titleField.get(frame);
      BoardHistoryList detached = SGFParser.parseSgf(sgf, true);
      assertSame(live, Lizzie.board.getHistory());
      assertEquals("W+R", live.getGameInfo().getResult());
      assertEquals(title, titleField.get(frame));
      assertEquals(publications, frame.resultPublications);
      assertTrue(SGFParser.loadFromString(sgf, false));
      assertTreeSemanticsEqual(Lizzie.board.getHistory(), detached);
      assertEquals("B+R", detached.getGameInfo().getResult());
      Lizzie.board.setHistory(detached);
      assertTrue(SGFParser.loadFromString(SGFParser.saveToString(false), false));
      assertEquals("B+R", Lizzie.board.getHistory().getGameInfo().getResult());
      assertTreeSemanticsEqual(detached, Lizzie.board.getHistory());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "(;SZ[5]\\r;B[aa])", "(;SZ[5]\\\\n;B[aa])",
      "(;SZ[5]K\\nM[6.5];B[aa])", "(;SZ[5]K\\n\\nM[6.5];B[aa])",
      "(;SZ[5]\\n;B[aa](;W[bb])", "(;SZ[5]\\n;B[aa]C[unfinished)",
      "(;SZ[5]\\n;B[aa])\\n(;SZ[5]"
  })
  void ambiguousStructuralEscapesCannotReplaceLoadedHistory(String invalid) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      assertTrue(SGFParser.loadFromString("(;SZ[5];B[cc])", false));
      BoardHistoryList original = Lizzie.board.getHistory();
      BoardHistoryNode current = original.getCurrentHistoryNode();
      assertNull(SGFParser.parseSgf(invalid, true));
      assertFalse(SGFParser.loadFromString(invalid, false));
      assertSame(original, Lizzie.board.getHistory());
      assertFalse(SGFParser.loadFromStringforedit(invalid));
      assertSame(original, Lizzie.board.getHistory());
      assertSame(current, original.getCurrentHistoryNode());
      Path file = tempDirectory.resolve("ambiguous.sgf");
      Files.writeString(file, invalid);
      assertFalse(SGFParser.load(file.toString(), false, false));
      assertSame(original, Lizzie.board.getHistory());
      assertSame(current, original.getCurrentHistoryNode());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"\\n", "\\r\\n"})
  void structuralNewlinesPreserveMovesBranchesAndPropertyValues(String newline) throws Exception {
    try (RulesLayerTestHarness ignored = RulesLayerTestHarness.open(SIZE)) {
      String normal =
          "(;SZ[5]\nKM[6.5]XX[keep\\\\n]C[中文 \\] \\\\ slash\nsecond line]"
              + "\n;B[aa]\n(;W[bb]C[main];B[cc])\n(;W[dd]C[variation];B[ee]))";
      String escaped = normal.replace("\n;", newline + ";")
          .replace("\nKM", newline + "KM").replace("\n(", newline + "(");
      BoardHistoryList expected = SGFParser.parseSgf(normal, true);
      BoardHistoryList live = Lizzie.board.getHistory();
      BoardHistoryList actual = SGFParser.parseSgf(escaped, true);
      assertSame(live, Lizzie.board.getHistory());
      assertTreeSemanticsEqual(expected, actual);
      assertEquals(expected.getStart().getData().getProperty("XX"),
          actual.getStart().getData().getProperty("XX"));
      assertEquals(List.of("BLACK 0,0", "WHITE 1,1", "BLACK 2,2"), mainlineMoves(actual));
      assertTrue(SGFParser.loadFromString(escaped, false));
      assertTreeSemanticsEqual(expected, Lizzie.board.getHistory());
      assertTrue(SGFParser.loadFromStringforedit(escaped));
      assertTreeSemanticsEqual(expected, Lizzie.board.getHistory());
      Path input = tempDirectory.resolve("escaped.sgf");
      Files.writeString(input, escaped);
      assertTrue(SGFParser.load(input.toString(), false, false));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertTreeSemanticsEqual(expected, Lizzie.board.getHistory());
      assertEquals(escaped, Files.readString(input));
      Path saved = tempDirectory.resolve("saved.sgf");
      Files.writeString(saved, SGFParser.saveToString(false));
      assertTrue(SGFParser.load(saved.toString(), false, false));
      javax.swing.SwingUtilities.invokeAndWait(() -> {});
      assertTreeSemanticsEqual(expected, Lizzie.board.getHistory());
      assertTreeSemanticsEqual(expected, SGFParser.parseSgf(SGFParser.saveToString(false), true));
    }
  }

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

  @Test
  void foxStandaloneHandicapNormalizesToWhiteToPlayAndPreservesRoundTrip() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(19)) {
      String source = "(;SZ[19]HA[7]KM[0.5];AB[pd][dp][dd][pp][dj][pj][jj];W[fq];B[qq])";
      BoardHistoryList history = SGFParser.parseSgf(source, true);
      assertNotNull(history);

      BoardHistoryNode root = history.getStart();
      BoardData rootData = root.getData();
      assertEquals(BoardNodeKind.SNAPSHOT, rootData.getNodeKind());
      assertFalse(rootData.isHistoryActionNode());
      assertEquals(0, rootData.moveNumber);
      assertTrue(rootData.blackToPlay, "empty root defaults to Black to play");
      assertFalse(
          InitialHandicapSetup.isInitialHandicap(root, 7, 19, 19),
          "empty root with HA should not be recognized as initial handicap setup");

      BoardHistoryNode setupNode = root.next().orElseThrow();
      BoardData setupData = setupNode.getData();
      assertEquals(BoardNodeKind.SNAPSHOT, setupData.getNodeKind());
      assertFalse(setupData.isHistoryActionNode());
      assertEquals(0, setupData.moveNumber);
      assertFalse(
          setupData.blackToPlay,
          "eligible standalone initial handicap setup must be normalized to White to play");
      assertTrue(
          InitialHandicapSetup.isInitialHandicap(setupNode, 7, 19, 19),
          "standalone setup with 7 valid black stones and HA=7 must be recognized");

      BoardHistoryNode move1 = setupNode.next().orElseThrow();
      BoardData move1Data = move1.getData();
      assertEquals(BoardNodeKind.MOVE, move1Data.getNodeKind());
      assertTrue(move1Data.isHistoryActionNode());
      assertEquals(Stone.WHITE, move1Data.lastMoveColor);
      assertEquals(1, move1Data.moveNumber);
      assertTrue(move1Data.blackToPlay);

      BoardHistoryNode move2 = move1.next().orElseThrow();
      BoardData move2Data = move2.getData();
      assertEquals(BoardNodeKind.MOVE, move2Data.getNodeKind());
      assertTrue(move2Data.isHistoryActionNode());
      assertEquals(Stone.BLACK, move2Data.lastMoveColor);
      assertEquals(2, move2Data.moveNumber);
      assertFalse(move2Data.blackToPlay);

      Lizzie.board.setHistory(history);
      String written = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(written, true);
      assertNotNull(roundTrip);

      BoardHistoryNode rtRoot = roundTrip.getStart();
      BoardHistoryNode rtSetup = rtRoot.next().orElseThrow();
      BoardHistoryNode rtMove1 = rtSetup.next().orElseThrow();
      BoardHistoryNode rtMove2 = rtMove1.next().orElseThrow();

      assertEquals(BoardNodeKind.SNAPSHOT, rtRoot.getData().getNodeKind());
      assertEquals(0, rtRoot.getData().moveNumber);
      assertTrue(rtRoot.getData().blackToPlay);

      assertEquals(BoardNodeKind.SNAPSHOT, rtSetup.getData().getNodeKind());
      assertEquals(0, rtSetup.getData().moveNumber);
      assertFalse(rtSetup.getData().blackToPlay);
      assertArrayEquals(setupData.stones, rtSetup.getData().stones);

      assertEquals(BoardNodeKind.MOVE, rtMove1.getData().getNodeKind());
      assertEquals(Stone.WHITE, rtMove1.getData().lastMoveColor);
      assertEquals(1, rtMove1.getData().moveNumber);

      assertEquals(BoardNodeKind.MOVE, rtMove2.getData().getNodeKind());
      assertEquals(Stone.BLACK, rtMove2.getData().lastMoveColor);
      assertEquals(2, rtMove2.getData().moveNumber);

      assertTreeSemanticsEqual(history, roundTrip);
    }
  }

  @Test
  void foxStandaloneHandicapExplicitWhiteToPlayPreserved() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(19)) {
      String source = "(;SZ[19]HA[7]KM[0.5];AB[pd][dp][dd][pp][dj][pj][jj]PL[W];W[fq];B[qq])";
      BoardHistoryList history = SGFParser.parseSgf(source, true);
      assertNotNull(history);

      BoardHistoryNode root = history.getStart();
      assertTrue(root.getData().blackToPlay);

      BoardHistoryNode setupNode = root.next().orElseThrow();
      assertEquals(0, setupNode.getData().moveNumber);
      assertFalse(setupNode.getData().blackToPlay, "explicit PL[W] must be White to play");
      assertTrue(
          InitialHandicapSetup.isInitialHandicap(setupNode, 7, 19, 19),
          "explicit PL[W] initial handicap must be recognized");

      BoardHistoryNode move1 = setupNode.next().orElseThrow();
      assertEquals(Stone.WHITE, move1.getData().lastMoveColor);
      assertEquals(1, move1.getData().moveNumber);

      BoardHistoryNode move2 = move1.next().orElseThrow();
      assertEquals(Stone.BLACK, move2.getData().lastMoveColor);
      assertEquals(2, move2.getData().moveNumber);

      Lizzie.board.setHistory(history);
      String written = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(written, true);
      assertNotNull(roundTrip);
      assertTreeSemanticsEqual(history, roundTrip);
    }
  }

  @Test
  void foxStandaloneHandicapExplicitBlackToPlayPreservedAndNotRecognized() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(19)) {
      String source = "(;SZ[19]HA[7]KM[0.5];AB[pd][dp][dd][pp][dj][pj][jj]PL[B];B[fq];W[qq])";
      BoardHistoryList history = SGFParser.parseSgf(source, true);
      assertNotNull(history);

      BoardHistoryNode root = history.getStart();
      assertTrue(root.getData().blackToPlay);

      BoardHistoryNode setupNode = root.next().orElseThrow();
      assertEquals(0, setupNode.getData().moveNumber);
      assertTrue(
          setupNode.getData().blackToPlay,
          "explicit PL[B] must remain Black to play and must not be overwritten to White");
      assertFalse(
          InitialHandicapSetup.isInitialHandicap(setupNode, 7, 19, 19),
          "explicit PL[B] must not be recognized as initial handicap setup");

      BoardHistoryNode move1 = setupNode.next().orElseThrow();
      assertEquals(Stone.BLACK, move1.getData().lastMoveColor);
      assertEquals(1, move1.getData().moveNumber);

      BoardHistoryNode move2 = move1.next().orElseThrow();
      assertEquals(Stone.WHITE, move2.getData().lastMoveColor);
      assertEquals(2, move2.getData().moveNumber);

      Lizzie.board.setHistory(history);
      String written = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(written, true);
      assertNotNull(roundTrip);
      assertTreeSemanticsEqual(history, roundTrip);
    }
  }

  @Test
  void legacyRootHandicapSetupPreservesWhiteToPlayAndRoundTrip() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(19)) {
      String withoutPl = "(;SZ[19]HA[2]AB[dp][pd];W[pp];B[dd])";
      BoardHistoryList history1 = SGFParser.parseSgf(withoutPl, true);
      assertNotNull(history1);

      BoardHistoryNode root1 = history1.getStart();
      assertEquals(0, root1.getData().moveNumber);
      assertFalse(root1.getData().blackToPlay, "root handicap setup without PL defaults to White to play");
      assertTrue(InitialHandicapSetup.isInitialHandicap(root1, 2, 19, 19));

      BoardHistoryNode move1 = root1.next().orElseThrow();
      assertEquals(Stone.WHITE, move1.getData().lastMoveColor);
      assertEquals(1, move1.getData().moveNumber);

      BoardHistoryNode move2 = move1.next().orElseThrow();
      assertEquals(Stone.BLACK, move2.getData().lastMoveColor);
      assertEquals(2, move2.getData().moveNumber);

      Lizzie.board.setHistory(history1);
      String written1 = SGFParser.saveToString(false);
      BoardHistoryList roundTrip1 = SGFParser.parseSgf(written1, true);
      assertNotNull(roundTrip1);
      assertTreeSemanticsEqual(history1, roundTrip1);

      String withPlW = "(;SZ[19]HA[2]AB[dp][pd]PL[W];W[pp];B[dd])";
      BoardHistoryList history2 = SGFParser.parseSgf(withPlW, true);
      assertNotNull(history2);
      BoardHistoryNode root2 = history2.getStart();
      assertFalse(root2.getData().blackToPlay);
      assertTrue(InitialHandicapSetup.isInitialHandicap(root2, 2, 19, 19));
    }
  }

  @Test
  void invalidOrConflictingSetupsAreNotRecognizedAsInitialHandicap() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open(19)) {
      // 1. Count conflict: HA=5 but only 2 stones placed
      String countConflict = "(;SZ[19]HA[5]KM[0.5];AB[pd][dp];W[fq])";
      BoardHistoryList historyConflict = SGFParser.parseSgf(countConflict, true);
      assertNotNull(historyConflict);
      BoardHistoryNode conflictNode = historyConflict.getStart().next().orElseThrow();
      assertFalse(
          InitialHandicapSetup.isInitialHandicap(conflictNode, 5, 19, 19),
          "count conflict (2 stones vs HA=5) must not be recognized as initial handicap");
      assertTrue(
          conflictNode.getData().blackToPlay,
          "unrecognized setup node must not be normalized to White");

      // 2. Setup with AW (preset white stone)
      String withAw = "(;SZ[19]HA[2]KM[0.5];AB[pd][dp]AW[dd];W[fq])";
      BoardHistoryList historyAw = SGFParser.parseSgf(withAw, true);
      assertNotNull(historyAw);
      BoardHistoryNode awNode = historyAw.getStart().next().orElseThrow();
      assertFalse(
          InitialHandicapSetup.isInitialHandicap(awNode, 2, 19, 19),
          "setup with AW must not be recognized as initial handicap");
      assertTrue(
          awNode.getData().blackToPlay,
          "unrecognized setup with AW must not be normalized to White");

      // 3. Setup with AE
      String withAe = "(;SZ[19]HA[2]KM[0.5];AB[pd][dp]AE[dd];W[fq])";
      BoardHistoryList historyAe = SGFParser.parseSgf(withAe, true);
      assertNotNull(historyAe);
      BoardHistoryNode aeNode = historyAe.getStart().next().orElseThrow();
      assertFalse(
          InitialHandicapSetup.isInitialHandicap(aeNode, 2, 19, 19),
          "setup with AE must not be recognized as initial handicap");

      // 4. Midgame setup: setup node appears after real moves
      String midgame = "(;SZ[19]HA[2]KM[0.5];B[dp];W[pd];AB[dd][pp];W[fq])";
      BoardHistoryList historyMidgame = SGFParser.parseSgf(midgame, true);
      assertNotNull(historyMidgame);
      BoardHistoryNode b1 = historyMidgame.getStart().next().orElseThrow();
      BoardHistoryNode w1 = b1.next().orElseThrow();
      BoardHistoryNode midSetup = w1.next().orElseThrow();
      assertFalse(
          InitialHandicapSetup.isInitialHandicap(midSetup, 2, 19, 19),
          "midgame setup with genuine move ancestors must not be recognized as initial handicap");

      // 5. Empty root with HA
      String emptyRootSgf = "(;SZ[19]HA[7]KM[0.5];W[fq])";
      BoardHistoryList historyEmptyRoot = SGFParser.parseSgf(emptyRootSgf, true);
      assertNotNull(historyEmptyRoot);
      BoardHistoryNode emptyRoot = historyEmptyRoot.getStart();
      assertFalse(
          InitialHandicapSetup.isInitialHandicap(emptyRoot, 7, 19, 19),
          "empty root must not be recognized as initial handicap");
      assertTrue(emptyRoot.getData().blackToPlay, "empty root must remain Black to play");
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
