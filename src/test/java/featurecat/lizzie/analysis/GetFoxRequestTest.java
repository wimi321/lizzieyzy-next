package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
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

    assertTrue(normalizedSgf.contains("HA[4]"), normalizedSgf);
    assertTrue(normalizedSgf.contains("AB[pd][pq][dd][dp]"), normalizedSgf);
    assertFalse(normalizedSgf.contains("HA[0]"), normalizedSgf);
    assertTrue(normalizedSgf.contains(";W[qo];B[qp];W[op];B[oq])"), normalizedSgf);
    assertFalse(normalizedSgf.contains(";B[pd];B[pq]"), normalizedSgf);
  }
}
