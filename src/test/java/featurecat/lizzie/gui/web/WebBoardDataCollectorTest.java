package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

public class WebBoardDataCollectorTest {

  @Test
  void buildStonesArray_convertsStoneEnumToInts() {
    Stone[] stones = new Stone[9]; // 3x3
    stones[0] = Stone.EMPTY;
    stones[1] = Stone.BLACK;
    stones[2] = Stone.WHITE;
    stones[3] = Stone.BLACK_RECURSED; // should map to 1 (black)
    stones[4] = Stone.WHITE_RECURSED; // should map to 2 (white)
    stones[5] = Stone.BLACK_CAPTURED; // should map to 0 (empty)
    stones[6] = Stone.WHITE_CAPTURED; // should map to 0 (empty)
    stones[7] = Stone.EMPTY;
    stones[8] = Stone.BLACK;

    JSONArray arr = WebBoardDataCollector.buildStonesArray(stones);
    assertEquals(9, arr.length());
    assertEquals(0, arr.getInt(0)); // EMPTY
    assertEquals(1, arr.getInt(1)); // BLACK
    assertEquals(2, arr.getInt(2)); // WHITE
    assertEquals(1, arr.getInt(3)); // BLACK_RECURSED -> 1
    assertEquals(2, arr.getInt(4)); // WHITE_RECURSED -> 2
    assertEquals(0, arr.getInt(5)); // BLACK_CAPTURED -> 0
    assertEquals(0, arr.getInt(6)); // WHITE_CAPTURED -> 0
    assertEquals(0, arr.getInt(7)); // EMPTY
    assertEquals(1, arr.getInt(8)); // BLACK
  }

  @Test
  void buildMoveDataJson_serializesAllFields() {
    MoveData move = new MoveData();
    move.coordinate = "Q16";
    move.winrate = 56.3;
    move.playouts = 3200;
    move.scoreMean = 2.5;
    move.scoreStdev = 8.1;
    move.policy = 0.18;
    move.lcb = 55.8;
    move.order = 0;
    move.variation = List.of("Q16", "D4", "R14");

    JSONObject json = WebBoardDataCollector.buildMoveDataJson(move, 19, 19);
    assertEquals("Q16", json.getString("coordinate"));
    assertEquals(56.3, json.getDouble("winrate"), 0.01);
    assertEquals(3200, json.getInt("playouts"));
    assertEquals(2.5, json.getDouble("scoreMean"), 0.01);
    assertEquals(0, json.getInt("order"));
    assertEquals(3, json.getJSONArray("variation").length());
    // x, y should be derived from GTP coordinate "Q16" on 19x19
    assertTrue(json.has("x"));
    assertTrue(json.has("y"));
  }

  @Test
  void gtpToXY_parsesCoordinateCorrectly() {
    // Q16 on 19x19: Q is column 15 (skip I), row 16 -> y = 19 - 16 = 3
    int[] xy = WebBoardDataCollector.gtpToXY("Q16", 19);
    assertNotNull(xy);
    assertEquals(15, xy[0]); // Q -> 16th letter, minus 1 for 0-index, minus 1 for skipping I = 15
    assertEquals(3, xy[1]); // 19 - 16 = 3

    // A1 on 19x19: A is column 0, row 1 -> y = 19 - 1 = 18
    int[] xy2 = WebBoardDataCollector.gtpToXY("A1", 19);
    assertNotNull(xy2);
    assertEquals(0, xy2[0]);
    assertEquals(18, xy2[1]);

    // D4 on 19x19: D is column 3, row 4 -> y = 19 - 4 = 15
    int[] xy3 = WebBoardDataCollector.gtpToXY("D4", 19);
    assertNotNull(xy3);
    assertEquals(3, xy3[0]);
    assertEquals(15, xy3[1]);

    // Null/invalid inputs
    assertNull(WebBoardDataCollector.gtpToXY(null, 19));
    assertNull(WebBoardDataCollector.gtpToXY("A", 19));
  }

  @Test
  void buildAnalysisUpdateJson_createsCorrectStructure() {
    MoveData move1 = new MoveData();
    move1.coordinate = "Q16";
    move1.winrate = 56.3;
    move1.playouts = 3200;
    move1.scoreMean = 2.5;
    move1.scoreStdev = 8.1;
    move1.policy = 0.18;
    move1.lcb = 55.8;
    move1.order = 0;
    move1.variation = List.of("Q16", "D4");

    List<MoveData> moves = List.of(move1);
    ArrayList<Double> estimate = new ArrayList<>(List.of(0.9, -0.8, 0.0));

    JSONObject json =
        WebBoardDataCollector.buildAnalysisUpdateJson(moves, 56.3, 2.5, 12800, estimate, 19, 19);

    assertEquals("analysis_update", json.getString("type"));
    assertEquals(56.3, json.getDouble("winrate"), 0.01);
    assertEquals(2.5, json.getDouble("scoreMean"), 0.01);
    assertEquals(12800, json.getInt("playouts"));
    assertEquals(1, json.getJSONArray("bestMoves").length());
    assertEquals(3, json.getJSONArray("estimateArray").length());
  }

  @Test
  void buildAnalysisUpdateJson_handlesNullEstimate() {
    JSONObject json =
        WebBoardDataCollector.buildAnalysisUpdateJson(List.of(), 50.0, 0.0, 0, null, 19, 19);
    assertTrue(json.isNull("estimateArray"));
  }

  @Test
  void buildFullStateJson_includesAllBoardFields() {
    Stone[] stones = new Stone[361];
    Arrays.fill(stones, Stone.EMPTY);
    stones[0] = Stone.BLACK;

    List<MoveData> bestMoves = new ArrayList<>();
    MoveData m = new MoveData();
    m.coordinate = "D4";
    m.winrate = 50.0;
    m.playouts = 100;
    m.order = 0;
    m.variation = List.of("D4");
    bestMoves.add(m);

    JSONObject json =
        WebBoardDataCollector.buildFullStateJson(
            19, 19, stones, new int[] {3, 15}, 10, true, bestMoves, 50.0, 0.0, 100, null);

    assertEquals("full_state", json.getString("type"));
    assertEquals(19, json.getInt("boardWidth"));
    assertEquals(19, json.getInt("boardHeight"));
    assertEquals(361, json.getJSONArray("stones").length());
    assertEquals(1, json.getJSONArray("stones").getInt(0)); // BLACK
    assertEquals(3, json.getJSONArray("lastMove").getInt(0));
    assertEquals(15, json.getJSONArray("lastMove").getInt(1));
    assertEquals(10, json.getInt("moveNumber"));
    assertEquals("B", json.getString("currentPlayer"));
    assertFalse(json.getJSONArray("bestMoves").isEmpty());
  }

  @Test
  void buildFullStateJson_handlesNullLastMove() {
    Stone[] stones = new Stone[361];
    Arrays.fill(stones, Stone.EMPTY);

    JSONObject json =
        WebBoardDataCollector.buildFullStateJson(
            19, 19, stones, null, 0, true, null, 50.0, 0.0, 0, null);

    assertTrue(json.isNull("lastMove"));
    assertTrue(json.isNull("estimateArray"));
    assertEquals(0, json.getJSONArray("bestMoves").length());
  }

  @Test
  void buildWinrateHistoryJson_traversesNodeChain() {
    // Create a chain: node0 (move 0) -> node1 (move 1) -> node2 (move 2)
    BoardData d0 = createBoardData(0, 50.0, 0.0, true);
    BoardData d1 = createBoardData(1, 55.0, 1.5, false);
    BoardData d2 = createBoardData(2, 48.0, -0.8, true);

    BoardHistoryNode node0 = new BoardHistoryNode(d0);
    BoardHistoryNode node1 = new BoardHistoryNode(d1);
    BoardHistoryNode node2 = new BoardHistoryNode(d2);
    node0.add(node1);
    node1.add(node2);

    JSONObject json = WebBoardDataCollector.buildWinrateHistoryJson(node0, node2);
    assertEquals("winrate_history", json.getString("type"));
    JSONArray data = json.getJSONArray("data");
    assertEquals(3, data.length());
    assertEquals(0, data.getJSONObject(0).getInt("moveNumber"));
    // d1 has blackToPlay=false → winrate flipped to black perspective: 100-55=45
    assertEquals(45.0, data.getJSONObject(1).getDouble("winrate"), 0.01);
    // d2 has blackToPlay=true → not flipped
    assertEquals(-0.8, data.getJSONObject(2).getDouble("scoreMean"), 0.01);
  }

  @Test
  void buildWinrateHistoryJson_singleNode() {
    BoardData d0 = createBoardData(0, 50.0, 0.0, true);
    BoardHistoryNode node0 = new BoardHistoryNode(d0);

    JSONObject json = WebBoardDataCollector.buildWinrateHistoryJson(node0, node0);
    JSONArray data = json.getJSONArray("data");
    assertEquals(1, data.length());
    assertEquals(50.0, data.getJSONObject(0).getDouble("winrate"), 0.01);
  }

  @Test
  void buildWinrateHistoryJson_branchedTree_followsActiveBranch() {
    // Tree: node0 -> node1a (main, via add)
    //            \-> node1b (branch, added to variations) -> node2b
    BoardData d0 = createBoardData(0, 50.0, 0.0, true);
    BoardData d1a = createBoardData(1, 55.0, 1.5, false);
    BoardData d1b = createBoardData(1, 45.0, -1.0, false);
    BoardData d2b = createBoardData(2, 42.0, -2.0, true);

    BoardHistoryNode node0 = new BoardHistoryNode(d0);
    BoardHistoryNode node1a = new BoardHistoryNode(d1a);
    BoardHistoryNode node1b = new BoardHistoryNode(d1b);
    BoardHistoryNode node2b = new BoardHistoryNode(d2b);

    // node0.add clears variations and adds node1a as main line
    node0.add(node1a);
    // manually add branch: node1b as second variation of node0, with previous set
    node0.getVariations().add(node1b);
    // set previous manually via reflection since it's package-private
    try {
      java.lang.reflect.Field prevField = BoardHistoryNode.class.getDeclaredField("previous");
      prevField.setAccessible(true);
      prevField.set(node1b, Optional.of(node0));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    node1b.add(node2b);

    // Current node is node2b (on the branch), path should be [node0, node1b, node2b]
    JSONObject json = WebBoardDataCollector.buildWinrateHistoryJson(node0, node2b);
    JSONArray data = json.getJSONArray("data");
    assertEquals(3, data.length());
    // d0 blackToPlay=true → not flipped: 50
    assertEquals(50.0, data.getJSONObject(0).getDouble("winrate"), 0.01);
    // d1b blackToPlay=false → flipped to black perspective: 100-45=55
    assertEquals(55.0, data.getJSONObject(1).getDouble("winrate"), 0.01);
    // d2b blackToPlay=true → not flipped: 42
    assertEquals(42.0, data.getJSONObject(2).getDouble("winrate"), 0.01);
  }

  private BoardData createBoardData(
      int moveNum, double winrate, double scoreMean, boolean blackToPlay) {
    return createBoardData(moveNum, winrate, scoreMean, blackToPlay, 100);
  }

  @Test
  void buildWinrateHistoryJson_nullsForUnanalyzedNodes() {
    // Mid-game sync: prior moves never analyzed (playouts=0), current node analyzed.
    BoardData d0 = createBoardData(0, 50.0, 0.0, true, 0);
    BoardData d1 = createBoardData(1, 0.0, 0.0, false, 0);
    BoardData d2 = createBoardData(2, 55.0, 1.5, true, 200);

    BoardHistoryNode n0 = new BoardHistoryNode(d0);
    BoardHistoryNode n1 = new BoardHistoryNode(d1);
    BoardHistoryNode n2 = new BoardHistoryNode(d2);
    n0.add(n1);
    n1.add(n2);

    JSONObject json = WebBoardDataCollector.buildWinrateHistoryJson(n0, n2);
    JSONArray data = json.getJSONArray("data");
    assertEquals(3, data.length());
    assertTrue(data.getJSONObject(0).isNull("winrate"));
    assertTrue(data.getJSONObject(0).isNull("scoreMean"));
    assertTrue(data.getJSONObject(1).isNull("winrate"));
    assertTrue(data.getJSONObject(1).isNull("scoreMean"));
    assertEquals(55.0, data.getJSONObject(2).getDouble("winrate"), 0.01);
  }

  @Test
  void buildWinrateHistoryJson_emitsBlackToPlayField() {
    BoardData d0 = createBoardData(0, 50.0, 0.0, true);
    BoardData d1 = createBoardData(1, 55.0, 1.5, false);
    BoardHistoryNode n0 = new BoardHistoryNode(d0);
    BoardHistoryNode n1 = new BoardHistoryNode(d1);
    n0.add(n1);

    JSONObject json = WebBoardDataCollector.buildWinrateHistoryJson(n0, n1);
    JSONArray data = json.getJSONArray("data");
    assertTrue(data.getJSONObject(0).getBoolean("blackToPlay"));
    assertFalse(data.getJSONObject(1).getBoolean("blackToPlay"));
  }

  @Test
  void buildTrialStateJsonForActiveSessionIncludesAllFields() {
    BoardHistoryNode anchor =
        new BoardHistoryNode(BoardData.empty(Board.boardWidth, Board.boardHeight));
    BoardHistoryNode display = anchor;
    WebBoardDataCollector.TrialSessionView view =
        new WebBoardDataCollector.TrialSessionView("client-x", 0, anchor, display);
    JSONObject json = WebBoardDataCollector.buildTrialStateJson(view);
    assertEquals("trial_state", json.getString("type"));
    assertTrue(json.getBoolean("active"));
    assertEquals("client-x", json.getString("ownerClientId"));
    assertEquals(0, json.getInt("anchorMoveNumber"));
    assertFalse(json.getBoolean("canBack"));
    assertFalse(json.getBoolean("canForward"));
    assertEquals(0, json.getJSONArray("siblingMarkers").length());
  }

  @Test
  void buildTrialStateJsonForIdleReturnsActiveFalse() {
    JSONObject json = WebBoardDataCollector.buildTrialStateJson(null);
    assertEquals("trial_state", json.getString("type"));
    assertFalse(json.getBoolean("active"));
    assertFalse(json.has("siblingMarkers"));
  }

  @Test
  void buildTrialStateJsonSiblingMarkersOnlyIncludeNonMainline() {
    BoardHistoryNode anchor = makeNodeWithLastMove(-1, -1, true);
    BoardHistoryNode mainline = makeNodeWithLastMove(3, 3, false);
    BoardHistoryNode s1 = makeNodeWithLastMove(15, 15, false);
    BoardHistoryNode s2 = makeNodeWithLastMove(9, 9, false);
    anchor.variations.add(mainline);
    anchor.variations.add(s1);
    anchor.variations.add(s2);

    WebBoardDataCollector.TrialSessionView view =
        new WebBoardDataCollector.TrialSessionView("c1", 0, anchor, anchor);
    JSONObject json = WebBoardDataCollector.buildTrialStateJson(view);
    JSONArray markers = json.getJSONArray("siblingMarkers");
    assertEquals(2, markers.length());
    assertEquals(1, markers.getJSONObject(0).getInt("childIndex"));
    assertEquals("1", markers.getJSONObject(0).getString("label"));
    assertEquals(15, markers.getJSONObject(0).getInt("x"));
    assertEquals(15, markers.getJSONObject(0).getInt("y"));
    assertEquals(2, markers.getJSONObject(1).getInt("childIndex"));
    assertEquals("2", markers.getJSONObject(1).getString("label"));
  }

  @Test
  void buildTrialStateJsonAllNonDummyChildrenAreSiblingsWhenMainlineIsDummy() {
    // anchor 进入试下时是 mainline 末端，插了 dummy 占位；后续两次试下落子。
    // variations = [dummy, 子A, 子B]，期望两个 sibling marker：A=childIndex 1 label 1，B=childIndex 2 label
    // 2。
    BoardHistoryNode anchor = makeNodeWithLastMove(-1, -1, true);
    BoardData dummyData = makeNodeWithLastMove(-1, -1, true).getData();
    dummyData.dummy = true;
    BoardHistoryNode dummy = new BoardHistoryNode(dummyData);
    BoardHistoryNode childA = makeNodeWithLastMove(15, 15, false);
    BoardHistoryNode childB = makeNodeWithLastMove(9, 9, false);
    anchor.variations.add(dummy);
    anchor.variations.add(childA);
    anchor.variations.add(childB);

    WebBoardDataCollector.TrialSessionView view =
        new WebBoardDataCollector.TrialSessionView("c1", 0, anchor, anchor);
    JSONObject json = WebBoardDataCollector.buildTrialStateJson(view);
    JSONArray markers = json.getJSONArray("siblingMarkers");
    assertEquals(2, markers.length());
    assertEquals(1, markers.getJSONObject(0).getInt("childIndex"));
    assertEquals("1", markers.getJSONObject(0).getString("label"));
    assertEquals(15, markers.getJSONObject(0).getInt("x"));
    assertEquals(2, markers.getJSONObject(1).getInt("childIndex"));
    assertEquals("2", markers.getJSONObject(1).getString("label"));
    assertEquals(9, markers.getJSONObject(1).getInt("x"));
  }

  @Test
  void buildTrialStateJsonCanForwardWhenVariationsExist() {
    BoardHistoryNode anchor = makeNodeWithLastMove(-1, -1, true);
    BoardHistoryNode child = makeNodeWithLastMove(3, 3, false);
    anchor.variations.add(child);
    WebBoardDataCollector.TrialSessionView view =
        new WebBoardDataCollector.TrialSessionView("c1", 0, anchor, anchor);
    assertTrue(WebBoardDataCollector.buildTrialStateJson(view).getBoolean("canForward"));
  }

  @Test
  void buildTrialStateJsonCanBackWhenDisplayDifferentFromAnchor() {
    BoardHistoryNode anchor = makeNodeWithLastMove(-1, -1, true);
    BoardHistoryNode display = makeNodeWithLastMove(3, 3, false);
    WebBoardDataCollector.TrialSessionView view =
        new WebBoardDataCollector.TrialSessionView("c1", 0, anchor, display);
    assertTrue(WebBoardDataCollector.buildTrialStateJson(view).getBoolean("canBack"));
  }

  private BoardHistoryNode makeNodeWithLastMove(int x, int y, boolean blackToPlay) {
    BoardData data = BoardData.empty(Board.boardWidth, Board.boardHeight);
    data.lastMove =
        (x >= 0 && y >= 0) ? java.util.Optional.of(new int[] {x, y}) : java.util.Optional.empty();
    data.blackToPlay = blackToPlay;
    return new BoardHistoryNode(data);
  }

  private BoardData createBoardData(
      int moveNum, double winrate, double scoreMean, boolean blackToPlay, int playouts) {
    Stone[] stones = new Stone[361];
    Arrays.fill(stones, Stone.EMPTY);
    BoardData d =
        BoardData.snapshot(
            stones,
            Optional.empty(),
            Stone.EMPTY,
            blackToPlay,
            new Zobrist(),
            moveNum,
            new int[361],
            0,
            0,
            winrate,
            playouts);
    d.scoreMean = scoreMean;
    return d;
  }
}
