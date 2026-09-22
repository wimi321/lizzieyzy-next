package featurecat.lizzie.gui.web;

import java.net.InetSocketAddress;
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
  private final WebBoardClientUpdates updates = new WebBoardClientUpdates();
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
    updates.connected(conn);
  }

  @Override
  public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    updates.disconnected(conn);
  }

  @Override
  public void stop(int timeout, String closeMessage) throws InterruptedException {
    updates.close();
    super.stop(timeout, closeMessage);
  }

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
    updates.fullState(json);
  }

  public void broadcastAnalysis(String json) {
    updates.analysis(json);
  }

  public void broadcastHistory(String json) {
    updates.history(json);
  }

  public void sendToConnection(WebSocket conn, String json) {
    if (conn != null && conn.isOpen()) conn.send(json);
  }
}
