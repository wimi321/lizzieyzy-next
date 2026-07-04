# 批量闪电分析设置与稳定性可行性说明

## 背景

本说明只覆盖“自动分析 -> 批量分析（闪电模式）”相关入口，不实现代码改动。

用户反馈的问题和补充约束：

1. 批量闪电分析选完棋谱后的“自动分析设置”窗口只能点“开始分析”和“终止分析”，无法从这里跳转到“闪电分析设置”；设置按钮只在分析开始后的进度窗口里出现。
2. “闪电分析设置”里的“自动生成”只能手动生成命令；希望在自动生成按钮右侧增加一个入口，可以从 `lizzieyzy-next` 已保存的引擎/模型中选择，并自动把主引擎命令转换为 KataGo analysis 命令。
3. 批量闪电分析开始后偶发无报错卡死，需要先找真实原因。
4. 如果用户以前没有手动在闪电分析里选择过引擎，也没有手动改过闪电分析命令，则闪电分析命令应跟随 `lizzieyzy-next` 当前设置的默认引擎；如果用户改过，则保留用户改过的命令不变。

## 已核对的项目文档和契约

- `docs/SNAPSHOT_NODE_KIND.md`
  - `AnalysisEngine` 组装分析请求时，`initialStones`、`initialPlayer`、`moves` 必须共同基于最近一个 `SNAPSHOT` 锚点。
  - 依赖真实手顺的功能只消费真实 `MOVE/PASS`，遇到 `SNAPSHOT` 时视为历史边界或静态局面锚点。
- `docs/TRACKING_ANALYSIS_CONTRACT.md`
  - 已有 KataGo 命令转换边界：不能用简单字符串替换破坏路径里的 `gtp`，长期更稳的做法是 token 级解析。
  - 该文档针对 tracking engine，但其中的命令转换边界适用于本任务。
- `docs/PLAYER_STRENGTH_CALIBRATION.md`
  - 离线批量 KataGo 分析已有“批次过大可能卡住”的经验：脚本侧用较小 batch 和超时重启规避。桌面闪电分析目前没有等价超时/背压处理。
- KataGo 官方 `analysis_example.cfg`
  - 来源：`https://github.com/lightvector/KataGo/blob/master/cpp/configs/analysis_example.cfg`
  - 可作为 `analysis.cfg` 缺失时的本地模板来源，但模板必须随源码或 release 预置，运行时不能现场从 GitHub 下载。

相关测试入口：

- `src/test/java/featurecat/lizzie/analysis/AnalysisEngineRequestTest.java`
- `src/test/java/featurecat/lizzie/util/KataGoRuntimeHelperTest.java`
- `src/test/java/featurecat/lizzie/util/UtilsEngineSettingsTest.java`

## 现有代码路径

批量闪电分析入口：

- `AnalysisTable` 中先通过“添加文件”选择棋谱，文件进入 `Lizzie.frame.Batchfiles`。
- `AnalysisTable` 的“批量分析（闪电模式）”按钮随后打开 `new StartAnaDialog(true, Lizzie.frame)`。
- `StartAnaDialog(true, ...)` 只渲染开始手数、结束手数、每手总计算量、是否分析所有分支，以及“开始分析 / 终止分析”两个按钮。
- 普通自动分析和普通批量分析打开的是 `new StartAnaDialog(false, Lizzie.frame)`，同一个类但字段集合不同：它会显示时间、总计算量、首位计算量、黑白方、波动加强分析、暂停退出等普通自动分析选项。
- 点击开始后，`StartAnaDialog.apply()` 调用 `Lizzie.frame.flashAnalyzeGameBatch(...)`。
- `flashAnalyzeGameBatch(...)` 设置 `isBatchAnalysisMode = true`，再调用 `flashAnalyzeGame(false, isAllBranches)`。
- `flashAnalyzeGame(...)` 在当前线程里创建或复用 `AnalysisEngine`，随后调用 `startRequest(...)` 或 `startRequestAllBranches(...)`。
- `AnalysisEngine.startRequest(...)` 会遍历待分析节点，并逐个调用 `sendRequest(...)`。
- `sendRequest(...)` 直接通过 `sendCommand(...)` 写入引擎 stdin。
- 分析进度窗口 `WaitForAnalysis` 只在所有请求发送之后创建；它里面才有“设置”按钮。

“闪电分析设置”入口：

- 主菜单/工具栏里的“闪电分析设置”直接打开 `new AnalysisSettings(false, false)`。
- 进度窗口里的“设置”打开 `new AnalysisSettings(true, false)`。
- `AnalysisSettings` 的“自动生成”按钮调用 `GetEngineLine.getEngineLine(dialog, true, true, false, false)`，这是基于文件选择器的生成流程，不读取已保存引擎列表。
- 已保存引擎列表来自 `Utils.getEngineData()`，底层读取 `leelaz.engine-settings-list`。
- `Config` 和 `KataGoAutoSetupHelper` 已经会保存一份 `analysis-engine-command`，但它不是从任意已保存引擎动态转换出来的。

## 问题 1：选完棋谱后的批量闪电窗口不能打开闪电分析设置

### 可行性

可行，改动范围小。用户构想的按钮位置应落在 `StartAnaDialog(true, ...)` 这个批量闪电专用的“自动分析设置”窗口里，而不是普通 `StartAnaDialog(false, ...)` 自动分析窗口里。

这两个窗口确实不一样：

- `StartAnaDialog(true, ...)` 是批量闪电分析模式，`GridLayout` 只有 4 行，只处理开始手数、结束手数、每手总计算量、是否分析所有分支。
- `StartAnaDialog(false, ...)` 是普通自动分析/普通批量分析模式，按 `Lizzie.frame.isBatchAna` 切 10 或 11 行，包含时间、总计算量、首位计算量、黑白方、波动加强分析、暂停退出、自动保存等选项。

因此按钮应只加在 `isAnalysisMode == true` 的窗口形态里，避免普通自动分析窗口多出一个语义重复的设置入口。

原因是 `AnalysisSettings.saveConfig()` 当前用 `Lizzie.frame.isBatchAnalysisMode` 判断“单步计算量”写入哪个配置项：

- `true`：写 `batchAnalysisPlayouts`
- `false`：写 `analysisMaxVisits`

而批量闪电分析开始前，`isBatchAnalysisMode` 还没有被置为 `true`。如果此时直接打开现有 `AnalysisSettings(false, false)`，用户在设置页里看到/保存的“单步计算量”会走普通闪电分析配置，不是批量闪电配置。

### 推荐方案

给 `AnalysisSettings` 增加显式上下文，而不是继续依赖全局 `Lizzie.frame.isBatchAnalysisMode`：

- 新增一个轻量参数，例如 `boolean batchAnalysisContext`，或一个更清晰的 enum。
- `StartAnaDialog(true, ...)` 的底部按钮区增加“闪电分析设置”或“设置”按钮，放在“开始分析 / 终止分析”旁边，打开批量上下文的 `AnalysisSettings`。
- 这个按钮只在 `isAnalysisMode == true` 时显示；普通自动分析窗口不增加该按钮。
- 保存配置时，批量上下文写 `batchAnalysisPlayouts`；普通上下文写 `analysisMaxVisits`。
- 如果设置保存后 `analysisEngineCommand` 或远程引擎配置发生变化，并且已有预加载的 `analysisEngine` 存活，应销毁预加载实例，让下一次开始分析使用新命令。

### 不推荐方案

临时把 `Lizzie.frame.isBatchAnalysisMode` 设成 `true` 后再打开设置页。

这会让 UI 状态和实际分析状态混在一起，也容易影响 `AnalysisTable.resetAnalysisMode()`、取消分析、批量文件流转等逻辑。

## 问题 2：从已保存引擎生成 analysis 命令

### 可行性

可行，但必须做 token 级转换，不能做裸字符串替换。

已保存引擎数据可从 `Utils.getEngineData()` 读取。每个 `EngineData` 包含：

- `name`
- `commands`
- `useJavaSSH`
- `initialCommand`
- 远程连接字段

KataGo 本地命令通常形如：

```text
"D:\katago\katago.exe" gtp -model "D:\katago\weights\xxx.bin.gz" -config "D:\katago\configs\gtp.cfg"
```

目标 analysis 命令应为：

```text
"D:\katago\katago.exe" analysis -model "D:\katago\weights\xxx.bin.gz" -config "D:\katago\configs\analysis.cfg" -quit-without-waiting
```

### 推荐转换规则

新增一个纯逻辑 helper，例如：

```text
KataGoRuntimeHelper.toAnalysisEngineCommand(EngineData engine)
```

或放在更 UI 中性的 helper 类中。它只做转换，不直接写 config。

规则：

1. 用 `Utils.splitCommand(engine.commands)` 解析命令。
2. 只接受本地 KataGo-like 命令，首期不支持 remote / iKataGo / LeelaZero。
3. 找到独立 token `gtp`，替换为 `analysis`。
4. 找到 `-config` / `--config` 的值。
5. 计算同目录下的 `analysis.cfg`。
6. 如果 `analysis.cfg` 已存在，直接把 config token 替换成它。
7. 如果 `analysis.cfg` 不存在，但目标目录可写，则从随应用打包的本地 `analysis_example.cfg` 模板复制生成同目录 `analysis.cfg`，再替换 config token。
8. 自动生成后必须给用户明确提示：`缺少 analysis.cfg，已自动生成`，并说明生成路径。
9. 如果 `analysis.cfg` 不存在且无法生成，候选项保持不可用或弹出错误，不生成半可用命令。
10. 如果已经有 `-quit-without-waiting`，不重复追加；否则追加。
11. 保留 `-model` / 其他用户参数。
12. 用已有 `KataGoRuntimeHelper` 的命令重建/引用转义逻辑输出字符串，避免路径空格丢失。

### `analysis.cfg` 不存在时的处理

按用户最新要求，缺少 `analysis.cfg` 时可以自动生成，但必须显式告知用户。

推荐生成策略：

- 在仓库中预置一份 KataGo 官方 `analysis_example.cfg`，建议路径为 `src/main/resources/katago/analysis_example.cfg`，构建后作为 classpath resource 读取。
- 模板来源记录为 KataGo 官方文件：`https://github.com/lightvector/KataGo/blob/master/cpp/configs/analysis_example.cfg`。更新模板只能在开发/构建阶段完成，不能在用户点击生成时联网下载。
- 只在已保存引擎命令里存在 `-config` / `--config` 时生成；原 config 文件本身不再作为生成内容来源，只用它的目录决定 `analysis.cfg` 目标位置。
- 目标路径固定为原 config 同目录下的 `analysis.cfg`。
- 若目标文件不存在，直接复制本地模板生成 `analysis.cfg`。首期不尝试根据用户 `gtp.cfg` 智能改写模板参数，避免误改用户调好的参数。
- 生成成功后，把命令里的 config token 指向新生成的 `analysis.cfg`。
- 生成成功后弹出信息或在设置窗口状态区提示：`缺少 analysis.cfg，已自动生成：<path>`。
- 如果目录不可写、模板资源缺失、命令没有 config 参数，停止转换并提示具体原因。

这个策略比静默回退到 `gtp.cfg` 更清楚：最终命令仍然满足“config 要改成 analysis.cfg”，同时用户能知道这个文件不是原来就存在，而是本次自动生成的。生成过程只读本地资源，不依赖 GitHub 或其他网络服务。

### UI 方案

在 `AnalysisSettings` 顶部“自动生成”按钮右侧增加一个小按钮或下拉入口：

- 文案建议：`已保存...` 或 `选择已保存引擎`
- 点击后弹出候选列表，显示 `EngineData.name`，必要时补充一段短错误原因。
- 选中可转换项后，把转换后的命令填入 `engineCmd` 文本框，不立即保存。
- 用户仍通过“确定”保存，保持现有 OK/Cancel 行为。

不要把已保存引擎选择直接绑定成自动保存，否则用户误点后没有撤销空间。

### 默认引擎跟随策略

新增一个轻量配置状态记录闪电分析命令是否被用户手动定制，例如：

```text
analysis-engine-command-customized
```

规则：

1. 如果该标记为 `false`，打开闪电分析设置或启动闪电分析时，应基于 `Utils.getEngineData()` 中当前默认引擎生成 analysis 命令，而不是继续使用静态占位命令 `katago analysis -model model.bin.gz -config analysis.cfg -quit-without-waiting`。
2. 如果用户通过新增的“选择已保存引擎”入口选择了某个引擎，或手动编辑了 `engineCmd` 并点击“确定”，把该标记置为 `true`，以后保留 `analysis-engine-command` 不再自动跟随默认引擎。
3. 如果用户只打开设置窗口后取消，不改变该标记。
4. 如果该标记为 `false` 且默认引擎不可转换，应保留现有命令并提示原因，不能静默写入坏命令。
5. 对历史版本升级时，因为旧配置没有记录“是否手动改过”，应采用保守迁移：
   - `analysis-engine-command` 缺失、为空、或等于内置占位默认命令时，视为未定制，可跟随当前默认引擎。
   - `analysis-engine-command` 已有非占位命令且无法可靠判定来源时，视为已定制并保留，避免覆盖用户可能手动维护的闪电分析命令。

这个策略牺牲了一部分旧配置自动迁移的激进性，但满足“改过则保留不变”的优先级。新版本之后有显式标记，就能准确区分“跟随默认引擎”和“用户定制命令”。

## 问题 3：开始分析后偶发无报错卡死

### 静态证据

目前有三类高风险点，优先级从高到低：

1. `sendCommand(...)` 在 EDT 上同步写入引擎 stdin。
   - `StartAnaDialog.apply()` 由按钮事件触发，运行在 EDT。
   - `flashAnalyzeGameBatch(...) -> flashAnalyzeGame(...) -> AnalysisEngine.startRequest(...)` 没有切后台线程。
   - `startRequest(...)` 会先发送所有待分析请求，再显示 `WaitForAnalysis`。
   - 如果 KataGo 还在加载、调参、或 stdin 管道发生背压，`outputStream.flush()` 可能阻塞 EDT，表现为无报错卡死。
2. `sendCommand(...)` 吞掉写入失败。
   - 当前 `sendCommand` 捕获所有异常后只 `printStackTrace()`。
   - `sendRequest(...)` 随后仍把该请求加入 `analyzeMap`。
   - 结果是进度窗口等待一个实际没有成功发送的 id，可能永久停在 `12/99` 这类状态。
3. 批量完成后的下一盘流转在非 EDT 线程里操作 UI 和棋局状态。
   - `AnalysisEngine.read()` 线程调用 `parseResult(...)`。
   - `parseResult(...)` 调用 `waitFrame.setProgress(...)`。
   - `WaitForAnalysis.setProgress(...)` 在批量完成时新建线程并调用 `Lizzie.frame.flashAutoAnaSaveAndLoad()`。
   - `flashAutoAnaSaveAndLoad()` 会隐藏窗口、保存 SGF、加载下一盘、修改 toolbar 和启动下一轮分析。这些都不是纯后台逻辑，跨线程调用 Swing/棋盘状态有偶发卡死或竞态风险。

### 初步判断

最像用户描述的“开始后无报错卡死”的是前两项：

- 如果是刚点开始后 UI 无响应，优先怀疑 EDT 上批量写 stdin 被引擎加载/管道背压卡住。
- 如果进度窗口已经出现但停在某个数字不动，优先怀疑某个请求发送失败、引擎无响应或缺少超时回收，`analyzeMap` 永远等不到完成。

第三项更像“批量跑完一盘切下一盘时偶发卡住”，也应一起纳入修复范围，但需要线程 dump 或复现日志确认。

### 推荐修复方向

先做可验证的最小修复，不一次性重写整个分析引擎：

1. 把批量请求构建和发送移出 EDT。
   - EDT 只负责打开进度窗口和更新按钮状态。
   - 后台线程负责遍历节点、发送请求。
   - 所有 Swing 更新用 `SwingUtilities.invokeLater(...)` 回到 EDT。
2. 让 `sendCommand` 返回成功/失败，或抛出受检异常。
   - 发送失败时不要把 id 放进 `analyzeMap`。
   - 发送失败需要结束当前分析任务，并在 UI 上显示明确错误。
3. 增加 analysis request watchdog。
   - 每个批量任务记录最后收到结果的时间。
   - 超时后销毁当前 `AnalysisEngine`，显示错误或允许重试。
   - 这个超时只针对“长时间无任何进展”，不是单步 visits 的硬超时。
4. 批量下一盘流转统一回到 EDT。
   - `flashAutoAnaSaveAndLoad()` 中涉及 Swing / board / toolbar 的部分必须在 EDT 执行。
   - SGF 保存可以在后台做，但完成后切回 EDT 继续加载下一盘。

### 需要的诊断证据

实现前最好加一轮轻量日志或复现时收集线程 dump：

- 点击开始时记录分析任务 id、待发送请求数、是否 EDT、命令摘要。
- 每发送 N 个请求记录一次进度。
- 发送失败记录 request id 和异常。
- 每收到 N 个结果记录 `resultCount/analyzeMap.size()`。
- 批量切下一盘时记录当前线程名。
- 卡死时抓一次 Java thread dump，确认 EDT 是否停在 `BufferedOutputStream.flush()` / `ProcessPipeOutputStream.write()` / Swing modal dialog / 文件 IO。

## 方案对比

### 方案 A：只补设置入口和已保存引擎选择

优点：

- 改动小，能快速解决前两个 UI 问题。
- 风险集中在 `AnalysisSettings` 和命令转换 helper。

缺点：

- 不解决卡死根因。
- 如果用户继续遇到卡死，新增入口会让问题更容易暴露。

### 方案 B：设置入口 + 已保存引擎选择 + 请求发送错误处理

优点：

- 同时解决前两个 UI 问题，并降低“进度卡住不报错”的概率。
- 改动仍然可控。

缺点：

- 只能覆盖发送失败，不能完全覆盖 EDT 阻塞和批量切盘线程问题。

### 方案 C：完整整理闪电分析执行模型

内容：

- 显式区分普通闪电分析 / 批量闪电分析上下文。
- 命令转换 helper 独立测试。
- 请求发送后台化。
- 发送失败显式终止。
- 进度和批量切盘统一 EDT。
- 增加 watchdog。

优点：

- 更接近真正解决“偶发卡死”。
- 后续扩展远程分析、已保存模型选择、预加载重启时更稳。

缺点：

- 改动面明显大于 UI 小修。
- 需要更完整的测试和一次实际 jar 验证。

## 推荐

推荐走方案 C，但拆成两个提交或两个阶段：

1. UI 和命令转换阶段：
   - `StartAnaDialog` 增加批量上下文设置入口。
   - `AnalysisSettings` 支持显式普通/批量上下文。
   - 新增已保存引擎选择入口。
   - 新增 token 级命令转换 helper 和单元测试。
2. 稳定性阶段：
   - 发送失败不再进入等待队列。
   - 批量请求发送移出 EDT。
   - UI 更新和批量切盘回到 EDT。
   - 增加最小 watchdog 或至少加入诊断日志。

这样做的边界清楚：第一阶段解决可见入口和命令生成，第二阶段专门处理卡死根因，避免把 UI 改动和并发修复混在一个不可审查的大 diff 里。

## 测试建议

新增或扩展以下测试：

- `KataGoRuntimeHelperTest`
  - 已保存 KataGo `gtp` 命令转换为 `analysis`。
  - 路径中包含 `gtp` 字符串时不误替换。
  - 已有 `-quit-without-waiting` 时不重复追加。
  - `analysis.cfg` 缺失但模板资源存在且目标目录可写时，复制本地模板生成 `analysis.cfg` 并返回转换后的命令。
  - `analysis.cfg` 缺失且无法生成时返回失败原因，而不是生成坏命令。
- `UtilsEngineSettingsTest`
  - 候选列表只包含可转换的本地 KataGo 引擎，或正确标注不可转换原因。
  - 未定制闪电分析命令时，默认使用 `lizzieyzy-next` 当前默认引擎生成 analysis 命令。
  - 用户选择已保存引擎或手动保存命令后，标记为已定制并保留原命令，不再自动跟随默认引擎。
  - 旧配置中已有非占位 `analysis-engine-command` 且缺少定制标记时，保守视为已定制，避免覆盖。
- `AnalysisEngineRequestTest`
  - `sendCommand` 失败时不把 request id 放入等待队列。
  - 批量范围仍遵守 `SNAPSHOT_NODE_KIND.md` 的最近 `SNAPSHOT` 锚点契约。
- Swing 行为可用较轻的界面行为测试：
  - `StartAnaDialog(true, ...)` 暴露设置入口。
  - 批量上下文保存 visits 时写入 `batchAnalysisPlayouts`，不误写 `analysisMaxVisits`。

手工验证：

- 选择已有本地 KataGo 引擎生成 analysis 命令，确认 `gtp -> analysis`、`gtp.cfg -> analysis.cfg`、追加 `-quit-without-waiting`。
- 从未手动配置过闪电分析命令的配置，应默认跟随 `lizzieyzy-next` 当前默认引擎。
- 手动选择过闪电分析引擎或手动改过命令后，再修改 `lizzieyzy-next` 默认引擎，闪电分析命令仍保持用户改过的值。
- 缺少 `analysis.cfg` 的引擎会从本地预置的官方 `analysis_example.cfg` 模板自动生成同目录 `analysis.cfg`，并明确提示“缺少 analysis.cfg，已自动生成”。
- `analysis.cfg` 无法生成时应显示具体原因，不应静默回退到 `gtp.cfg`。
- 开始批量闪电分析前可以进入设置页。
- 预加载分析引擎开启时，修改命令后下一次分析实际使用新命令。
- 连续批量分析多盘 SGF，确认进度窗口可取消、可隐藏、不卡 EDT。

## 非目标

- 不在用户点击生成时联网下载 `analysis_example.cfg`。
- 不解析或智能改写用户 config 内容；自动生成 `analysis.cfg` 只复制随程序打包的本地模板。
- 首期不支持从远程引擎、iKataGo、LeelaZero 命令生成 analysis 命令。
- 不修改 `SNAPSHOT_NODE_KIND.md` 的历史/SNAPSHOT 契约。
- 不重做整个 `AnalysisEngine` 架构；只处理本任务暴露的启动、发送、进度和批量切盘问题。
