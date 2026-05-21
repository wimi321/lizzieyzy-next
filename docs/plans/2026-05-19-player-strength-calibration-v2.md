# 棋力评测算法校准记录

## 目标

- 只使用 19 路、非让子、足够手数的棋谱做校准。
- 用批量数据的总体分布、相关性和误差表调公式，不针对某个用户、某盘棋或某个段位单独修公式。
- 让一选率、好手率、失误率、目损和难度共同参与判断，避免某个指标单独支配最终段位。

## 采集与分析流程

1. `scripts/run_strength_calibration.py` 负责自动遍历野狐用户、筛选棋谱、调用 KataGo、写出 `evaluation.jsonl`。
2. 默认筛选条件调整为：`--board-size 19`、`--min-moves 100`、`--max-moves 200`、`--max-visits 64`。
3. `scripts/evaluate_strength_samples.py` 支持递归 glob、SGF 编码回退、棋盘大小过滤、最少手数过滤和按野狐 chessid 去重。
4. 为了避免 GPU 等待单手串行请求，评测脚本增加了 `--batch-positions` 和 `--parallel-engines`。本机实测 4 个 KataGo analysis 进程可以显著提高 GPU 利用率，后续大样本建议从 `--parallel-engines 4 --batch-positions 64` 起步。
5. `scripts/analyze_strength_calibration.py` 读取一个或多个 JSONL/CSV，重算当前公式，输出：
   - 指标与显示段位的 Pearson/Spearman 相关性；
   - 按实际大段位组、实际精确段级位聚合的分位数；
   - 基于组内 robust z 距离的统计异常候选；
   - 当前公式在全部样本、剔除统计异常样本上的误差表。

## 本轮数据结论

已有 24 visits 大样本为 444 个有效方。其指标相关性显示：

- 好手率 Spearman 约 0.607；
- 中位目损 Spearman 约 0.607；
- 一选率 Spearman 约 0.559；
- 失误率 Spearman 约 0.501；
- 平均目损 Spearman 约 0.474；
- 加权目损 Spearman 约 0.444；
- 难度相关性很弱，约 0.028。

因此本轮没有继续把加权目损作为硬封顶条件，而是将它降为综合评分的一部分。综合评分中提高了一选率、好手率、失误率的权重，并保留目损作为稳定性信号。

重新校准后，444 个有效方上的当前公式结果为：

- 大段位组命中：132/444，约 29.7%；
- 大段位组误差不超过一组：315/444，约 70.9%；
- 级位评成段位：38/444，约 8.6%；
- 严重高估：83/444，约 18.7%；
- 严重低估：148/444，约 33.3%；
- 平均绝对段级位误差：约 5.35。

这不是最终校准结果，但比单点修正式规则更可复现。后续继续扩大 64 visits 大样本，并抽样用 160/200 visits 复核后，应直接重跑同一个分析脚本，再决定是否调整公式。

同盘 visits 敏感性对比使用 12 盘、24 方，比较 64 visits 和 160 visits：

- 质量分平均绝对差：约 1.58；
- 一选率平均绝对差：约 2.1 个百分点；
- 好手率平均绝对差：约 1.8 个百分点；
- 失误率平均绝对差：约 0.8 个百分点；
- 加权目损平均绝对差：约 0.16 目；
- 档位变化：5/24，主要集中在边界相邻档。

因此大规模采样默认降为 64 visits，以扩大棋谱数量；160 visits 保留为抽样复核和高置信对照。

另有一轮 64 visits 局部大跑保存为 `target\strength-cache-all-20260519\evaluation-50games-64v-parallel.jsonl`，当前为 42 方。该批数据用于验证并行评测、断点续跑和吞吐设置，不单独作为公式调参依据，因为样本仍偏小，且来源集中度高。

同 visits 的中断任务可以用 `--resume-jsonl` 继续，脚本会跳过 JSONL 中已经完整写入黑白双方的棋谱。不同 visits 之间不能把已经跑出的 20 visits 结果直接累加成 64 visits；当前保存的是聚合后的评测指标，不是 KataGo 搜索树状态，所以若要提高 visits，需要对同一批 SGF 重新评测并用 visits 对比脚本衡量差异。

## 公式变更原则

- 删除加权目损硬封顶：目损仍进入综合评分，但不会单独把高一选率、高好手率的棋局压到低段。
- 删除针对某个好手率/失误率组合的特殊封顶：避免为了某个样本把全局公式掰弯。
- 评分权重按总体相关性调整：好手率、一选率、失误率和目损共同决定质量分。
- 段位阈值按新质量分布整体重标定：避免权重改变后全局偏低。

## 已运行验证

- `python -m py_compile scripts\run_strength_calibration.py scripts\evaluate_strength_samples.py scripts\fetch_fox_sgf_samples.py scripts\analyze_strength_calibration.py scripts\compare_strength_visit_runs.py`
- `python scripts\analyze_strength_calibration.py target\strength-calibration-large-20260514-240x220\evaluation.jsonl --out target\strength-calibration-analysis-20260519 --min-samples 40`
- `python scripts\analyze_strength_calibration.py target\strength-cache-all-20260519\evaluation-50games-160v.jsonl --out target\strength-calibration-analysis-20260519-160v-partial --min-samples 40`
- `python scripts\evaluate_strength_samples.py "target\**\*.sgf" --dedupe-chessid --min-moves 100 --board-size 19 --max-games 50 --max-moves 120 --max-visits 160 --batch-positions 64 --parallel-engines 4 --jsonl target\strength-cache-all-20260519\evaluation-50games-160v-parallel.jsonl`
- `python scripts\evaluate_strength_samples.py --paths-from-jsonl target\strength-cache-all-20260519\evaluation-50games-160v-parallel.jsonl --max-games 12 --max-moves 120 --max-visits 64 --batch-positions 64 --parallel-engines 4 --jsonl target\strength-cache-all-20260519\evaluation-12games-64v-compare.jsonl`
- `python scripts\compare_strength_visit_runs.py target\strength-cache-all-20260519\evaluation-50games-160v-parallel.jsonl target\strength-cache-all-20260519\evaluation-12games-64v-compare.jsonl --out target\strength-visit-compare-64v-vs-160v-20260519`
- `python scripts\evaluate_strength_samples.py "target\**\*.sgf" --dedupe-chessid --min-moves 100 --board-size 19 --max-games 50 --max-moves 200 --max-visits 64 --batch-positions 64 --parallel-engines 4 --jsonl target\strength-cache-all-20260519\evaluation-50games-64v-parallel.jsonl --resume-jsonl`
- `python scripts\analyze_strength_calibration.py target\strength-cache-all-20260519\evaluation-50games-64v-parallel.jsonl --out target\strength-calibration-analysis-20260519-64v-partial --min-samples 40`
- `mvn -Dtest=PlayerStrengthEstimatorTest test`
- `mvn test`
- `git diff --check`

## 后续建议

- 继续等待或分批运行 160/200 visits 的本地 KataGo 评测，样本足够大后再看稳定规律。
- 若野狐网络可用，优先扩大用户数和棋谱数，而不是围绕某个段位补样本。
- 每次公式改动前先保存分析报告，比较全部样本和剔除统计异常样本的趋势。
