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
 * OpenAI 兼容 LLM 客户端（流式 SSE）。
 * 自动检测 Chat Completions API（/chat/completions）和 Responses API（/responses）。
 */
public class LLMClient {
  private final HttpClient http;
  private final String baseUrl;
  private final String apiKey;
  private final String model;
  private boolean useResponsesApi = false; // 自动检测

  public LLMClient(String baseUrl, String apiKey, String model) {
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    String b = baseUrl.trim();
    if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
    if (!b.endsWith("/v1")) b = b + "/v1";
    this.baseUrl = b;
    this.apiKey = apiKey;
    this.model = model;
  }

  /** 流式请求，onToken 每收到一段增量文本触发，返回完整文本。 */
  public String chatStream(List<Message> messages, Consumer<String> onToken) throws Exception {
    // 首次调用时自动检测 API 类型
    if (!useResponsesApi && !detected) {
      detectApi();
    }

    if (useResponsesApi) {
      return responsesStream(messages, onToken);
    } else {
      return chatCompletionsStream(messages, onToken);
    }
  }

  private boolean detected = false;

  /** 检测 API 类型：先试 /chat/completions，失败再试 /responses */
  private void detectApi() {
    detected = true;
    try {
      String body = "{\"model\":\"" + escape(model) + "\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(baseUrl + "/chat/completions"))
          .timeout(Duration.ofSeconds(10))
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer " + apiKey)
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
          .build();
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 200) {
        useResponsesApi = false;
        return;
      }
    } catch (Exception ignored) {}
    // Chat Completions 不通，用 Responses
    useResponsesApi = true;
    System.out.println("[LLM] 自动切换到 Responses API");
  }

  /** Chat Completions 流式调用 */
  private String chatCompletionsStream(List<Message> messages, Consumer<String> onToken) throws Exception {
    StringBuilder body = new StringBuilder();
    body.append("{\"model\":\"").append(escape(model)).append("\",\"stream\":true,\"messages\":[");
    for (int i = 0; i < messages.size(); i++) {
      if (i > 0) body.append(",");
      Message m = messages.get(i);
      body.append("{\"role\":\"").append(m.role).append("\",\"content\":");
      if (m.images == null || m.images.isEmpty()) {
        body.append("\"").append(escape(m.content)).append("\"");
      } else {
        body.append("[");
        body.append("{\"type\":\"text\",\"text\":\"").append(escape(m.content)).append("\"}");
        for (String img : m.images) {
          body.append(",{\"type\":\"image_url\",\"image_url\":{\"url\":\"")
              .append(escape(img)).append("\"}}");
        }
        body.append("]");
      }
      body.append("}");
    }
    body.append("]}");

    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/chat/completions"))
        .timeout(Duration.ofMinutes(5))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build();

    HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
    if (resp.statusCode() != 200) {
      throw new RuntimeException("LLM HTTP " + resp.statusCode() + "（Chat Completions）");
    }

    return parseSSE(resp.body(), onToken, "choices", "delta", "content");
  }

  /** Responses API 流式调用 */
  private String responsesStream(List<Message> messages, Consumer<String> onToken) throws Exception {
    // 转换 messages 为 input 格式
    StringBuilder input = new StringBuilder();
    input.append("[");
    for (int i = 0; i < messages.size(); i++) {
      if (i > 0) input.append(",");
      Message m = messages.get(i);
      input.append("{\"role\":\"").append(m.role).append("\",\"content\":\"").append(escape(m.content)).append("\"}");
    }
    input.append("]");

    String body = "{\"model\":\"" + escape(model) + "\",\"stream\":true,\"input\":" + input + "}";

    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/responses"))
        .timeout(Duration.ofMinutes(5))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();

    HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
    if (resp.statusCode() != 200) {
      throw new RuntimeException("LLM HTTP " + resp.statusCode() + "（Responses API）");
    }

    return parseSSE(resp.body(), onToken, "output_text_delta", "content", null);
  }

  /** 通用 SSE 解析 */
  private String parseSSE(java.io.InputStream stream, Consumer<String> onToken,
                          String eventPrefix, String deltaKey, String contentKey) throws Exception {
    StringBuilder full = new StringBuilder();
    try (java.io.BufferedReader r = new java.io.BufferedReader(
            new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        line = line.trim();
        if (!line.startsWith("data:")) continue;
        String data = line.substring(5).trim();
        if ("[DONE]".equals(data)) break;
        String token = null;
        if ("output_text_delta".equals(eventPrefix)) {
          // Responses API: {"type":"output_text_delta","content":"xxx"}
          token = extractField(data, "content");
        } else {
          // Chat Completions: {"choices":[{"delta":{"content":"xxx"}}]}
          token = extractNested(data, deltaKey, contentKey);
        }
        if (token != null) {
          full.append(token);
          if (onToken != null) onToken.accept(token);
        }
      }
    }
    return full.toString();
  }

  /** 提取 JSON 一级字段值 */
  private static String extractField(String json, String key) {
    int idx = json.indexOf("\"" + key + "\"");
    if (idx < 0) return null;
    int colon = json.indexOf(':', idx + key.length() + 2);
    if (colon < 0) return null;
    int p = colon + 1;
    while (p < json.length() && json.charAt(p) == ' ') p++;
    if (p >= json.length() || json.charAt(p) != '"') return null;
    int q1 = p;
    int q2 = q1 + 1;
    while (q2 < json.length()) {
      char ch = json.charAt(q2);
      if (ch == '\\') { q2 += 2; continue; }
      if (ch == '"') break;
      q2++;
    }
    if (q2 >= json.length()) return null;
    return unescape(json.substring(q1 + 1, q2));
  }

  /** 提取嵌套 JSON 字段值（两层） */
  private static String extractNested(String json, String outerKey, String innerKey) {
    int oidx = json.indexOf("\"" + outerKey + "\"");
    if (oidx < 0) return null;
    // 找 outer 对象的开始 {
    int obrace = json.indexOf('{', oidx);
    if (obrace < 0) return null;
    // 找 outer 对象的结束 }
    int depth = 1;
    int oend = obrace + 1;
    while (oend < json.length() && depth > 0) {
      char c = json.charAt(oend);
      if (c == '{') depth++;
      if (c == '}') depth--;
      oend++;
    }
    String outer = json.substring(obrace, oend);
    return extractField(outer, innerKey);
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
    public final List<String> images;

    public Message(String role, String content) {
      this.role = role;
      this.content = content;
      this.images = null;
    }

    public Message(String role, String content, List<String> images) {
      this.role = role;
      this.content = content;
      this.images = images;
    }
  }
}
