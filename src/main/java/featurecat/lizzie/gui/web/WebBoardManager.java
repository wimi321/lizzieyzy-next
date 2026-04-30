package featurecat.lizzie.gui.web;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public class WebBoardManager {
  private static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000L;

  @FunctionalInterface
  public interface DisplayNodeOverrideSink {
    void set(BoardHistoryNode node);
  }

  public static class TrialSession {
    public final String ownerClientId;
    public final BoardHistoryNode anchorNode;
    public volatile BoardHistoryNode displayNode;
    volatile long lastActivityMs;
    ScheduledFuture<?> idleTimer;

    TrialSession(String owner, BoardHistoryNode anchor) {
      this.ownerClientId = owner;
      this.anchorNode = anchor;
      this.displayNode = anchor;
      this.lastActivityMs = System.currentTimeMillis();
    }
  }

  private volatile WebBoardServer wsServer;
  private volatile WebBoardHttpServer httpServer;
  private volatile WebBoardDataCollector collector;
  private volatile boolean running;
  private volatile String accessUrl;
  private int actualHttpPort;
  private int actualWsPort;

  private volatile DisplayNodeOverrideSink overrideSink =
      node -> Lizzie.frame.setDisplayNodeOverride(node);
  private volatile TrialSession activeSession;

  public synchronized boolean start() {
    if (running) return true;
    JSONObject cfg = Lizzie.config.config.optJSONObject("web-board");
    int httpPort = cfg != null ? cfg.optInt("http-port", 9998) : 9998;
    int wsPort = cfg != null ? cfg.optInt("ws-port", 9999) : 9999;
    int maxConn = cfg != null ? cfg.optInt("max-connections", 20) : 20;

    httpServer = null;
    for (int i = 0; i < 10; i++) {
      try {
        httpServer = new WebBoardHttpServer(httpPort + i);
        httpServer.start();
        actualHttpPort = httpPort + i;
        break;
      } catch (Exception e) {
        httpServer = null;
      }
    }
    if (httpServer == null) return false;

    wsServer = null;
    for (int i = 0; i < 10; i++) {
      int candidatePort = wsPort + i;
      if (!isPortAvailable(candidatePort)) continue;
      try {
        wsServer = new WebBoardServer(new InetSocketAddress("0.0.0.0", candidatePort), maxConn);
        wsServer.start();
        actualWsPort = candidatePort;
        break;
      } catch (Exception e) {
        wsServer = null;
      }
    }
    if (wsServer == null) {
      httpServer.stop();
      return false;
    }

    httpServer.setWsPort(actualWsPort);

    collector = new WebBoardDataCollector();
    collector.setServer(wsServer);

    wsServer.setMessageHandler(this::handleClientMessage);

    String ip = getLanIp();
    accessUrl = "http://" + ip + ":" + actualHttpPort;
    running = true;
    collector.onBoardStateChanged();
    return true;
  }

  private void handleClientMessage(org.java_websocket.WebSocket conn, JSONObject msg) {
    String type = msg.optString("type");
    switch (type) {
      case "enter_trial":
        {
          String clientId = msg.optString("clientId");
          BoardHistoryNode anchor = Lizzie.board.getHistory().getCurrentHistoryNode();
          boolean ok = enterTrial(clientId, anchor);
          if (!ok) {
            JSONObject denied =
                new JSONObject()
                    .put("type", "trial_denied")
                    .put("reason", "in_use")
                    .put("ownerClientId", getCurrentTrialOwner());
            wsServer.sendToConnection(conn, denied.toString());
          } else {
            collector.broadcastTrialState(activeSession);
          }
          break;
        }
      case "exit_trial":
        exitTrial(msg.optString("clientId"));
        collector.broadcastTrialState(null);
        break;
      case "trial_move":
        {
          int x = msg.optInt("x", -1);
          int y = msg.optInt("y", -1);
          if (x < 0 || y < 0) return;
          applyTrialMove(msg.optString("clientId"), x, y);
          break;
        }
      case "trial_navigate":
        if (msg.has("childIndex")) {
          int childIndex = msg.optInt("childIndex", -1);
          if (childIndex < 0) return;
          trialNavigateForward(msg.optString("clientId"), childIndex);
        } else {
          trialNavigate(msg.optString("clientId"), msg.optString("direction"));
        }
        break;
      case "trial_reset":
        trialReset(msg.optString("clientId"));
        break;
      default:
        // unknown type — ignore
    }
  }

  public synchronized void stop() {
    if (!running) return;
    forceExitTrial();
    if (collector != null) {
      collector.shutdown();
      collector = null;
    }
    if (wsServer != null) {
      try {
        wsServer.stop(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Exception ignored) {
      }
      wsServer = null;
    }
    if (httpServer != null) {
      httpServer.stop();
      httpServer = null;
    }
    running = false;
    accessUrl = null;
  }

  public boolean isRunning() {
    return running;
  }

  public String getAccessUrl() {
    return accessUrl;
  }

  public int getWsPort() {
    return actualWsPort;
  }

  public WebBoardDataCollector getCollector() {
    return collector;
  }

  static String getLanIp() {
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface ni = interfaces.nextElement();
        if (ni.isLoopback() || !ni.isUp()) continue;
        Enumeration<InetAddress> addrs = ni.getInetAddresses();
        while (addrs.hasMoreElements()) {
          InetAddress addr = addrs.nextElement();
          if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
            return addr.getHostAddress();
          }
        }
      }
    } catch (SocketException ignored) {
    }
    return "127.0.0.1";
  }

  private static boolean isPortAvailable(int port) {
    try (ServerSocket ss = new ServerSocket(port)) {
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  // --- Trial session API ---

  public synchronized String getCurrentTrialOwner() {
    return activeSession != null ? activeSession.ownerClientId : "";
  }

  public synchronized boolean enterTrial(String clientId, BoardHistoryNode anchor) {
    if (activeSession != null) {
      return activeSession.ownerClientId.equals(clientId);
    }
    activeSession = new TrialSession(clientId, anchor);
    overrideSink.set(anchor);
    scheduleIdleTimeout(activeSession);
    return true;
  }

  public synchronized void exitTrial(String clientId) {
    if (activeSession == null || !activeSession.ownerClientId.equals(clientId)) return;
    cancelIdleTimer(activeSession);
    activeSession = null;
    overrideSink.set(null);
  }

  public synchronized void forceExitTrial() {
    if (activeSession == null) return;
    cancelIdleTimer(activeSession);
    activeSession = null;
    overrideSink.set(null);
  }

  public synchronized void applyTrialMove(String clientId, int x, int y) {
    TrialSession s = activeSession;
    if (s == null || !s.ownerClientId.equals(clientId)) return;
    collector.runOnExecutor(() -> doApplyMove(s, x, y));
    touchActivity(s);
  }

  public synchronized void trialNavigate(String clientId, String direction) {
    TrialSession s = activeSession;
    if (s == null || !s.ownerClientId.equals(clientId)) return;
    if ("back".equals(direction)) {
      if (s.displayNode == s.anchorNode) return;
      Optional<BoardHistoryNode> prev = s.displayNode.previous();
      if (!prev.isPresent()) return;
      s.displayNode = prev.get();
    } else if ("forward".equals(direction)) {
      if (s.displayNode.variations.isEmpty()) return;
      s.displayNode = s.displayNode.variations.get(0);
    } else {
      return;
    }
    overrideSink.set(s.displayNode);
    collector.onBoardStateChanged();
    collector.broadcastTrialState(s);
    touchActivity(s);
  }

  public synchronized void trialNavigateForward(String clientId, int childIndex) {
    TrialSession s = activeSession;
    if (s == null || !s.ownerClientId.equals(clientId)) return;
    if (childIndex < 0 || childIndex >= s.displayNode.variations.size()) return;
    s.displayNode = s.displayNode.variations.get(childIndex);
    overrideSink.set(s.displayNode);
    collector.onBoardStateChanged();
    collector.broadcastTrialState(s);
    touchActivity(s);
  }

  public synchronized void trialReset(String clientId) {
    TrialSession s = activeSession;
    if (s == null || !s.ownerClientId.equals(clientId)) return;
    s.displayNode = s.anchorNode;
    overrideSink.set(s.anchorNode);
    collector.onBoardStateChanged();
    collector.broadcastTrialState(s);
    touchActivity(s);
  }

  private void doApplyMove(TrialSession s, int x, int y) {
    synchronized (this) {
      if (activeSession != s) return;
      BoardHistoryNode parent = s.displayNode;
      BoardData parentData = parent.getData();

      for (BoardHistoryNode existing : parent.variations) {
        BoardData ed = existing.getData();
        if (ed.lastMove.isPresent() && ed.lastMove.get()[0] == x && ed.lastMove.get()[1] == y) {
          s.displayNode = existing;
          overrideSink.set(existing);
          collector.onBoardStateChanged();
          collector.broadcastTrialState(s);
          return;
        }
      }

      int idx = Board.getIndex(x, y);
      if (parentData.stones[idx] != Stone.EMPTY) return;

      Stone color = parentData.blackToPlay ? Stone.BLACK : Stone.WHITE;

      BoardData newData = parentData.clone();
      newData.stones[idx] = color;
      newData.lastMove = Optional.of(new int[] {x, y});
      newData.blackToPlay = !parentData.blackToPlay;
      newData.moveNumber = parentData.moveNumber + 1;
      newData.dummy = false;

      BoardHistoryNode child = new BoardHistoryNode(newData);
      parent.variations.add(child);
      parent.setPreviousForChild(child);

      s.displayNode = child;
      overrideSink.set(child);
      collector.onBoardStateChanged();
      collector.broadcastTrialState(s);
    }
  }

  private void scheduleIdleTimeout(TrialSession s) {
    if (collector == null) return;
    s.idleTimer =
        collector.scheduleOnExecutor(
            () -> {
              synchronized (this) {
                if (activeSession == s) forceExitTrial();
              }
            },
            IDLE_TIMEOUT_MS,
            TimeUnit.MILLISECONDS);
  }

  private void cancelIdleTimer(TrialSession s) {
    if (s.idleTimer != null) {
      s.idleTimer.cancel(false);
      s.idleTimer = null;
    }
  }

  private void touchActivity(TrialSession s) {
    s.lastActivityMs = System.currentTimeMillis();
    cancelIdleTimer(s);
    scheduleIdleTimeout(s);
  }

  // --- Test hooks (package-private) ---

  void setOverrideSinkForTest(DisplayNodeOverrideSink sink) {
    this.overrideSink = sink;
  }

  void setCollectorForTest(WebBoardDataCollector c) {
    this.collector = c;
  }

  String getTrialOwnerForTest() {
    return activeSession != null ? activeSession.ownerClientId : null;
  }

  BoardHistoryNode getDisplayNodeForTest() {
    return activeSession != null ? activeSession.displayNode : null;
  }

  BoardHistoryNode getTrialAnchorForTest() {
    return activeSession != null ? activeSession.anchorNode : null;
  }

  void setDisplayNodeForTest(BoardHistoryNode n) {
    if (activeSession != null) activeSession.displayNode = n;
  }
}
