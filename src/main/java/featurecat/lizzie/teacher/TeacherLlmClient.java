package featurecat.lizzie.teacher;

import featurecat.lizzie.logging.NetworkEndpointCategory;
import featurecat.lizzie.logging.NetworkObservation;
import featurecat.lizzie.util.NetworkProxy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small OpenAI-compatible client with streaming and Responses API fallback. */
public final class TeacherLlmClient {
  private static final int MAX_ERROR_BODY_BYTES = 4096;
  private static final int MAX_PROMPT_CHARACTERS = 250_000;

  private final HttpClient httpClient;
  private final URI apiBase;
  private final String apiKey;
  private final String model;

  public TeacherLlmClient(String baseUrl, String apiKey, String model) throws IOException {
    this(
        NetworkProxy.configure(HttpClient.newBuilder())
            .connectTimeout(Duration.ofSeconds(15))
            // Never forward an API key to a redirect target. Providers must expose their final
            // HTTPS API URL explicitly.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        baseUrl,
        apiKey,
        model);
  }

  TeacherLlmClient(HttpClient httpClient, String baseUrl, String apiKey, String model) {
    this.httpClient = httpClient;
    this.apiBase = normalizeApiBase(baseUrl);
    this.apiKey = requireApiKey(apiKey);
    this.model = TeacherSettings.validateModel(model);
  }

  public List<String> listModels() throws IOException, InterruptedException {
    long started = System.nanoTime();
    HttpRequest request = requestBuilder(endpoint("models"), Duration.ofSeconds(30)).GET().build();
    HttpResponse<String> response;
    try {
      response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException failure) {
      observe(request, 0, started, "failed");
      throw failure;
    }
    if (response.statusCode() / 100 != 2) {
      observe(request, response.statusCode(), started, "failed");
      throw httpFailure(response.statusCode(), response.body());
    }
    observe(request, response.statusCode(), started, "ok");
    JSONObject root = new JSONObject(response.body());
    JSONArray data = root.optJSONArray("data");
    if (data == null) {
      return Collections.emptyList();
    }
    ArrayList<String> models = new ArrayList<>();
    for (int index = 0; index < data.length(); index++) {
      JSONObject item = data.optJSONObject(index);
      String id = item == null ? "" : item.optString("id", "").trim();
      if (!id.isEmpty() && !models.contains(id)) {
        models.add(id);
      }
    }
    models.sort(Comparator.naturalOrder());
    return Collections.unmodifiableList(models);
  }

  public String stream(List<Message> messages, Cancellation cancellation, Consumer<String> onText)
      throws IOException, InterruptedException {
    List<Message> safeMessages = validateMessages(messages);
    Cancellation requestCancellation = cancellation == null ? new Cancellation() : cancellation;
    Consumer<String> receiver = onText == null ? ignored -> {} : onText;

    HttpResponse<InputStream> chatResponse =
        sendStreaming(endpoint("chat/completions"), chatCompletionsBody(safeMessages));
    if (chatResponse.statusCode() / 100 == 2) {
      return parseChatCompletions(chatResponse.body(), requestCancellation, receiver);
    }
    if (chatResponse.statusCode() != 404 && chatResponse.statusCode() != 405) {
      throw httpFailure(chatResponse.statusCode(), readErrorBody(chatResponse.body()));
    }
    chatResponse.body().close();

    // Some OpenAI-compatible providers expose only the Responses API. Retry only after the
    // original endpoint explicitly reports that it is unavailable, avoiding an extra paid ping.
    HttpResponse<InputStream> responsesResponse =
        sendStreaming(endpoint("responses"), responsesBody(safeMessages));
    if (responsesResponse.statusCode() / 100 != 2) {
      throw httpFailure(responsesResponse.statusCode(), readErrorBody(responsesResponse.body()));
    }
    return parseResponses(responsesResponse.body(), requestCancellation, receiver);
  }

  private HttpResponse<InputStream> sendStreaming(URI endpoint, JSONObject body)
      throws IOException, InterruptedException {
    long started = System.nanoTime();
    HttpRequest request =
        requestBuilder(endpoint, Duration.ofMinutes(5))
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();
    HttpResponse<InputStream> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException failure) {
      observe(request, 0, started, "failed");
      throw failure;
    }
    observe(
        request,
        response.statusCode(),
        started,
        response.statusCode() / 100 == 2 ? "ok" : "failed");
    return response;
  }

  private static void observe(HttpRequest request, int status, long startedNanos, String outcome) {
    URI uri = request.uri();
    NetworkObservation.recordNetwork(
        request.method(),
        uri == null ? "unknown" : uri.getHost(),
        NetworkEndpointCategory.OTHER,
        status,
        Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)),
        outcome,
        NetworkObservation.newRequestIdentity());
  }

  private HttpRequest.Builder requestBuilder(URI endpoint, Duration timeout) {
    return HttpRequest.newBuilder(endpoint)
        .timeout(timeout)
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json; charset=utf-8")
        .header("User-Agent", "LizzieYzy-Next-AI-Commentary");
  }

  private JSONObject chatCompletionsBody(List<Message> messages) {
    JSONArray payloadMessages = new JSONArray();
    for (Message message : messages) {
      payloadMessages.put(
          new JSONObject().put("role", message.role).put("content", message.content));
    }
    return new JSONObject()
        .put("model", model)
        .put("stream", true)
        .put("messages", payloadMessages);
  }

  private JSONObject responsesBody(List<Message> messages) {
    JSONArray input = new JSONArray();
    for (Message message : messages) {
      input.put(new JSONObject().put("role", message.role).put("content", message.content));
    }
    return new JSONObject().put("model", model).put("stream", true).put("input", input);
  }

  private static String parseChatCompletions(
      InputStream input, Cancellation cancelled, Consumer<String> receiver) throws IOException {
    return parseSse(
        input,
        cancelled,
        receiver,
        event -> {
          JSONArray choices = event.optJSONArray("choices");
          if (choices == null || choices.isEmpty()) {
            return "";
          }
          JSONObject choice = choices.optJSONObject(0);
          JSONObject delta = choice == null ? null : choice.optJSONObject("delta");
          return delta == null ? "" : delta.optString("content", "");
        });
  }

  private static String parseResponses(
      InputStream input, Cancellation cancelled, Consumer<String> receiver) throws IOException {
    return parseSse(
        input,
        cancelled,
        receiver,
        event -> {
          String type = event.optString("type", "");
          if ("response.output_text.delta".equals(type) || "output_text_delta".equals(type)) {
            String delta = event.optString("delta", "");
            return delta.isEmpty() ? event.optString("content", "") : delta;
          }
          return "";
        });
  }

  private static String parseSse(
      InputStream input,
      Cancellation cancelled,
      Consumer<String> receiver,
      EventTextExtractor extractor)
      throws IOException {
    StringBuilder complete = new StringBuilder();
    cancelled.attach(input);
    try (InputStream source = input;
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (cancelled.isCancelled() || Thread.currentThread().isInterrupted()) {
          throw new CancellationException("AI commentary request was cancelled.");
        }
        if (!line.startsWith("data:")) {
          continue;
        }
        String data = line.substring("data:".length()).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) {
          if ("[DONE]".equals(data)) {
            break;
          }
          continue;
        }
        JSONObject event;
        try {
          event = new JSONObject(data);
        } catch (RuntimeException malformedEvent) {
          continue;
        }
        throwIfStreamFailed(event);
        String text = extractor.extract(event);
        if (!text.isEmpty()) {
          complete.append(text);
          receiver.accept(text);
        }
      }
    } finally {
      cancelled.detach(input);
    }
    if (cancelled.isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new CancellationException("AI commentary request was cancelled.");
    }
    return complete.toString();
  }

  private static void throwIfStreamFailed(JSONObject event) throws IOException {
    JSONObject error = event.optJSONObject("error");
    if (error == null && "response.failed".equals(event.optString("type", ""))) {
      JSONObject response = event.optJSONObject("response");
      error = response == null ? null : response.optJSONObject("error");
    }
    if (error == null) {
      return;
    }
    String detail = sanitizeError(error.optString("message", ""), "");
    if (detail.length() > 240) {
      detail = detail.substring(0, 240) + "...";
    }
    throw new IOException(
        detail.isEmpty() ? "AI service stream failed." : "AI service stream failed: " + detail);
  }

  private static List<Message> validateMessages(List<Message> messages) {
    if (messages == null || messages.isEmpty()) {
      throw new IllegalArgumentException("At least one message is required.");
    }
    ArrayList<Message> result = new ArrayList<>();
    int characters = 0;
    for (Message message : messages) {
      if (message == null || message.content == null || message.content.isBlank()) {
        continue;
      }
      String role = message.role == null ? "" : message.role.trim();
      if (!("system".equals(role) || "user".equals(role) || "assistant".equals(role))) {
        throw new IllegalArgumentException("Unsupported message role.");
      }
      characters += message.content.length();
      if (characters > MAX_PROMPT_CHARACTERS) {
        throw new IllegalArgumentException("AI commentary context is too large.");
      }
      result.add(new Message(role, message.content));
    }
    if (result.isEmpty()) {
      throw new IllegalArgumentException("At least one non-empty message is required.");
    }
    return Collections.unmodifiableList(result);
  }

  private URI endpoint(String relativePath) {
    String base = apiBase.toString();
    if (!base.endsWith("/")) {
      base += "/";
    }
    return URI.create(base + relativePath);
  }

  static URI normalizeApiBase(String value) {
    String validated = TeacherSettings.validateBaseUrl(value);
    URI uri = URI.create(validated);
    String path = uri.getPath() == null ? "" : uri.getPath();
    if (path.isEmpty() || "/".equals(path)) {
      validated += validated.endsWith("/") ? "v1" : "/v1";
    }
    return URI.create(validated);
  }

  private static String requireApiKey(String value) {
    String candidate = value == null ? "" : value.trim();
    if (candidate.isEmpty() || candidate.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("A valid API key is required.");
    }
    return candidate;
  }

  private IOException httpFailure(int statusCode, String responseBody) {
    String detail = sanitizeError(responseBody, apiKey);
    if (detail.length() > 240) {
      detail = detail.substring(0, 240) + "...";
    }
    return new IOException(
        detail.isEmpty()
            ? "AI service returned HTTP " + statusCode + "."
            : "AI service returned HTTP " + statusCode + ": " + detail);
  }

  private static String sanitizeError(String value, String secret) {
    String detail = value == null ? "" : value.replaceAll("\\s+", " ").trim();
    if (secret != null && !secret.isEmpty()) {
      detail = detail.replace(secret, "[redacted]");
    }
    detail = detail.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+\\-/=]+", "Bearer [redacted]");
    detail =
        detail.replaceAll(
            "(?i)(\\\"(?:api[_-]?key|authorization|token)\\\"\\s*:\\s*\\\")[^\\\"]+(\\\")",
            "$1[redacted]$2");
    return detail;
  }

  private static String readErrorBody(InputStream input) throws IOException {
    try (InputStream source = input) {
      byte[] bytes = source.readNBytes(MAX_ERROR_BODY_BYTES + 1);
      int length = Math.min(bytes.length, MAX_ERROR_BODY_BYTES);
      return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
  }

  public static final class Message {
    public final String role;
    public final String content;

    public Message(String role, String content) {
      this.role = role;
      this.content = content;
    }
  }

  /** Cancellation token that closes the active response stream to unblock a pending read. */
  public static final class Cancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile InputStream activeStream;

    public void cancel() {
      cancelled.set(true);
      InputStream stream = activeStream;
      if (stream != null) {
        try {
          stream.close();
        } catch (IOException ignored) {
        }
      }
    }

    public boolean isCancelled() {
      return cancelled.get();
    }

    private void attach(InputStream stream) throws IOException {
      activeStream = stream;
      if (cancelled.get()) {
        stream.close();
        throw new CancellationException("AI commentary request was cancelled.");
      }
    }

    private void detach(InputStream stream) {
      if (activeStream == stream) {
        activeStream = null;
      }
    }
  }

  @FunctionalInterface
  private interface EventTextExtractor {
    String extract(JSONObject event);
  }
}
