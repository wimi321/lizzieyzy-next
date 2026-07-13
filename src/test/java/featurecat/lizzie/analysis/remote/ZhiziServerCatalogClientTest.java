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
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZhiziServerCatalogClientTest {
  private HttpServer server;
  private URI baseUri;
  private final AtomicReference<String> accountPath = new AtomicReference<>();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void fetchCatalogResolvesAccountEndpointAndRunsQueryServer() throws Exception {
    AtomicReference<String> sshCall = new AtomicReference<>();
    ZhiziServerCatalogClient client =
        new ZhiziServerCatalogClient(
            baseUri.resolve("/world.json"),
            HttpClient.newHttpClient(),
            (host, port, username, password, command) -> {
              sshCall.set(host + ":" + port + ":" + username + ":" + password + ":" + command);
              return liveCatalog().toString();
            });

    ZhiziEngineCatalog catalog =
        client.fetchCatalog(
            new ZhiziApiClient.ConnectAccount("zz-player@example.com", "temporary-password"));

    assertEquals("28bnbt", catalog.defaultWeight());
    assertEquals(6, catalog.weights().size());
    assertEquals("worker.example:2222:ssh-player:temporary-password:query-server", sshCall.get());
    assertTrue(accountPath.get().contains("zz-player%40example.com.ssh.json"));
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    JSONObject response;
    if ("/world.json".equals(path)) {
      response =
          new JSONObject()
              .put(
                  "platforms",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("name", "all")
                              .put("http", new JSONObject().put("getUrl", baseUri + "/accounts"))));
    } else {
      accountPath.set(exchange.getRequestURI().getRawPath());
      response =
          new JSONObject()
              .put("host", "worker.example")
              .put("port", 2222)
              .put("user", "ssh-player");
    }
    byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static JSONObject liveCatalog() {
    JSONArray weights = new JSONArray();
    for (String name : new String[] {"18bnbt", "28bnbt", "fdx", "60b", "40b", "20b"}) {
      weights.put(new JSONObject().put("name", name).put("description", name + " weight"));
    }
    return new JSONObject()
        .put("serverVersion", "8.0.1")
        .put("defaultKataWeight", "28bnbt")
        .put("supportKataWeights", weights);
  }
}
