package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZhiziApiClientTest {
  private HttpServer server;
  private String lastPath;
  private String lastAuthorization;
  private JSONObject lastBody;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void passwordLoginUsesEmailBodyAndReturnsToken() throws Exception {
    ZhiziApiClient client = client();

    String token = client.login("player@example.com", "secret");

    assertEquals("account-token", token);
    assertEquals("/api/cluster/account/login", lastPath);
    assertEquals("player@example.com", lastBody.getString("email"));
    assertEquals("secret", lastBody.getString("password"));
  }

  @Test
  void fastLoginUsesPhoneBodyAndReturnsToken() throws Exception {
    ZhiziApiClient client = client();

    String token = client.fastLogin("13800138000", "123456");

    assertEquals("account-token", token);
    assertEquals("/api/cluster/account/fast-login", lastPath);
    assertEquals("13800138000", lastBody.getString("phone"));
    assertEquals("123456", lastBody.getString("verificationCode"));
  }

  @Test
  void fetchSocketTokenSendsBearerTokenAndArgs() throws Exception {
    ZhiziApiClient client = client();

    ZhiziApiClient.SocketToken token =
        client.fetchSocketioToken("account-token", RemoteComputeConfig.FASTER_ZHIZI_ARGS);

    assertEquals("socket-token", token.token);
    assertEquals("https://socket.example", token.socketIOURL);
    assertEquals("/api/cluster/account/fetch-socketio-token", lastPath);
    assertEquals("Bearer account-token", lastAuthorization);
    assertTrue(lastBody.getString("args").contains("--gpu-type 3x"));
  }

  @Test
  void fetchSocketTokenCanUseVipShareGpuType() throws Exception {
    ZhiziApiClient client = client();

    client.fetchSocketioToken("account-token", RemoteComputeConfig.VIP_ZHIZI_ARGS);

    assertEquals("/api/cluster/account/fetch-socketio-token", lastPath);
    assertTrue(lastBody.getString("args").contains("--gpu-type vip-share"));
  }

  private ZhiziApiClient client() {
    URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    return new ZhiziApiClient(baseUri, HttpClient.newHttpClient());
  }

  private void handle(HttpExchange exchange) throws IOException {
    lastPath = exchange.getRequestURI().getPath();
    lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
    String request =
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    lastBody = request.isBlank() ? new JSONObject() : new JSONObject(request);
    JSONObject response = new JSONObject();
    if (lastPath.endsWith("/fetch-socketio-token")) {
      response.put("token", "socket-token");
      response.put("socketIOURL", "https://socket.example");
    } else {
      response.put("token", "account-token");
    }
    byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
