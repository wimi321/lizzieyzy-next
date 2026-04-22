# ReadBoard Mainline Window Sync Design

日期：2026-04-22

## 1. 背景

在 `fix-sync` 当前实现里，已有两类实机回归仍然存在：

1. 本地已有历史时，回退到某个祖先节点后，再前进一手，有时会停在回退后的那一手，不同步到目标手。
2. 本地已有历史时，回退多手到仍然属于当前主线祖先链的目标，有时会误触发 `FORCE_REBUILD`。

这两类现象都已确认不是 `readboard` 标题手数抖动导致：

- `readboard` 侧标题手数在复现时保持稳定正确。
- `LIVE_ROOM` 和 `RECORD_VIEW` 都会出现。
- “回退后一手前进卡住”时，更常见的现象是停留在回退后的那一手；等远端再前进一步时，lizzie 直接跳到更后一手。

因此，这轮问题定义为：`lizzieyzy-next` 本地对“已有主线节点”的命中与导航规则不完整，而不是远端输入不稳定，也不是 docs 既有边界要求重建。

## 2. 目标与非目标

### 2.1 目标

- 当远端目标已经是本地当前主线里的一个现有节点时，直接导航到该节点，不重建，不补写历史。
- 保持现有 docs 的四类外部结果：`NO_CHANGE`、`SINGLE_MOVE_RECOVERY`、`HOLD`、`FORCE_REBUILD`。
- 同时修复：
  - 回退到祖先后，再前进到已存在主线节点时卡住
  - 有历史时，回退到窗口内祖先仍误重建

### 2.2 非目标

- 不放宽真实历史推断边界。
- 不做多步补写，不做批量回放推断。
- 不改 `readboard` 协议。
- 不扩展非野狐平台的上下文恢复逻辑。
- 不通过全树搜索“盘面相同节点”来回捞旧历史。

## 3. 现状问题

当前 [ReadBoard.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/main/java/featurecat/lizzie/analysis/ReadBoard.java:956) 的完整恢复流程，核心上仍以 `mainEnd` 为同步基线：

1. 先从 `syncStartNode = mainEnd` 向祖先扫描命中。
2. 未命中时，再只尝试 `lastResolved.next()` 的单步邻接。
3. 再尝试 `SINGLE_MOVE_RECOVERY`。
4. 最后才进入 `HOLD / FORCE_REBUILD`。

这会漏掉一个已有历史但当前实现没有显式表达的情况：

- 当前视图已经回退到祖先节点 `current`
- 主线末端 `mainEnd` 仍停在更后的位置
- 远端新目标并不是“当前节点不变”，也不是“从 `lastResolved` 只前进一步的新造历史”
- 它其实是“当前主线里已经存在的另一个节点”

因为当前决策流没有“已有主线节点直达”的规则，这类场景会表现为：

- 被错误当成无需变化，停在祖先不动
- 或者错过已有节点命中后，落进 `HOLD / FORCE_REBUILD`

## 4. 核心设计

### 4.1 外部结果不变，内部细化 `NO_CHANGE`

本轮不修改 docs 对外暴露的四类结果。

但在内部实现上，把当前被模糊压成 `NO_CHANGE` 的情况拆成两个子语义：

- `STAY_ON_CURRENT`
  - 远端目标就是当前显示节点。
- `NAVIGATE_EXISTING_MAINLINE_NODE`
  - 远端目标不是当前显示节点，但它已经是本地当前主线中的一个现有节点。
  - 这时直接跳到该节点，不重建，不补写历史。

对外仍可统一归类为 `NO_CHANGE` 路径，因为它不新增真实历史，也不触发 rebuild。

### 4.2 当前保留主线窗口

为避免重新回到“盘面像就乱接旧历史”，命中范围必须严格限制在“当前保留主线窗口”。

当前保留主线窗口定义为：

- 当前显示节点 `current`
- `current` 的主线祖先链
- 从 `current` 沿当前主线向前直到当前主线末端 `mainEnd` 的已有节点链

不属于窗口的范围：

- variation 节点
- 当前主线之外，只是盘面相同的旧节点
- 需要跨越已被视为不可证明的旧同步段才能接上的节点
- 需要跨越 rebuild 形成的 `SNAPSHOT` 断口才能恢复的更老节点

### 4.3 命中原则

窗口命中仍然依赖现有“远端身份 + 盘面”规则：

- 不是只看手数
- 不是只看 marker
- 不是只看 `foxMoveNumber`
- 仍然要求：
  - `stones`
  - `recoveryMoveNumber()`
  - `window context`（如 `roomToken`、`titleFingerprint`、`recordTotalMove`）

这条规则只解决“导航到已有节点”，不产生任何新历史。

### 4.4 启用范围

这条“窗口内已有节点直达”规则只在 `FOX` 恢复元数据有效时启用：

- `LIVE_ROOM`
- `RECORD_VIEW`

非野狐平台继续保持 conservative mode：

- 当前盘面相同：`NO_CHANGE`
- 能唯一证明是一手：`SINGLE_MOVE_RECOVERY`
- 否则：`FORCE_REBUILD`

## 5. 决策流调整

当前实现的决策顺序调整为：

1. 先判上下文是否断开
   - `roomToken` / `titleFingerprint` / `recordTotalMove` 等强失效条件
2. 再在“当前保留主线窗口”里查找已有节点命中
   - 当前节点是否已经是目标
   - 当前节点祖先链是否命中
   - 当前节点到 `mainEnd` 之间的已有主线后继是否命中
3. 窗口命中时：
   - 命中当前节点：`STAY_ON_CURRENT`
   - 命中窗口内其他现有节点：`NAVIGATE_EXISTING_MAINLINE_NODE`
4. 窗口未命中时，再继续现有次级恢复路径：
   - `lastResolved` 单步邻接
   - `SINGLE_MOVE_RECOVERY`
   - `HOLD / FORCE_REBUILD`

其中关键约束是：

- 不能因为 `mainEnd` 更靠后，就把窗口之前已不可证明的历史重新算作可恢复历史。
- 不能因为 variation 里存在相同盘面，就误命中到错误节点。

## 6. 代码实现边界

### 6.1 `ReadBoard`

[ReadBoard.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/main/java/featurecat/lizzie/analysis/ReadBoard.java) 里的 `resolveCompleteSnapshotRecovery(...)` 不再只返回一个没有上下文的简单 enum。

建议改成返回一个内部决策对象，至少包含：

- `outcome`
- `resolvedNode`
- `shouldNavigate`

这样可以显式区分：

- 留在当前节点
- 跳转到窗口内已有节点
- `SINGLE_MOVE_RECOVERY`
- `HOLD`
- `FORCE_REBUILD`

### 6.2 `SyncSnapshotRebuildPolicy`

[SyncSnapshotRebuildPolicy.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/main/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicy.java) 新增“当前保留主线窗口命中”辅助方法。

该方法输入至少包括：

- `currentNode`
- `mainEnd`
- `snapshotCodes`
- `remoteContext`

该方法行为：

- 只扫描当前主线窗口
- 不扫描 variation
- 不扫描整棵树
- 命中时返回窗口内的现有主线节点

现有的：

- `findMatchingHistoryNode(...)`
- `findAdjacentMatchFromLastResolvedNode(...)`

继续保留，但顺序后移，成为窗口命中失败后的下一层恢复策略。

## 7. docs 对齐

本轮实现完成后，需要同步更新：

### 7.1 分支契约

[docs/SNAPSHOT_NODE_KIND.md](/mnt/d/dev/weiqi/lizzieyzy-next/docs/SNAPSHOT_NODE_KIND.md)

在同步决策规则中补充：

- 命中当前保留主线窗口内的已有节点时，直接导航到该节点。
- 这类导航不生成新历史，不触发 rebuild。
- 这条规则不允许跨 variation、跨旧 `SNAPSHOT` 断口、跨不可证明窗口回捞历史。

### 7.2 设计文档

[2026-04-21-readboard-sync-boundaries-design.md](/mnt/d/dev/weiqi/lizzieyzy-next/docs/superpowers/specs/2026-04-21-readboard-sync-boundaries-design.md)

需要在 `LIVE_ROOM` 与 `RECORD_VIEW` 决策矩阵中都加入：

- 若远端目标已是当前保留主线窗口内的现有节点，则直接导航到该节点

同时补充：

- 当前保留主线窗口的定义
- variation 不参与窗口命中
- 命中已有节点属于已有历史导航，不等于放宽真实历史恢复边界

## 8. 测试计划

优先扩展：

- [ReadBoardSyncDecisionTest.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java)
- [SyncSnapshotRebuildPolicyTest.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java)

### 8.1 `ReadBoardSyncDecisionTest`

新增或补强以下场景：

- `LIVE_ROOM`：从祖先前进到已存在的下一手主线节点，直接导航，不重建
- `LIVE_ROOM`：从祖先前进到已存在的更后主线节点，直接导航，不重建
- `LIVE_ROOM`：有历史时，多手回退到窗口内祖先，直接导航，不重建
- `LIVE_ROOM`：目标落在窗口外，仍然 `FORCE_REBUILD`
- `RECORD_VIEW`：补一组等价用例
- variation 上存在相同盘面节点时，不允许误命中 variation

### 8.2 `SyncSnapshotRebuildPolicyTest`

新增或补强以下场景：

- 当前保留主线窗口内已有节点优先命中
- variation 不参与窗口命中
- 当前节点与 `mainEnd` 不同的情况下，窗口前向命中仍能正确返回目标节点

## 9. 验收标准

修复后，以下行为应稳定成立：

- 回退到祖先后，再前进到已存在节点时，不会停在祖先不动
- 有历史时，多手回退到窗口内祖先时，不会误 `FORCE_REBUILD`
- 远端目标落到窗口外时，仍然按现有 docs 走重建
- variation 上的相同盘面不会被误认为可直接恢复的已有主线节点
- 该规则不改变非野狐 conservative mode

## 10. 风险与约束

本轮最大风险不是 rebuild，而是“误命中错误的已有节点”。

因此实现时必须坚持：

- 只扫当前主线窗口
- 不扫 variation
- 不扫整棵树
- 不跨旧同步断口回捞历史
- 不把“已有节点导航”偷换成“允许恢复更多真实历史”

只要这几条保持住，这轮修复就属于“补齐已有历史节点直达规则”，不是重新放宽同步边界。
