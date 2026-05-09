package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class YikeGeometryNormalizationTest {
  @Test
  void keepsSquareBoardRectUnchanged() {
    Rectangle normalized = OnlineDialog.normalizeYikeBoardRect(13, 50, 613, 613);

    assertEquals(new Rectangle(13, 50, 613, 613), normalized);
  }

  @Test
  void centersLandscapeRectIntoSquareBoardRect() {
    Rectangle normalized = OnlineDialog.normalizeYikeBoardRect(25, 50, 567, 541);

    assertEquals(new Rectangle(38, 50, 541, 541), normalized);
  }

  @Test
  void centersPortraitRectIntoSquareBoardRect() {
    Rectangle normalized = OnlineDialog.normalizeYikeBoardRect(855, 287, 332, 352);

    assertEquals(new Rectangle(855, 297, 332, 332), normalized);
  }

  @Test
  void prefersWrapperDerivedInnerBoardCandidateOverOuterBoardContainer() {
    JSONArray candidates = new JSONArray();
    candidates.put(candidate("div.board_content", "selector:[class*=board]", 25, 50, 687, 660));
    candidates.put(
        candidate(
            "div.board_content::content-box",
            "selector:[class*=board]:content-box",
            45,
            60,
            656,
            640));
    candidates.put(
        candidate(
            "div#board.board.wgo-player-main", "selector:[class*=board]:hit", 45, 60, 687, 687));

    assertEquals(
        "div#board.board.wgo-player-main|selector:[class*=board]:hit",
        OnlineDialog.describeBestYikeGeometry(candidates));
  }

  @Test
  void rejectsGenericSquareCandidateWithoutBoardSignal() {
    JSONArray candidates = new JSONArray();
    candidates.put(candidate("div.ivu-row", "square-candidate", 12, 94, 1183, 1134));

    assertEquals(null, OnlineDialog.describeAcceptedYikeGeometry(candidates, 1207, 1268));
  }

  @Test
  void acceptsWgoBoardCandidateOverContentBox() {
    JSONArray candidates = new JSONArray();
    candidates.put(
        candidate(
            "div.board_content::content-box",
            "selector:[class*=board]:content-box",
            45,
            60,
            656,
            640));
    candidates.put(
        candidate(
            "div#board.board.wgo-player-main", "selector:[class*=board]:hit", 45, 60, 687, 687));

    assertEquals(
        "div#board.board.wgo-player-main|selector:[class*=board]:hit",
        OnlineDialog.describeAcceptedYikeGeometry(candidates, 1463, 800));
  }

  @Test
  void rejectsGameBoardScopeOnLiveRoomRoute() {
    JSONArray candidates = new JSONArray();
    candidates.put(
        candidate(
            "div#board.board.wgo-player-main",
            "selector:[class*=board]:hit",
            "#board_width.board_width.noselect_text>div.board_detail_new",
            25,
            60,
            722,
            722));

    assertEquals(
        null,
        OnlineDialog.describeAcceptedYikeGeometry(
            candidates, 1397, 792, "https://home.yikeweiqi.com/#/live/new-room/186530/0/0"));
  }

  @Test
  void rejectsBoardCandidateOnLiveListRoute() {
    JSONArray candidates = new JSONArray();
    candidates.put(
        candidate(
            "div#board.board.wgo-player-main",
            "selector:[class*=board]:hit",
            "div.board_content>wgo-board-element",
            25,
            60,
            722,
            722));

    assertEquals(
        null,
        OnlineDialog.describeAcceptedYikeGeometry(
            candidates, 1397, 792, "https://home.yikeweiqi.com/#/live"));
  }

  @Test
  void distinguishesYikeGameLobbyFromBoardRoutes() {
    assertEquals("live-list", OnlineDialog.yikeRouteKind("https://home.yikeweiqi.com/#/live"));
    assertEquals(
        "live-room",
        OnlineDialog.yikeRouteKind("https://home.yikeweiqi.com/#/live/new-room/186530/0/0"));
    assertEquals("game-lobby", OnlineDialog.yikeRouteKind("https://home.yikeweiqi.com/#/game"));
    assertEquals(
        "unite-board", OnlineDialog.yikeRouteKind("https://home.yikeweiqi.com/#/unite/66304678"));
    assertEquals(
        "unite-board",
        OnlineDialog.yikeRouteKind("https://home.yikeweiqi.com/#/game/play/1/15630642"));
  }

  @Test
  void sessionKeyKeepsLiveRoomAndUniteBoardGeometrySeparated() {
    String liveRoomSession =
        OnlineDialog.yikeSessionKey("https://home.yikeweiqi.com/#/live/new-room/186530/0/0");
    String uniteSession =
        OnlineDialog.yikeSessionKey("https://home.yikeweiqi.com/#/unite/66304678");

    assertNotEquals(liveRoomSession, uniteSession);
    assertFalse(OnlineDialog.isGeometryForCurrentSession(liveRoomSession, uniteSession));
  }

  @Test
  void acceptsLiveRoomBoardCandidateOnLiveRoomRoute() {
    JSONArray candidates = new JSONArray();
    candidates.put(
        candidate(
            "div#board.board.wgo-player-main",
            "selector:[class*=board]:hit",
            "div#board.board.wgo-player-main>div.board_content",
            45,
            60,
            654,
            654));

    assertEquals(
        "div#board.board.wgo-player-main|selector:[class*=board]:hit",
        OnlineDialog.describeAcceptedYikeGeometry(
            candidates, 1397, 792, "https://home.yikeweiqi.com/#/live/new-room/186530/0/0"));
  }

  @Test
  void rejectsAnyBoardCandidateOnGameLobbyRoute() {
    JSONArray candidates = new JSONArray();
    candidates.put(
        candidate(
            "div#board.board.wgo-player-main",
            "selector:[class*=board]:hit",
            "div#board.board.wgo-player-main>div.board_content",
            45,
            60,
            654,
            654));

    assertNull(
        OnlineDialog.describeAcceptedYikeGeometry(
            candidates, 1397, 792, "https://home.yikeweiqi.com/#/game"));
  }

  @Test
  void acceptedGeometryCommandIncludesExplicitGridAndSkipsSquareNormalization() {
    JSONArray candidates = new JSONArray();
    candidates.put(
        candidate(
            "div#board.board.wgo-player-main", "selector:[class*=board]:hit", 45, 60, 687, 687));
    JSONObject grid = new JSONObject();
    grid.put("firstX", 81.1075);
    grid.put("firstY", 97.12);
    grid.put("cellX", 32.4602777778);
    grid.put("cellY", 31.4311111111);

    String command =
        OnlineDialog.describeAcceptedYikeGeometryCommand(candidates, grid, 1463, 800, 19);

    assertTrue(
        command.startsWith(
            "yikeGeometry left=45 top=60 width=687 height=687 board=19 firstX=81.1075"));
    assertTrue(command.contains(" firstY=97.12 "));
    assertTrue(command.contains(" cellX=32.460278 "));
    assertTrue(command.endsWith("cellY=31.431111"));
  }

  private static JSONObject candidate(
      String node, String reason, int left, int top, int width, int height) {
    JSONObject candidate = new JSONObject();
    candidate.put("node", node);
    candidate.put("reason", reason);
    candidate.put("rect", new JSONObject());
    candidate.getJSONObject("rect").put("left", left);
    candidate.getJSONObject("rect").put("top", top);
    candidate.getJSONObject("rect").put("width", width);
    candidate.getJSONObject("rect").put("height", height);
    return candidate;
  }

  private static JSONObject candidate(
      String node, String reason, String scope, int left, int top, int width, int height) {
    JSONObject candidate = candidate(node, reason, left, top, width, height);
    candidate.put("scope", scope);
    return candidate;
  }
}
