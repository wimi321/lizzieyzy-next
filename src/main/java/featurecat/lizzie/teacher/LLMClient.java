package featurecat.lizzie.teacher;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * OpenAI 兼容 Chat Completions 客户端（流式 SSE）。 对齐 GoAgent 的 LLM 调用：baseUrl + apiKey + model，多轮
 * messages，逐 token 回调。
 */
public class LLMClient {
  private final HttpClient http;
  private final String baseUrl;
  private final String apiKey;
  private final String model;

  public LLMClient(String baseUrl, String apiKey, String model) {
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    this.apiKey = apiKey;
    this.model = model;
  }

  /** 流式请求，onToken 每收到一段增量文本触发，返回完整文本。 */
  public String chatStream(List<Message> messages, Consumer<String> onToken) throws Exception {
    StringBuilder body = new StringBuilder();
    body.append("{\"model\":\"").append(escape(model)).append("\",\"stream\":true,\"messages\":[");
    for (int i = 0; i < messages.size(); i++) {
      if (i > 0) body.append(",");
      body.append("{\"role\":\"")
          .append(messages.get(i).role)
          .append("\",\"content\":\"")
          .append(escape(messages.get(i).content))
          .append("\"}");
    }
    body.append("]}");

    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "v1/chat/completions"))
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();

    HttpResponse<java.io.InputStream> resp =
        http.send(req, HttpResponse.BodyHandlers.ofInputStream());
    if (resp.statusCode() != 200) {
      throw new RuntimeException("LLM HTTP " + resp.statusCode());
    }

    java.io.BufferedReader r =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8));
    StringBuilder full = new StringBuilder();
    String line;
    while ((line = r.readLine()) != null) {
      line = line.trim();
      if (line.startsWith("data:")) {
        String data = line.substring(5).trim();
        if ("[DONE]".equals(data)) break;
        String token = parseToken(data);
        if (token != null) {
          full.append(token);
          if (onToken != null) onToken.accept(token);
        }
      }
    }
    return full.toString();
  }

  private String parseToken(String data) {
    // {"choices":[{"delta":{"content":"..."}}]}
    int idx = data.indexOf("\"content\"");
    if (idx < 0) return null;
    int c = data.indexOf(':', idx + 9);
    if (c < 0) return null;
    int q1 = data.indexOf('"', c + 1);
    if (q1 < 0) return null;
    int q2 = data.indexOf('"', q1 + 1);
    if (q2 < 0) return null;
    return unescape(data.substring(q1 + 1, q2));
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
  }

  private static String unescape(String s) {
    return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
  }

  public static class Message {
    public final String role;
    public final String content;

    public Message(String role, String content) {
      this.role = role;
      this.content = content;
    }
  }
}
