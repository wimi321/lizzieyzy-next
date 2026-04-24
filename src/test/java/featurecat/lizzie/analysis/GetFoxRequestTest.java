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
}
