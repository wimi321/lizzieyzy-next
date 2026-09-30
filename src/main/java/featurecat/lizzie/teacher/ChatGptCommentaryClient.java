package featurecat.lizzie.teacher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

final class ChatGptCommentaryClient implements CommentaryClient {
  private final ChatGptSessions sessions;
  private final String accountId;
  private final String model;
  private final long generation;

  ChatGptCommentaryClient(ChatGptSessions sessions, String accountId, String model) {
    this.sessions = sessions;
    this.accountId = accountId;
    this.model = model;
    this.generation = sessions.generation();
  }

  List<Model> models() throws IOException, InterruptedException {
    String token = sessions.accessToken(accountId);
    JSONObject reply = sessions.http.get(URI.create(sessions.http.api + "/models"), token);
    JSONArray entries = reply.optJSONArray("models");
    if (entries == null) throw ChatGptHttp.error("protocol");
    List<Model> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < entries.length(); i++) {
      JSONObject item = entries.optJSONObject(i);
      if (item == null || !"list".equals(item.optString("visibility"))) continue;
      String slug = item.optString("slug");
      if (slug.isBlank()
          || slug.length() > 160
          || slug.chars().anyMatch(Character::isISOControl)
          || !seen.add(slug)) continue;
      result.add(new Model(slug, item.optString("display_name", slug)));
    }
    if (result.isEmpty()) throw ChatGptHttp.error("models");
    return List.copyOf(result);
  }

  @Override
  public String stream(
      List<TeacherLlmClient.Message> messages,
      TeacherLlmClient.Cancellation cancellation,
      Consumer<String> onText)
      throws IOException, InterruptedException {
    sessions.register(cancellation);
    try {
      return streamCurrent(messages, cancellation, onText);
    } finally {
      sessions.unregister(cancellation);
    }
  }

  private String streamCurrent(
      List<TeacherLlmClient.Message> messages,
      TeacherLlmClient.Cancellation cancellation,
      Consumer<String> onText)
      throws IOException, InterruptedException {
    checkCurrent(cancellation);
    String token = sessions.accessToken(accountId);
    JSONObject body = requestBody(model, messages);
    checkCurrent(cancellation);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(sessions.http.api + "/responses"))
            .timeout(Duration.ofMinutes(5))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    HttpResponse<InputStream> response =
        sessions.http.client().send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream input = response.body()) {
      if (response.statusCode() / 100 != 2) {
        var deadline = ChatGptHttp.closeAfter(input, Duration.ofSeconds(30));
        try {
          throw ChatGptHttp.responseError(
              response.statusCode(),
              input.readNBytes(65536),
              response.headers().firstValue("x-request-id").orElse(""));
        } finally {
          deadline.cancel(false);
        }
      }
      cancellation.attach(input);
      var timer =
          Executors.newSingleThreadScheduledExecutor(
              task -> {
                Thread thread = new Thread(task, "chatgpt-stream-deadline");
                thread.setDaemon(true);
                return thread;
              });
      timer.schedule(cancellation::cancel, 5, TimeUnit.MINUTES);
      try {
        String result =
            readStream(
                input,
                cancellation,
                text -> {
                  checkCurrent(cancellation);
                  onText.accept(text);
                });
        checkCurrent(cancellation);
        return result;
      } finally {
        timer.shutdownNow();
        cancellation.detach(input);
      }
    }
  }

  private void checkCurrent(TeacherLlmClient.Cancellation cancellation) {
    if (cancellation.isCancelled()
        || Thread.currentThread().isInterrupted()
        || sessions.generation() != generation) throw new CancellationException();
  }

  static JSONObject requestBody(String model, List<TeacherLlmClient.Message> messages)
      throws IOException {
    if (model == null || model.isBlank() || messages == null || messages.isEmpty())
      throw ChatGptHttp.error("models");
    JSONArray input = new JSONArray();
    int characters = 0;
    for (TeacherLlmClient.Message message : messages) {
      if (message == null || message.content == null || message.content.isBlank()) continue;
      String role = "system".equals(message.role) ? "developer" : message.role;
      if (!Set.of("developer", "user", "assistant").contains(role))
        throw ChatGptHttp.error("protocol");
      characters += message.content.length();
      if (characters > 300000) throw ChatGptHttp.error("protocol");
      input.put(new JSONObject().put("role", role).put("content", message.content));
    }
    if (input.isEmpty()) throw ChatGptHttp.error("protocol");
    return new JSONObject()
        .put("model", model)
        .put("input", input)
        .put("store", false)
        .put("stream", true);
  }

  static String readStream(
      InputStream input, TeacherLlmClient.Cancellation cancellation, Consumer<String> onText)
      throws IOException {
    StringBuilder text = new StringBuilder();
    StringBuilder data = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (cancellation.isCancelled() || Thread.currentThread().isInterrupted())
          throw new CancellationException();
        if (line.startsWith("data:")) {
          if (data.length() > 0) data.append('\n');
          data.append(line.substring(5).stripLeading());
          if (data.length() > 1024 * 1024) throw ChatGptHttp.error("protocol");
        } else if (line.isEmpty() && data.length() > 0) {
          JSONObject event;
          try {
            event = new JSONObject(data.toString());
          } catch (RuntimeException invalid) {
            throw ChatGptHttp.error("incomplete");
          }
          data.setLength(0);
          String type = event.optString("type");
          if ("response.completed".equals(type)) {
            JSONObject response = event.optJSONObject("response");
            if (response == null
                || !"completed".equals(response.optString("status"))
                || text.length() == 0) {
              throw ChatGptHttp.error("incomplete");
            }
            return text.toString();
          }
          if ("response.failed".equals(type)
              || "response.incomplete".equals(type)
              || "error".equals(type)) {
            JSONObject failedResponse = event.optJSONObject("response");
            JSONObject payload = failedResponse == null ? event : failedResponse;
            if (payload.optJSONObject("error") != null)
              throw new ChatGptApiException(0, payload, "");
            throw ChatGptHttp.error("incomplete");
          }
          if ("response.output_text.delta".equals(type)) {
            String delta = event.optString("delta");
            text.append(delta);
            if (text.length() > 1024 * 1024) throw ChatGptHttp.error("protocol");
            onText.accept(delta);
          }
        }
      }
    }
    throw ChatGptHttp.error("incomplete");
  }

  static final class Model {
    final String slug;
    final String name;

    Model(String slug, String name) {
      this.slug = slug;
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
