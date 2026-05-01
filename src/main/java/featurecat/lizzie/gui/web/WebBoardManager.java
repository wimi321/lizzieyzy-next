package featurecat.lizzie.gui.web;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineFollowController;
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

  /** 试下状态变化后通知桌面端 EDT 重绘棋盘和历史树。 */
  @FunctionalInterface
  public interface DesktopRefresher {
    void refresh();
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
      node -> {
        if (Lizzie.frame != null) Lizzie.frame.setDisplayNodeOverride(node);
      };
  private volatile DesktopRefresher desktopRefresher =
      () -> {
        if (Lizzie.frame == null) return;
        // 强制变化树重画：常规 path 只在 treeNode != currentHistoryNode 时重画
        // (LizzieFrame.java:9772)，但试下不动真 currentNode，所以默认不会触发重画。
        Lizzie.frame.redrawTree = true;
        javax.swing.SwingUtilities.invokeLater(() -> Lizzie.frame.refresh());
        // 变化树由异步线程算缓存图，第一次 paint 启动线程时缓存还没更新；
        // 离散事件后没有连续 paint，延迟一次 repaint 让新缓存上屏。
        javax.swing.Timer timer =
            new javax.swing.Timer(
                300,
                ev -> {
                  if (Lizzie.frame != null) {
                    Lizzie.frame.redrawTree = true;
                    Lizzie.frame.repaint();
                  }
                });
        timer.setRepeats(false);
        timer.start();
      };
  private volatile TrialSession activeSession;

  private volatile EngineFollowController engineController;
  private volatile java.util.function.BooleanSupplier desktopPlayingProbe = () -> false;
  private volatile java.util.function.Supplier<BoardHistoryNode> mainlineTailSupplier =
      () -> {
        if (Lizzie.board == null) return null;
        BoardHistoryNode n = Lizzie.board.getHistory().getCurrentHistoryNode();
        while (n != null && n.getData() != null && n.getData().dummy) {
          n = n.previous().orElse(null);
        }
        return n;
      };

  public void setEngineFollowController(EngineFollowController c) {
    this.engineController = c;
  }

  public void setDesktopPlayingProbe(java.util.function.BooleanSupplier p) {
    if (p != null) this.desktopPlayingProbe = p;
  }

  public void setMainlineTailSupplier(java.util.function.Supplier<BoardHistoryNode> s) {
    if (s != null) this.mainlineTailSupplier = s;
  }

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
            String reason = desktopPlayingProbe.getAsBoolean() ? "engine_busy" : "in_use";
            JSONObject denied =
                new JSONObject()
                    .put("type", "trial_denied")
                    .put("reason", reason)
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
    if (desktopPlayingProbe.getAsBoolean()) {
      return false;
    }
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
    applyOverrideAndRefresh(anchor);
    EngineFollowController c = engineController;
    if (c != null) c.onTrialEnter(anchor);
    scheduleIdleTimeout(activeSession);
    return true;
  }

  public synchronized void exitTrial(String clientId) {
    if (activeSession == null || !activeSession.ownerClientId.equals(clientId)) return;
    cancelIdleTimer(activeSession);
    cleanupMainlineDummy(activeSession);
    activeSession = null;
    applyOverrideAndRefresh(null);
    EngineFollowController c = engineController;
    if (c != null) {
      BoardHistoryNode tail = mainlineTailSupplier.get();
      if (tail != null) c.onTrialExit(tail);
    }
  }

  public synchronized void forceExitTrial() {
    if (activeSession == null) return;
    cancelIdleTimer(activeSession);
    cleanupMainlineDummy(activeSession);
    activeSession = null;
    applyOverrideAndRefresh(null);
    EngineFollowController c = engineController;
    if (c != null) {
      BoardHistoryNode tail = mainlineTailSupplier.get();
      if (tail != null) c.onTrialExit(tail);
    }
  }

  /**
   * 退出试下时清理 enter 时插入的 dummy 占位。
   *
   * <p>仅当用户进入试下后**没有落任何子**（anchor.variations 里只有 dummy）才删 dummy 让 anchor 回到原来"无主线下一手"的状态。否则保留 dummy
   * 当 mainline 占位，让试下子永远停在 variations[1+] 当分叉——若退出时把 dummy 删了，试下子会接续 mainline 污染主线。
   *
   * <p>保留的 dummy 是 EndDummay（dummy=true && variations.isEmpty()），lizzie 现有渲染会跳过它
   * （VariationTree.java:222），同步管线 ReadBoard 推新主线时会自动替换它（line ~1035）。
   */
  private static void cleanupMainlineDummy(TrialSession s) {
    BoardHistoryNode dummy = s.mainlineDummy;
    if (dummy == null) return;
    boolean hasNonDummy = false;
    for (BoardHistoryNode v : s.anchorNode.variations) {
      if (v != dummy && !v.getData().dummy) {
        hasNonDummy = true;
        break;
      }
    }
    if (!hasNonDummy) {
      s.anchorNode.variations.remove(dummy);
    }
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
    applyOverrideAndRefresh(s.displayNode);
    {
      EngineFollowController c = engineController;
      if (c != null) c.onTrialDisplayNodeChanged(s.displayNode);
    }
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
    applyOverrideAndRefresh(s.displayNode);
    {
      EngineFollowController c = engineController;
      if (c != null) c.onTrialDisplayNodeChanged(s.displayNode);
    }
    collector.onBoardStateChanged();
    collector.broadcastTrialState(s);
    touchActivity(s);
  }

  public synchronized void trialReset(String clientId) {
    TrialSession s = activeSession;
    if (s == null || !s.ownerClientId.equals(clientId)) return;
    s.displayNode = s.anchorNode;
    applyOverrideAndRefresh(s.anchorNode);
    {
      EngineFollowController c = engineController;
      if (c != null) c.onTrialDisplayNodeChanged(s.anchorNode);
    }
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
          applyOverrideAndRefresh(existing);
          {
            EngineFollowController c = engineController;
            if (c != null) c.onTrialDisplayNodeChanged(existing);
          }
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
      applyOverrideAndRefresh(child);
      {
        EngineFollowController c = engineController;
        if (c != null) c.onTrialDisplayNodeChanged(child);
      }
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

  void setDesktopRefresherForTest(DesktopRefresher refresher) {
    this.desktopRefresher = refresher;
  }

  /**
   * 设置 displayNode override 并通知桌面端 EDT 重绘。 因为 manager 直接操作 BoardHistoryNode.variations 而绕过了 lizzie
   * 的常规 place/refresh 链， 必须显式触发 refresh，否则棋盘画面与历史树视图都不会更新。
   */
  private void applyOverrideAndRefresh(BoardHistoryNode node) {
    overrideSink.set(node);
    desktopRefresher.refresh();
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
