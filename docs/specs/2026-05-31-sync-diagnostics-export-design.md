# 同步诊断包导出设计

日期：2026-05-31

## 背景

`docs/specs/2026-05-30-sync-diagnostics-panel-design.md` 已完成 MVP：当前同步上下文面板、最近一次 `SyncDecisionTrace`、ReadBoard / 弈客 session readiness 快照，以及复制纯文本摘要。

MVP 能回答“当前状态是什么”和“最近一次完整快照恢复为什么这样决策”，但还缺少复现前后的连续证据。实际排查同步问题时，维护者通常还需要看到：

- 最近几条 readboard 协议摘要是否连续到达。
- `HOLD` / `FORCE_REBUILD` 前是否有多次相同或相近决策。
- 弈客 active / pending session、`syncReady`、`geometryReady` 是否经历过切换或清理。
- 用户发来的诊断内容是否脱敏、完整、可复现。

本阶段目标是把诊断面板升级为“一键导出当前诊断包”，但继续保持只读边界，不改变同步行为。

## 已核对的约束

- `docs/SNAPSHOT_NODE_KIND.md`
  - 同步主流程仍只允许 `NO_CHANGE`、`SINGLE_MOVE_RECOVERY`、`HOLD`、`FORCE_REBUILD`。
  - 诊断层不能从 marker、手数或盘面差异推导真实 `PASS`。
  - `FORCE_REBUILD` / `HOLD` 语义不能因为导出需求改变。
- `docs/specs/2026-05-30-sync-diagnostics-panel-design.md`
  - 诊断层是只读旁路，不参与同步 gating。
  - MVP 不做诊断包导出，本阶段正是补齐第二阶段。
  - 第二阶段需要最近 N 条 readboard 协议摘要、最近 N 次决策、最近 N 次弈客 session / geometry readiness 变化。
- 当前实现入口：
  - `SyncDiagnosticsRecorder` 保存 latest readboard / yike / decision snapshot。
  - `ReadBoard.recordProtocolLine(...)` 已生成安全协议摘要。
  - `ReadBoard.publishCompleteSnapshotRecoveryDiagnostics(...)` 已发布 `SyncDecisionTrace`。
  - `OnlineDialog.publishYikeDiagnostics(...)` 已发布 `YikeSessionDiagnosticsSnapshot`。
  - `SyncDiagnosticsDialog` 只读 recorder 并支持刷新 / 复制摘要。

## 目标

1. 在内存中保留最近的诊断事件，支持复现后导出。
2. 在同步诊断面板增加“导出诊断包”按钮。
3. 导出 zip 诊断包，包含当前摘要、结构化快照、最近决策、协议摘要、弈客事件和环境信息。
4. 默认脱敏，避免泄漏 SGF、room token、窗口标题、绝对路径用户名等信息。
5. 保持诊断代码只读，不新增任何改变同步状态的入口。

## 非目标

- 不新增 readboard wire protocol token。
- 不修改 `SNAPSHOT` / `MOVE` / `PASS` 合约。
- 不改变 `HOLD` / `FORCE_REBUILD` / `NO_CHANGE` / `SINGLE_MOVE_RECOVERY` 决策。
- 不把完整 SGF、房间 token、窗口标题或未脱敏路径写入诊断包。
- 不默认开启 `YikeSyncDebugLog`。
- 不做长期后台落盘日志。
- 不加“强制重建”“清 session”“重启 readboard”等操作按钮。
- 不引入第三方 JSON 或 zip 依赖；优先使用 JDK API。

## 用户工作流

1. 用户复现同步异常。
2. 打开 `同步诊断`。
3. 点击 `导出诊断包`。
4. 程序在固定目录生成 zip，并提示完整路径。
5. 用户把 zip 发给维护者。

第一版不做文件选择器，避免引入额外 UI 分支。默认输出目录为：

```text
target/sync-diagnostics/
```

文件名格式：

```text
sync-diagnostics-YYYYMMDD-HHMMSS.zip
```

如果运行目录不可写，导出失败并在对话框中显示错误文本；不尝试自动写入其他目录，避免用户找不到产物。

## 数据模型

### `SyncDiagnosticsEventBuffer<T>`

固定容量内存 ring buffer。

职责：

- 保存最近 N 条事件。
- 超过容量时丢弃最旧事件。
- 返回 immutable copy，避免 UI / exporter 修改内部状态。
- 内部同步，适配 ReadBoard / OnlineDialog / Swing UI 跨线程访问。

建议默认容量：

- readboard 协议摘要：`100`
- 同步决策：`50`
- 弈客 session 事件：`50`

容量先作为常量，不加配置项。

### `SyncProtocolDiagnosticEvent`

从现有 `lastProtocolLineSummary` 扩展出来的事件值对象。

字段：

- `timestampMillis`
- `summary`
- `source`

`summary` 必须使用现有脱敏摘要，不保存 raw line。

### `SyncDiagnosticsExportSnapshot`

导出时冻结 recorder 状态。

字段：

- `capturedAtMillis`
- `report`
- `recentProtocolEvents`
- `recentDecisionTraces`
- `recentYikeEvents`
- `environment`

`SyncDiagnosticsRecorder.exportSnapshot()` 返回这个对象。Exporter 只消费冻结后的 snapshot，避免写 zip 时事件还在变化。

### `SyncDiagnosticsEnvironment`

轻量环境摘要。

字段：

- `appVersion`（能取到则填，取不到为 `unknown`）
- `javaVersion`
- `osName`
- `osVersion`
- `osArch`
- `userDirSanitized`
- `timestampMillis`

路径脱敏规则：

- Windows 路径中 `C:\Users\<name>` 输出为 `C:\Users\<user>`。
- WSL / Linux 路径中 `/home/<name>` 输出为 `/home/<user>`。
- 其他路径只输出目录末尾或原值；不要为了环境摘要扫描文件系统。

## 导出包格式

zip 内固定文件：

```text
summary.txt
sync-context.json
yike-session.json
recent-decisions.jsonl
readboard-protocol.log
yike-events.jsonl
environment.txt
```

### `summary.txt`

使用现有 `SyncDiagnosticsReport.toSummaryText()`，再附加：

- recent protocol event count
- recent decision count
- recent yike event count
- export timestamp

### `sync-context.json`

当前 `SyncDiagnosticsSnapshot` 的结构化 JSON。

只输出已有安全字段，例如：

- `readBoardAttached`
- `readBoardConnected`
- `javaReadBoard`
- `usePipe`
- `syncing`
- `awaitingFirstSyncFrame`
- `hasResumeState`
- `hasLastResolvedSnapshotNode`
- `syncAnalysisEpoch`
- `pendingRemoteContextSummary`
- `lastResolvedSnapshotSummary`
- `lastProtocolLineSummary`
- `lastProtocolTimestampMillis`

### `yike-session.json`

当前 `YikeSessionDiagnosticsSnapshot` 的结构化 JSON。

只输出 session key / readiness / geometry readiness / reason 摘要，不输出窗口标题或 raw URL。

### `recent-decisions.jsonl`

每行一个 `SyncDecisionTrace`。

包含：

- `timestampMillis`
- `result`
- `reasonCode`
- `platform`
- `windowKind`
- `remoteContextFingerprint`
- `snapshotHash`
- `changedStoneCount`
- `removedStoneCount`
- `recoveryMoveNumber`
- `resolvedSnapshotMoveNumber`
- `resolvedSnapshotKind`
- `forceRebuildRequested`
- `firstSyncFrame`
- `shouldResumeAnalysis`
- `epoch`

### `readboard-protocol.log`

每行一个已脱敏协议摘要：

```text
timestampMillis<TAB>summary
```

禁止保存 raw protocol line。

### `yike-events.jsonl`

每行一个 `YikeSessionDiagnosticsSnapshot` 摘要，字段和 `yike-session.json` 一致。

### `environment.txt`

纯文本环境摘要，便于人工阅读。

示例：

```text
javaVersion: 17.0.x
osName: Windows 11
osArch: amd64
userDir: C:\Users\<user>\...
capturedAtMillis: 179...
```

## 脱敏规则

现有 `ReadBoard.summarizeProtocolLine(...)` 继续作为协议摘要入口。

必须脱敏：

- `roomToken`
- `sgf`
- `loadsgf`
- `recordTitleFingerprint`
- `readBoardUpdateReady`
- `liveTitle`
- 超过安全长度的 payload

新增环境脱敏：

- Windows 用户名路径。
- WSL / Linux 用户名路径。

诊断包不保存：

- 完整 SGF。
- 原始房间 token。
- 原始窗口标题。
- 浏览器完整 URL。
- 本机用户目录绝对路径中的用户名。

## 组件边界

### `SyncDiagnosticsRecorder`

新增职责：

- `recordProtocolEvent(SyncProtocolDiagnosticEvent event)`
- `recordDecisionTrace(SyncDecisionTrace trace)`
- `recordYikeEvent(YikeSessionDiagnosticsSnapshot event)`
- `exportSnapshot()`

保留职责：

- `updateSync(...)`
- `updateYikeSession(...)`
- `updateLatestDecision(...)`
- `snapshot()`

约束：

- `updateLatestDecision(...)` 同时写 latest 和 decision buffer。
- `updateYikeSession(...)` 同时写 latest 和 yike buffer。
- 协议摘要通过独立方法写入 protocol buffer，不要求每次都构造完整 sync snapshot。
- recorder 不写文件。

### `SyncDiagnosticsExporter`

职责：

- 接收 `SyncDiagnosticsExportSnapshot`。
- 创建输出目录。
- 写 zip。
- 返回 zip path。

约束：

- 不读取或修改同步状态。
- 不调用 `ReadBoard` / `OnlineDialog` 行为方法。
- 不吞异常；失败交给 UI 展示。

### `SyncDiagnosticsJson`

轻量 JSON writer。

约束：

- 只服务固定诊断字段。
- 必须正确转义 `\`、`"`、控制字符和换行。
- 不做通用反射 serializer。

### `SyncDiagnosticsDialog`

新增：

- `导出诊断包` 按钮。
- 导出成功后显示路径。
- 导出失败后显示错误摘要。

不新增：

- 改变同步状态的按钮。
- 自动打开文件管理器。
- 文件选择器。

## 错误处理

- 输出目录创建失败：显示错误，不改变诊断状态。
- zip 写入失败：显示错误，保留已有面板内容。
- snapshot 为空：仍导出 zip，字段显示 `not captured` / `unknown`。
- buffer 为空：对应 JSONL / log 文件为空文件，不省略文件。

## 测试计划

### 单元测试

- `SyncDiagnosticsEventBufferTest`
  - 超过容量只保留最新 N 条。
  - snapshot copy 不可修改内部状态。
- `SyncDiagnosticsRecorderTest`
  - protocol / decision / yike buffers 都能出现在 export snapshot。
  - `updateLatestDecision(...)` 同步更新 latest 和 recent decisions。
  - `updateYikeSession(...)` 同步更新 latest 和 yike events。
- `SyncDiagnosticsJsonTest`
  - 字符串转义覆盖引号、反斜杠、换行和控制字符。
- `SyncDiagnosticsExporterTest`
  - zip 包含固定文件列表。
  - JSONL 每行一条事件。
  - 空 buffer 仍生成固定文件。
  - 环境路径脱敏。

### 回归测试

- `ReadBoardSyncDecisionTest`
  - 现有 reasonCode 断言继续通过。
  - 协议摘要仍不泄漏敏感 payload。
- `OnlineDialogYikeSessionStateTest`
  - session / geometry readiness 发布不改变 promote / invalidate 决策。
- `SyncDiagnosticsResourceTest`
  - 新增导出按钮文案存在。

### 验证命令

```bash
mvn -Dtest=SyncDiagnosticsEventBufferTest,SyncDiagnosticsRecorderTest,SyncDiagnosticsJsonTest,SyncDiagnosticsExporterTest test
mvn -Dtest=SyncDecisionTraceTest,YikeSessionDiagnosticsSnapshotTest,ReadBoardSyncDecisionTest,OnlineDialogYikeSessionStateTest,SyncDiagnosticsResourceTest test
mvn -DskipTests package
```

Windows 侧最终验证使用仓库内置 Maven：

```powershell
D:\dev\weiqi\lizzieyzy-next\.tools\apache-maven-3.9.10\bin\mvn.cmd -DskipTests package
```

## 风险与缓解

- 风险：诊断包泄漏用户信息。
  - 缓解：只记录摘要；测试覆盖敏感命令和路径脱敏。
- 风险：导出时同步事件继续写入导致包内内容不一致。
  - 缓解：先冻结 `SyncDiagnosticsExportSnapshot`，再写 zip。
- 风险：诊断 recorder 变成同步决策依赖。
  - 缓解：所有同步逻辑仍只写 recorder，不读 recorder 决策；测试覆盖原同步行为。
- 风险：UI 导出失败让用户误以为同步失败。
  - 缓解：导出错误只显示在诊断面板，不触发同步状态变化。

## 实施顺序

1. 添加 event buffer、protocol event、environment、export snapshot 数据模型。
2. 扩展 recorder 保存 recent buffers。
3. 增加 JSON writer 和 zip exporter。
4. 在 ReadBoard / OnlineDialog 发布 recent 事件。
5. 在诊断面板加导出按钮和结果提示。
6. 补齐单元测试、回归测试和 Windows 构建验证。
