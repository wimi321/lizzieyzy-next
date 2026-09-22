package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

public class WebBoardServerTest {
  private WebBoardServer server;
  private final List<TestClient> ownedClients = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    AtomicReference<Exception> startupError = new AtomicReference<>();
    server =
        new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 2) {
          @Override
          public void onStart() {
            ready.countDown();
          }

          @Override
          public void onError(org.java_websocket.WebSocket connection, Exception error) {
            startupError.compareAndSet(null, error);
            ready.countDown();
          }
        };
    server.start();
    assertTrue(ready.await(3, TimeUnit.SECONDS), "server did not become ready");
    assertNull(startupError.get(), "server startup failed");
    assertTrue(server.getPort() > 0, "server did not bind an ephemeral port");
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      // Protocol assertions are complete. Close the fixture's sockets directly so
      // cleanup does not race the server stop against an unfinished close handshake.
      ownedClients.forEach(client -> client.closeConnection(1000, "test fixture cleanup"));
      if (server != null) server.stop(1000);
      for (TestClient client : ownedClients) {
        assertTrue(client.closed.await(3, TimeUnit.SECONDS), "owned client did not close");
      }
    } finally {
      if (server != null) server.stop(1000);
    }
  }

  @Test
  void rejectsConnectionsAboveLimit() throws Exception {
    CountDownLatch openLatch = new CountDownLatch(2);
    CountDownLatch closeLatch = new CountDownLatch(1);

    List<TestClient> clients = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      TestClient c = new TestClient(server.getPort(), openLatch, null);
      assertTrue(c.connectBlocking(2, TimeUnit.SECONDS));
      clients.add(c);
    }
    assertTrue(openLatch.await(3, TimeUnit.SECONDS));
    awaitServerConnections(2);

    TestClient rejected = new TestClient(server.getPort(), null, closeLatch);
    rejected.connectBlocking(2, TimeUnit.SECONDS);
    assertTrue(closeLatch.await(3, TimeUnit.SECONDS), "3rd connection should be closed");
  }

  @Test
  void sendsFullStateOnConnect() throws Exception {
    String fullState = "{\"type\":\"full_state\",\"boardWidth\":19}";
    server.broadcastFullState(fullState);

    CountDownLatch msgLatch = new CountDownLatch(1);
    AtomicReference<String> received = new AtomicReference<>();
    TestClient c =
        new TestClient(server.getPort(), null, null) {
          @Override
          public void onMessage(String msg) {
            received.set(msg);
            msgLatch.countDown();
          }
        };
    assertTrue(c.connectBlocking(2, TimeUnit.SECONDS));
    assertTrue(msgLatch.await(3, TimeUnit.SECONDS));
    assertTrue(received.get().contains("full_state"));
  }

  @Test
  void sendsLatestAnalysisAndHistoryAfterFullStateOnConnect() throws Exception {
    server.broadcastFullState("{\"type\":\"full_state\",\"moveNumber\":2}");
    server.broadcastHistory("{\"type\":\"winrate_history\",\"data\":[]}");
    server.broadcastAnalysis("{\"type\":\"analysis_update\",\"playouts\":100}");
    server.broadcastAnalysis("{\"type\":\"analysis_update\",\"playouts\":900}");
    BlockingQueue<JSONObject> received = new LinkedBlockingQueue<>();
    TestClient client = collectingClient(received);
    assertTrue(client.connectBlocking(2, TimeUnit.SECONDS));
    assertEquals("full_state", next(received).getString("type"));
    assertEquals("winrate_history", next(received).getString("type"));
    JSONObject analysis = next(received);
    assertEquals("analysis_update", analysis.getString("type"));
    assertEquals(900, analysis.getInt("playouts"));
  }

  @Test
  void newBoardClearsCachedAnalysisForNewClients() throws Exception {
    server.broadcastFullState("{\"type\":\"full_state\",\"moveNumber\":1}");
    server.broadcastAnalysis("{\"type\":\"analysis_update\",\"playouts\":100}");
    server.broadcastHistory("{\"type\":\"winrate_history\",\"data\":[]}");
    server.broadcastFullState("{\"type\":\"full_state\",\"moveNumber\":2}");
    BlockingQueue<JSONObject> received = new LinkedBlockingQueue<>();
    TestClient client = collectingClient(received);
    assertTrue(client.connectBlocking(2, TimeUnit.SECONDS));
    assertEquals(2, next(received).getInt("moveNumber"));
    server.broadcastMessage("{\"type\":\"barrier\"}");
    assertEquals("barrier", next(received).getString("type"));
  }

  @Test
  void controlMessagesRemainCompleteAndOrderedDuringStateUpdates() throws Exception {
    BlockingQueue<JSONObject> received = new LinkedBlockingQueue<>();
    TestClient client = collectingClient(received);
    assertTrue(client.connectBlocking(2, TimeUnit.SECONDS));
    awaitServerConnections(1);
    for (int i = 0; i < 100; i++) {
      server.broadcastFullState("{\"type\":\"full_state\",\"moveNumber\":" + i + "}");
      server.broadcastAnalysis("{\"type\":\"analysis_update\",\"playouts\":" + i + "}");
      server.broadcastMessage("{\"type\":\"trial_state\",\"sequence\":" + i + "}");
    }
    int sequence = 0;
    while (sequence < 100) {
      JSONObject message = next(received);
      if ("trial_state".equals(message.getString("type"))) {
        assertEquals(sequence++, message.getInt("sequence"));
      }
    }
  }

  @Test
  void slowTcpReaderBoundsActualSocketQueueAndEventuallyReceivesFinalState() throws Exception {
    CountDownLatch readerBlocked = new CountDownLatch(1);
    CountDownLatch releaseReader = new CountDownLatch(1);
    CountDownLatch finalAnalysis = new CountDownLatch(1);
    AtomicInteger lastBoard = new AtomicInteger(-1);
    TestClient client =
        new TestClient(server.getPort(), null, null) {
          @Override
          public void onMessage(String text) {
            JSONObject message = new JSONObject(text);
            if ("full_state".equals(message.getString("type"))) {
              lastBoard.set(message.getInt("moveNumber"));
              if (message.getInt("moveNumber") == 0) {
                WebBoardUpdateQueueTest.block(readerBlocked, releaseReader);
              }
            } else if ("analysis_update".equals(message.getString("type"))) {
              finalAnalysis.countDown();
            }
          }
        };
    assertTrue(client.connectBlocking(2, TimeUnit.SECONDS));
    awaitServerConnections(1);
    WebSocketImpl connection = (WebSocketImpl) server.getConnections().iterator().next();
    ((SocketChannel) connection.getChannel()).socket().setSendBufferSize(1024);
    server.broadcastFullState("{\"type\":\"full_state\",\"moveNumber\":0}");
    WebBoardUpdateQueueTest.await(readerBlocked);
    try {
      String payload = "x".repeat(65_536);
      boolean buffered = false;
      for (int i = 1; i <= 128; i++) {
        server.broadcastFullState(
            "{\"type\":\"full_state\",\"moveNumber\":" + i + ",\"payload\":\"" + payload + "\"}");
        buffered |= connection.hasBufferedData();
        assertTrue(
            connection.outQueue.size() <= 1, "only one state frame may enter the socket queue");
      }
      assertTrue(buffered, "fixture must exercise actual network backpressure");
      server.broadcastAnalysis("{\"type\":\"analysis_update\",\"playouts\":900}");
      releaseReader.countDown();
      assertTrue(finalAnalysis.await(5, TimeUnit.SECONDS));
      assertEquals(
          128, lastBoard.get(), "final board must precede final analysis without new updates");
    } finally {
      releaseReader.countDown();
    }
  }

  private TestClient collectingClient(BlockingQueue<JSONObject> received) throws Exception {
    return new TestClient(server.getPort(), null, null) {
      @Override
      public void onMessage(String msg) {
        received.add(new JSONObject(msg));
      }
    };
  }

  private static JSONObject next(BlockingQueue<JSONObject> received) throws InterruptedException {
    JSONObject message = received.poll(3, TimeUnit.SECONDS);
    assertNotNull(message, "client did not receive a message");
    return message;
  }

  @RepeatedTest(10)
  void broadcastsToAllClients() throws Exception {
    CountDownLatch openLatch = new CountDownLatch(2);
    CountDownLatch msgLatch = new CountDownLatch(2);
    List<AtomicReference<String>> received = new ArrayList<>();

    List<TestClient> clients = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      AtomicReference<String> ref = new AtomicReference<>();
      received.add(ref);
      TestClient c =
          new TestClient(server.getPort(), openLatch, null) {
            @Override
            public void onMessage(String msg) {
              ref.set(msg);
              msgLatch.countDown();
            }
          };
      assertTrue(c.connectBlocking(2, TimeUnit.SECONDS));
      clients.add(c);
    }
    assertTrue(openLatch.await(3, TimeUnit.SECONDS));
    awaitServerConnections(2);

    server.broadcastMessage("{\"type\":\"test\"}");
    assertTrue(msgLatch.await(3, TimeUnit.SECONDS));

    for (AtomicReference<String> ref : received) {
      assertNotNull(ref.get());
      assertTrue(ref.get().contains("test"));
    }
  }

  @Test
  void onMessageDispatchesEnterTrialToHandler() {
    java.util.concurrent.atomic.AtomicReference<org.json.JSONObject> received =
        new java.util.concurrent.atomic.AtomicReference<>();
    WebBoardServer s = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 20);
    s.setMessageHandler((conn, json) -> received.set(json));
    s.onMessage(null, "{\"type\":\"enter_trial\",\"clientId\":\"abc\"}");
    assertNotNull(received.get());
    assertEquals("enter_trial", received.get().getString("type"));
    assertEquals("abc", received.get().getString("clientId"));
  }

  @Test
  void onMessageIgnoresMalformedJson() {
    java.util.concurrent.atomic.AtomicBoolean called =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    WebBoardServer s = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 20);
    s.setMessageHandler((conn, json) -> called.set(true));
    s.onMessage(null, "not json");
    assertFalse(called.get());
  }

  @Test
  void onMessageNoOpWhenHandlerNotSet() {
    WebBoardServer s = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 20);
    s.onMessage(null, "{\"type\":\"x\"}");
  }

  private void awaitServerConnections(int count) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (server.getConnections().size() != count && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertEquals(
        count, server.getConnections().size(), "server must register clients before broadcast");
  }

  private class TestClient extends WebSocketClient {
    private final CountDownLatch openLatch;
    private final CountDownLatch closeLatch;
    private final CountDownLatch closed = new CountDownLatch(1);

    TestClient(int port, CountDownLatch openLatch, CountDownLatch closeLatch) throws Exception {
      super(new URI("ws://127.0.0.1:" + port));
      this.openLatch = openLatch;
      this.closeLatch = closeLatch;
      ownedClients.add(this);
    }

    @Override
    public void onOpen(ServerHandshake h) {
      if (openLatch != null) openLatch.countDown();
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
      closed.countDown();
      if (closeLatch != null) closeLatch.countDown();
    }

    @Override
    public void onMessage(String msg) {}

    @Override
    public void onError(Exception e) {}
  }
}
