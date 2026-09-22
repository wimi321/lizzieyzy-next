package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.java_websocket.WebSocket;
import org.junit.jupiter.api.Test;

class WebBoardClientUpdatesTest {
  @Test
  void bufferedClientRetainsOnlyLatestStateWhileOtherClientsContinue() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    try (WebBoardClientUpdates updates = new WebBoardClientUpdates(executor)) {
      Sink slow = new Sink(true);
      Sink fast = new Sink(false);
      slow.buffered.set(true);
      updates.connected(slow.socket);
      updates.connected(fast.socket);
      for (int i = 0; i < 10_000; i++) {
        updates.fullState("board-" + i);
        updates.history("history-" + i);
        updates.analysis("analysis-" + i);
      }
      assertTrue(slow.sent.isEmpty(), "do not add state frames to a buffered connection");
      assertEquals(30_000, fast.sent.size(), "slow clients cannot hold up a writable client");
      assertTrue(executor.getQueue().size() <= 1, "one retry timer for all clients");
      slow.buffered.set(false);
      assertEquals("board-9999", slow.next());
      assertTrue(slow.sent.isEmpty());
      slow.buffered.set(false);
      assertEquals("history-9999", slow.next());
      slow.buffered.set(false);
      assertEquals("analysis-9999", slow.next());
      assertTrue(slow.sent.isEmpty(), "intermediate states were replaced, not retained");
    }
    assertTrue(executor.getQueue().isEmpty());
    assertTrue(executor.isShutdown());
  }

  @Test
  void newFullStateInvalidatesUnsentAnalysisAndHistory() throws Exception {
    try (WebBoardClientUpdates updates = new WebBoardClientUpdates()) {
      Sink client = new Sink(true);
      updates.connected(client.socket);
      updates.fullState("old-board");
      assertEquals("old-board", client.next());
      updates.analysis("old-analysis");
      updates.history("old-history");
      updates.fullState("new-board");
      updates.analysis("new-analysis");
      client.buffered.set(false);
      assertEquals("new-board", client.next());
      client.buffered.set(false);
      assertEquals("new-analysis", client.next());
      assertTrue(client.sent.isEmpty());
    }
  }

  @Test
  void newlyConnectedClientReceivesLatestBoardHistoryAndAnalysis() throws Exception {
    try (WebBoardClientUpdates updates = new WebBoardClientUpdates()) {
      updates.fullState("board");
      updates.history("history");
      updates.analysis("analysis-1");
      updates.analysis("analysis-2");
      Sink client = new Sink(false);
      updates.connected(client.socket);
      assertEquals("board", client.next());
      assertEquals("history", client.next());
      assertEquals("analysis-2", client.next());
      assertTrue(client.sent.isEmpty());
    }
  }

  @Test
  void disconnectRemovesPendingClientAndCancelsItsRetry() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    try (WebBoardClientUpdates updates = new WebBoardClientUpdates(executor)) {
      Sink client = new Sink(true);
      client.buffered.set(true);
      updates.connected(client.socket);
      updates.fullState("unsent");
      updates.disconnected(client.socket);
      assertTrue(executor.getQueue().isEmpty());
      client.buffered.set(false);
      updates.analysis("later");
      assertTrue(client.sent.isEmpty());
      client.open.set(false);
      updates.connected(client.socket);
      updates.fullState("closed");
      assertTrue(client.sent.isEmpty());
    }
  }

  @Test
  void closedConnectionIsRemovedWhenItsCloseCallbackHasNotRunYet() {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    try (WebBoardClientUpdates updates = new WebBoardClientUpdates(executor)) {
      Sink client = new Sink(true);
      client.buffered.set(true);
      updates.connected(client.socket);
      updates.fullState("pending");
      client.open.set(false);
      updates.analysis("closed");
      assertTrue(executor.getQueue().isEmpty());
      assertTrue(client.sent.isEmpty());
    }
  }

  @Test
  void closeClearsPendingStatesAndPreventsReconnectOrFurtherWrites() {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    WebBoardClientUpdates updates = new WebBoardClientUpdates(executor);
    Sink client = new Sink(true);
    client.buffered.set(true);
    updates.connected(client.socket);
    updates.fullState("pending");
    updates.close();
    client.buffered.set(false);
    updates.connected(client.socket);
    updates.fullState("closed-board");
    updates.history("closed-history");
    updates.analysis("closed-analysis");
    assertTrue(client.sent.isEmpty());
    assertTrue(executor.getQueue().isEmpty());
    assertTrue(executor.isShutdown());
  }

  /** Models the public socket contract: send enqueues, hasBufferedData stays true until drained. */
  private static final class Sink {
    final AtomicBoolean open = new AtomicBoolean(true);
    final AtomicBoolean buffered = new AtomicBoolean();
    final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
    final WebSocket socket;

    Sink(boolean bufferOnSend) {
      socket =
          (WebSocket)
              Proxy.newProxyInstance(
                  WebSocket.class.getClassLoader(),
                  new Class<?>[] {WebSocket.class},
                  (proxy, method, arguments) -> {
                    switch (method.getName()) {
                      case "isOpen":
                        return open.get();
                      case "hasBufferedData":
                        return buffered.get();
                      case "send":
                        assertTrue(open.get());
                        assertFalse(buffered.get(), "state must wait for the socket buffer");
                        if (bufferOnSend) buffered.set(true);
                        sent.add((String) arguments[0]);
                        return null;
                      case "hashCode":
                        return System.identityHashCode(proxy);
                      case "equals":
                        return proxy == arguments[0];
                      case "toString":
                        return "test socket";
                      default:
                        throw new UnsupportedOperationException(method.getName());
                    }
                  });
    }

    String next() throws InterruptedException {
      String message = sent.poll(5, TimeUnit.SECONDS);
      assertNotNull(message, "timed out waiting for socket send");
      return message;
    }
  }
}
