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

第一版不做文件选择器，避免引入额外 UI 分支。默认输出目录为用户目录下的应用诊断目录：

```text
<user.home>/.lizzie-yzy/sync-diagnostics/
```

文件名格式：

```text
sync-diagnostics-YYYYMMDD-HHMMSS.zip
```

测试可以注入临时输出目录；UI 默认不写 `target/`，避免安装目录或快捷方式工作目录不可写导致一键导出默认失败。

如果输出目录不可写，导出失败并在对话框中显示错误摘要；错误文本可以显示经过脱敏的目录，不能显示原始用户路径。

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

路径脱敏规则默认拒绝输出原文：

- Windows 路径中 `<drive>:\Users\<name>` 输出为 `<drive>:\Users\<user>`。
- WSL mounted Windows 路径中 `/mnt/<drive>/Users/<name>` 输出为 `/mnt/<drive>/Users/<user>`。
- WSL UNC 路径中 `\\wsl.localhost\<distro>\home\<name>` 输出为 `\\wsl.localhost\<distro>\home\<user>`。
- Linux / WSL 路径中 `/home/<name>` 输出为 `/home/<user>`。
- macOS 路径中 `/Users/<name>` 输出为 `/Users/<user>`。
- 无法识别的绝对路径输出 `<redacted-path>`。
- 相对路径只允许输出 basename；不输出父目录。
- 不为了环境摘要扫描文件系统。

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

使用导出专用 formatter，不直接复用 live UI 的 `SyncDiagnosticsReport.toSummaryText()` 原文。导出 formatter 必须先经过 `SyncDiagnosticsExportSanitizer`，尤其要处理弈客 session key 和路径字段。

内容包括脱敏后的当前摘要，再附加：

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

只输出脱敏后的 session alias / readiness / geometry readiness / reason 摘要，不输出窗口标题、raw URL、原始 room id 或原始 session key。

弈客 session key 脱敏规则：

- `currentSessionKey`、`activeSessionKey`、`pendingSessionKey`、`effectiveGeometrySessionKey` 都不能以原文进入导出包。
- 导出时为同一份 zip 内出现过的 session key 分配稳定 alias，例如 `live-room#1`、`live-room#2`、`unite-board#1`。
- alias 只保留 route kind 和序号，用于同一诊断包内部关联 active / pending / effective geometry。
- 不使用原始 numeric room id；也不使用可被低成本枚举的 numeric id hash。

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

每行一个脱敏后的 `YikeSessionDiagnosticsSnapshot` 摘要，字段和 `yike-session.json` 一致。所有 session key 同样使用本次导出内的稳定 alias。

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

- `<drive>:\Users\<name>`。
- `/mnt/<drive>/Users/<name>`。
- `\\wsl.localhost\<distro>\home\<name>`。
- `/home/<name>`。
- `/Users/<name>`。
- 未识别绝对路径必须输出 `<redacted-path>`，不能输出原文。

诊断包不保存：

- 完整 SGF。
- 原始房间 token。
- 原始弈客 room id / session key。
- 原始窗口标题。
- 浏览器完整 URL。
- 本机用户目录绝对路径中的用户名。

导出层必须提供 `SyncDiagnosticsExportSanitizer`：

- 对 `SyncDiagnosticsReport`、`SyncDiagnosticsSnapshot`、`YikeSessionDiagnosticsSnapshot`、`SyncDecisionTrace` 和 environment 生成导出专用安全视图。
- live UI 可以继续显示当前 recorder 中的原始调试摘要；zip 导出只能使用安全视图。
- sanitizer 维护本次导出的 session alias 表，确保同一原始 key 在同一包内映射到同一 alias。

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

### `SyncDiagnosticsExportSanitizer`

职责：

- 生成导出专用安全视图。
- 脱敏弈客 session key / room id。
- 脱敏环境路径。
- 确保 `summary.txt`、JSON、JSONL、log 使用同一套安全字段。

约束：

- 不修改 recorder 内部对象。
- 不影响 live UI 的当前文本展示。
- 不保存 raw session key 到导出对象。

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
  - 构造包含敏感 SGF、`roomToken`、弈客 raw session key、raw URL、窗口标题、Windows/WSL/macOS 用户路径的 snapshot，解压 zip 后逐文件断言敏感原文不存在。
  - raw session key 在 `summary.txt`、`yike-session.json`、`yike-events.jsonl` 中都只出现 alias。
  - 不可写输出目录错误文案不包含原始用户路径。

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
  - 缓解：只记录摘要；导出层使用安全视图；测试扫描整个 zip 中所有文本文件，覆盖敏感命令、弈客 session key、raw URL、窗口标题和路径脱敏。
- 风险：导出时同步事件继续写入导致包内内容不一致。
  - 缓解：先冻结 `SyncDiagnosticsExportSnapshot`，再写 zip。
- 风险：诊断 recorder 变成同步决策依赖。
  - 缓解：所有同步逻辑仍只写 recorder，不读 recorder 决策；测试覆盖原同步行为。
- 风险：UI 导出失败让用户误以为同步失败。
  - 缓解：导出错误只显示在诊断面板，不触发同步状态变化。

## 实施顺序

1. 添加 event buffer、protocol event、environment、export snapshot 数据模型。
2. 扩展 recorder 保存 recent buffers。
3. 增加导出 sanitizer、JSON writer 和 zip exporter。
4. 在 ReadBoard / OnlineDialog 发布 recent 事件。
5. 在诊断面板加导出按钮和结果提示。
6. 补齐单元测试、端到端脱敏测试、回归测试和 Windows 构建验证。
