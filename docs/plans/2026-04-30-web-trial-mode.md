# Web 端试下模式实现计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 [`docs/specs/2026-04-30-web-trial-mode-design.md`](../specs/2026-04-30-web-trial-mode-design.md) 中描述的 Web 端试下模式：单 Web 用户可独占接管棋盘做试下，桌面端跟随显示但不动 mainline 同步管线。

**Architecture:** 在 `LizzieFrame` 加 `displayNodeOverride`（volatile 引用）。所有渲染/数据采集层改读 override；`ReadBoard` 同步路径完全不动。试下落子在 `anchorNode` 子树下创建 variation，由 `WebBoardManager.TrialSession` 在 collector 单线程 executor 上串行化所有状态变更。

**Tech Stack:** Java 11 + Java-WebSocket 1.6.0 + JUnit 5（Maven）；前端纯 HTML/JS/Canvas（无构建步骤）。

---

## 文件结构

### 新建（Java）
- 无新建类——所有改动在已有类内（`TrialSession` 作为 `WebBoardManager` 内部类）

### 修改（Java）
| 文件 | 责任 |
|---|---|
| `src/main/java/featurecat/lizzie/gui/LizzieFrame.java` | 新增 `displayNodeOverride` + `getDisplayNode()`；菜单加「强制结束试下」 |
| `src/main/java/featurecat/lizzie/gui/web/WebBoardServer.java` | `onMessage` 落地上行通道；新增 `sendToConnection(WebSocket, String)` 单播 |
| `src/main/java/featurecat/lizzie/gui/web/WebBoardManager.java` | 新增 `TrialSession` 内部类、`enterTrial/exitTrial/applyTrialMove/trialNavigate/trialReset/forceExitTrial` API |
| `src/main/java/featurecat/lizzie/gui/web/WebBoardDataCollector.java` | `doBroadcastFullState` 改读 `getDisplayNode()`；新增 `buildTrialStateJson`；`buildWinrateHistoryJson` 沿 anchor 上溯 |
| `src/main/java/featurecat/lizzie/gui/BoardRenderer.java` | 渲染棋子/标记取节点改读 `LizzieFrame.getDisplayNode()`；试下中隐藏候选点和胜率覆盖层 |
| `src/main/java/featurecat/lizzie/rules/Board.java` | `place(int, int)`（用户落子入口）开头加 guard：override 非空时 toast + return |

### 修改（前端）
| 文件 | 责任 |
|---|---|
| `src/main/resources/web/index.html` | 加试下控制条 DOM |
| `src/main/resources/web/board.js` | clientId localStorage、enter/exit/move/navigate 上行、`trial_state` 接收、棋盘点击 dispatch、分叉标记渲染 |
| `src/main/resources/web/board.css` | 试下控制条样式 |

### 修改（测试）
| 文件 | 责任 |
|---|---|
| `src/test/java/featurecat/lizzie/gui/web/WebBoardServerTest.java` | onMessage 解析与 dispatch |
| `src/test/java/featurecat/lizzie/gui/web/WebBoardManagerTest.java` | TrialSession 状态机、并发独占、空闲超时、navigate Optional 边界 |
| `src/test/java/featurecat/lizzie/gui/web/WebBoardDataCollectorTest.java` | buildTrialStateJson / 走 anchor 上溯的 winrate_history |

---

## Task 1：LizzieFrame.displayNodeOverride 地基

最小不破坏改动：加字段和 getter，确保 `null` 时行为完全等同现状。

**Files:**
- Modify: `src/main/java/featurecat/lizzie/gui/LizzieFrame.java`

- [ ] **Step 1**：在 `LizzieFrame` 顶部成员区加字段（找现有 `volatile` 字段就近放）：

```java
/** Web 试下模式下的渲染节点覆盖。null 表示无 override，渲染端读 Board.history 当前节点。 */
private volatile featurecat.lizzie.rules.BoardHistoryNode displayNodeOverride;
```

- [ ] **Step 2**：加访问器（同一文件，靠近其它 public 方法）：

```java
public featurecat.lizzie.rules.BoardHistoryNode getDisplayNode() {
  featurecat.lizzie.rules.BoardHistoryNode override = displayNodeOverride;
  if (override != null) return override;
  return Lizzie.board.getHistory().getCurrentHistoryNode();
}

public void setDisplayNodeOverride(featurecat.lizzie.rules.BoardHistoryNode node) {
  this.displayNodeOverride = node;
}

public boolean isTrialActive() {
  return displayNodeOverride != null;
}
```

- [ ] **Step 3**：编译通过：`mvn -q -DskipTests compile`，期望 BUILD SUCCESS。

- [ ] **Step 4**：commit：

```bash
git add src/main/java/featurecat/lizzie/gui/LizzieFrame.java
git commit -m "feat(web-trial): LizzieFrame 增加 displayNodeOverride 渲染覆盖"
```

---

## Task 2：WebBoardServer 上行 dispatch + 单播能力

**Files:**
- Modify: `src/main/java/featurecat/lizzie/gui/web/WebBoardServer.java`
- Test: `src/test/java/featurecat/lizzie/gui/web/WebBoardServerTest.java`

- [ ] **Step 1**：先写失败测试。`pom.xml` 当前**不含 mockito**（验证：`grep -i mockito pom.xml` 应返回空）；本计划**不引入 mockito**，改用 `null` 作为 conn 参数（`onMessage` 不解引用 conn，只把它原样传给 handler；handler 测试里也不用 conn）。

```java
@Test
void onMessageDispatchesEnterTrialToHandler() {
  AtomicReference<JSONObject> received = new AtomicReference<>();
  WebBoardServer server = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 20);
  server.setMessageHandler((conn, json) -> received.set(json));
  server.onMessage(null, "{\"type\":\"enter_trial\",\"clientId\":\"abc\"}");
  assertThat(received.get().getString("type")).isEqualTo("enter_trial");
  assertThat(received.get().getString("clientId")).isEqualTo("abc");
}

@Test
void onMessageIgnoresMalformedJson() {
  WebBoardServer server = new WebBoardServer(new InetSocketAddress("127.0.0.1", 0), 20);
  AtomicBoolean called = new AtomicBoolean(false);
  server.setMessageHandler((conn, json) -> called.set(true));
  server.onMessage(null, "not json");
  assertThat(called).isFalse();
}
```

`sendToConnection(null, ...)` 已经做了 null 检查，单测里同样可以用 null 验证「null 安全」。

- [ ] **Step 2**：跑测试看失败：`mvn -q -Dtest=WebBoardServerTest test`，期望编译失败（`setMessageHandler` 不存在）。

- [ ] **Step 3**：实现。`WebBoardServer` 新增：

```java
@FunctionalInterface
public interface MessageHandler {
  void handle(WebSocket conn, JSONObject message);
}

private volatile MessageHandler messageHandler;

public void setMessageHandler(MessageHandler h) {
  this.messageHandler = h;
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

public void sendToConnection(WebSocket conn, String json) {
  if (conn != null && conn.isOpen()) conn.send(json);
}
```

记得在文件顶部 import `org.json.JSONObject`。

- [ ] **Step 4**：跑测试：`mvn -q -Dtest=WebBoardServerTest test`，期望全部 PASS。

- [ ] **Step 5**：commit：

```bash
git add src/main/java/featurecat/lizzie/gui/web/WebBoardServer.java src/test/java/featurecat/lizzie/gui/web/WebBoardServerTest.java
git commit -m "feat(web-trial): WebBoardServer 落地上行通道与单播支持"
```

---

## Task 3：WebBoardManager.TrialSession — 状态机核心

这是协议背后的真正逻辑层。所有状态变更都在 collector 的单线程 executor 里串行化。

**Files:**
- Modify: `src/main/java/featurecat/lizzie/gui/web/WebBoardManager.java`
- Test: `src/test/java/featurecat/lizzie/gui/web/WebBoardManagerTest.java`

### 设计要点

- `TrialSession` 持有：`String ownerClientId`、`BoardHistoryNode anchorNode`、`BoardHistoryNode displayNode`、`long lastActivityMs`、`ScheduledFuture<?> idleTimer`
- 单例：`WebBoardManager` 持有 `volatile TrialSession activeSession`，`null` 表示 Idle
- 5 分钟空闲超时常量：`private static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000L;`
- 所有改 `activeSession`、改 `displayNode`、改 `anchorNode` 子树的操作必须 `collector.runOnExecutor(...)`（Step 3c 暴露）
- 落子：根据 `displayNode.getData().blackToPlay` 判断本手颜色，**手动**挂子节点到试下子树。**不**调 `BoardHistoryNode.addOrGoto(...)`——它会触发 `Lizzie.leelaz.clearBestMoves()`、`tryToResetByoTime()`、`Board.clearAfterMove()`、`Leelaz.maybeAjustPDA(node)` 等大量副作用，违反 spec「引擎不重 load / 不动同步管线」。仿照 `BoardHistoryNode.addAtLast(BoardData)`（`BoardHistoryNode.java:104-123`，无 leelaz/frame 副作用）的模式：构造 `new BoardHistoryNode(data)`、`variations.add(node)`、用反射或新增包级 setter 设置 `node.previous = Optional.of(parent)`（见 Step 3 实现细节）。已存在同位置子节点时复用（自己写匹配循环）

### 步骤

- [ ] **Step 1**：先写测试，保留三个最关键的：

```java
@Test
void enterTrialFromIdleSucceedsAndSetsOverride() {
  WebBoardManager m = new WebBoardManager();
  AtomicReference<BoardHistoryNode> sink = new AtomicReference<>();
  m.setOverrideSinkForTest(sink::set);
  m.setCollectorForTest(stubCollector());
  BoardHistoryNode anchor = anyNode();
  boolean ok = m.enterTrial("client-1", anchor);
  assertThat(ok).isTrue();
  assertThat(sink.get()).isSameAs(anchor);
  assertThat(m.getTrialOwnerForTest()).isEqualTo("client-1");
}

@Test
void enterTrialFromOtherClientReturnsFalse() {
  WebBoardManager m = new WebBoardManager();
  m.setOverrideSinkForTest(n -> {});
  m.setCollectorForTest(stubCollector());
  m.enterTrial("client-1", anyNode());
  assertThat(m.enterTrial("client-2", anyNode())).isFalse();
}

@Test
void trialNavigateBackAtAnchorIsNoop() {
  WebBoardManager m = new WebBoardManager();
  m.setOverrideSinkForTest(n -> {});
  m.setCollectorForTest(stubCollector());
  BoardHistoryNode anchor = anyNode();
  m.enterTrial("c", anchor);
  m.trialNavigate("c", "back");
  assertThat(m.getDisplayNodeForTest()).isSameAs(anchor);
}
```

由于 `WebBoardManager.enterTrial(...)` 调用 `Lizzie.frame.setDisplayNodeOverride(...)`，但**现有 web 测试不初始化 `Lizzie.frame`**（grep `Lizzie.frame =` 在 `src/test/` 验证）。直接调会 NPE。

**解决方案**：给 `WebBoardManager` 加一个**测试可注入**的 frame 接口，避免依赖 `Lizzie.frame` 静态全局：

```java
// in WebBoardManager
@FunctionalInterface
interface DisplayNodeOverrideSink {
  void set(BoardHistoryNode node);  // null 表示清空 override
}

private volatile DisplayNodeOverrideSink overrideSink =
    node -> Lizzie.frame.setDisplayNodeOverride(node);  // 默认走 Lizzie.frame

void setOverrideSinkForTest(DisplayNodeOverrideSink sink) { this.overrideSink = sink; }
```

测试里：

```java
private final AtomicReference<BoardHistoryNode> sinkSeen = new AtomicReference<>();

@BeforeEach
void setUp() {
  manager = new WebBoardManager();
  manager.setOverrideSinkForTest(sinkSeen::set);
  manager.setCollectorForTest(stubCollector());
}
```

类似地，`isTrialActive()` 在测试断言里改用 `manager.getTrialOwnerForTest() != null` 而非 `Lizzie.frame.isTrialActive()`。

`stubCollector()` 是测试辅助：返回一个 `WebBoardDataCollector` 子类，覆盖 `runOnExecutor` 为同步执行（`r.run()`）和 `scheduleOnExecutor` 为返回一个 dummy `ScheduledFuture`，让测试同步可断言。

- [ ] **Step 2**：跑测试看失败：`mvn -q -Dtest=WebBoardManagerTest test`，期望编译失败。

- [ ] **Step 3**：实现 `TrialSession` 内部类与 manager 上的 API。骨架：

```java
private static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000L;

private volatile TrialSession activeSession;

private static class TrialSession {
  final String ownerClientId;
  final BoardHistoryNode anchorNode;
  volatile BoardHistoryNode displayNode;
  volatile long lastActivityMs;
  ScheduledFuture<?> idleTimer;

  TrialSession(String owner, BoardHistoryNode anchor) {
    this.ownerClientId = owner;
    this.anchorNode = anchor;
    this.displayNode = anchor;
    this.lastActivityMs = System.currentTimeMillis();
  }
}

public synchronized boolean enterTrial(String clientId, BoardHistoryNode anchor) {
  if (activeSession != null) {
    return activeSession.ownerClientId.equals(clientId); // idempotent
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
  // 在 collector executor 上序列化构造 BoardData 并挂子树
  collector.runOnExecutor(() -> doApplyMove(s, x, y));
  touchActivity(s);
}

public synchronized void trialNavigate(String clientId, String direction) { /* back/forward */ }
public synchronized void trialNavigateForward(String clientId, int childIndex) { /* F3 */ }
public synchronized void trialReset(String clientId) { /* displayNode = anchorNode */ }
```

`doApplyMove`（**不**走 `addOrGoto`，仿 `addAtLast` 模式手写）：

```java
private void doApplyMove(TrialSession s, int x, int y) {
  BoardHistoryNode parent = s.displayNode;
  BoardData parentData = parent.getData();

  // 1) 先查同位置已存在的子节点（复用，不重建）
  for (BoardHistoryNode existing : parent.variations) {
    BoardData ed = existing.getData();
    if (ed.lastMove.isPresent()
        && ed.lastMove.get()[0] == x
        && ed.lastMove.get()[1] == y) {
      s.displayNode = existing;
      overrideSink.set(existing);
      collector.onBoardStateChanged();
      collector.broadcastTrialState(s);
      return;
    }
  }

  // 2) 构造新 BoardData。MVP 仅支持空交叉点落子（不算提子/打劫，TODO 留作后续）
  Stone color = parentData.blackToPlay ? Stone.BLACK : Stone.WHITE;
  int idx = Board.getIndex(x, y);
  if (parentData.stones[idx] != Stone.EMPTY) {
    // 非空点落子：MVP 静默拒绝（前端可以根据无 trial_state 变化感知失败）
    return;
  }
  Stone[] newStones = parentData.stones.clone();
  newStones[idx] = color;
  // 实现者：用 Grep 工具找 `new BoardData(` 在 src/main/java/featurecat/lizzie/rules/Board.java 中的所有调用点，
  // 选最简版本（无 capture/zobrist 计算）就地构造。关键字段：
  //   stones=newStones, lastMove=Optional.of(new int[]{x,y}), blackToPlay=!parentData.blackToPlay,
  //   moveNumber=parentData.moveNumber+1, dummy=false。其余字段（capturedStones/zobristHash/...）
  //   按 parentData 的零值或拷贝赋值即可（试下 MVP 不依赖它们）。
  //   不要新建 TrialBoardDataFactory 类——直接 new BoardData(...) 即可。
  BoardData newData = new BoardData(/* 按 Board.java 现有调用点参数填充 */);

  // 3) 手动挂子节点（仿 BoardHistoryNode.addAtLast，无 leelaz/frame 副作用）
  BoardHistoryNode child = new BoardHistoryNode(newData);
  parent.variations.add(child);
  parent.setPreviousForChild(child); // 见 Step 3a：在 BoardHistoryNode 加包级 setter

  s.displayNode = child;
  overrideSink.set(child);
  collector.onBoardStateChanged();
  collector.broadcastTrialState(s);
}
```

> **重要**：spec 明确试下子树要进 `BoardHistoryList`（共享同一 history 对象），所以**不**走 `Board.place(...)`（那条路会触发引擎、`refresh()`、SGF dirty 标记）。也**不**走 `BoardHistoryNode.addOrGoto(...)`（它内部调 `Lizzie.leelaz.clearBestMoves()` 等副作用）。手写 + `BoardHistoryNode` 的包级 setter 是唯一干净方案。

- [ ] **Step 3a**：在 `BoardHistoryNode.java` 加一个**包级**（package-private）setter，让试下逻辑能设置 `previous`：

```java
// in BoardHistoryNode.java，与 addAtLast 相邻
void setPreviousForChild(BoardHistoryNode child) {
  child.previous = Optional.of(this);
}
```

或者把 `previous` 字段从 `private` 改成 `package-private`（破坏面更小，因为 `featurecat.lizzie.rules` 包外没人直接访问 `previous`——grep 验证：`grep -rn "\.previous " src/main/java | grep -v rules/`）。**首选 setter 方式**，破坏面更小。

- [ ] **Step 3b**：`scheduleIdleTimeout` 用 `collector.scheduleOnExecutor(Runnable, long, TimeUnit)` 5 分钟后回调 `forceExitTrial()`；`touchActivity(s)` 取消旧 timer 并 reschedule。

- [ ] **Step 3c**：Task 3 依赖 collector 暴露三个新方法 `runOnExecutor(Runnable)` / `scheduleOnExecutor(Runnable, long, TimeUnit)` / `broadcastTrialState(TrialSession)`。**先**在 `WebBoardDataCollector.java` 加这三个方法的 stub（实现可空或最小转发），再写 manager 测试。这避免了 Task 3 / Task 4 的循环依赖。具体代码：

```java
// 临时 stub（Task 4 完整实现再覆盖）
public void runOnExecutor(Runnable r) {
  try { executor.execute(r); } catch (RejectedExecutionException ignored) {}
}
public ScheduledFuture<?> scheduleOnExecutor(Runnable r, long delay, TimeUnit unit) {
  return executor.schedule(r, delay, unit);
}
public void broadcastTrialState(WebBoardManager.TrialSession s) {
  // Task 4 实现完整 JSON 序列化；此处空实现保证 Task 3 编译/单测通过
}
```

- [ ] **Step 4**：跑测试：`mvn -q -Dtest=WebBoardManagerTest test`，期望全部 PASS。

- [ ] **Step 5**：commit：

```bash
git add src/main/java/featurecat/lizzie/gui/web/WebBoardManager.java src/test/java/featurecat/lizzie/gui/web/WebBoardManagerTest.java
git commit -m "feat(web-trial): WebBoardManager 实现 TrialSession 状态机"
```

---

## Task 4：WebBoardDataCollector 适配 displayNode + trial_state 序列化

**Files:**
- Modify: `src/main/java/featurecat/lizzie/gui/web/WebBoardDataCollector.java`
- Test: `src/test/java/featurecat/lizzie/gui/web/WebBoardDataCollectorTest.java`

- [ ] **Step 1**：写失败测试：

```java
@Test
void buildTrialStateJsonIncludesSiblingMarkers() {
  BoardHistoryNode display = nodeWithVariations(/*主线子*/ at(3,3), /*sibling*/ at(15,15), at(9,9));
  TrialSessionView view = new TrialSessionView("c1", /*anchorMove=*/42, display);
  JSONObject json = WebBoardDataCollector.buildTrialStateJson(view);
  assertThat(json.getString("type")).isEqualTo("trial_state");
  assertThat(json.getBoolean("active")).isTrue();
  assertThat(json.getString("ownerClientId")).isEqualTo("c1");
  JSONArray markers = json.getJSONArray("siblingMarkers");
  assertThat(markers.length()).isEqualTo(2);
  assertThat(markers.getJSONObject(0).getInt("childIndex")).isEqualTo(1);
  assertThat(markers.getJSONObject(0).getString("label")).isEqualTo("1");
}

@Test
void winrateHistoryWalksFromAnchorWhenTrialActive() {
  // 构造 root -> a -> b -> c 主线，c 是 anchor，c 下挂 trial 子 d
  // displayNode = d；期望返回的 path = [root, a, b, c]（试下子 d 不在主线 history 里）
  // ...
}

@Test
void canBackFalseAtAnchor() {
  TrialSessionView view = trialAt(anchor=N, display=N);
  JSONObject json = WebBoardDataCollector.buildTrialStateJson(view);
  assertThat(json.getBoolean("canBack")).isFalse();
}
```

`TrialSessionView` 是给序列化用的不可变快照（避免序列化时持有可变 `TrialSession`）。在测试里和实现里都用它。

- [ ] **Step 2**：跑测试看失败：`mvn -q -Dtest=WebBoardDataCollectorTest test`。

- [ ] **Step 3**：实现：
  - 加 `static class TrialSessionView { String ownerClientId; int anchorMoveNumber; BoardHistoryNode displayNode; }`
  - `buildTrialStateJson(TrialSessionView)`：按 spec JSON schema 输出，包含 `active=true`、`ownerClientId`、`anchorMoveNumber`、`displayMoveNumber`、`canBack`（`displayNode != anchor`）、`canForward`（`displayNode.variations` 非空）、`siblingMarkers`（`variations` 从 index=1 起的所有子节点；`label = String.valueOf(顺序号)`，`childIndex = 真实下标`）
  - 当 `WebBoardManager` 在 idle 时也广播一次「`trial_state {active:false}`」让客户端清状态
  - `doBroadcastFullState` 中的 `Lizzie.board.getHistory().getCurrentHistoryNode()` 改成 `Lizzie.frame.getDisplayNode()`（先用 Grep 工具查 `getCurrentHistoryNode` 在该文件的所有出现，逐处判断；`buildWinrateHistoryJson` 调用处保留主线起点，**不**改）
  - `buildWinrateHistoryJson`：保持现有按 `previous()` 上溯逻辑——对于试下子节点，向上爬到 anchor 再继续爬到 root，**自然包含正确路径**，不需要额外改动；写测试验证这条假设
  - 暴露 `runOnExecutor` / `scheduleOnExecutor` 已在 Task 3 Step 3c 落地，本任务无需再改
  - 把 Task 3 Step 3c 的 `broadcastTrialState(WebBoardManager.TrialSession s)` stub 实现完整：构造 `TrialSessionView` → `buildTrialStateJson` → `server.broadcastMessage(...)`

- [ ] **Step 4**：跑测试：`mvn -q -Dtest=WebBoardDataCollectorTest test`，期望全部 PASS。

- [ ] **Step 5**：commit：

```bash
git add src/main/java/featurecat/lizzie/gui/web/WebBoardDataCollector.java src/test/java/featurecat/lizzie/gui/web/WebBoardDataCollectorTest.java
git commit -m "feat(web-trial): collector 支持 displayNode 与 trial_state 序列化"
```

---

## Task 5：把 manager / server / collector 接起来

**Files:**
- Modify: `src/main/java/featurecat/lizzie/gui/web/WebBoardManager.java`

- [ ] **Step 1**：在 `WebBoardManager.start()` 末尾（`collector.onBoardStateChanged()` 之前）注册 server 上行 handler：

```java
wsServer.setMessageHandler((conn, msg) -> handleClientMessage(conn, msg));
```

- [ ] **Step 2**：实现 `handleClientMessage(WebSocket conn, JSONObject msg)`，按 type dispatch：

```java
private void handleClientMessage(WebSocket conn, JSONObject msg) {
  String type = msg.optString("type");
  switch (type) {
    case "enter_trial": {
      String clientId = msg.optString("clientId");
      BoardHistoryNode anchor = Lizzie.board.getHistory().getCurrentHistoryNode();
      boolean ok = enterTrial(clientId, anchor);
      if (!ok) {
        JSONObject denied = new JSONObject()
            .put("type", "trial_denied")
            .put("reason", "in_use")
            .put("ownerClientId", activeSession != null ? activeSession.ownerClientId : "");
        wsServer.sendToConnection(conn, denied.toString());
      } else {
        collector.broadcastTrialState(activeSession);
      }
      break;
    }
    case "exit_trial":
      // owner 校验在 exitTrial 内部；用 conn-to-clientId 映射或要求客户端在消息里带 clientId（spec 没要求，但实现里要求）
      // 简化：所有上行消息都要求带 clientId
      exitTrial(msg.optString("clientId"));
      collector.broadcastTrialState(null); // active=false
      break;
    case "trial_move":
      applyTrialMove(msg.optString("clientId"), msg.getInt("x"), msg.getInt("y"));
      break;
    case "trial_navigate":
      if (msg.has("childIndex")) {
        trialNavigateForward(msg.optString("clientId"), msg.getInt("childIndex"));
      } else {
        trialNavigate(msg.optString("clientId"), msg.optString("direction"));
      }
      break;
    case "trial_reset":
      trialReset(msg.optString("clientId"));
      break;
    default: // unknown type
  }
}
```

> **Spec 调整说明**：spec 的 `exit_trial` JSON 没有 `clientId` 字段，但服务端无法靠 `WebSocket conn` 反查 clientId（连接和 owner 是松耦合）。**实现时所有上行消息都带 `clientId`**——这是对 spec 的小补充，不改协议语义。在 spec 文件里加个备注（Task 8 的 commit 一起改）。

- [ ] **Step 3**：在 `stop()` 开头加 `forceExitTrial()`，确保停服时 override 清空。

- [ ] **Step 4**：编译：`mvn -q -DskipTests compile`，期望 BUILD SUCCESS。

- [ ] **Step 5**：commit：

```bash
git add src/main/java/featurecat/lizzie/gui/web/WebBoardManager.java
git commit -m "feat(web-trial): manager 接入上行消息 dispatch（所有上行消息要求带 clientId）"
```

---

## Task 6：BoardRenderer 渲染层切到 displayNode

**Files:**
- Modify: `src/main/java/featurecat/lizzie/gui/BoardRenderer.java`

- [ ] **Step 1**：用 Grep 工具找渲染用 `getCurrentHistoryNode()` 或 `getHistory().getData()` 的位置（pattern: `getCurrentHistoryNode|getHistory\(\)\.getData`，path: `src/main/java/featurecat/lizzie/gui/BoardRenderer.java`）：
- 棋子位置、`lastMove` 标记、移动数字 → 改读 `Lizzie.frame.getDisplayNode().getData()`
- 候选点（`bestMoves`）、胜率覆盖层、估值热力图 → 试下中**不显示**：开头加 `if (Lizzie.frame.isTrialActive()) return;` 在对应 draw 方法里
- 棋谱树视图（如果 BoardRenderer 也画树）→ **不动**，仍读真实 history（让用户能看到试下子树相对位置）

- [ ] **Step 2**：编译：`mvn -q -DskipTests compile`。

- [ ] **Step 3**：commit：

```bash
git add src/main/java/featurecat/lizzie/gui/BoardRenderer.java
git commit -m "feat(web-trial): BoardRenderer 渲染读 displayNode override"
```

> **手动 QA**（不在自动测试覆盖）：先跳过，到 Task 9 一起测。

---

## Task 7：Board.place 用户落子入口加 guard

**Files:**
- Modify: `src/main/java/featurecat/lizzie/rules/Board.java:1933`

- [ ] **Step 1**：在 `place(int x, int y)`（无 color 参数的 1933 行版本，是用户鼠标点击/键盘热键的入口）开头加：

```java
public void place(int x, int y) {
  if (Lizzie.frame != null && Lizzie.frame.isTrialActive()) {
    Lizzie.frame.showTrialBlockedToast(); // 见下
    return;
  }
  place(x, y, history.isBlacksTurn() ? Stone.BLACK : Stone.WHITE);
}
```

- [ ] **Step 2**：在 `LizzieFrame` 加 `showTrialBlockedToast()`：先用 Grep 工具找现有提示机制（pattern: `showMessage|toast|setStatusMessage|JOptionPane.*message`，path: `src/main/java/featurecat/lizzie/gui/LizzieFrame.java`），优先复用已有 API。若无现成机制，最简：在 LizzieFrame 加 `volatile String trialBlockedHint` + 在 `BoardRenderer` 顶部状态栏绘制时叠加该字符串，3 秒后通过 `Timer` 清空。**不**要新建 Swing 弹窗（会阻塞 EDT）。

- [ ] **Step 3**：编译：`mvn -q -DskipTests compile`。

- [ ] **Step 4**：**不动**带 color 参数的 `place(...)` 重载——它们用于 SGF 加载、引擎同步、setup stones，不能阻塞。

- [ ] **Step 5**：commit：

```bash
git add src/main/java/featurecat/lizzie/rules/Board.java src/main/java/featurecat/lizzie/gui/LizzieFrame.java
git commit -m "feat(web-trial): 桌面端用户落子入口在试下中拒绝并提示"
```

---

## Task 8：菜单项「强制结束试下」+ spec 微调

**Files:**
- Modify: `src/main/java/featurecat/lizzie/gui/LizzieFrame.java`
- Modify: `docs/specs/2026-04-30-web-trial-mode-design.md`

- [ ] **Step 1**：用 Grep 工具找 Web 旁观菜单创建处（pattern: `Web 旁观|web-board|webBoard`，path: `src/main/java/featurecat/lizzie/gui/LizzieFrame.java`）。

- [ ] **Step 2**：在该子菜单加 `JMenuItem`「强制结束试下」，仅当 `Lizzie.frame.isTrialActive()` 时 enable。可以参考已有 `MenuListener` 模式（spec 提到的多入口 toggle 同步）。点击调 `Lizzie.webBoardManager.forceExitTrial()` 并触发一次 `collector.broadcastTrialState(null)`。

- [ ] **Step 3**：在 spec 协议章节加备注（fix Task 5 提到的差异）：

```markdown
> **实现备注**：所有上行消息（`exit_trial` / `trial_move` / `trial_navigate` / `trial_reset`）都需携带 `clientId` 字段，服务端用它做 owner 校验。spec 上文 JSON 示例为简洁未列出，实现需补齐。
```

- [ ] **Step 4**：编译：`mvn -q -DskipTests compile`。

- [ ] **Step 5**：commit：

```bash
git add src/main/java/featurecat/lizzie/gui/LizzieFrame.java docs/specs/2026-04-30-web-trial-mode-design.md
git commit -m "feat(web-trial): 菜单加强制结束试下；补齐协议 clientId 实现备注"
```

---

## Task 9：前端 — 试下控制条 + 棋盘点击 + 分叉标记

**Files:**
- Modify: `src/main/resources/web/index.html`
- Modify: `src/main/resources/web/board.js`
- Modify: `src/main/resources/web/board.css`

前端改动较大，分小步走。

- [ ] **Step 1**：`index.html` 加 DOM（控制条放在棋盘下方现有信息面板里）：

```html
<div id="trial-bar" class="trial-bar">
  <button id="trial-enter" class="trial-btn">进入试下</button>
  <div id="trial-controls" class="trial-controls" style="display:none">
    <button id="trial-back">← 后退</button>
    <button id="trial-forward">→ 前进</button>
    <button id="trial-reset">↩ 回锚点</button>
    <button id="trial-exit" class="trial-exit">✕ 退出试下</button>
    <span id="trial-timeout"></span>
  </div>
  <div id="trial-status" class="trial-status"></div>
</div>
```

- [ ] **Step 2**：`board.js` 加 clientId（顶部初始化）：

```js
const clientId = (() => {
  let id = localStorage.getItem('webBoardClientId');
  if (!id) { id = crypto.randomUUID(); localStorage.setItem('webBoardClientId', id); }
  return id;
})();
```

- [ ] **Step 3**：`board.js` 加上行函数：

```js
function sendTrial(type, extra = {}) {
  ws.send(JSON.stringify({ type, clientId, ...extra }));
}
document.getElementById('trial-enter').onclick = () => sendTrial('enter_trial');
document.getElementById('trial-exit').onclick = () => sendTrial('exit_trial');
document.getElementById('trial-back').onclick = () => sendTrial('trial_navigate', {direction: 'back'});
document.getElementById('trial-forward').onclick = () => sendTrial('trial_navigate', {direction: 'forward'});
document.getElementById('trial-reset').onclick = () => sendTrial('trial_reset');
```

- [ ] **Step 4**：在现有 ws onmessage 里加 type 分发：

```js
case 'trial_state':
  applyTrialState(msg);
  break;
case 'trial_denied':
  showToast('另一位用户正在试下，稍后再试');
  break;
```

`applyTrialState(state)`：
- 若 `!state.active`：隐藏 controls，显示 enter 按钮，清 `trialOwner`、`siblingMarkers`，棋盘点击恢复禁用
- 若 `state.active && state.ownerClientId === clientId`：显示 controls，更新 canBack/canForward 按钮 disabled，`trialOwner=true`，渲染 `siblingMarkers`
- 若 `state.active && state.ownerClientId !== clientId`：状态栏显示「他人正在试下中（从第 N 手起）」，棋盘点击禁用

- [ ] **Step 5**：棋盘点击逻辑（在现有 canvas click handler 内）：

```js
canvas.addEventListener('click', (ev) => {
  if (!trialOwner) return;
  const xy = pixelToBoard(ev);
  if (xy) sendTrial('trial_move', { x: xy.x, y: xy.y });
});
```

- [ ] **Step 6**：分叉标记渲染。在主 draw 流程末尾加：

```js
function drawSiblingMarkers(ctx, markers) {
  if (!markers) return;
  for (const m of markers) {
    const [px, py] = boardToPixel(m.x, m.y);
    ctx.fillStyle = 'rgba(255,180,0,0.8)';
    ctx.beginPath(); ctx.arc(px, py, stoneRadius * 0.6, 0, 2*Math.PI); ctx.fill();
    ctx.fillStyle = 'black';
    ctx.font = `${stoneRadius}px sans-serif`;
    ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText(m.label, px, py);
  }
}
```

棋盘点击命中 `siblingMarkers` 中位置时改为 `sendTrial('trial_navigate', {direction:'forward', childIndex: m.childIndex})`，**优先级高于普通 trial_move**。

- [ ] **Step 7**：`board.css` 加最小样式：

```css
.trial-bar { padding: 8px; }
.trial-controls { display: flex; gap: 6px; }
.trial-controls button:disabled { opacity: 0.4; }
.trial-exit { margin-left: auto; }
.trial-status { margin-top: 6px; font-size: 0.9em; color: #888; }
```

- [ ] **Step 8**：手动 QA。启动 `mvn -q exec:java`（或正常 IDE 运行 `Lizzie.main`），打开浏览器访问 web 旁观 URL。检查清单：

| 用例 | 期望 |
|---|---|
| 点「进入试下」 | 按钮切到导航条；棋盘可点击落子 |
| 试下中点棋盘空交叉点 | 落一颗符合 `blackToPlay` 颜色的子；displayNode 前进 |
| 点「← 后退」 | displayNode 退一格；canBack 在 anchor 时灰 |
| 在分叉点点 → | 走 `variations[0]`；棋盘上其它子位置出现橙色数字标记 |
| 点橙色数字 | displayNode 跳到该 sibling 子节点 |
| 点「↩ 回锚点」 | displayNode = anchor |
| 试下中桌面端 Lizzie 棋盘点击 | 状态栏显示「Web 试下进行中…」，不落子 |
| 点「✕ 退出试下」 | 控制条收起；桌面端 displayNode 回到真实 currentNode；试下子树作为 variation 留在树上 |
| 浏览器开第二个 tab，第二个 tab 点「进入试下」 | toast「另一位用户正在试下，稍后再试」 |
| 5 分钟无操作 | 自动退出（手动测时把超时常量临时调小到 30s 验完再调回） |
| Lizzie 菜单点「强制结束试下」 | 试下立即结束，Web owner 收到广播切回 |

QA 通过后才 commit。

- [ ] **Step 9**：commit：

```bash
git add src/main/resources/web/
git commit -m "feat(web-trial): 前端实现试下控制条与分叉标记交互"
```

---

## Task 10：联调收尾

- [ ] **Step 1**：跑全部测试：`mvn -q test`，期望 0 失败。

- [ ] **Step 2**：跑 spotless / 代码格式化（项目惯例，从 pom.xml 找）：`mvn -q spotless:apply` 或类似。

- [ ] **Step 3**：手动 QA 完整路径再走一遍 Task 9 Step 8 表格。

- [ ] **Step 4**：（如有 lint / 静态检查）：`mvn -q verify`。

- [ ] **Step 5**：最终 commit（如有格式化修订）：

```bash
git add -u
git commit -m "chore(web-trial): 格式化与联调收尾" --allow-empty
```

- [ ] **Step 6**：`git log --oneline main..HEAD` 检查 commit 序列清晰，每条都能独立 review。

---

## 不在本计划范围

- 试下分支独立胜率曲线（spec「不在范围内」）
- 试下导出独立 SGF（用现有「保存 SGF」即可）
- 引擎对 displayNode 做 ondemand 分析
- 多人协同试下、抢占式接管

## 失败回退策略

每个 Task 是一个独立 commit，可单独 revert：
- 若 Task 6 渲染有问题，`git revert <BoardRenderer commit>` 回到非 override 状态
- 若 Task 3 状态机出 bug，server 上行已落地但 manager 未启用 trial → 回退该 commit，前端 enter_trial 被服务端 unknown type 静默忽略

## 关键风险点

1. **Task 3 `doApplyMove` 提子计算**：spec 没明确要求处理打劫/提子。建议 MVP 先支持空交叉点落子（`stones[idx] == EMPTY` 才允许），打劫/提子留作后续。在测试和实现里都先验空。
2. **Task 6 候选点屏蔽范围**：BoardRenderer 内可能有十几处覆盖层，挑漏一处不致命但视觉错乱。grep 全找出来逐个改。
3. **`Lizzie.frame` 测试初始化已通过 `DisplayNodeOverrideSink` 注入解决**（Task 3）。但实施时若新增依赖 `Lizzie.frame` 静态全局的代码路径（如 `LizzieFrame.showTrialBlockedToast` 在 manager 里被调用），需为该路径同样加注入点，否则单测仍会 NPE。
