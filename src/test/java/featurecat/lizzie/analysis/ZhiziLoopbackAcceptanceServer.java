package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.EngineTransport;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.analysis.remote.ZhiziApiClient;
import featurecat.lizzie.analysis.remote.ZhiziGtpTransport;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Real Socket.IO transport and real KataGo, with local-only token issuance and no cloud billing.
 */
public final class ZhiziLoopbackAcceptanceServer extends WebSocketServer implements AutoCloseable {
  private final Path engine;
  private final Path model;
  private final Path config;
  private final ConcurrentHashMap<WebSocket, Process> processes = new ConcurrentHashMap<>();
  private final ScheduledExecutorService events = Executors.newSingleThreadScheduledExecutor();
  private final CountDownLatch listening = new CountDownLatch(1);
  public final AtomicInteger sessions = new AtomicInteger();
  public final AtomicInteger tokens = new AtomicInteger();
  public final List<String> commands = new CopyOnWriteArrayList<>();
  public volatile long readyDelayMillis;

  public ZhiziLoopbackAcceptanceServer(Path engine, Path model, Path config) throws Exception {
    super(new InetSocketAddress("127.0.0.1", 0));
    this.engine = engine;
    this.model = model;
    this.config = config;
    start();
    if (!listening.await(5, TimeUnit.SECONDS)) throw new AssertionError("Loopback server startup");
    events.scheduleAtFixedRate(
        () -> getConnections().forEach(c -> c.send("2")), 1, 1, TimeUnit.SECONDS);
  }

  public Leelaz installPrimary() throws Exception {
    ZhiziApiClient api =
        new ZhiziApiClient(URI.create("http://127.0.0.1"), HttpClient.newHttpClient()) {
          @Override
          public SocketToken fetchSocketioToken(String token, String args) {
            return new SocketToken(
                "loopback-" + tokens.incrementAndGet(), "http://127.0.0.1:" + getPort());
          }
        };
    Leelaz primary =
        new Leelaz(RemoteComputeConfig.COMMAND_ZHIZI) {
          @Override
          protected EngineTransport createRemoteTransport() throws java.io.IOException {
            return new ZhiziGtpTransport(
                api, "loopback-test-account", RemoteComputeConfig.DEFAULT_ZHIZI_ARGS);
          }
        };
    primary.width = primary.oriWidth = 19;
    primary.height = primary.oriHeight = 19;
    primary.komi = primary.orikomi = 7.5f;
    primary.currentEnginename = primary.oriEnginename = "Zhizi protocol acceptance";
    Lizzie.engineManager.engineList.set(0, primary);
    Lizzie.engineManager.switchEngine(0, true);
    return primary;
  }

  public void disconnect() {
    for (WebSocket connection : getConnections()) connection.close(1001, "test disconnect");
  }

  @Override
  public void onOpen(WebSocket connection, ClientHandshake handshake) {
    sessions.incrementAndGet();
    connection.send(
        "0"
            + new JSONObject()
                .put("sid", "local-session")
                .put("upgrades", new JSONArray())
                .put("pingInterval", 1000)
                .put("pingTimeout", 20000)
                .put("maxPayload", 1000000));
  }

  @Override
  public void onMessage(WebSocket connection, String message) {
    try {
      if (message.startsWith("40")) {
        connection.send("40{\"sid\":\"local-namespace\"}");
        Process process =
            new ProcessBuilder(
                    engine.toString(),
                    "gtp",
                    "-model",
                    model.toString(),
                    "-config",
                    config.toString())
                .directory(config.getParent().toFile())
                .start();
        processes.put(connection, process);
        events.schedule(
            () -> {
              if (!connection.isOpen()) return;
              emit(connection, "ready", "");
              pipe(connection, process.getInputStream(), "stdout");
              pipe(connection, process.getErrorStream(), "stderr");
            },
            readyDelayMillis,
            TimeUnit.MILLISECONDS);
      } else if (message.startsWith("42")) {
        JSONArray event = new JSONArray(message.substring(2));
        if ("stdin".equals(event.getString(0))) {
          String command = event.getString(1);
          commands.add(command.strip());
          Process process = processes.get(connection);
          process.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
          process.getOutputStream().flush();
        }
      }
    } catch (Exception error) {
      error.printStackTrace();
      connection.close(1011, "local fixture failure");
    }
  }

  private void pipe(WebSocket connection, InputStream stream, String event) {
    Thread thread =
        new Thread(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && connection.isOpen())
                  emit(connection, event, line + "\n");
              } catch (Exception error) {
                if (connection.isOpen()) error.printStackTrace();
              }
            },
            "loopback-katago-" + event);
    thread.setDaemon(true);
    thread.start();
  }

  private void emit(WebSocket connection, String event, String value) {
    if (connection.isOpen()) connection.send("42" + new JSONArray().put(event).put(value));
  }

  @Override
  public void onClose(WebSocket connection, int code, String reason, boolean remote) {
    Process process = processes.remove(connection);
    if (process != null) process.destroyForcibly();
  }

  @Override
  public void onError(WebSocket connection, Exception error) {
    error.printStackTrace();
  }

  @Override
  public void onStart() {
    listening.countDown();
  }

  @Override
  public void close() throws Exception {
    events.shutdownNow();
    for (Process process : processes.values()) process.destroyForcibly();
    stop(3000);
  }
}
