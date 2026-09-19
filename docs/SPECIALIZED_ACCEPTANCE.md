# 专项验收契约

常规 CI、Windows 原生桌面、真实 GPU、完整打包分别给出结论。一次运行只证明其实际执行的场景、提交和环境；本页定义如何选择验收及记录证据，不是所有平台已经通过的声明。

## 按改动风险选择验收

| 层级 | 触发条件 | 运行条件与入口 | 通过含义 |
| --- | --- | --- | --- |
| 确定性 UI / 状态回归 | 分组、窄宽度重排、长文案、修复/启用资格与副作用变更 | JDK 21、Maven、可写的隔离工作目录；下述现有 JUnit 随两平台 Java gate 执行 | 被执行的布局与状态契约通过；fixture 仅证明受控输入下的行为 |
| Windows 原生桌面 | 窗口尺寸、主题、字体、DPI、滚动、焦点、按钮可达性等可见行为变更 | Windows 原生 JVM、交互桌面、实际产品主题、100% / 150% / 200% 显示缩放；启动指定 SHA 的 shaded JAR | 指定主题、缩放、窗口尺寸下实际观察的场景通过 |
| 真实 GPU | GPU 检测、安装、修复、显式启用、后端切换变更 | 对应 GPU/驱动、真实 KataGo/backend、权重与配置、必要的下载网络/代理、隔离可修复目录 | 实际组件操作、前台引擎身份与启动分析结果满足场景；纯布局变更不自动要求整套 GPU 流程 |
| 完整打包 / 发布 | 打包脚本、原生资源、运行时、启动器、安装/升级、签名、资产身份或上传变更 | 目标 OS/架构及平台 workflow 所需工具、真实资源、网络；签名/发布需要对应凭据和授权 | 选定真实资产的组装、审计、平台 smoke，以及实际执行的签名/发布步骤通过 |

先列触发的层级和场景，再检查工具、平台、服务与权限。缺少前提时记录具体原因并完成独立可执行项。凭据只记录“可用/不可用/未检查”，不记录值。普通 PR 不新增 GPU runner；已有 headless 测试留在常规 CI，不另跑同一清单制造独立 UI 绿勾。

## 确定性覆盖与执行边界

下表按可观察契约引用已有测试；方法名用于定位，不是额外的 CI 测试清单。

| 目标契约 | 已有证据 | 边界 |
| --- | --- | --- |
| 分组内按钮可见、提示与动作不重叠 | [KataGoAccelerationLayoutTest](../src/test/java/featurecat/lizzie/gui/KataGoAccelerationLayoutTest.java)：`maintenanceActionsWrapWithoutLeavingTheirGroup`、`hintAndActionsDoNotOverlapInNarrowBlock`、`experimentalSelectorAndButtonAreStacked` | 使用生产布局在 EDT 排列轻量 Swing 组件，检查边界和相对位置；不证明完整原生对话框的主题绘制 |
| 窄宽度重排及再次放大/缩小 | 同类：`narrowStatusRowUsesTheFullWidthInsteadOfA24PixelValueColumn`、`statusRowReturnsToColumnsAfterGrowing`、`growingAndShrinkingReflowsBothDirections`、`viewportWidthIsRespected` | 检查实际组件尺寸与重排，不是截图或 DPI 验收 |
| 文字完整、最后一行可见 | 同类：`allEightResourceBundlesKeepNarrowStatusAndHintsVisible`、`wrappedHeightIncludesSwingsCaretMarginAtLineBreakBoundaries`；[KataGoAutoSetupDialogLayoutTest](../src/test/java/featurecat/lizzie/gui/KataGoAutoSetupDialogLayoutTest.java)：`localizedButtonWidthIncludesTheEntireThaiLabel`、`longLocalizedWeightActionsWrapWithoutClipping`、`wrappedStatusCanShrinkAgainAfterBackendSwitch` | 文本布局测试含 `modelToView2D` 的末行边界；放大字体是受控输入，不是 Windows 150%/200% 原生证据 |
| 窗口工作区适配、权重动作重排 | `KataGoAutoSetupDialogLayoutTest`：`dialogShrinksBelowItsDesktopMinimumAtHighDisplayScaling`、`dialogPlacementStaysInsidePositiveAndNegativeMonitorCoordinates`、`weightActionsStayInlineAtTheDefaultDialogWidth` | 几何计算与生产行布局；真实显示器工作区、系统缩放及窗口装饰另验 |
| 修复与启用资格分离 | [TensorRtAccelerationViewTest](../src/test/java/featurecat/lizzie/gui/TensorRtAccelerationViewTest.java)：`readyComponentsWithInactiveProfileStayDistinguishable`、`incompleteWeightAndGtpDoNotBlockRepairAndAreListedForEnable`、`missingActivationItemsAreExposedOnTheAccessibleEnableDescription` | 验证组件就绪、profile 未启用和缺失项目的可观察状态；不以字符串键断言代替动作执行 |
| 修复不改 profile，只有显式启用写入 | [TensorRtComponentRepairTest](../src/test/java/featurecat/lizzie/util/TensorRtComponentRepairTest.java)：`missingRuntimeEngineAndCompanionRepairToReadyWithoutChangingProfiles`、`onlyEnableTensorRtWritesTheProfileAndMissingItemsBlockActivation` | 真实修复/启用方法使用临时配置与受控资源 fixture，检查文件和 profile 前后状态；模拟 Windows/GPU 输入，不启动真实 DirectML/TensorRT 引擎 |

上述四类均可在 headless 下运行。两平台 [ci.yml](../.github/workflows/ci.yml) 的 `java-linux` / `java-windows` 通过 [run_local_ci.py](../scripts/run_local_ci.py) 执行完整 Maven `verify`，均显式指定 `-Djava.awt.headless=true`、`-DskipTests=false`，没有为这些类另设排除。它们使用 Surefire 默认识别的 `*Test` 命名，留在 [pom.xml](../pom.xml) 的常规测试生命周期；shaded JAR、`LoggingProviderSmokeIT` 和 JaCoCo 仍由原 Java gate 负责。

需要显示器的反例：[WholeGameAnalysisDialogLayoutTest](../src/test/java/featurecat/lizzie/gui/WholeGameAnalysisDialogLayoutTest.java) 的窗口/缩放/输入动作测试先调用 `assumeFalse(GraphicsEnvironment.isHeadless())`。因此两平台全量 headless gate 不执行这些窗口断言。其他带 assumptions 的测试也以该次 JUnit XML 中的实际 skip 为准；不得固定全仓库 skips 数量，或把 skipped 算作通过。

### 聚焦复核

在仓库根目录运行（WSL 先 `unset DISPLAY`；PowerShell 不执行这一行）：

```bash
mvn -B -Dfmt.skip=true -Djava.awt.headless=true -Dtest=KataGoAccelerationLayoutTest,KataGoAutoSetupDialogLayoutTest,TensorRtAccelerationViewTest,TensorRtComponentRepairTest,WholeGameAnalysisDialogLayoutTest test
```

若需要隔离应用工作目录，增加 `-Dlizzie.work.dir=<独立目录>`。同一 checkout 不并行运行写入 `target` 的 Maven 调用。完整平台入口和分组使用方式见 [开发指南](DEVELOPMENT.md)，不要用上述聚焦命令替换完整 Java gate。

记录：目标 SHA、源码是否有未提交修改、OS/JDK/Maven、headless 值、命令、开始/结束时间、退出码、逐类 tests/failures/errors/skipped、实际跳过的测试及 assumption、Surefire XML/日志路径；hosted 运行另附 run URL、event、head SHA、job 与 artifact。dry-run 只记录命令规划成功。

覆盖盘点只有两种完成结果：目标确定性契约已有覆盖且无确认缺口；或所有确认缺口已补成行为回归、进入两平台 Java gate 并取得测试/hosted 证据。若缺口需要修改业务实现，记录可达行为与阻塞，先确认业务修复范围，不能仅列候选便关闭补缺任务。

## Windows 原生桌面

先固定完整 SHA，在独立 checkout 中按[开发指南的本地构建步骤](DEVELOPMENT.md#本地构建)生成候选，保存源码 SHA、构建命令、工具版本与构建日志。记录 shaded JAR 路径、启动时间、PID、JVM 命令、窗口标题和隔离配置目录，使截图能对应到实际构建与进程。维护机器的 candidate 工具可用时，保留其 `candidate.json`、`run.json`；其他机器记录同等证据即可。启动 shaded JAR；普通 JAR 没有 `Main-Class`。

每个实际产品主题分别列 100% / 150% / 200% 档位，记录主题名称、浅/深模式和版本。按改动风险选择默认宽度、窄宽度、放大再缩小、长文案、分组及滚动场景；同一场景逐档观察：

1. 打开受影响页面，确认 TensorRT 主动作、维护动作、实验后端组可见且彼此分开。
2. 缩窄再放大，检查动作重排、状态/提示不重叠、末行及按钮文字完整；必要时滚动到各组并记录位置。
3. 用鼠标与键盘触发受影响动作，观察禁用态、焦点和动作语义。涉及真实安装/切换时同时执行下一节 GPU 场景。

桌面记录每行必须填写以下字段：

| 场景 | 构建 SHA / artifact / run.json | Windows / JVM / 主题 / 语言 | 缩放与 DPI | 窗口尺寸 | 用户动作 | 预期 | 实际 | 截图 | 状态 / 原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 一个场景及一个缩放档位 | 完整 SHA、shaded JAR 路径及进程身份 | 具体版本与实际主题 | 100%/150%/200% 及实际 DPI | 外框和内容区宽×高，注明逻辑/物理像素 | 按顺序记录 | 可判定的结果 | 实际观察，不复制预期 | 可访问的图片路径或链接 | PASS / FAIL / BLOCKED / NOT RUN |

未执行档位逐项写 `NOT RUN`；缺少桌面/设备写 `BLOCKED`；决定放弃某档位仍写 `NOT RUN` 并附决定者、日期与原因，不计 PASS。WSLg、Xvfb、headless、手工放大 Swing 字体均不替代 Windows 原生实际主题与缩放证据。

## 真实 GPU

运行前记录：应用完整 SHA/候选身份、Windows 版本、GPU 型号及设备、驱动版本、KataGo 与 backend/runtime 版本、模型/配置、初始前台引擎、网络/代理可用状态和隔离目录。硬件不匹配则对应场景保持 `BLOCKED`；不操作日常用户的引擎文件或凭据。

对于 TensorRT 修复/启用边界，至少保留以下完整序列：

1. 在真实 DirectML 前台开始分析，保存实际命令、进程/引擎身份和一段分析输出作为基线。
2. 在隔离目录制造场景所需的 TensorRT 缺失组件，记录缺失项；从应用点击修复 TensorRT，保存开始、阶段、结束或错误日志。
3. 修复完成后确认组件状态正确，前台仍是原 DirectML 引擎，profile 未被暗中切换，并再次取得 DirectML 分析输出。修复成功不是启用成功。
4. 用户显式点击启用 TensorRT；记录此次动作和实际切换结果，保存真实 TensorRT 启动日志（可辨认 backend/GPU/模型）以及对应分析结果。只有写入 profile 或出现成功提示不能证明启用完成。
5. 其他受影响检测、安装、取消/失败、后端切换场景按改动风险单列；保留失败现场后再恢复隔离环境。

GPU 记录每行包含：场景 ID、SHA/构建身份、硬件/驱动/KataGo/backend/runtime、初始状态、带时间的动作序列、预期、实际、前后引擎身份、真实启动与分析日志路径及时间段、截图、PASS/FAIL/BLOCKED/NOT RUN 和原因。日志发布前脱敏，保留判断引擎身份与分析结果所需信息。PowerShell parser、资源下载 fixture、GPU 检测 fixture 均只证明其受控范围，不证明硬件链路。

## 完整打包与发布

真实入口与参数以目标提交的 workflow 为准：

| 平台 | Workflow | 组装与后续检查 |
| --- | --- | --- |
| Windows | [build-windows-release.yml](../.github/workflows/build-windows-release.yml) | [package_windows_exe.sh](../scripts/package_windows_exe.sh)、[validate_release_assets.sh](../scripts/validate_release_assets.sh)；最终产品用 [windows_product_acceptance.ps1](../scripts/windows_product_acceptance.ps1)，低层 smoke 与生成 MSI 回归分别保留 [windows_smoke_test.ps1](../scripts/windows_smoke_test.ps1)、[windows_upgrade_smoke.ps1](../scripts/windows_upgrade_smoke.ps1) |
| Linux | [build-linux-release.yml](../.github/workflows/build-linux-release.yml) | [package_release.sh](../scripts/package_release.sh)、资产内容审计与目标桌面启动 |
| macOS | [arm64](../.github/workflows/build-macos-arm64-release.yml) / [amd64](../.github/workflows/build-macos-amd64-release.yml) | [package_macos_dmg.sh](../scripts/package_macos_dmg.sh)、[sign_macos_release_with_retry.sh](../scripts/sign_macos_release_with_retry.sh)、资产审计和原生安装/启动 |

先检查目标平台工具和 workflow 声明的下载/签名/发布条件。平台 smoke 使用可丢弃的配置；执行前检查其配置清理选项。签名是否执行、为何跳过、验证结果按 [macOS 签名说明](MACOS_SIGNING.md)单独记录，不用打包成功推断签名成功。

### 非发布候选构建与交接

四个候选 workflow 只构建和取证，不发布：

| 范围 | Workflow | 触发与产物 |
| --- | --- | --- |
| Windows | [candidate-windows.yml](../.github/workflows/candidate-windows.yml) | Windows 打包或共享 provenance/topology 改动；最终资产、producer provenance、构建元数据 |
| Linux | [candidate-linux.yml](../.github/workflows/candidate-linux.yml) | Linux 打包或共享 provenance/topology 改动；三个最终 ZIP、producer provenance、构建元数据 |
| macOS | [candidate-macos.yml](../.github/workflows/candidate-macos.yml) | macOS 打包或共享 provenance/topology 改动；arm64/amd64 各自最终 DMG、producer provenance、构建元数据 |
| standalone Java 17 | [candidate-java17.yml](../.github/workflows/candidate-java17.yml) | Java 17 gate、POM 或共享记录校验改动；JDK 21 构建的 exact shaded JAR、Temurin 17 static/application evidence |

这些 workflow 同时支持 path-filtered `pull_request` 和手工 dispatch，顶层权限固定为 `contents: read`，不声明 release environment，不读取签名/发布 secrets，不执行 `gh release upload`、R2 promotion 或其他发布动作。平台本地脚本改动只触发对应平台；共享 provenance/topology 改动可触发全部 build-only 平台任务。它们不属于通用 required matrix，是否设为分支必需检查须在稳定性和管理配置完成后另行决定。

候选交接顺序固定如下：

1. build host 用现有前台打包脚本生成最终资产，再生成同一 workflow attempt 的 `release-asset-provenance.json`；Actions artifact 只承载资产、provenance 和实现证据。
2. 把一个最终资产和整份 provenance 下载到目标 native host。不要传递 producer 上生成的 `candidate.json`，也不要把 staging/app-image 当候选。
3. consumer host 本地重新打开资产并生成唯一可启动的 `candidate.json`，因此其中所有绝对路径均属于 consumer：

```bash
python3 scripts/release_asset_provenance.py verify-candidate \
  --manifest <release-asset-provenance.json> \
  --platform <windows|linux|mac-amd64|mac-arm64> \
  --date-tag <YYYY-MM-DD> --release-tag <next-YYYY-MM-DD.N> \
  --target-sha <40-lowercase-hex> --run-id <positive-int> --run-attempt <positive-int> \
  --asset-name <exact-public-filename> --asset-file <local-final-asset> \
  --output <local-candidate.json>
```

4. native runner 只消费这份本机 `candidate.json`。Windows 使用下面的 `Prepare` / `Start` / `Status` / `Stop`；Linux/macOS 使用各自 runner。rebuild、资产漂移、producer 路径记录或 SHA 不一致均重新生成候选或失败，不能沿用旧记录。

多行 native 结果完成后，用同一校验器生成 phase-aware 汇总。row ID 固定为 `<platform>/<architecture>/<artifact-key>/<scenarioId>`；standalone Java 17 的 artifact key 为 `NOT_APPLICABLE`。命令拒绝错误目标 SHA、缺失、重复、意外行及任何不满足 PASS/FAIL/BLOCKED phase 规则的记录：

```bash
python3 scripts/release_asset_provenance.py validate-acceptance \
  --target-sha <40-lowercase-hex> \
  --require-row <platform/architecture/artifact-key/scenarioId> \
  --record <acceptance.json> \
  --output <acceptance-report.json>
```

每增加一条必需行，重复传 `--require-row` 与 `--record`。`BLOCKED` 只表示行为阶段开始前缺少外部前提；阶段开始后的断言、超时或配置问题是 `FAIL`。`PASS` 要求该场景所有必需观察、断言、证据与 cleanup 完整。workflow 构建成功或记录汇总成功都不自行声明 native product PASS。

源码编译/API baseline 和 standalone shaded JAR 支持目标为 Java 17；候选/Release 的正常构建 JDK 与 bundled runtime 仍为 Java 21。Java 17 workflow 先用 JDK 21 构建 exact shaded JAR，再让 `LoggingProviderSmokeIT` 的 child 和 verifier 显式使用 Temurin 17；static gate 失败时不继续把 application smoke 报成通过。

standalone Java 17 的 shaded JAR 尚未传输时，runner 只有在同时收到 `--expected-jar-size`、`--expected-jar-sha256`、`--expected-build-jdk-spec` 与 `--expected-created-by` 后，才写带精确请求身份的 identity-phase `BLOCKED` 记录；JAR 已存在时，任何传入的期望值都必须与实际文件及 manifest build identity 一致，否则 fail closed。

Windows 最终产品在原生主机按同一入口交接。`Prepare` 接收已经传到该主机的最终资产和 producer provenance，在本机运行 `release_asset_provenance.py verify-candidate`，然后安全解压或安装到新建的 Unicode/空格路径；它产出的本机绝对路径 `candidate.json` 和 `prepared.json` 才能用于后续命令。可复用会话的命令顺序为：

```powershell
./scripts/windows_product_acceptance.ps1 -Command Prepare -AssetFile <final> -ProvenanceFile <json> -Platform windows -DateTag <date> -ReleaseTag <tag> -TargetSha <sha> -RunId <id> -RunAttempt <n> -EvidenceDir <new-dir>
./scripts/windows_product_acceptance.ps1 -Command Start -CandidateJson <candidate.json> -Scenario live-session -EvidenceDir <dir>
./scripts/windows_product_acceptance.ps1 -Command Status -RunJson <run.json>
./scripts/windows_product_acceptance.ps1 -Command Stop -RunJson <run.json>
```

`Start` 在任何进程变更前复验 candidate、provenance、资产、launcher、runtime、JVM module、shaded JAR、installed manifest、backend marker、engine/config/model、JCEF 与 ReadBoard 闭包哈希，并原子写入 `RUNNING` 的 `run.json`；该记录绑定 launcher PID/image/command line、进程 incarnation、同进程实际加载的 packaged `jvm.dll`、数据目录及完整产品身份。`Status` 只读复验这些身份、owned PID 树、readiness 证据和离线连接状态；`Stop` 即使 launcher 已退出或产品文件已漂移，也先按已记录的 image/creation time 清理匹配的 owned 进程、逐项移除本次 firewall rules、精确恢复 launcher cfg 与 Windows audit policy，再写入 `STOPPED`、identity errors、survivors 和清理错误。离线验收对除 loopback 外的 IPv4/IPv6 地址使用 Windows Firewall 阻断，并以 Windows Security 5157 deny events 与活动连接的双重零计数取证；不能查询 Security log、连接或 firewall state 时失败或写严格 `BLOCKED`，不把查询错误当作空结果。

`Run` 执行 `portable-offline-first-run`、`installer-offline-first-run`、`installer-upgrade-preserve`、`core-update-preserve` 或 `variant-launch`，并写 phase-discriminated `acceptance.json`。缺少已传输 candidate 时，需同时传 `-TargetSha` 与 `-ExpectedArtifactKey/-ExpectedArtifactName/-ExpectedArtifactClass`，脚本才会写带精确请求身份的 `BLOCKED` 记录。MSI 使用 `/qn /norestart` 安装到 disposable Unicode/空格路径，拒绝未明确归属的既有 Lizzie 安装；升级必须证明 prior 与 candidate ProductCode 按预期 UpgradeUUID 注册且版本身份发生变化。core update 使用每次运行唯一且新建的目标，只修改 manifest-owned files、保持资源 sentinel，并真实启动更新后产品。

CPU 深验收的 `-EngineOracleFile` 是本次运行专用的新输出路径，不是预先存在的输入文件。runner 先启动产品并原子写入当前 `run.json`，外部 oracle producer 读取该记录和 packaged engine closure，完成 frozen D4 SNAPSHOT/MOVE/PASS、Chinese rules、positive-visits 与 400ms quiet-stop 场景后，再原子写入 envelope；runner 在有界时间内等待它。envelope 必须绑定 candidate、当前 `run.json` 哈希、launcher/runtime/JVM/JAR/data root、捕获到的 engine PID/command 和 canonical oracle payload 哈希，且 result/stdout/stderr/app log/phases 证据与 staged SGF 清理均可复验；孤立、提前写入或重放结果会失败。安装、升级和 outbound-deny 需要提升权限。`windows_upgrade_smoke.ps1` 仍只标记为 `generated-msi-regression`，不能替代真实 prior-to-candidate installer upgrade。

Linux 最终归档只消费 native host 本地生成的 `candidate.json`，并通过归档内 `start-linux64.sh` 启动，不直接调用 Java。先把最终 ZIP 与同一 workflow attempt 的 provenance 传到 Linux x86_64 主机，再运行 `release_asset_provenance.py verify-candidate`；随后为每个产品使用新的证据目录：

```bash
scripts/linux_product_acceptance.sh \
  --candidate <candidate.json> \
  --scenario <cpu-offline-first-run|variant-launch> \
  --evidence-dir <new-directory>
```

执行主机需要可用桌面或 Xvfb、`unshare` 的非特权 user/network namespace、`ip`、`nft`、`xwininfo` 与 `xwd`。runner 安全解压到带空格和非 ASCII 字符的新目录，复验单一顶层根、bundled Java x86_64、shaded JAR、backend marker、engine/config/weight，并通过 launcher 的 `LIZZIE_WORK_DIR` 环境 seam 把所有应用状态放入隔离目录。运行进程树进入保留 loopback 的私有 network namespace；nft 阻断并计数所有非 loopback IPv4/IPv6 尝试。`acceptance.json`、`run.json`、runtime 输出、launcher 日志、窗口树、截图、network rule/counter 与清理结果保留在证据目录。

Linux `candidate.json` 尚未传输时，需同时传 `--expected-target-sha` 与 `--expected-artifact-key/--expected-artifact-name/--expected-artifact-class`，runner 才写带 canonical Linux topology 身份的 identity-phase `BLOCKED` 记录；候选已存在时，传入期望值与候选不符会在解压和启动前失败。

CPU 深验收还需把 `LIZZIE_LINUX_ACCEPTANCE_ENGINE_ORACLE` 设置为本次运行专用、尚不存在的 envelope 输出路径。runner 发布 live `run.json` 后等待 engine-sgf producer 原子写入 envelope；结果必须绑定当前 candidate/archive、`run.json` 哈希、launcher/runtime/JAR/data root、packaged engine PID/command/config/model 及 canonical oracle hash，并满足 frozen SNAPSHOT/MOVE/PASS、Chinese rules、正 visits、400 ms quiet stop 和 PID/reader/staged-SGF 清理。OpenCL/NVIDIA 使用 `variant-launch`：distribution 可在 application-ready 或明确 driver/backend repair 状态下通过，但 `inferenceStatus` 独立保持 `BLOCKED`，直到匹配硬件上的真实推理另有证据。

`LIZZIE_LINUX_ACCEPTANCE_FIXTURE_MODE=1` 仅供 `test_linux_product_acceptance.py` 的受控脚本 fixture 使用；其记录带 `observed.host.fixtureMode=true`，不能替代最终 ZIP、真实窗口或真实引擎/GPU 验收。脚本不上传、不发布，也不访问签名或发布凭据。

macOS 最终 DMG 只消费对应物理架构主机本地生成的 `candidate.json`。Apple Silicon 主机运行 `mac-arm64`，Intel 主机运行 `mac-amd64`；runner 同时记录进程架构、`sysctl hw.optional.arm64` 对应的物理架构和 `sysctl.proc_translated`，Rosetta 或其他模拟执行只作诊断，不能替代另一条 native 记录。每个候选使用新的证据目录，并把本次运行专用、尚不存在的 engine-sgf envelope 路径设为 `LIZZIE_MACOS_ACCEPTANCE_ENGINE_ORACLE`：

```bash
LIZZIE_MACOS_ACCEPTANCE_ENGINE_ORACLE=<new-evidence-dir>/engine-oracle.json \
scripts/macos_product_acceptance.sh \
  --candidate <candidate.json> \
  --scenario installed-offline-first-run \
  --evidence-dir <new-evidence-dir>
```

缺少已传输 candidate 时，需同时传 `--expected-target-sha` 与 `--expected-artifact-key/--expected-artifact-name/--expected-artifact-class`，脚本才会写带精确请求身份的 `BLOCKED` 记录；若已存在 `candidate.json` 但传入的预期标识与候选不符，则严格 fail closed。

runner 先复验 final DMG/provenance，再调用既有 `validate_macos_dmg_layout.sh`（其中继续调用 `macos_katago_bundle.py audit`）保留 layout 与 dylib closure 内容证据。它以 read-only 方式挂载 DMG，把唯一的 `LizzieYzy Next.app` 复制到带空格和非 ASCII 字符的新 Applications-equivalent 路径，eject 后才解析并启动 installed copy；launcher、embedded Java、launcher cfg、唯一 shaded JAR、JCEF、KataGo/config/model 必须全部解析到该 `.app` 内且与候选架构一致，symlink 不能逃逸。进程命令或 image 仍指向 `/Volumes` 会失败。

在任何 Gatekeeper 或 LaunchServices 启动前，runner 必须先建立隔离数据目录（`隔离 工作 数据`）、进程所有权标记、外部数据目录元数据快照（包含 product 根目录、`~/.lizzieyzy-next` 与遗留 `~/.lizzieyzy-next-foxuid`）、网络监控（macOS unified-log deny 事件流）、保存 launchctl 环境变量初始状态，并建立临时主机级 PF 出站阻断边界（基于 `com.apple` 锚点下的唯一临时 anchor，`sudo -n pfctl`，保留 loopback 并实测外部网络拒绝）。若所需权限、工具或证据探针在启动前不可用，记录严格 `BLOCKED`。

启动前对 installed app 写入实际 quarantine attribute。签名候选必须通过 `codesign`、DMG `stapler validate` 和 `spctl`；只有 `codesign` 明确报告完全未签名时才可进入 intentional-unsigned 分支，畸形或部分签名直接失败。unsigned 候选先观察 Gatekeeper 拒绝，生成绑定当前 DMG、installed app、launcher 哈希、quarantine attribute、随机 nonce 与时间的 `open-anyway-request.json`，并在 `LIZZIE_MACOS_OPEN_ANYWAY_MARKER` 收到字段完全匹配的确认文件后继续。无论签名分支还是 intentional-unsigned 分支，均必须在上述已建立的边界内通过 LaunchServices 真实拉起带有隔离属性的已安装副本（仅在 LaunchServices 启动期间通过 `launchctl setenv JAVA_TOOL_OPTIONS` 注入隔离路径，并在完成或异常时恢复/unset），由 runner 实际捕获并记录 observed 进程身份，并在后续受控 `sandbox-exec` 启动前完全终止所有 owned 进程；仅凭操作员确认文件中的 `launchObserved` 不作为进程存活证据。

`acceptance.json` 将信任输出独立拆分为 `signatureStatus` (`SIGNED_VALID` / `UNSIGNED_INTENTIONAL`)、`notarizationStatus` (`STAPLED_VALID` / `NOT_PERFORMED`) 与 `quarantineStatus` (`ALLOWED` / `BLOCKED_THEN_OPEN_ANYWAY`)，均为必需结果。

后续产品经 installed `jpackage` launcher 进入保留 loopback、拒绝外部网络的 macOS sandbox。`run.json` 绑定 candidate、installed launcher/runtime/JAR/JCEF/KataGo closure、PID/command、进程 incarnation、架构与隔离数据目录。外部 oracle producer 必须绑定该记录并满足各项规则与 cleanup；产品启动开始后，engine oracle 超时记为普通验收失败 `FAIL`（kind 为 `timeout`），不再记为 `BLOCKED`。最终 cleanup 终止 owned 进程、停止网络监控、清空 PF 临时 anchor 并用 `pfctl -X` 释放本次 enable token、恢复 launchctl；重新扫描 product 根目录、`~/.lizzieyzy-next` 与 `~/.lizzieyzy-next-foxuid` 并生成快照，任何外部写入或变动记入 `observed.dataRoot.outsideWrites` 并导致 `FAIL`。

`LIZZIE_MACOS_ACCEPTANCE_FIXTURE_MODE=1` 仅供 `test_macos_product_acceptance.py` 模拟 DMG/tool surface；记录带 `observed.host.fixtureMode=true`，内容审计日志明确标记 fixture。它不能替代 final DMG、Gatekeeper UI、真实签名/公证、native launcher/JVM/JCEF/KataGo、窗口或网络边界证据。缺少对应 native Mac、最终候选、GUI/quarantine/Accessibility/Screen Recording 权限或 oracle producer 时写精确 `BLOCKED`，不能用另一架构或 WSLg 补位。

记录：源码 SHA、tag/版本/渠道、OS/架构、workflow run URL/event/job、构建工具、真实资源来源及版本、资产文件名及身份校验、内容审计日志、安装/portable/DMG 启动与升级场景、签名/公证状态、上传目标与结果、预期/实际、证据路径、逐项状态。组装、签名、上传和实机启动分别给结论，未执行步骤明确标注。

脚本 fixture 单测通过不代表真实安装包、资源闭包、签名、发布身份或上传通过。源码 shaded JAR 验收也不证明 portable 启动器/安装器/升级路径。需要不发布的 packaging smoke 时先明确资产、平台与不写 Release 的范围；不能为文档或常规 CI 验收暗中触发具有发布写权限的 workflow。

## 历史证据与结论

[已验证平台](TESTED_PLATFORMS.md)记录历史环境；#453 等既有验收只适用于原记录的提交、构建和环境。缺少完整 SHA 或环境信息的旧记录保留其原始事实并注明不足，不补猜身份。新提交不自动继承历史 PASS；复用时必须指出原证据身份、覆盖边界和为何仍适用，未覆盖的新风险另验。

每次结论分别列：确定性测试、原生桌面、GPU、打包/发布的实际状态与证据。审查通过与验收完成是两道门；仅勾选有证据的验收项。专项契约/覆盖盘点完成，不等于全部硬件和发布资产已经验收。
