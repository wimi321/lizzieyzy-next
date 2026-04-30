package featurecat.lizzie.gui.web;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

public class WebBoardServer extends WebSocketServer {
  @FunctionalInterface
  public interface MessageHandler {
    void handle(WebSocket conn, JSONObject message);
  }

  private final int maxConnections;
  private final AtomicReference<String> lastFullState = new AtomicReference<>();
  private volatile MessageHandler messageHandler;

  public WebBoardServer(InetSocketAddress address, int maxConnections) {
    super(address);
    this.maxConnections = maxConnections;
    setReuseAddr(true);
  }

  public void setMessageHandler(MessageHandler h) {
    this.messageHandler = h;
  }

  @Override
  public void onOpen(WebSocket conn, ClientHandshake handshake) {
    if (getConnections().size() > maxConnections) {
      conn.close(1013, "Max connections reached");
      return;
    }
    String state = lastFullState.get();
    if (state != null) {
      conn.send(state);
    }
  }

  @Override
  public void onClose(WebSocket conn, int code, String reason, boolean remote) {}

  @Override
  public void onMessage(WebSocket conn, String message) {
    MessageHandler h = messageHandler;
    if (h == null) return;
    try {
      JSONObject json = new JSONObject(message);
      h.handle(conn, json);
    } catch (org.json.JSONException ignored) {
    }
  }

  @Override
  public void onError(WebSocket conn, Exception ex) {}

  @Override
  public void onStart() {}

  public void broadcastMessage(String json) {
    broadcast(json);
  }

  public void broadcastFullState(String json) {
    lastFullState.set(json);
    broadcast(json);
  }

  public void sendToConnection(WebSocket conn, String json) {
    if (conn != null && conn.isOpen()) conn.send(json);
  }
}
