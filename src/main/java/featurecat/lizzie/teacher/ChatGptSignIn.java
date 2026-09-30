package featurecat.lizzie.teacher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** A single, cancellable loopback authorization attempt with a five-minute deadline. */
final class ChatGptSignIn implements AutoCloseable {
  final CompletableFuture<ChatGptSessions.Account> result = new CompletableFuture<>();
  final URI authorization;
  private final HttpServer server;
  private final ScheduledExecutorService workers =
      Executors.newScheduledThreadPool(
          2,
          task -> {
            Thread thread = new Thread(task, "chatgpt-sign-in");
            thread.setDaemon(true);
            return thread;
          });
  private final AtomicBoolean consumed = new AtomicBoolean();
  private final ChatGptSessions sessions;
  private final String profile;
  private final String state = random();
  private final String nonce = random();
  private final String verifier = random();
  private final String callback;
  private final String clientId;

  ChatGptSignIn(ChatGptSessions sessions, String profile, Duration timeout) throws IOException {
    this.sessions = sessions;
    this.profile = profile;
    clientId = profile == null ? sessions.registrationClient() : sessions.clientId(profile);
    String hostId = sessions.hostId();
    boolean reconsent = profile != null && !sessions.planAuthorized(profile);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    callback = "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/callback";
    Map<String, String> params = new LinkedHashMap<>();
    params.put("client_id", clientId);
    params.put("ext_agent_host_id", hostId);
    if ("dynamic_agent_client".equals(clientId)) params.put("agent_name_hint", "LizzieYzy Next");
    params.put("response_type", "code");
    params.put("redirect_uri", callback);
    params.put(
        "scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct");
    params.put("resource", ChatGptHttp.RESOURCE);
    if (reconsent) params.put("prompt", "consent");
    params.put("state", state);
    params.put("nonce", nonce);
    params.put("code_challenge_method", "S256");
    params.put("code_challenge", challenge(verifier));
    authorization =
        URI.create(sessions.http.auth + "/api/accounts/authorize?" + ChatGptHttp.form(params));
    server.createContext("/auth/callback", this::handle);
    server.setExecutor(workers);
    server.start();
    ScheduledFuture<?> deadline =
        workers.schedule(
            () -> result.completeExceptionally(ChatGptHttp.error("timeout")),
            timeout.toMillis(),
            TimeUnit.MILLISECONDS);
    result.whenComplete(
        (account, error) -> {
          server.stop(0);
          deadline.cancel(false);
          workers.shutdown();
        });
  }

  private void handle(HttpExchange exchange) throws IOException {
    Map<String, String> values;
    try {
      if (!"GET".equals(exchange.getRequestMethod())
          || !"/auth/callback".equals(exchange.getRequestURI().getPath())
          || exchange.getRequestURI().toString().length() > 16384)
        throw new IllegalArgumentException();
      values = query(exchange.getRequestURI().getRawQuery());
      if (!MessageDigest.isEqual(
          state.getBytes(StandardCharsets.UTF_8),
          values.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8)))
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException invalid) {
      reply(exchange, 400);
      return;
    }
    if (result.isDone() || !consumed.compareAndSet(false, true)) {
      reply(exchange, 409);
      return;
    }
    reply(exchange, 200);
    try {
      if (values.containsKey("error")) {
        throw ChatGptHttp.error("access_denied".equals(values.get("error")) ? "denied" : "network");
      }
      String issued =
          values.getOrDefault("client_id", "dynamic_agent_client".equals(clientId) ? "" : clientId);
      if (issued.isBlank()
          || "dynamic_agent_client".equals(issued)
          || (!"dynamic_agent_client".equals(clientId) && !clientId.equals(issued)))
        throw ChatGptHttp.error("identity");
      String code = values.getOrDefault("code", "");
      if (code.isBlank()) throw ChatGptHttp.error("protocol");
      if (result.isDone()) return;
      if (profile == null) sessions.retainRegistration(issued);
      JSONObject tokens =
          sessions.http.token(
              Map.of(
                  "grant_type",
                  "authorization_code",
                  "client_id",
                  issued,
                  "code",
                  code,
                  "code_verifier",
                  verifier,
                  "redirect_uri",
                  callback,
                  "resource",
                  ChatGptHttp.RESOURCE));
      ChatGptIdentity identity =
          sessions.verify(
              tokens.getString("id_token"),
              issued,
              nonce,
              profile == null ? null : sessions.subject(profile));
      synchronized (this) {
        if (result.isDone() || Thread.currentThread().isInterrupted()) return;
        result.complete(sessions.accept(profile, issued, identity, tokens));
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      result.completeExceptionally(ChatGptHttp.error("network"));
    } catch (Exception failure) {
      result.completeExceptionally(
          failure instanceof IOException ? failure : ChatGptHttp.error("protocol"));
    }
  }

  private static void reply(HttpExchange exchange, int status) throws IOException {
    byte[] body =
        (status == 200
                ? "Return to LizzieYzy Next to finish connecting ChatGPT."
                : "This sign-in callback is invalid or has expired.")
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    exchange.sendResponseHeaders(status, body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  static Map<String, String> query(String value) {
    Map<String, String> result = new LinkedHashMap<>();
    if (value == null) return result;
    for (String field : value.split("&")) {
      String[] pair = field.split("=", 2);
      String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
      String content = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
      if (result.putIfAbsent(key, content) != null) throw new IllegalArgumentException();
    }
    return result;
  }

  static String random() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String challenge(String verifier) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              MessageDigest.getInstance("SHA-256")
                  .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  @Override
  public synchronized void close() {
    result.cancel(false);
  }
}
