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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
  void mergeRecoveryOnlyRunsWhenPayloadMatchesLiveBoardSize() {
    assertTrue(GetFoxRequest.mergeRecoveryMatchesLiveBoard(Board.boardWidth, Board.boardHeight));
    assertFalse(
        GetFoxRequest.mergeRecoveryMatchesLiveBoard(Board.boardWidth + 1, Board.boardHeight));
    assertFalse(
        GetFoxRequest.mergeRecoveryMatchesLiveBoard(Board.boardWidth, Board.boardHeight + 1));
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
}
