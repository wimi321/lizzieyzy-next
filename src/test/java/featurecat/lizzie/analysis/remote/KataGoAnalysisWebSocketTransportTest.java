package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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

  @Test
  void reportsKataGoCompatibleHandshakeForLizzieStartup() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");

    assertEquals("= KataGo\n\n", sendGtp(transport, "name"));
    assertEquals("= 1.16.4\n\n", sendGtp(transport, "version"));

    String commands = sendGtp(transport, "list_commands");
    assertTrue(commands.contains("kata-analyze"));
    assertTrue(commands.contains("kata-get-param"));
    assertTrue(commands.contains("kata-get-rules"));

    assertEquals("= 0.0\n\n", sendGtp(transport, "kata-get-param playoutDoublingAdvantage"));
    assertEquals("= 0.04\n\n", sendGtp(transport, "kata-get-param analysisWideRootNoise"));
    assertTrue(sendGtp(transport, "kata-get-rules").contains("\"scoring\":\"AREA\""));
  }

  private static String sendGtp(KataGoAnalysisWebSocketTransport transport, String command)
      throws Exception {
    OutputStream stdin = transport.stdin();
    stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
    stdin.flush();
    InputStream stdout = transport.stdout();
    long deadline = System.currentTimeMillis() + 1000;
    while (stdout.available() == 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    int available = stdout.available();
    assertTrue(available > 0, "no GTP response for " + command);
    byte[] buffer = new byte[available];
    int read = stdout.read(buffer);
    return new String(buffer, 0, read, StandardCharsets.UTF_8);
  }
}
