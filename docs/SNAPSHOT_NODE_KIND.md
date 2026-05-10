# fix-sync 分支契约

## 目标

这个分支只解决两件事：

1. 同步后的目标盘面正确。
2. 本地历史只保留可证明的真实手顺。

任何无法证明的中间顺序，都收敛成一个新的 `SNAPSHOT` 锚点。

## 节点定义

`BoardData` 只保留显式 `BoardNodeKind` 语义：

- `MOVE`: 明确的一手落子，带坐标，消耗 1 手。
- `PASS`: 明确的一手停着，消耗 1 手。
- `SNAPSHOT`: 静态盘面锚点，只表达“当前局面长这样”以及相关元数据。

`isHistoryActionNode()` 只对 `MOVE` 和 `PASS` 返回 `true`。

## 数据源定义

- 应用内下棋、引擎 GTP、SGF 回放：产出真实 `MOVE/PASS`。
- 棋盘同步工具、读盘结果、markerless sync：产出 `SNAPSHOT`。
- `foxMoveNumber`、marker、手数奇偶：只作为辅助元数据，不负责补造缺失历史。
- `foxMoveNumber` 只能修正 `SNAPSHOT` 元数据，不能改写已证明的真实 `MOVE/PASS`。

## 引擎支持矩阵（本轮）

- KataGo / 通用 GTP 属于本轮支持与验收范围。
- Leela Zero / leelaz / Leela 0.11 / Sai 归入 legacy engine path，移出本轮验收与阻塞范围。
- 现有菜单入口、类名、配置项保持现状，本轮不做 UI 隐藏和功能下架。

## PASS 契约

同步链路拿不到真实 pass 事件。

任何代码都不能再从以下信号推导真实 `PASS`：

- `lastMove.empty`
- 盘面差异
- 手数奇偶
- `foxMoveNumber`
- `moveNumberList`

真实 `PASS` 只来自显式 `nodeKind == PASS`。

`dummy PASS` 只表达占位语义，不属于可消费的真实 history action。

真实 `PASS` 节点和 `dummy PASS` 节点是不同语义实体，任何链路都必须保留这层区分，不能把 `dummy PASS` 升格、折叠或复用成真实 `PASS`。

自动播放、统计、评分等真实 `PASS` 消费方只统计真实 `PASS`，忽略 `dummy PASS`。

## setup 契约

- `setup` 加子和 `setup` 删除石子都属于静态局面同步，统一产出 `SNAPSHOT`。
- markerless `setup` 增删都属于静态局面变化，不得从 `changedStones` 推导 `moveNumber` 或 `blackToPlay`。
- 这类变化只表达目标局面，不写成真实 `MOVE/PASS`。
- `extraStones` 属于 setup 元数据，随 `SNAPSHOT` 锚点保存，不进入真实手顺通道。
- 根节点 `handicap`、`AB/AW`、`PL`、`hasStartStone`、`startStonelist` 也属于 setup 静态语义，随静态局面挂载到 `SNAPSHOT`。
- 根节点 setup/save round-trip 时，根节点 setup 语义只落在根节点一次，不能派生额外 SGF 子节点。
- 根节点 setup/save 连续保存结果必须稳定，不能在重复保存时增量生成新的 setup 子节点。
- SGF 中“首手前但非 root 的独立 setup 节点”必须保留为独立 `SNAPSHOT` 子节点，`moveNumber` 保持 `0`，不能并入 root setup。
- 根节点 setup / handicap 的 `side-to-play` 只由显式 `PL` 或固定默认规则决定。
- 根节点 `AB/AW` 属性顺序不参与 `side-to-play` 判定。
- 中盘 `setup SNAPSHOT` 上的显式 `PL` 直接决定该 `SNAPSHOT` 的 `blackToPlay / side-to-play`。
- 这个 `PL` 语义随 `SNAPSHOT` 锚点保存，在后续导航、`restoreMoveNumber(...)`、所有 `loadEngine=true` 恢复中持续生效。
- `parseBranch` / variation 首节点若同节点先 setup 后落子，setup 部分先落成独立 `SNAPSHOT` 边界，后续真实落子继续保持 `MOVE/PASS` 语义。
- 在“新节点先 setup 再落子”的 SGF 解析场景里，setup `SNAPSHOT` 只挂载当前 SGF 节点自己的 setup / comment / markup / `MN` 等元数据，父节点 metadata 继续挂载在父节点。
- 同一 setup 节点里，出现在首个 setup 属性之前的 comment / markup / `MN` 仍归属当前 setup `SNAPSHOT` 边界。
- 所有 SGF 字符串加载入口（含编辑态加载）与主 SGF 解析入口遵守同一 setup `SNAPSHOT` 边界语义，setup / comment / markup / `MN` 归属保持一致。
- `parseSgf()`、`loadFromString()`、`loadFromStringforedit()` 对 SGF 盘尺寸的解析保持一致，矩形盘 `SZ[w:h]` 在 load/save/round-trip 后持续保留。
- `parseSgf()` 在 setup 落点、`AB/AW/AE` 校验、坐标索引计算时使用 SGF 自身的盘尺寸；当前全局盘尺寸不同也不能丢石子或删石子。
- `parseSgf()` 返回的 history 自带 SGF 盘尺寸语义；后续 `setHistory(...)`、`saveToString(...)`、`generateNode(...)` 继续使用该 history 的盘尺寸，不能回退到当前全局盘尺寸。
- `parseSgf(...)` / detached history 解析运行在隔离上下文，解析阶段只产出解析结果，不改写当前 live board 的全局 UI / 棋局状态。
- 这些被隔离的 live state 包含 `hasStartStone/startStonelist`、player title、komi、引擎 komi/best-move 等当前窗口状态；相关副作用只允许在调用方显式采用解析结果后发生。
- SGF 分析标签 `LZ` / `LZ2` / `LZOP` / `LZOP2`（含双引擎对应 payload）在 detached `parseSgf(...)` 阶段必须写入解析目标 history/node 的 `BoardData`，不能读写或覆盖当前 live board 的分析字段。
- detached `parseSgf(...) -> setHistory(...) -> saveToString(...)` round-trip 必须保留 analysis payload（`engineName` / `engineName2`、playouts、best-move 列表）。
- `LZ` / `LZ2` / `LZOP` / `LZOP2` 单行 header-only analysis payload 视为完整 payload；缺少第二行 PV 时，`parseSgf(...)`、`setHistory(...)` adopt、`saveToString(...)` 与 round-trip 仍导出等价 payload。
- header 内 `engineName`、`playouts`、`scoreMean`、`scoreStdev`、`pda`（双引擎槽位含 `engineName2`、`scoreMean2`、`scoreStdev2`、`pda2`）在 parse、`setHistory(...)` adopt、`saveToString(...)`、round-trip 全链路保留。
- `setHistory(...)` adopt detached history 时，board 级 Kata 状态从 adopted history 重新推导：
  - history 任意节点存在可靠 Kata analysis payload（`isKataData` / `isKataData2`，或 best-move 项带 `isKataData=true`）时，`isKataBoard=true`。
  - 残留 `scoreMean` / `scoreMean2` 数值本身不作为 Kata board 判定信号。
  - root `DZ[G]` / `DZ[KB]` / `DZ[KW]` 保持与 live parse 一致的 board flag 映射（含 PK/Kata 组合标记）。
- detached `parseSgf(...)` 解析阶段只产出 history 结果，不改写 live board 的 `isKataBoard` / `isPkBoard` / `isPkBoardKataB` / `isPkBoardKataW`；board 级同步发生在 adopt/setHistory 阶段。
- 这些根节点 setup 语义不进入真实 movelist、save-load movelist、引擎 `clear_board + moveList` 历史重放。
- 涉及引擎同步时，`setup` 删除石子直接触发 `FORCE_REBUILD`。
- `removed-stone/setup` 形成的静态局面由最近一个 `SNAPSHOT` 锚点定义。
- 引擎恢复这段局面时，先从最近 `SNAPSHOT` 恢复静态局面和 setup 元数据，再续接后面的真实 `MOVE/PASS`。
- 分支切换、任意跳转、关闭后重开引擎后的 board restore 也属于导航 / 恢复入口，命中最近 `SNAPSHOT`，尤其 `removed-stone/setup SNAPSHOT` 时，先恢复该静态锚点，再续接后面的真实 `MOVE/PASS`。
- 静态 `SNAPSHOT` / setup / `removed-stone` 恢复到引擎后的结果盘面必须与该 `SNAPSHOT` 盘面完全一致。
- 恢复过程先落地静态局面，再续接真实 `MOVE/PASS`，静态石子不能拆成逐手重放。
- 后续 `clear_board + moveList` 只重放 `SNAPSHOT` 之后的真实手顺，`SNAPSHOT` 自带的静态局面持续生效。
- 所有 `loadEngine=true` 的恢复入口都遵守同一套 `SNAPSHOT/setup` 恢复契约。
- `restoreMoveNumber(...)` 恢复时也先命中最近 `SNAPSHOT` 边界，再续接后面的真实 `MOVE/PASS`。
- `exact snapshot restore` 的 `loadsgf` 生命周期按固定顺序执行：
  1. `loadsgf` 临时 SGF 准备完成后，命令先入队再发出。
  2. 命令发出前，当前次 `loadsgf` 的 pending response handler 与 dispatch 归属绑定完成，并持续到退休或完成。
  3. 发送失败时，当前次 pending handler 与 outstanding response 计数同步退休，dispatch 显式结束为失败。
  4. 收到 `?` 错误响应时，当前次 pending handler 与 outstanding response 计数同步退休，dispatch 显式结束为失败。
  5. 无响应超时时，当前次 pending handler 与 outstanding response 计数同步退休，dispatch 显式结束为失败。
  6. 只有 `loadsgf` 成功消费后，dispatch 才能结束为完成态。
  7. dispatch 完结后，才允许重放 `SNAPSHOT` 尾部真实 `MOVE/PASS`。
  8. 双引擎模式下，临时 SGF 生命周期覆盖两侧 `loadsgf` 消费与尾部真实 `MOVE/PASS` 重放，直到两侧都完成后删除。
- `exact snapshot restore` 中 `loadsgf` 发送阶段失败都视为恢复失败，必须显式结束 dispatch、终止后续真实 `MOVE/PASS` 重放、进入清理流程并显式抛错。
- 该恢复失败规则覆盖“先入队后发送”的链路形态。
- `exact snapshot restore` 中 `loadsgf` 收到 GTP `?` 错误响应也属于恢复失败，必须终止后续真实 `MOVE/PASS` 重放并显式抛错。
- `exact snapshot restore` 中 `loadsgf` 已成功发出但消费方长期无响应也属于恢复失败，恢复流程返回失败并完成临时 SGF 与对应处理器清理。
- `exact snapshot restore` 在发送失败、`?` 响应、无响应超时后的清理阶段，必须隔离该次 `loadsgf` 的晚到旧响应。
- 这类晚到响应只能被对应的失败 `loadsgf` 吸收或丢弃，不能再消费后续命令的 response handler。
- `exact snapshot restore` 若 `loadsgf` 仍停留在发送队列、尚未真正发出就已经超时失败，这条过期命令必须从队列移除，后续不能再被发送。
- `exact snapshot restore` 若 `loadsgf` 已离开发送队列但最终以发送失败或无响应超时结束，这条失败命令对应的 outstanding response 计数也同步退休，恢复后引擎发送窗口回到可用状态。
- `exact snapshot restore` 中 `loadsgf` 若在 `BufferedOutputStream` 或底层 `write` 路径失败，失败命令残留字节在后续 `flush` 前必须清理完成；后续发送只消费仍有效的队列命令，失败或过期 `loadsgf` 不得污染后续命令边界。
- `exact snapshot restore` 中 `loadsgf` 若在底层 `write` 阶段已写出部分字节后再失败，这条命令对应的引擎通信流视为已污染；后续命令发送前必须先完成该输出流的显式失效处理。
- `exact snapshot restore` 在 `requireResponseBeforeSend=true` 下，若已排队 `loadsgf` 在真正发送阶段失败或无响应超时，当前命令退休 outstanding 后，发送窗口恢复立即落到真实队列推进，后续已排队命令立即继续发送。
- 双引擎模式下，snapshot restore 使用的临时 SGF 只在两个引擎都完成消费后删除。
- 双引擎模式下，无论 restore 从主引擎入口还是副引擎入口发起，snapshot restore 都同时镜像到另一侧，并共用同一套临时 SGF 生命周期与消费完成边界。
- 双引擎模式下，主/副引擎入口复用同一 mirror restore 合约；副引擎入口发起时，另一侧也按同序执行 `loadsgf` 成功后尾部真实 `MOVE/PASS` 重放。
- 双引擎 mirror restore 只在当前调用实例属于主引擎或副引擎之一时成立；第三实例或临时引擎实例触发 `loadsgf` 时，只作用于该实例本身，不能镜像到 `Lizzie.leelaz2` 或另一主/副引擎实例。
- 第三实例或临时引擎实例的 snapshot restore 在 `loadsgf` 成功后，尾部真实 `MOVE/PASS` 重放也只作用于该实例本身，不能镜像到主/副引擎另一侧。
- 双引擎模式下，若一侧已发出快照加载请求而另一侧发送失败，兜底清理在已发出侧真实消费完成后执行，临时 SGF 生命周期覆盖该消费全程。
- 双引擎模式下，若已发出快照加载请求的一侧最终无响应，恢复流程仍执行兜底清理，并释放该侧对应处理器。
- 双引擎模式下，一侧收到 GTP `?` 错误而另一侧无响应时，恢复流程仍返回失败，并完成临时 SGF 与两侧处理器清理。

## 同步决策规则

同步主流程只允许四类结果：

“当前局面一致”的判定必须包含盘面内容。

marker、手数、轮次只能辅助比对，不能单独触发 `NO_CHANGE` 或同步命中。

1. `NO_CHANGE`
   当前局面和目标局面一致，保留当前节点。
2. `SINGLE_MOVE_RECOVERY`
   当前局面到目标局面能被唯一证明为一手合法落子时，允许追加一个真实 `MOVE`。
3. `FORCE_REBUILD`
   出现以下任一情况时，直接整盘重建：
   - 回退
   - 跨多手跳转
   - 移除棋子
   - markerless 吃子且顺序无法唯一证明
   - 缺少真实 pass 事件
   - 中间顺序存在多种解释
4. `HOLD`
   只用于短暂等待同一冲突快照再次出现，不用于拼造历史。

同一冲突快照的判定基于归一化远端身份，不基于原始 `snapshotCodes` 整帧全等。

这里的 `marker` 抖动同时包含：

- `marker` 颜色抖动
- 同一落点在相邻帧里“有 marker / 无 marker”的抖动

这类抖动只能让 `marker` 从增强信号降级为辅助信号，不能把同一冲突拆成不同 key。

命中当前保留主线窗口内的已有节点时，直接导航到该节点。

这里的“当前保留主线窗口”只包含：

- 当前显示节点
- 当前显示节点的主线祖先链
- 当前显示节点到当前 `mainEnd` 的现有主线节点链

这类命中仍属于已有历史导航，不生成新 `MOVE/PASS`，也不触发 `FORCE_REBUILD`。

variation、窗口外旧历史、以及需要跨旧 `SNAPSHOT` 断口回捞的节点都不参与这条命中。

本地只保留部分近期真实历史时，只要远端目标已经落在这段可证明窗口之外，就视为未命中当前主线祖先，最终仍属于 `FORCE_REBUILD`。

这条判断不因本地仍保留更晚的尾部节点而改变。

在具备有效野狐恢复元数据的路径上，新 session 的第一帧只允许“同手续接 / 差一手补一步 / 强制重建”，不依赖 `HOLD` 来拖延这类决策。

## 重建语义

`FORCE_REBUILD` 的行为固定为：

1. 截断当前同步段。
2. 放弃这段无法证明的旧历史。
3. 以目标局面创建新的 `SNAPSHOT` 锚点。
4. 后续只有拿到明确单步事件时，才继续追加真实 `MOVE/PASS`。

同步链路里的本地棋盘 / history 落地不依赖引擎是否可用。

无引擎或引擎未启动时，`FORCE_REBUILD` 仍允许完成 board-only rebuild；该帧不能因为缺少 `loadsgf` 通道而整帧失败。

有引擎时，`FORCE_REBUILD` 仍继续遵守 `exact snapshot restore` 的 `loadsgf` 生命周期契约。

同步链路不再通过“盘面看起来相同”去旧历史里回溯并继续接写手顺。

`snapshot rebuild` 创建新 `SNAPSHOT` 时，必须保留 setup、setup comment、`removed-stone` 标记、SGF property map、`extraStones` 元数据。

## 元数据修正边界

`metadata-only rebuild` 只修 `SNAPSHOT` 元数据。

它保留盘面内容、行棋方、`captures` 等已知局面状态。

sync / clone / round-trip / snapshot rebuild 路径上的 `SNAPSHOT` 必须保留 setup 相关元数据。

保留范围包括 setup 语义属性、setup comment、`removed-stone` 标记、SGF property map、`extraStones`。

`BoardHistoryList.sync()` 在主干节点相同的情况下，仍继续同步 variation 上 `SNAPSHOT` 的 setup 元数据变化。

这类同步覆盖 `AE/LB`、`removed-stone`、`extraStones` 等 metadata，不能只靠 child count 或主干比较短路。

`BoardHistoryList.sync()` 的 variation 同步结果与 source tree 保持一致；source 已删除的 variation 在本地同步后也一并删除。

同一父节点下，盘面相同但 setup metadata 不同的 sibling `SNAPSHOT` 是不同 variation 实体，不能在去重或同步时折叠成一个 child。

同一父节点下，`SNAPSHOT` variation identity 在现有 child state 基础上，还必须包含 `properties`、`hasRemovedStone`、`extraStones`；这些元数据存在差异时必须保留为不同 sibling variation。

这里的 setup 语义属性包含根节点 `handicap`、`AB/AW`、`PL`、`hasStartStone`、`startStonelist`。

中盘 `setup SNAPSHOT` 上的显式 `PL` 也属于必须保留的 setup 语义属性。

同一 SGF 节点同时包含 move、setup、comment / markup / `MN` 时，承载 setup 的 `SNAPSHOT` 必须保留该节点上的 setup 语义属性与 comment / markup / `MN`。

这类保留范围包含同节点首个 setup 属性之前出现的 comment / markup / `MN`。

这类 setup `SNAPSHOT` 只承载当前 SGF 节点元数据，父节点 metadata 保持在父节点。

这些属性跟随 setup `SNAPSHOT` 挂载，不能因 SGF 属性顺序漂移到前一手。

同一 SGF 节点同时包含 move、分析标签 `LZ/LZ2/LZOP/LZOP2` 和后续 `AB/AW/AE/PL` 时，setup 被拆成独立 `SNAPSHOT` 边界后，该节点 analysis payload ownership 跟随 setup `SNAPSHOT`；前一手 `MOVE` 节点不得残留该组 payload，`parseSgf()`、`loadFromString()`、`loadFromStringforedit()`、`saveToString()` 与 round-trip 统一遵守该 ownership，导出只在 setup `SNAPSHOT` 节点输出一次完整 payload。

同一 SGF 节点为“首手前但非 root 的独立 setup 节点”且同节点先出现分析标签 `LZ/LZ2/LZOP/LZOP2`、后出现首个 setup 属性时，该 analysis payload（含 primary/secondary 槽位、scalar 字段与 best-move 集合）owner 固定归属该 setup `SNAPSHOT`，并覆盖 `playouts == 0` 与 `playouts > 0`。

该场景下父节点与 root 节点都不保留这组 analysis scalar/collection 字段；`saveToString()` 只在该 setup `SNAPSHOT` owner 节点输出一次完整 payload。

在同节点 move + analysis + setup 场景下，后继节点若为独立 setup `SNAPSHOT`，analysis 继承保持为空；前一手 `MOVE` 节点保留自己的 payload owner 身份。

`saveToString()` 只在 payload owner 节点输出一次完整 analysis payload，导出结果保持唯一 owner 输出，且不生成仅含 `playouts/scoreMean/scoreStdev/pda` 数值字段的残缺 payload 壳。

materialized snapshot 在 engine-save 路径上也必须保留自己的 comment / markup，并持续挂载在该 `SNAPSHOT` 节点。

`SNAPSHOT` comment ownership 以 source tree 为准，sync 后再次 sync 结果保持稳定。

`playouts > 0` 的格式化 comment 只用于展示层，不能覆盖 source tree 上的 snapshot comment。

中盘 sync 产生的静态 `SNAPSHOT` 即使没有显式 `AB/AW/AE` 或 SGF property map，SGF 导出时也必须 materialize 成 setup 语义。

root `SNAPSHOT` 导出时，陈旧或 partial 的 `AB/AW/AE` SGF property map 只作为已有元数据参考，最终导出以当前根盘面与当前 `side-to-play` materialize 根 setup 语义。

root round-trip 后，根盘面与 `side-to-play` 与导出前保持一致。

中盘 setup/snapshot 导出时，materialized `AB/AW/AE` 与显式 SGF property map 按语义集合去重；同一石子在同一 setup 属性中只导出一次。

中盘 `SNAPSHOT` 导出时，陈旧或 partial 的显式 `AB/AW/AE` 只作为元数据参考；最终导出以当前 `SNAPSHOT` 盘面与当前 `side-to-play` materialize setup 语义。

当前 `SNAPSHOT` 盘面里不存在的 setup stone 不能因陈旧 property map 被重新写回导出结果。

这类 `SNAPSHOT` round-trip 后的盘面、`side-to-play`、历史边界必须与导出前一致。

## 功能边界

同步链路里的“跳转到指定手”和“跳转后几手”只保证目标盘面正确。

中间手顺回放只建立在真实 `MOVE/PASS` 历史上。

所有依赖真实手顺的功能都只消费 `MOVE/PASS`：

- movelist
- linked-list replay
- 编辑回放
- SGF 落子导出
- redo / auto-play
- 分析分支扩展
- quick overview
- quick analyze
- 胜率图和时间 / playout 统计
- 浮动分支预览
- save-load movelist
- 对局一致性比对
- 坐标跳转和“按某一手定位”

这些功能遇到 `SNAPSHOT` 时，将它视为历史边界或静态局面锚点。

这些功能只把最近一个真实 `MOVE/PASS` 视为真实最后一手。

`extraStones` 只属于 setup 元数据，不能进入 movelist、linked-list replay、编辑回放等真实手顺通道。

所有 `loadEngine=true` 的导航 / 恢复入口都先恢复最近 `SNAPSHOT` 锚点，再续接后面的真实 `MOVE/PASS`。

AnalysisEngine 组装分析请求时，`initialStones`、`initialPlayer`、`moves` 必须共同基于当前分析节点之前最近一个 `SNAPSHOT` 锚点。

当 root 含 `hasStartStone`，但当前分析节点之前已存在更近 `SNAPSHOT` 时，AnalysisEngine 基座切换到该最近 `SNAPSHOT`，并保持 `initialStones` / `initialPlayer` / `moves` 同基座一致性。

SGF 导出遇到 `SNAPSHOT` 时，以 setup 锚点语义落地，不把它导出成真实 `MOVE/PASS`。

quick overview、胜率图、浮动分支预览在 `SNAPSHOT` 处停在静态锚点。

quick overview、胜率图、浮动分支预览、save-load movelist 都不能为 `SNAPSHOT` 补造真实最后一手。

胜率图、quick overview、所有基于手数命中的 UI 在 `SNAPSHOT` 处只命中历史边界，不能命中不存在的中间手。

default 胜率图在 `SNAPSHOT` 节点即使 `playouts == 0` 也保留该历史边界命中。

胜率图节点命中按实际绘制点定位。

胜率图“按实际绘制点命中”规则覆盖普通模式、engine game / PK 模式、双曲线模式。

胜率图 hover / click / drag 在主图内优先按鼠标 X 映射的 move-number 列位命中；该列没有可见 anchor 时才退回最近可见列位。quick overview 命中仍只消费实际绘制出的 anchor 点。

`renderedGraphPoints`、`renderedQuickOverviewLayout` 只在生成它们的当前图状态与当前帧内有效。

`renderedGraphPoints`、`renderedQuickOverviewLayout` 的 freshness 同时绑定节点引用、模式标志与节点数据；分析结果、`playouts`、`score`、`snapshot fallback` 等会改变点位或可见性的原地更新发生后，旧缓存立即失效。

当前节点、variation/main trunk、play mode、panel mode、`showWinrateLine` 等任一影响图形可见点的状态变化后，命中流程使用新一帧渲染结果。

quick overview 命中集合以生产渲染路径实际绘制出的 dot 像素为准，包含生产 `Graphics2D` render hint 影响后的可见像素。

WinrateGraph quick overview 覆盖区命中以最终合成图可见像素为准；overview 空白区不命中节点，也不保留主图 anchor 的可见热点。

胜率图主图内空白背景复用同一列位命中语义；同一列存在多个可见点时仍按可见点的 Y 范围区分。quick overview 空白背景不命中任何节点，也不向主图透传命中。

`showWinrateLine=false` 时，主图不生成可见 winrate anchor，也不生成对应 hit-target。

quick overview 与胜率图复用同一命中语义，覆盖普通模式、engine game / PK 模式、双曲线模式。

胜率图在普通模式、engine game / PK 模式、双曲线模式都按真实 `moveNumber` 保留 `SNAPSHOT` gap 列位，不能按链表节点数压缩掉这类历史边界。

主线 / 支线在同一 `moveNumber` 重叠时，命中结果由图上可见点决定。

胜率图分支视图中，hover / click / drag 对同一个可见目标点都命中同一个节点，覆盖 forkNode 与更早祖先可见点。

分支视图 fork/ancestor 的 drag 回归用例以真实绘制层像素作为断言来源，命中语义与可见目标点保持一致。

真实 `moveList` 匹配类功能跨 `SNAPSHOT` 时跳过锚点，只消费 `MOVE/PASS`。

`contribute/watch-game` 对局一致性比对只在两个节点都是真实 `MOVE/PASS`，且坐标值（`PASS` 为空坐标）和颜色都一致时，才视为同一真实手顺。

`contribute` 中 `initMoveList` 与 `moveList` 的衔接从“已匹配前缀”的下一真实动作开始。

末尾已匹配节点只用于确定前缀长度，不参与下一轮重复比较。

`dummy PASS` 在 `contribute/watch-game`、`diff/blunder`、`quick analyze` 等消费方里都只作为占位节点，不能参与真实手顺匹配、差异计算、blunder 归因或历史动作分析。

save-load movelist 只保存和恢复真实 `MOVE/PASS` 序列。

## 工厂方法

`BoardData` 提供三个显式工厂：

- `BoardData.move(...)`
- `BoardData.pass(...)`
- `BoardData.snapshot(...)`

`snapshot(...)` 允许保留同步标记元数据：

- `lastMove`: 可为空；为空表示 markerless snapshot
- `lastMoveColor`: 仅在同步标记存在时有意义

`snapshot(...)` 还允许保留 setup 相关元数据：

- `extraStones`
- `removed-stone` 标记
- SGF property map

`SNAPSHOT` 永远不表示历史动作：

- 不进入 movelist、linked-list replay、编辑回放
- 不导出为 SGF 落子
- 不参与 redo / auto-play / export 的历史动作判定
- 不得被渲染或统计为 `PASS`
- 不得被当成引擎待分析落子
- 不得作为“按坐标跳到某一手”的真实目标

## 代码修改优先级

当实现与本契约不一致时，按下面顺序收敛：

1. `ReadBoard` 同步主流程
2. `SyncSnapshotRebuildPolicy` 历史匹配策略
3. `Board` 坐标跳转 / 导航 API
4. 分析、统计、导出、自动播放、对局比对

## 完成标准

以下场景全部成立时，这条分支视为收敛：

1. 同步源一次跳过多手，本地直接重建到目标局面，历史里没有虚构中间手顺。
2. 同步源从后面跳回前面，本地直接重建到目标局面，旧同步段在此结束。
3. 应用内真实 `PASS` 保持完整工作。
4. 棋盘同步工具不会生成伪 `PASS`。
5. 所有依赖真实手顺的功能都把 `SNAPSHOT` 当作局面锚点处理。
6. 所有 `loadEngine=true` 的恢复入口都通过最近 `SNAPSHOT` 恢复静态局面，并得到与 `SNAPSHOT` 一致的引擎盘面。
7. 根节点 setup / handicap 的 `side-to-play` 由显式 `PL` 或固定默认规则稳定决定。
8. `contribute` 的 `initMoveList` / `moveList` 衔接只消费下一真实动作。
9. 胜率图命中按实际绘制点定位，同手数重叠节点也能命中图上可见节点。
10. 中盘静态 `SNAPSHOT` 导出 SGF 后再 round-trip，盘面、`side-to-play`、历史边界保持一致。
11. 分支切换、任意跳转、关闭后重开引擎后的恢复都先命中最近 `SNAPSHOT` 锚点，再续接后面的真实 `MOVE/PASS`。
12. 根节点 setup/save round-trip 中，根 setup 语义只落在根节点一次，连续保存输出保持稳定。
13. 中盘 setup/snapshot 导出时，materialized `AB/AW/AE` 与显式 property map 按语义集合去重，单石单属性只导出一次。
14. `exact snapshot restore` 里 `loadsgf` 发送阶段失败（含先入队后发送链路）、收到 GTP `?` 错误响应、或已成功发出后消费方长期无响应都会显式结束 dispatch、进入清理流程、终止后续真实 `MOVE/PASS` 重放，并显式抛出恢复失败。
15. 双引擎模式下 snapshot restore 的临时 SGF 在两个引擎都完成消费后删除；若一侧已发出快照加载请求且另一侧发送失败，兜底清理在已发出侧真实消费完成后执行，临时 SGF 生命周期覆盖该消费全程。
16. SGF 中“首手前但非 root 的独立 setup 节点”在解析、保存、round-trip 后保持独立 `SNAPSHOT` 子节点，`moveNumber` 维持 `0`。
17. `parseBranch` / variation 首节点出现“先 setup 后落子”时，setup 落成 `SNAPSHOT` 边界，后续真实落子继续保持 `MOVE/PASS` 语义。
18. materialized snapshot 在 engine-save 路径上保留自己的 comment / markup，属性归属稳定挂载在该 `SNAPSHOT` 节点。
19. 胜率图“按实际绘制点命中”规则在普通模式、engine game / PK 模式、双曲线模式、分支视图一致生效；分支视图里 hover / click / drag 对同一可见目标点命中同一节点，覆盖 forkNode 与更早祖先点。
20. setup 节点中位于首个 setup 属性之前的 comment / markup / `MN` 归属当前 setup `SNAPSHOT`。
21. 所有 SGF 字符串加载入口（含编辑态加载）与主 SGF 解析入口在 setup `SNAPSHOT` 边界语义上保持一致。
22. default 胜率图在 `SNAPSHOT` 的 `playouts == 0` 场景仍可命中该历史边界。
23. 分支视图 fork/ancestor 的 drag 回归以真实绘制层像素断言，命中行为与可见目标点一致。
24. 双引擎恢复中，一侧已发出快照加载请求、另一侧失败且已发出侧无响应时，流程仍完成兜底清理并释放对应处理器。
25. 双引擎恢复中，一侧收到 GTP `?` 错误且另一侧无响应时，恢复流程返回失败，并完成临时 SGF 与两侧处理器清理。
26. root `SNAPSHOT` 导出在陈旧或 partial `AB/AW/AE` property map 场景仍以当前根盘面与当前 `side-to-play` materialize 根 setup 语义，root round-trip 后根盘面与 `side-to-play` 保持一致。
27. 引擎支持矩阵以 KataGo / 通用 GTP 为本轮验收范围，Leela Zero / leelaz / Leela 0.11 / Sai 保持 legacy engine path 且移出本轮阻塞范围。
28. 双引擎 snapshot restore 在主/副入口都复用同一 mirror restore 合约，副入口发起时另一侧也完成 `loadsgf` 后续真实 `MOVE/PASS` 镜像重放。
29. 同父节点下，`SNAPSHOT` child state 相同但 `properties`、`hasRemovedStone`、`extraStones` 任一不同时，variation 保持并行保留。
30. `SNAPSHOT` comment ownership 以 source tree 为准，连续 sync 结果稳定，`playouts > 0` 格式化 comment 不覆盖 source snapshot comment。
31. 胜率图主图的 hover / click / drag 在图内复用上述列位命中语义；同列重叠点继续按可见点 Y 范围区分。quick overview 命中只消费实际 anchor 点，quick overview 空白背景不命中节点，普通模式、engine game / PK、双曲线模式命中语义一致。
32. `requireResponseBeforeSend=true` 下，若已排队 `loadsgf` 在真正发送阶段失败或无响应超时，当前命令退休 outstanding 后，发送窗口恢复通过真实队列推进触发，后续已排队命令立即继续发送。
33. `renderedGraphPoints` / `renderedQuickOverviewLayout` 只在生成它们的当前图状态与当前帧有效；影响可见点状态变化后命中使用新帧渲染像素，quick overview 空白背景不向主图透传，`showWinrateLine=false` 时主图不生成可见 winrate anchor 与对应 hit-target。
34. `exact snapshot restore` 中 `loadsgf` 若在 `BufferedOutputStream` / 底层 `write` 路径失败，失败命令残留字节会在后续 `flush` 前清理完成；失败或过期 `loadsgf` 与后续命令发送边界保持隔离。
35. WinrateGraph quick overview 覆盖区命中与最终合成图可见像素一致，overview 空白区不命中节点且不保留主图 anchor 可见热点。
36. `renderedGraphPoints` / `renderedQuickOverviewLayout` 的 freshness 绑定节点引用、模式标志与节点数据；分析结果、`playouts`、`score`、`snapshot fallback` 等原地更新后旧缓存立即失效。
37. `exact snapshot restore` 中 `loadsgf` 若在底层 `write` 阶段已写出部分字节后再失败，该命令对应引擎通信流进入污染态；后续命令发送前先完成该输出流显式失效处理。
38. 双引擎 mirror restore 只在当前调用实例属于主/副引擎之一时生效；第三实例或临时引擎实例触发 `loadsgf` 时只作用于自身，不镜像到 `Lizzie.leelaz2` 或另一主/副引擎实例。
39. `parseSgf(...)` / detached history 解析保持隔离上下文：解析阶段当前 live board 的 `hasStartStone/startStonelist`、player title、komi、引擎 komi/best-move 等窗口状态保持不变，相关副作用在调用方显式采用解析结果后生效。
40. AnalysisEngine 组装分析请求时，`initialStones`、`initialPlayer`、`moves` 共同命中当前分析节点之前最近 `SNAPSHOT` 基座；root 含 `hasStartStone` 且中间已有更近 `SNAPSHOT` 时，以最近锚点为准。
41. `LZ/LZ2/LZOP/LZOP2` 单行 header-only analysis payload 在 `parseSgf(...) -> setHistory(...)` adopt -> `saveToString(...)` -> round-trip 全链路保持等价；header 的 `engineName`、playouts、`scoreMean`、`scoreStdev`、`pda` 持续保留。
42. 同节点 move + analysis + setup 场景里，analysis payload owner 固定挂载 setup `SNAPSHOT`；后继独立 setup `SNAPSHOT` 的 analysis 继承保持为空；`saveToString()` 只在 owner 节点输出一次完整 payload，导出无重复且无残缺数值 payload 壳。
43. “首手前但非 root 的独立 setup 节点”里，analysis 标签先于 setup 属性出现时，analysis payload owner 仍固定挂载该 setup `SNAPSHOT`；父节点与 root 不保留该组 scalar/collection 字段；`saveToString()` 仅在 owner 节点输出一次完整 payload，primary/secondary 与 `playouts == 0` / `playouts > 0` 语义一致。
