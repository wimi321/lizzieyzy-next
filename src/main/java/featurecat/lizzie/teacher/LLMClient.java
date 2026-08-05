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
    // 归一化 baseUrl：去结尾 /，确保末尾是 .../v1
    String b = baseUrl.trim();
    if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
    if (!b.endsWith("/v1")) b = b + "/v1";
    this.baseUrl = b;
    this.apiKey = apiKey;
    this.model = model;
  }

  /** 流式请求，onToken 每收到一段增量文本触发，返回完整文本。 */
  public String chatStream(List<Message> messages, Consumer<String> onToken) throws Exception {
    StringBuilder body = new StringBuilder();
    body.append("{\"model\":\"").append(escape(model)).append("\",\"stream\":true,\"messages\":[");
    for (int i = 0; i < messages.size(); i++) {
      if (i > 0) body.append(",");
      Message m = messages.get(i);
      body.append("{\"role\":\"").append(m.role).append("\",\"content\":");
      if (m.images == null || m.images.isEmpty()) {
        body.append("\"").append(escape(m.content)).append("\"");
      } else {
        // OpenAI 多模态格式：content 为数组
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

    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
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
    java.io.FileWriter dbg = null;
    try {
      dbg = new java.io.FileWriter("teacher_debug.log", true);
    } catch (Exception ignored) {
    }
    while ((line = r.readLine()) != null) {
      line = line.trim();
      if (line.startsWith("data:")) {
        String data = line.substring(5).trim();
        if (dbg != null) {
          dbg.write(data + "\n");
          dbg.flush();
        }
        if ("[DONE]".equals(data)) break;
        String token = parseToken(data);
        if (token != null) {
          full.append(token);
          if (onToken != null) onToken.accept(token);
        }
      }
    }
    if (dbg != null) {
      try {
        dbg.close();
      } catch (Exception ignored) {
      }
    }
    return full.toString();
  }

  private String parseToken(String data) {
    // 行形如: {"choices":[{"delta":{"content":"实际文本"}}]}
    // 或首包: {"choices":[{"delta":{"role":"assistant"}}]}  （无 content，应跳过）
    int idx = data.indexOf("\"content\"");
    if (idx < 0) return null;
    int colon = data.indexOf(':', idx + 9);
    if (colon < 0) return null;
    // colon 后第一个非空白字符必须是 " 才认为是字符串 content
    int p = colon + 1;
    while (p < data.length() && (data.charAt(p) == ' ' || data.charAt(p) == '\t')) p++;
    if (p >= data.length() || data.charAt(p) != '"') return null; // content 为 null 或非字符串，跳过
    int q1 = p;
    // 找匹配的闭合引号（处理转义 \"）
    int q2 = q1 + 1;
    while (q2 < data.length()) {
      char ch = data.charAt(q2);
      if (ch == '\\') {
        q2 += 2;
        continue;
      }
      if (ch == '"') break;
      q2++;
    }
    if (q2 >= data.length()) return null;
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
    public final List<String> images; // base64 data URLs（vision），可为 null/空

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
