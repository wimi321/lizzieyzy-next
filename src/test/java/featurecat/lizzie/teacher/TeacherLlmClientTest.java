package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.logging.DiagnosticModule;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeacherLlmClientTest {
  private HttpServer server;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
  }

  @Test
  void listsModelsWithoutSendingACompletionProbe() throws Exception {
    AtomicInteger modelCalls = new AtomicInteger();
    server.createContext(
        "/v1/models",
        exchange -> {
          modelCalls.incrementAndGet();
          assertEquals("Bearer secret", exchange.getRequestHeaders().getFirst("Authorization"));
          respond(exchange, 200, "{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"}]}");
        });
    server.start();

    TeacherLlmClient client = new TeacherLlmClient(baseUrl, "secret", "a-model");
    assertEquals(List.of("a-model", "z-model"), client.listModels());
    assertEquals(1, modelCalls.get());
  }

  @Test
  void streamsChatCompletionsWithoutAnExtraPaidPing() throws Exception {
    AtomicInteger chatCalls = new AtomicInteger();
    AtomicInteger responsesCalls = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          chatCalls.incrementAndGet();
          respondSse(
              exchange,
              "data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n"
                  + "data: {\"choices\":[{\"delta\":{\"content\":\"Go\"}}]}\n\n"
                  + "data: [DONE]\n\n");
        });
    server.createContext(
        "/v1/responses",
        exchange -> {
          responsesCalls.incrementAndGet();
          respond(exchange, 500, "unexpected");
        });
    server.start();

    StringBuilder streamed = new StringBuilder();
    String result =
        new TeacherLlmClient(baseUrl, "secret", "model")
            .stream(
                List.of(new TeacherLlmClient.Message("user", "Explain")),
                new TeacherLlmClient.Cancellation(),
                streamed::append);

    assertEquals("Hello Go", result);
    assertEquals(result, streamed.toString());
    assertEquals(1, chatCalls.get());
    assertEquals(0, responsesCalls.get());
  }

  @Test
  void fallsBackToResponsesOnlyWhenChatEndpointIsUnavailable() throws Exception {
    AtomicInteger responsesCalls = new AtomicInteger();
    server.createContext("/v1/chat/completions", exchange -> respond(exchange, 404, "not found"));
    server.createContext(
        "/v1/responses",
        exchange -> {
          responsesCalls.incrementAndGet();
          respondSse(
              exchange,
              "event: response.output_text.delta\n"
                  + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Fallback works\"}\n\n"
                  + "data: [DONE]\n\n");
        });
    server.start();

    String result =
        new TeacherLlmClient(baseUrl, "secret", "model")
            .stream(
                List.of(new TeacherLlmClient.Message("user", "Explain")),
                new TeacherLlmClient.Cancellation(),
                ignored -> {});

    assertEquals("Fallback works", result);
    assertEquals(1, responsesCalls.get());
  }

  @Test
  void cancellationClosesAnActiveSseRead() throws Exception {
    CountDownLatch firstChunkReceived = new CountDownLatch(1);
    CountDownLatch releaseServer = new CountDownLatch(1);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (java.io.OutputStream output = exchange.getResponseBody()) {
            output.write(
                "data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
            try {
              releaseServer.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          } catch (IOException clientClosedStream) {
          }
        });
    server.start();

    TeacherLlmClient.Cancellation cancellation = new TeacherLlmClient.Cancellation();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<String> future =
          executor.submit(
              () ->
                  new TeacherLlmClient(baseUrl, "secret", "model")
                      .stream(
                          List.of(new TeacherLlmClient.Message("user", "Explain")),
                          cancellation,
                          text -> firstChunkReceived.countDown()));
      assertTrue(firstChunkReceived.await(2, TimeUnit.SECONDS));
      cancellation.cancel();
      assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
    } finally {
      releaseServer.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void doesNotFollowRedirectsWithTheAuthorizationHeader() throws Exception {
    AtomicInteger redirectedCalls = new AtomicInteger();
    server.createContext(
        "/v1/models",
        exchange -> {
          exchange.getResponseHeaders().set("Location", baseUrl + "/redirect-target");
          respond(exchange, 307, "redirect");
        });
    server.createContext(
        "/v1/redirect-target",
        exchange -> {
          redirectedCalls.incrementAndGet();
          respond(exchange, 200, "{\"data\":[]}");
        });
    server.start();

    IOException error =
        assertThrows(
            IOException.class,
            () -> new TeacherLlmClient(baseUrl, "secret-value", "model").listModels());

    assertEquals(0, redirectedCalls.get());
    assertFalse(error.getMessage().contains("secret-value"));
  }

  @Test
  void surfacesStructuredStreamingErrorsWithoutLeakingTokens() throws Exception {
    server.createContext(
        "/v1/chat/completions",
        exchange ->
            respondSse(
                exchange,
                "data: {\"error\":{\"message\":\"quota exhausted\",\"token\":\"private-token\"}}\n\n"));
    server.start();

    IOException error =
        assertThrows(
            IOException.class,
            () ->
                new TeacherLlmClient(baseUrl, "secret-value", "model")
                    .stream(
                        List.of(new TeacherLlmClient.Message("user", "Explain")),
                        new TeacherLlmClient.Cancellation(),
                        ignored -> {}));

    assertTrue(error.getMessage().contains("quota exhausted"));
    assertFalse(error.getMessage().contains("private-token"));
    assertFalse(error.getMessage().contains("secret-value"));
  }

  @Test
  void recordsSuccessfulModelListAsOtherNotCredential(@TempDir Path tempDir) throws Exception {
    LoggingRuntime runtime = startNetworkDiagnostics(tempDir);
    server.createContext(
        "/v1/models", exchange -> respond(exchange, 200, "{\"data\":[{\"id\":\"a-model\"}]}"));
    server.start();
    try {
      assertEquals(
          List.of("a-model"),
          new TeacherLlmClient(baseUrl, "secret-value", "a-model").listModels());
      runtime.shutdown();
      String app = Files.readString(tempDir.resolve("logs/app.log"), StandardCharsets.UTF_8);
      assertTrue(app.contains("network event=http"), app);
      assertTrue(app.contains("method=GET"), app);
      assertTrue(app.contains("category=other"), app);
      assertTrue(app.contains("outcome=ok"), app);
      assertFalse(app.contains("category=credential"), app);
      assertFalse(app.contains("secret-value"), app);
    } finally {
      LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    }
  }

  @Test
  void recordsTransportFailureWithoutLeakingKeyOrPrompt(@TempDir Path tempDir) throws Exception {
    LoggingRuntime runtime = startNetworkDiagnostics(tempDir);
    int port;
    try (ServerSocket socket = new ServerSocket()) {
      socket.bind(new InetSocketAddress("127.0.0.1", 0));
      port = socket.getLocalPort();
    }
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    try {
      assertThrows(
          IOException.class,
          () ->
              new TeacherLlmClient(
                      httpClient,
                      "http://127.0.0.1:" + port + "/v1",
                      "T05_TEACHER_KEY_CANARY",
                      "model")
                  .stream(
                      List.of(
                          new TeacherLlmClient.Message("user", "T05_TEACHER_PROMPT_CANARY")),
                      new TeacherLlmClient.Cancellation(),
                      ignored -> {}));
      runtime.shutdown();
      String app = Files.readString(tempDir.resolve("logs/app.log"), StandardCharsets.UTF_8);
      assertTrue(app.contains("network event=http"), app);
      assertTrue(app.contains("method=POST"), app);
      assertTrue(app.contains("category=other"), app);
      assertTrue(app.contains("status=0"), app);
      assertTrue(app.contains("outcome=failed"), app);
      assertFalse(app.contains("category=credential"), app);
      assertFalse(app.contains("T05_TEACHER_KEY_CANARY"), app);
      assertFalse(app.contains("T05_TEACHER_PROMPT_CANARY"), app);
    } finally {
      LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    }
  }

  private static LoggingRuntime startNetworkDiagnostics(Path tempDir) {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    runtime.applySettings(
        LoggingSettings.defaults()
            .withDiagnosticsEnabled(true)
            .withDiagnosticModules(EnumSet.of(DiagnosticModule.NETWORK_REMOTE)));
    return runtime;
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (java.io.OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static void respondSse(HttpExchange exchange, String body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    respond(exchange, 200, body);
  }
}
