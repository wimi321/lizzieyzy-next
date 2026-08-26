package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TeacherRequestControllerTest {
  private HttpServer server;
  private String baseUrl;
  private TeacherRequestController controller;
  private CountDownLatch releaseBlockedResponses;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    controller = new TeacherRequestController();
    releaseBlockedResponses = new CountDownLatch(0);
  }

  @AfterEach
  void tearDown() {
    if (releaseBlockedResponses != null) {
      releaseBlockedResponses.countDown();
    }
    if (controller != null) {
      controller.close();
    }
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void forwardsCurrentRequestTextInOrderAndCompletesOnce() throws Exception {
    server.createContext(
        "/v1/chat/completions",
        exchange ->
            respondSse(
                exchange,
                sseDelta("Hello ") + sseDelta("Go") + "data: [DONE]\n\n"));
    server.start();

    RecordingListener listener = new RecordingListener();
    controller.start(client(), prompt(), listener);

    assertTrue(listener.completed.await(2, TimeUnit.SECONDS));
    drainControllerExecutor();
    assertEquals(List.of("Hello ", "Go"), listener.texts);
    assertEquals(List.of("Hello Go"), listener.completes);
    assertTrue(listener.failures.isEmpty());
    assertEquals(0, listener.cancelled.get());
  }

  @Test
  void propagatesCurrentRequestFailureOnceWithoutCompleting() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          if (calls.incrementAndGet() == 1) {
            respond(exchange, 500, "{\"error\":{\"message\":\"quota exhausted\"}}");
          } else {
            respondSse(exchange, sseDelta("drain") + "data: [DONE]\n\n");
          }
        });
    server.start();

    RecordingListener listener = new RecordingListener();
    controller.start(client(), prompt(), listener);

    assertTrue(listener.failed.await(2, TimeUnit.SECONDS));
    drainControllerExecutor();
    assertEquals(1, listener.failures.size());
    Throwable error = listener.failures.get(0);
    assertTrue(error instanceof IOException);
    assertTrue(error.getMessage().contains("HTTP 500"));
    assertTrue(error.getMessage().contains("quota exhausted"));
    assertTrue(listener.texts.isEmpty());
    assertTrue(listener.completes.isEmpty());
    assertEquals(0, listener.cancelled.get());
  }

  @Test
  void explicitCancelStopsRunningAndDropsLateCallbacks() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    releaseBlockedResponses = release;
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          if (calls.incrementAndGet() == 1) {
            holdAfterFirstChunk(exchange, "first", "stale-A", release);
          } else {
            respondSse(exchange, sseDelta("sentinel") + "data: [DONE]\n\n");
          }
        });
    server.start();

    RecordingListener cancelledRequest = new RecordingListener();
    controller.start(client(), prompt(), cancelledRequest);
    assertTrue(cancelledRequest.firstText.await(2, TimeUnit.SECONDS));
    assertTrue(controller.isRunning());

    controller.cancel();
    assertFalse(controller.isRunning());
    release.countDown();

    RecordingListener sentinel = new RecordingListener();
    controller.start(client(), prompt(), sentinel);
    assertTrue(sentinel.completed.await(2, TimeUnit.SECONDS));

    assertEquals(List.of("first"), cancelledRequest.texts);
    assertTrue(cancelledRequest.completes.isEmpty());
    assertTrue(cancelledRequest.failures.isEmpty());
    assertEquals(0, cancelledRequest.cancelled.get());
    assertEquals(List.of("sentinel"), sentinel.texts);
    assertEquals(List.of("sentinel"), sentinel.completes);
  }

  @Test
  void replacementIsolatesStaleGenerationAndCompletesTheNewRequest() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    releaseBlockedResponses = release;
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          if (calls.incrementAndGet() == 1) {
            holdAfterFirstChunk(exchange, "first", "stale-A", release);
          } else {
            respondSse(exchange, sseDelta("request-B") + "data: [DONE]\n\n");
          }
        });
    server.start();

    RecordingListener requestA = new RecordingListener();
    controller.start(client(), prompt(), requestA);
    assertTrue(requestA.firstText.await(2, TimeUnit.SECONDS));
    assertTrue(controller.isRunning());

    RecordingListener requestB = new RecordingListener();
    controller.start(client(), prompt(), requestB);
    release.countDown();

    assertTrue(requestB.completed.await(2, TimeUnit.SECONDS));
    assertEquals(List.of("first"), requestA.texts);
    assertTrue(requestA.completes.isEmpty());
    assertTrue(requestA.failures.isEmpty());
    assertEquals(0, requestA.cancelled.get());
    drainControllerExecutor();
    assertEquals(List.of("request-B"), requestB.texts);
    assertEquals(List.of("request-B"), requestB.completes);
    assertTrue(requestB.failures.isEmpty());
    assertEquals(0, requestB.cancelled.get());
  }

  @Test
  void closeCancelsActiveRequestAndDropsLateCallbacks() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch handlerFinished = new CountDownLatch(1);
    releaseBlockedResponses = release;
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          try {
            holdAfterFirstChunk(exchange, "first", "stale-A", release);
          } finally {
            handlerFinished.countDown();
          }
        });
    server.start();

    RecordingListener listener = new RecordingListener();
    controller.start(client(), prompt(), listener);
    assertTrue(listener.firstText.await(2, TimeUnit.SECONDS));
    assertTrue(controller.isRunning());

    controller.close();
    assertFalse(controller.isRunning());
    release.countDown();
    assertTrue(handlerFinished.await(2, TimeUnit.SECONDS));
    assertFalse(listener.completed.await(2, TimeUnit.SECONDS));
    assertFalse(listener.failed.await(2, TimeUnit.SECONDS));

    assertEquals(List.of("first"), listener.texts);
    assertTrue(listener.completes.isEmpty());
    assertTrue(listener.failures.isEmpty());
    assertEquals(0, listener.cancelled.get());
  }

  private TeacherLlmClient client() throws IOException {
    return new TeacherLlmClient(baseUrl, "secret", "model");
  }

  private void drainControllerExecutor() throws Exception {
    RecordingListener drain = new RecordingListener();
    controller.start(client(), prompt(), drain);
    assertTrue(drain.completed.await(2, TimeUnit.SECONDS));
  }

  private static List<TeacherLlmClient.Message> prompt() {
    return List.of(new TeacherLlmClient.Message("user", "Explain"));
  }

  private static void holdAfterFirstChunk(
      HttpExchange exchange, String first, String stale, CountDownLatch release)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(sseDelta(first).getBytes(StandardCharsets.UTF_8));
      output.flush();
      try {
        release.await(3, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      output.write((sseDelta(stale) + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
      output.flush();
    } catch (IOException clientClosedStream) {
    }
  }

  private static String sseDelta(String text) {
    return "data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}\n\n";
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static void respondSse(HttpExchange exchange, String body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    respond(exchange, 200, body);
  }

  private static final class RecordingListener implements TeacherRequestController.Listener {
    private final List<String> texts = new CopyOnWriteArrayList<>();
    private final List<String> completes = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final AtomicInteger cancelled = new AtomicInteger();
    private final CountDownLatch firstText = new CountDownLatch(1);
    private final CountDownLatch completed = new CountDownLatch(1);
    private final CountDownLatch failed = new CountDownLatch(1);

    @Override
    public void onText(String text) {
      texts.add(text);
      firstText.countDown();
    }

    @Override
    public void onComplete(String fullText) {
      completes.add(fullText);
      completed.countDown();
    }

    @Override
    public void onFailure(Throwable error) {
      failures.add(error);
      failed.countDown();
    }

    @Override
    public void onCancelled() {
      cancelled.incrementAndGet();
    }
  }
}
