# 棋力校准数据集

本目录包含本次 GP / XGBoost 棋力评估模型的可提交 CSV 数据集。

## 文件

| 文件 | 方数 | 盘数 | 说明 |
| --- | ---: | ---: | --- |
| `v48_m180_a16s2_raw_calibration_rows.csv` | 4,478 | 2,239 | 有效建模分析母表，包含原始标签、清洗标签、全部特征和审计标记 |
| `v48_m180_a16s2_clean_training_rows.csv` | 3,196 | 1,809 | GP / XGBoost 实际拟合使用的清洗训练集 |

这里的“方”表示一盘棋中的一方，黑方和白方分别计为一条样本。

## 训练集筛选规则

`v48_m180_a16s2_clean_training_rows.csv` 从 `v48_m180_a16s2_raw_calibration_rows.csv` 按训练脚本的真实筛选逻辑导出：

```text
if residual_core_four_outlier:
    exclude
elif metric_rank_reassigned:
    keep
else:
    keep only if not statistical_outlier and not rank_mismatch_candidate
```

因此，已经稳定重归属的样本会保留；无法稳定归属的统计异常、段位不匹配候选和残留核心四指标离群点会被剔除。

## 目标列

模型训练使用 `model_rank_value` 作为目标值。`actual_rank_value` 保留为原始采集标签，用于审计标签清洗过程。

固定标签定义：

| `model_rank_value` | 含义 |
| ---: | --- |
| 1-9 | 野狐 1d-9d |
| 10 | 职业 |
| 11 | 一线职业 |
| 12 | KataGo rating / AI |

## 生成来源

原始批处理目录和完整中间产物默认位于 `target/`，不随仓库提交。可通过以下脚本复现：

```bash
python3 scripts/analyze_strength_calibration.py \
  target/strength-calibration-results-v48-m180-a16s2/strength-calibration-batch-20260609-plus-toppro/evaluation-v48-m180-a16s2.jsonl \
  --out target/reanalysis-12class-12dstyle-cleaning-v48-m180-a16s2-full \
  --metric-cluster-mode rank_class \
  --enable-twelve-d-style-rule
```

训练脚本读取包含 `calibration_rows.csv` 的分析目录，并在内部应用同样的清洗筛选规则。
