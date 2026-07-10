package featurecat.lizzie.analysis.remote;

import featurecat.lizzie.util.NetworkProxy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.json.JSONException;
import org.json.JSONObject;

public class ZhiziApiClient {
  public static final URI DEFAULT_BASE_URI = URI.create("https://www.zhizigo.com");
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

  private final URI baseUri;
  private final HttpClient httpClient;

  public ZhiziApiClient() throws IOException {
    this(
        DEFAULT_BASE_URI,
        NetworkProxy.configure(HttpClient.newBuilder()).connectTimeout(REQUEST_TIMEOUT).build());
  }

  public ZhiziApiClient(URI baseUri, HttpClient httpClient) {
    this.baseUri = baseUri;
    this.httpClient = httpClient;
  }

  public String login(String identifier, String password) throws IOException, InterruptedException {
    JSONObject body = identifierBody(identifier);
    body.put("password", password == null ? "" : password);
    return extractToken(post("/api/cluster/account/login", body, ""));
  }

  public String fastLogin(String identifier, String verificationCode)
      throws IOException, InterruptedException {
    JSONObject body = identifierBody(identifier);
    body.put("verificationCode", verificationCode == null ? "" : verificationCode);
    return extractToken(post("/api/cluster/account/fast-login", body, ""));
  }

  public void sendCode(String identifier) throws IOException, InterruptedException {
    JSONObject body = identifierBody(identifier);
    body.put("type", "fast_login");
    post("/api/cluster/account/send-code", body, "");
  }

  public SocketToken fetchSocketioToken(String accountToken, String args)
      throws IOException, InterruptedException {
    JSONObject body = new JSONObject();
    body.put("args", args == null || args.trim().isEmpty() ? RemoteComputeConfig.DEFAULT_ZHIZI_ARGS : args);
    JSONObject response = post("/api/cluster/account/fetch-socketio-token", body, accountToken, true);
    String token = response.optString("token", "");
    String socketIOURL = response.optString("socketIOURL", "");
    if (token.isEmpty() || socketIOURL.isEmpty()) {
      throw new IOException("智子云算力返回的连接信息不完整。");
    }
    return new SocketToken(token, socketIOURL);
  }

  private JSONObject post(String path, JSONObject body, String bearerToken)
      throws IOException, InterruptedException {
    return post(path, body, bearerToken, false);
  }

  private JSONObject post(String path, JSONObject body, String bearerToken, boolean retryOnceOnIoFailure)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .version(HttpClient.Version.HTTP_1_1)
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    if (bearerToken != null && !bearerToken.trim().isEmpty()) {
      builder.header("Authorization", "Bearer " + bearerToken.trim());
    }
    HttpRequest request = builder.build();
    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      if (!retryOnceOnIoFailure) {
        throw friendlyNetworkException(e);
      }
      Thread.sleep(650L);
      try {
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (IOException retryError) {
        throw friendlyNetworkException(retryError);
      }
    }
    int status = response.statusCode();
    String responseBody = response.body() == null ? "" : response.body();
    if (status < 200 || status >= 300) {
      throw new IOException("智子云算力请求失败(" + status + "): " + summarize(responseBody));
    }
    if (responseBody.trim().isEmpty()) {
      return new JSONObject();
    }
    try {
      return new JSONObject(responseBody);
    } catch (JSONException e) {
      throw new IOException("智子云算力返回内容不是有效 JSON: " + summarize(responseBody), e);
    }
  }

  private static JSONObject identifierBody(String identifier) {
    String trimmed = identifier == null ? "" : identifier.trim();
    JSONObject body = new JSONObject();
    if (trimmed.contains("@")) {
      body.put("email", trimmed);
    } else {
      body.put("phone", trimmed);
    }
    return body;
  }

  private static String extractToken(JSONObject response) throws IOException {
    String token = response.optString("token", "");
    if (token.isEmpty()) {
      throw new IOException("智子云算力登录成功但没有返回 token。");
    }
    return token;
  }

  private static String summarize(String text) {
    String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
    if (normalized.length() <= 220) {
      return normalized;
    }
    return normalized.substring(0, 220) + "...";
  }

  private static IOException friendlyNetworkException(IOException cause) {
    return new IOException("智子接口连接中断，请稍后重试或检查网络代理。当前引擎配置不会被修改。", cause);
  }

  public static final class SocketToken {
    public final String token;
    public final String socketIOURL;

    public SocketToken(String token, String socketIOURL) {
      this.token = token;
      this.socketIOURL = socketIOURL;
    }
  }
}
