package featurecat.lizzie.teacher;

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
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.json.JSONObject;

/**
 * Fixed-origin OAuth transport. Never forwards a ChatGPT token to the configurable API provider.
 */
final class ChatGptHttp {
  static final String ISSUER = "https://auth.openai.com";
  static final String RESOURCE = "https://api.openai.com/v1";
  private static final ScheduledExecutorService DEADLINES =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "chatgpt-http-deadline");
            thread.setDaemon(true);
            return thread;
          });
  final URI auth;
  final URI api;
  private HttpClient client;

  ChatGptHttp() {
    this(URI.create(ISSUER), URI.create(RESOURCE));
  }

  // Package-private endpoints permit loopback fake services in tests, never user configuration.
  ChatGptHttp(URI auth, URI api) {
    this.auth = auth;
    this.api = api;
  }

  synchronized HttpClient client() throws IOException {
    if (client == null)
      client =
          NetworkProxy.configure(HttpClient.newBuilder())
              .connectTimeout(Duration.ofSeconds(20))
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
    return client;
  }

  JSONObject get(URI uri, String token) throws IOException, InterruptedException {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return json(request.GET().build());
  }

  JSONObject token(Map<String, String> fields) throws IOException, InterruptedException {
    return json(formRequest(auth.resolve("/api/accounts/oauth/token"), fields));
  }

  boolean revoke(String clientId, String refreshToken) throws IOException, InterruptedException {
    JSONObject discovery = get(auth.resolve("/.well-known/openid-configuration"), null);
    URI endpoint = URI.create(discovery.getString("revocation_endpoint"));
    if (!sameOrigin(auth, endpoint)) throw error("protocol");
    HttpResponse<InputStream> response =
        client()
            .send(
                formRequest(
                    endpoint,
                    Map.of(
                        "token",
                        refreshToken,
                        "token_type_hint",
                        "refresh_token",
                        "client_id",
                        clientId)),
                HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream body = response.body()) {
      return response.statusCode() == 200;
    }
  }

  JSONObject json(HttpRequest request) throws IOException, InterruptedException {
    HttpResponse<InputStream> response =
        client().send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream body = response.body()) {
      ScheduledFuture<?> deadline = closeAfter(body, Duration.ofSeconds(30));
      byte[] bytes;
      try {
        bytes = body.readNBytes(2 * 1024 * 1024 + 1);
      } catch (IOException failed) {
        throw error("network");
      } finally {
        deadline.cancel(false);
      }
      if (bytes.length > 2 * 1024 * 1024) throw error("protocol");
      if (response.statusCode() / 100 != 2)
        throw responseError(
            response.statusCode(), bytes, response.headers().firstValue("x-request-id").orElse(""));
      try {
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
      } catch (RuntimeException malformed) {
        throw error("protocol");
      }
    }
  }

  static ChatGptApiException responseError(int status, byte[] bytes, String requestId) {
    JSONObject payload;
    try {
      payload = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    } catch (RuntimeException malformed) {
      payload = new JSONObject();
    }
    return new ChatGptApiException(status, payload, requestId);
  }

  static ScheduledFuture<?> closeAfter(InputStream body, Duration timeout) {
    return DEADLINES.schedule(
        () -> {
          try {
            body.close();
          } catch (IOException ignored) {
            /* Abort a stalled response. */
          }
        },
        timeout.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  static HttpRequest formRequest(URI uri, Map<String, String> fields) {
    return HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form(fields)))
        .build();
  }

  static String form(Map<String, String> fields) {
    return fields.entrySet().stream()
        .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
        .collect(Collectors.joining("&"));
  }

  static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  static boolean sameOrigin(URI a, URI b) {
    return a.getScheme().equals(b.getScheme())
        && a.getHost().equals(b.getHost())
        && a.getPort() == b.getPort()
        && b.getUserInfo() == null
        && b.getFragment() == null;
  }

  static IOException statusError(int status) {
    return error(
        status == 429
            ? "limit"
            : status == 401 || status == 400
                ? "loginRequired"
                : status == 403 ? "permission" : "network");
  }

  static IOException error(String code) {
    return new IOException(
        TeacherStrings.get(
            "Teacher.chatgpt.error." + code,
            "ChatGPT request could not be completed (" + code + ")."));
  }
}
