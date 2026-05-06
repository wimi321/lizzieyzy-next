# Web 试下引擎跟随分析 实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 KataGo 引擎在 Web 试下期间跟随 displayNode 实时分析，恢复试下中桌面端 / Web 端的候选点和当前节点胜率/目差数字显示。

**Architecture:** 新增 `EngineCommandSink` 接口 + `EngineFollowController` 集中管理引擎"指针位置"。controller 通过单线程 executor 串行处理 displayNode 切换，按 LCA 算 undo+playMove 增量序列；遇 SNAPSHOT 节点或异常时 `forceResync` 兜底（委托 `Leelaz.loadsgf` 等价 sync 路径）。`Board.place` 内部 mainline `playMove` 调用集中走 `feedEngineForMainlineMove`，试下激活时短路。试下进入若桌面端正在 `isPlayingAgainstLeelaz/isAnaPlayingAgainstLeelaz` 则拒绝。

**Tech Stack:** Java 8、JUnit 4、`featurecat.lizzie.analysis.Leelaz`、`featurecat.lizzie.gui.web.WebBoardManager`、`featurecat.lizzie.rules.Board/BoardHistoryNode`

**关键 spec：** [`docs/specs/2026-04-30-web-trial-engine-follow-design.md`](../specs/2026-04-30-web-trial-engine-follow-design.md)

**Maven 路径：** `/d/dev/weiqi/lizzieyzy-next/.tools/apache-maven-3.9.10/bin/mvn`（PATH 没有 mvn，必须用绝对路径）

**Spotless 杂音：** 每次 mvn 跑后还原 `git checkout -- src/test/java/featurecat/lizzie/gui/SnapshotNodeRenderGateTest.java`

**基线 commit：** 本计划基于 `77bb1f7`（最新 fix 之上）。早期版本提到的 `WebBoardManager` 行号（line 261/269/312/325/335/353/397）已飘移；实施时按**调用点**而非行号定位，不要依赖 plan 里的具体数字。

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

⚠️ 坐标 `D17` 计算：x=3,y=3 在 19 路上 → A19 是 (0,0)，所以 (3,3) → D16？需要按 lizzie 现有 `Board.convertCoordinatesToName` / `BoardRenderer` 的转换逻辑核实。**测试里不要硬编码字符串**——在 setup 里调 `Board.convertCoordinatesToName(x, y)` 算预期，断言用变量：

```java
String expectedCoord = featurecat.lizzie.rules.Board.convertCoordinatesToName(3, 3);
assertEquals(java.util.Arrays.asList("play B " + expectedCoord, "clearBestMoves"), sink.calls);
```

⚠️ **实施前先 Read `BoardData.move(...)` 工厂签名确认 `lastMoveColor` 字段填充顺序**：测试里 `mkMove(3, 3, Stone.BLACK)` 期望子节点 `data.lastMoveColor == BLACK`，但 `BoardData.move` 的入参 `Stone color` 在不同重载里语义可能是"刚下的子的颜色"或"下一手轮到的颜色"。controller 实现从子节点 `data` 取颜色拼 GTP 时也要核对一致性。如果约定不一致，调整 mkMove 的传参或 controller 的 color 取法。

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
  - **注**：spec 决策 3 早期措辞写"clear()+从根全量 play"。本计划采用委托方案（与 SNAPSHOT 段一致）：controller **不**自己跑 clear+playback，全量重 sync 由 `sink.resyncFromCurrentHistory` 委托给 Leelaz 现有 fix-sync 路径。`EngineCommandSink.clear()` 接口仍保留以备特殊场景，但 controller 自身不调用
- 任何 task 中 sink 抛异常 → catch 调 `forceResync(目标 node)`；forceResync 自身抛异常 → 记 ERROR 日志（用 `org.slf4j.Logger`，仿照 WebBoardManager 的日志风格），不再重抛
- `awaitIdle()` 测试钩子：submit 一个空任务并 `.get(2, SECONDS)`（不要 shutdown，方便后续测试继续用）
- `setCurrentEngineNode(node)`：仅供启动入口（Task 8）调用一次做初始化对齐；assert `!trialActive`

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

⚠️ **dummy 节点处理**：`WebBoardManager.cleanupMainlineDummy`（commit `4deb696`）改为：试下退出时若 anchor 下已有用户落的非 dummy 试下子，**保留** dummy 占位（防止试下子接续主线）。这意味着 BoardHistoryList 中可能存在 `BoardData.dummy == true` 的节点。

dummy 影响**两条路径**，分别处理：

**(a) `forceResync` 路径（Task 4）**：实施 Step 1 前先核对 lizzie 现有 fix-sync 路径是否会跳过 `dummy == true` 的 BoardData：
- 若已跳过：直接调用即可
- 若未跳过：在 `LeelazEngineCommandSink.resyncFromCurrentHistory` 里加一层包装，遍历 history 时跳过 dummy 节点

**(b) 增量路径（Task 2 / Task 5 onTrialExit）**：
- `pathBetween` 在 `playSequence` 收集阶段必须跳过 `data.dummy == true` 的节点（否则会对 dummy 发 `playMove(color, dummyCoord)`）。**修订 Task 2 实现**：在 `playSequence` 收集循环里 `if (n.getData() != null && n.getData().dummy) continue;`
- 同时 Task 5 注入的默认 `mainlineTailSupplier` 必须返回**最后一个非 dummy 节点**，防止 `onTrialExit` 把 dummy 当切换目标：

```java
private volatile java.util.function.Supplier<BoardHistoryNode> mainlineTailSupplier =
    () -> {
      if (Lizzie.board == null) return null;
      BoardHistoryNode n = Lizzie.board.getHistory().getCurrentHistoryNode();
      while (n != null && n.getData() != null && n.getData().dummy) {
        n = n.previous().orElse(null);
      }
      return n;
    };
```

dummy 节点定位：`grep -n "data.dummy\|getData\(\)\.dummy\|\.dummy = true" src/main/java/featurecat/lizzie/`

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

⚠️ **以 commit `77bb1f7` 为基线**，`WebBoardManager` 新增了 `applyOverrideAndRefresh(node)` 私有包装器（覆盖 `overrideSink.set` + `desktopRefresher.refresh`），所有 displayNode 变更点已统一调它。Plan 早期版本写的"line 312 / 325 / 335 / 353 / 397 / 261 / 269"行号已飘移；按**调用点**而非行号定位。

**最简实施方式：把 controller hook 也搬进 `applyOverrideAndRefresh`**：

```java
private void applyOverrideAndRefresh(BoardHistoryNode node) {
  overrideSink.set(node);
  desktopRefresher.refresh();
  EngineFollowController c = engineController;
  if (c != null) {
    if (node == null) {
      // 退出试下：node==null 表示 displayNodeOverride 被清空，引擎要切回真 mainline tail
      BoardHistoryNode tail = mainlineTailSupplier.get();
      if (tail != null) c.onTrialExit(tail);
    } else {
      // 试下中各种 displayNode 切换；onTrialEnter 仍单独在 enterTrial 里调（区分"进入"语义）
      if (activeSession != null && node != activeSession.anchorNode || c.isTrialActive()) {
        c.onTrialDisplayNodeChanged(node);
      }
    }
  }
}
```

⚠️ 上面分支判断要小心 enterTrial 第一次调 `applyOverrideAndRefresh(anchor)` 时 `activeSession` 已经 set 但 controller 还没 `onTrialEnter`——会误进 `onTrialDisplayNodeChanged` 分支。**正确做法**：让 `enterTrial` 在调 `applyOverrideAndRefresh(anchor)` **之前**先调 `controller.onTrialEnter(anchor)`，让 `c.isTrialActive()` 已为 true，然后 `applyOverrideAndRefresh` 中走 `onTrialDisplayNodeChanged(anchor)` 是 no-op（同节点 path 为空）。或者更简单：

**推荐方案**：不把 hook 搬进 `applyOverrideAndRefresh`，而在每个调用点之后**显式**追加一行 controller 调用，按语义区分：

| 调用点 | controller 调用 |
|---|---|
| `enterTrial`：`applyOverrideAndRefresh(anchor)` 之后 | `c.onTrialEnter(anchor)` |
| `exitTrial` / `forceExitTrial`：`applyOverrideAndRefresh(null)` 之后 | `c.onTrialExit(mainlineTailSupplier.get())` |
| `trialNavigate` / `trialNavigateForward` / `trialReset`：`applyOverrideAndRefresh(s.displayNode)` 之后 | `c.onTrialDisplayNodeChanged(s.displayNode)` |
| `doApplyMove` 同位置子节点复用分支：`applyOverrideAndRefresh(existing)` 之后 | `c.onTrialDisplayNodeChanged(existing)` |
| `doApplyMove` 新建子节点分支：`applyOverrideAndRefresh(child)` 之后 | `c.onTrialDisplayNodeChanged(child)` |

每处统一用：

```java
EngineFollowController c = engineController;
if (c != null) c.onTrialDisplayNodeChanged(<node>);  // 或 onTrialEnter / onTrialExit
```

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
  mgr.setDesktopRefresherForTest(() -> {}); // 不触发真实 EDT
  mgr.setDesktopPlayingProbe(() -> true);
  BoardHistoryNode anchor = /* 构 anchor */;
  assertFalse(mgr.enterTrial("client-1", anchor));
}

@Test
public void applyTrialMove_invokesControllerHook() {
  RecordingEngineCommandSink sink = new RecordingEngineCommandSink();
  EngineFollowController c = new EngineFollowController(sink);
  WebBoardManager mgr = /* fixture */;
  mgr.setDesktopRefresherForTest(() -> {});
  mgr.setEngineFollowController(c);
  /* enterTrial + applyTrialMove */
  c.awaitIdle();
  // 断言 sink.calls 含 "play X Y" + "clearBestMoves"
}

@Test
public void idleTimeout_invokesControllerExitHook() throws Exception {
  // 直接在 fixture 里把 idle timeout 缩到 0 / 提供可触发钩子（sleep 测试不可取，会 flake）
  // 推荐做法：在 WebBoardManager 加 package-private `triggerIdleTimeoutForTest(TrialSession s)`
  // 或把 idleTimeout 回调抽成 Runnable 让测试直接调
  // fixture 同样要 mgr.setDesktopRefresherForTest(() -> {});
  // 断言：sink.calls 包含 onTrialExit 对应的命令序列（试下深度 1 时为 1 次 undo + clearBestMoves）
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

- [ ] **Step 2：把 `Board.java` 中 mainline 同步路径的 `Lizzie.leelaz.playMove(...)` 调用替换为 `feedEngineForMainlineMove(...)`**

**callsite 分类查表（需替换 = 走 mainline 推进路径）：**

| 行号 | 上下文 | 分类 | 处理 |
|---|---|---|---|
| 985  | `placeForSync` 黑棋分支 | mainline 同步 | **替换** |
| 1032 | `placeForSync` 白棋分支 | mainline 同步 | **替换** |
| 1584 | pass 路径，前置 `!EngineManager.isEngineGame` | 用户/SGF pass | **替换**（pass 也属 mainline 推进，试下中也要短路） |
| 1619 | pass 路径 | 用户 pass | **替换** |
| 1728 | 用户落子主路径 | 用户落子 | **不动**（前置 spec 决策 8 入口 guard 已拒绝试下中的用户落子） |
| 1732 | 注释 | — | 不动 |
| 1758 | 用户落子（另一重载） | 用户落子 | **不动** |
| 1761 | 用户落子（另一重载） | 用户落子 | **不动** |
| 1889 | `playMovePonder`，不是 `playMove` | — | 不动 |
| 1895 | 用户落子带 ponder 标志 | 用户落子 | **不动** |
| 1900 | 用户落子带 ponder 标志 | 用户落子 | **不动** |
| 2020 | `addStone`/复盘添加 | mainline 同步 | **替换** |
| 2063 | `addStone`/复盘添加 | mainline 同步 | **替换** |
| 2072 | 注释 | — | 不动 |
| 2438 | SGF 加载 / restore 路径 | SGF 加载 | **不动**（SGF 加载与试下不交互） |
| 2440 | SGF 加载 pass | SGF 加载 | **不动** |
| 2626 | restore / undo 重放 | mainline 同步 | **替换** |
| 2628 | restore pass 重放 | mainline 同步 | **替换** |

⚠️ 表中"用户落子"分类的 4 处不动是基于"前置 spec 决策 8 在 `Board.place` 入口 guard 早返"的假设。实施时**先打开 1700-1900 行段，确认入口处确有 `if (LizzieFrame.displayNodeOverride != null)` 早返**；如果没有，必须先按前置 spec 决策 8 加 guard 再决定是否替换。

- [ ] **Step 3：在 `ReadBoard.java` 同步落点之后追加 `controller.onMainlineAdvance(...)`**

**ReadBoard 推 mainline 用的是 `Lizzie.board.placeForSync(...)`，而非 `Board.place`**。grep 已确认 4 处：

| 行号 | 上下文 |
|---|---|
| 1034 | 黑棋同步落子 |
| 1054 | 白棋同步落子 |
| 1094 | 最后一手同步 |
| 1994 | move 序列同步落点 |

每处之后插入：

```java
EngineFollowController c = Lizzie.engineFollowController;
if (c != null) c.onMainlineAdvance(Lizzie.board.getHistory().getCurrentHistoryNode());
```

⚠️ `placeForSync` 内部最终调 `Board.place` → 命中 Step 2 表的 985 / 1032 / 2020 / 2063 路径（已替换为 `feedEngineForMainlineMove`），所以 GTP 短路在 Board 层面完成；ReadBoard 这一步只通知 controller 更新内部 `currentEngineNode` 指针（试下中跳过、非试下时发 playMove）。两层互不重复。

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

**已 read line 70-130 确认现状：**

- **line 76**（`doBroadcastAnalysis`）：`if (... isAnalysisHiddenForTrial()) return;` —— 整段抑制 `analysis_update`。**整行删除**，恢复广播（候选点对得上 displayNode 之后就该发）
- **line 107**（`doBroadcastFullState`）：`boolean trialActive = ...` 决定 `bestMoves / wr / sm / playouts` 是否置空。原意是"试下中 anchor 节点的 mainline 分析对不上 displayNode 不广播"。引擎跟随后 displayNode 节点本身就持有自己的引擎结果（由 collector 写入），**直接删 line 107 + 把 `trialActive ? null : data.bestMoves` 改回 `data.bestMoves`、`trialActive ? 0 : wr` 改回 `wr`**，等等。winrate_history 字段在 build 调用的另一处单独算（spec 决策 5：仍仅 anchor 之前 mainline）——若在 `buildFullStateJson` 的同一调用段内有 `winrate_history` 参数，需要 read 上下文确认它是单独算的。**实施步骤**：
  1. read line 100-160 看清 `buildFullStateJson` 的全部参数
  2. 仅把 `bestMoves / wr / sm / playouts` 4 个三元改回直读 `data.*`
  3. `winrate_history` 参数如果通过 `trialActive` 影响过，**保留**对 winrate_history 的"仅 anchor 之前 mainline"逻辑
  4. 删除 `boolean trialActive` 局部变量定义

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
- [ ] 试下退出后引擎日志中无 dummy 坐标的 playMove（即引擎不把 dummy 占位当成一手）

- [ ] **Step 3：如有 regression 立刻回到对应 Task 排查**

---

## 完成后

- [ ] 写一行 release note 草稿到 PR 描述
- [ ] 更新前置 spec `2026-04-30-web-trial-mode-design.md`：决策 2 / 决策 6 / 「不在范围内」按本 spec「与现有契约的关系」段所列改写（这一步可以本任务最后一个 commit 顺手做）

```bash
git commit -m "docs: 同步前置试下 spec 的引擎/对弈冲突决策"
```
