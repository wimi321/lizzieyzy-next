# 同步修复代码审查记录

日期：2026-04-22

分支：`fix-sync`

审查范围：`3533d9f fix(sync): 修复同步节点匹配与胜率图性能问题` 和 `504c0b1 refactor: 清理同步死代码` 两个 commit 涉及的所有文件改动。

上位契约：[SNAPSHOT_NODE_KIND.md](SNAPSHOT_NODE_KIND.md)

## 1. 审查维度

correctness、边界条件、状态同步、异常传播、资源释放、API 合约、测试缺口。

## 2. 审查问题与结论

### 2.1 `matchesStones` fox recovery 跳过 marker 位置是否放宽了匹配条件

**文件**：`SyncSnapshotRebuildPolicy.java:170-173`

**改动**：fox recovery 时，`matchesStones` 对 `snapshotCode == 3 || == 4` 的位置直接 `continue`，不做 stone 比较。旧代码用 `normalizeConflictSnapshot` 推导颜色后仍做比较。

**疑虑**：如果 marker 位置的实际棋子颜色与本地历史不同（例如 readboard 误识别），跳过后不会发现差异，可能误匹配。

**结论：不是问题。**

- `fox-marker-valid-gate-fix-design.md`（内部设计文档） 第 2 节明确：fox recovery 路径只用 `stones + moveNumber`，不依赖 marker。
- [SNAPSHOT_NODE_KIND.md](SNAPSHOT_NODE_KIND.md) 第 3.1 节：`marker` 是增强信号，不是硬前提。
- fox recovery 的 `LIVE_ROOM` 身份键定义为 `stones + foxMoveNumber`，marker 不参与 stone 级硬匹配。
- `moveNumber` 匹配提供额外安全网，单独 stone 颜色误判不足以造成误匹配。

### 2.2 `buildConflictKey` 与 `matchesStones` 对 marker 位置处理不一致

**文件**：`SyncSnapshotRebuildPolicy.java:232-236` vs `162-182`

**改动**：`buildConflictKey` 通过 `normalizeConflictSnapshot` 将 marker 位置推导为确定颜色值，而 `matchesStones` 在 fox recovery 时直接跳过 marker 位置。

**疑虑**：两个方法对 marker 位置的处理语义不同，可能导致 conflict tracker 去重不一致。

**结论：不是问题。**

- 两个方法有不同职责：`matchesStones` 判断"这个节点是不是目标"，`buildConflictKey` 判断"这个远端快照是不是同一个冲突身份"。
- [SNAPSHOT_NODE_KIND.md](SNAPSHOT_NODE_KIND.md)：同一冲突快照的判定基于归一化远端身份，不基于原始 `snapshotCodes` 整帧全等。marker 抖动不能把同一冲突拆成不同 key。
- `buildConflictKey` 用 `normalizeConflictSnapshot` 将 marker 推导为确定颜色值，正是为了满足"marker 抖动不拆 key"的契约要求。语义差异是设计意图。

### 2.3 `allowsFoxMarkerlessSingleStep` 移除 `hasMarker()` 后可能在非让子棋场景误触发

**文件**：`ReadBoard.java:976-989`

**改动**：移除了 `snapshotDelta.hasMarker()` 前置条件。

**疑虑**：普通对局中 readboard 帧丢失 marker 时，如果恰好有 1 个新增且 moveNumber 差 1，可能走入非预期的增量路径。

**结论：不是问题，是 spec 要求的行为。**

- `readboard-early-game-history-design.md`（内部设计文档） 第 5.1 节明确要求接受"无 marker + 单颗新增 + recoveryMoveNumber == current + 1"的帧进入增量路径。
- 方法名 `allowsFoxMarkerlessSingleStep` 本身就表明它是为"无 marker"场景设计的。
- 该路径仅在 `supportsFoxRecovery()` 为 true 时启用，且要求 `hasOnlyAdditions() && additions() == 1 && moveNumber 差 1`，有足够约束。

### 2.4 WinrateGraph 最小间距计算从 O(n²) 改为 O(n) 相邻比较的排序假设

**文件**：`WinrateGraph.java:2369-2377` 和 `2390-2398`

**改动**：`minPositiveGraphColumnSpacing` 和 `minPositiveQuickOverviewColumnSpacing` 从 O(n²) 双重循环改为 O(n) 相邻比较。

**疑虑**：如果 points 不按 x 坐标排序，相邻比较会遗漏非相邻点之间更小的间距。

**结论：不是问题。**

- `GraphPoint` 的 x 坐标由 `graphPointX(moveNumber)` 计算，是对 `moveNumber` 的线性映射（整数像素）。
- 三个 builder 均从历史末尾向前遍历（end → root），但相邻元素 moveNumber 差值上界固定：default 模式最多跳 1（variation 跳转处产生大跳，不产生小跳），engine 模式产生 (N, N-2, N-1, N-3, ...) 的交错序列（相邻差 ≤ 2），dualCurve 模式同一节点添两次（差 0，被过滤）后差 ≥ 1。
- 在所有三种 builder 中，序列的全局最小正间距（≥ 1）均必然出现在某对相邻元素之间，因为每次"交错/跳转"只引入大跳，不会产生非相邻元素之间距离小于所有相邻对的情况。
- `QuickOverviewPoint` 按 moveNumber 顺序线性构造，严格单调，O(n) 相邻比较天然正确。

### 2.5 测试中 `readBoardRestartLock` 反射初始化的脆弱性

**文件**：`ReadBoardShutdownTest.java:228-234`、`LizzieFrameRegressionTest.java:311-316`

**改动**：`LizzieFrame.readBoardRestartLock` 从 DCL 懒初始化改为 `= new Object()` 字段初始化器。测试使用 `Unsafe.allocateInstance` 绕过构造函数，需要通过反射手动初始化该字段。

**疑虑**：字段重命名时测试不会编译期报错，只会运行时抛 `NoSuchFieldException`。

**结论：存在但极低风险，不需要改动。**

- 这是 `Unsafe.allocateInstance` 绕过构造函数的固有限制，不是本轮改动引入的设计缺陷。
- 字段重命名时测试会立即失败并明确报错（`RuntimeException` 包装 `NoSuchFieldException`），不会静默通过。
- 生产代码不受影响。

## 3. 各维度覆盖结论

| 维度 | 结论 |
|---|---|
| 正确性 | 所有改动与上位契约一致。`matchesStones` 跳过 marker、移除 `hasMarker()` 前置条件均为 spec 要求的行为 |
| 边界条件 | WinrateGraph O(n) 相邻比较安全：三种 builder 的 x 序列中全局最小正间距始终由某对相邻元素体现。空列表、单元素列表安全（循环不进入）。`normalizeSnapshot` vs `normalizeConflictSnapshot`：非 fox-recovery 路径行为等价，fox-recovery 路径正确跳过 marker 位置 |
| 状态同步 | `readBoardRestartLock` 从 DCL 改为字段初始化器消除了竞态。测试通过反射补偿 `Unsafe.allocateInstance` |
| 异常传播 | 反射初始化的异常包装为 `RuntimeException`，fail-fast，无静默吞异常 |
| 资源释放 | 删除的死代码不涉及资源持有。无新问题 |
| API 合约 | `allowsFoxMarkerlessSingleStep` 放宽了准入条件，但与 spec 定义的放宽范围完全一致 |
| 测试缺口 | 无新增缺口。现有 219 个 sync 相关测试全部通过 |

## 4. 死代码清理

本轮同时清理了以下确认的死代码（零调用者）：

- `Board.collectSnapshotStones(BoardData)` 及其仅被它调用的 `compareRootSnapshotStonesByMoveNumber`、`compareRootSnapshotStonesByPosition`
- `ReadBoard.snapshotCodes()`

清理后构建成功，219 个 sync 相关测试全部通过，10 个预存失败测试与本轮改动无关。
