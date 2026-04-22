# 读盘同步边界设计

- 日期：2026-04-21
- 分支：`fix-sync`
- 状态：已记录当前已确认边界，供后续实现与子代理对齐
- 上位契约：[docs/SNAPSHOT_NODE_KIND.md](/mnt/d/dev/weiqi/lizzieyzy-next/docs/SNAPSHOT_NODE_KIND.md)

## 1. 目标与范围

本设计只定义 `readboard -> lizzieyzy-next` 的同步边界，目标有三条：

1. 同步后的目标盘面正确。
2. 本地历史只保留可证明的真实 `MOVE/PASS`。
3. 无法证明的中间顺序统一收敛成新的 `SNAPSHOT` 锚点。

本设计不做以下承诺：

- 不把 `foxMoveNumber`、房间号、标题手数升格成真实历史。
- 不把房间号或标题文本当作棋局唯一标识。
- 不为新浪、弈城等非野狐平台做与野狐同等级的上下文恢复能力。

如本设计与 [docs/SNAPSHOT_NODE_KIND.md](/mnt/d/dev/weiqi/lizzieyzy-next/docs/SNAPSHOT_NODE_KIND.md) 冲突，以后者为准；实现应优先满足上位契约。

## 2. 已确认术语

- 远端快照身份：用于判断“当前读到的是不是同一个远端静态局面”，不是用于伪造真实手顺。
- `LIVE_ROOM`：野狐房间/观战/对局类窗口上下文。
- `RECORD_VIEW`：野狐打开棋谱后的浏览窗口上下文。
- `UNKNOWN`：标题和辅助元数据都不足以判定上下文的保守路径。
- `lastResolved`：最后一次成功落地同步结果时，对应的本地主线节点锚点。
- 当前主线祖先链：仅指当前主线从当前节点向前追溯到 root 的祖先链，不包含旁支 variation。

## 3. 远端身份与元数据边界

### 3.1 总原则

- `foxMoveNumber` 绑定到“远端快照身份”，不绑定到“真实历史手顺”。
- `marker` 是增强信号，不是硬前提；稳定时可参与命中，不稳定时不能绑架同步流程。
- `marker` 抖动同时包含颜色抖动和“本帧有 marker、下一帧无 marker”的抖动；这类抖动只能弱化 `marker` 信号，不能单独制造新的冲突身份。
- `blackToPlay` 在 markerless 场景下不能作为硬身份键，因为让子棋、`setup/PL`、标题奇偶都可能让它不可靠。
- `blackToPlay` 只在来源可信时参与辅助判断；否则只作为 rebuild 后的元数据填充候选。

### 3.2 可信与不可信的 `blackToPlay`

可信来源：

- 稳定且唯一的 marker。
- 本地已有 `SNAPSHOT` 上的显式 `PL`。

不可信来源：

- `foxMoveNumber % 2` 的奇偶推导。
- markerless 时沿用当前节点轮次。

不可信来源不能进入 markerless 远端身份硬匹配。

### 3.3 `LIVE_ROOM` 身份键

`LIVE_ROOM` 的主身份为：

- `stones + foxMoveNumber`

当 marker 唯一且稳定时，增强为：

- `stones + foxMoveNumber + marker`

若 marker 缺失或短暂不稳定，则直接退回 `stones + foxMoveNumber` 主身份，不把这类抖动额外派生成新的冲突类别。

额外辅助失效信号：

- `roomToken`

其中：

- `roomToken` 只截取到标题里第一个 `号` 为止。
- `roomToken` 保留原始字符串，不强转纯数字。
- `roomToken` 不是棋局唯一 ID，只用于“换房偏向失效”。
- 若当前 `LIVE_ROOM` 帧缺少有效 `foxMoveNumber`，则不进入野狐完整恢复路径，直接退回保守路径。

### 3.4 `RECORD_VIEW` 身份键

`RECORD_VIEW` 的主身份为：

- `stones + currentMoveFromTitle`

当 marker 唯一且稳定时，增强为：

- `stones + currentMoveFromTitle + marker`

若 marker 缺失或短暂不稳定，则直接退回 `stones + currentMoveFromTitle` 主身份，不把这类抖动额外派生成新的冲突类别。

辅助校验信号：

- `totalMoveFromTitle`
- `titleFingerprint`

其中：

- `titleFingerprint` 是去掉动态手数后的静态标题骨架。
- `titleFingerprint` 不是棋局唯一 ID，只做“上下文是否明显断开”的弱校验。

### 3.5 标题归一化

`LIVE_ROOM`：

- 只解析 `roomToken`
- 只解析当前显示手数 `titleMove`
- `号` 后面的房间类型/状态文本全部忽略

`RECORD_VIEW`：

- 标题同时包含“第 `M` 手”和“总 `N` 手”时，归一化为 `currentMove=M, totalMove=N`
- 标题只显示“总 `N` 手”时，归一化为 `currentMove=N, totalMove=N, atRecordEnd=true`
- 标题手数解析失败时，该帧不使用标题手数参与命中

### 3.6 上下文识别

- 能解析出 `roomToken`：判为 `LIVE_ROOM`
- 解析不出 `roomToken`，但能解析出棋谱标题手数：判为 `RECORD_VIEW`
- 其他情况：判为 `UNKNOWN`

## 4. 本地基线选择与同步决策

### 4.1 统一前置步骤

每次收到远端快照时，先选“允许参与续接的本地基线”，顺序固定如下：

1. 当前主线祖先链精确命中。
2. `lastResolved` 精确命中。
3. `lastResolved + 1` 且这一手唯一可证。
4. 以上都不命中时，进入冲突处理或 `FORCE_REBUILD`。

明确禁止：

- 扫任意旧历史节点做模糊续接。
- 扫旁支 variation 做续接。
- 围绕 `lastResolved` 做一般性的 `±N` 搜索窗口。
- 用 `foxMoveNumber`、房间号、标题手数伪造真实 `MOVE/PASS`。

### 4.2 “当前主线祖先可直接回退”的语义

“当前主线祖先可直接回退”不是新增第五种同步结果，而是“允许把某个已存在祖先节点选为本次同步基线”。

这里的“命中当前主线祖先”只成立于：远端目标仍落在本地当前保留下来的、可证明的这段主线窗口内。

如果本地只保留了少量近期历史，而远端已经回退或跳转到这段窗口之前，即使本地还保留更晚的尾部节点，也算“没有命中当前主线祖先”。

后续结果仍只落在上位契约允许的结果集合中：

- `NO_CHANGE`
- `SINGLE_MOVE_RECOVERY`
- `FORCE_REBUILD`
- `HOLD`

如果远端正好等于命中的祖先节点，则最终结果记作 `NO_CHANGE`；只是同步前允许先把本地定位回该祖先。

这里的“当前保留主线窗口”只包含：

- 当前显示节点
- 当前显示节点的主线祖先链
- 当前显示节点到当前 `mainEnd` 的现有主线节点链

variation、窗口外旧历史、以及需要跨旧 `SNAPSHOT` 断口回捞的节点都不参与这条窗口命中。

### 4.3 `LIVE_ROOM` 决策矩阵

1. `roomToken` 明确变化：
   - 视为换房强信号。
   - 禁止继续沿用旧 `lastResolved`。
   - 默认偏向 `FORCE_REBUILD`，不依赖 `HOLD` 拖延。

2. 远端目标已是当前保留主线窗口内的现有节点：
   - 直接导航到该节点。
   - 不生成新历史，不触发 `FORCE_REBUILD`。
   - variation 不参与这条命中。

3. 命中当前主线祖先：
   - 允许直接回退到该祖先。
   - 若远端就等于该祖先局面，结果为 `NO_CHANGE`。

4. 命中 `lastResolved` 同一手：
   - 结果为 `NO_CHANGE`。

5. 只比 `lastResolved` 前进一手，且这一步唯一可证：
   - 结果为 `SINGLE_MOVE_RECOVERY`。
   - 只补这一手真实 `MOVE`。

6. 回退但没有命中当前主线祖先：
   - 结果为 `FORCE_REBUILD`。
   - 这包括“本地只保留少量近期历史，远端已经退到这段窗口之前一手或更多手”的场景。

7. 跨多手跳转、移除棋子、顺序不唯一、缺真实 pass：
   - 结果为 `FORCE_REBUILD`。

8. 冲突快照第一次出现：
   - 可返回 `HOLD`。
   - 但“同一冲突”的判定必须基于归一化远端身份，不能再用原始 `snapshotCodes` 整帧全等判断。
   - `marker` 颜色抖动和“有 / 无 marker”抖动都归入同一归一化冲突，不得把同一冲突拆成两次新的 `HOLD`。
   - 若这是 readboard `start/clear` 之后的新 session 第一帧，则不使用这条 `HOLD` 分支。

9. 同一归一化冲突再次出现：
   - 结果为 `FORCE_REBUILD`。

### 4.4 `RECORD_VIEW` 决策矩阵

1. `titleFingerprint` 明显变化：
   - 视为切到另一份棋谱上下文。
   - 默认 `FORCE_REBUILD`。

2. `totalMove` 明显冲突：
   - 默认 `FORCE_REBUILD`。

3. 远端目标已是当前保留主线窗口内的现有节点：
   - 直接导航到该节点。
   - 不生成新历史，不触发 `FORCE_REBUILD`。
   - variation 不参与这条命中。

4. 命中当前主线祖先：
   - 允许直接回退到该祖先。
   - 若远端就等于该祖先局面，结果为 `NO_CHANGE`。

5. 命中 `lastResolved` 同一手：
   - 结果为 `NO_CHANGE`。

6. 只比 `lastResolved` 前进一手，且可唯一证明：
   - 结果为 `SINGLE_MOVE_RECOVERY`。

7. 多手前跳或后跳：
   - 结果为 `FORCE_REBUILD`。
   - 不做多步补写，不做批量回放推断。
   - 本地只保留局部近期历史，而目标落在这段窗口之外时，同样归入这里。

8. 标题只显示“总 `N` 手”：
   - 先归一化成 `currentMove=N, totalMove=N`
   - 再走同一套矩阵

9. 冲突快照第一次出现：
   - 可短暂 `HOLD`
   - 同样必须按归一化身份判定“是否同一冲突”
   - `marker` 颜色抖动和“有 / 无 marker”抖动都归入同一归一化冲突，不得把同一冲突拆成两次新的 `HOLD`
   - 若这是 readboard `start/clear` 之后的新 session 第一帧，则不使用这条 `HOLD` 分支

10. 同一归一化冲突再次出现：
   - 结果为 `FORCE_REBUILD`

### 4.5 `UNKNOWN` 决策矩阵

`UNKNOWN` 走保守路径：

- 当前盘面完全相同：`NO_CHANGE`
- 能唯一证明是一手合法落子：`SINGLE_MOVE_RECOVERY`
- 其他情况：`FORCE_REBUILD`

### 4.6 混帧与 `marker` 抖动

- 同房间 / 同棋谱弱上下文未断开，且 `stones + recoveryMoveNumber` 已命中主身份时，`marker` 与本地 `lastMove` 的短暂不一致不能单独触发 `FORCE_REBUILD`。
- 这类帧优先按“`marker` 降级为不可信后重判”处理；只有主身份本身不成立，或冲突按归一化身份重复出现，才进入 `FORCE_REBUILD`。
- 对明显像“标题手数与盘面未原子绑定”的混帧，允许最多一次 `HOLD`；再次出现同一归一化冲突后再 `FORCE_REBUILD`。
- 以上规则只影响“是否立即重建”的决策，不放宽真实历史追加边界；多手跳转、顺序不唯一、缺 pass 等情况仍不得伪造 `MOVE/PASS`。

## 5. 状态生命周期

同步相关状态分三层：

### 5.1 `FrameInputState`

单帧输入缓冲，只保存当前帧原始输入，例如：

- `snapshotCodes`
- `pendingFoxMoveNumber`
- 标题解析结果
- `roomToken`

该层在每次完整帧结束后自然清空。

### 5.2 `ActiveSyncState`

当前同步会话的短期状态，例如：

- 上一次冲突快照的归一化身份
- 本地落子待 readboard 确认状态
- 当前远端 session epoch

该层在以下情况清空：

- 手动 `stop sync`
- readboard 发来 `start`
- readboard 发来 `clear`
- 远端上下文明显断开

### 5.3 `ResumeState`

允许跨 stop/start 续接的锚点，只保存：

- 最后一次成功落地的主线节点
- 该节点对应的归一化远端身份
- 该节点对应的弱上下文键

该层不能被 `stop sync`、readboard `start`、readboard `clear` 顺手清掉。

### 5.4 生命周期规则

1. 手动 `stop sync`
   - 只清 `ActiveSyncState`
   - 保留本地棋盘、本地历史、`ResumeState`

2. readboard 发来 `start` 或 `clear`
   - 视为远端 session 边界
   - 只清 `FrameInputState + ActiveSyncState`
   - 不立即清空 lizzie 本地棋盘
   - 等第一帧新快照到达后，再决定续接还是重建

3. 第一帧新快照到达
   - 按第 4 节的顺序选基线
   - 对于具备有效野狐恢复元数据的 `LIVE_ROOM / RECORD_VIEW` 首帧，只能同手续接、差一手补一步、或强制重建
   - 上述野狐可恢复路径首帧不额外插入一次 `HOLD` 观察轮次

4. 远端上下文明显断开
   - `LIVE_ROOM` 的 `roomToken` 变化
   - `RECORD_VIEW` 的 `titleFingerprint` 或 `totalMove` 明显冲突
   - 发生断开时，旧 `ResumeState` 不再允许直接续接

5. 同步成功落地
   - 无论是祖先命中后的 `NO_CHANGE`、单步补手还是 `FORCE_REBUILD`
   - 都更新 `ResumeState`
   - 同时清空旧冲突状态

6. 非同步路径覆盖主线历史
   - 例如手动载入 SGF、明确新对局、本地清盘、外部 `setHistory(...)` 覆盖
   - 必须显式作废 `ResumeState`

7. `ResumeState` 只跟“最后一次成功落地的主线节点”绑定
   - 不跟临时浏览视图绑定
   - 用户停同步后临时浏览旧节点，不自动把该节点升级成新的 resume anchor

## 6. 引擎恢复规则

### 6.1 总原则

- 不依赖 `Leelaz.clear()` 的隐式副作用来恢复分析。
- 同步链路分成“本地棋盘 / history 落地”和“引擎 exact snapshot restore”两个阶段；前者不依赖引擎可用，后者依赖。
- rebuild 成功后，应在快照真正被引擎消费成功之后，显式恢复分析。
- 恢复分析动作要绑定到本次成功落地的目标节点，避免旧 restore 回写过时分析状态。

### 6.2 `FORCE_REBUILD`

任何 `FORCE_REBUILD` 成功后：

1. 先保证本地棋盘 / history 的 `SNAPSHOT` 已落地。
2. 若引擎可用，再等 `loadsgf` 确认成功消费。
3. 只有 `loadsgf` 成功消费后，才显式触发一次“重建后恢复分析”。
4. 若 restore 期间已出现更新的远端目标，旧恢复请求失效。

无引擎或引擎未启动时：

- 允许停在明确的 board-only sync 路径。
- 该帧视为“棋盘同步成功，但无分析恢复”。
- 不把这条路径伪装成 exact snapshot restore 成功。
- 后续若引擎才启动，不自动消费旧的重建后恢复请求；只有新的同步成功落地才触发新的分析恢复。

### 6.3 `SINGLE_MOVE_RECOVERY`

单步补手成功后：

- 若当前配置允许 readboard ponder，且当前节点就是同步落点
- 应显式检查并确保当前节点正在分析

### 6.4 新 session 首帧 `NO_CHANGE`

当重新开启同步后，第一帧远端正好等于当前本地节点时：

- 允许做一次轻量的“确保分析已恢复”检查
- 解决“盘面对了，但 KataGo 没有恢复出结果”的场景

### 6.5 双引擎

- 继续遵守 [docs/SNAPSHOT_NODE_KIND.md](/mnt/d/dev/weiqi/lizzieyzy-next/docs/SNAPSHOT_NODE_KIND.md) 中既有的 exact snapshot restore 生命周期
- 但每侧 restore 成功消费后，仍应显式恢复各自分析

## 7. 手动强制重建入口

### 7.1 目标

在 `readboard` 侧提供一个显式“强制 rebuild 当前帧”的人工纠偏入口。

### 7.2 语义

- 该按钮不立即清空 lizzie 本地棋盘。
- 该按钮不复用现有 `clear` 语义。
- 该按钮只给当前同步会话设置一个一次性 `forceRebuild` 标记。

### 7.3 执行方式

当下一次完整快照发送到 lizzie 时：

- 若存在一次性 `forceRebuild` 标记
- lizzie 跳过祖先命中、`lastResolved` 续接和 `HOLD`
- 直接对该帧执行 `FORCE_REBUILD`
- 成功后自动清掉该标记

## 8. 平台分级策略

### 8.1 野狐：`Fox full mode`

只有野狐进入完整上下文恢复模型，使用：

- `foxMoveNumber`
- `roomToken`
- 棋谱标题 `currentMove/totalMove`
- `LIVE_ROOM / RECORD_VIEW / UNKNOWN` 分流
- `ResumeState`
- 当前主线祖先精确命中

前提：

- 当前帧具备有效 `foxMoveNumber`
- 否则退回保守路径，不做野狐级上下文恢复

### 8.2 非野狐：`Generic conservative mode`

新浪、弈城和其他平台统一走保守通用模式，不追求与野狐同等级兼容。

该模式下：

- 不使用 `roomToken`
- 不使用棋谱标题上下文恢复
- 不做野狐级别的跨 stop/start 续接锚点恢复
- 不做“当前主线祖先 + 远端上下文增强匹配”的复杂命中

允许的自动路径只保留保守子集：

- 当前盘面完全相同：`NO_CHANGE`
- 能唯一证明是一手合法落子：`SINGLE_MOVE_RECOVERY`
- 其他情况：`FORCE_REBUILD`

### 8.3 非野狐的一手判定边界

非野狐平台的判断标准不是“变化了几个点”，而是“能不能唯一证明成一手真实落子”。

因此：

- 即使提了很多颗子，只要仍能唯一证明是某一手落子导致的，就允许记成真实 `MOVE`
- 即使变化点不多，只要解释不唯一，也必须 `FORCE_REBUILD`

## 9. 验收重点

后续实现至少要覆盖以下场景：

1. 野狐观战房里手动回退，若命中当前主线祖先则直接回退，否则强制重建。
2. 停止同步后切换房间，再重新同步时，能够区分“同手续接 / 差一手补一步 / 强制重建”。
3. readboard `start/clear` 不再提前清空 lizzie 本地棋盘，而是等待第一帧新快照决策。
4. 第一次同步成功落地后，KataGo 能在当前节点恢复分析，不再依赖“等下一手”。
5. 棋谱窗口最后一手只显示“总 `N` 手”时，正确归一化为 `currentMove=N`。
6. `roomToken` 只截取到第一个 `号`，并保留原始字符串格式。
7. 新浪、弈城等非野狐平台只在“唯一可证的一手”时保留真实历史，其余情况走重建。
8. 无引擎时，普通同步帧和 `FORCE_REBUILD` 帧都能把棋盘同步到 lizzie，本地不会因为缺少引擎而整帧失败。

## 10. 测试与验收矩阵

本节把后续验证拆成三层：

1. `readboard` 侧标题/协议归一化测试
2. `lizzieyzy-next` 侧同步判定与状态生命周期测试
3. 端到端手工验收

### 10.1 自动化测试总原则

- 纯解析和协议行为尽量放在 `readboard` 仓库验证。
- 纯同步判定、历史边界、状态生命周期尽量放在 `lizzieyzy-next` 仓库验证。
- 引擎恢复与 `loadsgf` 生命周期，优先扩展现有 `LeelazLoadSgfResponseBindingTest` 相关测试。
- 所有“是否保留真实历史”的断言，都必须同时检查：
  - 目标盘面正确
  - 主线节点类型正确
  - 未伪造额外 `MOVE/PASS`

### 10.2 `readboard` 自动化矩阵

这些测试建议落在 `/mnt/d/dev/weiqi/readboard`，优先围绕现有标题解析与协议适配层补充。

| ID | 优先级 | 位置建议 | 场景 | 预期 |
| --- | --- | --- | --- | --- |
| `RB-PARSE-001` | P0 | `LegacyWindowDescriptorFactory` 相关测试 | 标题 `> [高级房1] > 43581号对弈房 观战中[第89手] - 升降级` | `roomToken=43581号`，后缀房间类型完全忽略 |
| `RB-PARSE-002` | P0 | 同上 | 标题 `> [高级房1] > 23\|890号房间 对弈中[第03手] - 友谊赛 - 数子规则` | `roomToken=23\|890号`，保留原始格式，不转纯数字 |
| `RB-PARSE-003` | P0 | 同上 | 棋谱标题同时包含“第 `M` 手 / 总 `N` 手” | 归一化为 `currentMove=M, totalMove=N, atRecordEnd=false` |
| `RB-PARSE-004` | P0 | 同上 | 棋谱标题只显示“总 `N` 手” | 归一化为 `currentMove=N, totalMove=N, atRecordEnd=true` |
| `RB-PARSE-005` | P1 | 同上 | 标题无可解析手数 | 不抛异常，返回无标题手数元数据 |
| `RB-PROTO-001` | P0 | `SyncSessionCoordinator` / 协议适配层测试 | 人工点击“强制 rebuild”按钮 | 下一次完整快照携带一次性 `forceRebuild` 标记 |
| `RB-PROTO-002` | P0 | 同上 | `forceRebuild` 标记发送一帧后继续同步 | 标记自动清掉，不污染后续普通帧 |
| `RB-PROTO-003` | P1 | `Form1`/状态协调测试 | 野狐模式能抓到 `foxMoveNumber` 时 | 会把有效 `foxMoveNumber` 附到本帧输出 |
| `RB-PROTO-004` | P1 | 同上 | 野狐窗口标题缺少有效 `foxMoveNumber` | 不伪造 move number，交由下游走保守路径 |

### 10.3 `lizzieyzy-next` 同步判定自动化矩阵

这些测试优先扩展现有文件：

- [src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java)
- [src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java)
- [src/test/java/featurecat/lizzie/analysis/ReadBoardFoxMoveNumberParsingTest.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/test/java/featurecat/lizzie/analysis/ReadBoardFoxMoveNumberParsingTest.java)
- [src/test/java/featurecat/lizzie/analysis/LeelazLoadSgfResponseBindingTest.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/test/java/featurecat/lizzie/analysis/LeelazLoadSgfResponseBindingTest.java)

| ID | 优先级 | 位置建议 | 场景 | 预期 |
| --- | --- | --- | --- | --- |
| `LZ-POLICY-001` | P0 | `SyncSnapshotRebuildPolicyTest` | 远端命中当前主线祖先，而不是当前节点 | 返回祖先节点作为有效基线 |
| `LZ-POLICY-002` | P0 | `SyncSnapshotRebuildPolicyTest` | 重复盘面存在更早旧节点，但不在当前主线祖先链上 | 不得回捞旧节点续接 |
| `LZ-POLICY-003` | P0 | `SyncSnapshotRebuildPolicyTest` | marker 抖动但 `stones + foxMoveNumber` 相同 | 冲突身份按归一化身份合并，不因原始帧不同而永久 `HOLD` |
| `LZ-POLICY-003A` | P0 | `SyncSnapshotRebuildPolicyTest` | 同一落点在相邻帧里“有 marker / 无 marker”但 `stones + foxMoveNumber` 相同 | 同样按归一化身份合并，不得把同一冲突拆成两次新的 `HOLD` |
| `LZ-POLICY-004` | P0 | `SyncSnapshotRebuildPolicyTest` | `LIVE_ROOM` 缺少有效 `foxMoveNumber` | 不进入野狐完整恢复路径，退回保守路径 |
| `LZ-POLICY-005` | P1 | `SyncSnapshotRebuildPolicyTest` | `RECORD_VIEW` 标题只显示“总 `N` 手” | 视为 `currentMove=N` 参与命中 |
| `LZ-POLICY-006` | P1 | `SyncSnapshotRebuildPolicyTest` | `roomToken` 变化 | 旧 `lastResolved` 失效，不能直接续接 |
| `LZ-POLICY-007` | P1 | `SyncSnapshotRebuildPolicyTest` | `titleFingerprint` 或 `totalMove` 明显冲突 | 旧 `lastResolved` 失效，偏向重建 |
| `LZ-POLICY-008` | P0 | `SyncSnapshotRebuildPolicyTest` | 远端目标已是当前保留主线窗口内的现有节点 | 直接返回该现有主线节点，不扫描 variation |
| `LZ-DECISION-001` | P0 | `ReadBoardSyncDecisionTest` | 观战房手动回退，且远端命中当前主线祖先 | 直接回退到祖先，最终结果 `NO_CHANGE` |
| `LZ-DECISION-002` | P0 | `ReadBoardSyncDecisionTest` | 观战房手动回退，但没有命中当前主线祖先 | 最终 `FORCE_REBUILD` |
| `LZ-DECISION-002A` | P0 | `ReadBoardSyncDecisionTest` | 本地只保留少量近期历史，远端逐步回退到最早保留节点之前一手 | 不得永久停在旧局面；命中该帧后直接 `FORCE_REBUILD` |
| `LZ-DECISION-003` | P0 | `ReadBoardSyncDecisionTest` | 手动 `stop sync` 后重新同步，同一手 | 保留 `ResumeState`，结果 `NO_CHANGE` |
| `LZ-DECISION-004` | P0 | `ReadBoardSyncDecisionTest` | 手动 `stop sync` 后重新同步，只差一手且唯一可证 | 只补这一手真实 `MOVE` |
| `LZ-DECISION-005` | P0 | `ReadBoardSyncDecisionTest` | 手动 `stop sync` 后重新同步，相差多手 | `FORCE_REBUILD` |
| `LZ-DECISION-005A` | P0 | `ReadBoardSyncDecisionTest` | 同房间内快速前跳多手或直接跳到局部历史窗口之外 | 不做多步补写，最终 `FORCE_REBUILD` |
| `LZ-DECISION-006` | P0 | `ReadBoardSyncDecisionTest` | readboard 发来 `start/clear` | 不立即清空本地棋盘，等待第一帧快照后决策 |
| `LZ-DECISION-006A` | P0 | `ReadBoardSyncDecisionTest` | readboard `start/clear` 后第一帧就是多手回退或多手前跳 | 首帧不走 `HOLD`，直接 `FORCE_REBUILD` |
| `LZ-DECISION-007` | P0 | `ReadBoardSyncDecisionTest` | 一次性 `forceRebuild` 标记到达 | 跳过祖先命中、`lastResolved`、`HOLD`，直接重建 |
| `LZ-DECISION-007A` | P0 | `ReadBoardSyncDecisionTest` | 无引擎时命中 `FORCE_REBUILD` | 本地 `SNAPSHOT` 仍正确落地，不发送 `loadsgf`，不同步失败 |
| `LZ-DECISION-007B` | P0 | `ReadBoardSyncDecisionTest` | 当前视图停在祖先，远端目标是当前保留主线窗口内的已有节点 | 直接导航到该节点，不重建，也不把视图还原回旧节点 |
| `LZ-DECISION-008` | P1 | `ReadBoardSyncDecisionTest` | 非野狐平台连续单步、无歧义 | 允许 `SINGLE_MOVE_RECOVERY` |
| `LZ-DECISION-009` | P1 | `ReadBoardSyncDecisionTest` | 非野狐平台回退 | 直接 `FORCE_REBUILD` |
| `LZ-DECISION-010` | P1 | `ReadBoardSyncDecisionTest` | 非野狐平台单步提多子但唯一可证 | 仍允许记成真实 `MOVE` |
| `LZ-DECISION-011` | P1 | `ReadBoardSyncDecisionTest` | 非野狐平台变化点不多但解释不唯一 | `FORCE_REBUILD` |

### 10.4 引擎恢复自动化矩阵

这些测试优先扩展：

- [src/test/java/featurecat/lizzie/analysis/LeelazLoadSgfResponseBindingTest.java](/mnt/d/dev/weiqi/lizzieyzy-next/src/test/java/featurecat/lizzie/analysis/LeelazLoadSgfResponseBindingTest.java)
- 必要时新增 `ReadBoard`/`Leelaz` 协同测试文件

| ID | 优先级 | 位置建议 | 场景 | 预期 |
| --- | --- | --- | --- | --- |
| `LZ-ENGINE-001` | P0 | `LeelazLoadSgfResponseBindingTest` 或新协同测试 | `FORCE_REBUILD` 成功后 | `loadsgf` 消费成功后显式恢复分析，不再等下一手 |
| `LZ-ENGINE-002` | P0 | 同上 | 新 session 第一帧就是 `NO_CHANGE` | 当前节点分析能被恢复 |
| `LZ-ENGINE-003` | P0 | 同上 | `SINGLE_MOVE_RECOVERY` 成功后 | 当前节点立即进入分析态 |
| `LZ-ENGINE-004` | P1 | 同上 | rebuild 过程中又收到更新目标 | 旧恢复分析请求失效，不把分析拉回旧局面 |
| `LZ-ENGINE-005` | P1 | 同上 | 双引擎快照恢复成功 | 两侧都在各自 restore 成功后恢复分析 |
| `LZ-ENGINE-006` | P1 | `ReadBoardEngineResumeTest` 或新协同测试 | 收到 `sync` 时引擎未启动 | 只进入棋盘同步态，不强行 `togglePonder()` |
| `LZ-ENGINE-007` | P1 | 同上 | board-only rebuild 完成后，引擎稍后才启动 | 不自动复用旧的重建后恢复请求，直到下一次同步成功落地 |

### 10.5 手工验收矩阵

端到端验收按平台和窗口上下文分组执行。每个场景都至少检查 4 件事：

1. lizzie 最终盘面是否正确
2. 主线是否只保留可证明的真实历史
3. 是否错误触发或遗漏 `FORCE_REBUILD`
4. KataGo 是否在当前节点恢复出结果

| ID | 优先级 | 场景 | 操作 | 预期 |
| --- | --- | --- | --- | --- |
| `MAN-FOX-LIVE-001` | P0 | 野狐观战房手动回退到 lizzie 当前主线祖先 | 保持同步开启，在野狐手动回退 | lizzie 直接回到该祖先，不新造 `SNAPSHOT`，不丢分析 |
| `MAN-FOX-LIVE-002` | P0 | 野狐观战房手动回退到 lizzie 不存在的历史点 | 在野狐手动回退到本地无可证明历史的局面 | 短暂确认冲突后 `FORCE_REBUILD`，盘面正确 |
| `MAN-FOX-LIVE-002A` | P0 | 野狐观战房逐步回退越过本地最早保留节点 | 先让 lizzie 只保留一小段近期历史，再在野狐逐步回退到这段窗口之前一手 | 不得永久停在旧局面；命中该帧后直接 `FORCE_REBUILD` |
| `MAN-FOX-LIVE-003` | P0 | 停同步后切换到新房间 | `stop sync`，切到另一房间，再重新同步 | 旧历史不被误续接，第一帧偏向强制重建 |
| `MAN-FOX-LIVE-004` | P0 | 停同步后仍在同房间同一手 | `stop sync` 后不动局面，再重新同步 | 结果 `NO_CHANGE`，分析恢复 |
| `MAN-FOX-LIVE-005` | P0 | 停同步后同房间只差一手 | `stop sync` 后野狐走一手，再重新同步 | lizzie 只补这一手真实 `MOVE` |
| `MAN-FOX-LIVE-006` | P1 | 房间 token 为异常格式 | 使用类似 `23\|890号房间` 的窗口 | `roomToken` 正确截取，换房逻辑仍成立 |
| `MAN-FOX-LIVE-007` | P0 | 野狐观战房直接跳到局部历史窗口之外 | 先让 lizzie 只保留一小段近期历史，再在野狐直接输入更早或更晚的手数跳转 | 不做多步补写，不永久停住，最终 `FORCE_REBUILD` |
| `MAN-FOX-RECORD-001` | P0 | 棋谱窗口普通浏览 | 从第 `M` 手跳到前后附近 | 同手 `NO_CHANGE`、差一手补一步、多手跳转重建 |
| `MAN-FOX-RECORD-002` | P0 | 棋谱窗口最后一手只显示总手数 | 打开已走到终局的棋谱窗口 | 正确识别为 `currentMove=totalMove`，不同步错乱 |
| `MAN-FOX-RECORD-003` | P1 | 棋谱窗口回退到当前主线祖先 | 在同一份棋谱中向前跳回 | lizzie 允许直接回到主线祖先 |
| `MAN-FOX-RECORD-004` | P1 | 棋谱窗口切到另一份棋谱 | 打开另一份棋谱文件/窗口 | 旧 `lastResolved` 不再续接，偏向重建 |
| `MAN-ENGINE-001` | P0 | 未启动引擎，仅开启棋盘同步工具 | 在野狐连续看棋、回退、触发重建 | lizzie 棋盘仍持续同步；只是没有分析结果，不出现“偶尔同步一下又卡住” |
| `MAN-GENERIC-001` | P1 | 新浪/弈城连续单步落子 | 正常一手一手跟进 | 仅在唯一可证时记真实历史 |
| `MAN-GENERIC-002` | P1 | 新浪/弈城发生回退 | 在平台侧回退棋子 | 不尝试保留旧历史，直接重建 |
| `MAN-GENERIC-003` | P1 | 新浪/弈城发生单步提多子 | 形成明确单手提子 | 若唯一可证则仍视为一手，不因提子数直接重建 |
| `MAN-GENERIC-004` | P1 | 新浪/弈城出现歧义变化 | 多点变化但无法唯一解释 | 直接重建 |
| `MAN-FORCE-001` | P0 | `readboard` 人工强制 rebuild 按钮 | 在错误续接/卡住时点击按钮，等待下一帧 | 下一帧直接重建，按钮效果只生效一次 |

### 10.6 通过标准

本轮实现完成后，验收通过的最低标准是：

1. 所有 P0 自动化测试通过。
2. 所有 P0 手工验收场景通过。
3. 不新增违反 [docs/SNAPSHOT_NODE_KIND.md](/mnt/d/dev/weiqi/lizzieyzy-next/docs/SNAPSHOT_NODE_KIND.md) 的真实历史伪造路径。
4. 问题 1、问题 2、问题 3 都有对应的自动化或手工回归用例，不再只靠口头验证。

## 11. 实施分支与清理策略

### 11.1 分支策略

本轮实现继续在当前 `fix-sync` 分支上进行，不从更早的“干净基线”重开。

原因：

- 当前 `fix-sync` 上已累积多轮 bugfix 与辅助函数，直接回到旧基线会丢失已有修复成果。
- 本分支已受 [docs/SNAPSHOT_NODE_KIND.md](/mnt/d/dev/weiqi/lizzieyzy-next/docs/SNAPSHOT_NODE_KIND.md) 约束，继续在当前分支上推进更利于后续子代理保持一致边界。
- 当前问题的主要风险是同步路径演化后出现回归，不是“历史提交不够干净”；因此更适合在现状上做受控收敛，而不是从头重搭。

### 11.2 安全备份

在进入实际代码修改前，建议先从当前 `fix-sync` 的 `HEAD` 打一个安全备份分支或等价备份点。

该备份点的目的只是：

- 保留当前分支已有修复成果
- 降低后续清理冗余路径时的回退成本

该备份点不是新的开发主线。

### 11.3 清理策略

不采用“先继续堆代码，最后统一大扫除”的方式。

采用分段收敛：

1. 先接入新的同步判定主路径。
2. 每落地一块新逻辑，就删除与之直接冲突、且已被替代的旧路径。
3. 最后一轮只做小范围收尾清理，不做大体量无边界重构。

### 11.4 删除边界

允许删除的代码类型：

- 已被新主路径完全替代的旧匹配逻辑
- 已不再被生产路径读取的同步 tracker 状态
- 与新状态生命周期冲突、且已无回归价值的死分支

不在本轮顺手做的事：

- 与同步边界无关的大规模重构
- 仅为“看起来更整洁”而做的无行为收益清理
- 跨模块的大范围命名迁移

### 11.5 验证原则

每一块同步主路径替换完成后，都应立刻跑对应的自动化测试与手工回归，不把“行为变更”和“冗余清理”长时间混在一起累计到最后一轮一起验证。
