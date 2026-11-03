package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.util.ArrayList;
import org.json.JSONObject;

public class AnalysisRequestBuilder {

  public static JSONObject buildRequest(
      String id,
      BoardHistoryNode analyzeNode,
      int maxVisits,
      boolean includePVVisits,
      boolean includeOwnership,
      boolean includeMovesOwnership) {
    JSONObject request = new JSONObject();
    request.put("id", id);
    request.put("maxVisits", maxVisits);
    request.put("includePVVisits", includePVVisits);
    request.put("includeOwnership", includeOwnership);
    request.put("includeMovesOwnership", includeMovesOwnership);
    BoardHistoryNode snapshotAnchor = AnalysisEngine.findSnapshotAnchor(analyzeNode);
    BoardHistoryNode initialStateAnchor = AnalysisEngine.resolveInitialStateAnchor(snapshotAnchor);
    ArrayList<String[]> initialStoneList = AnalysisEngine.collectInitialStones(initialStateAnchor);
    if (!initialStoneList.isEmpty()) {
      request.put("initialStones", initialStoneList);
    }
    String initialPlayer = AnalysisEngine.collectInitialPlayer(initialStateAnchor);
    if (initialPlayer != null) {
      request.put("initialPlayer", initialPlayer);
    }
    addRules(request);
    request.put("komi", Lizzie.board.getHistory().getGameInfo().getKomi());
    request.put("boardXSize", Board.boardWidth);
    request.put("boardYSize", Board.boardHeight);
    ArrayList<String[]> moveList =
        AnalysisEngine.collectHistoryActions(analyzeNode, snapshotAnchor);
    ArrayList<Integer> moveTurns = new ArrayList<Integer>();
    moveTurns.add(moveList.size());
    request.put("moves", moveList);
    request.put("analyzeTurns", moveTurns);
    JSONObject overrideSettings = new JSONObject();
    overrideSettings.put("reportAnalysisWinratesAs", "SIDETOMOVE");
    request.put("overrideSettings", overrideSettings);
    return request;
  }

  static void addRules(JSONObject request) {
    request.put("rules", rules());
  }

  /** The rules analysis requests send: KataGo rules as JSON, or "tromp-taylor". */
  static Object rules() {
    Config config = Lizzie.config;
    if (!config.analysisUseCurrentRules) {
      return config.analysisSpecificRules.equals("")
          ? "tromp-taylor"
          : new JSONObject(config.analysisSpecificRules);
    }
    if (!config.currentKataGoRules.equals("")) {
      return new JSONObject(config.currentKataGoRules.substring(2));
    }
    if (config.autoLoadKataRules && !config.kataRules.equals("")) {
      return new JSONObject(config.kataRules);
    }
    return "tromp-taylor";
  }

  /** Whether analysis requests ask for rules that give a drawn count to the first passer. */
  public static boolean sendsFirstPassDrawRule() {
    try {
      return FirstPassDraw.applies(KataGoRules.parse(String.valueOf(rules())).orElse(null));
    } catch (RuntimeException unreadable) {
      return false; // A count must still end; requests report unreadable rules themselves.
    }
  }
}
