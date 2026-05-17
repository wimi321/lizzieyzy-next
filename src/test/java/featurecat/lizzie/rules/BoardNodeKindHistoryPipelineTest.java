package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.WinrateGraph;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BoardNodeKindHistoryPipelineTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void addOrGotoKeepsPassAndSnapshotAsSeparateChildren() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryNode root = new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));

      BoardHistoryNode snapshot =
          root.addOrGoto(snapshotNode(Optional.empty(), Stone.BLACK, true, 1), false);
      BoardHistoryNode pass = root.addOrGoto(passNode(Stone.BLACK, false, 1), false);

      assertEquals(2, root.numberOfChildren(), "pass child should not merge into snapshot child.");
      assertTrue(snapshot.getData().isSnapshotNode(), "first child should remain a snapshot.");
      assertTrue(pass.getData().isPassNode(), "second child should be an explicit pass.");
      assertNotSame(snapshot, pass, "snapshot and pass should stay as different nodes.");
    } finally {
      env.close();
    }
  }

  @Test
  void historyPassDoesNotRedoSnapshotChild() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history
          .getCurrentHistoryNode()
          .addOrGoto(snapshotNode(Optional.empty(), Stone.BLACK, true, 1), false);

      history.pass(Stone.BLACK, false, false, false);

      assertTrue(
          history.getData().isPassNode(), "history.pass should land on an explicit pass node.");
      assertEquals(
          2,
          history.getStart().numberOfChildren(),
          "history.pass should add a new pass child beside the snapshot.");
    } finally {
      env.close();
    }
  }

  @Test
  void moveUpTargetsExactSiblingWhenPassAndSnapshotShareBoardState() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryNode root = new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode move = root.addOrGoto(moveNode(0, 0, Stone.BLACK, false, 1), false);
      BoardHistoryNode pass = root.addOrGoto(passNode(Stone.BLACK, false, 1), false);
      BoardHistoryNode snapshot =
          root.addOrGoto(snapshotNode(Optional.empty(), Stone.EMPTY, true, 1), false);

      snapshot.moveUp();

      assertEquals(
          3, root.numberOfChildren(), "branch count should remain stable during re-order.");
      assertSame(
          move, root.getVariation(0).orElseThrow(), "existing earlier sibling should stay put.");
      assertSame(
          snapshot,
          root.getVariation(1).orElseThrow(),
          "moveUp should swap the actual snapshot child upward.");
      assertTrue(
          root.getVariation(1).orElseThrow().getData().isSnapshotNode(),
          "the reordered node should still be the snapshot child.");
      assertSame(
          pass,
          root.getVariation(2).orElseThrow(),
          "moveUp should keep the pass sibling instead of overwriting it.");
      assertTrue(
          root.getVariation(2).orElseThrow().getData().isPassNode(),
          "moveUp should leave the pass sibling intact.");
    } finally {
      env.close();
    }
  }

  @Test
  void moveDownTargetsExactSiblingWhenPassAndSnapshotShareBoardState() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryNode root = new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode pass = root.addOrGoto(passNode(Stone.BLACK, false, 1), false);
      BoardHistoryNode snapshot =
          root.addOrGoto(snapshotNode(Optional.empty(), Stone.EMPTY, true, 1), false);
      BoardHistoryNode move = root.addOrGoto(moveNode(0, 0, Stone.BLACK, false, 1), false);

      snapshot.moveDown();

      assertEquals(
          3, root.numberOfChildren(), "branch count should remain stable during re-order.");
      assertSame(
          pass,
          root.getVariation(0).orElseThrow(),
          "earlier pass sibling should remain in place during moveDown.");
      assertSame(
          move, root.getVariation(1).orElseThrow(), "moveDown should pull the next sibling up.");
      assertTrue(
          root.getVariation(1).orElseThrow().getData().isMoveNode(),
          "moveDown should keep the real move node in the middle slot.");
      assertSame(
          snapshot,
          root.getVariation(2).orElseThrow(),
          "moveDown should move the actual snapshot child downward.");
      assertTrue(
          root.getVariation(2).orElseThrow().getData().isSnapshotNode(),
          "moveDown should keep the snapshot node identity when reordering.");
    } finally {
      env.close();
    }
  }

  @Test
  void sgfGenerateNodeSerializesOnlyHistoryActions() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    boolean previousSavingRaw = LizzieFrame.isSavingRaw;
    boolean previousSavingRawComment = LizzieFrame.isSavingRawComment;
    try {
      LizzieFrame.isSavingRaw = true;
      LizzieFrame.isSavingRawComment = false;

      assertEquals(
          "",
          generateNode(snapshotNode(Optional.of(new int[] {0, 0}), Stone.BLACK, true, 1)),
          "snapshot nodes should not export as SGF moves.");
      assertEquals(
          ";W[]",
          generateNode(passNode(Stone.WHITE, true, 2)),
          "pass nodes should still export as SGF passes.");
      assertEquals(
          ";B[ba]",
          generateNode(moveNode(1, 0, Stone.BLACK, false, 3)),
          "move nodes should still export their coordinates.");
    } finally {
      LizzieFrame.isSavingRaw = previousSavingRaw;
      LizzieFrame.isSavingRawComment = previousSavingRawComment;
      env.close();
    }
  }

  @Test
  void analysisScanHelpersSkipSnapshotsAndKeepPasses() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryNode root = new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode snapshot =
          root.add(
              new BoardHistoryNode(
                  snapshotNode(Optional.of(new int[] {2, 2}), Stone.BLACK, true, 1)));
      BoardHistoryNode pass = snapshot.add(new BoardHistoryNode(passNode(Stone.BLACK, false, 2)));
      BoardHistoryNode move = pass.add(new BoardHistoryNode(moveNode(0, 0, Stone.WHITE, true, 3)));

      BoardHistoryNode anchor =
          (BoardHistoryNode)
              requireMethod(AnalysisEngine.class, "findSnapshotAnchor", BoardHistoryNode.class)
                  .invoke(null, move);
      @SuppressWarnings("unchecked")
      List<String[]> moves =
          (List<String[]>)
              requireMethod(AnalysisEngine.class, "collectHistoryActions", BoardHistoryNode.class)
                  .invoke(null, move);

      assertTrue(anchor.getData().isSnapshotNode(), "analysis scan should anchor on the snapshot.");
      assertEquals(2, moves.size(), "analysis scan should skip snapshots and keep both actions.");
      assertArrayEquals(
          new String[] {"B", "pass"}, moves.get(0), "first action should be the real pass.");
      assertArrayEquals(
          new String[] {"W", "A3"}, moves.get(1), "second action should be the real move.");
    } finally {
      env.close();
    }
  }

  @Test
  void foxMainBranchExtractionSkipsSnapshotsAndKeepsPasses() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(snapshotNode(Optional.of(new int[] {2, 2}), Stone.BLACK, true, 1));
      history.add(passNode(Stone.BLACK, false, 2));
      history.add(moveNode(0, 0, Stone.WHITE, true, 3));

      Object normalizer = newSgfMainLineNormalizer("(;SZ[3])");
      @SuppressWarnings("unchecked")
      List<Object> moves =
          (List<Object>)
              requireMethod(normalizer.getClass(), "extractMainBranch", BoardHistoryList.class)
                  .invoke(normalizer, history);

      assertEquals(2, moves.size(), "Fox main-line extraction should skip snapshots only.");
      assertSgfMove(moves.get(0), "B", "");
      assertSgfMove(moves.get(1), "W", "aa");
    } finally {
      env.close();
    }
  }

  @Test
  void sgfMidgameSetupCreatesSnapshotAnchorWhileRealPassStaysPass() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3];B[aa]AB[bb];W[])", false);

      BoardHistoryNode first = history.getStart().next().orElseThrow();
      BoardHistoryNode second = first.next().orElseThrow();
      BoardHistoryNode third = second.next().orElseThrow();

      assertTrue(first.getData().isMoveNode(), "real B[aa] should stay a MOVE node.");
      assertTrue(
          second.getData().isSnapshotNode(), "midgame setup stones should land on SNAPSHOT.");
      assertEquals(
          first.getData().moveNumber,
          second.getData().moveNumber,
          "setup snapshots should not consume a move number.");
      assertTrue(
          !second.getData().lastMove.isPresent(),
          "setup snapshots should stay static and avoid move coordinates.");
      assertEquals(
          Stone.BLACK,
          second.getData().stones[Board.getIndex(1, 1)],
          "setup snapshot should carry the added black stone.");
      assertTrue(third.getData().isPassNode(), "explicit W[] should stay a real PASS node.");
      assertEquals(2, third.getData().moveNumber, "explicit pass should advance the move count.");
    } finally {
      env.close();
    }
  }

  @Test
  void sgfRootSetupStaysOnSnapshotRootAndDoesNotEnterRealHistory() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3]HA[2]AB[aa]AB[ca];W[ba])", false);
      Lizzie.board.setHistory(history);

      BoardHistoryNode root = history.getStart();
      BoardHistoryNode firstMove = root.next().orElseThrow();

      assertTrue(root.getData().isSnapshotNode(), "root setup should stay on the snapshot root.");
      assertFalse(Lizzie.board.hasStartStone, "root setup should not use hasStartStone anymore.");
      assertEquals(
          2, history.getGameInfo().getHandicap(), "handicap metadata should stay on the root.");
      assertEquals(0, root.getData().moveNumber, "root setup should not consume move numbers.");
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(2, 0)]);
      assertTrue(firstMove.getData().isMoveNode(), "the first real action should stay a move.");
      assertEquals(1, firstMove.getData().moveNumber, "the first real action should stay move 1.");
      history.next();
      assertEquals(
          1,
          Lizzie.board.getmovelistForSaveLoad().size(),
          "root setup should stay out of save/load movelist history.");
    } finally {
      env.close();
    }
  }

  @Test
  void normalHandicapRootSetupDefaultsToWhiteToPlayAndKeepsFixedStones() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[3]HA[2]AB[aa]AB[ca];W[ba];B[bb])";
      BoardHistoryList history = SGFParser.parseSgf(sgf, false);

      BoardHistoryNode root = history.getStart();
      BoardHistoryNode whiteMove = root.next().orElseThrow();
      BoardHistoryNode blackMove = whiteMove.next().orElseThrow();

      assertFalse(
          root.getData().blackToPlay,
          "normal handicap SGF with HA and root AB stones should start with White to play.");
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(2, 0)]);
      assertEquals(
          Stone.BLACK,
          whiteMove.getData().stones[Board.getIndex(0, 0)],
          "fixed handicap stones should still be present after White's first move.");
      assertEquals(
          Stone.BLACK,
          whiteMove.getData().stones[Board.getIndex(2, 0)],
          "fixed handicap stones should not reappear later as phantom moves.");
      assertEquals(Stone.WHITE, whiteMove.getData().stones[Board.getIndex(1, 0)]);
      assertEquals(Stone.BLACK, blackMove.getData().stones[Board.getIndex(1, 1)]);
    } finally {
      env.close();
    }
  }

  @Test
  void liveLoadNormalHandicapRootSetupKeepsWhiteToPlayAndFixedStones() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[3]HA[2]AB[aa]AB[ca];W[ba];B[bb])";

      assertTrue(SGFParser.loadFromString(sgf), "loadFromString should parse normal handicap SGF.");

      BoardHistoryList history = Lizzie.board.getHistory();
      BoardHistoryNode root = history.getStart();
      BoardHistoryNode whiteMove = root.next().orElseThrow();
      assertFalse(
          root.getData().blackToPlay,
          "live SGF loading should leave normal handicap games ready for White.");
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.BLACK, root.getData().stones[Board.getIndex(2, 0)]);
      assertEquals(Stone.BLACK, whiteMove.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.BLACK, whiteMove.getData().stones[Board.getIndex(2, 0)]);
      assertEquals(Stone.WHITE, whiteMove.getData().stones[Board.getIndex(1, 0)]);
    } finally {
      env.close();
    }
  }

  @Test
  void preFirstMoveStandaloneSetupNodeStaysIndependentSnapshot() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3];AB[aa]C[setup0];B[bb])", false);

      BoardHistoryNode root = history.getStart();
      BoardHistoryNode setupNode = root.next().orElseThrow();
      BoardHistoryNode firstMove = setupNode.next().orElseThrow();

      assertTrue(
          setupNode.getData().isSnapshotNode(), "pre-first-move setup node should stay SNAPSHOT.");
      assertEquals(
          0,
          setupNode.getData().moveNumber,
          "pre-first-move setup node should keep moveNumber at 0.");
      assertEquals("setup0", setupNode.getData().comment, "setup comment should stay on snapshot.");
      assertTrue(root.getData().comment.isEmpty(), "setup comment should not drift to root.");
      assertEquals(Stone.BLACK, setupNode.getData().stones[Board.getIndex(0, 0)]);
      assertTrue(firstMove.getData().isMoveNode(), "first real move should stay MOVE.");
      assertEquals(1, firstMove.getData().moveNumber, "first real move should keep move number 1.");
    } finally {
      env.close();
    }
  }

  @Test
  void standaloneSetupNodeKeepsParentMetadataAndOwnSnapshotMetadata() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history =
          SGFParser.parseSgf(
              "(;SZ[3];B[aa]C[parent]LB[aa:P]MN[9];AB[bb]C[setup]LB[bb:S]MN[12];W[cc])", false);

      BoardHistoryNode parentMove = history.getStart().next().orElseThrow();
      BoardHistoryNode setupNode = parentMove.next().orElseThrow();

      int parentMoveIndex = Board.getIndex(0, 0);

      assertEquals("parent", parentMove.getData().comment, "parent comment should stay on parent.");
      assertEquals("aa:P", parentMove.getData().getProperty("LB"), "parent LB should stay put.");
      assertEquals("9", parentMove.getData().getProperty("MN"), "parent MN should stay on parent.");
      assertEquals(
          9,
          parentMove.getData().moveMNNumber,
          "parent moveMNNumber should not be rolled back by child setup snapshot.");
      assertEquals(
          9,
          parentMove.getData().moveNumberList[parentMoveIndex],
          "parent moveNumberList should keep the parent MN number.");

      assertTrue(setupNode.getData().isSnapshotNode(), "setup node should stay SNAPSHOT.");
      assertEquals("setup", setupNode.getData().comment, "setup snapshot should keep own comment.");
      assertEquals(
          "bb:S",
          setupNode.getData().getProperty("LB"),
          "setup snapshot should keep only current-node markup.");
      assertEquals(
          "12",
          setupNode.getData().getProperty("MN"),
          "setup snapshot should keep current-node MN.");
    } finally {
      env.close();
    }
  }

  @Test
  void standaloneSetupNodeLeadingMetadataBeforeFirstSetupPropertyStaysOnSnapshot()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history =
          SGFParser.parseSgf(
              "(;SZ[3];B[aa]C[parent]LB[aa:P]MN[9];C[setup-head]LB[bb:S]MN[12]AB[bb];W[cc])",
              false);

      BoardHistoryNode parentMove = history.getStart().next().orElseThrow();
      BoardHistoryNode setupNode = parentMove.next().orElseThrow();
      int parentMoveIndex = Board.getIndex(0, 0);

      assertEquals("parent", parentMove.getData().comment, "parent comment should stay on parent.");
      assertEquals("aa:P", parentMove.getData().getProperty("LB"), "parent LB should stay put.");
      assertEquals("9", parentMove.getData().getProperty("MN"), "parent MN should stay on parent.");
      assertEquals(
          9,
          parentMove.getData().moveMNNumber,
          "parent moveMNNumber should stay on parent after child setup split.");
      assertEquals(
          9,
          parentMove.getData().moveNumberList[parentMoveIndex],
          "parent moveNumberList should stay on parent after child setup split.");

      assertTrue(setupNode.getData().isSnapshotNode(), "setup node should stay SNAPSHOT.");
      assertEquals(
          "setup-head",
          setupNode.getData().comment,
          "setup snapshot should keep pre-setup comment from the same SGF node.");
      assertEquals(
          "bb:S",
          setupNode.getData().getProperty("LB"),
          "setup snapshot should keep pre-setup markup from the same SGF node.");
      assertEquals(
          "12",
          setupNode.getData().getProperty("MN"),
          "setup snapshot should keep pre-setup MN from the same SGF node.");
    } finally {
      env.close();
    }
  }

  @Test
  void loadFromStringEntriesMatchParseSgfStandaloneSetupSnapshotBoundary() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[3];C[setup-head]LB[aa:S]MN[4]AB[aa];B[bb])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertStandalonePreMoveSetupNode(parsed, "parseSgf baseline");

      assertTrue(SGFParser.loadFromString(sgf), "loadFromString should parse SGF successfully.");
      assertStandalonePreMoveSetupNode(Lizzie.board.getHistory(), "loadFromString");
      assertSetupBoundaryMatchesParsed(parsed, Lizzie.board.getHistory(), "loadFromString");

      assertTrue(
          SGFParser.loadFromStringforedit(sgf),
          "loadFromStringforedit should parse SGF successfully.");
      assertStandalonePreMoveSetupNode(Lizzie.board.getHistory(), "loadFromStringforedit");
      assertSetupBoundaryMatchesParsed(parsed, Lizzie.board.getHistory(), "loadFromStringforedit");
    } finally {
      env.close();
    }
  }

  @Test
  void rectangularBoardSizeParsingMatchesAcrossStringEntrypointsAndRoundTrips() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[5:7]AB[aa]AW[eg]PL[W];B[bc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertTrue(parsed != null, "parseSgf should parse rectangular SZ values.");
      assertEquals(
          35,
          parsed.getStart().getData().stones.length,
          "parseSgf should allocate board data using SZ width and height.");

      Board.boardWidth = 5;
      Board.boardHeight = 7;
      Zobrist.init();
      Lizzie.board.setHistory(new BoardHistoryList(BoardData.empty(5, 7)));

      assertTrue(SGFParser.loadFromString(sgf), "loadFromString should parse rectangular SZ.");
      assertEquals(5, Board.boardWidth, "loadFromString should set board width from SZ.");
      assertEquals(7, Board.boardHeight, "loadFromString should set board height from SZ.");
      assertEquals(
          35,
          Lizzie.board.getHistory().getStart().getData().stones.length,
          "loadFromString should keep rectangular board data.");

      assertTrue(
          SGFParser.loadFromStringforedit(sgf),
          "loadFromStringforedit should parse rectangular SZ.");
      assertEquals(5, Board.boardWidth, "loadFromStringforedit should set board width from SZ.");
      assertEquals(7, Board.boardHeight, "loadFromStringforedit should set board height from SZ.");
      assertEquals(
          35,
          Lizzie.board.getHistory().getStart().getData().stones.length,
          "loadFromStringforedit should keep rectangular board data.");

      String exported = SGFParser.saveToString(false);
      assertTrue(exported.contains("SZ[5:7]"), "save output should preserve rectangular SZ.");

      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      assertTrue(roundTrip != null, "parseSgf should parse saved rectangular SZ.");
      assertEquals(
          35,
          roundTrip.getStart().getData().stones.length,
          "parseSgf round-trip should keep rectangular dimensions.");
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfSetupUsesSgfRectSizeWhenGlobalBoardSizeDiffers() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[5:7]AB[ed]AB[ee]AE[ee])";

      assertEquals(3, Board.boardWidth, "fixture should start from 3x3 global width.");
      assertEquals(3, Board.boardHeight, "fixture should start from 3x3 global height.");

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedRoot = parsed.getStart().getData();

      assertEquals(35, parsedRoot.stones.length, "parseSgf should allocate the 5x7 root board.");
      assertEquals(
          Stone.BLACK,
          parsedRoot.stones[boardIndex(4, 3, 7)],
          "AB[ed] should land on the SGF 5x7 coordinate.");
      assertEquals(
          Stone.EMPTY,
          parsedRoot.stones[boardIndex(4, 4, 7)],
          "AE[ee] should remove the stone on the SGF 5x7 coordinate.");
      assertEquals(3, Board.boardWidth, "parseSgf should keep global board width unchanged.");
      assertEquals(3, Board.boardHeight, "parseSgf should keep global board height unchanged.");

      Board.boardWidth = 5;
      Board.boardHeight = 7;
      Zobrist.init();
      Lizzie.board.setHistory(new BoardHistoryList(BoardData.empty(5, 7)));

      assertTrue(SGFParser.loadFromString(sgf), "loadFromString should parse this fixture.");
      assertArrayEquals(
          parsedRoot.stones,
          Lizzie.board.getHistory().getStart().getData().stones,
          "parseSgf setup result should match loadFromString.");

      assertTrue(
          SGFParser.loadFromStringforedit(sgf), "loadFromStringforedit should parse this fixture.");
      assertArrayEquals(
          parsedRoot.stones,
          Lizzie.board.getHistory().getStart().getData().stones,
          "parseSgf setup result should match loadFromStringforedit.");
    } finally {
      env.close();
    }
  }

  @Test
  void detachedParseSgfAeDoesNotRefreshLiveWindowOnDifferentBoardSize() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingFrame frame = (TrackingFrame) Lizzie.frame;
      int initialRefreshCallCount = frame.refreshCallCount();

      assertEquals(3, Board.boardWidth, "fixture should start from 3x3 global width.");
      assertEquals(3, Board.boardHeight, "fixture should start from 3x3 global height.");

      BoardHistoryList parsed = SGFParser.parseSgf("(;SZ[5:7]AB[ee]AE[ee])", false);
      BoardData parsedRoot = parsed.getStart().getData();

      assertEquals(
          initialRefreshCallCount,
          frame.refreshCallCount(),
          "detached parse should not refresh the live window during AE handling.");
      assertEquals(35, parsedRoot.stones.length, "detached parse should keep SGF board size.");
      assertEquals(
          Stone.EMPTY,
          parsedRoot.stones[boardIndex(4, 4, 7)],
          "AE[ee] should still remove stones on detached history.");
      assertEquals(3, Board.boardWidth, "detached parse should keep global board width unchanged.");
      assertEquals(
          3, Board.boardHeight, "detached parse should keep global board height unchanged.");
    } finally {
      env.close();
    }
  }

  @Test
  void setHistoryAndGenerateNodeKeepParseSgfRectangularBoardSize() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[5:7];B[aa]AB[ed];W[bc])";

      assertEquals(3, Board.boardWidth, "fixture should start from 3x3 global width.");
      assertEquals(3, Board.boardHeight, "fixture should start from 3x3 global height.");

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode setupNode = parsed.getStart().next().orElseThrow().next().orElseThrow();
      String setupSgf = generateNode(setupNode);
      assertTrue(
          setupSgf.contains("AB[ed]"),
          "generateNode should use parsed 5x7 history coordinates for setup export.");

      Lizzie.board.setHistory(parsed);
      assertEquals(5, Board.boardWidth, "setHistory should keep parsed history width.");
      assertEquals(7, Board.boardHeight, "setHistory should keep parsed history height.");

      String exported = SGFParser.saveToString(false);
      assertTrue(exported.contains("SZ[5:7]"), "save output should keep parsed rectangular SZ.");
      assertTrue(exported.contains("AB[ed]"), "save output should keep parsed setup coordinates.");
      assertTrue(exported.contains("W[bc]"), "save output should keep parsed move coordinates.");

      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardHistoryNode roundTripSetup =
          roundTrip.getStart().next().orElseThrow().next().orElseThrow();
      assertArrayEquals(
          setupNode.getData().stones,
          roundTripSetup.getData().stones,
          "setHistory+save round-trip should keep parsed rectangular setup stones.");
    } finally {
      env.close();
    }
  }

  @Test
  void parseBranchFirstNodeSetupThenMoveCreatesSnapshotBoundary() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));

      SGFParser.parseBranch(history, ";AB[aa]B[bb]");

      BoardHistoryNode root = history.getStart();
      BoardHistoryNode setupNode = root.getVariation(0).orElseThrow();
      BoardHistoryNode moveNode = setupNode.next().orElseThrow();

      assertTrue(setupNode.getData().isSnapshotNode(), "setup part should land on SNAPSHOT.");
      assertEquals(
          0,
          setupNode.getData().moveNumber,
          "setup snapshot should keep branch boundary move number.");
      assertEquals(Stone.BLACK, setupNode.getData().stones[Board.getIndex(0, 0)]);
      assertTrue(moveNode.getData().isMoveNode(), "real move after setup should stay MOVE.");
      assertEquals(
          1, moveNode.getData().moveNumber, "real move should still consume one move number.");
      assertEquals(Stone.BLACK, moveNode.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.BLACK, moveNode.getData().stones[Board.getIndex(1, 1)]);
    } finally {
      env.close();
    }
  }

  @Test
  void parseBranchSetupThenMoveKeepsParentMetadataAndSnapshotBoundaryMetadata() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3];B[aa]C[parent]LB[aa:P]MN[9])", false);
      BoardHistoryNode root = history.getStart();
      BoardHistoryNode parentMove = root.next().orElseThrow();
      history.next();

      SGFParser.parseBranch(history, ";AB[bb]C[setup-b]LB[bb:S]MN[12]W[cc]");

      BoardHistoryNode setupNode = parentMove.getVariation(0).orElseThrow();
      BoardHistoryNode moveNode = setupNode.next().orElseThrow();
      int parentMoveIndex = Board.getIndex(0, 0);

      assertEquals("parent", parentMove.getData().comment, "parent comment should stay on parent.");
      assertEquals("aa:P", parentMove.getData().getProperty("LB"), "parent LB should stay put.");
      assertEquals("9", parentMove.getData().getProperty("MN"), "parent MN should stay on parent.");
      assertEquals(
          9,
          parentMove.getData().moveMNNumber,
          "branch setup snapshot should not roll back parent moveMNNumber.");
      assertEquals(
          9,
          parentMove.getData().moveNumberList[parentMoveIndex],
          "branch setup snapshot should not roll back parent moveNumberList.");

      assertTrue(setupNode.getData().isSnapshotNode(), "setup part should land on SNAPSHOT.");
      assertEquals(
          "setup-b", setupNode.getData().comment, "setup snapshot should keep own comment.");
      assertEquals(
          "bb:S",
          setupNode.getData().getProperty("LB"),
          "setup snapshot should keep only current-node markup.");
      assertEquals(
          "12",
          setupNode.getData().getProperty("MN"),
          "setup snapshot should keep current-node MN.");
      assertTrue(moveNode.getData().isMoveNode(), "move part should stay a real MOVE.");
    } finally {
      env.close();
    }
  }

  @Test
  void rootSetupDefaultsToBlackToPlayRegardlessOfAbAwOrder() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList abThenAw = SGFParser.parseSgf("(;SZ[3]AB[aa]AW[bb])", false);
      BoardHistoryList awThenAb = SGFParser.parseSgf("(;SZ[3]AW[bb]AB[aa])", false);

      assertTrue(
          abThenAw.getStart().getData().blackToPlay,
          "root setup without PL should keep the fixed default side to play.");
      assertEquals(
          abThenAw.getStart().getData().blackToPlay,
          awThenAb.getStart().getData().blackToPlay,
          "AB/AW property order should not affect root side to play.");
      assertArrayEquals(
          abThenAw.getStart().getData().stones,
          awThenAb.getStart().getData().stones,
          "AB/AW order variants should still build the same root board.");
    } finally {
      env.close();
    }
  }

  @Test
  void rootSetupHonorsExplicitPlRegardlessOfPropertyOrder() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList plLast = SGFParser.parseSgf("(;SZ[3]AB[aa]AW[bb]PL[W])", false);
      BoardHistoryList plFirst = SGFParser.parseSgf("(;SZ[3]PL[W]AW[bb]AB[aa])", false);

      assertFalse(
          plLast.getStart().getData().blackToPlay, "explicit PL should set the root side to play.");
      assertEquals(
          plLast.getStart().getData().blackToPlay,
          plFirst.getStart().getData().blackToPlay,
          "explicit PL should win over AB/AW property order.");
      assertArrayEquals(
          plLast.getStart().getData().stones,
          plFirst.getStart().getData().stones,
          "explicit PL variants should still build the same root board.");
    } finally {
      env.close();
    }
  }

  @Test
  void detachedParseSgfKeepsCurrentBoardStartStoneState() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Movelist setupMove = new Movelist();
      setupMove.x = 1;
      setupMove.y = 2;
      setupMove.isblack = true;
      setupMove.ispass = false;
      setupMove.movenum = 1;
      Lizzie.board.hasStartStone = true;
      Lizzie.board.startStonelist.add(setupMove);
      List<Movelist> originalStartStoneList = Lizzie.board.startStonelist;

      BoardHistoryList detached =
          SGFParser.parseSgf("(;SZ[3]AB[aa]AW[bb]AE[cc]PL[W];B[ba])", false);

      assertTrue(
          Lizzie.board.hasStartStone,
          "detached parse should keep the current board hasStartStone state.");
      assertSame(
          originalStartStoneList,
          Lizzie.board.startStonelist,
          "detached parse should keep the current board startStonelist reference.");
      assertEquals(
          1,
          Lizzie.board.startStonelist.size(),
          "detached parse should not clear current board startStonelist content.");
      assertSame(
          setupMove,
          Lizzie.board.startStonelist.get(0),
          "detached parse should not replace current board start stone entries.");
      assertEquals("W", detached.getStart().getData().getProperty("PL"));
      assertTrue(
          detached.getStart().getData().stones[Board.getIndex(0, 0)] == Stone.BLACK,
          "detached parse should still parse root AB setup stones.");
    } finally {
      env.close();
    }
  }

  @Test
  void detachedParseSgfDoesNotOverwriteCurrentWindowPlayerTitle() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingFrame frame = (TrackingFrame) Lizzie.frame;
      frame.seedPlayers("LiveWhite", "LiveBlack");
      int initialSetPlayersCallCount = frame.setPlayersCallCount();

      BoardHistoryList detached =
          SGFParser.parseSgf("(;SZ[3]PB[SgfBlack]PW[SgfWhite];B[aa])", false);

      assertEquals(
          initialSetPlayersCallCount,
          frame.setPlayersCallCount(),
          "detached parse should not touch current window player title.");
      assertEquals(
          "LiveWhite",
          frame.currentWhitePlayerTitle(),
          "detached parse should keep the current white player title.");
      assertEquals(
          "LiveBlack",
          frame.currentBlackPlayerTitle(),
          "detached parse should keep the current black player title.");
      assertEquals("SgfBlack", detached.getGameInfo().getPlayerBlack());
      assertEquals("SgfWhite", detached.getGameInfo().getPlayerWhite());
    } finally {
      env.close();
    }
  }

  @Test
  void detachedParseSgfFirstWithKomiKeepsLiveBoardAndEngineState() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config.readKomi = true;
      TrackingLeelaz leelaz = (TrackingLeelaz) Lizzie.leelaz;
      BoardHistoryList liveHistory = Lizzie.board.getHistory();
      liveHistory.getGameInfo().setKomiNoMenu(5.5);
      BoardData liveRoot = liveHistory.getStart().getData();
      liveRoot.setPlayouts(120);
      liveRoot.isChanged = false;
      int initialCommandCount = leelaz.recordedCommands().size();

      BoardHistoryList detached = SGFParser.parseSgf("(;SZ[3]KM[7.5];B[aa])", true);

      assertEquals(
          initialCommandCount,
          leelaz.recordedCommands().size(),
          "detached parse should not send live engine komi command.");
      assertEquals(
          5.5,
          liveHistory.getGameInfo().getKomi(),
          0.0001,
          "detached parse should keep current live board komi.");
      assertFalse(
          liveRoot.isChanged, "detached parse should keep live best-move state flags untouched.");
      assertEquals(7.5, detached.getGameInfo().getKomi(), 0.0001);
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfDetachedLzopRoundTripKeepsSingleEngineAnalysisPayload() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf =
          "(;SZ[3]LZOP[MainEngine 44.0 120\n"
              + "move D4 visits 120 winrate 5600 prior 5000 pv D4 C4])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedRoot = parsed.getStart().getData();

      assertEquals("MainEngine", parsedRoot.engineName);
      assertEquals(120, parsedRoot.getPlayouts());
      assertFalse(parsedRoot.bestMoves.isEmpty(), "detached parse should keep root best-moves.");
      assertEquals("D4", parsedRoot.bestMoves.get(0).coordinate);

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();

      assertEquals("MainEngine", roundTripRoot.engineName);
      assertEquals(120, roundTripRoot.getPlayouts());
      assertFalse(roundTripRoot.bestMoves.isEmpty(), "round-trip should keep root best-moves.");
      assertEquals("D4", roundTripRoot.bestMoves.get(0).coordinate);
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfDetachedRootLzopHeaderOnlyKataPayloadKeepsKataFlagsAcrossRoundTrip()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf =
          "(;SZ[3]LZOP[MainEngine 44.0 120 3.5 0.7\n"
              + "move D4 visits 120 winrate 5600 prior 5000 pv D4 C4])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedRoot = parsed.getStart().getData();

      assertTrue(parsedRoot.isKataData, "header scoreMean should keep Kata payload on root.");
      assertEquals(3.5, parsedRoot.scoreMean, 0.0001);
      assertEquals(0.7, parsedRoot.scoreStdev, 0.0001);
      assertFalse(
          Lizzie.board.isKataBoard,
          "detached parse should keep live board kata flag unchanged before adopt.");

      Lizzie.board.setHistory(parsed);
      assertTrue(
          Lizzie.board.isKataBoard,
          "adopting header-only root kata payload should re-derive board kata flag.");

      String exported = SGFParser.saveToString(false);
      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();
      assertTrue(roundTripRoot.isKataData, "round-trip should keep root kata payload flag.");
      assertEquals(3.5, roundTripRoot.scoreMean, 0.0001);
      assertEquals(0.7, roundTripRoot.scoreStdev, 0.0001);
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfDetachedRootSingleLineHeaderOnlyLzopRoundTripKeepsKataHeaderPayload()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[3]LZOP[Main 44.0 120 3.5 0.7])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedRoot = parsed.getStart().getData();

      assertEquals("Main", parsedRoot.engineName);
      assertEquals(120, parsedRoot.getPlayouts(), "single-line header should keep root playouts.");
      assertTrue(parsedRoot.isKataData, "single-line header should keep root kata flag.");
      assertEquals(3.5, parsedRoot.scoreMean, 0.0001);
      assertEquals(0.7, parsedRoot.scoreStdev, 0.0001);

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();

      assertTrue(exported.contains("LZOP["), "single-line root payload should still export.");
      assertEquals("Main", roundTripRoot.engineName);
      assertEquals(
          120, roundTripRoot.getPlayouts(), "round-trip should keep single-line root playouts.");
      assertTrue(roundTripRoot.isKataData, "round-trip should keep single-line root kata flag.");
      assertEquals(3.5, roundTripRoot.scoreMean, 0.0001);
      assertEquals(0.7, roundTripRoot.scoreStdev, 0.0001);
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfDetachedRootSingleLineHeaderOnlyLzopZeroPlayoutsRoundTripKeepsPrimaryPayload()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[3]LZOP[Main 44.0 0 3.5 0.7 0.9])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedRoot = parsed.getStart().getData();
      assertPrimaryHeaderOnlyPayload(parsedRoot, "parseSgf", "Main", 0, 3.5, 0.7, 0.9, true, true);

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();

      assertTrue(exported.contains("LZOP["), "zero-playouts root payload should still export.");
      assertPrimaryHeaderOnlyPayload(
          roundTripRoot, "round-trip", "Main", 0, 3.5, 0.7, 0.9, true, true);
    } finally {
      env.close();
    }
  }

  @Test
  void loadFromStringEntriesMatchParseSgfForRootSingleLineHeaderOnlyLzPayload() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[3]LZ[Main 44.0 120 3.5 0.7 0.9])";

      BoardData parsedRoot = SGFParser.parseSgf(sgf, false).getStart().getData();
      assertPrimaryHeaderOnlyPayload(
          parsedRoot, "parseSgf", "Main", 120, 3.5, 0.7, 0.9, true, true);

      assertTrue(SGFParser.loadFromString(sgf), "loadFromString should parse root LZ payload.");
      assertPrimaryHeaderOnlyPayload(
          Lizzie.board.getHistory().getStart().getData(),
          "loadFromString",
          "Main",
          120,
          3.5,
          0.7,
          0.9,
          true,
          true);

      assertTrue(
          SGFParser.loadFromStringforedit(sgf), "loadFromStringforedit should parse root LZ.");
      assertPrimaryHeaderOnlyPayload(
          Lizzie.board.getHistory().getStart().getData(),
          "loadFromStringforedit",
          "Main",
          120,
          3.5,
          0.7,
          0.9,
          true,
          true);
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfDetachedAnalysisInfoCannotOverridePrimaryHeaderPayloadFields() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf =
          "(;SZ[3]LZOP[Main 30.0 120 3.5 0.7 0.9\n"
              + "move D4 visits 50 winrate 5600 scoreMean 1.1 scoreStdev 0.2 prior 5000 pv D4 C4 ownership 0.1 -0.1])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedRoot = parsed.getStart().getData();
      assertPrimaryHeaderOnlyPayload(
          parsedRoot, "parseSgf", "Main", 120, 3.5, 0.7, 0.9, true, false);
      assertEquals(
          70.0, parsedRoot.winrate, 0.0001, "analysis line should not override header winrate.");
      assertFalse(parsedRoot.bestMoves.isEmpty(), "analysis line should still keep best-moves.");
      assertEquals("D4", parsedRoot.bestMoves.get(0).coordinate);
      assertTrue(parsedRoot.estimateArray != null, "analysis line should keep ownership payload.");

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();
      assertPrimaryHeaderOnlyPayload(
          roundTripRoot, "round-trip", "Main", 120, 3.5, 0.7, 0.9, true, false);
      assertEquals(
          70.0,
          roundTripRoot.winrate,
          0.0001,
          "round-trip should keep header winrate when analysis line differs.");
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfDetachedRootSingleLineHeaderOnlyLzop2RoundTripKeepsSecondaryPda2() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3]LZOP2[Sub 41.0 150 2.1 0.5 0.8])";

      BoardData parsedRoot = SGFParser.parseSgf(sgf, false).getStart().getData();
      assertSecondaryHeaderOnlyPayload(
          parsedRoot, "parseSgf", "Sub", 150, 2.1, 0.5, 0.8, true, true);

      Lizzie.board.setHistory(SGFParser.parseSgf(sgf, false));
      Lizzie.config.extraMode = ExtraMode.Normal;
      String exported = SGFParser.saveToString(false);
      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();

      assertTrue(
          exported.contains("LZOP2["),
          "single-line secondary root payload should export in single-engine UI mode.");
      assertSecondaryHeaderOnlyPayload(
          roundTripRoot, "round-trip", "Sub", 150, 2.1, 0.5, 0.8, true, true);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void parseSgfDetachedRootSingleLineHeaderOnlyLzop2ZeroPlayoutsRoundTripKeepsSecondaryPayload()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3]LZOP2[Sub 41.0 0 2.1 0.5 0.8])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedRoot = parsed.getStart().getData();
      assertSecondaryHeaderOnlyPayload(parsedRoot, "parseSgf", "Sub", 0, 2.1, 0.5, 0.8, true, true);

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();

      assertTrue(
          exported.contains("LZOP2["), "zero-playouts secondary root payload should export.");
      assertSecondaryHeaderOnlyPayload(
          roundTripRoot, "round-trip", "Sub", 0, 2.1, 0.5, 0.8, true, true);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void parseSgfDetachedRootLzop2HeaderOnlyKataPayloadKeepsScoreMean2WhenUiSingleMode()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf =
          "(;SZ[3]LZOP2[SubEngine 41.0 150 2.5 0.6\n"
              + "move C3 visits 150 winrate 5900 prior 5000 pv C3 B3];B[aa])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedRoot = parsed.getStart().getData();
      assertTrue(parsedRoot.isKataData2, "header scoreMean2 should keep secondary Kata payload.");
      assertEquals(2.5, parsedRoot.scoreMean2, 0.0001);
      assertEquals(0.6, parsedRoot.scoreStdev2, 0.0001);

      Lizzie.board.setHistory(parsed);
      Lizzie.config.extraMode = ExtraMode.Normal;
      String exported = SGFParser.saveToString(false);
      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();

      assertTrue(
          exported.contains("LZOP2["),
          "secondary root payload should export even when UI is single-engine mode.");
      assertTrue(roundTripRoot.isKataData2, "round-trip should keep secondary kata payload flag.");
      assertEquals(2.5, roundTripRoot.scoreMean2, 0.0001);
      assertEquals(0.6, roundTripRoot.scoreStdev2, 0.0001);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void parseSgfDetachedMidgameSnapshotLzHeaderOnlyKataPayloadKeepsScoreMeanAcrossRoundTrip()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf =
          "(;SZ[3];B[aa]AB[bb]LZ[Main 44.0 120 3.3 0.4\n"
              + "move C3 visits 120 winrate 5600 prior 5000 pv C3];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedSnapshot =
          parsed.getStart().next().orElseThrow().next().orElseThrow().getData();

      assertTrue(parsedSnapshot.isSnapshotNode(), "midgame setup should stay on SNAPSHOT.");
      assertTrue(parsedSnapshot.isKataData, "snapshot header scoreMean should keep Kata payload.");
      assertEquals(3.3, parsedSnapshot.scoreMean, 0.0001);
      assertEquals(0.4, parsedSnapshot.scoreStdev, 0.0001);

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardData roundTripSnapshot =
          roundTrip.getStart().next().orElseThrow().next().orElseThrow().getData();

      assertTrue(roundTripSnapshot.isKataData, "round-trip should keep snapshot kata payload.");
      assertEquals(3.3, roundTripSnapshot.scoreMean, 0.0001);
      assertEquals(0.4, roundTripSnapshot.scoreStdev, 0.0001);
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfDetachedMidgameSnapshotSingleLineHeaderOnlyLzZeroPlayoutsRoundTripKeepsPayload()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[3];B[aa]AB[bb]LZ[Main 44.0 0 3.3 0.4 0.9];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode parsedMove = parsed.getStart().next().orElseThrow();
      BoardHistoryNode parsedSnapshot = parsedMove.next().orElseThrow();

      assertNeutralPrimaryAnalysis(parsedMove.getData(), "parseSgf move");
      assertPrimaryHeaderOnlyPayload(
          parsedSnapshot.getData(), "parseSgf snapshot", "Main", 0, 3.3, 0.4, 0.9, true, true);

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardHistoryNode roundTripMove = roundTrip.getStart().next().orElseThrow();
      BoardHistoryNode roundTripSnapshot = roundTripMove.next().orElseThrow();

      assertEquals(
          1,
          countOccurrences(exported, "LZ["),
          "zero-playouts setup payload should export once on setup snapshot.");
      assertNeutralPrimaryAnalysis(roundTripMove.getData(), "round-trip move");
      assertPrimaryHeaderOnlyPayload(
          roundTripSnapshot.getData(), "round-trip snapshot", "Main", 0, 3.3, 0.4, 0.9, true, true);
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfDetachedMidgameSnapshotLz2HeaderOnlyKataPayloadKeepsScoreMean2AcrossRoundTrip()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf =
          "(;SZ[3];B[aa]AB[bb]LZ2[SubEngine 41.0 150 2.1 0.5\n"
              + "move C3 visits 150 winrate 5900 prior 5000 pv C3 B3];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedSnapshot =
          parsed.getStart().next().orElseThrow().next().orElseThrow().getData();

      assertTrue(parsedSnapshot.isSnapshotNode(), "midgame setup should stay on SNAPSHOT.");
      assertTrue(
          parsedSnapshot.isKataData2, "snapshot header scoreMean2 should keep secondary Kata.");
      assertEquals(2.1, parsedSnapshot.scoreMean2, 0.0001);
      assertEquals(0.5, parsedSnapshot.scoreStdev2, 0.0001);

      Lizzie.board.setHistory(parsed);
      Lizzie.config.extraMode = ExtraMode.Normal;
      String exported = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardData roundTripSnapshot =
          roundTrip.getStart().next().orElseThrow().next().orElseThrow().getData();

      assertTrue(roundTripSnapshot.isKataData2, "round-trip should keep secondary kata payload.");
      assertEquals(2.1, roundTripSnapshot.scoreMean2, 0.0001);
      assertEquals(0.5, roundTripSnapshot.scoreStdev2, 0.0001);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void parseSgfDetachedMidgameSnapshotSingleLineHeaderOnlyLz2ZeroPlayoutsRoundTripKeepsPayload()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3];B[aa]AB[bb]LZ2[Sub 41.0 0 2.1 0.5 0.8];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode parsedMove = parsed.getStart().next().orElseThrow();
      BoardHistoryNode parsedSnapshot = parsedMove.next().orElseThrow();

      assertSecondaryHeaderOnlyPayload(
          parsedSnapshot.getData(), "parseSgf snapshot", "Sub", 0, 2.1, 0.5, 0.8, true, true);

      Lizzie.board.setHistory(parsed);
      Lizzie.config.extraMode = ExtraMode.Normal;
      String exported = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardHistoryNode roundTripMove = roundTrip.getStart().next().orElseThrow();
      BoardHistoryNode roundTripSnapshot = roundTripMove.next().orElseThrow();

      assertEquals(
          1,
          countOccurrences(exported, "LZ2["),
          "zero-playouts secondary setup payload should export once on setup snapshot.");
      assertSecondaryHeaderOnlyPayload(
          roundTripSnapshot.getData(), "round-trip snapshot", "Sub", 0, 2.1, 0.5, 0.8, true, true);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void parseSgfDetachedMidgameSnapshotSingleLineHeaderOnlyLz2RoundTripKeepsSecondaryPayload()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3];B[aa]AB[bb]LZ2[Sub 41.0 150 2.1 0.5];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedSnapshot =
          parsed.getStart().next().orElseThrow().next().orElseThrow().getData();

      assertTrue(parsedSnapshot.isSnapshotNode(), "midgame setup should stay on SNAPSHOT.");
      assertEquals("Sub", parsedSnapshot.engineName2);
      assertEquals(
          150, parsedSnapshot.getPlayouts2(), "single-line header should keep setup playouts2.");
      assertTrue(parsedSnapshot.isKataData2, "single-line header should keep setup kata2 flag.");
      assertEquals(2.1, parsedSnapshot.scoreMean2, 0.0001);
      assertEquals(0.5, parsedSnapshot.scoreStdev2, 0.0001);

      Lizzie.board.setHistory(parsed);
      Lizzie.config.extraMode = ExtraMode.Normal;
      String exported = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardData roundTripSnapshot =
          roundTrip.getStart().next().orElseThrow().next().orElseThrow().getData();

      assertTrue(exported.contains("LZ2["), "single-line setup payload should still export.");
      assertEquals("Sub", roundTripSnapshot.engineName2);
      assertEquals(
          150,
          roundTripSnapshot.getPlayouts2(),
          "round-trip should keep single-line setup playouts2.");
      assertTrue(
          roundTripSnapshot.isKataData2, "round-trip should keep single-line setup kata2 flag.");
      assertEquals(2.1, roundTripSnapshot.scoreMean2, 0.0001);
      assertEquals(0.5, roundTripSnapshot.scoreStdev2, 0.0001);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void parseSgfDetachedMidgameSetupSnapshotRoundTripKeepsSingleEngineAnalysisPayload()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf =
          "(;SZ[3];B[aa]AB[bb]LZ[Main 44.0 120\n"
              + "move C3 visits 120 winrate 5600 prior 5000 pv C3];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode parsedSnapshot = parsed.getStart().next().orElseThrow().next().orElseThrow();
      BoardData parsedSnapshotData = parsedSnapshot.getData();

      assertTrue(parsedSnapshotData.isSnapshotNode(), "midgame setup should stay on SNAPSHOT.");
      assertEquals("Main", parsedSnapshotData.engineName);
      assertEquals(120, parsedSnapshotData.getPlayouts());
      assertFalse(parsedSnapshotData.bestMoves.isEmpty(), "detached parse should keep best-moves.");
      assertEquals("C3", parsedSnapshotData.bestMoves.get(0).coordinate);

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardData roundTripSnapshot =
          roundTrip.getStart().next().orElseThrow().next().orElseThrow().getData();

      assertTrue(exported.contains("LZ["), "snapshot analysis payload should be exported.");
      assertEquals("Main", roundTripSnapshot.engineName);
      assertEquals(120, roundTripSnapshot.getPlayouts());
      assertFalse(roundTripSnapshot.bestMoves.isEmpty(), "round-trip should keep best-moves.");
      assertEquals("C3", roundTripSnapshot.bestMoves.get(0).coordinate);
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfDetachedLzop2RoundTripKeepsSecondaryPayloadWhenUiModeIsSingleEngine()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf =
          "(;SZ[3]LZOP2[SubEngine 41.0 150\n"
              + "move C3 visits 150 winrate 5900 prior 5000 pv C3 B3];B[aa])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedRoot = parsed.getStart().getData();
      assertEquals("SubEngine", parsedRoot.engineName2);
      assertEquals(150, parsedRoot.getPlayouts2());
      assertFalse(parsedRoot.bestMoves2.isEmpty(), "detached parse should keep root best-moves2.");
      assertEquals("C3", parsedRoot.bestMoves2.get(0).coordinate);

      Lizzie.board.setHistory(parsed);
      Lizzie.config.extraMode = ExtraMode.Normal;
      String exported = SGFParser.saveToString(false);
      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();

      assertTrue(
          exported.contains("LZOP2["),
          "secondary root payload should export even when UI is single-engine mode.");
      assertEquals("SubEngine", roundTripRoot.engineName2);
      assertEquals(150, roundTripRoot.getPlayouts2());
      assertFalse(roundTripRoot.bestMoves2.isEmpty(), "round-trip should keep root best-moves2.");
      assertEquals("C3", roundTripRoot.bestMoves2.get(0).coordinate);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void parseSgfDetachedMidgameSetupSnapshotRoundTripKeepsSecondaryAnalysisPayloadWhenUiSingleMode()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf =
          "(;SZ[3];B[aa]AB[bb]LZ2[SubEngine 41.0 150\n"
              + "move C3 visits 150 winrate 5900 prior 5000 pv C3 B3];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedSnapshot =
          parsed.getStart().next().orElseThrow().next().orElseThrow().getData();

      assertTrue(parsedSnapshot.isSnapshotNode(), "midgame setup should stay on SNAPSHOT.");
      assertEquals("SubEngine", parsedSnapshot.engineName2);
      assertEquals(150, parsedSnapshot.getPlayouts2());
      assertFalse(parsedSnapshot.bestMoves2.isEmpty(), "detached parse should keep best-moves2.");
      assertEquals("C3", parsedSnapshot.bestMoves2.get(0).coordinate);

      Lizzie.board.setHistory(parsed);
      Lizzie.config.extraMode = ExtraMode.Normal;
      String exported = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardData roundTripSnapshot =
          roundTrip.getStart().next().orElseThrow().next().orElseThrow().getData();

      assertTrue(
          exported.contains("LZ2["),
          "secondary snapshot payload should export even when UI is single-engine mode.");
      assertEquals("SubEngine", roundTripSnapshot.engineName2);
      assertEquals(150, roundTripSnapshot.getPlayouts2());
      assertFalse(roundTripSnapshot.bestMoves2.isEmpty(), "round-trip should keep best-moves2.");
      assertEquals("C3", roundTripSnapshot.bestMoves2.get(0).coordinate);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void parseSgfMoveThenPrimaryAnalysisThenSetupTransfersOwnershipToSetupSnapshot()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf =
          "(;SZ[3];B[aa]LZ[Main 44.0 120 3.3 0.4\n"
              + "move C3 visits 120 winrate 5600 scoreMean 3.3 scoreStdev 0.4 prior 5000 pv C3 B3 ownership 0.1 -0.1]"
              + "AB[bb];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertPrimaryAnalysisOwnershipOnSetupSnapshot(parsed, "parseSgf");

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          1,
          countOccurrences(exported, "LZ["),
          "saveToString should emit one primary analysis payload on the setup snapshot.");
      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      assertPrimaryAnalysisOwnershipOnSetupSnapshot(roundTrip, "round-trip");
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfMoveThenStandaloneSetupDoesNotDuplicatePrimaryAnalysisPayloadOnSave()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf =
          "(;SZ[3];B[aa]LZ[Main 44.0 120\n"
              + "move C3 visits 120 winrate 5600 prior 5000 pv C3];AB[bb];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode moveNode = parsed.getStart().next().orElseThrow();
      BoardHistoryNode setupNode = moveNode.next().orElseThrow();

      assertTrue(moveNode.getData().isMoveNode(), "fixture should keep analysis on the move node.");
      assertTrue(setupNode.getData().isSnapshotNode(), "fixture should keep setup as SNAPSHOT.");
      assertEquals(120, moveNode.getData().getPlayouts(), "move should keep primary playouts.");
      assertEquals(
          0,
          setupNode.getData().getPlayouts(),
          "standalone setup SNAPSHOT should not inherit primary playouts.");

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          1,
          countOccurrences(exported, "LZ["),
          "saveToString should emit primary analysis payload exactly once.");

      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardHistoryNode roundTripMove = roundTrip.getStart().next().orElseThrow();
      BoardHistoryNode roundTripSetup = roundTripMove.next().orElseThrow();
      assertEquals(120, roundTripMove.getData().getPlayouts());
      assertEquals(0, roundTripSetup.getData().getPlayouts());
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfMoveThenStandaloneSetupKeepsPrimaryScalarsNeutralOnSetupAndFollowingMove()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf =
          "(;SZ[3];B[aa]LZ[Main 44.0 120\n"
              + "move C3 visits 120 winrate 5600 prior 5000 pv C3];AB[bb];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode moveNode = parsed.getStart().next().orElseThrow();
      BoardHistoryNode setupNode = moveNode.next().orElseThrow();
      BoardHistoryNode trailingMove = setupNode.next().orElseThrow();

      assertEquals(
          "Main", moveNode.getData().engineName, "owner move should keep primary payload.");
      assertEquals(120, moveNode.getData().getPlayouts(), "owner move should keep playouts.");
      assertNeutralPrimaryAnalysis(setupNode.getData(), "parseSgf setup snapshot");
      assertNeutralPrimaryAnalysis(trailingMove.getData(), "parseSgf trailing move");

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardHistoryNode roundTripMove = roundTrip.getStart().next().orElseThrow();
      BoardHistoryNode roundTripSetup = roundTripMove.next().orElseThrow();
      BoardHistoryNode roundTripTrailingMove = roundTripSetup.next().orElseThrow();

      assertEquals(1, countOccurrences(exported, "LZ["), "primary payload should export once.");
      assertNeutralPrimaryAnalysis(roundTripSetup.getData(), "round-trip setup snapshot");
      assertNeutralPrimaryAnalysis(roundTripTrailingMove.getData(), "round-trip trailing move");
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfMoveThenSecondaryAnalysisThenSetupTransfersOwnershipToSetupSnapshot()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf =
          "(;SZ[3];B[aa]LZ2[SubEngine 41.0 150 2.1 0.5\n"
              + "move C3 visits 150 winrate 5900 scoreMean 2.1 scoreStdev 0.5 prior 5000 pv C3 B3 ownership 0.2 -0.2]"
              + "AB[bb];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertSecondaryAnalysisOwnershipOnSetupSnapshot(parsed, "parseSgf");

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          1,
          countOccurrences(exported, "LZ2["),
          "saveToString should emit one secondary analysis payload on the setup snapshot.");
      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      assertSecondaryAnalysisOwnershipOnSetupSnapshot(roundTrip, "round-trip");
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfMoveThenStandaloneSetupKeepsSecondaryAnalysisOnMoveOnly() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf =
          "(;SZ[3];B[aa]LZ2[SubEngine 41.0 150\n"
              + "move C3 visits 150 winrate 5900 prior 5000 pv C3 B3];AB[bb];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode moveNode = parsed.getStart().next().orElseThrow();
      BoardHistoryNode setupNode = moveNode.next().orElseThrow();

      assertEquals(150, moveNode.getData().getPlayouts2(), "move should keep secondary playouts2.");
      assertEquals(
          0,
          setupNode.getData().getPlayouts2(),
          "standalone setup SNAPSHOT should keep secondary playouts2 cleared.");

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          1,
          countOccurrences(exported, "LZ2["),
          "saveToString should emit secondary analysis payload exactly once.");
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void parseSgfRootPrimaryOwnerThenStandaloneSetupKeepsOwnerAndNeutralSetupBaseline()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf =
          "(;SZ[3]LZOP[Main 44.0 120\n"
              + "move C3 visits 120 winrate 5600 prior 5000 pv C3];AB[bb];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode root = parsed.getStart();
      BoardHistoryNode setupNode = root.next().orElseThrow();
      BoardHistoryNode trailingMove = setupNode.next().orElseThrow();

      assertEquals("Main", root.getData().engineName, "root should keep primary owner payload.");
      assertEquals(120, root.getData().getPlayouts(), "root should keep primary playouts.");
      assertNeutralPrimaryAnalysis(setupNode.getData(), "parseSgf setup snapshot");
      assertNeutralPrimaryAnalysis(trailingMove.getData(), "parseSgf trailing move");

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardHistoryNode roundTripRoot = roundTrip.getStart();
      BoardHistoryNode roundTripSetup = roundTripRoot.next().orElseThrow();
      BoardHistoryNode roundTripTrailingMove = roundTripSetup.next().orElseThrow();

      assertEquals(
          1, countOccurrences(exported, "LZOP["), "root owner payload should export once.");
      assertEquals(
          "Main", roundTripRoot.getData().engineName, "round-trip root should keep owner payload.");
      assertEquals(
          120, roundTripRoot.getData().getPlayouts(), "round-trip root should keep playouts.");
      assertNeutralPrimaryAnalysis(roundTripSetup.getData(), "round-trip setup snapshot");
      assertNeutralPrimaryAnalysis(roundTripTrailingMove.getData(), "round-trip trailing move");
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfStandaloneSetupLeadingPrimaryZeroPlayoutOwnerStaysOnSetupSnapshot()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[3];LZ[Main 44.0 0 3.5 0.7 0.9]AB[aa];W[bb])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode root = parsed.getStart();
      BoardHistoryNode setupNode = root.next().orElseThrow();
      assertTrue(setupNode.next().isPresent(), "fixture should keep trailing move.");

      assertTrue(setupNode.getData().isSnapshotNode(), "setup node should stay SNAPSHOT.");
      assertPrimaryHeaderOnlyPayload(
          setupNode.getData(), "parseSgf setup snapshot", "Main", 0, 3.5, 0.7, 0.9, true, true);
      assertNeutralPrimaryAnalysis(root.getData(), "parseSgf root");
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfStandaloneSetupLeadingSecondaryPositivePlayoutOwnerStaysOnSetupSnapshot()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3];C[h]LZ2[Sub 41.0 150 2.1 0.5 0.8]PL[W];B[aa])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode root = parsed.getStart();
      BoardHistoryNode setupNode = root.next().orElseThrow();
      assertTrue(setupNode.next().isPresent(), "fixture should keep trailing move.");

      assertTrue(setupNode.getData().isSnapshotNode(), "setup node should stay SNAPSHOT.");
      assertEquals(
          "h", setupNode.getData().comment, "setup snapshot should keep same-node comment.");
      assertEquals("W", setupNode.getData().getProperty("PL"), "setup snapshot should keep PL.");
      assertSecondaryHeaderOnlyPayload(
          setupNode.getData(), "parseSgf setup snapshot", "Sub", 150, 2.1, 0.5, 0.8, true, true);
      assertNeutralSecondaryAnalysis(root.getData(), "parseSgf root");
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void standaloneSetupLeadingPrimaryZeroPlayoutOwnerSurvivesSaveRoundTrip() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[3];LZ[Main 44.0 0 3.5 0.7 0.9]AB[aa];W[bb])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      Lizzie.board.setHistory(parsed);

      String exported = SGFParser.saveToString(false);
      assertEquals(1, countOccurrences(exported, "LZ["), "setup owner payload should export once.");

      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardHistoryNode roundTripRoot = roundTrip.getStart();
      BoardHistoryNode roundTripSetup = roundTripRoot.next().orElseThrow();
      assertTrue(roundTripSetup.next().isPresent(), "round-trip should keep trailing move.");

      assertPrimaryHeaderOnlyPayload(
          roundTripSetup.getData(),
          "round-trip setup snapshot",
          "Main",
          0,
          3.5,
          0.7,
          0.9,
          true,
          true);
      assertNeutralPrimaryAnalysis(roundTripRoot.getData(), "round-trip root");
    } finally {
      env.close();
    }
  }

  @Test
  void rootHeaderOnlyPrimaryOwnerKeepsHighPrecisionScalarsAndSingleLineExport() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[3]LZOP[Main 44.0 0 3.55 0.77 0.91])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertPrimaryHeaderOnlyPayload(
          parsed.getStart().getData(), "parseSgf root", "Main", 0, 3.55, 0.77, 0.91, true, true);

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          1, countOccurrences(exported, "LZOP["), "root owner payload should export once.");
      assertTrue(
          exported.contains("LZOP[Main 44.0 0 3.55 0.77 0.91]"),
          "root owner export should keep high precision header scalars.");
      assertFalse(exported.contains("\n]"), "root owner export should stay header-only.");

      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();
      assertPrimaryHeaderOnlyPayload(
          roundTripRoot, "round-trip root", "Main", 0, 3.55, 0.77, 0.91, true, true);
    } finally {
      env.close();
    }
  }

  @Test
  void rootHeaderOnlySecondaryOwnerKeepsHighPrecisionScalarsAndSingleLineExport() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3]LZOP2[Sub 41.0 0 2.15 0.58 0.82])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertSecondaryHeaderOnlyPayload(
          parsed.getStart().getData(), "parseSgf root", "Sub", 0, 2.15, 0.58, 0.82, true, true);

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          1, countOccurrences(exported, "LZOP2["), "root owner payload should export once.");
      assertTrue(
          exported.contains("LZOP2[Sub 41.0 0 2.15 0.58 0.82]"),
          "root secondary owner export should keep high precision header scalars.");
      assertFalse(exported.contains("\n]"), "root secondary owner export should stay header-only.");

      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();
      assertSecondaryHeaderOnlyPayload(
          roundTripRoot, "round-trip root", "Sub", 0, 2.15, 0.58, 0.82, true, true);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void sameNodeSetupOwnerHeaderOnlyHighPrecisionPrimarySecondaryExportOnce() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf =
          "(;SZ[3];B[aa]LZ[Main 44.0 0 3.55 0.77 0.91]LZ2[Sub 41.0 0 2.15 0.58 0.82]AB[bb];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode parsedMove = parsed.getStart().next().orElseThrow();
      BoardHistoryNode parsedSetup = parsedMove.next().orElseThrow();
      assertNeutralPrimaryAnalysis(parsedMove.getData(), "parseSgf move");
      assertEquals(
          "", parsedMove.getData().engineName2, "parseSgf move should clear secondary engine.");
      assertEquals(0, parsedMove.getData().getPlayouts2(), "parseSgf move should clear playouts2.");
      assertTrue(
          parsedMove.getData().bestMoves2.isEmpty(),
          "parseSgf move should clear secondary best-moves.");
      assertFalse(
          parsedMove.getData().isKataData2, "parseSgf move should clear secondary kata flag.");
      assertPrimaryHeaderOnlyPayload(
          parsedSetup.getData(),
          "parseSgf setup snapshot",
          "Main",
          0,
          3.55,
          0.77,
          0.91,
          true,
          true);
      assertSecondaryHeaderOnlyPayload(
          parsedSetup.getData(), "parseSgf setup snapshot", "Sub", 0, 2.15, 0.58, 0.82, true, true);

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          1, countOccurrences(exported, "LZ["), "setup owner primary payload should export once.");
      assertEquals(
          1,
          countOccurrences(exported, "LZ2["),
          "setup owner secondary payload should export once.");
      assertTrue(
          exported.contains("LZ[Main 44.0 0 3.55 0.77 0.91]"),
          "setup owner primary export should keep high precision header scalars.");
      assertTrue(
          exported.contains("LZ2[Sub 41.0 0 2.15 0.58 0.82]"),
          "setup owner secondary export should keep high precision header scalars.");
      assertFalse(exported.contains("\n]"), "setup owner export should stay header-only.");

      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardHistoryNode roundTripMove = roundTrip.getStart().next().orElseThrow();
      BoardHistoryNode roundTripSetup = roundTripMove.next().orElseThrow();
      assertNeutralPrimaryAnalysis(roundTripMove.getData(), "round-trip move");
      assertEquals(
          "",
          roundTripMove.getData().engineName2,
          "round-trip move should clear secondary engine.");
      assertEquals(
          0, roundTripMove.getData().getPlayouts2(), "round-trip move should clear playouts2.");
      assertTrue(
          roundTripMove.getData().bestMoves2.isEmpty(),
          "round-trip move should clear secondary best-moves.");
      assertFalse(
          roundTripMove.getData().isKataData2, "round-trip move should clear secondary kata flag.");
      assertPrimaryHeaderOnlyPayload(
          roundTripSetup.getData(),
          "round-trip setup snapshot",
          "Main",
          0,
          3.55,
          0.77,
          0.91,
          true,
          true);
      assertSecondaryHeaderOnlyPayload(
          roundTripSetup.getData(),
          "round-trip setup snapshot",
          "Sub",
          0,
          2.15,
          0.58,
          0.82,
          true,
          true);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void standaloneSelfOwnedSetupHeaderOnlyHighPrecisionPrimarySecondaryExportOnce()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf =
          "(;SZ[3];LZ[Main 44.0 0 3.55 0.77 0.91]LZ2[Sub 41.0 0 2.15 0.58 0.82]AB[aa];W[bb])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode parsedRoot = parsed.getStart();
      BoardHistoryNode parsedSetup = parsedRoot.next().orElseThrow();
      assertNeutralPrimaryAnalysis(parsedRoot.getData(), "parseSgf root");
      assertNeutralSecondaryAnalysis(parsedRoot.getData(), "parseSgf root");
      assertPrimaryHeaderOnlyPayload(
          parsedSetup.getData(),
          "parseSgf setup snapshot",
          "Main",
          0,
          3.55,
          0.77,
          0.91,
          true,
          true);
      assertSecondaryHeaderOnlyPayload(
          parsedSetup.getData(), "parseSgf setup snapshot", "Sub", 0, 2.15, 0.58, 0.82, true, true);

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          1,
          countOccurrences(exported, "LZ["),
          "standalone owner primary payload should export once.");
      assertEquals(
          1,
          countOccurrences(exported, "LZ2["),
          "standalone owner secondary payload should export once.");
      assertTrue(
          exported.contains("LZ[Main 44.0 0 3.55 0.77 0.91]"),
          "standalone owner primary export should keep high precision header scalars.");
      assertTrue(
          exported.contains("LZ2[Sub 41.0 0 2.15 0.58 0.82]"),
          "standalone owner secondary export should keep high precision header scalars.");
      assertFalse(exported.contains("\n]"), "standalone owner export should stay header-only.");

      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardHistoryNode roundTripRoot = roundTrip.getStart();
      BoardHistoryNode roundTripSetup = roundTripRoot.next().orElseThrow();
      assertNeutralPrimaryAnalysis(roundTripRoot.getData(), "round-trip root");
      assertNeutralSecondaryAnalysis(roundTripRoot.getData(), "round-trip root");
      assertPrimaryHeaderOnlyPayload(
          roundTripSetup.getData(),
          "round-trip setup snapshot",
          "Main",
          0,
          3.55,
          0.77,
          0.91,
          true,
          true);
      assertSecondaryHeaderOnlyPayload(
          roundTripSetup.getData(),
          "round-trip setup snapshot",
          "Sub",
          0,
          2.15,
          0.58,
          0.82,
          true,
          true);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void rootHeaderOnlyPrimaryFourSlotsRoundTripKeepsExactHeaderSlots() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[3]LZOP[Main 44.0 120 3.5])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertEquals(4, parsed.getStart().getData().analysisHeaderSlots);
      Lizzie.board.setHistory(parsed);
      assertEquals(4, Lizzie.board.getHistory().getStart().getData().analysisHeaderSlots);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          1, countOccurrences(exported, "LZOP["), "root owner payload should export once.");
      assertEquals(
          "Main 44.0 120 3.5",
          extractSingleAnalysisPayload(exported, "LZOP"),
          "root owner export should keep exact 4-slot header.");

      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();
      assertEquals("Main", roundTripRoot.engineName);
      assertEquals(120, roundTripRoot.getPlayouts());
      assertEquals(3.5, roundTripRoot.scoreMean, 0.0001);
      assertEquals(0.0, roundTripRoot.scoreStdev, 0.0001);
      assertEquals(0.0, roundTripRoot.pda, 0.0001);
    } finally {
      env.close();
    }
  }

  @Test
  void rootHeaderOnlySecondaryFourSlotsRoundTripKeepsExactHeaderSlots() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3]LZOP2[Sub 41.0 120 2.1])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertEquals(4, parsed.getStart().getData().analysisHeaderSlots2);
      Lizzie.board.setHistory(parsed);
      assertEquals(4, Lizzie.board.getHistory().getStart().getData().analysisHeaderSlots2);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          1, countOccurrences(exported, "LZOP2["), "root owner payload should export once.");
      assertEquals(
          "Sub 41.0 120 2.1",
          extractSingleAnalysisPayload(exported, "LZOP2"),
          "root secondary export should keep exact 4-slot header.");

      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();
      assertEquals("Sub", roundTripRoot.engineName2);
      assertEquals(120, roundTripRoot.getPlayouts2());
      assertEquals(2.1, roundTripRoot.scoreMean2, 0.0001);
      assertEquals(0.0, roundTripRoot.scoreStdev2, 0.0001);
      assertEquals(0.0, roundTripRoot.pda2, 0.0001);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void sameNodeSetupOwnerHeaderOnlyExplicitZeroPdaPrimarySecondaryRoundTripKeepsSixSlots()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3];B[aa]LZ[Main 44.0 0 3.5 0.7 0]LZ2[Sub 41.0 0 2.1 0.5 0]AB[bb];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedSetup = parsed.getStart().next().orElseThrow().next().orElseThrow().getData();
      assertEquals(6, parsedSetup.analysisHeaderSlots);
      assertEquals(6, parsedSetup.analysisHeaderSlots2);
      Lizzie.board.setHistory(parsed);
      BoardData setupAfterSetHistory =
          Lizzie.board.getHistory().getStart().next().orElseThrow().next().orElseThrow().getData();
      assertEquals(6, setupAfterSetHistory.analysisHeaderSlots);
      assertEquals(6, setupAfterSetHistory.analysisHeaderSlots2);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          1, countOccurrences(exported, "LZ["), "setup owner primary payload should export once.");
      assertEquals(
          1,
          countOccurrences(exported, "LZ2["),
          "setup owner secondary payload should export once.");
      assertEquals(
          "Main 44.0 0 3.5 0.7 0.0",
          extractSingleAnalysisPayload(exported, "LZ"),
          "same-node setup primary export should keep explicit zero-pda 6 slots.");
      assertEquals(
          "Sub 41.0 0 2.1 0.5 0.0",
          extractSingleAnalysisPayload(exported, "LZ2"),
          "same-node setup secondary export should keep explicit zero-pda 6 slots.");

      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardHistoryNode roundTripSetup =
          roundTrip.getStart().next().orElseThrow().next().orElseThrow();
      BoardData setupData = roundTripSetup.getData();
      assertEquals(0.0, setupData.pda, 0.0001);
      assertEquals(0.0, setupData.pda2, 0.0001);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void standaloneSelfOwnedSetupHeaderOnlyExplicitZeroPdaPrimarySecondaryRoundTripKeepsSixSlots()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3];LZ[Main 44.0 0 3.5 0.7 0]LZ2[Sub 41.0 0 2.1 0.5 0]AB[aa];W[bb])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedSetup = parsed.getStart().next().orElseThrow().getData();
      assertEquals(6, parsedSetup.analysisHeaderSlots);
      assertEquals(6, parsedSetup.analysisHeaderSlots2);
      Lizzie.board.setHistory(parsed);
      BoardData setupAfterSetHistory =
          Lizzie.board.getHistory().getStart().next().orElseThrow().getData();
      assertEquals(6, setupAfterSetHistory.analysisHeaderSlots);
      assertEquals(6, setupAfterSetHistory.analysisHeaderSlots2);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          1,
          countOccurrences(exported, "LZ["),
          "standalone owner primary payload should export once.");
      assertEquals(
          1,
          countOccurrences(exported, "LZ2["),
          "standalone owner secondary payload should export once.");
      assertEquals(
          "Main 44.0 0 3.5 0.7 0.0",
          extractSingleAnalysisPayload(exported, "LZ"),
          "standalone setup primary export should keep explicit zero-pda 6 slots.");
      assertEquals(
          "Sub 41.0 0 2.1 0.5 0.0",
          extractSingleAnalysisPayload(exported, "LZ2"),
          "standalone setup secondary export should keep explicit zero-pda 6 slots.");

      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardHistoryNode roundTripSetup = roundTrip.getStart().next().orElseThrow();
      BoardData setupData = roundTripSetup.getData();
      assertEquals(0.0, setupData.pda, 0.0001);
      assertEquals(0.0, setupData.pda2, 0.0001);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void rootHeaderOnlyPrimaryFiveSlotsRoundTripKeepsExactHeaderSlots() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[3]LZOP[Main 44.0 120 3.5 0.7])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertEquals(5, parsed.getStart().getData().analysisHeaderSlots);
      Lizzie.board.setHistory(parsed);
      assertEquals(5, Lizzie.board.getHistory().getStart().getData().analysisHeaderSlots);

      String exported = SGFParser.saveToString(false);
      assertEquals(
          1, countOccurrences(exported, "LZOP["), "root owner payload should export once.");
      assertEquals(
          "Main 44.0 120 3.5 0.7",
          extractSingleAnalysisPayload(exported, "LZOP"),
          "root owner export should keep exact 5-slot header.");
    } finally {
      env.close();
    }
  }

  @Test
  void rootHeaderOnlySecondaryFiveSlotsRoundTripKeepsExactHeaderSlots() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3]LZOP2[Sub 41.0 120 2.1 0.5])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertEquals(5, parsed.getStart().getData().analysisHeaderSlots2);
      Lizzie.board.setHistory(parsed);
      assertEquals(5, Lizzie.board.getHistory().getStart().getData().analysisHeaderSlots2);

      String exported = SGFParser.saveToString(false);
      assertEquals(
          1, countOccurrences(exported, "LZOP2["), "root owner payload should export once.");
      assertEquals(
          "Sub 41.0 120 2.1 0.5",
          extractSingleAnalysisPayload(exported, "LZOP2"),
          "root owner export should keep exact 5-slot header.");
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void clearBestMovesRemovesPrimaryPayloadAndSlotsFromRootOwner() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[3]LZOP[Main 44.0 120 3.5])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData rootData = parsed.getStart().getData();
      rootData.tryToClearBestMoves();
      assertEquals("", rootData.engineName, "clear should reset primary engine.");
      assertEquals(0, rootData.getPlayouts(), "clear should reset primary playouts.");
      assertEquals(0, rootData.analysisHeaderSlots, "clear should reset primary header slots.");

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          0, countOccurrences(exported, "LZOP["), "clear should remove root primary payload.");
      assertEquals(
          0, countOccurrences(exported, "LZ["), "clear should remove non-root primary payload.");
    } finally {
      env.close();
    }
  }

  @Test
  void clearBestMovesRemovesPrimarySecondaryPayloadsAndSlotsFromSetupOwner() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3];LZ[Main 44.0 0 3.5 0.7 0]LZ2[Sub 41.0 0 2.1 0.5 0]AB[aa];W[bb])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData setupOwner = parsed.getStart().next().orElseThrow().getData();
      setupOwner.tryToClearBestMoves();
      assertEquals("", setupOwner.engineName, "clear should reset primary engine.");
      assertEquals("", setupOwner.engineName2, "clear should reset secondary engine.");
      assertEquals(0, setupOwner.analysisHeaderSlots, "clear should reset primary slots.");
      assertEquals(0, setupOwner.analysisHeaderSlots2, "clear should reset secondary slots.");

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          0, countOccurrences(exported, "LZOP["), "clear should remove root primary payload.");
      assertEquals(
          0, countOccurrences(exported, "LZOP2["), "clear should remove root secondary payload.");
      assertEquals(
          0, countOccurrences(exported, "LZ["), "clear should remove setup primary payload.");
      assertEquals(
          0, countOccurrences(exported, "LZ2["), "clear should remove setup secondary payload.");
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void cloneKeepsAnalysisHeaderSlotsForPrimaryAndSecondaryExactExport() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3]LZOP[Main 44.0 120 3.5 0.7]LZOP2[Sub 41.0 120 2.1 0.5])";

      BoardData source = SGFParser.parseSgf(sgf, false).getStart().getData();
      BoardData cloned = source.clone();
      assertEquals(5, cloned.analysisHeaderSlots, "clone should keep primary header slots.");
      assertEquals(5, cloned.analysisHeaderSlots2, "clone should keep secondary header slots.");

      Lizzie.board.setHistory(new BoardHistoryList(cloned));
      String exported = SGFParser.saveToString(false);
      assertEquals("Main 44.0 120 3.5 0.7", extractSingleAnalysisPayload(exported, "LZOP"));
      assertEquals("Sub 41.0 120 2.1 0.5", extractSingleAnalysisPayload(exported, "LZOP2"));
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void syncKeepsAnalysisHeaderSlotsForPrimaryAndSecondaryExactExport() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3]LZOP[Main 44.0 120 3.5 0.7]LZOP2[Sub 41.0 120 2.1 0.5])";

      BoardData source = SGFParser.parseSgf(sgf, false).getStart().getData();
      BoardData target = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      target.sync(source);
      assertEquals(5, target.analysisHeaderSlots, "sync should keep primary header slots.");
      assertEquals(5, target.analysisHeaderSlots2, "sync should keep secondary header slots.");

      Lizzie.board.setHistory(new BoardHistoryList(target));
      String exported = SGFParser.saveToString(false);
      assertEquals("Main 44.0 120 3.5 0.7", extractSingleAnalysisPayload(exported, "LZOP"));
      assertEquals("Sub 41.0 120 2.1 0.5", extractSingleAnalysisPayload(exported, "LZOP2"));
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void sameNodeSetupOwnerHeaderOnlyPrimaryFourSecondaryFiveSlotsKeepExactHeaders()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3];B[aa]LZ[Main 44.0 0 3.5]LZ2[Sub 41.0 0 2.1 0.5]AB[bb];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData setupOwner = parsed.getStart().next().orElseThrow().next().orElseThrow().getData();
      assertEquals(4, setupOwner.analysisHeaderSlots);
      assertEquals(5, setupOwner.analysisHeaderSlots2);

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          1, countOccurrences(exported, "LZ["), "setup owner primary payload should export once.");
      assertEquals(
          1,
          countOccurrences(exported, "LZ2["),
          "setup owner secondary payload should export once.");
      assertEquals("Main 44.0 0 3.5", extractSingleAnalysisPayload(exported, "LZ"));
      assertEquals("Sub 41.0 0 2.1 0.5", extractSingleAnalysisPayload(exported, "LZ2"));
      assertFalse(exported.contains("\n]"), "setup owner export should stay header-only.");
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void standaloneSelfOwnedSetupOwnerHeaderOnlyPrimaryFiveSecondaryFourSlotsKeepExactHeaders()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3];LZ[Main 44.0 0 3.5 0.7]LZ2[Sub 41.0 0 2.1]AB[aa];W[bb])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData setupOwner = parsed.getStart().next().orElseThrow().getData();
      assertEquals(5, setupOwner.analysisHeaderSlots);
      assertEquals(4, setupOwner.analysisHeaderSlots2);

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          1, countOccurrences(exported, "LZ["), "setup owner primary payload should export once.");
      assertEquals(
          1,
          countOccurrences(exported, "LZ2["),
          "setup owner secondary payload should export once.");
      assertEquals("Main 44.0 0 3.5 0.7", extractSingleAnalysisPayload(exported, "LZ"));
      assertEquals("Sub 41.0 0 2.1", extractSingleAnalysisPayload(exported, "LZ2"));
      assertFalse(exported.contains("\n]"), "setup owner export should stay header-only.");
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void nonRootSetupOwnerCloneKeepsExactHeaderExportForFourAndFiveSlots() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3];LZ[Main 44.0 0 3.5]LZ2[Sub 41.0 0 2.1 0.5]AB[aa];W[bb])";

      BoardData source = SGFParser.parseSgf(sgf, false).getStart().next().orElseThrow().getData();
      BoardData cloned = source.clone();
      assertEquals(4, cloned.analysisHeaderSlots);
      assertEquals(5, cloned.analysisHeaderSlots2);

      BoardHistoryNode clonedNode = new BoardHistoryNode(cloned);
      String primaryHeader =
          (String)
              requireMethod(SGFParser.class, "formatNodeData", BoardHistoryNode.class)
                  .invoke(null, clonedNode);
      String secondaryHeader =
          (String)
              requireMethod(SGFParser.class, "formatNodeData2", BoardHistoryNode.class)
                  .invoke(null, clonedNode);

      assertEquals("Main 44.0 0 3.5", primaryHeader);
      assertEquals("Sub 41.0 0 2.1 0.5", secondaryHeader);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void nonRootSetupOwnerSyncKeepsExactHeaderExportForFiveAndFourSlots() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3];LZ[Main 44.0 0 3.5 0.7]LZ2[Sub 41.0 0 2.1]AB[aa];W[bb])";

      BoardData source = SGFParser.parseSgf(sgf, false).getStart().next().orElseThrow().getData();
      BoardData target = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
      target.sync(source);
      assertEquals(5, target.analysisHeaderSlots);
      assertEquals(4, target.analysisHeaderSlots2);

      BoardHistoryNode syncedNode = new BoardHistoryNode(target);
      String primaryHeader =
          (String)
              requireMethod(SGFParser.class, "formatNodeData", BoardHistoryNode.class)
                  .invoke(null, syncedNode);
      String secondaryHeader =
          (String)
              requireMethod(SGFParser.class, "formatNodeData2", BoardHistoryNode.class)
                  .invoke(null, syncedNode);

      assertEquals("Main 44.0 0 3.5 0.7", primaryHeader);
      assertEquals("Sub 41.0 0 2.1", secondaryHeader);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void nonRootSetupOwnerClearResetsAllAnalysisFieldsAndRemovesAllAnalysisTags() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Normal;
      String sgf = "(;SZ[3];LZ[Main 44.0 0 3.5 0.7]LZ2[Sub 41.0 0 2.1]AB[aa];W[bb])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData setupOwner = parsed.getStart().next().orElseThrow().getData();
      setupOwner.tryToClearBestMoves();
      assertAllAnalysisFieldsCleared(setupOwner, "non-root setup owner");

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      assertEquals(
          0, countOccurrences(exported, "LZOP["), "clear should remove root primary payload.");
      assertEquals(
          0, countOccurrences(exported, "LZOP2["), "clear should remove root secondary payload.");
      assertEquals(
          0, countOccurrences(exported, "LZ["), "clear should remove setup primary payload.");
      assertEquals(
          0, countOccurrences(exported, "LZ2["), "clear should remove setup secondary payload.");
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void standaloneSetupLeadingPrimaryCollectionsKeepPreviousAndRootNeutral() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf =
          "(;SZ[3];B[cc];LZ[Main 44.0 120 3.5 0.7 0.9\n"
              + "move C3 visits 120 winrate 5600 scoreMean 3.5 scoreStdev 0.7 prior 5000 pv C3 B3 ownership 0.1 -0.2]"
              + "AB[aa];W[bb])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardHistoryNode root = parsed.getStart();
      BoardHistoryNode previousMove = root.next().orElseThrow();
      BoardHistoryNode setupNode = previousMove.next().orElseThrow();

      assertTrue(setupNode.getData().isSnapshotNode(), "standalone setup should stay SNAPSHOT.");
      assertEquals("Main", setupNode.getData().engineName, "setup snapshot should keep owner.");
      assertEquals(120, setupNode.getData().getPlayouts(), "setup snapshot should keep playouts.");
      assertFalse(
          setupNode.getData().bestMoves.isEmpty(), "setup snapshot should keep best-moves.");
      assertTrue(
          setupNode.getData().estimateArray != null && !setupNode.getData().estimateArray.isEmpty(),
          "setup snapshot should keep ownership array.");
      assertNeutralPrimaryAnalysis(root.getData(), "parseSgf root");
      assertNeutralPrimaryAnalysis(previousMove.getData(), "parseSgf previous move");
    } finally {
      env.close();
    }
  }

  @Test
  void loadFromStringEntriesMatchParseSgfForMoveThenAnalysisThenSetupOwnership() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf =
          "(;SZ[3];B[aa]LZ[Main 44.0 120 3.3 0.4\n"
              + "move C3 visits 120 winrate 5600 scoreMean 3.3 scoreStdev 0.4 prior 5000 pv C3 B3 ownership 0.1 -0.1]"
              + "AB[bb];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertPrimaryAnalysisOwnershipOnSetupSnapshot(parsed, "parseSgf baseline");

      assertTrue(
          SGFParser.loadFromString(sgf),
          "loadFromString should parse move-analysis-setup fixture.");
      assertPrimaryAnalysisOwnershipOnSetupSnapshot(Lizzie.board.getHistory(), "loadFromString");
      assertPrimaryAnalysisOwnershipMatchesParsed(
          parsed, Lizzie.board.getHistory(), "loadFromString");

      assertTrue(
          SGFParser.loadFromStringforedit(sgf),
          "loadFromStringforedit should parse move-analysis-setup fixture.");
      assertPrimaryAnalysisOwnershipOnSetupSnapshot(
          Lizzie.board.getHistory(), "loadFromStringforedit");
      assertPrimaryAnalysisOwnershipMatchesParsed(
          parsed, Lizzie.board.getHistory(), "loadFromStringforedit");
    } finally {
      env.close();
    }
  }

  @Test
  void loadFromStringEntriesMatchParseSgfForMoveThenSecondaryAnalysisThenSetupOwnership()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      String sgf =
          "(;SZ[3];B[aa]LZ2[SubEngine 41.0 150 2.1 0.5\n"
              + "move C3 visits 150 winrate 5900 scoreMean 2.1 scoreStdev 0.5 prior 5000 pv C3 B3 ownership 0.2 -0.2]"
              + "AB[bb];W[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertSecondaryAnalysisOwnershipOnSetupSnapshot(parsed, "parseSgf baseline");

      assertTrue(
          SGFParser.loadFromString(sgf),
          "loadFromString should parse move-secondary-analysis-setup fixture.");
      assertSecondaryAnalysisOwnershipOnSetupSnapshot(Lizzie.board.getHistory(), "loadFromString");
      assertSecondaryAnalysisOwnershipMatchesParsed(
          parsed, Lizzie.board.getHistory(), "loadFromString");

      assertTrue(
          SGFParser.loadFromStringforedit(sgf),
          "loadFromStringforedit should parse move-secondary-analysis-setup fixture.");
      assertSecondaryAnalysisOwnershipOnSetupSnapshot(
          Lizzie.board.getHistory(), "loadFromStringforedit");
      assertSecondaryAnalysisOwnershipMatchesParsed(
          parsed, Lizzie.board.getHistory(), "loadFromStringforedit");
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void parseSgfDetachedLzop2RoundTripKeepsDoubleEngineAnalysisPayload() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      String sgf =
          "(;SZ[3]LZOP2[SubEngine 41.0 150\n"
              + "move C3 visits 150 winrate 5900 prior 5000 pv C3 B3])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedRoot = parsed.getStart().getData();

      assertEquals("SubEngine", parsedRoot.engineName2);
      assertEquals(150, parsedRoot.getPlayouts2());
      assertFalse(parsedRoot.bestMoves2.isEmpty(), "detached parse should keep root best-moves2.");
      assertEquals("C3", parsedRoot.bestMoves2.get(0).coordinate);

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();

      assertEquals("SubEngine", roundTripRoot.engineName2);
      assertEquals(150, roundTripRoot.getPlayouts2());
      assertFalse(roundTripRoot.bestMoves2.isEmpty(), "round-trip should keep root best-moves2.");
      assertEquals("C3", roundTripRoot.bestMoves2.get(0).coordinate);
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void parseSgfDetachedKataPayloadSetsKataBoardOnlyWhenHistoryIsAdopted() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf =
          "(;SZ[3]LZOP[MainEngine 44.0 120 3.5\n"
              + "move D4 visits 120 winrate 5600 scoreMean 3.5 prior 5000 pv D4 C4])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      BoardData parsedRoot = parsed.getStart().getData();
      assertTrue(parsedRoot.isKataData, "detached parse should keep kata payload on history root.");
      assertEquals(3.5, parsedRoot.scoreMean, 0.0001);
      assertFalse(
          Lizzie.board.isKataBoard,
          "detached parse should keep live board kata flag unchanged before adopt.");

      Lizzie.board.setHistory(parsed);

      assertTrue(
          Lizzie.board.isKataBoard,
          "adopting detached kata payload should re-derive board kata flag.");
      String exported = SGFParser.saveToString(false);
      assertTrue(
          exported.contains("DZ[G]"), "kata board adoption should export DZ[G] on saveToString.");
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfDetachedRootDzKbSetsPkKataFlagsWhenAdopted() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList parsed = SGFParser.parseSgf("(;SZ[3]DZ[KB];B[aa])", false);
      assertEquals("KB", parsed.getStart().getData().getProperty("DZ"));
      assertFalse(Lizzie.board.isPkBoard);
      assertFalse(Lizzie.board.isKataBoard);

      Lizzie.board.setHistory(parsed);

      assertTrue(Lizzie.board.isPkBoard, "DZ[KB] adopt should enter PK board mode.");
      assertTrue(Lizzie.board.isKataBoard, "DZ[KB] adopt should enable kata board flag.");
      assertTrue(Lizzie.board.isPkBoardKataB, "DZ[KB] adopt should set black kata PK flag.");
      assertFalse(Lizzie.board.isPkBoardKataW, "DZ[KB] adopt should keep white kata PK flag off.");
    } finally {
      env.close();
    }
  }

  @Test
  void setHistoryWithNormalDetachedHistoryDoesNotKeepStaleKataFlag() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList kataHistory = SGFParser.parseSgf("(;SZ[3]DZ[G];B[aa])", false);
      Lizzie.board.setHistory(kataHistory);
      assertTrue(Lizzie.board.isKataBoard, "first adopt should set kata board flag.");

      BoardHistoryList normalHistory = SGFParser.parseSgf("(;SZ[3];B[aa])", false);
      Lizzie.board.setHistory(normalHistory);

      assertFalse(Lizzie.board.isKataBoard, "normal adopt should clear kata board flag.");
      assertFalse(Lizzie.board.isPkBoard, "normal adopt should clear PK board flag.");
      assertFalse(Lizzie.board.isPkBoardKataB, "normal adopt should clear black PK kata flag.");
      assertFalse(Lizzie.board.isPkBoardKataW, "normal adopt should clear white PK kata flag.");
      String exported = SGFParser.saveToString(false);
      assertFalse(exported.contains("DZ[G]"), "normal adopt should not emit DZ[G].");
      assertFalse(exported.contains("DZ[KB]"), "normal adopt should not emit DZ[KB].");
      assertFalse(exported.contains("DZ[KW]"), "normal adopt should not emit DZ[KW].");
    } finally {
      env.close();
    }
  }

  @Test
  void setHistoryIgnoresStaleScoreMeanWithoutKataPayloadOrDzTag() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList normalHistory = SGFParser.parseSgf("(;SZ[3];B[aa])", false);
      BoardData root = normalHistory.getStart().getData();
      root.scoreMean = 3.5;
      root.scoreMean2 = 0.0;
      root.isKataData = false;
      root.isKataData2 = false;
      root.bestMoves.clear();
      root.bestMoves2.clear();

      Lizzie.board.setHistory(normalHistory);

      assertFalse(
          Lizzie.board.isKataBoard,
          "stale scoreMean without kata payload should not enable kata board.");
      String exported = SGFParser.saveToString(false);
      assertFalse(exported.contains("DZ[G]"), "stale scoreMean adopt should not emit DZ[G].");
    } finally {
      env.close();
    }
  }

  @Test
  void detachedParseSgfLzopKeepsLiveBoardAnalysisStateUntouched() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardData liveRoot = Lizzie.board.getHistory().getStart().getData();
      liveRoot.engineName = "LiveEngine";
      liveRoot.setPlayouts(321);
      liveRoot.bestMoves = new ArrayList<>();
      MoveData liveMove = new MoveData();
      liveMove.coordinate = "K10";
      liveMove.playouts = 321;
      liveMove.winrate = 52.0;
      liveMove.variation = List.of("K10");
      liveRoot.bestMoves.add(liveMove);

      BoardHistoryList detached =
          SGFParser.parseSgf(
              "(;SZ[3]LZOP[MainEngine 44.0 120\nmove D4 visits 120 winrate 5600 prior 5000 pv D4 C4])",
              false);

      BoardData detachedRoot = detached.getStart().getData();
      assertEquals("MainEngine", detachedRoot.engineName);
      assertEquals(120, detachedRoot.getPlayouts());
      assertFalse(
          detachedRoot.bestMoves.isEmpty(), "detached parse should keep best-move payload.");

      assertEquals("LiveEngine", liveRoot.engineName);
      assertEquals(321, liveRoot.getPlayouts());
      assertEquals(1, liveRoot.bestMoves.size());
      assertEquals("K10", liveRoot.bestMoves.get(0).coordinate);
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfDetachedAnalysisShorthandKPlayoutsKeepLegacyScaleOnRoundTrip() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf =
          "(;SZ[3]LZOP[MainEngine 44.0 1.2k\n"
              + "move D4 visits 1.2k winrate 5600 prior 5000 pv D4 C4])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertEquals(1200, parsed.getStart().getData().getPlayouts());

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();
      assertEquals(1200, roundTripRoot.getPlayouts());
    } finally {
      env.close();
    }
  }

  @Test
  void parseSgfDetachedAnalysisShorthandMPlayoutsKeepLegacyScaleOnRoundTrip() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    ExtraMode previousMode = Lizzie.config.extraMode;
    try {
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      String sgf =
          "(;SZ[3]LZOP2[SubEngine 41.0 1.2m\n"
              + "move C3 visits 1.2m winrate 5900 prior 5000 pv C3 B3])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertEquals(1_200_000, parsed.getStart().getData().getPlayouts2());

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();
      assertEquals(1_200_000, roundTripRoot.getPlayouts2());
    } finally {
      Lizzie.config.extraMode = previousMode;
      env.close();
    }
  }

  @Test
  void saveRootSetupKeepsSetupOnlyOnRootAndStaysStableAcrossRoundTrip() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3]AB[aa]AW[cc]PL[W];B[ba])", false);
      Date fixedDate = fixedLocalDate(2020, Calendar.JANUARY, 2);
      history.getGameInfo().setPlayerBlack("Black");
      history.getGameInfo().setPlayerWhite("White");
      history.getGameInfo().setResult("");
      history.getGameInfo().setKomiNoMenu(6.5);
      history.getGameInfo().setDate(fixedDate);
      Lizzie.board.setHistory(history);

      String expected =
          "(;AB[aa]AW[cc]PL[W]SZ[3]KM[6.5]PW[White]PB[Black]DT[2020-01-02]AP[LizzieYzy Next: "
              + Lizzie.nextVersion
              + "]RE[]CA[UTF-8];B[ba])";
      String firstSave = SGFParser.saveToString(false);
      assertEquals(expected, firstSave, "root setup should be serialized only once on root.");
      String secondSave = SGFParser.saveToString(false);
      assertEquals(firstSave, secondSave, "repeated save should stay byte-stable.");

      BoardHistoryList roundTrip = SGFParser.parseSgf(firstSave, false);
      BoardHistoryNode firstNode = roundTrip.getStart().next().orElseThrow();
      assertTrue(firstNode.getData().isMoveNode(), "root setup must not create a setup child.");
      assertEquals(Stone.BLACK, roundTrip.getStart().getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.WHITE, roundTrip.getStart().getData().stones[Board.getIndex(2, 2)]);
    } finally {
      env.close();
    }
  }

  @Test
  void rootSetupWithoutPlKeepsDefaultBlackToPlayAcrossRoundTrip() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3]AB[aa];W[bb])", false);
      Date fixedDate = fixedLocalDate(2020, Calendar.JANUARY, 2);
      history.getGameInfo().setDate(fixedDate);
      Lizzie.board.setHistory(history);

      assertTrue(
          history.getStart().getData().blackToPlay,
          "root setup without PL should keep the fixed default side to play.");

      String exported = SGFParser.saveToString(false);
      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();

      assertTrue(
          exported.contains("PL[B]"), "root export should materialize default black-to-play.");
      assertEquals(
          "B", roundTripRoot.getProperty("PL"), "round-trip root should keep materialized PL.");
      assertTrue(
          roundTripRoot.blackToPlay, "round-trip root should keep the fixed default side to play.");
      assertEquals(Stone.BLACK, roundTripRoot.stones[Board.getIndex(0, 0)]);
    } finally {
      env.close();
    }
  }

  @Test
  void rootSnapshotExportUsesCurrentBoardAndDropsStaleSetupStones() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Stone[] rootStones = emptyStones();
      rootStones[Board.getIndex(0, 0)] = Stone.BLACK;
      rootStones[Board.getIndex(1, 0)] = Stone.BLACK;
      rootStones[Board.getIndex(1, 2)] = Stone.WHITE;
      rootStones[Board.getIndex(2, 2)] = Stone.WHITE;
      BoardData root =
          BoardData.snapshot(
              rootStones,
              Optional.empty(),
              Stone.EMPTY,
              false,
              zobrist(rootStones),
              0,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0);
      root.addProperty("AB", "aa");
      root.addProperty("AB", "cb");
      root.addProperty("AW", "cc");
      root.addProperty("AW", "ac");
      root.addProperty("AE", "ab");
      root.addProperty("LB", "ab:X");
      BoardHistoryList history = new BoardHistoryList(root);
      history.getGameInfo().setDate(fixedLocalDate(2020, Calendar.JANUARY, 3));
      Lizzie.board.setHistory(history);

      String firstSave = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(firstSave, false);
      BoardData roundTripRoot = roundTrip.getStart().getData();
      List<String> abValues = List.of(roundTripRoot.getProperty("AB").split(","));
      List<String> awValues = List.of(roundTripRoot.getProperty("AW").split(","));

      assertArrayEquals(rootStones, roundTripRoot.stones, "root export should keep full board.");
      assertEquals(2, abValues.size());
      assertEquals(2, new java.util.LinkedHashSet<>(abValues).size());
      assertEquals(2, awValues.size());
      assertEquals(2, new java.util.LinkedHashSet<>(awValues).size());
      assertTrue(abValues.contains("aa"), "root export should keep current black stones.");
      assertTrue(abValues.contains("ba"), "root export should materialize missing black stones.");
      assertFalse(abValues.contains("cb"), "stale AB stones should not be exported back.");
      assertTrue(awValues.contains("cc"), "root export should keep current white stones.");
      assertTrue(awValues.contains("bc"), "root export should materialize missing white stones.");
      assertFalse(awValues.contains("ac"), "stale AW stones should not be exported back.");
      assertEquals("ab:X", roundTripRoot.getProperty("LB"), "other root metadata should survive.");

      Lizzie.board.setHistory(roundTrip);
      String secondSave = SGFParser.saveToString(false);
      BoardData secondRoundTripRoot = SGFParser.parseSgf(secondSave, false).getStart().getData();
      assertArrayEquals(
          roundTripRoot.stones,
          secondRoundTripRoot.stones,
          "root board should stay round-trip stable.");
      assertEquals(roundTripRoot.getProperty("AB"), secondRoundTripRoot.getProperty("AB"));
      assertEquals(roundTripRoot.getProperty("AW"), secondRoundTripRoot.getProperty("AW"));
      assertEquals(roundTripRoot.getProperty("AE"), secondRoundTripRoot.getProperty("AE"));
      assertEquals(roundTripRoot.getProperty("LB"), secondRoundTripRoot.getProperty("LB"));
    } finally {
      env.close();
    }
  }

  @Test
  void rootPartialSnapshotExportMaterializesCurrentSideToPlayWhenPlMissing() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Stone[] rootStones = emptyStones();
      rootStones[Board.getIndex(0, 0)] = Stone.BLACK;
      rootStones[Board.getIndex(2, 2)] = Stone.WHITE;
      BoardData root =
          BoardData.snapshot(
              rootStones,
              Optional.empty(),
              Stone.EMPTY,
              false,
              zobrist(rootStones),
              0,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0);
      root.addProperty("AB", "aa");
      BoardHistoryList history = new BoardHistoryList(root);
      history.getGameInfo().setDate(fixedLocalDate(2020, Calendar.JANUARY, 4));
      Lizzie.board.setHistory(history);

      String exported = SGFParser.saveToString(false);
      BoardData roundTripRoot = SGFParser.parseSgf(exported, false).getStart().getData();

      assertTrue(
          exported.contains("PL[W]"), "partial root setup should materialize PL from root turn.");
      assertEquals(
          "W", roundTripRoot.getProperty("PL"), "round-trip root should keep materialized PL.");
      assertFalse(
          roundTripRoot.blackToPlay, "round-trip root should keep side-to-play from snapshot.");
      assertArrayEquals(
          rootStones, roundTripRoot.stones, "round-trip root should keep the current board.");
    } finally {
      env.close();
    }
  }

  @Test
  void realPassDoesNotReuseExistingDummyPassChild() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      Lizzie.board.setHistory(history);

      history.pass(Stone.BLACK, false, true);
      BoardHistoryNode dummyPass = history.getCurrentHistoryNode();

      history.previous();
      history.pass(Stone.BLACK, false, false);
      BoardHistoryNode realPass = history.getCurrentHistoryNode();

      assertNotSame(
          dummyPass, realPass, "real PASS should create a distinct child from dummy PASS.");
      assertTrue(dummyPass.getData().dummy, "fixture should keep the original dummy PASS child.");
      assertTrue(realPass.getData().isPassNode(), "replacement child should still be a PASS node.");
      assertFalse(realPass.getData().dummy, "real PASS should stay non-dummy.");
      assertSame(
          realPass,
          history.getStart().next(true).orElseThrow(),
          "the current child should be the real PASS node after replacement.");
    } finally {
      env.close();
    }
  }

  @Test
  void setupSnapshotExportsAsSetupNodeAndRoundTripsBoardState() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history =
          SGFParser.parseSgf("(;SZ[3];B[aa]C[setup]LB[bb:X]AB[bb]AW[cc]AE[aa];W[])", false);

      BoardHistoryNode moveNode = history.getStart().next().orElseThrow();
      BoardHistoryNode setupNode = moveNode.next().orElseThrow();
      String exportedSetup = generateNode(setupNode.getData(), setupNode);

      assertTrue(
          moveNode.getData().comment.isEmpty(), "leading setup comment should leave the move.");
      assertFalse(
          moveNode.getData().getProperties().containsKey("LB"),
          "leading setup markup should leave the move.");
      assertTrue(setupNode.getData().isSnapshotNode(), "fixture should create a setup snapshot.");
      assertTrue(
          exportedSetup.startsWith(";"), "setup snapshot export should stay in SGF node form.");
      assertTrue(exportedSetup.contains("AB[bb]"), "setup snapshot export should keep AB stones.");
      assertTrue(exportedSetup.contains("AW[cc]"), "setup snapshot export should keep AW stones.");
      assertTrue(exportedSetup.contains("AE[aa]"), "setup snapshot export should keep AE stones.");
      assertTrue(exportedSetup.contains("LB[bb:X]"), "setup snapshot export should keep markup.");
      assertTrue(exportedSetup.contains("C[setup]"), "setup snapshot export should keep comments.");
      assertFalse(exportedSetup.contains(";B["), "setup snapshot export should not forge a move.");
      assertFalse(exportedSetup.contains(";W["), "setup snapshot export should not forge a pass.");

      String roundTripSgf =
          "(;SZ[3]"
              + generateNode(history.getStart().next().orElseThrow())
              + exportedSetup
              + ";W[])";
      BoardHistoryList roundTrip = SGFParser.parseSgf(roundTripSgf, false);

      BoardHistoryNode roundTripSetup =
          roundTrip.getStart().next().orElseThrow().next().orElseThrow();
      BoardHistoryNode roundTripPass = roundTripSetup.next().orElseThrow();

      assertTrue(
          roundTripSetup.getData().isSnapshotNode(), "setup node should round-trip as SNAPSHOT.");
      assertEquals(
          Stone.EMPTY,
          roundTripSetup.getData().stones[Board.getIndex(0, 0)],
          "round-trip setup should preserve removed stones.");
      assertEquals(
          Stone.BLACK,
          roundTripSetup.getData().stones[Board.getIndex(1, 1)],
          "round-trip setup should preserve added black stones.");
      assertEquals(
          Stone.WHITE,
          roundTripSetup.getData().stones[Board.getIndex(2, 2)],
          "round-trip setup should preserve added white stones.");
      assertEquals("bb:X", roundTripSetup.getData().getProperty("LB"), "markup should round-trip.");
      assertEquals("setup", roundTripSetup.getData().comment, "comment should round-trip.");
      assertTrue(
          roundTripPass.getData().isPassNode(), "real pass after setup should stay a PASS node.");
    } finally {
      env.close();
    }
  }

  @Test
  void midgameSetupExportDedupsMaterializedAndExplicitSetupProperties() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history =
          SGFParser.parseSgf("(;SZ[3];B[bb]AB[aa]AW[cc]AE[bb]AB[aa]AW[cc]AE[bb];W[])", false);
      BoardHistoryNode moveNode = history.getStart().next().orElseThrow();
      BoardHistoryNode setupNode = moveNode.next().orElseThrow();

      String exportedSetup = generateNode(setupNode);
      assertEquals(
          ";PL[W]AB[aa]AE[bb]AW[cc]",
          exportedSetup,
          "setup export should deduplicate AB/AW/AE by semantic stone set.");

      BoardHistoryList roundTrip =
          SGFParser.parseSgf("(;SZ[3]" + generateNode(moveNode) + exportedSetup + ";W[])", false);
      BoardHistoryNode roundTripMove = roundTrip.getStart().next().orElseThrow();
      BoardHistoryNode roundTripSetup = roundTripMove.next().orElseThrow();
      BoardHistoryNode roundTripPass = roundTripSetup.next().orElseThrow();

      assertTrue(roundTripSetup.getData().isSnapshotNode(), "setup node should stay SNAPSHOT.");
      assertEquals(
          roundTripMove.getData().moveNumber,
          roundTripSetup.getData().moveNumber,
          "setup snapshot should keep the history boundary.");
      assertEquals(Stone.BLACK, roundTripSetup.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.EMPTY, roundTripSetup.getData().stones[Board.getIndex(1, 1)]);
      assertEquals(Stone.WHITE, roundTripSetup.getData().stones[Board.getIndex(2, 2)]);
      assertTrue(roundTripPass.getData().isPassNode(), "trailing explicit pass should stay PASS.");
    } finally {
      env.close();
    }
  }

  @Test
  void midgameSyncSnapshotWithoutPropertiesMaterializesSetupOnExportAndRoundTrips()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData firstMove = moveNode(0, 0, Stone.BLACK, false, 1);
      history.add(firstMove);

      Stone[] snapshotStones = firstMove.stones.clone();
      snapshotStones[Board.getIndex(1, 1)] = Stone.WHITE;
      BoardData snapshot =
          BoardData.snapshot(
              snapshotStones,
              Optional.empty(),
              Stone.EMPTY,
              true,
              zobrist(snapshotStones),
              1,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0);
      history.add(snapshot);

      Stone[] trailingMoveStones = snapshotStones.clone();
      trailingMoveStones[Board.getIndex(2, 2)] = Stone.BLACK;
      history.add(
          BoardData.move(
              trailingMoveStones,
              new int[] {2, 2},
              Stone.BLACK,
              false,
              zobrist(trailingMoveStones),
              2,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0));

      Lizzie.board.setHistory(history);
      String exported = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);

      BoardHistoryNode roundTripMove = roundTrip.getStart().next().orElseThrow();
      BoardHistoryNode roundTripSnapshot = roundTripMove.next().orElseThrow();
      BoardHistoryNode roundTripTrailingMove = roundTripSnapshot.next().orElseThrow();

      assertTrue(exported.contains("AW[bb]"), "snapshot export should materialize added stones.");
      assertTrue(exported.contains("PL[B]"), "snapshot export should materialize side-to-play.");
      assertTrue(
          roundTripSnapshot.getData().isSnapshotNode(),
          "materialized setup should round-trip as a SNAPSHOT anchor.");
      assertEquals(
          roundTripMove.getData().moveNumber,
          roundTripSnapshot.getData().moveNumber,
          "materialized setup should preserve the history boundary move number.");
      assertTrue(
          roundTripSnapshot.getData().blackToPlay,
          "materialized setup should round-trip the snapshot side-to-play.");
      assertEquals(
          Stone.BLACK,
          roundTripSnapshot.getData().stones[Board.getIndex(0, 0)],
          "materialized setup should preserve existing stones.");
      assertEquals(
          Stone.WHITE,
          roundTripSnapshot.getData().stones[Board.getIndex(1, 1)],
          "materialized setup should preserve synced static stones.");
      assertTrue(
          roundTripTrailingMove.getData().isMoveNode(),
          "the real move after the snapshot should stay a MOVE node.");
      assertEquals(
          2,
          roundTripTrailingMove.getData().moveNumber,
          "the real move after the snapshot should keep its move number.");
    } finally {
      env.close();
    }
  }

  @Test
  void midgameSnapshotExportUsesCurrentBoardAndDropsStaleSetupPropertyMap() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData firstMove = moveNode(0, 0, Stone.BLACK, false, 1);
      history.add(firstMove);

      Stone[] snapshotStones = firstMove.stones.clone();
      snapshotStones[Board.getIndex(0, 0)] = Stone.EMPTY;
      snapshotStones[Board.getIndex(1, 1)] = Stone.WHITE;
      BoardData snapshot =
          BoardData.snapshot(
              snapshotStones,
              Optional.empty(),
              Stone.EMPTY,
              true,
              zobrist(snapshotStones),
              1,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0);
      snapshot.addProperty("AB", "aa");
      snapshot.addProperty("AB", "cc");
      snapshot.addProperty("AW", "aa");
      snapshot.addProperty("AE", "bb");
      snapshot.addProperty("LB", "bb:S");
      history.add(snapshot);

      Stone[] trailingMoveStones = snapshotStones.clone();
      trailingMoveStones[Board.getIndex(2, 0)] = Stone.BLACK;
      history.add(
          BoardData.move(
              trailingMoveStones,
              new int[] {2, 0},
              Stone.BLACK,
              false,
              zobrist(trailingMoveStones),
              2,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0));

      BoardHistoryNode snapshotNode = history.getStart().next().orElseThrow().next().orElseThrow();
      String exportedSetup = generateNode(snapshotNode);
      assertTrue(exportedSetup.contains("PL[B]"), "export should materialize snapshot turn.");
      assertTrue(exportedSetup.contains("AE[aa]"), "export should keep real removed stones.");
      assertTrue(exportedSetup.contains("AW[bb]"), "export should keep real added white stones.");
      assertTrue(exportedSetup.contains("LB[bb:S]"), "export should keep non-setup metadata.");
      assertFalse(exportedSetup.contains("AB[aa]"), "stale AB should not be re-exported.");
      assertFalse(exportedSetup.contains("AB[cc]"), "stale partial AB should not be re-exported.");
      assertFalse(exportedSetup.contains("AW[aa]"), "stale AW should not be re-exported.");
      assertFalse(exportedSetup.contains("AE[bb]"), "stale AE should not be re-exported.");

      Lizzie.board.setHistory(history);
      String exported = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      BoardHistoryNode roundTripMove = roundTrip.getStart().next().orElseThrow();
      BoardHistoryNode roundTripSnapshot = roundTripMove.next().orElseThrow();
      BoardHistoryNode roundTripTrailingMove = roundTripSnapshot.next().orElseThrow();

      assertTrue(roundTripSnapshot.getData().isSnapshotNode(), "snapshot boundary should remain.");
      assertEquals(
          roundTripMove.getData().moveNumber,
          roundTripSnapshot.getData().moveNumber,
          "snapshot boundary move number should stay stable.");
      assertArrayEquals(
          snapshotStones,
          roundTripSnapshot.getData().stones,
          "round-trip snapshot board should stay on current materialized board.");
      assertTrue(
          roundTripSnapshot.getData().blackToPlay,
          "round-trip snapshot side-to-play should stay on snapshot state.");
      assertEquals("bb:S", roundTripSnapshot.getData().getProperty("LB"));
      assertTrue(
          roundTripTrailingMove.getData().isMoveNode(),
          "real move after snapshot should remain MOVE.");
      assertEquals(2, roundTripTrailingMove.getData().moveNumber);
      assertEquals(Stone.BLACK, roundTripTrailingMove.getData().stones[Board.getIndex(2, 0)]);
    } finally {
      env.close();
    }
  }

  @Test
  void midgameSetupSnapshotPlSetsSnapshotSideToPlayAndLeavesMoveClean() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3];B[aa]PL[B]AW[bb];B[cc])", false);

      BoardHistoryNode moveNode = history.getStart().next().orElseThrow();
      BoardHistoryNode setupNode = moveNode.next().orElseThrow();
      BoardHistoryNode trailingMove = setupNode.next().orElseThrow();
      String exportedSetup = generateNode(setupNode);

      assertTrue(moveNode.getData().isMoveNode(), "leading node should stay a real MOVE.");
      assertFalse(
          moveNode.getData().getProperties().containsKey("PL"),
          "explicit PL should leave the split move node.");
      assertTrue(setupNode.getData().isSnapshotNode(), "setup node should stay a SNAPSHOT.");
      assertEquals("B", setupNode.getData().getProperty("PL"), "setup snapshot should keep PL.");
      assertTrue(
          setupNode.getData().blackToPlay,
          "explicit PL should directly set snapshot side-to-play.");
      assertEquals(
          Stone.WHITE,
          setupNode.getData().stones[Board.getIndex(1, 1)],
          "setup snapshot should still keep setup stones.");
      assertTrue(exportedSetup.contains("PL[B]"), "exported setup snapshot should keep PL.");
      assertTrue(
          trailingMove.getData().isMoveNode(), "the following real move should stay a MOVE node.");
      assertEquals(
          2, trailingMove.getData().moveNumber, "the following move should keep its move number.");
    } finally {
      env.close();
    }
  }

  @Test
  void midgameSetupSnapshotWithExplicitWhitePlStaysStableAfterRoundTrip() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[3];B[aa]PL[W]AW[bb];B[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertMidgameWhitePlSnapshotState(parsed, "parse result");

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      BoardHistoryList roundTrip = SGFParser.parseSgf(exported, false);
      assertMidgameWhitePlSnapshotState(roundTrip, "round-trip result");
    } finally {
      env.close();
    }
  }

  @Test
  void midgameSetupSnapshotWithExplicitWhitePlStaysStableAfterSaveLoadRoundTrip() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      String sgf = "(;SZ[3];B[aa]PL[W]AW[bb];B[cc])";

      BoardHistoryList parsed = SGFParser.parseSgf(sgf, false);
      assertMidgameWhitePlSnapshotState(parsed, "parse result");

      Lizzie.board.setHistory(parsed);
      String exported = SGFParser.saveToString(false);
      assertTrue(SGFParser.loadFromString(exported), "loadFromString should load round-trip SGF.");
      assertMidgameWhitePlSnapshotState(Lizzie.board.getHistory(), "save/load round-trip result");
    } finally {
      env.close();
    }
  }

  @Test
  void snapshotTurnStateStaysStableWhenPlIsAttachedAfterFirstRealChild() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryNode root = new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode firstMove = root.addOrGoto(moveNode(0, 0, Stone.BLACK, false, 1), false);

      Stone[] snapshotStones = firstMove.getData().stones.clone();
      snapshotStones[Board.getIndex(1, 1)] = Stone.WHITE;
      BoardData setupSnapshot =
          BoardData.snapshot(
              snapshotStones,
              Optional.empty(),
              Stone.EMPTY,
              false,
              zobrist(snapshotStones),
              1,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0);
      BoardHistoryNode setupNode = firstMove.addOrGoto(setupSnapshot, false);

      Stone[] trailingMoveStones = snapshotStones.clone();
      trailingMoveStones[Board.getIndex(2, 2)] = Stone.BLACK;
      BoardHistoryNode trailingMove =
          setupNode.addOrGoto(
              BoardData.move(
                  trailingMoveStones,
                  new int[] {2, 2},
                  Stone.BLACK,
                  false,
                  zobrist(trailingMoveStones),
                  2,
                  new int[BOARD_AREA],
                  0,
                  0,
                  50,
                  0),
              false);

      setupNode.getData().addProperty("PL", "W");

      assertEquals("W", setupNode.getData().getProperty("PL"), "setup snapshot should keep PL=W.");
      assertFalse(
          setupNode.getData().blackToPlay,
          "explicit PL semantics should keep snapshot side-to-play stable as white.");
      assertTrue(trailingMove.getData().isMoveNode(), "trailing child should remain a real move.");
      assertEquals(2, trailingMove.getData().moveNumber, "trailing move should keep move number.");
    } finally {
      env.close();
    }
  }

  @Test
  void explicitPlTurnStateDoesNotGetOverwrittenByFirstRealChild() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryNode root = new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode parent = root.addOrGoto(moveNode(0, 0, Stone.BLACK, false, 1), false);
      parent.getData().addProperty("PL", "W");
      parent.getData().blackToPlay = false;

      parent.addOrGoto(moveNode(1, 1, Stone.BLACK, false, 2), false);

      assertEquals("W", parent.getData().getProperty("PL"), "parent should keep explicit PL=W.");
      assertFalse(
          parent.getData().blackToPlay,
          "first real child should not overwrite explicit PL turn state.");
    } finally {
      env.close();
    }
  }

  @Test
  void firstSetupSnapshotChildDoesNotRewriteParentBlackToPlay() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3];W[aa]AB[bb])", false);

      BoardHistoryNode whiteMoveNode = history.getStart().next().orElseThrow();
      BoardHistoryNode setupSnapshotNode = whiteMoveNode.next().orElseThrow();

      assertTrue(whiteMoveNode.getData().isMoveNode(), "W[aa] should stay a real move node.");
      assertTrue(
          setupSnapshotNode.getData().isSnapshotNode(),
          "setup split should still create a snapshot child.");
      assertTrue(
          whiteMoveNode.getData().blackToPlay,
          "first snapshot child should not rewrite parent side-to-play.");
      assertTrue(
          setupSnapshotNode.getData().blackToPlay,
          "setup snapshot should keep the move node side-to-play.");
    } finally {
      env.close();
    }
  }

  @Test
  void moveMnMetadataRollsBackOnMoveWhenSplitIntoSetupSnapshot() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3];B[aa]MN[7]AB[bb])", false);

      BoardHistoryNode moveNode = history.getStart().next().orElseThrow();
      BoardHistoryNode setupNode = moveNode.next().orElseThrow();
      int moveIndex = Board.getIndex(0, 0);

      assertFalse(
          moveNode.getData().getProperties().containsKey("MN"),
          "split move node should no longer keep MN after setup extraction.");
      assertEquals("7", setupNode.getData().getProperty("MN"), "setup snapshot should keep MN.");
      assertEquals(
          -1, moveNode.getData().moveMNNumber, "split move node should roll back its MN marker.");
      assertEquals(
          1,
          moveNode.getData().moveNumberList[moveIndex],
          "split move node should roll back moveNumberList to the real move number.");
      assertEquals(
          7,
          setupNode.getData().moveNumberList[moveIndex],
          "setup snapshot should keep the moved MN numbering.");
    } finally {
      env.close();
    }
  }

  @Test
  void saveWithAppendWinrateKeepsSnapshotSourceCommentOwnership() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    boolean previousAppendWinrateToComment = Lizzie.config.appendWinrateToComment;
    ResourceBundle previousResourceBundle = Lizzie.resourceBundle;
    try {
      Lizzie.config.appendWinrateToComment = true;
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);

      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData firstMove = moveNode(0, 0, Stone.BLACK, false, 1);
      history.add(firstMove);

      Stone[] snapshotStones = firstMove.stones.clone();
      snapshotStones[Board.getIndex(1, 1)] = Stone.WHITE;
      BoardData snapshot =
          BoardData.snapshot(
              snapshotStones,
              Optional.empty(),
              Stone.EMPTY,
              firstMove.blackToPlay,
              zobrist(snapshotStones),
              firstMove.moveNumber,
              firstMove.moveNumberList.clone(),
              0,
              0,
              63.5,
              16);
      snapshot.comment = "source-snapshot-comment";
      history.add(snapshot);
      Lizzie.board.setHistory(history);

      BoardHistoryNode snapshotNode = history.getCurrentHistoryNode();
      String sourceComment = snapshotNode.getData().comment;
      String exported = SGFParser.saveToString(false);

      assertEquals(
          sourceComment,
          snapshotNode.getData().comment,
          "save path should keep in-memory snapshot source comment unchanged.");
      assertTrue(
          exported.contains("source-snapshot-comment"),
          "exported SGF should preserve source snapshot comment semantics.");
    } finally {
      Lizzie.config.appendWinrateToComment = previousAppendWinrateToComment;
      Lizzie.resourceBundle = previousResourceBundle;
      env.close();
    }
  }

  @Test
  void engineSgfSetupSnapshotKeepsItsOwnComment() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    boolean previousSavingEngineSgf = EngineManager.isSaveingEngineSGF;
    try {
      BoardHistoryList history =
          SGFParser.parseSgf("(;SZ[3];W[cc];B[aa]C[m1]AB[bb]C[setup])", false);

      BoardHistoryNode moveNode = history.getStart().next().orElseThrow().next().orElseThrow();
      BoardHistoryNode setupNode = moveNode.next().orElseThrow();
      EngineManager.isSaveingEngineSGF = true;
      String exportedSetup = generateNode(setupNode);

      assertTrue(setupNode.getData().isSnapshotNode(), "fixture should create a setup snapshot.");
      assertTrue(
          moveNode.getData().comment.isEmpty(),
          "setup extraction should leave the real move comment behind.");
      assertEquals("setup", setupNode.getData().comment, "setup snapshot should keep its comment.");
      assertTrue(
          exportedSetup.contains("C[setup]"),
          "engine SGF export should keep the setup snapshot comment.");
    } finally {
      EngineManager.isSaveingEngineSGF = previousSavingEngineSgf;
      env.close();
    }
  }

  @Test
  void engineSaveMaterializedSnapshotKeepsOwnCommentAndMarkup() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    boolean previousSavingEngineSgf = EngineManager.isSaveingEngineSGF;
    try {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData firstMove = moveNode(0, 0, Stone.BLACK, false, 1);
      firstMove.comment = "m1";
      history.add(firstMove);

      Stone[] snapshotStones = firstMove.stones.clone();
      snapshotStones[Board.getIndex(1, 1)] = Stone.WHITE;
      BoardData snapshot =
          BoardData.snapshot(
              snapshotStones,
              Optional.empty(),
              Stone.EMPTY,
              true,
              zobrist(snapshotStones),
              1,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0);
      snapshot.comment = "snap-c";
      snapshot.addProperty("LB", "bb:S");
      history.add(snapshot);

      BoardHistoryNode snapshotNode = history.getStart().next().orElseThrow().next().orElseThrow();
      EngineManager.isSaveingEngineSGF = true;
      String exportedSnapshot = generateNode(snapshotNode);

      assertTrue(
          exportedSnapshot.contains("C[snap-c]"),
          "materialized snapshot should keep its own comment on engine-save path.");
      assertFalse(
          exportedSnapshot.contains("C[m1]"),
          "materialized snapshot should not take previous move comment.");
      assertTrue(
          exportedSnapshot.contains("LB[bb:S]"),
          "materialized snapshot should keep its own markup on engine-save path.");
    } finally {
      EngineManager.isSaveingEngineSGF = previousSavingEngineSgf;
      env.close();
    }
  }

  @Test
  void aeMidgameSetupMarksRemovedStoneAndRebuildsWhenSteppingForward() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardHistoryList history = SGFParser.parseSgf("(;SZ[3];B[aa];W[ba]AE[aa]AB[cc])", false);
      while (history.previous().isPresent()) {}
      Lizzie.board.setHistory(history);

      TrackingLeelaz leelaz = (TrackingLeelaz) Lizzie.leelaz;

      assertTrue(Lizzie.board.nextMove(false), "first real move should still replay normally.");
      assertTrue(Lizzie.board.nextMove(false), "second real move should still replay normally.");
      assertTrue(
          Lizzie.board.nextMove(false), "setup snapshot should stay reachable by navigation.");

      BoardHistoryNode setupNode = Lizzie.board.getHistory().getCurrentHistoryNode();
      assertTrue(
          setupNode.getData().isSnapshotNode(), "fixture should land on the setup snapshot.");
      assertTrue(
          setupNode.hasRemovedStone(), "AE setup should mark the snapshot as removed-stone.");
      assertTrue(
          setupNode.getData().blackToPlay,
          "setup snapshot should keep the original side to play after rebuild.");
      assertEquals("clear", leelaz.commandsSinceLastClear().get(0));
      assertTrue(
          leelaz.commandsSinceLastClear().get(1).startsWith("loadsgf "),
          "stepping onto the setup snapshot should restore through exact loadsgf.");
      assertArrayEquals(
          setupNode.getData().stones,
          leelaz.copyStones(),
          "stepping onto the setup snapshot should rebuild the exact static board.");
      assertEquals(
          setupNode.getData().blackToPlay,
          leelaz.isBlackToPlay(),
          "stepping onto the setup snapshot should restore turn ownership through the snapshot.");
      assertTempFileEventuallyDeleted(
          leelaz.lastLoadedSgf(),
          "setup snapshot navigation should clean up the temporary SGF file after exact restore.");
    } finally {
      env.close();
    }
  }

  @Test
  void moveToAnyPositionRestoresSnapshotBranchAnchorBeforeLaterRealMove() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingBoard board = (TrackingBoard) Lizzie.board;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode root = history.getCurrentHistoryNode();
      root.addOrGoto(moveNode(2, 2, Stone.BLACK, false, 1), false);

      Stone[] snapshotStones = emptyStones();
      snapshotStones[Board.getIndex(0, 0)] = Stone.BLACK;
      BoardData setupSnapshotData =
          BoardData.snapshot(
              snapshotStones,
              Optional.empty(),
              Stone.EMPTY,
              false,
              zobrist(snapshotStones),
              1,
              new int[BOARD_AREA],
              0,
              0,
              50,
              0);
      setupSnapshotData.addProperty("AB", "aa");
      setupSnapshotData.addProperty("PL", "W");
      BoardHistoryNode setupSnapshot = root.addOrGoto(setupSnapshotData, false);

      Stone[] finalStones = snapshotStones.clone();
      finalStones[Board.getIndex(1, 0)] = Stone.WHITE;
      BoardHistoryNode targetMove =
          setupSnapshot.addOrGoto(
              BoardData.move(
                  finalStones,
                  new int[] {1, 0},
                  Stone.WHITE,
                  true,
                  zobrist(finalStones),
                  2,
                  new int[BOARD_AREA],
                  0,
                  0,
                  50,
                  0),
              false);
      board.setHistory(history);

      TrackingLeelaz leelaz = (TrackingLeelaz) Lizzie.leelaz;
      board.moveToAnyPosition(targetMove);

      assertEquals(
          "clear",
          leelaz.recordedCommands().get(0),
          "branch jumps through setup snapshots should clear the engine before exact restore.");
      assertTrue(
          leelaz.recordedCommands().get(1).startsWith("loadsgf "),
          "branch jumps through setup snapshots should restore the snapshot anchor exactly.");
      assertEquals(
          "play W B3",
          leelaz.recordedCommands().get(2),
          "after restoring the snapshot anchor, branch jumps should replay the later real move.");
      assertArrayEquals(
          targetMove.getData().stones,
          leelaz.copyStones(),
          "branch jumps should reproduce the target board after restoring the snapshot anchor.");
    } finally {
      env.close();
    }
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

  private static BoardData passNode(Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    return BoardData.pass(
        emptyStones(),
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

  private static String generateNode(BoardData data)
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    return generateNode(data, new BoardHistoryNode(data));
  }

  private static String generateNode(BoardHistoryNode node)
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    return generateNode(node.getData(), node);
  }

  private static String generateNode(BoardData data, BoardHistoryNode node)
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    Method method =
        SGFParser.class.getDeclaredMethod(
            "generateNode",
            Board.class,
            BoardHistoryNode.class,
            boolean.class,
            StringBuilder.class);
    method.setAccessible(true);
    StringBuilder builder = new StringBuilder();
    Object result = method.invoke(null, null, node, false, builder);
    return result.toString();
  }

  private static void assertStandalonePreMoveSetupNode(BoardHistoryList history, String source) {
    BoardHistoryNode root = history.getStart();
    BoardHistoryNode setupNode = root.next().orElseThrow();
    BoardHistoryNode firstMove = setupNode.next().orElseThrow();

    assertTrue(
        setupNode.getData().isSnapshotNode(),
        source + " should keep pre-first-move setup as SNAPSHOT.");
    assertEquals(
        0,
        setupNode.getData().moveNumber,
        source + " should keep standalone setup moveNumber at 0.");
    assertEquals("setup-head", setupNode.getData().comment, source + " should keep setup comment.");
    assertEquals("aa:S", setupNode.getData().getProperty("LB"), source + " should keep setup LB.");
    assertEquals("4", setupNode.getData().getProperty("MN"), source + " should keep setup MN.");
    assertTrue(root.getData().comment.isEmpty(), source + " should keep root comment untouched.");
    assertFalse(
        root.getData().getProperties().containsKey("LB"),
        source + " should not move setup LB onto root.");
    assertFalse(
        root.getData().getProperties().containsKey("MN"),
        source + " should not move setup MN onto root.");
    assertTrue(firstMove.getData().isMoveNode(), source + " should keep trailing action as MOVE.");
    assertEquals(
        1, firstMove.getData().moveNumber, source + " should keep trailing move number at 1.");
  }

  private static void assertSetupBoundaryMatchesParsed(
      BoardHistoryList expected, BoardHistoryList actual, String source) {
    BoardHistoryNode expectedSetup = expected.getStart().next().orElseThrow();
    BoardHistoryNode actualSetup = actual.getStart().next().orElseThrow();
    BoardHistoryNode expectedMove = expectedSetup.next().orElseThrow();
    BoardHistoryNode actualMove = actualSetup.next().orElseThrow();

    assertEquals(
        expectedSetup.getData().comment,
        actualSetup.getData().comment,
        source + " should keep setup comment consistent with parseSgf.");
    assertEquals(
        expectedSetup.getData().getProperty("LB"),
        actualSetup.getData().getProperty("LB"),
        source + " should keep setup markup consistent with parseSgf.");
    assertEquals(
        expectedSetup.getData().getProperty("MN"),
        actualSetup.getData().getProperty("MN"),
        source + " should keep setup MN consistent with parseSgf.");
    assertEquals(
        expectedSetup.getData().moveNumber,
        actualSetup.getData().moveNumber,
        source + " should keep setup boundary move number consistent with parseSgf.");
    assertEquals(
        expectedMove.getData().moveNumber,
        actualMove.getData().moveNumber,
        source + " should keep trailing move number consistent with parseSgf.");
  }

  private static void assertMidgameWhitePlSnapshotState(BoardHistoryList history, String source) {
    BoardHistoryNode firstMove = history.getStart().next().orElseThrow();
    BoardHistoryNode snapshot = firstMove.next().orElseThrow();
    BoardHistoryNode trailingMove = snapshot.next().orElseThrow();

    assertTrue(snapshot.getData().isSnapshotNode(), source + " should keep setup boundary.");
    assertEquals(
        firstMove.getData().moveNumber,
        snapshot.getData().moveNumber,
        source + " should keep setup boundary move number.");
    assertEquals("W", snapshot.getData().getProperty("PL"), source + " should keep PL=W.");
    assertFalse(
        snapshot.getData().blackToPlay, source + " should keep snapshot side-to-play as white.");
    assertTrue(trailingMove.getData().isMoveNode(), source + " should keep trailing move as MOVE.");
    assertEquals(2, trailingMove.getData().moveNumber, source + " should keep move number.");
  }

  private static void assertPrimaryAnalysisOwnershipOnSetupSnapshot(
      BoardHistoryList history, String source) {
    BoardHistoryNode moveNode = history.getStart().next().orElseThrow();
    BoardHistoryNode setupNode = moveNode.next().orElseThrow();
    BoardData moveData = moveNode.getData();
    BoardData setupData = setupNode.getData();

    assertTrue(moveData.isMoveNode(), source + " should keep the leading node as MOVE.");
    assertTrue(setupData.isSnapshotNode(), source + " should keep setup split as SNAPSHOT.");
    assertEquals("", moveData.engineName, source + " should clear primary engineName on MOVE.");
    assertEquals(0, moveData.getPlayouts(), source + " should clear primary playouts on MOVE.");
    assertTrue(moveData.bestMoves.isEmpty(), source + " should clear primary best-moves on MOVE.");
    assertFalse(moveData.isKataData, source + " should clear primary kata flag on MOVE.");
    assertTrue(
        moveData.estimateArray == null || moveData.estimateArray.isEmpty(),
        source + " should clear primary ownership array on MOVE.");

    assertEquals("Main", setupData.engineName, source + " should keep primary engineName.");
    assertEquals(120, setupData.getPlayouts(), source + " should keep primary playouts.");
    assertFalse(setupData.bestMoves.isEmpty(), source + " should keep primary best-moves.");
    assertEquals("C3", setupData.bestMoves.get(0).coordinate);
    assertTrue(setupData.isKataData, source + " should keep primary kata metadata.");
    assertEquals(3.3, setupData.scoreMean, 0.0001);
    assertEquals(0.4, setupData.scoreStdev, 0.0001);
    assertTrue(
        setupData.estimateArray != null && setupData.estimateArray.size() == 2,
        source + " should keep primary ownership array.");
  }

  private static void assertSecondaryAnalysisOwnershipOnSetupSnapshot(
      BoardHistoryList history, String source) {
    BoardHistoryNode moveNode = history.getStart().next().orElseThrow();
    BoardHistoryNode setupNode = moveNode.next().orElseThrow();
    BoardData moveData = moveNode.getData();
    BoardData setupData = setupNode.getData();

    assertTrue(moveData.isMoveNode(), source + " should keep the leading node as MOVE.");
    assertTrue(setupData.isSnapshotNode(), source + " should keep setup split as SNAPSHOT.");
    assertEquals("", moveData.engineName2, source + " should clear secondary engineName on MOVE.");
    assertEquals(0, moveData.getPlayouts2(), source + " should clear secondary playouts on MOVE.");
    assertTrue(
        moveData.bestMoves2.isEmpty(), source + " should clear secondary best-moves on MOVE.");
    assertFalse(moveData.isKataData2, source + " should clear secondary kata flag on MOVE.");
    assertTrue(
        moveData.estimateArray2 == null || moveData.estimateArray2.isEmpty(),
        source + " should clear secondary ownership array on MOVE.");

    assertEquals("SubEngine", setupData.engineName2, source + " should keep secondary engineName.");
    assertEquals(150, setupData.getPlayouts2(), source + " should keep secondary playouts.");
    assertFalse(setupData.bestMoves2.isEmpty(), source + " should keep secondary best-moves.");
    assertEquals("C3", setupData.bestMoves2.get(0).coordinate);
    assertTrue(setupData.isKataData2, source + " should keep secondary kata metadata.");
    assertEquals(2.1, setupData.scoreMean2, 0.0001);
    assertEquals(0.5, setupData.scoreStdev2, 0.0001);
    assertTrue(
        setupData.estimateArray2 != null && setupData.estimateArray2.size() == 2,
        source + " should keep secondary ownership array.");
  }

  private static void assertPrimaryAnalysisOwnershipMatchesParsed(
      BoardHistoryList expected, BoardHistoryList actual, String source) {
    BoardData expectedMove = expected.getStart().next().orElseThrow().getData();
    BoardData expectedSetup =
        expected.getStart().next().orElseThrow().next().orElseThrow().getData();
    BoardData actualMove = actual.getStart().next().orElseThrow().getData();
    BoardData actualSetup = actual.getStart().next().orElseThrow().next().orElseThrow().getData();

    assertEquals(
        expectedMove.getPlayouts(),
        actualMove.getPlayouts(),
        source + " should match move playouts.");
    assertEquals(
        expectedMove.engineName, actualMove.engineName, source + " should match move engineName.");
    assertEquals(
        expectedSetup.getPlayouts(),
        actualSetup.getPlayouts(),
        source + " should match setup playouts.");
    assertEquals(
        expectedSetup.engineName,
        actualSetup.engineName,
        source + " should match setup engineName.");
    assertEquals(
        expectedSetup.bestMoves.get(0).coordinate,
        actualSetup.bestMoves.get(0).coordinate,
        source + " should match setup best-move ownership.");
  }

  private static void assertSecondaryAnalysisOwnershipMatchesParsed(
      BoardHistoryList expected, BoardHistoryList actual, String source) {
    BoardData expectedMove = expected.getStart().next().orElseThrow().getData();
    BoardData expectedSetup =
        expected.getStart().next().orElseThrow().next().orElseThrow().getData();
    BoardData actualMove = actual.getStart().next().orElseThrow().getData();
    BoardData actualSetup = actual.getStart().next().orElseThrow().next().orElseThrow().getData();

    assertEquals(
        expectedMove.getPlayouts2(),
        actualMove.getPlayouts2(),
        source + " should match secondary move playouts.");
    assertEquals(
        expectedMove.engineName2,
        actualMove.engineName2,
        source + " should match secondary move engineName.");
    assertEquals(
        expectedSetup.getPlayouts2(),
        actualSetup.getPlayouts2(),
        source + " should match secondary setup playouts.");
    assertEquals(
        expectedSetup.engineName2,
        actualSetup.engineName2,
        source + " should match secondary setup engineName.");
    assertEquals(
        expectedSetup.bestMoves2.get(0).coordinate,
        actualSetup.bestMoves2.get(0).coordinate,
        source + " should match secondary setup best-move ownership.");
  }

  private static void assertNeutralPrimaryAnalysis(BoardData data, String source) {
    assertEquals("", data.engineName, source + " should keep primary engineName neutral.");
    assertEquals(0, data.getPlayouts(), source + " should keep primary playouts neutral.");
    assertEquals(50.0, data.winrate, 0.0001, source + " should keep primary winrate neutral.");
    assertTrue(data.bestMoves.isEmpty(), source + " should keep primary best-moves neutral.");
    assertFalse(data.isKataData, source + " should keep primary kata flag neutral.");
    assertEquals(0.0, data.scoreMean, 0.0001, source + " should keep primary scoreMean neutral.");
    assertEquals(0.0, data.scoreStdev, 0.0001, source + " should keep primary scoreStdev neutral.");
    assertEquals(0.0, data.pda, 0.0001, source + " should keep primary pda neutral.");
    assertTrue(
        data.estimateArray == null || data.estimateArray.isEmpty(),
        source + " should keep primary ownership array neutral.");
  }

  private static void assertNeutralSecondaryAnalysis(BoardData data, String source) {
    assertEquals("", data.engineName2, source + " should keep secondary engineName neutral.");
    assertEquals(0, data.getPlayouts2(), source + " should keep secondary playouts neutral.");
    assertEquals(0.0, data.winrate2, 0.0001, source + " should keep secondary winrate neutral.");
    assertTrue(data.bestMoves2.isEmpty(), source + " should keep secondary best-moves neutral.");
    assertFalse(data.isKataData2, source + " should keep secondary kata flag neutral.");
    assertEquals(
        0.0, data.scoreMean2, 0.0001, source + " should keep secondary scoreMean neutral.");
    assertEquals(
        0.0, data.scoreStdev2, 0.0001, source + " should keep secondary scoreStdev neutral.");
    assertEquals(0.0, data.pda2, 0.0001, source + " should keep secondary pda2 neutral.");
    assertTrue(
        data.estimateArray2 == null || data.estimateArray2.isEmpty(),
        source + " should keep secondary ownership array neutral.");
  }

  private static void assertPrimaryHeaderOnlyPayload(
      BoardData data,
      String source,
      String engineName,
      int playouts,
      double scoreMean,
      double scoreStdev,
      double pda,
      boolean kata,
      boolean assertWinrate) {
    assertEquals(engineName, data.engineName, source + " should keep primary engineName.");
    assertEquals(playouts, data.getPlayouts(), source + " should keep primary playouts.");
    assertEquals(kata, data.isKataData, source + " should keep primary kata flag.");
    assertEquals(scoreMean, data.scoreMean, 0.0001, source + " should keep primary scoreMean.");
    assertEquals(scoreStdev, data.scoreStdev, 0.0001, source + " should keep primary scoreStdev.");
    assertEquals(pda, data.pda, 0.0001, source + " should keep primary pda.");
    if (assertWinrate) {
      assertEquals(56.0, data.winrate, 0.0001, source + " should keep primary winrate.");
    }
  }

  private static void assertSecondaryHeaderOnlyPayload(
      BoardData data,
      String source,
      String engineName,
      int playouts,
      double scoreMean,
      double scoreStdev,
      double pda,
      boolean kata,
      boolean assertWinrate) {
    assertEquals(engineName, data.engineName2, source + " should keep secondary engineName.");
    assertEquals(playouts, data.getPlayouts2(), source + " should keep secondary playouts.");
    assertEquals(kata, data.isKataData2, source + " should keep secondary kata flag.");
    assertEquals(scoreMean, data.scoreMean2, 0.0001, source + " should keep secondary scoreMean.");
    assertEquals(
        scoreStdev, data.scoreStdev2, 0.0001, source + " should keep secondary scoreStdev.");
    assertEquals(pda, data.pda2, 0.0001, source + " should keep secondary pda2.");
    if (assertWinrate) {
      assertEquals(59.0, data.winrate2, 0.0001, source + " should keep secondary winrate.");
    }
  }

  private static int countOccurrences(String text, String token) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(token, index)) >= 0) {
      count += 1;
      index += token.length();
    }
    return count;
  }

  private static String extractSingleAnalysisPayload(String sgf, String tag) {
    String token = tag + "[";
    int startToken = sgf.indexOf(token);
    assertTrue(startToken >= 0, "export should contain " + token);
    int start = startToken + token.length();
    int end = sgf.indexOf("]", start);
    assertTrue(end > start, "export should contain closing bracket for " + tag);
    return sgf.substring(start, end);
  }

  private static void assertAllAnalysisFieldsCleared(BoardData data, String source) {
    assertEquals("", data.engineName, source + " should clear primary engineName.");
    assertEquals("", data.engineName2, source + " should clear secondary engineName.");
    assertEquals(0, data.getPlayouts(), source + " should clear primary playouts.");
    assertEquals(0, data.getPlayouts2(), source + " should clear secondary playouts.");
    assertEquals(0.0, data.scoreMean, 0.0001, source + " should clear primary scoreMean.");
    assertEquals(0.0, data.scoreMean2, 0.0001, source + " should clear secondary scoreMean.");
    assertEquals(0.0, data.scoreStdev, 0.0001, source + " should clear primary scoreStdev.");
    assertEquals(0.0, data.scoreStdev2, 0.0001, source + " should clear secondary scoreStdev.");
    assertEquals(0.0, data.pda, 0.0001, source + " should clear primary pda.");
    assertEquals(0.0, data.pda2, 0.0001, source + " should clear secondary pda2.");
    assertEquals(0, data.analysisHeaderSlots, source + " should clear primary header slots.");
    assertEquals(0, data.analysisHeaderSlots2, source + " should clear secondary header slots.");
  }

  private static String moveRecord(Stone color, int x, int y) {
    return color.name() + ":" + Board.convertCoordinatesToName(x, y);
  }

  private static int boardIndex(int x, int y, int boardHeight) {
    return x * boardHeight + y;
  }

  private static Date fixedLocalDate(int year, int month, int dayOfMonth) {
    GregorianCalendar calendar = new GregorianCalendar();
    calendar.clear();
    calendar.set(year, month, dayOfMonth, 12, 0, 0);
    return calendar.getTime();
  }

  private static void assertTempFileEventuallyDeleted(Path path, String message)
      throws InterruptedException {
    for (int attempt = 0; attempt < 40; attempt++) {
      if (!Files.exists(path)) {
        return;
      }
      Thread.sleep(50L);
    }
    assertFalse(Files.exists(path), message);
  }

  private static Object newSgfMainLineNormalizer(String sgf) throws ReflectiveOperationException {
    Class<?> type = Class.forName("featurecat.lizzie.analysis.GetFoxRequest$SgfMainLineNormalizer");
    var constructor = type.getDeclaredConstructor(String.class);
    constructor.setAccessible(true);
    return constructor.newInstance(sgf);
  }

  private static Method requireMethod(Class<?> type, String name, Class<?>... parameterTypes) {
    try {
      Method method = type.getDeclaredMethod(name, parameterTypes);
      method.setAccessible(true);
      return method;
    } catch (NoSuchMethodException ex) {
      fail(type.getSimpleName() + " should expose " + name + "(...): " + ex.getMessage());
      throw new AssertionError(ex);
    }
  }

  private static void assertSgfMove(Object move, String color, String coordinate)
      throws NoSuchFieldException, IllegalAccessException {
    assertEquals(color, readField(move, "color"));
    assertEquals(coordinate, readField(move, "coordinate"));
  }

  private static Object readField(Object target, String fieldName)
      throws NoSuchFieldException, IllegalAccessException {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
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
    private final LizzieFrame previousFrame;
    private final WinrateGraph previousWinrateGraph;
    private final Leelaz previousLeelaz;
    private final Config previousConfig;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Board previousBoard,
        LizzieFrame previousFrame,
        WinrateGraph previousWinrateGraph,
        Leelaz previousLeelaz,
        Config previousConfig) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousWinrateGraph = previousWinrateGraph;
      this.previousLeelaz = previousLeelaz;
      this.previousConfig = previousConfig;
    }

    private static TestEnvironment open() throws Exception {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Board previousBoard = Lizzie.board;
      LizzieFrame previousFrame = Lizzie.frame;
      WinrateGraph previousWinrateGraph = LizzieFrame.winrateGraph;
      Leelaz previousLeelaz = Lizzie.leelaz;
      Config previousConfig = Lizzie.config;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      TrackingBoard board = allocate(TrackingBoard.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      board.movelistwr = new ArrayList<>();
      board.setHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      Lizzie.board = board;
      Lizzie.frame = allocate(TrackingFrame.class);
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      Config config = allocate(Config.class);
      config.newMoveNumberInBranch = false;
      config.playSound = false;
      config.initialMaxScoreLead = 10;
      Lizzie.config = config;
      LizzieFrame.winrateGraph = allocate(WinrateGraph.class);
      return new TestEnvironment(
          previousBoardWidth,
          previousBoardHeight,
          previousBoard,
          previousFrame,
          previousWinrateGraph,
          previousLeelaz,
          previousConfig);
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.winrateGraph = previousWinrateGraph;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.config = previousConfig;
    }
  }

  private static final class TrackingBoard extends Board {
    private TrackingBoard() {
      super();
    }

    @Override
    public void clear(boolean isEngineGame) {
      if (startStonelist == null) {
        startStonelist = new ArrayList<>();
      } else {
        startStonelist.clear();
      }
      if (movelistwr == null) {
        movelistwr = new ArrayList<>();
      } else {
        movelistwr.clear();
      }
      hasStartStone = false;
      setHistory(new BoardHistoryList(BoardData.empty(Board.boardWidth, Board.boardHeight)));
    }

    @Override
    public void clearforedit() {
      clear(false);
    }

    @Override
    public void clearAfterMove() {}
  }

  private static final class TrackingFrame extends LizzieFrame {
    private int setPlayersCallCount;
    private int refreshCallCount;
    private String whitePlayerTitle;
    private String blackPlayerTitle;

    private TrackingFrame() {
      super();
    }

    @Override
    public void requestProblemListRefresh() {}

    @Override
    public void refreshProblemListSnapshot() {}

    @Override
    public void refresh() {
      refreshCallCount += 1;
    }

    @Override
    public void setPlayers(String whitePlayer, String blackPlayer) {
      setPlayersCallCount += 1;
      whitePlayerTitle = whitePlayer;
      blackPlayerTitle = blackPlayer;
    }

    @Override
    public void resetTitle() {}

    @Override
    public void tryToResetByoTime() {}

    private void seedPlayers(String whitePlayer, String blackPlayer) {
      whitePlayerTitle = whitePlayer;
      blackPlayerTitle = blackPlayer;
    }

    private int setPlayersCallCount() {
      return setPlayersCallCount;
    }

    private int refreshCallCount() {
      return refreshCallCount;
    }

    private String currentWhitePlayerTitle() {
      return whitePlayerTitle;
    }

    private String currentBlackPlayerTitle() {
      return blackPlayerTitle;
    }
  }

  private static final class TrackingLeelaz extends Leelaz {
    private static final Pattern PLAY_COMMAND = Pattern.compile("^play\\s+([BW])\\s+(.+)$");
    private static final Pattern LOAD_SGF_COMMAND = Pattern.compile("^loadsgf\\s+(.+)$");
    private static final Pattern PROPERTY_PATTERN = Pattern.compile("(AB|AW|PL)\\[([^\\]]*)\\]");

    private List<String> commands;
    private Stone[] stones;
    private boolean blackToPlay = true;
    private Path lastLoadedSgf;

    private TrackingLeelaz() throws IOException {
      super("");
    }

    @Override
    public void clearBestMoves() {}

    @Override
    public void maybeAjustPDA(BoardHistoryNode node) {}

    @Override
    public void clear() {
      recordedCommands().add("clear");
      resetBoardState();
    }

    @Override
    public void playMove(Stone color, String move) {
      sendCommand("play " + (color.isBlack() ? "B" : "W") + " " + move);
    }

    @Override
    public void playMove(Stone color, String move, boolean addPlayer, boolean blackToPlay) {
      playMove(color, move);
    }

    @Override
    public void loadSgf(Path sgfFile) {
      recordedCommands().add("loadsgf " + sgfFile.toAbsolutePath());
      restoreSnapshotSgf(sgfFile);
    }

    @Override
    public void sendCommand(String command) {
      recordedCommands().add(command);
      if ("clear_board".equals(command)) {
        resetBoardState();
        return;
      }
      Matcher playMatcher = PLAY_COMMAND.matcher(command);
      if (playMatcher.matches()) {
        applyPlay(
            playMatcher.group(1).charAt(0) == 'B' ? Stone.BLACK : Stone.WHITE,
            playMatcher.group(2));
        return;
      }
      Matcher loadSgfMatcher = LOAD_SGF_COMMAND.matcher(command);
      if (loadSgfMatcher.matches()) {
        restoreSnapshotSgf(Path.of(loadSgfMatcher.group(1).trim()));
      }
    }

    private List<String> commandsSinceLastClear() {
      List<String> recorded = recordedCommands();
      int lastClear = recorded.lastIndexOf("clear");
      return recorded.subList(lastClear, recorded.size());
    }

    private Stone[] copyStones() {
      ensureBoardState();
      return stones.clone();
    }

    private boolean isBlackToPlay() {
      return blackToPlay;
    }

    private Path lastLoadedSgf() {
      return lastLoadedSgf;
    }

    private List<String> recordedCommands() {
      if (commands == null) {
        commands = new ArrayList<>();
      }
      return commands;
    }

    private void resetBoardState() {
      stones = new Stone[Board.boardWidth * Board.boardHeight];
      for (int index = 0; index < stones.length; index++) {
        stones[index] = Stone.EMPTY;
      }
      blackToPlay = true;
    }

    private void ensureBoardState() {
      if (stones == null) {
        resetBoardState();
      }
    }

    private void restoreSnapshotSgf(Path path) {
      lastLoadedSgf = path;
      resetBoardState();
      try {
        String content = Files.readString(path);
        Matcher matcher = PROPERTY_PATTERN.matcher(content);
        while (matcher.find()) {
          String tag = matcher.group(1);
          String value = matcher.group(2);
          if ("PL".equals(tag)) {
            blackToPlay = !"W".equalsIgnoreCase(value);
            continue;
          }
          int[] coords = SGFParser.convertSgfPosToCoord(value);
          if (coords == null) {
            continue;
          }
          stones[Board.getIndex(coords[0], coords[1])] =
              "AB".equals(tag) ? Stone.BLACK : Stone.WHITE;
        }
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to read SGF fixture", ex);
      }
    }

    private void applyPlay(Stone color, String move) {
      ensureBoardState();
      blackToPlay = color == Stone.WHITE;
      if ("pass".equalsIgnoreCase(move)) {
        return;
      }
      int[] coords = Board.convertNameToCoordinates(move, Board.boardHeight);
      int index = Board.getIndex(coords[0], coords[1]);
      stones[index] = color;
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
