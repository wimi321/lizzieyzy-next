# 棋力水平评估校准说明

## 目标

棋力水平评估只使用当前主线已经缓存的 KataGo 分析结果，综合一选率、好手率、吻合度、失误率、目损和难度，给用户一个复盘参考段位。它不是野狐官方段位，也不应该为了单个用户、单盘棋或单个段位硬调公式。

本轮优化遵循一个原则：公式只根据清洗后的大样本数据调整。原始全量数据保留用于追溯，但异常样本和明显不属于标注段位的样本不参与回归拟合。

## 数据范围

最终整理的数据位于：

`target/strength-calibration-curated-20260519-230817/collected-results-20260520/`

本轮全量分析配置：

- 只保留 19 路棋。
- 每盘不少于 100 手，且剔除大于 350 手的棋谱。
- 每个用户最多纳入 3 盘，减少单个账号对分布的影响。
- 剔除用户指定的小号或特殊账号，例如 `随机2536`、`实之`、`艾池繁123`。
- 名称包含 `让子` 的样本按 AI 档处理。
- KataGo 每步 64 visits，4 个并发分析进程。
- 全量结果共 568 盘，黑白两方合计 1136 方样本。

清洗规则：

- 统计异常候选：在同一实际大段位组内，对一选率、好手率、吻合度、失误率、目损等指标做 robust z / MAD 距离，距离过大则标记。
- 段位不匹配候选：把样本指标同各大段位组中心比较，如果整体明显更像相隔较远的其他组，则标记。
- 这些标记不删除原始 JSONL，只在回归拟合和主结论中排除。

清洗后用于回归的主样本为 1081 方、556 盘。公式和 PR 说明以这个清洗集为准。

## 指标规律

清洗前后的统计报告由 `scripts/analyze_strength_calibration.py` 生成。全量指标与实际段位的 Spearman 相关性大致如下：

- 好手率：0.671
- 吻合度：0.668
- 中位目损：0.664
- 失误率：0.605
- 一选率：0.591
- 平均目损：0.550
- 加权目损：0.528
- P90 目损：0.522
- 难度：0.085

结论：

- 好手率和吻合度是最稳定的正向信号，但需要结合难度看，低难度局的一选和好手不应直接推高到顶段。
- 中位目损比加权目损更稳定；加权目损不再单独主导段位，只作为目损族信号和封顶规则的一部分。
- 失误率、重大失误率对高段判断很重要，可以有效压住“少量好手但尾部崩盘”的样本。
- 难度本身与段位相关性很弱，因此只作为修正项，不作为主要加分项。

## 上线公式

生产代码没有引入运行时模型文件。离线回归只用于从清洗集里寻找指标权重，最终固化为 `PlayerStrengthEstimator` 中的可解释公式和封顶规则。

本轮使用清洗集做 Huber 回归：

- 方法：`HuberRegressor(alpha=0.001, epsilon=1.8)`
- 训练样本：1081 方、556 盘
- 加权方式：按大段位组均衡加权，避免样本多的段位压过样本少的段位
- 特征：一选率、好手率、吻合度、非失误率、非重大失误率、加权/平均/中位/P75/P90 目损拟合值、难度，以及一选/好手/吻合度与难度的交互项

回归只决定基础连续段位值；最终显示段位还会经过证据封顶：

- 一选率、好手率、吻合度、失误率、中位目损分别设置上限。
- P90 目损和平均目损过高时降低上限，避免尾部大失误被平均值掩盖。
- 近乎全一选、全好手且低目损时才允许进入 `12d AI`。
- 高段需要好手率、吻合度、失误率和目损同时给出证据，不能只靠某一个指标顶上去。

## 本轮效果

`formula_evaluation.csv` 中主看清洗集行：

- 清洗集：1081 方
- 大段位命中：439/1081，40.6%
- 大段位误差不超过 1 组：940/1081，87.0%
- 级位评成段位：52/1081，4.8%
- 严重高估：244/1081，22.6%
- 严重低估：169/1081，15.6%
- MAE：3.651

`clean_set_regression_summary.csv` 是只使用清洗集的回归复现报告：

- 5 折按棋局分组交叉验证 MAE：3.621
- 交叉验证大段位命中：458/1081，42.4%
- 交叉验证大段位误差不超过 1 组：947/1081，87.6%
- 回归系数输出在 `clean_set_regression_coefficients.csv`

## 脚本说明

这些脚本是离线校准工具，不是软件运行时依赖。默认把采集棋谱、KataGo 输出和统计报告写到 `target/`，避免把大体量数据随 PR 提交。

### `scripts/run_strength_calibration.py`

用途：从野狐用户种子开始自动扩展采集样本，按段位桶、手数、棋盘大小、每用户最大盘数等条件筛选 SGF，然后可选择直接调用 `evaluate_strength_samples.py` 做 KataGo 评测。

常用启动方式：

```powershell
python scripts\run_strength_calibration.py --out target\strength-calibration-curated --target-games 240 --target-dan-games 8 --target-kyu-group-games 8 --max-games-per-user 3 --min-moves 100 --board-size 19 --max-moves 350 --max-visits 64 --parallel-engines 4 --batch-positions 4 --resume-jsonl
```

常用参数：

- `seeds`：野狐 uid 或昵称种子，不传时使用脚本内默认种子。
- `--out`：输出目录，保存 SGF、清单、评测 JSONL 和摘要。
- `--target-games`：目标采集盘数。
- `--target-dan-games` / `--target-kyu-group-games`：段位桶最低盘数约束。
- `--max-games-per-user`：每个用户最多纳入几盘，当前建议不高于 3。
- `--exclude-player-marker`：排除昵称包含指定文本的用户，可重复传入。
- `--min-moves` / `--max-moves` / `--board-size`：手数和棋盘大小过滤。
- `--crawl-only`：只采集，不启动 KataGo。
- `--max-visits`：每个局面 KataGo visits，本轮为 64。
- `--parallel-engines`：并发 KataGo 进程数，本机 4 并发较稳定。
- `--batch-positions`：每个 KataGo 进程一次排队的局面数；本轮全量用 4，避免大批次卡住。
- `--resume-jsonl`：续跑时跳过 JSONL 中已经完成的棋谱。
- `--reuse-sgf-analysis` / `--sgf-analysis-only`：复用 SGF 内已有 `LZ` / `LZOP` 分析。
- `--katago` / `--model` / `--config`：KataGo 可执行文件、权重文件和分析配置路径。

### `scripts/evaluate_strength_samples.py`

用途：对一个或多个 SGF 文件运行离线棋力评估。它可以直接调用 KataGo，也可以读取 LizzieYZY Next 保存进 SGF 的已有分析结果。输出 JSONL 每行是一盘棋的一方，包含一选率、好手率、吻合度、目损、难度、评分和段位等字段。

常用启动方式：

```powershell
python scripts\evaluate_strength_samples.py "target\strength-calibration-curated\quota-sgfs-max3-per-player\*.sgf" --max-games 568 --min-moves 100 --max-moves 350 --board-size 19 --max-visits 64 --parallel-engines 4 --batch-positions 4 --resume-jsonl --jsonl target\strength-calibration-curated\runs\64v-full\evaluation-64v-full.jsonl
```

常用参数：

- `sgfs`：SGF 文件或通配符。
- `--paths-from-jsonl`：从旧 JSONL 读取 SGF 路径，适合补跑或复算。
- `--max-games`：最多评测多少盘。
- `--min-moves` / `--max-moves` / `--board-size`：棋谱过滤和每盘分析手数上限。
- `--max-visits`：每个局面 KataGo visits。
- `--parallel-engines`：并发 KataGo 进程数。
- `--batch-positions`：每个进程一次发送多少个局面；本轮建议 4。
- `--katago-response-timeout`：单个 KataGo 查询批次的超时时间，超时后会重启对应进程并重试。
- `--progress-interval`：进度输出间隔，会显示总进度、当前第几盘和已完成手数。
- `--dedupe-chessid`：按野狐棋局 id 去重。
- `--resume-jsonl`：已在 JSONL 中黑白两方都完成的棋谱会跳过。
- `--reuse-sgf-analysis`：优先用 SGF 内已有分析，缺失局面再跑 KataGo。
- `--sgf-analysis-only`：只读 SGF 内已有分析，不启动 KataGo。
- `--jsonl`：评测结果输出文件。

### `scripts/analyze_strength_calibration.py`

用途：读取评测 JSONL 或汇总 CSV，先标记统计异常和段位不匹配候选，再统计指标与真实段位的相关性、分布、当前公式误差，并在清洗集上运行回归。

常用启动方式：

```powershell
python scripts\analyze_strength_calibration.py target\strength-calibration-curated-20260519-230817\collected-results-20260520\evaluation-64v-full.jsonl --out target\strength-calibration-curated-20260519-230817\collected-results-20260520\analysis-results\full-64v-20260521-clean-final --min-samples 40 --outlier-z 3.5
```

常用输出：

- `calibration_rows.csv`：逐方样本，含异常标记、估计段位和误差。
- `metric_correlations.csv`：各指标与实际段位的相关性。
- `metric_distribution_by_actual_group.csv`：各大段位组指标分布。
- `formula_evaluation.csv`：当前公式在全量、清洗集和异常候选上的效果。主结论只看清洗集。
- `clean_set_regression_summary.csv`：只使用清洗集的回归摘要。
- `clean_set_regression_coefficients.csv`：清洗集回归系数。
- `analysis.md`：Markdown 汇总报告。

### `scripts/compare_strength_visit_runs.py`

用途：比较两次 visits 不同的评测结果，观察 20/32/64/160 visits 之间指标差异是否足够小，从而判断是否可以降低 visits 换取更大的样本量。

常用启动方式：

```powershell
python scripts\compare_strength_visit_runs.py target\run-160v\evaluation.jsonl target\run-64v\evaluation.jsonl --out target\strength-visit-compare-160v-64v
```

## 复现命令

```powershell
python -m py_compile scripts\run_strength_calibration.py scripts\evaluate_strength_samples.py scripts\analyze_strength_calibration.py scripts\compare_strength_visit_runs.py
python scripts\analyze_strength_calibration.py target\strength-calibration-curated-20260519-230817\collected-results-20260520\evaluation-64v-full.jsonl --out target\strength-calibration-curated-20260519-230817\collected-results-20260520\analysis-results\full-64v-20260521-clean-final --min-samples 40 --outlier-z 3.5
```

如果要验证 Java 侧实现，可运行项目现有的相关单测或编译命令；大体量 SGF 和 JSONL 校准产物留在 `target/`，不随 PR 提交。
