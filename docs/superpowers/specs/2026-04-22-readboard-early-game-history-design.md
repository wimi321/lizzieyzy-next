# ReadBoard Early-Game History Design

日期：2026-04-22

分支：`fix-sync`

上位契约：[docs/SNAPSHOT_NODE_KIND.md](/mnt/d/dev/weiqi/lizzieyzy-next/docs/SNAPSHOT_NODE_KIND.md)

## 1. 背景

当前 `fix-sync` 分支在主线窗口同步、无引擎重建恢复、野狐房间/棋谱上下文恢复上已经修到可用，但仍存在一个稳定回归：

- 正常对局里，从空棋盘开始同步，前 1、2 手不会进入真实历史，通常到第 3 手才开始正常记录。
- 让子棋里，从让子初始盘面开始同步，第 1 手不会进入真实历史，通常从第 2 手开始正常记录。

该问题满足以下实测前提：

- 同步已经开启。
- `lizzieyzy-next` 会先稳定显示空棋盘或让子初始盘面。
- 后续中盘同步、主线窗口内导航、房间/棋谱切换等前面已修复的行为基本正常。

因此，本问题不是“打开同步时已经错过前几手”的采样时机问题，而是 `lizzieyzy-next` 对早期单步同步帧的分类与落地逻辑仍有缺口。

## 2. 目标与非目标

### 2.1 目标

- 修复野狐同步开局前几手被错误收敛成 `SNAPSHOT` 的问题。
- 让正常对局从第 1 手开始进入真实 `MOVE` 历史。
- 让让子初始盘面之后的第 1 手开始进入真实 `MOVE` 历史。
- 保持当前 `fix-sync` 对真实历史的收敛边界不变：只保留可证明的一手，不伪造多手历史。

### 2.2 非目标

- 不放宽 generic / 非野狐平台的恢复能力。
- 不为无 marker 的吃子、多手跳转、回退、缺 pass 等场景补造真实历史。
- 不修改 `readboard` 协议。
- 不把 root setup / handicap / `hasStartStone` 重新升格成普通同步历史容器。

## 3. 现状原因

当前实现里，早期历史丢失由三条链路叠加造成。

### 3.1 无 marker 的单颗新增在入口就被判成“不能增量”

[SyncSnapshotClassifier.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/main/java/featurecat/lizzie/analysis/SyncSnapshotClassifier.java) 的 `SnapshotDelta.allowsIncrementalSync()` 目前只允许：

- `0` 变化且无 marker
- `1` 次新增且有 marker，且 marker 与新增落点一致

对“`1` 次新增、`0` 次移除、无 marker”直接返回 `false`。

但 `ReadBoard.syncBoardStones(...)` 下方的实际增量执行逻辑，本身可以处理 `snapshotCode == 1/2` 的单颗新增。因此当前是“执行层支持，入口分类先拒绝”。

### 3.2 早期非增量帧会过早掉入 `FORCE_REBUILD`

[ReadBoard.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/main/java/featurecat/lizzie/analysis/ReadBoard.java) 的完整恢复流程中：

- `SINGLE_MOVE_RECOVERY` 仍要求 `snapshotDelta.hasMarker()`
- steady-state 下，只要 `remote recoveryMoveNumber != syncStartNode.moveNumber`，就会直接走 `FORCE_REBUILD`

因此，一旦野狐在开局前几手发来“无 marker 的单颗新增”帧：

- 它不会进入普通增量路径
- 也不会进入单步恢复路径
- 最终会被静态重建成新的 `SNAPSHOT`

这会把已可证明的早期真实手顺提前截断。

### 3.3 普通增量成功后没有推进 `lastResolved`

设计文档里，`lastResolved` 的定义是“最后一次成功落地同步结果时，对应的本地主线节点锚点”。

但当前实现只在以下路径更新 `lastResolved`：

- 现有节点命中的 `NO_CHANGE`
- `SINGLE_MOVE_RECOVERY`
- `FORCE_REBUILD`

普通增量 `placeForSync(...)` 成功后，没有同步推进 `lastResolved`。这会导致后续一帧即使只是“差一手的无 marker 单步新增”，也无法稳定命中 docs 里定义好的 `lastResolved + 1` 语义。

### 3.4 `firstSync flatten` 是错误加剧项，但不是唯一主因

[ReadBoard.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/main/java/featurecat/lizzie/analysis/ReadBoard.java) 当前仍保留：

- `hasStartStone = true`
- `addStartListAll()`
- `flatten()`

这会把已证明的同步前缀压回 root setup / root `SNAPSHOT`，与当前分支契约冲突。

但基于实测，“空棋盘 / 让子初始盘面已经先稳定显示，后面前几手仍丢历史”，说明这段旧逻辑不是唯一根因。真正核心仍是 3.1 到 3.3。

## 4. 选项比较

### 4.1 方案 A：只删 `firstSync flatten`

优点：

- 改动最小。
- 能消除“把已证明前缀压回 root setup”的旧逻辑。

缺点：

- 不能解决“无 marker 单颗新增被入口拒绝”的核心问题。
- 也不能解决普通增量后 `lastResolved` 不推进的问题。

结论：

- 应该做，但不足以单独修复本问题。

### 4.2 方案 B：只放宽无 marker 单步，不动 `lastResolved`

优点：

- 直接命中最明显的入口缺口。

缺点：

- 后续一旦出现相邻的第二个同类帧，仍可能因为 `lastResolved` 停在旧节点而过早重建。

结论：

- 不推荐单独采用。

### 4.3 方案 C：放宽野狐无 marker 单步 + 推进 `lastResolved` + 删除 `firstSync flatten`

优点：

- 能同时修掉入口拒绝、后继锚点滞后和首帧压平三个问题。
- 仍然只在野狐受控边界内放宽，不改 generic 平台。
- 与当前 docs 的“只保留可证明单步”边界一致。

缺点：

- 需要补测试并调整当前把错误行为锁成预期的旧断言。

结论：

- 采用本方案。

## 5. 设计

### 5.1 放宽范围

只在 `FOX` 恢复元数据有效时放宽：

- `syncPlatform == FOX`
- `remoteContext.supportsFoxRecovery() == true`
- `recoveryMoveNumber()` 存在

仅额外接受这一类原本会被拒绝的帧：

- 相对当前同步基线只有 `1` 颗新增石子
- `0` 颗移除石子
- 无 marker
- `recoveryMoveNumber == syncStartNode.moveNumber + 1`

这类帧按“可证明的一手新增”处理。

### 5.2 明确不放宽的场景

以下场景仍继续按当前 docs 边界处理：

- 无 marker 吃子
- 无 marker 多颗新增
- 回退
- 多手跳转
- 缺失真实 pass
- 顺序不唯一
- generic / 非野狐平台

这些场景仍只能：

- `NO_CHANGE`
- `SINGLE_MOVE_RECOVERY`
- `HOLD`
- `FORCE_REBUILD`

中的现有合法结果，不能因为这轮修复被误判成真实单步历史。

### 5.3 `lastResolved` 语义收口

以后只要一次同步成功落地到确定节点，就推进 `lastResolved`。

覆盖范围：

- 现有节点命中
- `SINGLE_MOVE_RECOVERY`
- 普通增量落子
- `FORCE_REBUILD`

不覆盖：

- `HOLD`
- 失败或中止的同步帧

这样可以让 docs 中的：

- `lastResolved` 精确命中
- `lastResolved + 1` 且这一手唯一可证

在普通同步成功后持续成立，而不是只对重建/单步恢复生效。

### 5.4 删除同步主路径里的 `firstSync flatten`

从同步增量主路径中移除：

- `Lizzie.board.hasStartStone = true`
- `Lizzie.board.addStartListAll()`
- `Lizzie.board.flatten()`

原因：

- 这段逻辑会把已经证明的真实同步前缀压回 root setup / root `SNAPSHOT`
- 与 [docs/SNAPSHOT_NODE_KIND.md](/mnt/d/dev/weiqi/lizzieyzy-next/docs/SNAPSHOT_NODE_KIND.md) 当前对 root setup 的静态语义定义冲突

保留的边界：

- 真正的让子初始盘面、root setup、静态盘面重建仍只通过 `FORCE_REBUILD -> SNAPSHOT` 落地
- 不再通过首帧增量同步的副作用写入 `hasStartStone/startStonelist`

## 6. 代码实现边界

### 6.1 `ReadBoard`

文件：[ReadBoard.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/main/java/featurecat/lizzie/analysis/ReadBoard.java)

新增一个只在 `ReadBoard` 内部使用的判断层，替代直接把 `snapshotDelta.allowsIncrementalSync()` 当作唯一入口。

建议语义：

- `snapshotDelta.allowsIncrementalSync()` 成立时，照旧
- 否则，额外允许“野狐无 marker 单颗新增且 `moveNumber == current + 1`”进入增量落子路径

同时：

- 普通增量同步成功后，显式 `rememberResolvedSnapshotNode(currentSyncEndNode, ...)`
- 删除同步主路径中的 `firstSync flatten`

### 6.2 `SyncSnapshotClassifier`

文件：[SyncSnapshotClassifier.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/main/java/featurecat/lizzie/analysis/SyncSnapshotClassifier.java)

本轮不把 generic 语义直接改宽。

保持其通用分类接口大体稳定，避免影响非野狐平台与现有 conservative path。野狐无 marker 单步放宽逻辑优先在 `ReadBoard` 侧按远端上下文包一层实现。

### 6.3 测试基线调整

当前有一部分测试把“野狐无 marker 单步前进直接重建”锁成了预期，这与本轮修复目标冲突，必须同步调整。

尤其需要检查并更新：

- [ReadBoardSyncDecisionTest.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java)

## 7. 测试计划

### 7.1 `ReadBoardSyncDecisionTest`

新增：

- `FOX + 无 marker + 单颗新增 + foxMoveNumber = current + 1`
  - 期望：不重建，追加一个真实 `MOVE`
- 同上，但发生在正常对局开局第 1 手
  - 期望：第 1 手进入真实历史
- 让子初始盘面后的第 1 手满足同样条件
  - 期望：这一步进入真实历史
- `FOX + 无 marker + 单颗新增，但 foxMoveNumber != current + 1`
  - 期望：仍重建
- `FOX + 无 marker + 吃子`
  - 期望：仍重建
- `FOX + 无 marker + 多颗新增`
  - 期望：仍重建

调整或替换现有过时断言：

- 当前把“野狐无 marker 单步前进直接重建”为预期的用例，需要改成只在不满足“单颗新增且差一手”时才重建。

### 7.2 `ReadBoardSyncDecisionTest` / `ReadBoardResumeLifecycleTest`

新增：

- 普通增量落子成功后，`lastResolved` 被推进到新的落地节点
- 再来一帧符合“无 marker 单颗新增且差一手”的野狐帧时，仍能继续接写，而不是因为旧锚点停滞而重建

### 7.3 首帧压平回归

新增：

- 开局前两手成功增量后，不写入 `hasStartStone/startStonelist`
- root 不会因为普通同步前缀被错误改造成 setup root

### 7.4 聚焦回归

复跑：

- [ReadBoardSyncDecisionTest.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java)
- [ReadBoardEngineResumeTest.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/test/java/featurecat/lizzie/analysis/ReadBoardEngineResumeTest.java)
- [ReadBoardResumeLifecycleTest.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/test/java/featurecat/lizzie/analysis/ReadBoardResumeLifecycleTest.java)
- [SyncSnapshotRebuildPolicyTest.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java)

## 8. 风险

### 8.1 误把 setup 当成真实一手

本轮主要风险是把“静态 setup 增子”误判成真实 `MOVE`。

防线：

- 只在 `FOX` 恢复元数据有效时启用
- 必须是单颗新增
- 必须没有移除
- 必须 `recoveryMoveNumber == current + 1`

### 8.2 generic 路径被意外放宽

防线：

- 不直接修改 generic 的分类语义
- 放宽逻辑放在 `ReadBoard` 的野狐上下文分支中

### 8.3 首帧 flatten 删除后影响旧兼容行为

这条风险存在，但从当前分支契约看，旧行为本身已不正确。

防线：

- 只删除 `ReadBoard` 同步主路径中的首帧 flatten
- 不动真正 root setup / handicap / `FORCE_REBUILD -> SNAPSHOT` 的保留逻辑

## 9. 结论

本轮采用：

- 放宽野狐无 marker 单颗新增且差一手的单步同步
- 在普通增量成功后推进 `lastResolved`
- 删除同步主路径中的 `firstSync flatten`

该方案能命中当前“开局前几手不进历史”的真实根因，同时保持 `fix-sync` 已确认的“只保留可证明真实手顺”的边界不变。
