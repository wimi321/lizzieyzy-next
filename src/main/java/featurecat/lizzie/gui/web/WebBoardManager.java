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

    /** 进入试下时若 anchor 是 mainline 末端（variations 为空），插入的 dummy 占位节点。null 表示未插入。 */
    BoardHistoryNode mainlineDummy;

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
    TrialSession s = new TrialSession(clientId, anchor);
    // 试下子要走分叉而非接续 mainline。若 anchor 是 mainline 末端（无主线下一手），
    // 先插一个 dummy 占据 variations[0]，让后续试下子永远 add 到 index>=1。
    // ReadBoard 同步推进 mainline 时会识别 dummy 并把它替换走（line ~1035），互不干扰。
    if (anchor.variations.isEmpty()) {
      BoardData dummyData = anchor.getData().clone();
      dummyData.dummy = true;
      BoardHistoryNode dummy = new BoardHistoryNode(dummyData);
      anchor.variations.add(dummy);
      anchor.setPreviousForChild(dummy);
      s.mainlineDummy = dummy;
    }
    activeSession = s;
    overrideSink.set(anchor);
    scheduleIdleTimeout(activeSession);
    return true;
  }

  public synchronized void exitTrial(String clientId) {
    if (activeSession == null || !activeSession.ownerClientId.equals(clientId)) return;
    cancelIdleTimer(activeSession);
    cleanupMainlineDummy(activeSession);
    activeSession = null;
    overrideSink.set(null);
  }

  public synchronized void forceExitTrial() {
    if (activeSession == null) return;
    cancelIdleTimer(activeSession);
    cleanupMainlineDummy(activeSession);
    activeSession = null;
    overrideSink.set(null);
  }

  /**
   * 退出试下时清掉 enter 时插入的 dummy 占位。 若同步管线 (ReadBoard line ~1035) 已经把 dummy 从 variations[0]
   * 替换走，本方法无副作用。 否则把 dummy 从 anchor.variations 中移除。
   */
  private static void cleanupMainlineDummy(TrialSession s) {
    BoardHistoryNode dummy = s.mainlineDummy;
    if (dummy == null) return;
    s.anchorNode.variations.remove(dummy);
    s.mainlineDummy = null;
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
      // 跳过 dummy 占位（enter 时插的 mainline 占位 + ReadBoard 同步可能产生的 dummy）
      BoardHistoryNode firstReal = null;
      for (BoardHistoryNode v : s.displayNode.variations) {
        if (!v.getData().dummy) {
          firstReal = v;
          break;
        }
      }
      if (firstReal == null) return;
      s.displayNode = firstReal;
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
    BoardHistoryNode target = s.displayNode.variations.get(childIndex);
    if (target.getData().dummy) return; // 不允许跳到 dummy 占位
    s.displayNode = target;
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

      // 复用同位置子节点（跳过 dummy 占位）
      for (BoardHistoryNode existing : parent.variations) {
        BoardData ed = existing.getData();
        if (ed.dummy) continue;
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

      // 用 BoardData.move(...) 工厂构造，保证 nodeKind=MOVE、moveNumberList、zobrist 等渲染必须字段齐全
      Stone[] newStones = parentData.stones.clone();
      newStones[idx] = color;
      int newMoveNumber = parentData.moveNumber + 1;
      int[] newMoveNumberList =
          parentData.moveNumberList == null ? null : parentData.moveNumberList.clone();
      if (newMoveNumberList != null) newMoveNumberList[idx] = newMoveNumber;
      featurecat.lizzie.rules.Zobrist newZobrist =
          parentData.zobrist == null ? null : parentData.zobrist.clone();
      if (newZobrist != null) newZobrist.toggleStone(x, y, color);

      BoardData newData =
          BoardData.move(
              newStones,
              new int[] {x, y},
              color,
              !parentData.blackToPlay,
              newZobrist,
              newMoveNumber,
              newMoveNumberList,
              parentData.blackCaptures,
              parentData.whiteCaptures,
              0,
              0);
      // 试下分支没有引擎分析（默认 bestMoves 即空、playouts=0）

      BoardHistoryNode child = new BoardHistoryNode(newData);
      // 试下永远走分叉：如果第一个子是 dummy 占位，把 child add 到末尾（自然在 dummy 之后）
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
