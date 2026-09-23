# RTX 50 / TensorRT 分析性能验收

这份文档用于排查“相同 KataGo、模型和配置下，LizzieYzy Next 比旧版吞吐低或功耗低”的问题。不能只比较界面上的瞬时 visits；必须固定模型、配置、TensorRT 缓存、局面和运行时，并同时记录 KataGo 原始指标与 GPU 状态。

## 本轮资源仲裁

- 未启用“预加载分析引擎”时，应用启动后不再常驻第二个隐藏 KataGo。
- 自动快速胜率曲线按需创建独立分析进程，完成后立即退出。
- 自动快速曲线运行期间不会同时恢复主棋盘分析；曲线完成后立即恢复。
- 用户主动开始主棋盘分析时，空闲的第二分析进程会被释放，自动后台分析会被抢占；用户明确启动的闪电分析、整盘精析等任务不会被静默终止。
- 诊断默认关闭，不增加正常分析 I/O。启用后会记录进程用途、PID、脱敏命令、配置哈希、动态 `kata-set-param` 和主引擎 playouts/s。

## 固定参数基准

在 Windows PowerShell 中运行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\windows_rtx50_analysis_benchmark.ps1 `
  -KataGoExe "D:\KataGo\katago.exe" `
  -Model "D:\KataGo\model.bin.gz" `
  -Config "D:\KataGo\gtp.cfg" `
  -OutputDirectory "$env:USERPROFILE\Desktop\lizzie-rtx50-benchmark" `
  -Runs 3 `
  -SecondsPerMove 30 `
  -VisitsPerPosition 5000
```

脚本会保存：

- KataGo 官方 `benchmark` 的完整 stdout/stderr
- GPU 型号、驱动、P-State、功耗、显存、GPU/显存控制器利用率
- 每秒计算进程 PID、名称和显存
- 模型与配置文件 SHA-256，防止比较时实际使用了不同文件

`SecondsPerMove` 对应 KataGo 官方 benchmark 的 `-time` 参数，表示典型单手思考时间，不是脚本总运行时间；完整基准会测试多个局面和线程组合。

## Next 运行诊断

### 同局面、固定预算的实时与整盘对照

`scripts/measure_analysis.py` 在独立目录启动真实引擎，不读写日常应用设置。固定引擎、模型、配置、局面、规则、贴目、visits 预算和输出内容；仅允许比较搜索线程、并行局面、每局面线程、批大小。输出保存输入 SHA-256、每次最终覆盖参数、原始日志、冷启动时间、GPU/驱动/显存及可见 KataGo 进程数。

```powershell
python scripts/measure_analysis.py --engine "D:\KataGo\katago.exe" `
  --model "D:\KataGo\b11.bin.gz" --config "D:\KataGo\gtp.cfg" `
  --fixture scripts/fixtures/performance-opening.json `
  --profiles scripts/fixtures/performance-profiles.json `
  --output "D:\measurement\engine" --source-commit "<measured-commit>" `
  --visits 5000 --rounds 3
```

示例 fixture 为 8 手开局、整盘场景包含空盘在内的 9 个局面，只能证明该样本的结果。正式推荐应再加入用户常用棋谱和中盘局面；示例 profiles 是待比较参数，不是推荐或默认值。固定预算使用相同配置；配置含 `include` 时还必须归档并核对所有被包含文件。并发搜索可能略微超出目标 visits，原始 root visits 会保留。

每组先记录独立缓存目录的冷进程结果，然后按 A/B、B/A 交替至少三轮。热轮在同一进程内先完成一次同预算预热，再清除搜索/神经网络缓存进行计时；驱动的全局缓存不在脚本控制范围内，不能把这里的冷进程等同首次安装。`--rounds 0` 仅用于冒烟验证，不是调优依据；波动较大时用新的输出目录运行 `--rounds 5`。

实时场景测最后落子到首次有效 rootInfo、达到固定 visits 的耗时以及带编号的 stop 回执。整盘场景核对每个局面预算完成，另外提交高预算请求并等待全部取消回执，不能把“收到 terminate ACK”当成搜索已经停止。

应用内对照复用生产 Swing 窗口、`Leelaz` 和 `AnalysisEngine`，不修改生产分析入口：

```powershell
mvn -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/performance-classpath.txt
$measurementClasspath = (Join-Path $PWD 'target/test-classes') + ';' + `
  (Join-Path $PWD 'target/classes') + ';' + (Get-Content target/performance-classpath.txt -Raw).Trim()
# 在同一上方命令中改用新的 --output，并追加：
# --app-classpath $measurementClasspath --java "$env:JAVA_HOME\bin\java.exe"
```

应用探针额外保存 EDT 事件延迟样本、Java 堆使用量和实际子进程最终命令（优先 OS argv，Windows 必要时按本次引擎 PID 读取 CIM command line，绝不以配置中的原始命令代替）。实时测量先等待生产棋盘恢复的确认 Future，再等 stop 和 clear_cache 回执，最后才从实际落子开始计时；只等“载入完成”界面标志或随意插入 GTP 回执不能证明异步恢复队列已清空。修正后的报告标记 `measurementContractVersion: 2`；无此标记的旧应用测量仅保留作诊断，不能作为推荐依据。

整盘单独记录专用引擎启动时间，取消测试必须先观察到该请求的正 visits 搜索中报告，再走生产关闭专用引擎流程并等待真实进程退出。清缓存 ACK 与取消测试的中间报告由测试探针识别，预算测量仍使用生产请求及最终结果解析器。应用进程退出与裸 JSON 引擎的终止回执属于不同取消机制，不直接混算。引擎侧和应用侧须使用相同 manifest 输入；不能用不同局面的官方 benchmark 来计算应用开销。`--scene realtime` 或 `--scene whole-game` 可单独复测；`performance-midgame.json` 提供另一组 80 手中盘 fixture。

运行期间不要并行运行其他引擎、编译或桌面验收。Windows WDDM 的进程显存可能显示 `N/A`；此时总 GPU 显存不能冒充单进程显存，其他桌面程序的占用也应记录为限制。样本失败立即停止，原始失败日志保留；不要混入成功样本，也不要覆盖旧输出目录。

验收比较每轮及中位数，而非单次最好数字；同时看首结果、暂停/取消、EDT 的 p95/p99、显存峰值和预算完整性。只有多个实际棋谱上稳定收益且无明显延迟/内存回退才推荐参数；没有足够证据继续使用原配置。

复制一份 Windows 启动器旁的 `app\LizzieYzy Next*.cfg`，只在测试副本的 `[JavaOptions]` 末尾加入：

```text
java-options=-Dlizzie.analysis.diagnostics=true
java-options=-Dlizzie.analysis.diagnostics.path=C:\Users\Public\Documents\LizzieYzyNext\analysis-resource-diagnostics.jsonl
```

完成测试后删除这两行。诊断文件不会记录密码、token、cookie 或完整模型路径；远程连接参数会脱敏。不要把诊断开关作为日常设置长期保留。

## 对比矩阵

所有行必须使用同一局面、模型、配置、KataGo 二进制、已完成预热的 TensorRT 缓存和相同运行时长。

| 场景 | 自动快速曲线 | 其他 KataGo 进程 | 必须记录 |
| --- | --- | --- | --- |
| 官方 KataGo benchmark | 不适用 | 0 | nnEvals/s、batch、功耗、显存 |
| Next 主棋盘单引擎 | 关闭 | 0 | visits/s、功耗、显存、最终参数 |
| Next 自动快速曲线开启 | 开启 | 曲线完成后应为 0 | 曲线期间与完成后进程数 |
| 旧版同局面对照 | 与 Next 一致 | 明确记录 | visits/s、功耗、显存 |

每个场景至少运行三次，丢弃第一次 TensorRT 建图/缓存生成的冷启动数据。RTX 5080 真机数据未完成前，不能仅凭 CI 或其他显卡结果宣称性能问题已经解决。

## 结果判读

- 官方 benchmark 已慢：优先检查驱动、TensorRT/CUDA 版本、模型、配置和缓存，不属于 Swing UI 开销。
- 官方 benchmark 正常、Next 单引擎慢：检查诊断中的最终命令、配置哈希、动态参数和进程用途。
- Next 单引擎正常、开启自动快速曲线后持续慢：检查是否仍有第二 KataGo PID；这是资源仲裁回归。
- 首次运行慢、第二次正常：通常是 TensorRT 引擎构建或 CUDA 缓存，不应与热缓存数据混合比较。
