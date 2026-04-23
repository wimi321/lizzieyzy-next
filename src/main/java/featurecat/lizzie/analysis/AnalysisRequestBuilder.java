package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Movelist;
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
    addInitialStones(request);
    addRules(request);
    request.put("komi", Lizzie.board.getHistory().getGameInfo().getKomi());
    request.put("boardXSize", Board.boardWidth);
    request.put("boardYSize", Board.boardHeight);
    addMoveHistory(request, analyzeNode);
    JSONObject overrideSettings = new JSONObject();
    overrideSettings.put("reportAnalysisWinratesAs", "SIDETOMOVE");
    request.put("overrideSettings", overrideSettings);
    return request;
  }

  static void addInitialStones(JSONObject request) {
    if (Lizzie.board.hasStartStone) {
      ArrayList<String[]> initialStoneList = new ArrayList<String[]>();
      for (Movelist mv : Lizzie.board.startStonelist) {
        if (!mv.ispass) {
          if (mv.isblack) {
            initialStoneList.add(new String[] {"B", Board.convertCoordinatesToName(mv.x, mv.y)});
          } else {
            initialStoneList.add(new String[] {"W", Board.convertCoordinatesToName(mv.x, mv.y)});
          }
        }
      }
      request.put("initialStones", initialStoneList);
    }
  }

  static void addRules(JSONObject request) {
    JSONObject ruleSettings;
    if (!Lizzie.config.analysisUseCurrentRules) {
      if (!Lizzie.config.analysisSpecificRules.equals("")) {
        ruleSettings = new JSONObject(Lizzie.config.analysisSpecificRules);
        request.put("rules", ruleSettings);
      } else request.put("rules", "tromp-taylor");
    } else if (!Lizzie.config.currentKataGoRules.equals("")) {
      ruleSettings = new JSONObject(new String(Lizzie.config.currentKataGoRules.substring(2)));
      request.put("rules", ruleSettings);
    } else if (Lizzie.config.autoLoadKataRules && !Lizzie.config.kataRules.equals("")) {
      ruleSettings = new JSONObject(Lizzie.config.kataRules);
      request.put("rules", ruleSettings);
    } else request.put("rules", "tromp-taylor");
  }

  static void addMoveHistory(JSONObject request, BoardHistoryNode analyzeNode) {
    ArrayList<Integer> moveTurns = new ArrayList<Integer>();
    ArrayList<String[]> moveList = new ArrayList<String[]>();
    BoardHistoryNode node = analyzeNode;
    while (node.previous().isPresent()) {
      if (node.getData().lastMove.isPresent()) {
        int[] move = node.getData().lastMove.get();
        if (node.getData().lastMoveColor.isBlack())
          moveList.add(new String[] {"B", Board.convertCoordinatesToName(move[0], move[1])});
        else moveList.add(new String[] {"W", Board.convertCoordinatesToName(move[0], move[1])});
      } else {
        if (node.getData().lastMoveColor.isBlack()) moveList.add(new String[] {"B", "pass"});
        else moveList.add(new String[] {"W", "pass"});
      }
      node = node.previous().get();
    }
    ArrayList<String[]> moveList2 = new ArrayList<String[]>();
    for (int i = moveList.size() - 1; i >= 0; i--) {
      moveList2.add(moveList.get(i));
    }
    moveTurns.add(moveList2.size());
    request.put("moves", moveList2);
    request.put("analyzeTurns", moveTurns);
  }
}
