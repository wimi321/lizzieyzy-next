package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.gui.YikeApiClient.YikeLiveDetail;
import featurecat.lizzie.gui.YikeApiClient.YikeLiveGame;
import featurecat.lizzie.gui.YikeApiClient.YikeLivePage;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YikeApiClientTest {
  @Test
  void parseLiveListMapsCurrentYikeFields() throws Exception {
    String response =
        "{"
            + "\"Status\":1200,"
            + "\"Result\":{\"since\":186031,\"list\":[{"
            + "\"Id\":186031,"
            + "\"Version\":2,"
            + "\"GameName\":\"LG Cup\","
            + "\"BlackName\":\"Black Player\","
            + "\"WhiteName\":\"White Player\","
            + "\"BlackCounty\":\"KR\","
            + "\"WhiteCounty\":\"CN\","
            + "\"Status\":2,"
            + "\"FinishOrder\":\"2026-04-27\","
            + "\"BroadcastTime\":\"20:07\","
            + "\"HandsCount\":100,"
            + "\"PersonTimes\":5325,"
            + "\"RealtimeAnalysisFlag\":1,"
            + "\"BlackWinRate\":67.8,"
            + "\"Delta\":2.5,"
            + "\"hall\":0,"
            + "\"room\":0"
            + "}]},"
            + "\"Message\":\"\""
            + "}";

    YikeLivePage page = YikeApiClient.parseLiveList(response);

    assertEquals(186031, page.getSince());
    assertEquals(1, page.getGames().size());
    YikeLiveGame game = page.getGames().get(0);
    assertEquals(186031, game.getId());
    assertEquals("LG Cup", game.getGameName());
    assertEquals("Black Player", game.getBlackName());
    assertEquals("White Player", game.getWhiteName());
    assertEquals(100, game.getHandsCount());
    assertEquals("2026-04-27 20:07", game.timeText());
    assertEquals("https://home.yikeweiqi.com/#/live/new-room/186031/0/0", game.toRoomUrl());
  }

  @Test
  void parseLiveDetailReadsNewRoomSgf() throws Exception {
    String response =
        "{"
            + "\"status\":0,"
            + "\"message\":\"\","
            + "\"result\":{"
            + "\"id\":186031,"
            + "\"sgf\":\"(;GM[1]SZ[19];B[aa];W[bb])\","
            + "\"status\":2,"
            + "\"game_result\":\"\""
            + "}"
            + "}";

    YikeLiveDetail detail = YikeApiClient.parseLiveDetail(response);

    assertEquals("(;GM[1]SZ[19];B[aa];W[bb])", detail.getSgf());
    assertEquals(2, detail.getStatus());
  }

  @Test
  void previewWithoutMovesDoesNotStartAWebSyncSession() throws Exception {
    String response =
        "{\"Status\":1200,\"Result\":{\"since\":1,\"list\":[{"
            + "\"Id\":1,\"Version\":2,\"GameName\":\"Upcoming\","
            + "\"Status\":1,\"HandsCount\":0,\"hall\":0,\"room\":0"
            + "}]}}";

    YikeLiveGame preview = YikeApiClient.parseLiveList(response).getGames().get(0);

    assertTrue(preview.isPreviewWithoutMoves());
    assertFalse(YikeLiveDialog.canStartSync(preview));
  }

  @Test
  void liveRoomCanBeWatchedBeforeItsFirstMoveArrives() throws Exception {
    String response =
        "{\"Status\":1200,\"Result\":{\"since\":2,\"list\":[{"
            + "\"Id\":2,\"Version\":2,\"GameName\":\"Starting\","
            + "\"Status\":2,\"HandsCount\":0,\"hall\":0,\"room\":0"
            + "}]}}";

    YikeLiveGame live = YikeApiClient.parseLiveList(response).getGames().get(0);

    assertFalse(live.isPreviewWithoutMoves());
    assertTrue(YikeLiveDialog.canStartSync(live));
  }

  @Test
  void parseLiveDetailFallsBackToCleanSgf() throws Exception {
    String response =
        "{"
            + "\"status\":0,"
            + "\"message\":\"\","
            + "\"result\":{"
            + "\"clean_sgf\":\"(;GM[1]SZ[19];B[cc])\","
            + "\"status\":3,"
            + "\"game_result\":\"B+R\""
            + "}"
            + "}";

    YikeLiveDetail detail = YikeApiClient.parseLiveDetail(response);

    assertEquals("(;GM[1]SZ[19];B[cc])", detail.getSgf());
    assertEquals(3, detail.getStatus());
    assertEquals("B+R", detail.getResult());
  }

  @Test
  void headerSignatureMatchesYikeWebClientAlgorithm() {
    Map<String, String> headers = YikeApiClient.buildHeadersForTest(1000, 12345);

    assertEquals("3396jtzhK57XhJom", headers.get("AppKey"));
    assertEquals("1000", headers.get("CurTime"));
    assertEquals("12345", headers.get("Nonce"));
    assertEquals("12d497b28b684dc903cccbe060271b17571d1d5d", headers.get("CheckSum"));
    assertEquals("15d1693d60abd643f7c680175a52b2b9", headers.get("accesstoken"));
    assertEquals("-1", headers.get("usertoken"));
    assertEquals("web", headers.get("Platform"));
  }
}
