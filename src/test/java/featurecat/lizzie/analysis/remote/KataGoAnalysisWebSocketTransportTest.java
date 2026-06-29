package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class KataGoAnalysisWebSocketTransportTest {
  @Test
  void convertsKataGoAnalysisJsonToGtpInfoLine() {
    JSONObject response = new JSONObject();
    response.put(
        "moveInfos",
        new JSONArray()
            .put(
                new JSONObject()
                    .put("move", "Q16")
                    .put("visits", 42)
                    .put("winrate", 0.62)
                    .put("scoreLead", 3.5)
                    .put("prior", 0.12)
                    .put("order", 0)
                    .put("pv", new JSONArray().put("Q16").put("D4"))));

    String line = KataGoAnalysisWebSocketTransport.toGtpInfoLine(response);

    assertEquals(
        "info move Q16 visits 42 winrate 0.620000 scoreLead 3.500000 prior 0.120000 order 0 pv Q16 D4",
        line);
  }

  @Test
  void includesOwnershipValuesWhenPresent() {
    JSONObject response = new JSONObject();
    response.put(
        "moveInfos",
        new JSONArray().put(new JSONObject().put("move", "pass").put("visits", 1)));
    response.put("ownership", new JSONArray().put(0.25).put(-0.5));

    String line = KataGoAnalysisWebSocketTransport.toGtpInfoLine(response);

    assertTrue(line.startsWith("info move pass visits 1 order 0 ownership "));
    assertTrue(line.contains("0.250000 -0.500000"));
  }
}
