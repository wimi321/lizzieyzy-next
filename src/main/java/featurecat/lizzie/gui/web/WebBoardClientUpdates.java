package featurecat.lizzie.gui.web;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;

/** Latest state slots per connection; never add more state frames to a buffered socket. */
final class WebBoardClientUpdates implements AutoCloseable {
  private final ScheduledThreadPoolExecutor executor;
  private final Map<WebSocket, Pending> clients = new HashMap<>();
  private ScheduledFuture<?> retry;
  private long retryGeneration;
  private String fullState;
  private String analysis;
  private String history;
  private boolean closed;

  WebBoardClientUpdates() {
    this(
        new ScheduledThreadPoolExecutor(
            1,
            r -> {
              Thread thread = new Thread(r, "WebBoardClientUpdates");
              thread.setDaemon(true);
              return thread;
            }));
  }

  WebBoardClientUpdates(ScheduledThreadPoolExecutor executor) {
    this.executor = executor;
    executor.setRemoveOnCancelPolicy(true);
  }

  synchronized void connected(WebSocket connection) {
    if (closed || !connection.isOpen()) return;
    Pending pending = new Pending();
    pending.fullState = fullState;
    pending.analysis = analysis;
    pending.history = history;
    clients.put(connection, pending);
    flush();
  }

  synchronized void disconnected(WebSocket connection) {
    clients.remove(connection);
    if (clients.isEmpty()) cancelRetry();
  }

  synchronized void fullState(String message) {
    if (closed) return;
    fullState = message;
    analysis = null;
    history = null;
    for (Pending pending : clients.values()) {
      pending.fullState = message;
      // A new board supersedes all unsent analysis and history from the old board.
      pending.analysis = null;
      pending.history = null;
    }
    flush();
  }

  synchronized void analysis(String message) {
    if (closed) return;
    analysis = message;
    for (Pending pending : clients.values()) pending.analysis = message;
    flush();
  }

  synchronized void history(String message) {
    if (closed) return;
    history = message;
    for (Pending pending : clients.values()) pending.history = message;
    flush();
  }

  private void flush() {
    boolean waiting = false;
    Iterator<Map.Entry<WebSocket, Pending>> iterator = clients.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<WebSocket, Pending> entry = iterator.next();
      WebSocket connection = entry.getKey();
      if (!connection.isOpen()) {
        iterator.remove();
        continue;
      }
      Pending pending = entry.getValue();
      try {
        // send() only enqueues locally. Do not wait for network IO or one client's drain.
        while (pending.hasMessages() && !connection.hasBufferedData()) {
          connection.send(pending.removeFirst());
        }
        waiting |= pending.hasMessages();
      } catch (WebsocketNotConnectedException ignored) {
        iterator.remove();
      }
    }
    if (waiting && retry == null) {
      long generation = ++retryGeneration;
      try {
        retry = executor.schedule(() -> retry(generation), 25, TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException ignored) {
        close();
      }
    } else if (!waiting) {
      cancelRetry();
    }
  }

  private synchronized void retry(long generation) {
    if (closed || generation != retryGeneration) return;
    retry = null;
    flush();
  }

  private void cancelRetry() {
    if (retry != null) {
      retry.cancel(false);
      retry = null;
      retryGeneration++;
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    cancelRetry();
    clients.clear();
    fullState = null;
    analysis = null;
    history = null;
    executor.shutdownNow();
  }

  private static final class Pending {
    String fullState;
    String analysis;
    String history;

    boolean hasMessages() {
      return fullState != null || analysis != null || history != null;
    }

    String removeFirst() {
      String message;
      if (fullState != null) {
        message = fullState;
        fullState = null;
      } else if (history != null) {
        message = history;
        history = null;
      } else {
        message = analysis;
        analysis = null;
      }
      return message;
    }
  }
}
