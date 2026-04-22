package featurecat.lizzie.gui.web;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Collects board state and analysis data, serializes to JSON, and broadcasts to WebSocket clients.
 * Throttles updates to a maximum of 10 per second.
 */
public class WebBoardDataCollector {

  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "WebBoardDataCollector");
            t.setDaemon(true);
            return t;
          });
  private volatile WebBoardServer server;
  private volatile long lastBroadcastTime = 0;
  private static final long MIN_BROADCAST_INTERVAL_MS = 100; // 10 updates/sec max
  private final AtomicBoolean pendingUpdate = new AtomicBoolean(false);

  public WebBoardDataCollector() {}

  public void setServer(WebBoardServer server) {
    this.server = server;
  }

  /** Called when analysis data is updated. Throttles to max 10 updates/sec. */
  public void onAnalysisUpdated() {
    long now = System.currentTimeMillis();
    if (now - lastBroadcastTime < MIN_BROADCAST_INTERVAL_MS) {
      if (pendingUpdate.compareAndSet(false, true)) {
        long delay = MIN_BROADCAST_INTERVAL_MS - (now - lastBroadcastTime);
        executor.schedule(this::doBroadcastAnalysis, delay, TimeUnit.MILLISECONDS);
      }
      return;
    }
    executor.execute(this::doBroadcastAnalysis);
  }

  /** Called when board state changes (new move, navigation, etc.). */
  public void onBoardStateChanged() {
    executor.execute(this::doBroadcastFullState);
  }

  private void doBroadcastAnalysis() {
    pendingUpdate.set(false);
    lastBroadcastTime = System.currentTimeMillis();
    if (server == null) return;
    try {
      BoardData data = Lizzie.board.getHistory().getCurrentHistoryNode().getData();
      if (data.bestMoves == null || data.bestMoves.isEmpty()) return;
      int bw = Board.boardWidth;
      int bh = Board.boardHeight;
      JSONObject json =
          buildAnalysisUpdateJson(
              data.bestMoves,
              data.winrate,
              data.scoreMean,
              data.getPlayouts(),
              data.estimateArray,
              bw,
              bh);
      server.broadcastMessage(json.toString());
    } catch (Exception ignored) {
    }
  }

  private void doBroadcastFullState() {
    lastBroadcastTime = System.currentTimeMillis();
    if (server == null) return;
    try {
      BoardHistoryNode currentNode = Lizzie.board.getHistory().getCurrentHistoryNode();
      BoardData data = currentNode.getData();
      int bw = Board.boardWidth;
      int bh = Board.boardHeight;
      int[] lastMove = data.lastMove.isPresent() ? data.lastMove.get() : null;
      JSONObject fullState =
          buildFullStateJson(
              bw,
              bh,
              data.stones,
              lastMove,
              data.moveNumber,
              data.blackToPlay,
              data.bestMoves,
              data.winrate,
              data.scoreMean,
              data.getPlayouts(),
              data.estimateArray);
      server.broadcastFullState(fullState.toString());

      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      JSONObject history = buildWinrateHistoryJson(root, currentNode);
      server.broadcastMessage(history.toString());
    } catch (Exception ignored) {
    }
  }

  /** Shuts down the executor. */
  public void shutdown() {
    executor.shutdownNow();
    try {
      executor.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
    server = null;
  }

  // --- Static JSON serialization methods ---

  /**
   * Converts a Stone array to a JSONArray of ints. BLACK/BLACK_RECURSED -> 1, WHITE/WHITE_RECURSED
   * -> 2, EMPTY/CAPTURED -> 0.
   */
  static JSONArray buildStonesArray(Stone[] stones) {
    JSONArray arr = new JSONArray();
    for (Stone s : stones) {
      if (s.isBlack()) arr.put(1);
      else if (s.isEmpty()) arr.put(0);
      else arr.put(2); // white variants
    }
    return arr;
  }

  /**
   * Parses a GTP coordinate (e.g. "Q16") to [x, y]. Column A=0, skip I, row maps to y = boardHeight
   * - rowNumber.
   */
  static int[] gtpToXY(String coordinate, int boardHeight) {
    if (coordinate == null || coordinate.length() < 2) return null;
    char col = coordinate.toUpperCase().charAt(0);
    int x = col - 'A';
    if (col > 'I') x--; // GTP skips 'I'
    int row;
    try {
      row = Integer.parseInt(coordinate.substring(1));
    } catch (NumberFormatException e) {
      return null;
    }
    int y = boardHeight - row;
    return new int[] {x, y};
  }

  /** Serializes a MoveData to JSON with coordinate, x, y, winrate, playouts, etc. */
  static JSONObject buildMoveDataJson(MoveData move, int boardWidth, int boardHeight) {
    JSONObject obj = new JSONObject();
    obj.put("coordinate", move.coordinate);
    int[] xy = gtpToXY(move.coordinate, boardHeight);
    if (xy != null) {
      obj.put("x", xy[0]);
      obj.put("y", xy[1]);
    }
    obj.put("winrate", move.winrate);
    obj.put("playouts", move.playouts);
    obj.put("scoreMean", move.scoreMean);
    obj.put("scoreStdev", move.scoreStdev);
    obj.put("policy", move.policy);
    obj.put("lcb", move.lcb);
    obj.put("order", move.order);
    JSONArray var = new JSONArray();
    if (move.variation != null) {
      for (String s : move.variation) var.put(s);
    }
    obj.put("variation", var);
    return obj;
  }

  /** Builds an analysis_update JSON message. */
  static JSONObject buildAnalysisUpdateJson(
      List<MoveData> bestMoves,
      double winrate,
      double scoreMean,
      int playouts,
      ArrayList<Double> estimateArray,
      int boardWidth,
      int boardHeight) {
    JSONObject obj = new JSONObject();
    obj.put("type", "analysis_update");
    JSONArray movesArr = new JSONArray();
    for (MoveData m : bestMoves) {
      movesArr.put(buildMoveDataJson(m, boardWidth, boardHeight));
    }
    obj.put("bestMoves", movesArr);
    obj.put("winrate", winrate);
    obj.put("scoreMean", scoreMean);
    obj.put("playouts", playouts);
    if (estimateArray != null) {
      JSONArray est = new JSONArray();
      for (Double d : estimateArray) est.put(d);
      obj.put("estimateArray", est);
    } else {
      obj.put("estimateArray", JSONObject.NULL);
    }
    return obj;
  }

  /** Builds a full_state JSON message with all board information. */
  static JSONObject buildFullStateJson(
      int boardWidth,
      int boardHeight,
      Stone[] stones,
      int[] lastMove,
      int moveNumber,
      boolean blackToPlay,
      List<MoveData> bestMoves,
      double winrate,
      double scoreMean,
      int playouts,
      ArrayList<Double> estimateArray) {
    JSONObject obj = new JSONObject();
    obj.put("type", "full_state");
    obj.put("boardWidth", boardWidth);
    obj.put("boardHeight", boardHeight);
    obj.put("stones", buildStonesArray(stones));
    if (lastMove != null) {
      obj.put("lastMove", new JSONArray(lastMove));
    } else {
      obj.put("lastMove", JSONObject.NULL);
    }
    obj.put("moveNumber", moveNumber);
    obj.put("currentPlayer", blackToPlay ? "B" : "W");
    JSONArray movesArr = new JSONArray();
    if (bestMoves != null) {
      for (MoveData m : bestMoves) {
        movesArr.put(buildMoveDataJson(m, boardWidth, boardHeight));
      }
    }
    obj.put("bestMoves", movesArr);
    obj.put("winrate", winrate);
    obj.put("scoreMean", scoreMean);
    obj.put("playouts", playouts);
    if (estimateArray != null) {
      JSONArray est = new JSONArray();
      for (Double d : estimateArray) est.put(d);
      obj.put("estimateArray", est);
    } else {
      obj.put("estimateArray", JSONObject.NULL);
    }
    return obj;
  }

  /** Builds a winrate_history JSON message by walking from root to current node. */
  static JSONObject buildWinrateHistoryJson(BoardHistoryNode root, BoardHistoryNode current) {
    JSONObject obj = new JSONObject();
    obj.put("type", "winrate_history");

    // Walk backwards from current to root to collect the actual path
    java.util.LinkedList<BoardHistoryNode> path = new java.util.LinkedList<>();
    BoardHistoryNode node = current;
    while (node != null) {
      path.addFirst(node);
      Optional<BoardHistoryNode> prev = node.previous();
      node = prev.isPresent() ? prev.get() : null;
    }

    JSONArray data = new JSONArray();
    for (BoardHistoryNode n : path) {
      BoardData d = n.getData();
      JSONObject entry = new JSONObject();
      entry.put("moveNumber", d.moveNumber);
      entry.put("winrate", d.winrate);
      entry.put("scoreMean", d.scoreMean);
      data.put(entry);
    }

    obj.put("data", data);
    return obj;
  }
}
