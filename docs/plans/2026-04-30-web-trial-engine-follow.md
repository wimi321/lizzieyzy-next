# Web 试下引擎跟随分析 实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 KataGo 引擎在 Web 试下期间跟随 displayNode 实时分析，恢复试下中桌面端 / Web 端的候选点和当前节点胜率/目差数字显示。

**Architecture:** 新增 `EngineCommandSink` 接口 + `EngineFollowController` 集中管理引擎"指针位置"。controller 通过单线程 executor 串行处理 displayNode 切换，按 LCA 算 undo+playMove 增量序列；遇 SNAPSHOT 节点或异常时 `forceResync` 兜底（委托 `Leelaz.loadsgf` 等价 sync 路径）。`Board.place` 内部 mainline `playMove` 调用集中走 `feedEngineForMainlineMove`，试下激活时短路。试下进入若桌面端正在 `isPlayingAgainstLeelaz/isAnaPlayingAgainstLeelaz` 则拒绝。

**Tech Stack:** Java 8、JUnit 4、`featurecat.lizzie.analysis.Leelaz`、`featurecat.lizzie.gui.web.WebBoardManager`、`featurecat.lizzie.rules.Board/BoardHistoryNode`

**关键 spec：** [`docs/specs/2026-04-30-web-trial-engine-follow-design.md`](../specs/2026-04-30-web-trial-engine-follow-design.md)

**Maven 路径：** `/d/dev/weiqi/lizzieyzy-next/.tools/apache-maven-3.9.10/bin/mvn`（PATH 没有 mvn，必须用绝对路径）

**Spotless 杂音：** 每次 mvn 跑后还原 `git checkout -- src/test/java/featurecat/lizzie/gui/SnapshotNodeRenderGateTest.java`

**通用约束：**
- commit 中文，**不**加 Co-Authored-By 等 AI 标识
- 测试不引入 mockito
- 测试不依赖 `Lizzie.frame` / `Lizzie.board` 静态全局，全部走构造注入
- 不改 `BoardHistoryNode.addOrGoto / addAtLast`
- 不改 `SNAPSHOT_NODE_KIND` / `TRACKING_ANALYSIS_CONTRACT` 契约
- 用 Edit 工具改文件，不要用 `sed -i`（行尾噪音）

---

## 文件结构

**新建：**
- `src/main/java/featurecat/lizzie/analysis/EngineCommandSink.java` —— 接口（playMove / undo / clear / clearBestMoves / loadsgfFromCurrentHistory）
- `src/main/java/featurecat/lizzie/analysis/LeelazEngineCommandSink.java` —— 生产实现，薄 wrapper 调 `Lizzie.leelaz`
- `src/main/java/featurecat/lizzie/analysis/EngineFollowController.java` —— 状态机，executor 串行化
- `src/test/java/featurecat/lizzie/analysis/EngineFollowControllerTest.java` —— fake sink 单测

**修改：**
- `src/main/java/featurecat/lizzie/gui/web/WebBoardManager.java` —— 注入 controller，所有 displayNode hook 追加 `controller.onTrialDisplayNodeChanged(...)`，enterTrial 加桌面端对弈拒绝
- `src/main/java/featurecat/lizzie/gui/web/WebBoardDataCollector.java` —— 删除 line 76 / 107 的 `isAnalysisHiddenForTrial` 抑制
- `src/main/java/featurecat/lizzie/gui/BoardRenderer.java` —— 删除 7 处 `isAnalysisHiddenForTrial()` 早返
- `src/main/java/featurecat/lizzie/gui/LizzieFrame.java` —— 删除 `isAnalysisHiddenForTrial()`
- `src/main/java/featurecat/lizzie/rules/Board.java` —— 加私有 `feedEngineForMainlineMove`，把 mainline 同步路径里的 `Lizzie.leelaz.playMove` 调用统一走它
- `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`（同步管线落点；具体类名以代码为准） —— `Board.place` 之后调 `controller.onMainlineAdvance(currentHistoryNode)`
- `src/main/java/featurecat/lizzie/Lizzie.java`（启动入口） —— 构造 sink + controller 并注入 `WebBoardManager`
- `src/test/java/featurecat/lizzie/gui/web/WebBoardManagerTest.java` —— 加 controller hook 调用断言、桌面端对弈拒绝测试

---

## 任务总览

1. **Task 1**：定义 `EngineCommandSink` 接口
2. **Task 2**：实现 `EngineFollowController` 路径计算（pathBetween + LCA）
3. **Task 3**：实现 `EngineFollowController` 切换入口（onTrialEnter / Display / Exit / MainlineAdvance / forceResync）
4. **Task 4**：实现生产 `LeelazEngineCommandSink`
5. **Task 5**：`WebBoardManager` 接入 controller + 桌面端对弈拒绝
6. **Task 6**：`Board.place` 加 `feedEngineForMainlineMove` + ReadBoard hook `onMainlineAdvance`
7. **Task 7**：删除渲染层 + collector 的试下分析屏蔽
8. **Task 8**：在 `Lizzie` 启动入口装配 controller
9. **Task 9**：手动验收

---

### Task 1：定义 `EngineCommandSink` 接口

**Files:**
- Create: `src/main/java/featurecat/lizzie/analysis/EngineCommandSink.java`

- [ ] **Step 1：写接口**

```java
package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;

/**
 * 给 EngineFollowController 用的引擎指令出口。生产实现包装 Lizzie.leelaz；
 * 测试实现记录调用序列。所有方法可能阻塞（GTP 回环），调用方负责异步派发。
 */
public interface EngineCommandSink {
  void playMove(Stone color, String coord);

  void undo();

  void clear();

  void clearBestMoves();

  /** 触发引擎按当前 BoardHistoryList 重 sync（forceResync 兜底用，等价 loadsgf 路径）。 */
  void resyncFromCurrentHistory(BoardHistoryNode target);
}
```

- [ ] **Step 2：编译通过**

```
/d/dev/weiqi/lizzieyzy-next/.tools/apache-maven-3.9.10/bin/mvn -q -pl . compile -DskipTests
```

预期：BUILD SUCCESS

- [ ] **Step 3：commit**

```bash
git checkout -- src/test/java/featurecat/lizzie/gui/SnapshotNodeRenderGateTest.java 2>/dev/null
git add src/main/java/featurecat/lizzie/analysis/EngineCommandSink.java
git commit -m "feat(engine-follow): 新增 EngineCommandSink 接口"
```

---

### Task 2：`EngineFollowController` 路径计算

**Files:**
- Create: `src/main/java/featurecat/lizzie/analysis/EngineFollowController.java`（仅路径计算 + 字段，切换入口在 Task 3 加）
- Test: `src/test/java/featurecat/lizzie/analysis/EngineFollowControllerTest.java`

**设计：**
`pathBetween(from, to)` 沿 `previous` 指针上溯求 LCA，返回 `(undoCount, List<BoardHistoryNode> playSequence)`。`playSequence` 顺序：从 LCA 的下一层一直到 `to`，每个节点的 move 信息会被 controller 转成 `playMove(color, coord)`。

**SNAPSHOT 处理：** path 上任一节点 `getData().nodeKind == SNAPSHOT` 即视为不可增量，返回特殊值 `Path.RESYNC`，调用方走 `forceResync`。

- [ ] **Step 1：写测试（先失败）**

```java
package featurecat.lizzie.analysis;

import static org.junit.Assert.*;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import org.junit.Test;

public class EngineFollowControllerTest {

  /** 构一个最简 BoardData：只放 lastMove + blackToPlay，其它字段 null/0；够 path 计算用。 */
  private BoardData mkData(int x, int y, Stone color) {
    return BoardData.move(
        new Stone[0],
        new int[] {x, y},
        color,
        color != Stone.BLACK,
        null,
        0,
        null,
        0,
        0,
        0,
        0);
  }

  @Test
  public void pathBetween_sameNode_emptyPath() {
    BoardHistoryNode root = new BoardHistoryNode(mkData(0, 0, Stone.BLACK));
    EngineFollowController.Path p = EngineFollowController.pathBetween(root, root);
    assertEquals(0, p.undoCount);
    assertTrue(p.playSequence.isEmpty());
    assertFalse(p.needsResync);
  }

  @Test
  public void pathBetween_forwardOneStep() {
    BoardHistoryNode root = new BoardHistoryNode(mkData(0, 0, Stone.BLACK));
    BoardHistoryNode child = new BoardHistoryNode(mkData(3, 3, Stone.BLACK));
    root.variations.add(child);
    root.setPreviousForChild(child);

    EngineFollowController.Path p = EngineFollowController.pathBetween(root, child);
    assertEquals(0, p.undoCount);
    assertEquals(1, p.playSequence.size());
    assertSame(child, p.playSequence.get(0));
  }

  @Test
  public void pathBetween_backwardTwoSteps() {
    BoardHistoryNode a = new BoardHistoryNode(mkData(0, 0, Stone.BLACK));
    BoardHistoryNode b = new BoardHistoryNode(mkData(3, 3, Stone.BLACK));
    BoardHistoryNode c = new BoardHistoryNode(mkData(15, 15, Stone.WHITE));
    a.variations.add(b);
    a.setPreviousForChild(b);
    b.variations.add(c);
    b.setPreviousForChild(c);

    EngineFollowController.Path p = EngineFollowController.pathBetween(c, a);
    assertEquals(2, p.undoCount);
    assertTrue(p.playSequence.isEmpty());
  }

  @Test
  public void pathBetween_siblingJump() {
    BoardHistoryNode root = new BoardHistoryNode(mkData(0, 0, Stone.BLACK));
    BoardHistoryNode left = new BoardHistoryNode(mkData(3, 3, Stone.BLACK));
    BoardHistoryNode right = new BoardHistoryNode(mkData(15, 15, Stone.BLACK));
    root.variations.add(left);
    root.setPreviousForChild(left);
    root.variations.add(right);
    root.setPreviousForChild(right);

    EngineFollowController.Path p = EngineFollowController.pathBetween(left, right);
    assertEquals(1, p.undoCount);
    assertEquals(1, p.playSequence.size());
    assertSame(right, p.playSequence.get(0));
  }
}
```

- [ ] **Step 2：跑测试确认失败**

```
/d/dev/weiqi/lizzieyzy-next/.tools/apache-maven-3.9.10/bin/mvn -q test -Dtest=EngineFollowControllerTest
```

预期：编译失败（类不存在）

- [ ] **Step 3：写最小实现**

```java
package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class EngineFollowController {

  public static final class Path {
    public final int undoCount;
    public final List<BoardHistoryNode> playSequence;
    public final boolean needsResync;

    private Path(int undoCount, List<BoardHistoryNode> playSequence, boolean needsResync) {
      this.undoCount = undoCount;
      this.playSequence = playSequence;
      this.needsResync = needsResync;
    }

    static Path of(int undo, List<BoardHistoryNode> play) {
      return new Path(undo, play, false);
    }

    static Path resync() {
      return new Path(0, Collections.emptyList(), true);
    }
  }

  /** 算 from -> to 的增量切换路径。任一节点是 SNAPSHOT 则返回 needsResync=true。 */
  public static Path pathBetween(BoardHistoryNode from, BoardHistoryNode to) {
    if (from == to) return Path.of(0, Collections.emptyList());

    // 1. 收集 from 到根的祖先集合
    Set<BoardHistoryNode> fromAncestors = new HashSet<>();
    for (BoardHistoryNode n = from; n != null; n = n.previous().orElse(null)) {
      fromAncestors.add(n);
    }

    // 2. 沿 to 上溯找首个公共祖先
    List<BoardHistoryNode> toUpward = new ArrayList<>();
    BoardHistoryNode lca = null;
    for (BoardHistoryNode n = to; n != null; n = n.previous().orElse(null)) {
      if (fromAncestors.contains(n)) {
        lca = n;
        break;
      }
      toUpward.add(n);
    }
    if (lca == null) return Path.resync(); // 不连通（理论上不该发生）

    // 3. 算 from -> lca 的 undo 数（含 SNAPSHOT 检测）
    int undoCount = 0;
    for (BoardHistoryNode n = from; n != lca; n = n.previous().orElse(null)) {
      if (isSnapshot(n)) return Path.resync();
      undoCount++;
    }

    // 4. toUpward 是从 to 一路到 lca 的子节点（含 to，不含 lca），反转得到 lca 之后的 play 序列
    Collections.reverse(toUpward);
    for (BoardHistoryNode n : toUpward) {
      if (isSnapshot(n)) return Path.resync();
    }
    return Path.of(undoCount, toUpward);
  }

  private static boolean isSnapshot(BoardHistoryNode n) {
    BoardData d = n.getData();
    return d != null && d.nodeKind == BoardData.NodeKind.SNAPSHOT;
  }
}
```

⚠️ 注意：`BoardData.NodeKind.SNAPSHOT` 实际枚举名以代码为准（grep `SNAPSHOT` 在 BoardData.java），如有差异按实际写。

- [ ] **Step 4：跑测试确认通过**

```
/d/dev/weiqi/lizzieyzy-next/.tools/apache-maven-3.9.10/bin/mvn -q test -Dtest=EngineFollowControllerTest
git checkout -- src/test/java/featurecat/lizzie/gui/SnapshotNodeRenderGateTest.java 2>/dev/null
```

预期：4 tests passed

- [ ] **Step 5：commit**

```bash
git add src/main/java/featurecat/lizzie/analysis/EngineFollowController.java src/test/java/featurecat/lizzie/analysis/EngineFollowControllerTest.java
git commit -m "feat(engine-follow): EngineFollowController 增量路径计算"
```

---

### Task 3：`EngineFollowController` 切换入口与 executor

**Files:**
- Modify: `src/main/java/featurecat/lizzie/analysis/EngineFollowController.java`
- Modify: `src/test/java/featurecat/lizzie/analysis/EngineFollowControllerTest.java`

**新增字段：** `sink`、`currentEngineNode`、`trialActive`、单线程 `Executor`（`Executors.newSingleThreadExecutor` 加 daemon ThreadFactory）。

**新增方法：** `onTrialEnter`、`onTrialDisplayNodeChanged`、`onTrialExit`、`onMainlineAdvance`、`forceResync`、`isTrialActive`、`awaitIdle`（测试用，executor.shutdown + awaitTermination）。

测试用 `RecordingEngineCommandSink`，把每次调用追加 `List<String>` 如 `"play B Q16"` / `"undo"` / `"clear"` / `"clearBestMoves"` / `"resync(<nodeId>)"`。

- [ ] **Step 1：在测试文件加 fake sink + 切换序列断言**

```java
private static final class RecordingEngineCommandSink implements EngineCommandSink {
  final java.util.List<String> calls = new java.util.ArrayList<>();
  RuntimeException nextThrow;

  @Override
  public void playMove(Stone color, String coord) {
    if (consumeThrow()) return;
    calls.add("play " + (color == Stone.BLACK ? "B" : "W") + " " + coord);
  }

  @Override
  public void undo() {
    if (consumeThrow()) return;
    calls.add("undo");
  }

  @Override
  public void clear() {
    if (consumeThrow()) return;
    calls.add("clear");
  }

  @Override
  public void clearBestMoves() {
    if (consumeThrow()) return;
    calls.add("clearBestMoves");
  }

  @Override
  public void resyncFromCurrentHistory(BoardHistoryNode target) {
    if (consumeThrow()) return;
    calls.add("resync");
  }

  private boolean consumeThrow() {
    if (nextThrow != null) {
      RuntimeException e = nextThrow;
      nextThrow = null;
      throw e;
    }
    return false;
  }
}

// helper：构 BoardData 时填 lastMoveColor 和 coord，让 playMove 序列可断言。
// 复用前面 mkData，但要把 lastMoveColor 写进去：
private BoardData mkMove(int x, int y, Stone color) {
  return BoardData.move(
      new Stone[0], new int[] {x, y}, color, color != Stone.BLACK,
      null, 1, null, 0, 0, 0, 0);
}

// 注意：playMove 时 color/coord 来自子节点 BoardData.lastMoveColor + lastMove；
// controller 实现要从节点 data 拿这两个字段拼 GTP。

@Test
public void displayNodeChanged_oneForward_emitsOnePlay() throws Exception {
  BoardHistoryNode anchor = new BoardHistoryNode(mkMove(0, 0, Stone.WHITE));
  BoardHistoryNode child = new BoardHistoryNode(mkMove(3, 3, Stone.BLACK));
  anchor.variations.add(child);
  anchor.setPreviousForChild(child);

  RecordingEngineCommandSink sink = new RecordingEngineCommandSink();
  EngineFollowController c = new EngineFollowController(sink);
  c.onTrialEnter(anchor);
  c.onTrialDisplayNodeChanged(child);
  c.awaitIdle();

  // 期望：onTrialEnter 不发命令；onTrialDisplayNodeChanged 发 1 次 playMove + 1 次 clearBestMoves
  assertEquals(java.util.Arrays.asList("play B D17", "clearBestMoves"), sink.calls);
}
```

⚠️ 坐标 `D17` 计算：x=3,y=3 在 19 路上 → A19 是 (0,0)，所以 (3,3) → D16？需要按 lizzie 现有 `Board.convertCoordinatesToName` / `BoardRenderer` 的转换逻辑核实。在测试里用一个稳定的小工具方法 `coord(x, y)` 算预期值，避免硬编码出错。**实际写测试时先 grep `convertCoordinatesToName`，调它算预期值。**

补充测试：

```java
@Test
public void displayNodeChanged_oneBack_emitsOneUndo() throws Exception { /* ... */ }

@Test
public void displayNodeChanged_siblingJump_emitsUndoThenPlay() throws Exception { /* ... */ }

@Test
public void mainlineAdvance_duringTrial_doesNotEmitGtp() throws Exception {
  // anchor → mainline tail B；trial 进入后 mainline 推进到 C
  // controller 期望：onMainlineAdvance 时不发命令；onTrialExit(C) 时一次性补
}

@Test
public void mainlineAdvance_outsideTrial_emitsPlay() throws Exception {
  // 非试下时 onMainlineAdvance 直接 sink.playMove
}

@Test
public void sinkException_triggersForceResync() throws Exception {
  RecordingEngineCommandSink sink = new RecordingEngineCommandSink();
  sink.nextThrow = new RuntimeException("boom");
  // 期望：playMove 失败 → 触发 forceResync → calls 含 "resync"
}

@Test
public void enter_whenEngineNodeMismatch_forceResyncs() throws Exception {
  // currentEngineNode 初始为 root；enter(anchor != root) → 应触发 resync 对齐
}
```

- [ ] **Step 2：跑测试确认失败**

```
/d/dev/weiqi/lizzieyzy-next/.tools/apache-maven-3.9.10/bin/mvn -q test -Dtest=EngineFollowControllerTest
```

预期：编译失败（构造函数 + 方法不存在）

- [ ] **Step 3：实现 controller**

完整实现要点：
- 构造器接收 `EngineCommandSink sink`，初始化 `Executors.newSingleThreadExecutor(daemonFactory)`
- `currentEngineNode` 初始 null（启动时由 `setCurrentEngineNode(root)` 显式设）
- `onTrialEnter(anchor)`：set `trialActive = true`，submit task：若 `currentEngineNode != anchor` 则 `forceResync(anchor)`
- `onTrialDisplayNodeChanged(node)`：submit task → `pathBetween(currentEngineNode, node)` → 若 `needsResync` 调 `forceResync(node)`；否则按 path 发 undo×N + playMove×M（playMove 参数从节点 `data.lastMoveColor` + `Board.convertCoordinatesToName(data.lastMove)` 取）→ `clearBestMoves` → 更新 `currentEngineNode`
- `onTrialExit(target)`：submit task → 与 onTrialDisplayNodeChanged 同逻辑，最后 `trialActive = false`
- `onMainlineAdvance(newTail)`：若 `trialActive` 直接 return；否则 submit task：算 path 推进 + 更新 `currentEngineNode`
- `forceResync(target)`：catch 内部异常打日志，调用 `sink.resyncFromCurrentHistory(target)` → `clearBestMoves` → `currentEngineNode = target`
- 任何 task 中 sink 抛异常 → catch 调 `forceResync(目标 node)`；forceResync 自身抛异常 → 记 ERROR 日志（用 `org.slf4j.Logger`，仿照 WebBoardManager 的日志风格），不再重抛
- `awaitIdle()` 测试钩子：submit 一个空任务并 `.get(2, SECONDS)`（不要 shutdown，方便后续测试继续用）

- [ ] **Step 4：跑测试确认通过**

```
/d/dev/weiqi/lizzieyzy-next/.tools/apache-maven-3.9.10/bin/mvn -q test -Dtest=EngineFollowControllerTest
git checkout -- src/test/java/featurecat/lizzie/gui/SnapshotNodeRenderGateTest.java 2>/dev/null
```

预期：所有测试 pass

- [ ] **Step 5：commit**

```bash
git add src/main/java/featurecat/lizzie/analysis/EngineFollowController.java src/test/java/featurecat/lizzie/analysis/EngineFollowControllerTest.java
git commit -m "feat(engine-follow): controller 切换入口与异常兜底"
```

---

### Task 4：生产 `LeelazEngineCommandSink`

**Files:**
- Create: `src/main/java/featurecat/lizzie/analysis/LeelazEngineCommandSink.java`

- [ ] **Step 1：写实现**

```java
package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;

public final class LeelazEngineCommandSink implements EngineCommandSink {

  @Override
  public void playMove(Stone color, String coord) {
    Lizzie.leelaz.playMove(color, coord);
  }

  @Override
  public void undo() {
    Lizzie.leelaz.undo();
  }

  @Override
  public void clear() {
    Lizzie.leelaz.clear();
  }

  @Override
  public void clearBestMoves() {
    Lizzie.leelaz.clearBestMoves();
  }

  @Override
  public void resyncFromCurrentHistory(BoardHistoryNode target) {
    // 走 Lizzie 现有"按当前 BoardHistoryList 重 sync 引擎"路径。
    // 具体实现：调用 Lizzie.leelaz 现有的 sync helper（fix-sync 流程），
    // 不在 controller 里解析 SNAPSHOT setup 元数据。
    Lizzie.leelaz.syncBoardStateWithEngine();
  }
}
```

⚠️ 实施时必须先 grep `Leelaz.java` 确认实际存在的 sync 方法名（如 `syncBoardStateWithEngine` / `fixEngineSync` / 类似名字）；如没有现成方法，则在 `Leelaz` 里加一个**仅做工厂动作**的 `void resyncFromHistory()`（实现仅是已有 fix-sync 路径的封装，不新增逻辑）。

- [ ] **Step 2：编译**

```
/d/dev/weiqi/lizzieyzy-next/.tools/apache-maven-3.9.10/bin/mvn -q -pl . compile -DskipTests
```

- [ ] **Step 3：commit**

```bash
git checkout -- src/test/java/featurecat/lizzie/gui/SnapshotNodeRenderGateTest.java 2>/dev/null
git add src/main/java/featurecat/lizzie/analysis/LeelazEngineCommandSink.java
git commit -m "feat(engine-follow): 生产 LeelazEngineCommandSink 实现"
```

---

### Task 5：`WebBoardManager` 接入 controller + 桌面端对弈拒绝

**Files:**
- Modify: `src/main/java/featurecat/lizzie/gui/web/WebBoardManager.java`
- Modify: `src/test/java/featurecat/lizzie/gui/web/WebBoardManagerTest.java`

- [ ] **Step 1：在 `WebBoardManager` 加字段 + setter**

参考现有 `setOverrideSink(...)` 模式，加：

```java
private volatile EngineFollowController engineController;
private volatile java.util.function.BooleanSupplier desktopPlayingProbe = () -> false;

public void setEngineFollowController(EngineFollowController c) { this.engineController = c; }
public void setDesktopPlayingProbe(java.util.function.BooleanSupplier p) {
  if (p != null) this.desktopPlayingProbe = p;
}
```

`desktopPlayingProbe` 让测试不依赖 `Lizzie.frame.isPlayingAgainstLeelaz` 静态全局；生产装配时注入 `() -> Lizzie.frame.isPlayingAgainstLeelaz || Lizzie.frame.isAnaPlayingAgainstLeelaz`。

- [ ] **Step 2：`enterTrial` 加桌面端对弈拒绝**

```java
public synchronized boolean enterTrial(String clientId, BoardHistoryNode anchor) {
  if (desktopPlayingProbe.getAsBoolean()) {
    return false; // 桌面端对弈中拒绝
  }
  // ...原逻辑...
  EngineFollowController c = engineController;
  if (c != null) c.onTrialEnter(anchor);
  return true;
}
```

`enterTrial` 调用方（line 119）已根据返回值发 ack；需要扩展 ack 协议带 reason `engine_busy`（具体协议字段位置参考前置 spec 第 234 行附近）。

- [ ] **Step 3：所有 displayNode 变更 hook 加 controller 调用**

修改点：
- `applyTrialMove` → `doApplyMove` 末尾（line 397 之后）：`if (engineController != null) engineController.onTrialDisplayNodeChanged(child);`（同位置子节点复用分支 line 353 也要加 `onTrialDisplayNodeChanged(existing)`）
- `trialNavigate` line 312 之后：`if (engineController != null) engineController.onTrialDisplayNodeChanged(s.displayNode);`
- `trialNavigateForward` line 325 之后：同上
- `trialReset` line 335 之后：同上
- `exitTrial` line 261 之后：`if (engineController != null) engineController.onTrialExit(Lizzie.board.history.getCurrentHistoryNode());`
- `forceExitTrial` line 269 之后：同 exitTrial

⚠️ `Lizzie.board.history.getCurrentHistoryNode()` 也是静态全局；为可测，把"取 mainlineTail"也抽成 `Supplier<BoardHistoryNode>` 注入：

```java
private volatile java.util.function.Supplier<BoardHistoryNode> mainlineTailSupplier =
    () -> Lizzie.board != null ? Lizzie.board.getHistory().getCurrentHistoryNode() : null;

public void setMainlineTailSupplier(java.util.function.Supplier<BoardHistoryNode> s) {
  if (s != null) this.mainlineTailSupplier = s;
}
```

- [ ] **Step 4：写测试**

在 `WebBoardManagerTest` 添加：

```java
@Test
public void enterTrial_refusesWhenDesktopPlaying() {
  WebBoardManager mgr = new WebBoardManager(/* 现有 fixture */);
  mgr.setDesktopPlayingProbe(() -> true);
  BoardHistoryNode anchor = /* 构 anchor */;
  assertFalse(mgr.enterTrial("client-1", anchor));
}

@Test
public void applyTrialMove_invokesControllerHook() {
  RecordingEngineCommandSink sink = new RecordingEngineCommandSink();
  EngineFollowController c = new EngineFollowController(sink);
  WebBoardManager mgr = /* fixture */;
  mgr.setEngineFollowController(c);
  /* enterTrial + applyTrialMove */
  c.awaitIdle();
  // 断言 sink.calls 含 "play X Y" + "clearBestMoves"
}
```

⚠️ 现有 `WebBoardManagerTest` 的 fixture 通常需要 `WebBoardDataCollector` mock/fake；沿用现有测试模式，不引入 mockito。

- [ ] **Step 5：跑全套 web 测试**

```
/d/dev/weiqi/lizzieyzy-next/.tools/apache-maven-3.9.10/bin/mvn -q test -Dtest='WebBoardManagerTest,EngineFollowControllerTest'
git checkout -- src/test/java/featurecat/lizzie/gui/SnapshotNodeRenderGateTest.java 2>/dev/null
```

预期：全 pass

- [ ] **Step 6：commit**

```bash
git add src/main/java/featurecat/lizzie/gui/web/WebBoardManager.java src/test/java/featurecat/lizzie/gui/web/WebBoardManagerTest.java
git commit -m "feat(engine-follow): WebBoardManager 接入 controller 与桌面端对弈拒绝"
```

---

### Task 6：`Board.place` mainline 拦截 + ReadBoard hook

**Files:**
- Modify: `src/main/java/featurecat/lizzie/rules/Board.java`
- Modify: `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`（同步管线落点）

- [ ] **Step 1：在 `Board.java` 加私有 helper**

```java
/**
 * 试下激活时 mainline 推进的 GTP 调用要由 EngineFollowController 接管，
 * 这里短路；非试下时按原路调 leelaz.playMove。
 * 用户落子路径在前置 spec 决策 8 的 guard 中已被拒绝，故试下中只可能 ReadBoard 同步路径走到这里。
 */
private void feedEngineForMainlineMove(Stone color, String coord) {
  EngineFollowController c = Lizzie.engineFollowController;
  if (c != null && c.isTrialActive()) return;
  Lizzie.leelaz.playMove(color, coord);
}
```

`Lizzie.engineFollowController` 是 Task 8 加的静态字段。

- [ ] **Step 2：把 `Board.java` 中所有 mainline 同步路径的 `Lizzie.leelaz.playMove(...)` 调用替换为 `feedEngineForMainlineMove(...)`**

grep 结果（前面已收集，约 13 处）。**不要替换的**：
- 用户落子路径（spec 决策 8 已 guard，但代码上无法区分 mainline / user 路径时优先保守；判断方式：看上下文有无 `LizzieFrame.displayNodeOverride != null` guard，已 guard 的路径无所谓改不改）
- 注释行（line 1732 / 2072）
- `playMovePonder`（line 1889，不是 playMove）
- SGF 加载路径（如果有，通常调用 loadsgf 而非 playMove）

替换前用 `git diff` 逐行核对每处上下文，确保改的是 mainline 推进路径。

⚠️ 实施时第一步先把整个 `Board.java` 的 `Lizzie.leelaz.playMove` 行号全部列出，按调用上下文分类（mainline 推进 / 用户落子 / pass / SGF），仅替换"mainline 推进"分类。每替换 3-5 处编译一次确认。

- [ ] **Step 3：在 `ReadBoard.java`（或同步落点）每次 `Board.place` 之后追加**

```java
EngineFollowController c = Lizzie.engineFollowController;
if (c != null) {
  BoardHistoryNode tail = Lizzie.board.getHistory().getCurrentHistoryNode();
  c.onMainlineAdvance(tail);
}
```

具体行号：`grep -n "Lizzie.board.place\|Lizzie\.board\.placeMoveQuick" src/main/java/featurecat/lizzie/analysis/ReadBoard.java` 找到落点，在最近的 place 之后插。

- [ ] **Step 4：编译 + 跑全测**

```
/d/dev/weiqi/lizzieyzy-next/.tools/apache-maven-3.9.10/bin/mvn -q test
git checkout -- src/test/java/featurecat/lizzie/gui/SnapshotNodeRenderGateTest.java 2>/dev/null
```

预期：全测 pass（除 pre-existing flake `WebBoardServerTest.rejectsConnectionsAboveLimit`）

- [ ] **Step 5：commit**

```bash
git add src/main/java/featurecat/lizzie/rules/Board.java src/main/java/featurecat/lizzie/analysis/ReadBoard.java
git commit -m "feat(engine-follow): Board.place mainline 拦截与 ReadBoard 通知"
```

---

### Task 7：删除渲染层 + collector 的试下分析屏蔽

**Files:**
- Modify: `src/main/java/featurecat/lizzie/gui/BoardRenderer.java`
- Modify: `src/main/java/featurecat/lizzie/gui/web/WebBoardDataCollector.java`
- Modify: `src/main/java/featurecat/lizzie/gui/LizzieFrame.java`

- [ ] **Step 1：删除 BoardRenderer 7 处 guard**

行号（注意编辑后行号会变，每次只删一处再确认下一处的新行号）：318 / 1473 / 1870 / 2171 / 3139 / 3249 / 4079。

每处都是单行 `if (Lizzie.frame != null && Lizzie.frame.isAnalysisHiddenForTrial()) return;`，整行删除。

- [ ] **Step 2：删除 collector 2 处抑制**

`WebBoardDataCollector.java` line 76 + line 107 附近的 `isAnalysisHiddenForTrial` 短路条件。

⚠️ line 76 是 `analysis_update` 广播抑制，删后试下中 `analysis_update` 正常发；line 107 是某字段填充逻辑（可能涉及 winrate_history）。**winrate_history 仍要保持 spec 决策 5：仅含 anchor 之前 mainline**——所以 line 107 不能简单删，要核对其逻辑：
- 如果 line 107 的 `trialActive` 分支是"试下时只取 anchor 之前 mainline 计算 winrate_history"（即正确实现），则**保留**这个分支
- 如果 line 107 是"试下时整个 winrate_history 设空"，则改为"沿 displayNode 上溯到 anchor，再从 anchor 沿 previous 取 mainline 序列"——但这一步如果太大就拆出独立任务

实施步骤：先 read `WebBoardDataCollector.java` 70-130 行看清楚两处分支语义，再决定删 / 改。**默认行为：line 76 删（恢复广播）；line 107 仅在它是"整段抑制"时删，若是"按 anchor 计算"则保留。**

- [ ] **Step 3：删除 `LizzieFrame.isAnalysisHiddenForTrial()` 方法**

`LizzieFrame.java` line 1884 起的方法整段删除。

- [ ] **Step 4：编译 + 跑测**

```
/d/dev/weiqi/lizzieyzy-next/.tools/apache-maven-3.9.10/bin/mvn -q test
git checkout -- src/test/java/featurecat/lizzie/gui/SnapshotNodeRenderGateTest.java 2>/dev/null
```

预期：全测 pass

- [ ] **Step 5：commit**

```bash
git add src/main/java/featurecat/lizzie/gui/BoardRenderer.java src/main/java/featurecat/lizzie/gui/web/WebBoardDataCollector.java src/main/java/featurecat/lizzie/gui/LizzieFrame.java
git commit -m "refactor(engine-follow): 删除试下分析屏蔽，候选点恢复实时显示"
```

---

### Task 8：在 `Lizzie` 启动入口装配 controller

**Files:**
- Modify: `src/main/java/featurecat/lizzie/Lizzie.java`

- [ ] **Step 1：加静态字段**

```java
public static EngineFollowController engineFollowController;
```

- [ ] **Step 2：找到 `WebBoardManager` 与 `Leelaz` 初始化点（grep `new WebBoardManager` / `new Leelaz`）**，在 `WebBoardManager` 创建之后、引擎可用之后插入：

```java
EngineCommandSink sink = new LeelazEngineCommandSink();
engineFollowController = new EngineFollowController(sink);
engineFollowController.setCurrentEngineNode(board.getHistory().getCurrentHistoryNode());
webBoardManager.setEngineFollowController(engineFollowController);
webBoardManager.setDesktopPlayingProbe(
    () -> Lizzie.frame != null
        && (Lizzie.frame.isPlayingAgainstLeelaz || Lizzie.frame.isAnaPlayingAgainstLeelaz));
webBoardManager.setMainlineTailSupplier(() -> Lizzie.board.getHistory().getCurrentHistoryNode());
```

⚠️ `setCurrentEngineNode` 是 controller 给生产代码用的初始化 setter（仅允许在 trial 未激活时调）；Task 3 实现 controller 时记得加这个方法。

- [ ] **Step 3：编译 + 跑全测**

```
/d/dev/weiqi/lizzieyzy-next/.tools/apache-maven-3.9.10/bin/mvn -q test
git checkout -- src/test/java/featurecat/lizzie/gui/SnapshotNodeRenderGateTest.java 2>/dev/null
```

- [ ] **Step 4：commit**

```bash
git add src/main/java/featurecat/lizzie/Lizzie.java src/main/java/featurecat/lizzie/analysis/EngineFollowController.java
git commit -m "feat(engine-follow): 启动入口装配 controller 与桌面端对弈探针"
```

---

### Task 9：手动验收

- [ ] **Step 1：启动 lizzieyzy-next 桌面端，开 Web 端浏览器**

```
/d/dev/weiqi/lizzieyzy-next/.tools/apache-maven-3.9.10/bin/mvn -q -DskipTests package
# 或按现有 README 启动方式
```

- [ ] **Step 2：验收清单**

- [ ] 试下进入：anchor 处候选点显示与 mainline 一致
- [ ] 试下落 1 子：1 秒内桌面端 + Web 端候选点切换到新局面对应分析
- [ ] 试下后退 1 手：候选点回到上一手分析
- [ ] 试下前进 / 跳兄弟分叉：候选点跟随
- [ ] ↩ 回锚点：候选点回 anchor 分析
- [ ] 退出试下：桌面端候选点立刻回到真 mainline 末端 currentNode 分析
- [ ] 试下中胜率/目差数字（桌面端 winrate bar / Web 端字段）跟随 displayNode
- [ ] 胜率曲线（桌面端 + Web 端）只显示 anchor 之前 mainline，试下分支节点不画进去
- [ ] 试下期间外部对局推 mainline 新手：桌面端棋盘画面更新（mainline 推进可见），引擎不切；试下退出后引擎切回 mainline tail（含新手）
- [ ] 桌面端在对弈模式（启动 KataGo 对弈）时，Web 端发起试下被拒绝（toast / 错误提示）

- [ ] **Step 3：如有 regression 立刻回到对应 Task 排查**

---

## 完成后

- [ ] 写一行 release note 草稿到 PR 描述
- [ ] 更新前置 spec `2026-04-30-web-trial-mode-design.md`：决策 2 / 决策 6 / 「不在范围内」按本 spec「与现有契约的关系」段所列改写（这一步可以本任务最后一个 commit 顺手做）

```bash
git commit -m "docs: 同步前置试下 spec 的引擎/对弈冲突决策"
```
