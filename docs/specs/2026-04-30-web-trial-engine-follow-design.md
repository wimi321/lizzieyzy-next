# Web 试下模式 — 引擎跟随分析（设计）

**日期**：2026-04-30
**前置文档**：[`2026-04-30-web-trial-mode-design.md`](2026-04-30-web-trial-mode-design.md)（试下骨架）

## 背景

Web 试下骨架已实现：用户在浏览器试下时，桌面端通过 `displayNodeOverride` 跟随显示，KataGo 引擎仍在分析真实 mainline 末端 `currentNode`。因此试下走出 anchor 后，桌面端候选点 / 胜率覆盖层 / 形势热力图被屏蔽（在错盘面上画候选点会误导用户）；Web 端同样不再广播 `analysis_update`。

本设计让引擎在试下期间**跟随 displayNode 实时分析**，恢复试下中候选点和胜率/目差数字显示。

## 设计决策摘要

| 编号 | 决策 |
|---|---|
| 1 | 试下期间 mainline 棋盘画面正常更新（ReadBoard 仍推 BoardHistoryList），但发往引擎的 `playMove` 调用排队延迟，待退出试下后由 controller 一次性补发 |
| 2 | 试下进入时若桌面端处于人机对弈状态（`isPlayingAgainstLeelaz \|\| isAnaPlayingAgainstLeelaz`）则拒绝，回 Web "桌面端正在对弈，无法进入试下"提示 |
| 3 | 引擎切换走"算 LCA → undo 到 LCA → play 到目标"的增量路径；任意切换异常降级到 `clear()` + 从根全量重 play 兜底 |
| 4 | 不维护具体队列结构；controller 仅持有 `currentEngineNode` 指针，退出时按 BoardHistoryList 当前 mainline 末端补发 |
| 5 | 胜率曲线仍仅画 anchor 之前 mainline；当前节点胜率/目差数字与候选点跟随 displayNode 实时显示 |
| 6 | 所有引擎切换（含退出）异步发 GTP；视图层 `displayNodeOverride` 立即清除，候选点慢半拍刷新 |
| 7 | controller 通过 `EngineCommandSink` 接口注入；单元测试用 fake sink 断言 GTP 序列 |
| 8 | Tracking analysis 用独立引擎实例（见 `TRACKING_ANALYSIS_CONTRACT.md`），与本设计无冲突，不在范围内 |

## 系统架构

### 新增组件

#### `EngineCommandSink`（接口）

```
playMove(Stone color, String coord)
undo()
clear()
clearBestMoves()
```

生产实现 `LeelazEngineCommandSink`：薄 wrapper，方法体内直接调 `Lizzie.leelaz.playMove(...)` / `undo(...)` / `clear()` / `clearBestMoves()`。

测试实现 `RecordingEngineCommandSink`：把每次调用追加到 `List<String>`（如 `"play B Q16"` / `"undo"`），断言 GTP 序列。可选 `failNextN` 字段触发异常注入测兜底。

#### `EngineFollowController`

**字段**：
- `EngineCommandSink sink`（构造注入）
- `volatile BoardHistoryNode currentEngineNode`：引擎当前对齐到的节点
- `volatile boolean trialActive`
- 单线程 `Executor`：串行化所有切换请求，避免并发发 GTP 命令交错

**入口方法**（均异步派发到 executor）：
- `onTrialEnter(BoardHistoryNode anchor)`：仅记录 `trialActive = true`，无 GTP（anchor 即真 currentNode，引擎已对齐）
- `onTrialDisplayNodeChanged(BoardHistoryNode newNode)`：算 path(currentEngineNode → newNode)，按 LCA 发 undo/play，最后 `clearBestMoves()`，更新 `currentEngineNode`
- `onTrialExit(BoardHistoryNode mainlineTail)`：`trialActive = false`，path(currentEngineNode → mainlineTail) → undo/play 切回 mainline，`clearBestMoves()`
- `onMainlineAdvance(BoardHistoryNode newTail)`：试下激活时不发 GTP（退出时由 `onTrialExit` 一次性补）；非试下时直接 `sink.playMove(...)` 走原路
- `forceResync(BoardHistoryNode target)`：`sink.clear()` → 从根沿 mainline + 试下分支 play 到 target → `clearBestMoves()`，更新 `currentEngineNode`。所有切换异常的兜底入口

**路径计算 `pathBetween(from, to)`**：
- 沿 `previous` 指针上溯求 LCA
- 返回 `(undoCount, playSequence)`：从 `from` undo 到 LCA，再沿 `to` 的祖先链 play 到 `to`
- 实现注意：BoardHistoryNode 的兄弟分叉信息从父节点 `variations` 里读，沿子节点链 play 时按节点的 move 信息发 GTP

### 修改组件

| 组件 | 改动 |
|---|---|
| `WebBoardManager.enterTrial` | 进入前判断 `Lizzie.leelaz.isPlayingAgainstLeelaz()` / `isAnaPlayingAgainstLeelaz()`，命中其一则拒绝并单播错误（新错误码 `engine_busy`）；否则照旧并调 `controller.onTrialEnter(anchor)` |
| `WebBoardManager.applyTrialMove` / `trialNavigate` / `trialNavigateForward` / `trialReset` | 在 `overrideSink.set(node)` 之后追加 `controller.onTrialDisplayNodeChanged(node)` |
| `WebBoardManager.exitTrial` / `forceExitTrial` / idle timeout | 清 displayNodeOverride 后调 `controller.onTrialExit(Lizzie.board.history.currentHistoryNode)` |
| `ReadBoard`（mainline 同步落点） | 调 `Board.place(...)` 之后调 `controller.onMainlineAdvance(newTail)`；引擎调用从直接 `Lizzie.leelaz.playMove` 改为通过 controller |
| `LizzieFrame.isAnalysisHiddenForTrial()` | 删除 |
| `BoardRenderer` | 删除 7 处 `isAnalysisHiddenForTrial()` 早返 |
| `WebBoardDataCollector` | 删除 2 处广播抑制；试下中 `analysis_update` 正常发送；当前节点胜率/目差数字字段读 displayNode 引擎结果；`winrate_history` 保持现状仅画 anchor 之前 mainline |
| `Lizzie`（启动入口） | 构造 `LeelazEngineCommandSink` 与 `EngineFollowController`，注入 `WebBoardManager` 与 `ReadBoard` |

## 数据流

```
[Trial 落子/导航]
  WebBoardManager 改 displayNode
    → overrideSink.set(node)                          // 视图层立即生效
    → controller.onTrialDisplayNodeChanged(node)      // 引擎层异步切换
        → executor 提交任务:
            path = pathBetween(currentEngineNode, node)
            sink.undo() × path.undoCount
            for move in path.playSequence: sink.playMove(...)
            sink.clearBestMoves()
            currentEngineNode = node

[ReadBoard 推 mainline 新手]
  Board.place(mainline 新手)            // BoardHistoryList 已更新
    → controller.onMainlineAdvance(newTail)
        if trialActive:
            // 不发 GTP，等 onTrialExit 时统一补
            return
        else:
            sink.playMove(newMove)
            currentEngineNode = newTail

[退出试下（按钮 / 超时 / 强制）]
  WebBoardManager.exitTrial
    → overrideSink.set(null)            // 视图立即切回 mainline
    → controller.onTrialExit(realCurrentNode)
        → executor 提交任务:
            trialActive = false
            path = pathBetween(currentEngineNode, realCurrentNode)
            sink.undo() × path.undoCount
            for move in path.playSequence: sink.playMove(...)
            sink.clearBestMoves()
            currentEngineNode = realCurrentNode
```

## 错误处理

- sink 调用抛异常 → controller 捕获 → 调 `forceResync(目标 node)`：`sink.clear()` + 从根重 play。`forceResync` 自身再失败则记 ERROR 日志，下次切换重试
- 切换执行中又收到新 displayNode 变更：executor 串行排队，新任务在旧任务完成后再跑；旧任务发出的命令照常生效，不取消（取消语义在 GTP 上不可靠）
- 桌面端在试下中切换到对弈模式（`isPlayingAgainstLeelaz` 由 false 变 true）：触发 `WebBoardManager.forceExitTrial`，已有路径覆盖
- ReadBoard 在 controller 还没构造完时就来推手：`controller` 引用为 `null` 时 ReadBoard 走旧路径直接调 `Lizzie.leelaz.playMove`（启动期兼容）

## 测试

### `EngineFollowControllerTest`（fake sink）
- 进入试下、currentEngineNode == anchor：不发任何命令
- 落子 1 手：发 1 次 playMove
- 后退 1 手：发 1 次 undo
- 跳兄弟分叉（深度 1）：发 1 次 undo + 1 次 playMove
- 回锚点（试下走深 5 手）：发 5 次 undo
- 退出 + 试下期间 mainline 推进 2 手：发 N 次 undo 回 anchor + 沿 mainline play 2 手
- sink 抛异常 → forceResync 触发 → 断言 `clear` + 全量重 play 命令序列
- mainline 推进期间试下激活：不发 GTP；退出时按当前 BoardHistoryList tail 补发

### `WebBoardManagerTest`（recording sink + recording overrideSink）
- 现有 trial 测试加入 controller 调用序列断言
- `enterTrial` 在 `isPlayingAgainstLeelaz()` 为 true 时返回错误，单播 `trial_state` rejected reason `engine_busy`

## 不在范围内

- 试下分支节点的胜率曲线绘制（决策 5：曲线保持现状）
- Tracking analysis 与试下的交互（决策 8：独立引擎，无冲突）
- 多人协同试下、抢占式接管（沿用前置 spec 决策 1 / 6）
- 修改 `SNAPSHOT_NODE_KIND` / `TRACKING_ANALYSIS_CONTRACT` 契约

## 与现有契约的关系

### `SNAPSHOT_NODE_KIND.md`
试下子树仍作为 anchor 下 variation，本设计不改 BoardHistoryNode 结构。

### `TRACKING_ANALYSIS_CONTRACT.md`
Tracking analysis 用独立引擎实例，本 controller 仅协调主引擎，与 tracking 无交互。

### `2026-04-30-web-trial-mode-design.md`
**变更**：
- 决策 2 改为「引擎在试下期间跟随 displayNode 实时分析；mainline 棋盘画面照常更新，引擎对 mainline 的同步在退出时一次性补发」
- 决策 6（先到先得）补充「桌面端处于对弈状态时拒绝试下进入」
- 「不在范围内」移除"试下期间引擎对 displayNode 做 ondemand 分析"
- 「不在范围内」保留"试下分支独立胜率曲线"
- 渲染层"试下期间隐藏候选点/胜率覆盖层"段落改为"试下期间正常显示候选点和当前节点胜率/目差数字；胜率曲线仍仅含 anchor 之前 mainline"
