# 开发指南

这份文档面向准备改代码、修打包、补文档或继续维护这个仓库的人。

如果你只是普通使用者，优先看 [安装指南](INSTALL.md)、[排错指南](TROUBLESHOOTING.md) 和 [发布包说明](PACKAGES.md)。

## 先知道这几个事实

- 这是一个持续维护中的 LizzieYzy 分支，不是一次性补丁仓库。
- 当前最重要的用户链路是：能装、能开、能通过 **野狐昵称** 获取最新公开棋谱、能正常分析。
- 项目已有 Java 回归、发布脚本和多平台 CI 门禁；真实 GUI、显卡后端和签名安装包仍需对应平台手工验收。

## 本地构建

### 方案一：使用系统自带 Java / Maven

只要你本机有可用的 Java 和 Maven，就可以直接构建：

```bash
mvn -B -DskipTests package
```

### 方案二：使用仓库里的工具缓存

维护这个仓库时，通常优先使用仓库内已经准备好的工具：

- JDK: `.tools/jdk-21/jdk-21.0.10.jdk/Contents/Home`
- Maven: `.tools/apache-maven-3.9.10/bin/mvn`

示例：

```bash
export JAVA_HOME="$PWD/.tools/jdk-21/jdk-21.0.10.jdk/Contents/Home"
export PATH="$PWD/.tools/apache-maven-3.9.10/bin:$JAVA_HOME/bin:$PATH"
mvn -B -DskipTests package
```

构建完成后，当前常用输出包括：

- `target/lizzie-yzy2.5.3.jar`
- `target/lizzie-yzy2.5.3-shaded.jar`

## 本地桌面场景验收

Linux / WSL 可使用独立 Xvfb 显示运行真实 Swing 场景，不占用 WSLg 或 Windows 日常桌面。先准备 JDK 21、Maven、`xvfb`、`xauth` 和中英文字体。

```bash
python3 scripts/run_acceptance.py --scenario search --scenario settings
python3 scripts/run_acceptance.py --scenario quick-analysis --engine /absolute/path/to/katago --model /absolute/path/to/model.bin.gz
```

省略 `--scenario` 只运行默认的 `search`、`settings`、`quick-analysis`，需要同时提供真实 Linux KataGo 与模型。`search` 复用中英文真实输入链；`settings` 点击生产设置保存按钮，再用新 JVM 读取同一隔离配置；`quick-analysis` 从已可用的前台引擎导入 SGF，验证自动快析完成、共享引擎交回后的 visits 增长，以及快析期间用户暂停的保持。它不覆盖启动期间导入竞态或 Windows 原生文件选择器。

以下两个场景必须显式选择，不随默认场景运行：

```bash
python3 scripts/run_acceptance.py --scenario sgf-ui
python3 scripts/prepare_cpu_engine_acceptance.py --root target/acceptance/cpu-assets
python3 scripts/run_acceptance.py --scenario real-cpu-engine \
  --engine /absolute/path/from/manifest/to/katago \
  --model /absolute/path/from/manifest/to/model.bin.gz \
  --engine-config /absolute/path/from/manifest/to/default_gtp.cfg \
  --engine-manifest target/acceptance/cpu-assets/manifest.json
```

`sgf-ui` 用真实菜单、Swing 文件选择器和键盘操作完成打开、分支导航、受控 Java GTP 分析、另存为、取消覆盖、退出及新进程重开。它校验整棵 SGF 树的语义保留；重开定位遵循 `loadSgfLast` 的主分支语义，不要求保留保存前选中的旁支。

[Acceptance integration](../.github/workflows/acceptance-integration.yml) 在验收代码或资源目录变更时运行上述两个专项，也支持手动执行。它在独立 Linux/Xvfb 环境下载并核验目录锁定的 Eigen 引擎及模型，再保存真实 UI、GTP 和清理证据；不代表 Windows 原生桌面或自编译 GPU 后端已经验收。两平台脚本 CI 另行验证准备器和结果判定器的失败路径。

CPU 准备器按 `src/main/resources/katago-assets.json` 校验 Linux Eigen 引擎归档和模型，提取归档内的配置，并输出包含实际路径及哈希的 `manifest.json`。上例三个资源路径必须取自该 manifest；已有 manifest 的输出根目录会被拒绝。准备阶段可能联网，运行阶段不下载资源。`real-cpu-engine` 通过生产引擎管理器启动真实 KataGo，验证规则和快照盘面、当前节点正访问量、停止静默窗口以及进程／reader／临时 SGF 清理。

这是功能验收，不是吞吐基准。默认旗舰 Transformer 在托管 Eigen CPU 上曾耗时约 83 秒才输出零访问量根节点候选，因此冷搜索预算为 300 秒，整个探针上限为 450 秒；仍必须收到合法候选的正访问量并写入冻结的当前节点，零访问量、超时或跳过均不能通过。原始 GTP trace 和 `cpu-timing.json` 分别保留首批候选、首个有效搜索的证据，不修改模型、归档配置或正式应用的等待参数。

每个资源下载的 socket 等待上限为 30 秒，传输预算为 600 秒；在每次读取前后检查预算，已开始的单次读取最多再等待一个 socket 时限。停滞或超预算会失败并移除 `.part`，不会发布该资源或 manifest；已有校验通过的缓存仍可复用。

离线验收需要将整个 runner、应用和引擎进程树置于已验证的断网环境，使用相同资源和新的 `--output` 目录再执行一次；普通运行成功本身不证明离线可用。Linux/Xvfb 结果不代表 Windows/macOS 原生文件选择器或最终发布包验收。

每次运行创建新的 `target/acceptance/run-*`，保留 `acceptance.json`、Maven/JUnit 日志、子 JVM 配置、阶段记录和窗口截图。`--output` 可指定不存在的证据目录；`--timeout` 限制整体耗时。缺少引擎资源为 BLOCKED，缺少或跳过必跑用例不会成为 PASS。摘要记录源码 SHA 和工作树状态；未提交源码的本地结果不是已提交 Windows 候选的验收证明。

同一 checkout 的 Maven 调用应串行运行。此入口不改变现有 CI 分组，也不替代原生 Windows、GPU、IME 或发布包验收。

## 本地快速跑测试

日常改动可以先只跑相关测试，并把工作目录指到临时目录：

```bash
mvn -B -q -Dfmt.skip=true -Djava.awt.headless=true -Dlizzie.work.dir="$(mktemp -d)" \
  -Dtest='SomeTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

完整单测默认在一个 JVM 里串行执行（与 CI 相同）。想更快地本地跑全量，可以每个测试类用一个独立 JVM、
占用一半 CPU 核心并行（约快一倍）：

```bash
mvn -B -Dfmt.skip=true -Djava.awt.headless=true -Dsurefire.forkCount=0.5C -Dsurefire.reuseForks=false test
```

少数依赖时序的测试在高负载下仍可能偶发失败；以串行结果和 CI 为准。

## 本地一键 CI 预检

提交前建议运行与 GitHub Actions 同源的本地预检。它会校验 JDK 21、执行完整
Maven `verify`、打包辅助脚本、换行和链接检查，并把准确的 JUnit 数量写入
`target/local-ci/`。

Windows：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run_local_ci.ps1 -Profile Windows
```

macOS / Linux：

```bash
bash scripts/run_local_ci.sh --profile portable
```

推送前的干净工作树复核：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run_local_ci.ps1 -Profile Windows -RequireClean
```

可用 profile 为 `Windows`、`Portable` 和 `All`；加 `-DryRun` / `--dry-run`
可只查看计划执行的步骤。`LIZZIE_PYTHON`、`LIZZIE_MAVEN`、`LIZZIE_BASH`
和 `LIZZIE_POWERSHELL` 可用于指定工具路径。

按职责选择 `--group all|repository|scripts|java|desktop|engine-process`（PowerShell 为
`-Group All|Repository|Scripts|Java|Desktop|EngineProcess`），默认 `all` 保持原完整 headless 调用，
不包含 `desktop` 或 `engine-process`。所有组都需要 Python 和 Git，并执行 `git diff --check`；
`--require-clean` / `-RequireClean` 保留运行前后的干净工作树检查。

| Group | Portable | Windows | 额外工具 |
| --- | --- | --- | --- |
| `repository` | 换行自测、换行、Markdown 链接 | 换行 | 无 |
| `scripts` | Python 辅助脚本、KataGo shell、Bash 语法 | JCEF、NVIDIA、RTX50 PowerShell 语法、原生 CI 进程监督检查 | Portable 需 Bash；Windows 需 PowerShell 7 |
| `java` | 原完整 Maven verify | 凭据专项，再执行原完整 Maven verify | Maven、JDK 21；不查找 Bash/PowerShell |
| `desktop` | 七个测试类覆盖生产窗口导航 / 搜索输入 / 离线棋盘 / 引擎进程生命周期 / 更新窗口 | 相同显式测试选择；不代表 Windows 原生 UI 验收 | Maven、JDK 21、可用显示环境；Linux 使用 Xvfb |
| `engine-process` | 七个真实引擎进程生命周期 / 失败恢复用例 | 相同七个用例的原生 Windows 执行 | Maven、JDK 21、可用显示环境；Linux 使用 Xvfb |

例如仅运行无 Java 的仓库检查：

```bash
bash scripts/run_local_ci.sh --profile portable --group repository --summary-dir target/local-ci/portable-repository
```

```powershell
pwsh -File scripts/run_local_ci.ps1 -Profile Windows -Group Scripts -SummaryDir target/local-ci/windows-scripts
```

需要在 Windows 额外执行 Portable 辅助检查时，可显式选择 `-Profile All`。
它需要原生 Git Bash，以及创建符号链接的权限：归档安全测试会创建真实符号链接，
无此权限的 Windows 会在 fixture 准备阶段报 `WinError 1314`，不能通过跳过该测试来验收。
可改在 Linux / WSL 的独立 Linux 文件系统 checkout 中执行 `--profile portable --group scripts`；
CI 的 Portable 脚本组也在 Ubuntu 执行。Windows profile 通过不代表 Portable 脚本组通过。

`profile=all` 合并并去重两套检查，只执行一次 Windows 全量 verify 和凭据专项，
不表示跨 OS 验收，也不替代 Windows 与 Ubuntu 的独立 CI 门禁。
不要在同一 checkout 并行运行两个写入 `target` 的 Maven 调用；需要并行时使用独立 worktree。

多次分组调用请使用不同 summary 目录；默认仍为 `target/local-ci/`。
JSON/Markdown 摘要增加 `group`；非 Java 组不清理或读取既有 JUnit 报告，
Java/JUnit 状态为未执行。真实 Java 调用先清除旧 Surefire/Failsafe 报告，再收集本次结果。
Dry-run 仅生成计划，不代表检查通过。

Portable 脚本组逐文件执行 Bash 语法检查，失败步骤直接显示脚本路径。
两平台 Java gate 使用 Failsafe 默认命名发现集成测试，不再限定单一类；集成测试为空会失败。
执行器还检查本次 XML 中 `LoggingProviderSmokeIT.shadedArtifactWritesOneProviderEvent`
确实成功执行：关键用例缺失，或关键类任一用例 skipped/失败/报错，均使预检失败并写出失败摘要。
其他需要显示环境的可选用例仍可在 headless gate 跳过；不固定全仓测试或 skip 数量。

`desktop` 必须有可用显示环境且 `java.awt.headless=false`；缺失显示、关键用例缺失或 skipped/失败/报错均失败。
Linux 安装 `xvfb xauth fonts-dejavu-core fonts-noto-cjk` 后运行：

```bash
LANG=C.UTF-8 LC_ALL=C.UTF-8 xvfb-run -a -s '-screen 0 1920x1080x24 -nolisten tcp' bash scripts/run_local_ci.sh --profile portable --group desktop --summary-dir target/local-ci/portable-desktop
```

该组要求以下用例全部成功执行（含导航重复运行和自动下载参数化场景）：

- `FunctionSearchNavigationTest.navigationPreservesRealStateAcrossNativeAndCustomMenus`
- `ConfigDialog2NavigationTest.blackWinrateRemainsReachableAcrossRebuildsAndRecreation`
- `EngineProcessSmokeTest.restoresSnapshotAnalyzesAndQuits`
- `FunctionSearchInputTest.chineseInputChain`
- `FunctionSearchInputTest.englishInputChain`
- `OfflineBoardAcceptanceTest.editsAndImportsWithNoEngine`
- `CheckUpdateDialogTest.preferencesAndResultSurviveClosingAndReopeningWithoutAnotherCheck`
- `CheckUpdateDialogTest.automaticOfferIsViewOnlyUntilUpdateNowEvenAfterReopening`（自动下载关闭 / 开启）
- `UpdatePauseRecoveryTest.windowsManualDownloadCanResumeAfterClosingItsView`
- `UpdatePauseRecoveryTest.packageManualDownloadCanResumeAfterClosingItsView`
- `UpdatePauseRecoveryTest.recoveringAutomaticDownloadDoesNotGrantCompletionAuthority`

两个更新窗口测试类在 headless Java 作业中跳过，在 `desktop` 作业中必须实际执行全部六个用例。
它们验证偏好与更新结果在关闭 / 重开窗口后保留、点击“立即更新”前不授予安装权限，以及暂停下载的恢复。
缺失或跳过任一用例均使 desktop gate 失败；受控下载服务不替代 Windows 原生安装与重启验收。

两个搜索输入用例在独立的 zh_CN/native 和 en_US/custom 子 JVM 中，通过真实 Robot 输入覆盖工具栏、
`Ctrl+K`、查询、方向键、Enter、Escape、文本组件所有权和模态窗口边界。引擎进程用例在独立子 JVM 中
通过生产 `EngineManager` 启动受控 Java GTP peer，验证 removed-stone `SNAPSHOT`、真实 MOVE/PASS tail、
当前节点分析、停止、正常退出以及 owned process/reader/临时 SGF 清理。该 peer 的直接本地文件访问只使用
测试专用信任 seam；不证明自动 transport classification 或 remote/WSL/SSH 路径。任一必需用例缺失、
skipped、失败或报错都会使实际 runner 失败；失败摘要仍保留本次已收集的测试、失败、报错和跳过计数。
每个子 JVM 的独立工作目录、配置、应用日志、stdout/stderr、locale（如适用）、阶段、场景结果和设置前后
证据保留于 `target/desktop-smoke/probes/`。
每个探针等待最多 90 秒；超时后分别尝试有界 `jcmd` 栈和截图，再终止所拥有的进程，不依赖 EDT 响应。
诊断不可用会记录失败，不掩盖探针失败。父进程不创建子进程输出读取线程。
desktop 的新鲜 XML 单独放在 `target/desktop-smoke/surefire-reports/`；只清除该 lane 的旧报告，保留探针日志。
原 headless `all` / `java` 契约及其报告保持不变；headless Java 报告与 desktop 报告互不替代，
所有调用仍须遵守单 checkout 单 Maven 写入约束。
这证明 Linux/Xvfb 中的生产窗口导航和搜索输入链，不替代 Windows DPI/IME/theme、原生
Windows/macOS 快捷键行为或引擎/GPU 验收。

`engine-process` 单独执行 `EngineProcessSmokeTest` 的成功生命周期以及
`EngineProcessFailureTest` 的六个错误、超时、崩溃恢复、切换隔离、双管道排空和拒绝退出场景。
它不包含 Robot 输入用例，且将 XML 与探针证据分别写入
`target/engine-process-smoke/surefire-reports/` 和 `target/engine-process-smoke/probes/`，不会消费
`desktop` 或 headless Java 的报告。七个指定方法必须全部成功；显示缺失、方法缺失、skip、失败或报错均失败。
Linux 本地运行：

```bash
LANG=C.UTF-8 LC_ALL=C.UTF-8 xvfb-run -a -s '-screen 0 1920x1080x24 -nolisten tcp' bash scripts/run_local_ci.sh --profile portable --group engine-process --summary-dir target/local-ci/portable-engine-process
```

Windows 必须在带原生桌面会话的 Windows 主机中运行：

```powershell
pwsh -NoProfile -File scripts/run_local_ci.ps1 -Profile Windows -Group EngineProcess -SummaryDir target/local-ci/windows-engine-process
```

设置 `java.awt.headless=false` 不会替代可用显示；显示不可用时 runner 必须失败，不能以 skip 通过。


Actions 的 `ci.yml` 对所有 PR（包括仅文档改动）和 main push 执行原有六个 job，另有
`engine-process` 的 Linux/Xvfb 与原生 Windows matrix：`repository-checks`、`script-tests`、
`windows-script-tests`、`java-linux`、`java-windows`、`desktop-smoke`，以及两个进程 matrix leg。
Windows 脚本 job 依次运行 `windows/repository` 与 `windows/scripts`，摘要独立上传。
两平台 Java job 保留全量测试、`LoggingProviderSmokeIT`、shaded JAR 和 JaCoCo；
验证成功但 coverage artifact 缺失仍失败。各组失败时仍尝试上传本组摘要；两个进程 leg 还始终上传
`target/engine-process-smoke` 下的报告、日志和探针证据。

`ci-required` 是唯一汇总门禁，要求原有六项和 `engine-process` matrix 全部成功，拒绝失败、取消、
跳过、缺失或未知结果。发布仍仅接受目标 SHA 的完整 `ci.yml` push 成功运行。

main 分支保护要求 GitHub Actions 来源的 `ci-required`。回退 workflow 时必须保留
可运行且真实验证六项执行结果的 `ci-required`；不能直接回退到缺少该检查的版本。
如需调整 required checks，由维护者协调保护设置与 workflow，始终保留有效合并门禁。

本地预检用于在推送前尽早发现问题，不能代替受保护分支上的干净 Windows 和
Ubuntu runner，也不能代替 macOS 签名、公证与多平台发布资产审计。

如果你改了打包、引擎路径、首次启动流程、野狐抓谱流程，建议再做对应平台的手工验证。

### Windows Java 停滞取证

`java-windows` 使用 `scripts/run_windows_ci_diagnostics.ps1` 包裹原有完整 Java 检查，
不重试失败用例，也不把抓栈成功视为测试通过。job 上限仍为 30 分钟：第一步记录
27 分钟的绝对截止时间，监督器取它与自身 23 分钟预算的较早值，运行步骤另设
25 分钟上限，为摘要与 artifact 上传留出时间。

测试 JVM 通过 test-scope JUnit listener 逐条刷新 `PLAN_STARTED`、`START`、`FINISH`、
`SKIPPED`、`PLAN_FINISHED` 事件，包含 PID、时间、JUnit unique ID 和用例名称。
只有设置了 `LIZZIE_CI_EVIDENCE_DIR` 才启用；普通本地测试不写取证文件。
监督器仅给受监督进程设置该变量，读取完整 JSONL 行并按 PID 跟踪嵌套测试计划。
存在活动计划时，控制台输出不算测试进展。

默认 120 秒无进展后，保留两次相隔 30 秒的进程/CPU 快照，并对所属 JVM 调用
`JAVA_HOME/bin/jcmd.exe Thread.print -l`，单次最多等待 10 秒。第一次停滞取证不终止
任务，同一段未恢复的停滞不重复抓取；到执行预算前也会尝试取证。截止时终止所属
Windows Job Object，独立写出失败摘要，即使 Python 执行器尚未生成自己的摘要。
命令正常完成则保留退出码；命令退出后仍留有子进程会失败并清理，不误报成功。

Actions 始终尝试上传 `windows-java-diagnostics`（保留 14 天），包含：

- `target/ci-diagnostics/summary.json`：退出状态、预算、运行身份及进程清理结果。
- `console.stdout.log`、`console.stderr.log` 和 `junit-<pid>.jsonl`：原始控制台与用例事件。
- `snapshot-<episode>-<1|2>.json` 和成功抓取的 `threads-<episode>-<1|2>-<pid>.txt`。
- `target/surefire-reports`、`target/failsafe-reports` 中已落盘的测试报告。

先从 JSONL 找最后启动但未结束的用例，再比较两份线程栈与 CPU 时间。`jcmd` 缺失或
失败会记入快照，仍保留第二份进程快照和其余证据。原始日志没有通用秘密脱敏；
不要让测试输出凭据。runner 丢失、强制取消或磁盘写入失败时，不能保证摘要及上传完成。

本地需要 Windows、PowerShell 7、Python、Maven 和 JDK 21。在干净工作树运行：

```powershell
pwsh -NoProfile -File scripts/run_windows_ci_diagnostics.ps1 -OutputDirectory target/ci-diagnostics-local
```

输出目录必须不存在或为空；再次运行请换新目录，避免混入旧证据。
原生监督器回归检查由 `windows/scripts` 组执行，无需启动 Maven 或 JDK。


## 换行规则

- 仓库中的文本文件统一使用 LF，不受开发者操作系统或 `core.autocrlf` 配置影响。
- 只有 Windows 命令脚本 `.bat`、`.cmd` 在工作区使用 CRLF；PowerShell、Shell、Java、Python、Markdown 和资源文件均使用 LF。
- `.gitattributes` 是 Git 层面的最终规则，`.editorconfig` 用于让常见编辑器在保存时提前遵守规则。
- 日常功能 PR 不要运行全仓 `git add --renormalize .`，也不要混入无关的 `fmt:format` 改写。

## Java 格式

- Java 代码统一使用 google-java-format（`fmt-maven-plugin`）。CI 的 java-linux 任务会运行 `fmt:check`，格式不符直接失败。
- 提交前运行 `mvn -B -q com.spotify.fmt:fmt-maven-plugin:format` 自动格式化；全仓已格式化过一次，所以它只会改动你修改的文件。
- 全仓格式化的提交记录在 `.git-blame-ignore-revs`。本地执行一次 `git config blame.ignoreRevsFile .git-blame-ignore-revs`，`git blame` 就会跳过它；GitHub 的 blame 页面会自动跳过。
- CI 会运行 `python3 scripts/check_line_endings.py`，发现混合换行或错误行尾时直接失败。

## 仓库结构速览

### 代码目录

- `src/main/java/featurecat/lizzie/gui`
  - 主要界面、对话框、窗口、交互逻辑
- `src/main/java/featurecat/lizzie/analysis`
  - 引擎集成、分析流程、远程连接、野狐抓谱相关逻辑
  - 包括 `GetFoxRequest.java` 这类和野狐请求直接相关的实现
- `src/main/java/featurecat/lizzie/rules`
  - 棋盘、落子、SGF / GIB 解析、局面数据结构
- `src/main/java/featurecat/lizzie/util`
  - 常用工具类
- `src/main/java/featurecat/lizzie/theme`
  - 主题相关逻辑

### 资源目录

- `src/main/resources/l10n`
  - 多语言文案资源，例如 `DisplayStrings_zh_CN.properties`
- `src/main/resources/assets`
  - 内置资源、工具依赖、辅助素材
- `theme/`
  - 主题资源文件

### 打包与发布相关目录

- `scripts/`
  - 打包脚本、工具脚本
- `runtime/`
  - 打包时用到的运行时内容
- `engines/`
  - 内置引擎相关文件
- `weights/`
  - 默认权重文件
- `dist/`
  - 打包输出相关目录

### 文档与仓库配置

- `docs/`
  - 安装、排错、维护、发布、开发等文档
- `.github/`
  - CI、issue 模板、PR 模板、release 配置
- `assets/`
  - GitHub 项目页使用的视觉素材

## 常见改动从哪里开始

### 1. 改野狐抓谱或 野狐昵称 相关流程

优先看：

- `src/main/java/featurecat/lizzie/analysis/`
- `src/main/java/featurecat/lizzie/gui/`
- `src/main/resources/l10n/`

除了代码本身，还要确认：

- 界面里仍然写的是“野狐昵称 / Fox nickname”
- README 和安装文档没有回到旧的 UID / 用户名说法
- 至少做一轮真实抓谱验证

### 2. 改界面文案、多语言、菜单入口

优先看：

- `src/main/resources/l10n/DisplayStrings*.properties`
- `src/main/java/featurecat/lizzie/gui/`

建议同时确认：

- 中文、英文术语一致
- `with-katago`、`nvidia`、`without.engine` 拼写保持一致
- 用户可见文案变化是否需要同步 README 或安装文档

### 3. 改打包、内置引擎、发布资产

优先看：

- `scripts/prepare_bundled_runtime.sh`
- `scripts/prepare_bundled_katago.sh`
- `scripts/package_release.sh`
- `scripts/package_macos_dmg.sh`
- [发布检查清单](RELEASE_CHECKLIST.md)

这类改动通常还要同步：

- `README.md`
- `docs/PACKAGES.md`
- `docs/TESTED_PLATFORMS.md`
- GitHub Releases 说明

## 当前打包脚本分工

- `scripts/prepare_bundled_runtime.sh`
  - 准备带运行时的整合包内容
- `scripts/prepare_bundled_katago.sh`
  - 准备内置 KataGo 和默认权重
- `scripts/package_release.sh`
  - 生成 Windows / Linux / 进阶 zip 包
- `scripts/package_macos_dmg.sh`
  - 生成 macOS `.dmg`
- `scripts/validate_release_assets.sh`
  - 检查 `dist/release/` 里是否只剩普通用户应该看到的主资产
- `scripts/check_markdown_links.py`
  - 检查本地 Markdown 链接是否失效

## 提交前建议再看一眼

- 用户主链路有没有被影响：安装、启动、野狐昵称抓谱、分析
- 文案有没有回到旧的 UID / 用户名说法
- 发布包名、README、文档是否仍然一致
- 如果改了打包流程，是否补了对应说明和验证记录
- 如果改了界面，是否应该补截图

## 建议搭配阅读

- [贡献指南](../CONTRIBUTING.md)
- [维护说明](MAINTENANCE.md)
- [发布检查清单](RELEASE_CHECKLIST.md)
- [发布包说明](PACKAGES.md)
- [已验证平台](TESTED_PLATFORMS.md)
