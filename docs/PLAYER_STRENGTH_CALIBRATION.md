# 棋力评估 GP / XGBoost 校准说明

## 目标

棋力评估模块基于当前主线已经缓存或批量生成的 KataGo 分析结果，综合吻合度、一选率、好手率、Top5 命中率、折算目损、失误率和局面难度，给出复盘参考用的连续棋力评分。

本轮改动把离线校准结果接入运行时：

- 默认模型：GP core4；
- 可选模型：XGBoost top16；
- 旧 Huber linear 方案保留为 fallback / debug 选项；
- 前端显示连续评分和明确等级标签，不再显示估计区间或业余参考区间。

显示规则：

| 分数 | 显示 |
| ---: | --- |
| `>=12` | 半神/AI |
| `>=11, <12` | 一线职业 |
| `>=10, <11` | 职业 |
| `<10` | 野狐段位/等级 |

该评分只用于复盘参考，不等同于任何平台官方段位。

## 本次提交的数据集

本 PR 随仓库提交两个 CSV 数据集，便于复现模型训练。这里的“方”表示一盘棋中的一方，黑方和白方分别计为一条样本。

| 文件 | 方数 | 盘数 | 说明 |
| --- | ---: | ---: | --- |
| `data/strength_calibration/v48_m180_a16s2_raw_calibration_rows.csv` | 4,478 | 2,239 | 有效建模分析母表，包含原始标签、清洗标签、特征和审计标记 |
| `data/strength_calibration/v48_m180_a16s2_clean_training_rows.csv` | 3,196 | 1,809 | GP / XGBoost 实际拟合使用的清洗训练集 |

完整 SGF、KataGo JSONL、tar 包和中间分析报告不提交到仓库，默认保留在本地 `target/` 或外部实验存储。运行时只需要提交导出的模型资源：

| 文件 | 说明 |
| --- | --- |
| `src/main/resources/models/strength/exact_gp_core4_strength_model.json.gz` | GP core4 运行时模型 |
| `src/main/resources/models/strength/xgboost_selected_strength_booster.json` | XGBoost top16 运行时模型 |

## 数据收集

本轮批次名为 `strength-calibration-batch-20260609-plus-toppro`。原始输入共 2,357 盘 SGF：

| 来源 | 棋谱数 | 标签用途 |
| --- | ---: | --- |
| `fox_dan` | 1,271 | 野狐 1d-9d 段位样本 |
| `jgdb_pro` | 500 | 职业棋手样本 |
| `fox_top_pro` | 386 | 顶尖职业 / 世界冠军级样本 |
| `katago_ai` | 200 | KataGo rating / AI 参考样本 |
| 合计 | 2,357 | 原始采集输入 |

过滤规则：

- 只保留 19 路；
- 默认剔除让子棋；
- 主线手数不少于 100；
- 按 Fox `chessid` 去重；
- 每方有效采样手数不足的记录不进入建模分析。

KataGo 批量分析在 waffle 上运行，关键参数：

```text
maxVisits = 48
maxMoves = 180
batchPositions = 384
parallelEngines = 1
numAnalysisThreads = 16
numSearchThreadsPerAnalysisThread = 2
nnMaxBatchSize = 64
```

最终得到：

| 阶段 | 方数 | 盘数 | 说明 |
| --- | ---: | ---: | --- |
| 原始分析数据 | 4,540 | 2,270 | KataGo 对有效棋谱完成分析后的黑白双方结果 |
| 有效建模分析 | 4,478 | 2,239 | 剔除每方有效采样手数不足等不满足建模条件的记录 |
| 清洗候选训练集 | 3,252 | 1,831 | 完成 12 类标签清洗、重归属和主要异常剔除后的候选训练样本 |
| 最终拟合训练集 | 3,196 | 1,809 | 进一步剔除残留核心四指标离群样本后的最终拟合样本 |

## 数据生成流程

### 1. 构建棋谱批次

棋谱批次和 `metadata.jsonl` 保存在本地/远端实验目录。`metadata.jsonl` 记录每盘棋的来源、原始标签、棋手信息和 SGF 路径。

批次目录示例：

```text
target/strength-calibration-batch-20260609-plus-toppro/
```

### 2. 跑 KataGo 批量分析

等价命令：

```bash
python3 scripts/evaluate_strength_samples.py \
  --metadata-jsonl strength-calibration-batch-20260609-plus-toppro/metadata.jsonl \
  --jsonl strength-calibration-batch-20260609-plus-toppro/evaluation-v48-m180-a16s2.jsonl \
  --min-moves 100 \
  --board-size 19 \
  --dedupe-chessid \
  --max-moves 180 \
  --max-visits 48 \
  --batch-positions 384 \
  --parallel-engines 1 \
  --katago <katago> \
  --model <katago-model> \
  --config <analysis-config> \
  --katago-override-config numAnalysisThreads=16,numSearchThreadsPerAnalysisThread=2,nnMaxBatchSize=64
```

远端完成标记：

```text
DONE_RESUME 2026-06-09T00:35:18+00:00 status=0
POSTPROCESS_DONE 2026-06-09T00:36:11+00:00 status=0
PACKAGE_DONE 2026-06-09T00:38:32+00:00 status=0
```

### 3. 拉回并校验结果

```bash
bash scripts/pull_strength_calibration_results.sh
```

该脚本经 lab 从 waffle 拉回 tar 包和 sha256，校验 hash，解包，并运行结果完整性检查。

### 4. 生成清洗母表

```bash
python3 scripts/analyze_strength_calibration.py \
  target/strength-calibration-results-v48-m180-a16s2/strength-calibration-batch-20260609-plus-toppro/evaluation-v48-m180-a16s2.jsonl \
  --out target/reanalysis-12class-12dstyle-cleaning-v48-m180-a16s2-full \
  --metric-cluster-mode rank_class \
  --enable-twelve-d-style-rule
```

这个阶段输出 `calibration_rows.csv`，也就是本 PR 中 `v48_m180_a16s2_raw_calibration_rows.csv` 的来源。

### 5. 导出清洗训练集

最终清洗训练集来自 `calibration_rows.csv`。训练脚本使用下面的筛选规则：

```text
if residual_core_four_outlier:
    exclude
elif metric_rank_reassigned:
    keep
else:
    keep only if not statistical_outlier and not rank_mismatch_candidate
```

该规则保留稳定重归属样本，剔除未能稳定归属的统计异常、段位不匹配候选和残留核心四指标离群样本。

## 标签定义

本轮固定使用 12 个监督标签类：

| rank value | 类别 |
| ---: | --- |
| 1-9 | 野狐 1d-9d |
| 10 | 职业 |
| 11 | 一线职业 |
| 12 | KataGo rating / AI |

类别数来自采集标签定义，不由无监督聚类自动决定。无监督聚类只用于诊断和审计。

## 标签清洗方法

设样本特征为 \(x_i \in \mathbb{R}^d\)，原始标签为 \(y_i \in \{1,\ldots,12\}\)。对每个固定类别 \(c\)，估计鲁棒中心 \(\mu_c\) 和尺度 \(s_c\)，并定义标准化距离：

\[
D_c(x_i)=\sqrt{\sum_j \left(\frac{x_{ij}-\mu_{cj}}{s_{cj}+\epsilon}\right)^2}.
\]

若样本满足：

\[
D_{y_i}(x_i) > \tau_{\text{out}},
\]

且存在

\[
c^\*=\arg\min_c D_c(x_i)
\]

使得

\[
D_{c^\*}(x_i)+\Delta < D_{y_i}(x_i),
\]

并通过最小段位差和多指标方向一致性约束，则将训练标签设为：

\[
\tilde{y}_i=c^\*.
\]

否则保留原标签：

\[
\tilde{y}_i=y_i.
\]

这里的 12 类是预先定义的监督标签类；聚类结果仅作为辅助诊断，不直接决定最终标签。

## 12d 强规则

12d 不是只看平均目损。满足 12d 风格需要多个指标同时达到高水平，包括：

- 吻合度；
- Top5 命中率；
- 平均 AI 排名；
- 折算平均目损；
- P90 折算目损；
- 失误率。

本批次 12d 强规则命中 309 方，其中非 12d 提升到 12d 训练标签的有 62 方。

## 每个段位的样本量

| 段位值 | 标签 | 原始方数 | 原始盘数 | 清洗后归属方数 | 最终训练方数 | 迁出方数 | 迁入方数 | 12d 强规则命中 | 12d 提升 |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1d-fox | 690 | 374 | 715 | 701 | 1 | 26 | 1 | 1 |
| 2 | 2d-fox | 434 | 226 | 449 | 442 | 0 | 15 | 0 | 0 |
| 3 | 3d-fox | 516 | 306 | 514 | 505 | 2 | 0 | 2 | 2 |
| 4 | 4d-fox | 322 | 182 | 321 | 313 | 1 | 0 | 1 | 1 |
| 5 | 5d-fox | 208 | 129 | 208 | 204 | 0 | 0 | 0 | 0 |
| 6 | 6d-fox | 114 | 83 | 110 | 110 | 4 | 0 | 3 | 3 |
| 7 | 7d-fox | 102 | 84 | 100 | 32 | 2 | 0 | 0 | 0 |
| 8 | 8d-fox | 85 | 63 | 81 | 39 | 4 | 0 | 1 | 1 |
| 9 | 9d-fox | 71 | 54 | 59 | 34 | 12 | 0 | 10 | 10 |
| 10 | 10d-pro | 1,136 | 750 | 1,114 | 482 | 22 | 0 | 9 | 9 |
| 11 | 11d-top-pro | 406 | 385 | 371 | 134 | 35 | 0 | 35 | 35 |
| 12 | 12d-katago-rating | 394 | 197 | 436 | 256 | 20 | 62 | 247 | 0 |

## 特征

完整特征池包含 29 个工程特征。当前 GP core4 默认模型只使用 4 个核心特征：

| 特征 | 含义 |
| --- | --- |
| `good_move_rate` | 好手率 |
| `first_choice_rate` | 一选率 |
| `average_score_equivalent_loss` | 折算平均目损 |
| `average_difficulty` | 当前难度 |

当前 XGBoost top16 使用 16 个特征：

```text
weighted_loss_fit
good_move_rate
average_loss_fit
median_loss_fit
top5_rate
opening_loss_fit
middlegame_loss_fit
average_ai_rank_fit
p75_loss_fit
max_loss_fit
match_rate
p90_loss_fit
first_choice_rate
non_mistake_rate
top5_x_difficulty
good_move_x_difficulty
```

所有 `*_loss_fit` 都是由原始目损变换得到的正向特征，值越大表示目损越小。

## 模型训练

### GP core4

```bash
python3 scripts/fit_strength_exact_gp_model.py \
  target/reanalysis-12class-12dstyle-cleaning-v48-m180-a16s2-full \
  --feature-set core4 \
  --export-cholesky
```

输出模型复制为：

```text
src/main/resources/models/strength/exact_gp_core4_strength_model.json.gz
```

### XGBoost top16

```bash
python3 scripts/select_strength_xgboost_features.py \
  target/reanalysis-12class-12dstyle-cleaning-v48-m180-a16s2-full
```

输出模型复制为：

```text
src/main/resources/models/strength/xgboost_selected_strength_booster.json
```

## 审计文件

完整分析目录会生成以下审计文件：

```text
rank_data_cleaning_audit.md
rank_label_cleaning_summary.csv
source_rank_count_summary.csv
rank_model_transition_matrix.csv
rank_cluster_assignment_matrix.csv
metric_rank_reassignments.csv
metric_reassignment_summary.csv
twelve_d_style_rule_hits.csv
twelve_d_style_rule_summary.csv
residual_core_four_outliers.csv
residual_core_four_outlier_summary.csv
core_four_metric_summary.csv
exact_gp_core4_strength_model_report.md
xgboost_selected_strength_feature_importance.csv
```

这些文件不随 PR 提交完整产物，但可由提交的脚本和 CSV 数据重新生成。

## 验证

Java 侧验证：

```bash
mvn -Dtest=GaussianProcessStrengthModelTest,HighEndStrengthCalibratorTest,XGBoostStrengthModelTest,PlayerStrengthEstimatorTest test
mvn -DskipTests package
```

结果：

- GP / XGBoost 运行时模型加载测试通过；
- 高端锚点校准测试通过；
- `PlayerStrengthEstimator` 模型选择和旧逻辑回归测试通过；
- shaded jar 打包通过。
