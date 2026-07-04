# ReadBoard `lastMoveSource` 与 GMA 轮次可信度设计（2026-06-26）

## 背景

GMA 自动落子已改为收到 `play>... gma` 后等待下一帧 ReadBoard 棋盘数据落地，再根据同步后的 `isBlacksTurn()` 启动 `kata-genmove_analyze`。这个修复解决了“旧当前节点”问题，但没有解决“同步局面 side-to-play 本身不可信”的问题。

野狐让子棋、试下模式和白方首手场景下，`foxMoveNumber % 2` 不能作为权威轮次。ReadBoard 当前又只把末手压成 `BlackLastMove` / `WhiteLastMove`，Lizzie 无法区分真实视觉 marker 和启发式猜测。

本设计遵守 `docs/SNAPSHOT_NODE_KIND.md`：

- ReadBoard 结果仍是 `SNAPSHOT`，不是 `MOVE` / `PASS`。
- `foxMoveNumber`、marker、手数奇偶只作为辅助元数据，不能补造真实历史。
- `foxMoveNumber` 继续修正 `SNAPSHOT` moveNumber metadata，不改写已证明的真实 `MOVE/PASS`。
- setup / `PL` / `AB` / `AW` / `HA` 语义仍属于静态 `SNAPSHOT` 契约。

## 目标

1. 解析 ReadBoard 新增的 `lastMoveSource <token>`。
2. 让 side-to-play 推断优先使用可信视觉 marker，而不是无条件使用 `foxMoveNumber` 奇偶。
3. 让 GMA 只在当前同步局面的轮次可信时启动。
4. 保持 markerless 普通局和旧 ReadBoard 协议兼容。

## Non-Goals

- 不改变 `inferSnapshotMoveNumber(...)` 中 `foxMoveNumber` 作为 moveNumber metadata 的用途。
- 不改变 `SyncSnapshotRebuildPolicy`、conflict key 或 history matching。
- 不把 `SNAPSHOT` 伪造成真实 `MOVE` / `PASS`。
- 不支持启发式末手在让子棋/GMA 中充当权威轮次。
- 不删除 `deviation` / `stoneCount` 启发式；它们仍可服务普通同步和诊断，只是不提升为可信视觉 marker。

## 协议解析

ReadBoard 新增 outbound line：

```text
lastMoveSource <token>
```

Lizzie 需要解析以下 token：

| token | 含义 | GMA 轮次可信度 |
| --- | --- | --- |
| `none` | 无末手 | 不提供 marker 可信度 |
| `redBlueMarker` | 红/蓝角标 | 可信 |
| `foxCornerFlip` | 野狐默认右下反色缺口 | 可信 |
| `deviation` | 颜色偏差推断 | 不可信 |
| `stoneCount` | 棋子数量推断 | 不可信 |

未知 token 按 `unknown` 处理，不能提升为可信视觉 marker。旧 ReadBoard 不发送该行时按 legacy unknown 处理：普通同步保持兼容，GMA 风险场景保守。

`lastMoveSource` 只属于当前 ReadBoard board frame。每个新 frame 的默认值是 legacy unknown；如果本帧在 `end` 前没有收到 `lastMoveSource`，就按 legacy unknown 处理。`end`、`clear`、新的 `start`、停止同步和任何会重置 pending remote context 的控制行，都必须清掉上一帧 source。未知 token 也只能落到 `unknown`，不得沿用上一帧 `redBlueMarker` / `foxCornerFlip` 的可信值。

## side-to-play 推断

当前 `inferSnapshotBlackToPlay(...)` 的问题是：

```java
if (foxMoveNumber.isPresent()) {
  return foxMoveNumber.getAsInt() % 2 == 0;
}
```

这会让 `foxMoveNumber` 压过真实 marker。新优先级：

1. 空盘和既有 setup / `PL` 语义保持高优先级。
2. Fox `foxMoveNumber 0` 且 markerless 盘面只有多颗黑方 setup 石：按让子初始局固定规则判为白方落子。
3. `snapshotDelta.hasMarker()` 且 source 是 `redBlueMarker` 或 `foxCornerFlip`：按 marker 棋色推断下一手。
4. source 是 `deviation` 或 `stoneCount`：marker 不能作为 GMA 权威轮次；只有上一条 Fox 零手全黑 setup 例外可按固定 setup 规则判定。
5. markerless 普通局才允许 `foxMoveNumber % 2` 兜底。
6. 其他情况回到现有 `inferBlackToPlayWithoutMarker(...)` 的保守路径。

`inferSnapshotMoveNumber(...)` 不变：`foxMoveNumber` 仍优先作为 `SNAPSHOT` moveNumber metadata。

这里的 “markerless 普通局” 是一个可实现的保守判定，只用于决定 GMA 是否可信任 `foxMoveNumber` fallback。必须同时满足：

- 本帧没有 marker，且 `lastMoveSource` 是 `none` 或 legacy unknown。
- `foxMoveNumber` 存在。
- `snapshotDelta` 表示普通行棋形态：无 removals；若有变化，只能是单一 addition，不能是多子 setup add/remove。
- 当前同步起点和将创建/更新的 `SNAPSHOT` 没有 setup/handicap 风险信号。

setup/handicap 风险信号包括任一项：

- 全局 `Lizzie.board.hasStartStone` 或 `startStonelist` 非空。
- 相关 `BoardHistoryNode` 的 `hasRemovedStone()` 为 true。
- 相关 `BoardHistoryNode.extraStones` 非空。
- 相关 `BoardData.getProperties()` 含 `AB`、`AW`、`AE`、`PL` 或 `HA`。
- 当前 `snapshotDelta` 含 removals，或 additions 大于 1。

只要存在这些信号，GMA 不得把 `foxMoveNumber % 2` 当作权威轮次；普通同步仍可沿用既有保守路径。

Fox 零手全黑 setup 是单独规则，不属于 markerless ordinary fallback：必须是 `syncPlatform fox`、`foxMoveNumber 0`、无 marker、无 removals、无可信视觉 source、盘面只有至少两颗黑石且没有白石。该规则只确定初始让子局白方先下，不把 `stoneCount` / `deviation` 作为末手证据。

## GMA 轮次可信度

新增轻量状态记录最近一次 ReadBoard 同步局面的 turn trust。`scheduleReadBoardGmaIfNeeded(...)` 在启动前除检查：

- GMA active
- no pending request
- both sync
- engine capability ready
- `isBlacksTurn()` 与自动落子棋色匹配

还要检查当前同步局面的轮次可信。

可信来源：

- `redBlueMarker`
- `foxCornerFlip`
- 明确 setup / `PL`
- 空盘默认黑方开局
- ReadBoard“交换顺序”按钮发送的 `pass` 行，作为显式手动轮次覆盖
- 同步主流程已接受并落地的一手真实 `MOVE`
- Fox `foxMoveNumber 0` + 全黑多子 setup 的让子初始局默认白方
- markerless 普通局的 `foxMoveNumber` fallback，且没有让子/setup 风险

不可信来源：

- `deviation`
- `stoneCount`
- legacy unknown 且存在让子/setup 风险
- `foxMoveNumber` 与可信视觉 marker 冲突时的奇偶结果

不可信时只跳过本次 GMA 调度并记录 debug log，不清除自动落子配置。下一帧如果出现可信视觉 marker 或明确轮次，再正常启动。

## 兼容策略

- 新 Lizzie + 新 ReadBoard：使用 `lastMoveSource` 完整可信度。
- 新 Lizzie + 旧 ReadBoard：普通同步按旧行为兼容；GMA 在让子/setup 风险下保守跳过。
- 旧 Lizzie + 新 ReadBoard：旧端忽略未知 `lastMoveSource` 行，仍按旧协议处理。

## 代码改动范围

预计改动：

- `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
- ReadBoard/GMA 相关单元测试
- `docs/SNAPSHOT_NODE_KIND.md`
- `docs/specs/2026-06-24-readboard-gma-engine-decision-design.md`

ReadBoard 侧主 spec 位于 ReadBoard feature worktree：

- `/home/dev/.config/superpowers/worktrees/readboard/kata-genmove-analyze-sync/docs/specs/2026-06-26-last-move-source-gma-turn-trust-design.md`

## 测试要求

1. 解析 `lastMoveSource redBlueMarker` / `foxCornerFlip` / `deviation` / `stoneCount`。
2. visual source 与 `foxMoveNumber` 奇偶冲突时，`blackToPlay` 跟随 marker。
3. `deviation` / `stoneCount` 在让子/GMA 场景不作为权威轮次。
4. markerless 普通局 `foxMoveNumber` 旧用例仍通过。
5. 旧协议缺少 `lastMoveSource` 不破坏普通同步。
6. GMA 在可信视觉 marker 下按正确方启动。
7. GMA 在不可信启发式末手/让子冲突下不误启动。
8. 连续帧中 source 不泄漏：上一帧 visual source 不会污染下一帧缺行或 unknown token。
9. legacy unknown + setup/handicap 风险跳过 GMA；markerless ordinary + `foxMoveNumber` 仍兼容。
10. Fox `foxMoveNumber 0` + 全黑多子 setup 判为白方落子并可启动执白 GMA。
11. ReadBoard“交换顺序”后，GMA 按手动覆盖的当前轮次重新判断。

## 成功标准

- `foxMoveNumber` 不再压过真实视觉 marker。
- GMA 只在可信同步局面上启动。
- ReadBoard 同步仍产出 `SNAPSHOT`，不补造真实历史。
- 现有 `ReadBoardEngineResumeTest` 和相关 GMA 测试继续通过。
