# 同步诊断面板设计

日期：2026-05-30

## 背景

ReadBoard / 弈客同步的问题经常表现为“用户能看到现象，但维护者缺少同一时刻的上下文”：

- 弈客白框出现了，但 Lizzie 棋盘没有推进。
- 切换房间后，白框或落子几何像是来自旧房间。
- 当前帧进入了 `HOLD`，但用户不知道它在等待什么。
- 当前帧进入了 `FORCE_REBUILD`，但用户不知道是回退、多手跳转、移除棋子，还是 marker 抖动导致无法证明历史。
- 棋盘重建成功，但分析没有恢复。

现有信息分散在 `ReadBoard`、`SyncRemoteContext`、`OnlineDialog`、`BrowserFrame`、`YikeSyncDebugLog` 和 readboard 协议流里。这个功能把这些信息整理成一个只读诊断层，帮助排查同步问题。

## 已核对的约束

本设计基于以下文档和代码入口：

- `docs/SNAPSHOT_NODE_KIND.md`
  - 同步主流程只允许 `NO_CHANGE`、`SINGLE_MOVE_RECOVERY`、`HOLD`、`FORCE_REBUILD` 四类结果。
  - `HOLD` 只用于短暂等待同一冲突快照再次出现，不用于拼造历史。
  - `FORCE_REBUILD` 固定为截断当前同步段并创建新的 `SNAPSHOT` 锚点。
  - 无引擎或引擎未启动时，`FORCE_REBUILD` 仍允许 board-only rebuild。
  - 同步链路不能从 marker、手数、盘面差异推导真实 `PASS`。
- `docs/specs/2026-05-08-yike-sync-session-state-design.md`
  - 弈客 `listenerEnabled`、页面态、`activeSession`、`pendingSession` 是不同语义。
  - `geometryReady` 可以先于 `syncReady` 成立。
  - 只有 `syncReady + geometryReady` 同时满足时，pending session 才允许切 active。
  - 离开棋盘页时，旧 geometry 必须立即失效，不能继续落子。
- `docs/2026-05-09-yike-sync-hotfix-and-debug-log-map.md`
  - 现有弈客 debug log 入口是 `featurecat.lizzie.util.YikeSyncDebugLog`，默认关闭。
  - 主要打点分布在 `ReadBoard`、`BrowserFrame`、`OnlineDialog`、`LizzieFrame`、`Leelaz`、`BoardRenderer`。
- 代码入口：
  - `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
    - `CompleteSnapshotRecoveryOutcome`
    - `CompleteSnapshotRecoveryDecision`
    - `syncBoardStones(boolean isSecondTime)`
    - `pendingRemoteContext`
    - `isSyncing`
    - `resumeState`
    - `lastResolvedSnapshotNode`
    - `awaitingFirstSyncFrame`
    - `syncAnalysisEpoch`
  - `src/main/java/featurecat/lizzie/gui/OnlineDialog.java`
    - `YikeSessionState`
    - `shouldPromotePendingSession(...)`
    - `shouldInvalidateYikePlacementGeometry(...)`
  - `src/main/java/featurecat/lizzie/util/YikeSyncDebugLog.java`

`docs/specs/2026-04-21-readboard-sync-boundaries-design.md` 在当前工作树中不存在；同步边界以 `docs/SNAPSHOT_NODE_KIND.md` 和现有 `docs/plans/2026-05-08-yike-sync-session-state.md` 为准。

## 目标

提供一个只读“当前同步上下文”面板和诊断摘要，回答以下问题：

1. 当前同步源是什么：generic / fox / yike。
2. readboard 当前是否连接、是否正在同步、最近一次协议输入何时到达。
3. 弈客当前 active / pending session 是什么，`syncReady` 和 `geometryReady` 分别是否成立。
4. 当前 geometry 属于哪个 session，是否允许用于落子。
5. 最近一次完整快照恢复决策是什么：`NO_CHANGE` / `SINGLE_MOVE_RECOVERY` / `HOLD` / `FORCE_REBUILD`。
6. 该决策的输入摘要和原因是什么。
7. 若发生 `FORCE_REBUILD`，这是 board-only rebuild，还是还涉及引擎 exact snapshot restore。
8. 最近一次重建后是否安排过分析恢复。

## 非目标

- 不修改 ReadBoard 同步行为。
- 不新增 readboard wire protocol token。
- 不改变 `SNAPSHOT` / `MOVE` / `PASS` 合约。
- 不从弈客 `yikeMoveNumber` 或 readboard 手数推导真实历史。
- 不把诊断字段纳入同步 gating。
- 不提供“强制重建”“清 session”“重启 readboard”等操作按钮。
- 不把 `YikeSyncDebugLog` 默认改为开启。
- 不为了输出诊断原因重构 `ReadBoard` 主流程。
- 不在 MVP 中做 zip 诊断包导出。

## 方案取舍

### 方案 A：只增强日志

优点：改动最小。  
缺点：用户仍然需要复现后找日志，维护者也难以看到当前 live 状态。

不推荐作为主方案，可以作为第二阶段导出的一部分。

### 方案 B：在现有同步路径旁记录只读快照

优点：不改变同步决策，面板可以直接展示当前状态；测试可以锁 formatter 和 snapshot。  
缺点：需要新增少量诊断模型和一个 recorder。

推荐采用。

### 方案 C：把诊断做成同步状态机的一部分

优点：字段最完整。  
缺点：容易污染同步行为，和本功能“只读排障层”的边界冲突。

不采用。

## MVP 范围

MVP 只做四件事：

1. 记录最近一次 `SyncDecisionTrace`。
2. 暴露当前 `SyncDiagnosticsSnapshot`。
3. 暴露当前 `YikeSessionDiagnosticsSnapshot`。
4. 提供“当前同步上下文”面板和“复制诊断摘要”。

诊断摘要使用纯文本，方便用户直接贴到 issue、聊天或 PR 评论中。

## 数据模型

### `SyncDecisionTrace`

职责：描述最近一次完整快照恢复决策，不能参与决策本身。

建议字段：

- `result`
  - `NO_CHANGE`
  - `SINGLE_MOVE_RECOVERY`
  - `HOLD`
  - `FORCE_REBUILD`
- `reasonCode`
  - 稳定字符串，便于测试和搜索。
  - 示例：`snapshot_matches_current`、`single_move_recovery`、`conflict_hold`、`force_rebuild_removed_stone`、`force_rebuild_multi_step`。
- `platform`
  - 来自 `SyncRemoteContext.SyncPlatform`。
- `windowKind`
  - 来自 `SyncRemoteContext.WindowKind`。
- `recoveryMoveNumber`
  - 仅展示，不用于诊断层新决策。
- `forceRebuildRequested`
- `firstSyncFrame`
- `snapshotHash`
  - 用于区分远端盘面，不输出完整棋盘。
- `changedStoneCount`
- `removedStoneCount`
- `resolvedNodeMoveNumber`
- `shouldResumeAnalysis`
- `timestampMillis`

`reasonCode` 第一版不必覆盖所有细节；无法稳定分类时可以使用 `unknown_complete_snapshot_reason`，但不能反向改变原同步结果。

### `SyncDiagnosticsSnapshot`

职责：当前 ReadBoard / 同步主流程状态。

建议字段：

- `readBoardAttached`
- `readBoardConnected`
- `javaReadBoard`
- `usePipe`
- `isSyncing`
- `pendingRemoteContextSummary`
- `awaitingFirstSyncFrame`
- `hasResumeState`
- `hasLastResolvedSnapshotNode`
- `syncAnalysisEpoch`
- `latestDecisionTrace`
- `lastProtocolLineSummary`
- `lastProtocolTimestampMillis`

`lastProtocolLineSummary` 只保留协议类型和少量安全 token，不输出完整窗口标题。

### `YikeSessionDiagnosticsSnapshot`

职责：当前弈客 session / geometry readiness 状态。

建议字段：

- `listenerEnabled`
- `currentRouteKind`
- `currentSessionKey`
- `activeSessionKey`
- `activeSyncReady`
- `activeGeometryReady`
- `activeBoardSize`
- `pendingSessionKey`
- `pendingSyncReady`
- `pendingGeometryReady`
- `pendingBoardSize`
- `effectiveGeometrySessionKey`
- `effectiveGeometryReady`
- `placementGeometryAllowed`
- `lastGeometryClearReason`
- `lastSessionSwitchReason`
- `lastYikeDebugEventSummary`

第一版允许部分字段为空；UI 必须把“未采集”与 `false` 区分开。

### `SyncDiagnosticsRecorder`

职责：保存当前只读快照。

约束：

- 不持有 `BoardHistoryNode`、`YikeSessionState` 等可变对象引用。
- 所有 getter 返回 immutable snapshot。
- 写入方法只接收值对象或基础字段。
- UI 和导出逻辑只能读 recorder，不能调用同步行为方法。

### `SyncDiagnosticsReport`

职责：组合 `SyncDiagnosticsSnapshot`、`YikeSessionDiagnosticsSnapshot` 和最近一次 `SyncDecisionTrace`，供 UI 和复制摘要使用。

约束：

- 只做组合和格式化，不采集状态。
- `SyncDiagnosticsRecorder.snapshot()` 返回这个组合对象。
- `SyncDiagnosticsSnapshot` 仍只表示 ReadBoard / 同步主流程状态，避免和整份报告混名。

## UI 设计

入口放在现有同步 / 读盘相关菜单中，名称建议：

- `同步诊断`
- 或 `当前同步上下文`

面板为普通 Swing 工具窗口，分为四块：

1. 连接状态
   - readboard 是否连接
   - 当前平台
   - 最近协议输入时间
   - 是否正在 `syncBoardStones`
2. 弈客 readiness
   - route kind
   - active / pending session
   - `syncReady`
   - `geometryReady`
   - geometry 是否允许用于落子
3. 最近同步决策
   - result
   - reasonCode
   - remote context 摘要
   - changed / removed stone count
   - first sync frame
   - 是否需要恢复分析
4. 分析恢复 / 引擎状态摘要
   - 是否存在 resume state
   - 是否有 last resolved snapshot node
   - `syncAnalysisEpoch`

按钮：

- `复制诊断摘要`
- `刷新`
- `关闭`

MVP 不放任何改变同步状态的按钮。

## 复制摘要格式

纯文本示例：

```text
Sync Diagnostics
time: 2026-05-30T20:15:04+08:00
platform: YIKE
readboard: attached=true connected=true syncing=false
remote: window=LIVE_ROOM recoveryMove=58 forceRebuild=false

Yike
route: live-room
active: live-room:186538 syncReady=true geometryReady=true boardSize=19
pending: live-room:186999 syncReady=true geometryReady=false boardSize=19
effectiveGeometry: session=live-room:186538 allowed=true

Latest decision
result: HOLD
reason: conflict_hold
firstSyncFrame: false
changedStones: 3
removedStones: 0
snapshotHash: 8f3a2c11
resumeAnalysis: false
```

## 第二阶段：诊断包导出

第二阶段再加导出目录或 zip：

- `summary.txt`
- `sync-context.json`
- `yike-session.json`
- `recent-decisions.jsonl`
- `readboard-protocol.log`
- `yike-debug.log`
- `environment.txt`

第二阶段需要 ring buffer：

- 最近 N 条 readboard 协议摘要。
- 最近 N 次 `SyncDecisionTrace`。
- 最近 N 次弈客 session / geometry readiness 变化。

脱敏默认开启：

- 窗口标题只保留 fingerprint。
- 房间 token 默认 hash。
- 绝对路径中的用户名隐藏。

## 测试计划

### 单元测试

新增或扩展：

- `SyncDecisionTraceTest`
  - formatter 输出稳定字段。
  - `HOLD`、`FORCE_REBUILD`、`NO_CHANGE`、`SINGLE_MOVE_RECOVERY` 都能格式化。
- `SyncDiagnosticsRecorderTest`
  - snapshot 不暴露可变引用。
  - 空状态可格式化。
  - 更新 latest decision 不影响 readboard / yike 其他字段。
- `YikeSessionDiagnosticsSnapshotTest`
  - active / pending readiness 输出正确。
  - `geometryReady=true` 且 `syncReady=false` 能被清楚展示。
  - 等待页 geometry invalidated 能展示为 placement not allowed。

### 现有回归测试

实现后至少跑：

```text
-Dtest=ReadBoardSyncDecisionTest,SyncSnapshotRebuildPolicyTest,OnlineDialogYikeSessionStateTest,BrowserFrameYikeSyncControlTest,YikeGeometryNormalizationTest,YikeUrlParserTest test
```

如果只改 formatter / snapshot，可先跑新增单测和相关 Yike / ReadBoard targeted tests，再按风险决定是否跑更广范围。

### 验收标准

1. 打开面板不会触发 `syncBoardStones(...)`。
2. 复制摘要不会触发同步、重建、清 session 或 readboard restart。
3. 弈客 `geometryReady=true`、`syncReady=false` 时，面板显示“白框 ready 但同步源未 ready”。
4. `HOLD` 能显示稳定 reason code。
5. `FORCE_REBUILD` 能显示是否 board-only，以及是否安排分析恢复。
6. 现有同步决策测试结果不因诊断层改变。

## 实施顺序

1. 新增诊断模型和 formatter 测试。
2. 新增 `SyncDiagnosticsRecorder`，只保存当前快照。
3. 在 `ReadBoard` 的完整快照恢复结果出口旁记录 `SyncDecisionTrace`。
4. 在 readboard 协议接收处记录最近协议摘要和时间。
5. 在 `OnlineDialog` 生成 `YikeSessionDiagnosticsSnapshot`。
6. 增加 Swing 面板和菜单入口。
7. 增加复制摘要按钮。
8. 跑 targeted tests，确认现有同步行为不变。

## 关键实现边界

- `SyncDecisionTrace` 只能在原 decision 已经得出后生成。
- recorder 不能决定 `HOLD` / `FORCE_REBUILD`。
- UI 不能读取 mutable sync objects。
- UI 不能调用 `syncBoardStones(...)`、`rebuildFromSnapshot(...)`、`clearBoardWithoutInvalidatingResumeState(...)`。
- 诊断摘要不能输出完整棋盘、完整窗口标题、完整房间 token。
- 若 docs 与实测代码行为冲突，先更新本 spec 或相关契约文档，再改实现。
