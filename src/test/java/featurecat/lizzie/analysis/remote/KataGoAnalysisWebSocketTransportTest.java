package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

public class KataGoAnalysisWebSocketTransportTest {
  @Test
  void streamsNumberedAnalyzeAsOneCompleteGtpResponse() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      writeGtp(harness.transport, "7 kata-analyze B 10");
      JSONObject query = harness.webSocket.sentJson(0);
      String queryId = query.getString("id");

      assertEquals("=7\n", readAvailable(harness.transport.stdout()));

      harness.webSocket.emit(analysisResponse(queryId, true, "Q16", 12));
      harness.webSocket.emit(analysisResponse(queryId, false, "D4", 24));

      assertEquals(
          "info move Q16 visits 12 order 0\n"
              + "info move D4 visits 24 order 0\n\n",
          readAvailable(harness.transport.stdout()));
    }
  }

  @Test
  void acknowledgesStopAfterTerminateAckThenAnalysisFinal() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      writeGtp(harness.transport, "7 kata-analyze B 10");
      String queryId = harness.webSocket.sentJson(0).getString("id");
      assertEquals("=7\n", readAvailable(harness.transport.stdout()));

      writeGtp(harness.transport, "8 stop");
      JSONObject terminate = harness.webSocket.sentJson(1);
      assertEquals("terminate", terminate.getString("action"));
      assertEquals(queryId, terminate.getString("terminateId"));
      assertEquals("", readAvailable(harness.transport.stdout()));

      harness.webSocket.emit(terminate);
      assertEquals("", readAvailable(harness.transport.stdout()));
      harness.webSocket.emit(finalWithoutResults(queryId));
      assertEquals("\n=8\n\n", readAvailable(harness.transport.stdout()));

      harness.webSocket.emit(terminate);
      harness.webSocket.emit(finalWithoutResults(queryId));
      assertEquals("", readAvailable(harness.transport.stdout()));
    }
  }

  @Test
  void acknowledgesStopAfterAnalysisFinalThenTerminateAck() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      writeGtp(harness.transport, "17 kata-analyze W 10");
      String queryId = harness.webSocket.sentJson(0).getString("id");
      assertEquals("=17\n", readAvailable(harness.transport.stdout()));

      writeGtp(harness.transport, "18 stop");
      JSONObject terminate = harness.webSocket.sentJson(1);
      harness.webSocket.emit(finalWithoutResults(queryId));
      assertEquals("\n", readAvailable(harness.transport.stdout()));

      harness.webSocket.emit(terminate);
      assertEquals("=18\n\n", readAvailable(harness.transport.stdout()));

      harness.webSocket.emit(finalWithoutResults(queryId));
      harness.webSocket.emit(terminate);
      assertEquals("", readAvailable(harness.transport.stdout()));
    }
  }

  @Test
  void buffersRemoteResponseUntilAnalyzeSendSucceeds() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      CompletableFuture<WebSocket> sendResult = harness.webSocket.deferNextSend();
      writeGtp(harness.transport, "27 kata-analyze B 10");
      String queryId = harness.webSocket.sentJson(0).getString("id");

      harness.webSocket.emit(analysisResponse(queryId, true, "Q16", 12));
      assertEquals("", readAvailable(harness.transport.stdout()));
      assertTrue(harness.transport.isOpen());

      sendResult.complete(harness.webSocket);
      assertEquals(
          "=27\ninfo move Q16 visits 12 order 0\n", readAvailable(harness.transport.stdout()));
    }
  }

  @Test
  void activeQueryKeepsItsParameterSnapshotAndLaterQueriesUseCommittedValues() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      assertEquals("=\n\n", sendGtp(harness.transport, "kata-set-param maxTime 1.25"));
      assertEquals("=\n\n", sendGtp(harness.transport, "kata-set-param maxVisits 125"));
      assertEquals(
          "=\n\n", sendGtp(harness.transport, "kata-set-param playoutDoublingAdvantage -1"));
      assertEquals(
          "=\n\n", sendGtp(harness.transport, "kata-set-param analysisWideRootNoise 0.25"));

      CompletableFuture<WebSocket> sendResult = harness.webSocket.deferNextSend();
      writeGtp(harness.transport, "201 genmove B");
      JSONObject active = harness.webSocket.sentJson(0);
      assertEquals(125L, active.getLong("maxVisits"));
      assertEquals(1.25, active.getJSONObject("overrideSettings").getDouble("maxTime"));
      assertEquals(-1.0, active.getDouble("playoutDoublingAdvantage"));

      assertEquals("=211\n\n", sendGtp(harness.transport, "211 kata-set-param maxTime 2.5"));
      assertEquals("=212\n\n", sendGtp(harness.transport, "212 kata-set-param maxVisits 250"));
      assertEquals(
          "=213\n\n", sendGtp(harness.transport, "213 kata-set-param playoutDoublingAdvantage 2"));
      assertEquals(
          "=214\n\n", sendGtp(harness.transport, "214 kata-set-param analysisWideRootNoise 0.5"));

      assertEquals(125L, active.getLong("maxVisits"));
      assertEquals(1.25, active.getJSONObject("overrideSettings").getDouble("maxTime"));
      assertEquals(-1.0, active.getDouble("playoutDoublingAdvantage"));
      assertEquals(1, harness.webSocket.sentTexts.size());

      sendResult.complete(harness.webSocket);
      assertEquals("", readAvailable(harness.transport.stdout()));
      harness.webSocket.emit(analysisResponse(active.getString("id"), false, "Q16", 125));
      assertEquals(
          "info move Q16 visits 125 order 0\n=201 Q16\n\n",
          readAvailable(harness.transport.stdout()));

      writeGtp(harness.transport, "221 genmove W");
      JSONObject nextMove = harness.webSocket.sentJson(1);
      assertEquals(250L, nextMove.getLong("maxVisits"));
      assertEquals(2.5, nextMove.getJSONObject("overrideSettings").getDouble("maxTime"));
      assertEquals(2.0, nextMove.getDouble("playoutDoublingAdvantage"));
      harness.webSocket.emit(analysisResponse(nextMove.getString("id"), false, "D4", 250));
      assertEquals(
          "info move D4 visits 250 order 0\n=221 D4\n\n",
          readAvailable(harness.transport.stdout()));

      writeGtp(harness.transport, "231 kata-analyze B 10");
      JSONObject nextAnalysis = harness.webSocket.sentJson(2);
      assertEquals(2.0, nextAnalysis.getDouble("playoutDoublingAdvantage"));
      assertEquals(0.5, nextAnalysis.getJSONObject("overrideSettings").getDouble("wideRootNoise"));
      assertEquals("=231\n", readAvailable(harness.transport.stdout()));
      harness.webSocket.emit(finalWithoutResults(nextAnalysis.getString("id")));
      assertEquals("\n", readAvailable(harness.transport.stdout()));
    }
  }

  @Test
  void parameterUpdateAndStopDuringPendingSendKeepOneTerminalResponse() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      CompletableFuture<WebSocket> sendResult = harness.webSocket.deferNextSend();
      writeGtp(harness.transport, "301 kata-analyze B 10");
      JSONObject active = harness.webSocket.sentJson(0);

      assertEquals(
          "=311\n\n",
          sendGtp(harness.transport, "311 kata-set-param playoutDoublingAdvantage 1.5"));
      writeGtp(harness.transport, "302 stop");
      JSONObject terminate = harness.webSocket.sentJson(1);
      harness.webSocket.emit(terminate);
      harness.webSocket.emit(finalWithoutResults(active.getString("id")));
      assertEquals("", readAvailable(harness.transport.stdout()));

      sendResult.complete(harness.webSocket);
      assertEquals("=301\n\n=302\n\n", readAvailable(harness.transport.stdout()));

      harness.webSocket.emit(terminate);
      harness.webSocket.emit(finalWithoutResults(active.getString("id")));
      assertEquals("", readAvailable(harness.transport.stdout()));
      assertEquals(2, harness.webSocket.sentTexts.size());

      writeGtp(harness.transport, "303 kata-analyze W 10");
      JSONObject next = harness.webSocket.sentJson(2);
      assertEquals(1.5, next.getDouble("playoutDoublingAdvantage"));
      assertEquals("=303\n", readAvailable(harness.transport.stdout()));
      harness.webSocket.emit(finalWithoutResults(next.getString("id")));
      assertEquals("\n", readAvailable(harness.transport.stdout()));
    }
  }

  @Test
  void unnumberedStopStillWaitsForBothRemoteTerminalEvents() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      writeGtp(harness.transport, "kata-analyze B 10");
      String queryId = harness.webSocket.sentJson(0).getString("id");
      assertEquals("=\n", readAvailable(harness.transport.stdout()));

      writeGtp(harness.transport, "stop");
      JSONObject terminate = harness.webSocket.sentJson(1);
      harness.webSocket.emit(finalWithoutResults(queryId));
      assertEquals("\n", readAvailable(harness.transport.stdout()));

      harness.webSocket.emit(terminate);
      assertEquals("=\n\n", readAvailable(harness.transport.stdout()));
    }
  }

  @Test
  void duplicateStopAndTerminalEventsCompleteOnlyOnce() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      writeGtp(harness.transport, "147 kata-analyze B 10");
      String queryId = harness.webSocket.sentJson(0).getString("id");
      assertEquals("=147\n", readAvailable(harness.transport.stdout()));

      writeGtp(harness.transport, "148 stop");
      JSONObject terminate = harness.webSocket.sentJson(1);
      writeGtp(harness.transport, "148 stop");
      assertEquals(2, harness.webSocket.sentTexts.size());

      harness.webSocket.emit(terminate);
      harness.webSocket.emit(terminate);
      harness.webSocket.emit(finalWithoutResults(queryId));
      harness.webSocket.emit(finalWithoutResults(queryId));

      assertEquals("\n=148\n\n", readAvailable(harness.transport.stdout()));
    }
  }

  @Test
  void lateResponseRemainsIgnoredAfterMoreThanRecentHistoryWindow() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      JSONObject firstFinal = null;
      for (int index = 0; index < 70; index++) {
        writeGtp(harness.transport, "kata-analyze B 10");
        String queryId = harness.webSocket.sentJson(index).getString("id");
        JSONObject terminal = finalWithoutResults(queryId);
        if (firstFinal == null) {
          firstFinal = terminal;
        }
        harness.webSocket.emit(terminal);
        assertEquals("=\n\n", readAvailable(harness.transport.stdout()));
      }

      writeGtp(harness.transport, "177 kata-analyze B 10");
      String activeQueryId = harness.webSocket.sentJson(70).getString("id");
      assertEquals("=177\n", readAvailable(harness.transport.stdout()));

      harness.webSocket.emit(firstFinal);
      assertTrue(harness.transport.isOpen());
      assertEquals("", readAvailable(harness.transport.stdout()));

      harness.webSocket.emit(finalWithoutResults(activeQueryId));
      assertEquals("\n", readAvailable(harness.transport.stdout()));
    }
  }

  @Test
  void analyzeSendFailureReturnsOneErrorBeforeHeaderAndClosesStreams() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      harness.webSocket.failNextSend(new IOException("send failed"));

      writeGtp(harness.transport, "37 kata-analyze B 10");

      String stdout = readAvailable(harness.transport.stdout());
      assertTrue(stdout.startsWith("?37 "));
      assertTrue(stdout.contains("send failed"));
      assertEof(harness.transport.stdout());
      String stderr = readAvailable(harness.transport.stderr());
      assertTrue(stderr.contains("send failed"));
      assertEof(harness.transport.stderr());
      assertFalse(harness.transport.isOpen());
    }
  }

  @Test
  void bufferedRemoteErrorBeforeHeaderReturnsOneGtpError() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      CompletableFuture<WebSocket> sendResult = harness.webSocket.deferNextSend();
      writeGtp(harness.transport, "47 kata-analyze B 10");
      String queryId = harness.webSocket.sentJson(0).getString("id");
      harness.webSocket.emit(new JSONObject().put("id", queryId).put("error", "bad query"));

      sendResult.complete(harness.webSocket);

      String stdout = readAvailable(harness.transport.stdout());
      assertTrue(stdout.startsWith("?47 "));
      assertTrue(stdout.contains("bad query"));
      assertFalse(stdout.startsWith("=47"));
      assertEof(harness.transport.stdout());
    }
  }

  @Test
  void remoteErrorAfterHeaderEndsStreamWithoutInjectingSecondGtpResponse() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      writeGtp(harness.transport, "57 kata-analyze B 10");
      String queryId = harness.webSocket.sentJson(0).getString("id");
      assertEquals("=57\n", readAvailable(harness.transport.stdout()));

      harness.webSocket.emit(new JSONObject().put("id", queryId).put("error", "remote failed"));

      assertEquals("", readAvailable(harness.transport.stdout()));
      assertEof(harness.transport.stdout());
      assertTrue(readAvailable(harness.transport.stderr()).contains("remote failed"));
      assertEof(harness.transport.stderr());
      assertFalse(harness.transport.isOpen());
    }
  }

  @Test
  void malformedJsonAfterHeaderTerminatesTransport() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      writeGtp(harness.transport, "67 kata-analyze B 10");
      assertEquals("=67\n", readAvailable(harness.transport.stdout()));

      harness.webSocket.emitRaw("{not-json");

      assertEof(harness.transport.stdout());
      assertTrue(readAvailable(harness.transport.stderr()).contains("无法解析"));
      assertEof(harness.transport.stderr());
    }
  }

  @Test
  void missingQueryIdAfterHeaderTerminatesTransport() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      writeGtp(harness.transport, "77 kata-analyze B 10");
      assertEquals("=77\n", readAvailable(harness.transport.stdout()));

      harness.webSocket.emit(new JSONObject().put("isDuringSearch", false));

      assertEof(harness.transport.stdout());
      assertTrue(readAvailable(harness.transport.stderr()).contains("缺少查询 ID"));
    }
  }

  @Test
  void wrongQueryIdAfterHeaderTerminatesTransport() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      writeGtp(harness.transport, "87 kata-analyze B 10");
      assertEquals("=87\n", readAvailable(harness.transport.stdout()));

      harness.webSocket.emit(
          new JSONObject().put("id", "other-query").put("isDuringSearch", false));

      assertEof(harness.transport.stdout());
      assertTrue(readAvailable(harness.transport.stderr()).contains("错误的查询 ID"));
    }
  }

  @Test
  void missingSearchStateAfterHeaderTerminatesTransport() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      writeGtp(harness.transport, "97 kata-analyze B 10");
      String queryId = harness.webSocket.sentJson(0).getString("id");
      assertEquals("=97\n", readAvailable(harness.transport.stdout()));

      harness.webSocket.emit(new JSONObject().put("id", queryId).put("turnNumber", 0));

      assertEof(harness.transport.stdout());
      assertTrue(readAvailable(harness.transport.stderr()).contains("isDuringSearch"));
    }
  }

  @Test
  void terminateSendFailureAfterHeaderClosesWithoutStopResponse() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      writeGtp(harness.transport, "107 kata-analyze B 10");
      assertEquals("=107\n", readAvailable(harness.transport.stdout()));
      harness.webSocket.failNextSend(new IOException("terminate failed"));

      writeGtp(harness.transport, "108 stop");

      assertEquals("", readAvailable(harness.transport.stdout()));
      assertEof(harness.transport.stdout());
      assertTrue(readAvailable(harness.transport.stderr()).contains("terminate failed"));
      assertFalse(harness.transport.isOpen());
    }
  }

  @Test
  void incompleteTerminateAckAfterHeaderTerminatesTransport() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      writeGtp(harness.transport, "157 kata-analyze B 10");
      assertEquals("=157\n", readAvailable(harness.transport.stdout()));
      writeGtp(harness.transport, "158 stop");
      JSONObject terminate = harness.webSocket.sentJson(1);

      harness.webSocket.emit(
          new JSONObject().put("id", terminate.getString("id")).put("action", "terminate"));

      assertEof(harness.transport.stdout());
      assertTrue(readAvailable(harness.transport.stderr()).contains("不完整的停止回执"));
      assertFalse(harness.transport.isOpen());
    }
  }

  @Test
  void webSocketCloseFinishesStdoutAndStderr() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      writeGtp(harness.transport, "117 kata-analyze B 10");
      String queryId = harness.webSocket.sentJson(0).getString("id");
      assertEquals("=117\n", readAvailable(harness.transport.stdout()));

      harness.webSocket.closeFromServer(1006, "lost");

      assertEof(harness.transport.stdout());
      assertTrue(readAvailable(harness.transport.stderr()).contains("1006 lost"));
      assertEof(harness.transport.stderr());
      assertFalse(harness.transport.isOpen());
      harness.webSocket.emit(finalWithoutResults(queryId));
      assertEof(harness.transport.stdout());
    }
  }

  @Test
  void webSocketErrorFinishesStdoutAndStderr() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      writeGtp(harness.transport, "127 kata-analyze B 10");
      String queryId = harness.webSocket.sentJson(0).getString("id");
      assertEquals("=127\n", readAvailable(harness.transport.stdout()));

      harness.webSocket.errorFromServer(new IOException("socket failed"));

      assertEof(harness.transport.stdout());
      assertTrue(readAvailable(harness.transport.stderr()).contains("socket failed"));
      assertEof(harness.transport.stderr());
      assertFalse(harness.transport.isOpen());
      harness.webSocket.emit(finalWithoutResults(queryId));
      assertEof(harness.transport.stdout());
    }
  }

  @Test
  void explicitCloseIsIdempotentAndFinishesStdoutAndStderr() throws Exception {
    TransportHarness harness = TransportHarness.open();
    writeGtp(harness.transport, "137 kata-analyze B 10");
    String queryId = harness.webSocket.sentJson(0).getString("id");
    assertEquals("=137\n", readAvailable(harness.transport.stdout()));

    harness.transport.close();
    harness.transport.close();

    assertEof(harness.transport.stdout());
    assertEof(harness.transport.stderr());
    assertFalse(harness.transport.isOpen());
    assertEquals(1, harness.webSocket.closeCount);
    harness.webSocket.emit(finalWithoutResults(queryId));
    assertEof(harness.transport.stdout());
  }

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
        "moveInfos", new JSONArray().put(new JSONObject().put("move", "pass").put("visits", 1)));
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
    assertTrue(commands.contains("set_position"));
    assertTrue(commands.contains("loadsgf"));

    assertEquals("= 0.0\n\n", sendGtp(transport, "kata-get-param playoutDoublingAdvantage"));
    assertEquals("= 0.04\n\n", sendGtp(transport, "kata-get-param analysisWideRootNoise"));
    String rules = sendGtp(transport, "kata-get-rules");
    assertTrue(rules.contains("\"scoring\":\"AREA\""));
    assertTrue(rules.contains("\"ko\":\"SIMPLE\""));
    assertTrue(rules.contains("\"friendlyPassOk\":true"));
  }

  @Test
  void advertisesOnlySupportedWebSocketTimeCommands() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");

    String commands = sendGtp(transport, "list_commands");

    assertTrue(commands.contains("\nkata-list_time_settings\n"));
    assertTrue(commands.contains("\nkata-time_settings\n"));
    assertFalse(commands.contains("\ntime_settings\n"));
    assertFalse(commands.contains("\ntime_left\n"));
    assertEquals("=71 none\n\n", sendGtp(transport, "71 kata-list_time_settings"));
  }

  @Test
  void noneClockModeKeepsIndependentFixedMoveTime() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");

    assertEquals("=\n\n", sendGtp(transport, "kata-set-param maxTime 2.5"));

    assertEquals("=72\n\n", sendGtp(transport, "72 kata-time_settings none"));
    assertEquals("=73 2.5\n\n", sendGtp(transport, "73 kata-get-param maxTime"));
  }

  @Test
  void rejectsUnsupportedClockCommandsWithIdsWithoutChangingMoveLimits() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");
    assertEquals("=\n\n", sendGtp(transport, "kata-set-param maxTime 3.5"));
    assertEquals("=\n\n", sendGtp(transport, "kata-set-param maxVisits 777"));

    assertEquals(
        "?74 websocket analysis adapter supports only kata-time_settings none\n\n",
        sendGtp(transport, "74 kata-time_settings absolute 60"));
    assertEquals(
        "?75 time_settings unsupported by websocket analysis adapter\n\n",
        sendGtp(transport, "75 time_settings 60 10 1"));
    assertEquals(
        "?76 time_left unsupported by websocket analysis adapter\n\n",
        sendGtp(transport, "76 time_left B 30 1"));
    assertEquals(
        "?77 websocket analysis adapter supports only kata-time_settings none\n\n",
        sendGtp(transport, "77 kata-time_settings"));

    assertEquals("= 3.5\n\n", sendGtp(transport, "kata-get-param maxTime"));
    assertEquals("= 777\n\n", sendGtp(transport, "kata-get-param maxVisits"));
  }

  @Test
  void moveSearchLimitsRoundTripAndResetToAdapterBaselines() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");

    assertEquals("= 1.0E20\n\n", sendGtp(transport, "kata-get-param maxTime"));
    assertEquals("= 256\n\n", sendGtp(transport, "kata-get-param maxVisits"));

    assertEquals("=31\n\n", sendGtp(transport, "31 kata-set-param maxTime 2.5"));
    assertEquals("=32 2.5\n\n", sendGtp(transport, "32 kata-get-param maxTime"));
    assertEquals("=33\n\n", sendGtp(transport, "33 kata-set-param maxVisits 4096"));
    assertEquals("=34 4096\n\n", sendGtp(transport, "34 kata-get-param maxVisits"));

    assertEquals("=35\n\n", sendGtp(transport, "35 kata-set-param maxTime"));
    assertEquals("=36 1.0E20\n\n", sendGtp(transport, "36 kata-get-param maxTime"));
    assertEquals("=37\n\n", sendGtp(transport, "37 kata-set-param maxVisits"));
    assertEquals("=38 256\n\n", sendGtp(transport, "38 kata-get-param maxVisits"));
  }

  @Test
  void rejectsInvalidParameterUpdatesWithoutChangingEffectiveValues() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");

    assertEquals("=\n\n", sendGtp(transport, "kata-set-param maxTime 12.5"));
    assertTrue(sendGtp(transport, "41 kata-set-param maxTime NaN").startsWith("?41 "));
    assertTrue(sendGtp(transport, "42 kata-set-param maxTime Infinity").startsWith("?42 "));
    assertTrue(sendGtp(transport, "43 kata-set-param maxTime -1").startsWith("?43 "));
    assertTrue(sendGtp(transport, "44 kata-set-param maxTime 1.1e20").startsWith("?44 "));
    assertTrue(sendGtp(transport, "45 kata-set-param maxTime nope").startsWith("?45 "));
    assertEquals("= 12.5\n\n", sendGtp(transport, "kata-get-param maxTime"));

    assertEquals("=\n\n", sendGtp(transport, "kata-set-param maxVisits 99"));
    assertTrue(sendGtp(transport, "51 kata-set-param maxVisits 0").startsWith("?51 "));
    assertTrue(sendGtp(transport, "52 kata-set-param maxVisits 1.5").startsWith("?52 "));
    assertTrue(
        sendGtp(transport, "53 kata-set-param maxVisits 1125899906842625").startsWith("?53 "));
    assertTrue(sendGtp(transport, "54 kata-set-param maxVisits nope").startsWith("?54 "));
    assertEquals("= 99\n\n", sendGtp(transport, "kata-get-param maxVisits"));
  }

  @Test
  void acceptsInclusiveNumericParameterBoundaries() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");

    String[][] acceptedValues = {
      {"maxTime", "0", "0.0"},
      {"maxTime", "1e20", "1.0E20"},
      {"maxVisits", "1", "1"},
      {"maxVisits", "1125899906842624", "1125899906842624"},
      {"playoutDoublingAdvantage", "-3", "-3.0"},
      {"playoutDoublingAdvantage", "3", "3.0"},
      {"analysisWideRootNoise", "0", "0.0"},
      {"analysisWideRootNoise", "5", "5.0"}
    };
    for (String[] accepted : acceptedValues) {
      assertEquals(
          "=\n\n", sendGtp(transport, "kata-set-param " + accepted[0] + " " + accepted[1]));
      assertEquals(
          "= " + accepted[2] + "\n\n", sendGtp(transport, "kata-get-param " + accepted[0]));
    }
  }

  @Test
  void queryCustomizationParametersRoundTripAndUnsupportedParametersFail() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");

    assertEquals("=61\n\n", sendGtp(transport, "61 kata-set-param playoutDoublingAdvantage -2.5"));
    assertEquals("=62 -2.5\n\n", sendGtp(transport, "62 kata-get-param playoutDoublingAdvantage"));
    assertEquals("=63\n\n", sendGtp(transport, "63 kata-set-param analysisWideRootNoise 1.25"));
    assertEquals("=64 1.25\n\n", sendGtp(transport, "64 kata-get-param analysisWideRootNoise"));

    assertTrue(
        sendGtp(transport, "65 kata-set-param playoutDoublingAdvantage 3.1").startsWith("?65 "));
    assertTrue(
        sendGtp(transport, "66 kata-set-param analysisWideRootNoise -0.1").startsWith("?66 "));
    assertEquals("= -2.5\n\n", sendGtp(transport, "kata-get-param playoutDoublingAdvantage"));
    assertEquals("= 1.25\n\n", sendGtp(transport, "kata-get-param analysisWideRootNoise"));

    assertTrue(sendGtp(transport, "67 kata-get-param ponderingEnabled").startsWith("?67 "));
    assertTrue(sendGtp(transport, "68 kata-set-param ponderingEnabled true").startsWith("?68 "));
    assertTrue(sendGtp(transport, "69 kata-get-param unsupported").startsWith("?69 "));
    assertTrue(sendGtp(transport, "70 kata-set-param unsupported 1").startsWith("?70 "));
  }

  @Test
  void moveProducingQueriesUseEffectiveMoveSearchLimits() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      assertEquals("=\n\n", sendGtp(harness.transport, "kata-set-param maxTime 0.75"));
      assertEquals("=\n\n", sendGtp(harness.transport, "kata-set-param maxVisits 321"));
      assertEquals(
          "=\n\n", sendGtp(harness.transport, "kata-set-param playoutDoublingAdvantage 1.5"));
      assertEquals("=\n\n", sendGtp(harness.transport, "kata-set-param analysisWideRootNoise 2.0"));

      writeGtp(harness.transport, "genmove B");
      JSONObject genmove = harness.webSocket.sentJson(0);
      assertEquals(321L, genmove.getLong("maxVisits"));
      assertFalse(genmove.has("maxTime"));
      assertEquals(0.75, genmove.getJSONObject("overrideSettings").getDouble("maxTime"));
      assertEquals(1.5, genmove.getDouble("playoutDoublingAdvantage"));
      assertFalse(genmove.getJSONObject("overrideSettings").has("wideRootNoise"));

      harness.webSocket.emit(
          analysisResponse(genmove.getString("id"), false, "D4", genmove.getInt("maxVisits")));
      readAvailable(harness.transport.stdout());

      writeGtp(harness.transport, "kata-genmove_analyze W 50");
      JSONObject analyzedGenmove = harness.webSocket.sentJson(1);
      assertEquals(321L, analyzedGenmove.getLong("maxVisits"));
      assertFalse(analyzedGenmove.has("maxTime"));
      assertEquals(0.75, analyzedGenmove.getJSONObject("overrideSettings").getDouble("maxTime"));
      assertEquals(1.5, analyzedGenmove.getDouble("playoutDoublingAdvantage"));
      assertFalse(analyzedGenmove.getJSONObject("overrideSettings").has("wideRootNoise"));
    }
  }

  @Test
  void continuousAnalysisKeepsItsExistingLimitAndUsesOnlySharedCustomizations() throws Exception {
    try (TransportHarness harness = TransportHarness.open()) {
      assertEquals("=\n\n", sendGtp(harness.transport, "kata-set-param maxTime 0.25"));
      assertEquals("=\n\n", sendGtp(harness.transport, "kata-set-param maxVisits 12"));
      assertEquals(
          "=\n\n", sendGtp(harness.transport, "kata-set-param playoutDoublingAdvantage -1.5"));
      assertEquals("=\n\n", sendGtp(harness.transport, "kata-set-param analysisWideRootNoise 0.5"));

      writeGtp(harness.transport, "kata-analyze B 10");
      JSONObject analysis = harness.webSocket.sentJson(0);

      assertEquals(10_000_000, analysis.getInt("maxVisits"));
      assertFalse(analysis.has("maxTime"));
      assertFalse(analysis.getJSONObject("overrideSettings").has("maxTime"));
      assertEquals(-1.5, analysis.getDouble("playoutDoublingAdvantage"));
      assertEquals(0.5, analysis.getJSONObject("overrideSettings").getDouble("wideRootNoise"));
    }
  }

  @Test
  void setPositionBuildsStaticInitialStonesAndKeepsLaterMovesSeparate() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");

    assertEquals("=\n\n", sendGtp(transport, "set_position B A3 W C3"));
    assertEquals("=\n\n", sendGtp(transport, "play W pass"));

    JSONObject query = transport.buildAnalysisQuery("test", false, false, 50, "B");
    assertEquals(
        java.util.List.of(java.util.List.of("B", "A3"), java.util.List.of("W", "C3")),
        query.getJSONArray("initialStones").toList());
    assertEquals(
        java.util.List.of(java.util.List.of("W", "pass")), query.getJSONArray("moves").toList());
    assertEquals(java.util.List.of(1), query.getJSONArray("analyzeTurns").toList());
    assertEquals("W", query.getString("initialPlayer"));
  }

  @Test
  void loadSgfRestoresGeneratedSnapshotIntoAnalysisQuery() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");
    Path snapshot = Files.createTempFile("remote-snapshot-", ".sgf");
    try {
      Files.writeString(snapshot, "(;FF[4]GM[1]CA[UTF-8]SZ[9:13]KM[6.5]PL[W]AB[aa]AB[bc]AW[cb])");

      assertEquals("=\n\n", sendGtp(transport, "loadsgf " + snapshot));

      JSONObject query = transport.buildAnalysisQuery("test", false, false, 50, "B");
      assertEquals(9, query.getInt("boardXSize"));
      assertEquals(13, query.getInt("boardYSize"));
      assertEquals(6.5, query.getDouble("komi"));
      assertEquals(
          java.util.List.of(
              java.util.List.of("B", "A13"),
              java.util.List.of("B", "B11"),
              java.util.List.of("W", "C12")),
          query.getJSONArray("initialStones").toList());
      assertEquals(java.util.List.of(), query.getJSONArray("moves").toList());
      assertEquals("W", query.getString("initialPlayer"));
    } finally {
      Files.deleteIfExists(snapshot);
    }
  }

  @Test
  void rejectedLoadSgfLeavesPreviousPositionUntouched() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");
    Path unsupported = Files.createTempFile("remote-unsupported-", ".sgf");
    try {
      assertEquals("=\n\n", sendGtp(transport, "set_position B A3"));
      Files.writeString(unsupported, "(;FF[4]GM[1]CA[UTF-8]SZ[19]PL[B]AB[aa];W[bb])");

      String response = sendGtp(transport, "loadsgf " + unsupported);

      assertTrue(response.startsWith("?"));
      JSONObject query = transport.buildAnalysisQuery("test", false, false, 50, "W");
      assertEquals(
          java.util.List.of(java.util.List.of("B", "A3")),
          query.getJSONArray("initialStones").toList());
    } finally {
      Files.deleteIfExists(unsupported);
    }
  }

  @Test
  void unknownGtpCommandReturnsExplicitErrorWithResponseId() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");

    String response = sendGtp(transport, "42 unsupported-command");

    assertTrue(response.startsWith("?42 unknown command"));
    assertTrue(sendGtp(transport, "time_warp 1").startsWith("? unknown command"));
    assertTrue(
        sendGtp(transport, "kata-get-param unsupported").startsWith("? unknown parameter"));
  }

  @Test
  void rejectedSetPositionLeavesPreviousStaticPositionUntouched() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");
    assertEquals("=\n\n", sendGtp(transport, "set_position B A3"));

    String response = sendGtp(transport, "set_position B A3 W A3");

    assertTrue(response.startsWith("?"));
    JSONObject query = transport.buildAnalysisQuery("test", false, false, 50, "W");
    assertEquals(
        java.util.List.of(java.util.List.of("B", "A3")),
        query.getJSONArray("initialStones").toList());
    assertFalse(query.getJSONArray("initialStones").isEmpty());
  }

  @Test
  void zeroLibertySetPositionLeavesPreviousStaticPositionUntouched() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");
    assertEquals("=\n\n", sendGtp(transport, "boardsize 3"));
    assertEquals("=\n\n", sendGtp(transport, "set_position B A3"));

    String response = sendGtp(transport, "set_position B B2 W A2 W B1 W C2 W B3");

    assertTrue(response.startsWith("?"));
    JSONObject query = transport.buildAnalysisQuery("test", false, false, 50, "W");
    assertEquals(
        java.util.List.of(java.util.List.of("B", "A3")),
        query.getJSONArray("initialStones").toList());
  }

  @Test
  void kataGetRulesReturnsTheRulesSetThroughTheAdapter() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");
    String rules =
        "{\"ko\":\"SIMPLE\",\"scoring\":\"TERRITORY\",\"tax\":\"SEKI\",\"suicide\":false}";

    assertEquals("=\n\n", sendGtp(transport, "kata-set-rules " + rules));

    assertEquals("= " + rules + "\n\n", sendGtp(transport, "kata-get-rules"));
    JSONObject query = transport.buildAnalysisQuery("test", false, false, 50, "B");
    assertEquals("TERRITORY", query.getJSONObject("rules").getString("scoring"));
  }

  @Test
  void kataSetRulesAcceptsAllDocumentedShorthands() throws Exception {
    KataGoAnalysisWebSocketTransport transport =
        new KataGoAnalysisWebSocketTransport("ws://127.0.0.1:1");
    String[][] cases = {
      {"chinese", "\"ko\":\"SIMPLE\""},
      {"chinese-ogs", "\"ko\":\"POSITIONAL\""},
      {"chinese-kgs", "\"ko\":\"POSITIONAL\""},
      {"japanese", "\"scoring\":\"TERRITORY\""},
      {"korean", "\"scoring\":\"TERRITORY\""},
      {"stone-scoring", "\"tax\":\"ALL\""},
      {"aga", "\"whiteHandicapBonus\":\"N-1\""},
      {"bga", "\"whiteHandicapBonus\":\"N-1\""},
      {"new-zealand", "\"suicide\":true"},
      {"aga-button", "\"hasButton\":true"},
      {"tromp-taylor", "\"friendlyPassOk\":false"}
    };

    for (String[] rulesCase : cases) {
      assertEquals("=\n\n", sendGtp(transport, "kata-set-rules " + rulesCase[0]));
      assertTrue(sendGtp(transport, "kata-get-rules").contains(rulesCase[1]), rulesCase[0]);
    }
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

  private static void writeGtp(KataGoAnalysisWebSocketTransport transport, String command)
      throws IOException {
    OutputStream stdin = transport.stdin();
    stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
    stdin.flush();
  }

  private static String readAvailable(InputStream input) throws IOException {
    int available = input.available();
    if (available == 0) {
      return "";
    }
    byte[] buffer = new byte[available];
    int read = input.read(buffer);
    return new String(buffer, 0, read, StandardCharsets.UTF_8);
  }

  private static void assertEof(InputStream input) {
    assertTimeoutPreemptively(
        Duration.ofSeconds(1), () -> assertEquals(-1, input.read()), "stream did not reach EOF");
  }

  private static JSONObject analysisResponse(
      String queryId, boolean duringSearch, String move, int visits) {
    return new JSONObject()
        .put("id", queryId)
        .put("isDuringSearch", duringSearch)
        .put("turnNumber", 0)
        .put(
            "moveInfos",
            new JSONArray()
                .put(new JSONObject().put("move", move).put("visits", visits).put("order", 0)));
  }

  private static JSONObject finalWithoutResults(String queryId) {
    return new JSONObject()
        .put("id", queryId)
        .put("isDuringSearch", false)
        .put("turnNumber", 0)
        .put("noResults", true);
  }

  public static final class TransportHarness implements AutoCloseable {
    private final FakeWebSocket webSocket;
    private final KataGoAnalysisWebSocketTransport transport;

    private TransportHarness(
        FakeWebSocket webSocket, KataGoAnalysisWebSocketTransport transport) {
      this.webSocket = webSocket;
      this.transport = transport;
    }

    public static TransportHarness open() throws Exception {
      FakeWebSocket webSocket = new FakeWebSocket();
      KataGoAnalysisWebSocketTransport transport =
          new KataGoAnalysisWebSocketTransport(
              "ws://127.0.0.1:12345", new FakeHttpClient(webSocket));
      transport.start();
      readAvailable(transport.stderr());
      return new TransportHarness(webSocket, transport);
    }

    public KataGoAnalysisWebSocketTransport transport() {
      return transport;
    }

    public void closeFromServer(int statusCode, String reason) {
      webSocket.closeFromServer(statusCode, reason);
    }

    @Override
    public void close() {
      transport.close();
    }
  }

  private static final class FakeHttpClient extends HttpClient {
    private final HttpClient delegate = HttpClient.newHttpClient();
    private final FakeWebSocket webSocket;

    private FakeHttpClient(FakeWebSocket webSocket) {
      this.webSocket = webSocket;
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
      return new FakeWebSocketBuilder(webSocket);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
      return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
      return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
      return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return delegate.authenticator();
    }

    @Override
    public Version version() {
      return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
      return delegate.executor();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class FakeWebSocketBuilder implements WebSocket.Builder {
    private final FakeWebSocket webSocket;

    private FakeWebSocketBuilder(FakeWebSocket webSocket) {
      this.webSocket = webSocket;
    }

    @Override
    public WebSocket.Builder header(String name, String value) {
      return this;
    }

    @Override
    public WebSocket.Builder connectTimeout(Duration timeout) {
      return this;
    }

    @Override
    public WebSocket.Builder subprotocols(String mostPreferred, String... lesserPreferred) {
      return this;
    }

    @Override
    public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
      webSocket.listener = listener;
      listener.onOpen(webSocket);
      return CompletableFuture.completedFuture(webSocket);
    }
  }

  private static final class FakeWebSocket implements WebSocket {
    private final List<String> sentTexts = new ArrayList<>();
    private final ArrayDeque<CompletableFuture<WebSocket>> sendResults = new ArrayDeque<>();
    private WebSocket.Listener listener;
    private boolean outputClosed;
    private boolean inputClosed;
    private int closeCount;

    private JSONObject sentJson(int index) {
      return new JSONObject(sentTexts.get(index));
    }

    private CompletableFuture<WebSocket> deferNextSend() {
      CompletableFuture<WebSocket> result = new CompletableFuture<>();
      sendResults.add(result);
      return result;
    }

    private void failNextSend(Throwable failure) {
      CompletableFuture<WebSocket> result = new CompletableFuture<>();
      result.completeExceptionally(failure);
      sendResults.add(result);
    }

    private void emit(JSONObject response) {
      assertNotNull(listener);
      listener.onText(this, response.toString(), true);
    }

    private void emitRaw(String response) {
      assertNotNull(listener);
      listener.onText(this, response, true);
    }

    private void closeFromServer(int statusCode, String reason) {
      inputClosed = true;
      outputClosed = true;
      listener.onClose(this, statusCode, reason);
    }

    private void errorFromServer(Throwable failure) {
      listener.onError(this, failure);
    }

    @Override
    public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
      sentTexts.add(data.toString());
      return sendResults.isEmpty() ? CompletableFuture.completedFuture(this) : sendResults.remove();
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
      closeCount++;
      outputClosed = true;
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public void request(long n) {}

    @Override
    public String getSubprotocol() {
      return "";
    }

    @Override
    public boolean isOutputClosed() {
      return outputClosed;
    }

    @Override
    public boolean isInputClosed() {
      return inputClosed;
    }

    @Override
    public void abort() {
      outputClosed = true;
      inputClosed = true;
    }
  }
}
