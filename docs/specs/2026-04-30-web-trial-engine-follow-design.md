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
| 4 | 不维护具体队列结构；controller 仅持有 `currentEngineNode` 指针，退出时取 `Lizzie.board.history.currentHistoryNode` 作为 mainline 末端补发目标（试下不改 `currentHistoryNode`，故试下期间该字段恒等于 mainline tail） |
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
- `volatile BoardHistoryNode currentEngineNode`：引擎当前对齐到的节点。**试下激活期间 mainline 推进不更新此字段**——引擎仍停在 displayNode 路径上，待 `onTrialExit` 现取 `mainlineTail` 作为目标，所以 controller 不需要单独维护"待补发 tail"
- `volatile boolean trialActive`
- 单线程 `Executor`：串行化所有切换请求，避免并发发 GTP 命令交错

**入口方法**（均异步派发到 executor）：
- `onTrialEnter(BoardHistoryNode anchor)`：记录 `trialActive = true`；若 `currentEngineNode != anchor`（理论上不应发生，但留兜底——比如 controller 启动期错过过 mainline 推进），调 `forceResync(anchor)` 把引擎对齐到 anchor 再开始
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
| `WebBoardManager.applyTrialMove` / `trialNavigate("back"\|"forward")` / `trialNavigateForward(childIndex)` / `trialReset` | 在 `overrideSink.set(node)` 之后追加 `controller.onTrialDisplayNodeChanged(node)`。所有 sibling / 主线子 / 多步回退都走同一 hook，由 controller 内部根据节点关系算路径 |
| `WebBoardManager.exitTrial` / `forceExitTrial` | 清 displayNodeOverride 后调 `controller.onTrialExit(Lizzie.board.history.currentHistoryNode)` |
| `WebBoardManager.TrialSession` idle timer | idle timeout 回调统一走 `forceExitTrial` 路径，不另开 hook（与桌面端"强制结束"语义一致） |
| `ReadBoard`（mainline 同步落点） | **拦截点放在 ReadBoard**：现有"读到新手 → 调 `Lizzie.board.place(...)`"路径保持不变（`Board.place` 不动，因为它也是用户落子的入口，不能误拦）；ReadBoard 在 `place` 调用之后追加 `controller.onMainlineAdvance(newTail)`，由 controller 决定本次是否真的发 GTP。`Board.place` 内部对 `Lizzie.leelaz.playMove` 的调用按试下激活态短路（详见下文「Board.place 引擎调用的拦截」） |
| `LizzieFrame.isAnalysisHiddenForTrial()` | 删除 |
| `BoardRenderer` | 删除 7 处 `isAnalysisHiddenForTrial()` 早返 |
| `WebBoardDataCollector` | 删除 2 处广播抑制；试下中 `analysis_update` 正常发送；当前节点胜率/目差数字字段读 displayNode 引擎结果；`winrate_history` 保持现状仅画 anchor 之前 mainline |
| `Lizzie`（启动入口） | 构造 `LeelazEngineCommandSink` 与 `EngineFollowController`，注入 `WebBoardManager` 与 `ReadBoard` |

### `Board.place` 引擎调用的拦截

`Board.place(...)` 内部多处调用 `Lizzie.leelaz.playMove(...)`。试下期间需要让 mainline 推进**只更 BoardHistoryList、不直接发 GTP**（GTP 由 controller 在试下退出时统一补发）。

**做法**：
- `Board.place` 内对 `Lizzie.leelaz.playMove(...)` 的调用集中走一个新增的私有方法 `feedEngineForMainlineMove(color, coord)`
- 该方法判断：若 `EngineFollowController.isTrialActive()` 为真，则**不发 GTP**，直接返回；否则照旧调 `Lizzie.leelaz.playMove(...)`
- 用户落子（试下中桌面端落子已被前置 spec 决策 8 拒绝）和 SGF 加载路径不受影响（前者被 guard，后者不走 `Board.place` 的 mainline 同步分支）
- 严格保持 `Board.place` 对 `BoardHistoryList` 的副作用与现状一致——只改"是否发 GTP"这一点
- **调用面收敛**：试下中桌面端用户落子已在前置 spec 决策 8（`Board.place` 用户路径入口 guard，toast + return）层面被拒绝，不会进入 `feedEngineForMainlineMove`；SGF 加载 / 引擎切换走 `loadsgf` / 不经 `Board.place`；因此试下激活期间 `feedEngineForMainlineMove` 的调用方实际上只剩 ReadBoard 同步路径。实现时若发现尚未覆盖到的 `place` 入口能在试下中触发，必须先按前置 guard 模式拦截，再考虑放行 controller

### `pathBetween` 与 SNAPSHOT 节点的处理

`SNAPSHOT_NODE_KIND.md` 契约：SNAPSHOT 节点不参与 GTP movelist；engine 重 sync 必须先恢复到最近 SNAPSHOT 锚点的 setup 状态再续 MOVE/PASS。

本设计的策略：
- **`pathBetween` 在 LCA 路径中遇到 SNAPSHOT 节点 → 直接放弃增量路径，触发 `forceResync(target)`**；不尝试跨 SNAPSHOT 做 undo/play
- **`forceResync` 实现**：直接复用现有 `Lizzie.leelaz.loadsgf(...)` 等价的"按当前 BoardHistoryList 重 sync 引擎"路径（`Leelaz` 已有该路径用于桌面端 SGF 加载 / fix-sync）。controller 不自己解析 SNAPSHOT setup 元数据，委托给 Leelaz 现有 sync 逻辑
- 试下子树本身不含 SNAPSHOT（前置 spec 决策 9：试下不创 SNAPSHOT/MOVE/PASS 主链节点，仅作 anchor 下 variation；anchor 之后到 displayNode 全是普通 MOVE/PASS）
- 因此**仅当 anchor 之上 mainline 路径含 SNAPSHOT 时**才触发 forceResync 兜底；常规情况（无中盘 SNAPSHOT）走增量路径

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
- 跨深度跳分叉（LCA 在 anchor 上方 2 层）：发对应 undo 数 + 沿目标祖先链 play 数
- 回锚点（试下走深 5 手）：发 5 次 undo
- 退出 + 试下期间 mainline 推进 2 手：发 N 次 undo 回 anchor + 沿 mainline play 2 手
- sink 抛异常 → forceResync 触发 → 断言 `clear` + 全量重 play 命令序列
- mainline 推进期间试下激活：不发 GTP；退出时按当前 BoardHistoryList tail 补发
- `pathBetween` 路径中含 SNAPSHOT 节点：触发 forceResync（fake sink 断言改用 loadsgf 路径或对应 sync 序列）

### `WebBoardManagerTest`（recording sink + recording overrideSink）
- 现有 trial 测试加入 controller 调用序列断言
- `enterTrial` 在 `isPlayingAgainstLeelaz()` 为 true 时返回错误，单播 `trial_state` rejected reason `engine_busy`

## 不在范围内

- 试下分支节点的胜率曲线绘制（决策 5：曲线保持现状）
- Tracking analysis 与试下的交互（决策 8：独立引擎，无冲突）
- 多人协同试下、抢占式接管（沿用前置 spec 决策 1 / 6）
- 修改 `SNAPSHOT_NODE_KIND` / `TRACKING_ANALYSIS_CONTRACT` 契约

## 实现注记：trial 期间 kata-analyze 视角

`Leelaz.maybeAddPlayer()` 决定 `kata-analyze X` 的玩家颜色，原实现读
`Lizzie.board.getHistory().isBlacksTurn()`。trial 激活时 mainline `currentHistoryNode`
始终停在 anchor，与实际要分析的 displayNode 轮次不一致 → KataGo 用错视角输出 winrate →
画面表现"双方都觉得自己稳赢"（典型如黑下完 Q16 后白方应招，KataGo 报告 winrate=98%）。

修复：trial 激活时改读 `Lizzie.frame.getDisplayNode().getData().blackToPlay`，让
kata-analyze 跟 displayNode 轮次对齐。非 trial 路径（含让子棋等正常分析）走原代码。

## 实现注记：snapshot SGF 的 KM 字段

`SnapshotEngineRestore.buildSnapshotSgf` 之前不写 `KM[]`。KataGo `loadsgf` 加载没有 KM 字段的
SGF 后，会按 SGF 默认值重置进程内 komi（与 lizzie 启动后 GTP `komi X` 设置过的进程内 komi
脱钩）。修复：写入 `KM[komi]`，值取自 `Lizzie.board.getHistory().getGameInfo().getKomi()`。

## 诊断日志

代码里保留一组诊断日志，默认关闭。启动加 `-Dlizzie.trial.diag=true` 打开：
- `[trial-apply]`：用户落子坐标 + 落子前 displayNode 的引擎首选（`WebBoardManager.doApplyMove`）
- `[trial-resync]` / `[trial-replay]`：sync 重 play 序列（`LeelazEngineCommandSink`）
- `[trial-sgf]`：snapshot 路径生成的 SGF 内容（`SnapshotEngineRestore.writeSnapshotSgf`）
- `[trial-kata-info]` / `[mainline-kata-info]`：KataGo info 写入哪个 displayNode（`Leelaz.parseInfoKatago`）
- `[trial-raw-info]`：KataGo 原始 info 行前缀（`Leelaz.parseLine`）
- `[katago-cmd]` / `[katago-stderr]`：发给/来自 KataGo 的所有命令与 stderr（`Leelaz.sendCommand` / `Leelaz.readError`）

## 与现有契约的关系

### `SNAPSHOT_NODE_KIND.md`
试下子树仍作为 anchor 下 variation，本设计不改 BoardHistoryNode 结构。

### `TRACKING_ANALYSIS_CONTRACT.md`
Tracking analysis 用独立引擎实例，本 controller 仅协调主引擎，与 tracking 无交互。

### `2026-04-30-web-trial-mode-design.md`
**变更**：
- 决策 2 改为「引擎在试下期间跟随 displayNode 实时分析；mainline 棋盘画面照常更新，引擎对 mainline 的同步在退出时一次性补发」
- 决策 6（先到先得）补充「桌面端处于对弈状态时拒绝试下进入」
- 协议小节：`trial_state` 拒绝消息 `reason` 字段新增枚举值 `engine_busy`
- 「不在范围内」移除"试下期间引擎对 displayNode 做 ondemand 分析"
- 「不在范围内」保留"试下分支独立胜率曲线"
- 渲染层"试下期间隐藏候选点/胜率覆盖层"段落改为"试下期间正常显示候选点和当前节点胜率/目差数字；胜率曲线仍仅含 anchor 之前 mainline"
