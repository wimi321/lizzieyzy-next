package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.analysis.MoveData;
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
    assertEquals(55.0, data.getJSONObject(1).getDouble("winrate"), 0.01);
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

  private BoardData createBoardData(
      int moveNum, double winrate, double scoreMean, boolean blackToPlay) {
    Stone[] stones = new Stone[361];
    Arrays.fill(stones, Stone.EMPTY);
    BoardData d =
        new BoardData(
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
            0);
    d.scoreMean = scoreMean;
    return d;
  }
}
