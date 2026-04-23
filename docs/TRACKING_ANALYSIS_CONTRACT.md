# Tracking Analysis Contract

补充《Tracking Analysis Design》中未明确的契约边界，作为后续修复和审查的基线。

## 线程模型

| 线程 | 调用 |
|------|------|
| EDT | 用户交互（菜单、右键、键盘）、`BoardRenderer` 渲染 |
| 引擎 readLoop | `TrackingEngine` 内部的 `inputStream.readLine()` 循环 |
| Leelaz read 线程 | 主引擎日志解析，可能调用 `Lizzie.frame.ensureTrackingEngine()`（预加载） |
| 后台启动线程 | `ensureTrackingEngine` 中创建用于 `engine.startEngine(cmd)` |
| 后台关闭线程 | `destroyTrackingEngine` 中创建用于 `te.shutdown()` |

## 字段可见性合约

| 字段 | 修饰 | 原因 |
|------|------|------|
| `LizzieFrame.trackingEngine` | `volatile` | 多线程写读（EDT/引擎线程/启动线程） |
| `LizzieFrame.trackingConsolePane` | 普通字段 | 只在 EDT 内 `SwingUtilities.invokeLater` 中访问 |
| `LizzieFrame.trackedCoords` | `Collections.synchronizedSet` | 多线程添加/删除/迭代，迭代必须用调用者持锁或拷贝 |
| `LizzieFrame.isKeepTracking` | `volatile` | 引擎线程读，EDT/菜单写 |
| `TrackingEngine.isLoaded` | `volatile` | 引擎线程写、UI 线程读 |
| `TrackingEngine.currentTrackedMoves` | `volatile` 整体替换 | `getCurrentTrackedMoves` 必须返回快照拷贝 |
| `TrackingEngine.requestId` | `AtomicInteger` | sendTrackingRequest / clearTrackedMoves 并发递增 |
| `TrackingEngine.consolePane` | `volatile` | EDT 写、引擎线程读 |
| `TrackingEngine.engineReady` | `volatile` | 引擎线程读写、状态指示 |

## 启动顺序合约

`TrackingEngine.startEngine` 必须保证：
1. 进程启动成功后**先**初始化 `inputStream`/`outputStream`/`executor`
2. **最后**才设置 `isLoaded = true`
3. 启动失败时调用 `updateConsoleTitle("启动失败")` 而非保持"启动中"

## 调用者合约

- `ensureTrackingEngine()` 是异步的：返回不代表引擎已 ready。调用者若要立刻 sendCommand，必须先检查 `isLoaded()` 或接受请求被丢弃
- `ensureTrackingEngineWithWarning()` 返回 `false` 仅表示用户在警告对话框点了取消，返回 `true` 不代表引擎可用
- `ensureTrackingEngineWithWarning()` 内部会调用 `JOptionPane.showConfirmDialog`，**必须** 从 EDT 调用。当前唯一调用点是 `RightClickMenu.trackPointAction`（菜单 ActionListener，本身就在 EDT）。如果未来有从非 EDT 线程触发警告的需求，必须用 `SwingUtilities.invokeAndWait` 包装
- `triggerTrackingAnalysis` 在 `sendTrackingRequest` 内部已对 `trackedCoords` 做防御拷贝（见 `LinkedHashSet` 拷贝），调用者无需额外加锁
- `clearTrackedMoves()` 必须递增 `requestId` 以失效旧响应
- `getCurrentTrackedMoves()` 返回的是 List 拷贝，但其中的 `MoveData` 元素是引用共享的——渲染端读字段时应一次性提取到局部变量

## 资源清理合约

- `Font.createFont(stream)` 必须使用 try-with-resources 关闭流
- `process.destroyForcibly()` 后必须 `waitFor(2, SECONDS)` 等待
- `destroyTrackingEngine` 异步 shutdown 必须有 try-catch 防止异常静默逃逸
- `TrackingConsolePane.addLine` 必须节流，避免 KataGo tuning 阶段每行一次 `invokeLater` 堆积 EDT 队列
- `addLine` 累积缓冲超过 `MAX_BUFFER_BYTES`（默认 8192）时丢弃多余行，避免巨量日志一次性插入冻结 EDT
- `startEngine` 在创建流之后任何步骤失败时必须释放已创建的流和 process

## 引擎销毁/创建顺序合约

`destroyTrackingEngine` 是异步的（后台线程做 shutdown）。如需在销毁后立即调 `ensureTrackingEngine`，必须确保新引擎不会在旧引擎 shutdown 完成前启动。
当前实现：`ensureTrackingEngine` 通过 `trackingEngineStarting` CAS 防重入；调用方应避免在 100ms 内连续 destroy + ensure。

## 错误传播合约

- `sendCommand` 写流失败（IOException）必须把 `isLoaded` 置为 false 并 `updateConsoleTitle("已关闭")`，让 UI 立刻反映引擎不可用
- `parseResult` 中比对 `requestId` 必须在函数入口缓存到局部变量；解析完成准备写入 `currentTrackedMoves` 前再次校验 ID 仍然匹配，否则丢弃
- `parseResult` 中所有访问 `Lizzie.board` / `Lizzie.frame` 必须先缓存到局部变量再做 null 检查，避免两次读之间字段被外部置为 null

## 启停时序合约

- `startEngine` 调用 `executor.execute(this::readLoop)` 时必须用 try-catch 包围，应对并发 shutdown 已经 `shutdownNow()` 的情况；rejected 时回到失败路径（`isLoaded=false` + 控制台"启动失败"）
- `shutdown` 是幂等的，可以多次调用不会引发异常

## 重置警告对话框

`tracking-engine-skip-warning` 配置必须可在 ConfigDialog2 中重置，否则用户无法恢复警告。
重置操作必须遵循 ConfigDialog2 标准的 OK/Cancel 流程——点重置按钮只暂存意图，OK 时才真正写入 config，Cancel 时不生效。

## 命令转换合约

`TrackingEngine.toAnalysisCommand` 必须把主引擎命令中的 `gtp` 子命令 token 替换为 `analysis`，并补充 analysis 模式必需参数：
- 使用 `(\\s)gtp(\\s|$)` 而非 `\\bgtp\\b` 进行匹配，确保只替换"两侧均为空白或行尾"的独立 token
- 路径中含 `gtp` 字符串（如 `/usr/local/gtp/bin/katago`）不得被破坏
- `numAnalysisThreads=1` 与 `nnMaxBatchSize=8` 必须出现在最终命令中（追加到 `-override-config`，已存在则跳过）
- **已知限制**：如果可执行路径**目录名含空格且包含 gtp 子串**（如 `/path with gtp dir/katago gtp ...`），`replaceFirst` 会优先匹配路径里的 `gtp`。生产环境中 KataGo 通常装在简单路径下，此 case 不在保障范围；如需支持需切换到 `Utils.splitCommand` token 级解析。

## EDT 防御

`TrackingConsolePane` 的按钮事件回调可能在主窗口尚未完全初始化或正在销毁时触发。所有访问 `Lizzie.frame.xxx` 的回调都必须先做 `Lizzie.frame != null` 防御。

## 不在范围内（已被 spec 排除）

- 持久化追踪结果（spec 明确为 transient）
- 追踪点显示 PV（spec 排除）
- 非 KataGo 引擎支持（spec 排除）
- SSH 远程追踪引擎（spec 排除）
- `MoveData.playouts` int 溢出（项目原有类型，不属本任务）
