# Web 端试下模式设计文档

## 概述

为 LizzieYzy Next 的 Web 旁观功能（见 [`2026-04-22-web-board-viewer-design.md`](2026-04-22-web-board-viewer-design.md)）增加**试下模式**：单个 Web 用户可在浏览器中暂时接管棋盘，自由摆放变化、来回浏览试下分支；桌面端 lizzieyzy-next 在此期间跟随显示但**不切换主线分支**，KataGo 引擎不重 load。退出试下后桌面端回到原工作流，试下子树作为 variation 保留在锚点节点下。

**核心约束**：试下期间不改动现有「ReadBoard 同步 → mainline 推进」管线，桌面端的同步管线照常运行；试下仅在渲染层用一个 `displayNodeOverride` 把视图切到独立子树。

## 设计决策摘要

| 编号 | 决策 |
|---|---|
| 1 | 单人独占试下；其他观看者跟随显示，不能操作 |
| 2 | 共享 displayNode 模型，不动 mainline 同步管线，引擎不重 load |
| 3 | 严格交替黑白（围棋默认），不提供颜色切换 |
| 4 | 导航：← 后退 / → 前进 / ↩ 回锚点 / 棋盘点击落子；分叉处 → 走主线子，**其它子节点位置在棋盘上显示标记**（F3） |
| 5 | 双向退出（Web 按钮 / 桌面端「强制结束」），5 分钟空闲自动退出；试下子树作为 variation 保留 |
| 6 | 先到先得；他人请求进入试下时拒绝（不抢占） |
| 7 | WebSocket 协议在现有基础上扩展（见下文） |
| 8 | 桌面端跟随 displayNode 显示，禁止落子，菜单提供「强制结束试下」 |
| 9 | 不修改 `SNAPSHOT_NODE_KIND.md` / `TRACKING_ANALYSIS_CONTRACT.md` 等现有契约 |

## 架构

### 渲染层：displayNodeOverride

新增 `LizzieFrame.displayNodeOverride`（`volatile BoardHistoryNode`，默认 `null`）。

- `null`：所有渲染读 `Board.history.getCurrentHistoryNode()`，行为完全等同于现状
- 非 `null`：渲染端改读 override，**同步管线和引擎仍以真实 `currentNode` 为准**

**改动点（仅渲染/数据采集）：**

| 位置 | 改动 |
|---|---|
| `BoardRenderer` 取节点处 | 新增 `LizzieFrame.getDisplayNode()`，渲染棋子/标记/胜率全部走它 |
| `WebBoardDataCollector.onBoardStateChanged()` | 收集状态时使用 `getDisplayNode()` 而非 `currentNode` |
| `WebBoardDataCollector` 胜率曲线 | 试下期间 `winrate_history` 沿 displayNode 到根的 mainline 序列计算；试下子树本身不计入主曲线（试下分支独立胜率曲线见「不在范围内」一节） |

**不动点：**

- `ReadBoard` / `BoardSyncEngine` 等同步路径**完全不动**，照常推 `currentNode` 和 `mainlineTail`
- `Leelaz` / KataGo 引擎不感知 displayNode override，引擎分析的目标仍由现有 mainline 同步逻辑决定
- `SNAPSHOT_NODE_KIND` 契约不变：试下子树挂在锚点 `currentNode` 下作为 variation，不会插入 SNAPSHOT/MOVE/PASS 主链

### 试下分支结构

进入试下时记录**锚点** `anchorNode = Board.history.getCurrentHistoryNode()`。

试下期间所有落子在 `anchorNode` 子树下创建 variation：

```
anchorNode (mainline)
   ├── mainline child (原有，若有)
   └── trial child #1   ← 试下期间 displayNode 在这棵子树里漫游
        ├── trial child #1.1
        └── trial child #1.2  (在分叉点落不同位置形成)
```

退出试下时：
- `displayNodeOverride = null`
- 试下子树**保留**为 variation，不删除
- 真实 `currentNode` 在试下期间可能已被同步管线推进到新位置——若 `currentNode != anchorNode`，渲染直接显示新的 `currentNode`，试下子树仍挂在原 `anchorNode` 下作为历史 variation

### 状态机

```
   [Idle]                                    ← override = null
     │
     │ Web client 发送 enter_trial（首个）
     ▼
  [TrialActive]                              ← override = anchor 下的 trial 节点
     │
     │ ┌── Web client 发送 exit_trial            ─┐
     │ ├── Web client 5 分钟无操作（服务端计时）  │  → [Idle]
     │ ├── Web client WebSocket 断开              │
     │ └── 桌面端菜单「强制结束试下」          ─┘
     ▼
   [Idle]
```

二次 `enter_trial`（来自其它客户端）在 `TrialActive` 期间**被服务端拒绝**，回包 `trial_denied`。

### 新增 / 修改 Java 类

| 类 | 改动 |
|---|---|
| `LizzieFrame` | 新增 `displayNodeOverride` 字段、`getDisplayNode()`、`setDisplayNodeOverride()`；菜单加「强制结束试下」项（试下中可见） |
| `BoardRenderer` | 渲染棋子/标记的取节点点改为 `LizzieFrame.getDisplayNode()` |
| `WebBoardDataCollector` | 状态序列化用 `getDisplayNode()`；新增 `enterTrial(clientId, anchor)` / `exitTrial()` / `applyTrialMove(x,y)` / `trialNavigate(direction)` / `trialResetToAnchor()` |
| `WebBoardServer` | `onMessage` 当前是空实现（`WebBoardServer.java:35`），本次**首次落地上行通道**：按 type dispatch 到 `WebBoardManager`；新增 `trial_state` 广播、`trial_denied` 单播 |
| `WebBoardManager` | 新增 `TrialSession` 内部类，持有 `clientId` / `anchorNode` / 最后操作时间戳 / 空闲超时 timer；负责单线程串行化所有试下操作 |

### 并发模型

试下相关操作全部 marshal 到 `WebBoardDataCollector` 现有的单线程 executor 上执行（与广播同一线程）：

- 上行消息（`onMessage` 内）解析后 `executor.submit(...)`
- 落子在 executor 线程上构造 `BoardHistoryNode`，挂到 `anchorNode` 子树
- 修改 `displayNodeOverride` 也在 executor 线程，避免渲染线程读到半成品状态
- 5 分钟空闲超时由 `ScheduledExecutorService.schedule` 驱动，回调亦提交到同一 executor

桌面端 EDT 渲染读 `displayNodeOverride` 时**不加锁**——`volatile` 即可保证可见性，渲染最坏情况是看到一帧旧节点，下一帧立即修正。

## WebSocket 协议扩展

在 [`2026-04-22` 设计](2026-04-22-web-board-viewer-design.md) 已有的下行 `full_state` / `analysis_update` / `winrate_history` 之外，新增双向消息。

### 上行（客户端 → 服务端）

#### `enter_trial` — 请求进入试下

```json
{ "type": "enter_trial", "clientId": "uuid-string" }
```

服务端处理：
- 当前 `Idle`：接受，广播 `trial_state { active: true, ownerClientId }`
- 当前 `TrialActive` 且 `ownerClientId == clientId`：幂等成功
- 当前 `TrialActive` 且不同 `clientId`：单播 `trial_denied { reason: "in_use" }`

#### `exit_trial` — 退出试下

```json
{ "type": "exit_trial" }
```

仅试下 owner 有效。服务端清空 override，广播 `trial_state { active: false }`。

#### `trial_move` — 在 displayNode 落一手

```json
{ "type": "trial_move", "x": 15, "y": 3 }
```

服务端在 executor 线程：
1. 校验：当前必须 `TrialActive` 且发起者是 owner
2. 颜色：根据 `displayNode` 的 `blackToPlay` 决定，**严格交替**，客户端不传 color
3. 在 `displayNode` 下查找已存在的同位置子节点：
   - 存在 → `displayNode = 该子节点`（复用，不创建）
   - 不存在 → 新建 `BoardHistoryNode`，挂到 `displayNode.variations` 末尾，`displayNode = 新节点`
4. 广播 `full_state`（带 `trial_state.displayPath`）和 `trial_state`

#### `trial_navigate` — 前进 / 后退

```json
{ "type": "trial_navigate", "direction": "forward" | "back" }
```

服务端处理（注意 `BoardHistoryNode.previous` 返回 `Optional<BoardHistoryNode>`，`variations` 是 `ArrayList`）：
- `back`：若 `displayNode == anchorNode` 则忽略（不能后退到 anchor 之前，其父节点属于 mainline，不在试下范围）；否则 `displayNode = displayNode.previous().get()`（试下子树内部 `previous` 必非 empty，因为试下落子时已建立父子链）
- `forward`：若 `displayNode.variations.isEmpty()` 则忽略；否则取 `variations.get(0)`（主线子）

#### `trial_reset` — 回锚点

```json
{ "type": "trial_reset" }
```

`displayNode = anchorNode`。试下子树保留。

### 下行（服务端 → 客户端）

#### `trial_state` — 试下状态广播

每次状态变化时向所有客户端广播：

```json
{
  "type": "trial_state",
  "active": true,
  "ownerClientId": "uuid-string",
  "anchorMoveNumber": 42,
  "displayMoveNumber": 45,
  "canBack": true,
  "canForward": false,
  "siblingMarkers": [
    { "x": 3, "y": 3, "label": "1", "childIndex": 1 },
    { "x": 16, "y": 16, "label": "2", "childIndex": 2 }
  ]
}
```

字段说明：
- `ownerClientId`：当前 owner，非 owner 客户端据此把 UI 切到「跟随显示」
- `anchorMoveNumber` / `displayMoveNumber`：UI 显示「试下中：从第 42 手起，已下到第 45 手」
- `canBack` / `canForward`：导航按钮 enable 状态（false 时灰显）
- `siblingMarkers`：当 `displayNode.variations.size() > 1` 时，列出**除主线子（`variations.get(0)`）以外**的所有子节点（决策 4 / F3）。客户端在棋盘上以半透明数字标记显示；点击标记发送 `trial_navigate forward` 并带 `childIndex` 跳到该子节点。
  - `childIndex`：对应 `variations` 列表的真实下标（主线子是 0，因此 `siblingMarkers` 里的 `childIndex` 都 ≥ 1）
  - `label`：按 `childIndex` 升序从 `"1"` 起编号显示（与 `childIndex` **不一定相同**——label 仅供 UI 显示，childIndex 才是协议字段）

#### `trial_denied` — 拒绝进入

```json
{ "type": "trial_denied", "reason": "in_use", "ownerClientId": "uuid-string" }
```

仅向请求方单播。

### 分叉点交互（F3）

`siblingMarkers` 仅列出**非主线子**。客户端点击某个标记时，发送：

```json
{ "type": "trial_navigate", "direction": "forward", "childIndex": 2 }
```

服务端：`displayNode = displayNode.variations.get(childIndex)`。

主线子（`variations.get(0)`）由不带 `childIndex` 的 `forward` 命令前进，与基本导航行为一致。

### 协议节流

- `trial_state` 广播与 `full_state` 同一节流策略（10 次/秒）
- 客户端短时间内连续点击导航键时，服务端按到达顺序串行处理，不丢弃

## 桌面端 UI 行为

### 跟随显示

- `LizzieFrame.refresh()` 渲染读 `getDisplayNode()`，所以试下期间桌面端棋盘自然显示 Web 端的当前 displayNode
- 状态栏显示 `Web 试下中（剩余 N:NN 自动退出）`，倒计时由 `WebBoardManager.TrialSession` 推送
- 候选点 / 胜率覆盖层：试下期间引擎仍在分析真实 `currentNode`，**桌面端候选点显示在 displayNode 上是误导**——试下期间桌面端**隐藏候选点和胜率覆盖层**，仅显示子树位置和最后一手标记

### 输入屏蔽

试下期间桌面端 `Board.place()` 等用户落子入口不被禁用——保留鼠标点击会触发：
- 弹一个非阻塞 toast：「Web 试下进行中，桌面端暂不响应落子。可在「同步」菜单选择强制结束。」
- 不修改 mainline，不传给引擎

> **注**：实际入口是 `Board.place(...)` 系列重载（`Board.java` 内多个签名，如 `place(int, int, Stone, boolean, ...)`）。计划阶段需在用户落子触发的 `place` 重载入口（非 SGF 加载、引擎同步路径）统一加 guard：`if (LizzieFrame.displayNodeOverride != null) { showToast(...); return; }`。SGF 加载、`ReadBoard` 同步等内部调用路径**不**经过此 guard（它们走不同的 `place` 重载或直接操作 `BoardHistoryList`）。

### 菜单：强制结束试下

`LizzieFrame` 菜单 → 「同步」 → 「Web 旁观」子菜单 → 新增 「强制结束试下」（试下中可见）。点击后调 `WebBoardManager.forceExitTrial()`，等同于 `exit_trial`，广播状态。

## 前端 UI 改动

### 试下控制条

非试下时显示「进入试下」按钮。点击后发送 `enter_trial`：
- 收到 `trial_state.active=true && ownerClientId == myClientId`：进入试下 UI
- 收到 `trial_denied`：toast「另一位用户正在试下，稍后再试」

试下 owner UI：

```
[← 后退]  [→ 前进]  [↩ 回锚点]  [✕ 退出试下]    剩余 4:32 自动退出
```

非 owner 客户端在试下中：状态栏显示「他人正在试下中（从第 42 手起）」，棋盘上不能点击落子。

### 棋盘点击行为

仅 owner 在 trial active 时：点击棋盘交叉点 → 发送 `trial_move { x, y }`。其它情况点击无效。

### 分叉标记渲染

收到 `trial_state.siblingMarkers` 时，在棋盘上对应位置叠加半透明数字标号（与 `lastMove` 标记不同色）。点击数字 → 发送 `trial_navigate forward childIndex`。

### 客户端 ID 持久化

`clientId` 用 `crypto.randomUUID()` 生成，存 `localStorage`，刷新页面后保持，便于刷新后仍能识别为同一 owner。

## 不在范围内

- Web 端导出试下 SGF（试下子树留在内存里的 variation，复用现有「保存 SGF」即可在桌面端导出）
- 多人协同试下（决策 1：单人独占）
- 试下期间引擎对 displayNode 做 ondemand 分析（决策 2：引擎不重 load）
- 颜色切换 / 自由放子 / 让先（决策 3：严格交替）
- 试下期间禁止 mainline 同步（决策 2：同步管线照常）
- 抢占式接管（决策 6：先到先得）
- 修改 `SNAPSHOT_NODE_KIND` 或 `TRACKING_ANALYSIS_CONTRACT` 契约（决策 9）
- 试下分支的独立胜率曲线 / 节点级胜率展示（试下期间前端胜率曲线仅显示 anchor 之前的 mainline，试下分支节点不绘制；后续版本可加）

## 与现有契约的对照

### `SNAPSHOT_NODE_KIND.md`

试下落子在 `anchorNode` 子树下创建 `MOVE` / `PASS` 节点，**不创建 SNAPSHOT**。锚点本身就是 mainline 上一个已存在的节点（`SNAPSHOT` 或 `MOVE`），其 kind 不变。

### `TRACKING_ANALYSIS_CONTRACT.md`

Tracking analysis 使用独立引擎实例（见原契约），不与主引擎竞争。试下不触发 tracking analysis、也不被 tracking analysis 影响。

### `2026-04-22-web-board-viewer-design.md`

本设计**扩展**而非替换：原 `full_state` / `analysis_update` / `winrate_history` 协议仍在；新增的 `trial_state` / `trial_denied` 是独立 type；`WebBoardDataCollector.onBoardStateChanged()` 改为读 `getDisplayNode()`，但当 `displayNodeOverride == null` 时行为完全等同。

## 测试要点

| 场景 | 预期 |
|---|---|
| 单 Web 用户进入试下，落几手，退出 | 桌面端跟随显示，退出后回到 mainline 末端，试下子树作为 variation 保留 |
| 试下期间桌面端 ReadBoard 同步推进 mainline | mainline 正常推进；退出试下后 displayNode 回到新的 `currentNode`，试下子树挂在原 anchor 下 |
| 第二个 Web 客户端在试下中尝试 `enter_trial` | 收到 `trial_denied`；第一位继续试下不受影响 |
| 试下 5 分钟无操作 | 服务端自动 `exit_trial`，所有客户端收到 `trial_state.active=false` |
| 试下中 owner WebSocket 断开 | 服务端检测到断开，自动 `exit_trial` |
| 在分叉点按 → | 走 `variations[0]`，棋盘上显示其它子节点的位置标记 |
| 试下中桌面端用户点击棋盘 | 弹 toast 提示，不落子，不影响 mainline |
| 桌面端「强制结束试下」 | 试下立即退出，Web owner 客户端收到状态广播切回旁观 UI |
