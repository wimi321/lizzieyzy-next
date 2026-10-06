package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.Board;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class GetFoxRequestTest {
  @Test
  void normalizeFoxSgfPayloadPromotesLeadingSetupNodesIntoRoot() throws Exception {
    GetFoxRequest request = new GetFoxRequest(null);
    Method normalizeMethod =
        GetFoxRequest.class.getDeclaredMethod("normalizeFoxSgfPayload", String.class);
    normalizeMethod.setAccessible(true);

    String payload =
        new JSONObject()
            .put("chess", "(;GM[1]FF[4]SZ[19]KM[0]HA[2]PB[Black]PW[White];AB[pd][dp];W[pp];B[dd])")
            .toString();

    String normalizedPayload = (String) normalizeMethod.invoke(request, payload);
    String normalizedSgf = new JSONObject(normalizedPayload).getString("chess");

    assertTrue(normalizedSgf.startsWith("(;GM[1]FF[4]"), normalizedSgf);
    assertTrue(normalizedSgf.contains("HA[2]"), normalizedSgf);
    assertTrue(normalizedSgf.contains("AB[pd][dp]"), normalizedSgf);
    assertTrue(normalizedSgf.contains(";W[pp];B[dd])"), normalizedSgf);
    assertFalse(normalizedSgf.contains(";AB[pd][dp];"), normalizedSgf);
  }

  @Test
  void normalizeFoxSgfPayloadPromotesLeadingConsecutiveBlackMovesToHandicap() throws Exception {
    GetFoxRequest request = new GetFoxRequest(null);
    Method normalizeMethod =
        GetFoxRequest.class.getDeclaredMethod("normalizeFoxSgfPayload", String.class);
    normalizeMethod.setAccessible(true);

    String payload =
        new JSONObject()
            .put(
                "chess",
                "(;GM[1]FF[4]SZ[19]GN[]DT[2026-06-11]PB[随机2536]PW[廿月廿]BR[15级]WR[7段]"
                    + "KM[375]HA[0]RU[Chinese]AP[GNU Go:3.8]RN[3]RE[W+R]TM[300]TC[3]TT[30]"
                    + "AP[foxwq]RL[0];B[pd];B[pq];B[dd];B[dp];W[qo];B[qp];W[op];B[oq])")
            .toString();

    String normalizedPayload = (String) normalizeMethod.invoke(request, payload);
    String normalizedSgf = new JSONObject(normalizedPayload).getString("chess");

    assertTrue(normalizedSgf.contains("KM[0]"), normalizedSgf);
    assertFalse(normalizedSgf.contains("KM[375]"), normalizedSgf);
    assertTrue(normalizedSgf.contains("HA[4]"), normalizedSgf);
    assertTrue(normalizedSgf.contains("AB[pd][pq][dd][dp]"), normalizedSgf);
    assertFalse(normalizedSgf.contains("HA[0]"), normalizedSgf);
    assertTrue(normalizedSgf.contains(";W[qo];B[qp];W[op];B[oq])"), normalizedSgf);
    assertFalse(normalizedSgf.contains(";B[pd];B[pq]"), normalizedSgf);
  }

  @Test
  void readResponseBodyReturnsFullUtf8Payload() throws Exception {
    String payload = "{\"result\":0,\"fox_nickname\":\"野狐昵称\",\"chess\":\"(;GM[1])\"}";
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

    assertEquals(payload, GetFoxRequest.readResponseBody(new ByteArrayInputStream(bytes)));
  }

  @Test
  void readResponseBodyReturnsEmptyStringForEmptyBody() throws Exception {
    assertEquals("", GetFoxRequest.readResponseBody(new ByteArrayInputStream(new byte[0])));
  }

  @Test
  void readResponseBodyPropagatesMidBodyFailureInsteadOfTruncating() {
    InputStream broken =
        new InputStream() {
          private int reads;

          @Override
          public int read() throws IOException {
            if (reads++ < 5) {
              return 'x';
            }
            throw new IOException("connection reset mid-body");
          }
        };

    assertThrows(IOException.class, () -> GetFoxRequest.readResponseBody(broken));
  }

  @Test
  void emitDropsPayloadsAfterShutdown() throws Exception {
    RecordingFoxRequest request = new RecordingFoxRequest();
    Method emitMethod = GetFoxRequest.class.getDeclaredMethod("emit", String.class);
    emitMethod.setAccessible(true);

    emitMethod.invoke(request, "{\"result\":0,\"chesslist\":[]}");
    assertEquals(1, request.delivered.size());

    request.shutdown();
    emitMethod.invoke(request, "{\"result\":1,\"resultstr\":\"stale\"}");
    assertEquals(1, request.delivered.size());
  }

  @Test
  void errorPayloadsCarryTheFailedAction() throws Exception {
    RecordingFoxRequest request = new RecordingFoxRequest();
    Method emitErrorMethod =
        GetFoxRequest.class.getDeclaredMethod("emitError", String.class, String.class);
    emitErrorMethod.setAccessible(true);

    emitErrorMethod.invoke(request, "uid", "boom");
    request.shutdown();

    JSONObject delivered = new JSONObject(request.delivered.get(0));
    assertEquals(1, delivered.getInt("result"));
    assertEquals("uid", delivered.getString("fox_action"));
    assertEquals("boom", delivered.getString("resultstr"));
  }

  @Test
  void emptyHttpBodyBecomesTaggedErrorInsteadOfSilentDrop() throws Exception {
    RecordingFoxRequest request = new RecordingFoxRequest();
    Method emitOrFailMethod =
        GetFoxRequest.class.getDeclaredMethod("emitOrFail", String.class, String.class);
    emitOrFailMethod.setAccessible(true);

    emitOrFailMethod.invoke(request, "uid", "   ");
    request.shutdown();

    assertEquals(1, request.delivered.size());
    JSONObject delivered = new JSONObject(request.delivered.get(0));
    assertEquals(1, delivered.getInt("result"));
    assertEquals("uid", delivered.getString("fox_action"));
  }

  @Test
  void mergeRecoveryOnlyRunsWhenPayloadMatchesLiveBoardSize() {
    assertTrue(GetFoxRequest.mergeRecoveryMatchesLiveBoard(Board.boardWidth, Board.boardHeight));
    assertFalse(
        GetFoxRequest.mergeRecoveryMatchesLiveBoard(Board.boardWidth + 1, Board.boardHeight));
    assertFalse(
        GetFoxRequest.mergeRecoveryMatchesLiveBoard(Board.boardWidth, Board.boardHeight + 1));
  }

  @Test
  void nicknameSearchResolvesEveryNicknameShapeBeforeFetchingThatAccountsGames() throws Exception {
    String[] nicknames = {"00123456", "123456789012345678901234", "\u68CB\u624B", "abc", "ab12"};
    for (String nickname : nicknames) {
      ScriptedFoxRequest request =
          new ScriptedFoxRequest()
              .respond(
                  "QueryUserInfoPanel",
                  new JSONObject()
                      .put("result", 0)
                      .put("uid", "900001")
                      .put("username", nickname)
                      .toString())
              .respond("YHWQFetchChessList", "{\"result\":0,\"chesslist\":[]}");

      runCommand(request, "user_name " + nickname);

      assertEquals(2, request.urls.size(), nickname);
      assertEquals(nickname, queryParam(request.urls.get(0), "username"), nickname);
      assertEquals("900001", queryParam(request.urls.get(1), "dstuid"), nickname);
      JSONObject payload = new JSONObject(request.delivered.get(0));
      assertEquals("900001", payload.getString("fox_uid"), nickname);
      assertEquals(nickname, payload.getString("fox_nickname"), nickname);
      assertEquals(nickname, payload.getString("fox_query"), nickname);
    }
  }

  @Test
  void unknownNicknameFailsAsUserNotFoundWithoutFetchingAnyGames() throws Exception {
    ScriptedFoxRequest request =
        new ScriptedFoxRequest()
            .respond("QueryUserInfoPanel", "{\"result\":101201,\"uid\":\"0\",\"username\":\"\"}");

    runCommand(request, "user_name 123456");

    assertEquals(1, request.urls.size());
    JSONObject payload = new JSONObject(request.delivered.get(0));
    assertEquals(1, payload.getInt("result"));
    assertEquals("user_name", payload.getString("fox_action"));
    assertEquals(GetFoxRequest.USER_NOT_FOUND_ERROR, payload.getString("fox_error"));
  }

  @Test
  void transportFailureIsNotReportedAsUnknownAccount() throws Exception {
    ScriptedFoxRequest request = new ScriptedFoxRequest();
    request.failure = new RuntimeException("connect timed out");

    runCommand(request, "user_name 123456");

    JSONObject payload = new JSONObject(request.delivered.get(0));
    assertEquals(1, payload.getInt("result"));
    assertEquals("user_name", payload.getString("fox_action"));
    assertFalse(payload.has("fox_error"));
  }

  @Test
  void explicitUidSearchFetchesThatUidAndCarriesItsIdentity() throws Exception {
    ScriptedFoxRequest request =
        new ScriptedFoxRequest()
            .respond("YHWQFetchChessList", "{\"result\":0,\"chesslist\":[]}");

    runCommand(request, "account_uid 123456 00123456");

    assertEquals(1, request.urls.size());
    assertEquals("123456", queryParam(request.urls.get(0), "dstuid"));
    JSONObject payload = new JSONObject(request.delivered.get(0));
    assertEquals("123456", payload.getString("fox_uid"));
    assertEquals("00123456", payload.getString("fox_nickname"));
    assertFalse(payload.has("fox_action"));
  }

  @Test
  void explicitUidSearchRejectsNonNumericUidWithoutRequesting() throws Exception {
    ScriptedFoxRequest request = new ScriptedFoxRequest();

    runCommand(request, "account_uid 12a34");

    assertTrue(request.urls.isEmpty());
    JSONObject payload = new JSONObject(request.delivered.get(0));
    assertEquals(1, payload.getInt("result"));
    assertEquals("account_uid", payload.getString("fox_action"));
  }

  private static void runCommand(GetFoxRequest request, String command) throws Exception {
    Method handleCommand = GetFoxRequest.class.getDeclaredMethod("handleCommand", String.class);
    handleCommand.setAccessible(true);
    try {
      handleCommand.invoke(request, command);
    } finally {
      request.shutdown();
    }
  }

  private static String queryParam(String url, String name) {
    for (String pair : URI.create(url).getRawQuery().split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(name)) {
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static final class RecordingFoxRequest extends GetFoxRequest {
    final List<String> delivered = new ArrayList<String>();

    RecordingFoxRequest() {
      super(null);
    }

    @Override
    void deliver(String payload) {
      delivered.add(payload);
    }
  }

  private static final class ScriptedFoxRequest extends GetFoxRequest {
    final List<String> urls = new ArrayList<String>();
    final List<String> delivered = new ArrayList<String>();
    private final Map<String, String> responses = new LinkedHashMap<String, String>();
    RuntimeException failure;

    ScriptedFoxRequest() {
      super(null);
    }

    ScriptedFoxRequest respond(String endpoint, String body) {
      responses.put(endpoint, body);
      return this;
    }

    @Override
    String httpGet(String url) {
      urls.add(url);
      if (failure != null) {
        throw failure;
      }
      for (Map.Entry<String, String> response : responses.entrySet()) {
        if (url.contains("/" + response.getKey() + "?")) {
          return response.getValue();
        }
      }
      throw new AssertionError("unexpected Fox request: " + url);
    }

    @Override
    void deliver(String payload) {
      delivered.add(payload);
    }
  }
}
