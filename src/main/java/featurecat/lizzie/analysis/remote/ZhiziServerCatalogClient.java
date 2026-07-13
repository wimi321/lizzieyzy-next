package featurecat.lizzie.analysis.remote;

import ch.ethz.ssh2.ChannelCondition;
import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.Session;
import featurecat.lizzie.util.NetworkProxy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads the live iKataGo resource catalog associated with a Zhizi account. */
public final class ZhiziServerCatalogClient {
  static final URI DEFAULT_WORLD_URI =
      URI.create("https://ikatago-fairyland.oss-cn-beijing.aliyuncs.com/world.json");
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
  private static final int SSH_TIMEOUT_MILLIS = 10_000;
  private static final int MAX_RESPONSE_BYTES = 512 * 1024;

  private final URI worldUri;
  private final HttpClient httpClient;
  private final SshCatalogQuery sshQuery;

  public ZhiziServerCatalogClient() throws IOException {
    this(
        DEFAULT_WORLD_URI,
        NetworkProxy.configure(HttpClient.newBuilder()).connectTimeout(HTTP_TIMEOUT).build(),
        ZhiziServerCatalogClient::queryOverSsh);
  }

  ZhiziServerCatalogClient(URI worldUri, HttpClient httpClient, SshCatalogQuery sshQuery) {
    this.worldUri = worldUri;
    this.httpClient = httpClient;
    this.sshQuery = sshQuery;
  }

  public ZhiziEngineCatalog fetchCatalog(ZhiziApiClient.ConnectAccount account)
      throws IOException, InterruptedException {
    if (account == null || account.username.isBlank() || account.password.isBlank()) {
      throw new IOException("Zhizi connection account is incomplete.");
    }
    JSONObject world = getJson(worldUri);
    JSONObject platform = findPlatform(world, "all");
    URI accountUri = accountMetadataUri(platform, account.username);
    JSONObject endpointJson = getJson(accountUri);
    SshEndpoint endpoint = SshEndpoint.fromJson(endpointJson);
    String response =
        sshQuery.query(
            endpoint.host, endpoint.port, endpoint.username, account.password, "query-server");
    return ZhiziEngineCatalog.fromJson(response);
  }

  private JSONObject getJson(URI uri) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(HTTP_TIMEOUT)
            .header("Accept", "application/json")
            .version(HttpClient.Version.HTTP_1_1)
            .GET()
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException(
          "iKataGo catalog request failed with HTTP " + response.statusCode() + ".");
    }
    try {
      return new JSONObject(response.body());
    } catch (RuntimeException e) {
      throw new IOException("iKataGo catalog endpoint returned invalid JSON.", e);
    }
  }

  static JSONObject findPlatform(JSONObject world, String name) throws IOException {
    JSONArray platforms = world == null ? null : world.optJSONArray("platforms");
    if (platforms == null) {
      throw new IOException("iKataGo world metadata has no platforms.");
    }
    for (int i = 0; i < platforms.length(); i++) {
      JSONObject platform = platforms.optJSONObject(i);
      if (platform != null && name.equals(platform.optString("name", ""))) {
        return platform;
      }
    }
    throw new IOException("iKataGo platform is unavailable: " + name);
  }

  static URI accountMetadataUri(JSONObject platform, String username) throws IOException {
    String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8).replace("+", "%20");
    JSONObject http = platform.optJSONObject("http");
    if (http != null && !http.optString("getUrl", "").isBlank()) {
      return appendPath(http.getString("getUrl"), "users/" + encoded + ".ssh.json");
    }
    JSONObject oss = platform.optJSONObject("oss");
    if (oss == null) {
      throw new IOException("iKataGo platform has no account metadata endpoint.");
    }
    String bucket = oss.optString("bucket", "").trim();
    String endpoint = oss.optString("bucketEndpoint", "").trim();
    if (bucket.isEmpty() || endpoint.isEmpty()) {
      throw new IOException("iKataGo account metadata endpoint is incomplete.");
    }
    return URI.create("https://" + bucket + "." + endpoint + "/users/" + encoded + ".ssh.json");
  }

  private static URI appendPath(String base, String path) {
    return URI.create(base.endsWith("/") ? base + path : base + "/" + path);
  }

  private static String queryOverSsh(
      String host, int port, String username, String password, String command) throws IOException {
    Connection connection = new Connection(host, port);
    Session session = null;
    try {
      connection.connect(null, SSH_TIMEOUT_MILLIS, SSH_TIMEOUT_MILLIS);
      if (!connection.authenticateWithPassword(username, password)) {
        throw new IOException("iKataGo catalog authentication failed.");
      }
      session = connection.openSession();
      session.execCommand(command);
      int condition =
          session.waitForCondition(
              ChannelCondition.EOF | ChannelCondition.CLOSED, SSH_TIMEOUT_MILLIS);
      if ((condition & ChannelCondition.TIMEOUT) != 0) {
        throw new IOException("iKataGo catalog query timed out.");
      }
      String stdout = readLimited(session.getStdout());
      String stderr = readLimited(session.getStderr());
      if (stdout.isBlank()) {
        throw new IOException(
            stderr.isBlank() ? "iKataGo catalog response is empty." : stderr.trim());
      }
      return stdout;
    } finally {
      if (session != null) {
        session.close();
      }
      connection.close();
    }
  }

  private static String readLimited(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
    if (bytes.length > MAX_RESPONSE_BYTES) {
      throw new IOException("iKataGo catalog response is too large.");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  interface SshCatalogQuery {
    String query(String host, int port, String username, String password, String command)
        throws IOException;
  }

  static final class SshEndpoint {
    final String host;
    final int port;
    final String username;

    SshEndpoint(String host, int port, String username) {
      this.host = host;
      this.port = port;
      this.username = username;
    }

    static SshEndpoint fromJson(JSONObject json) throws IOException {
      String host = json.optString("host", "").trim();
      String username = json.optString("user", "").trim();
      int port = json.optInt("port", 22);
      if (host.isEmpty() || username.isEmpty() || port < 1 || port > 65_535) {
        throw new IOException("iKataGo SSH endpoint is incomplete.");
      }
      return new SshEndpoint(host, port, username);
    }
  }
}
