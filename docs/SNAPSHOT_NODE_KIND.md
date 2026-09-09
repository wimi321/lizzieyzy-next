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
- `foxMoveNumber` 修正 `SNAPSHOT` moveNumber metadata；在 `side-to-play` 上只作为 markerless 兼容兜底，不能覆盖可信视觉 marker，也不能在让子、setup 或白方首手等风险场景下作为 GMA 权威轮次。
- Fox `foxMoveNumber 0` 且 markerless 盘面只有多颗黑方 setup 石时，可按让子初始局固定规则把该 `SNAPSHOT` 判为白方落子；这不是用 `stoneCount` / `deviation` 推断末手，也不补造历史。
- ReadBoard `lastMoveSource` 区分真实视觉 marker 与启发式末手；只有真实视觉 marker 可在冲突时优先决定 `side-to-play`。
- 启发式 `deviation` / `stoneCount` 不能被当成真实 `MOVE/PASS` 或 GMA 权威轮次；若同步主流程已经把某帧接受为一手可证明的真实 `MOVE`，GMA 可信任该真实落子落地后的本地轮次。

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

ReadBoard 协议里的 `pass` 行在自动落子/交换顺序链路中表示用户显式切换当前轮次；Lizzie 通过 `changeNextTurn()` 应用该覆盖，不创建真实 `PASS` 节点，也不把它纳入真实手顺。

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
- 起始局面编辑只允许原地修改 root-only `SNAPSHOT`；黑/白设置子、擦除、清空和
  `side-to-play` 修改都不能创建 `MOVE/PASS`、手数或 variation，也不经过提子、打劫、
  自杀等普通落子规则。
- “将当前盘面设为起始局面”是显式的破坏性转换路径，也可在进入起始局面设置模式时触发。
  树中存在非 dummy 的真实 `MOVE/PASS` 时必须先确认；取消确认不能修改 history。
- 确认转换后，当前显示棋子与当前 `side-to-play` 形成新的 root-only `SNAPSHOT`；原有
  手顺和 variation 全部删除，同时保留 `GameInfo`、root comment 与非 setup SGF 属性。
- 转换后的 history 必须通过 `Board.setHistory(...)` 正式采用，使棋盘尺寸、Kata/PK 派生
  标志和 history-overwrite 通知与新 root 保持一致；不能直接替换 history 引用。
- 设置模式内的编辑只在本地生效，不向引擎发送普通 `play`。退出设置模式后，通过
  `EngineFollowController` 排队并使用既有 exact snapshot restore 同步最终 root setup；
  setup stones 不能进入普通引擎手顺重放。
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
- `ExactSnapshotEngineRestore` 是 exact snapshot restore core owner：负责 immutable snapshot/current-position plan、静态锚点 materialization、真实 `MOVE/PASS` tail、临时 SGF、captured target 的 `loadsgf -> tail` sequencing、cleanup 与 completion。它不拥有 generic lifecycle、root replay、reservation、restart fence 或 ponder。
- 普通 caller 在自己的 owner 语境中捕获 ordinary admission；switch/restart/PK/foreground/GMA adapter 在自己的 owner 语境中先冻结 opaque admission，再通过窄 capture seam 取得 exact plan。`PreparedRestore` 只暴露 one-shot `execute() -> Completion`，不暴露 komi、target、mirror、tail、SGF、root payload 或 dispatch state。
- 所有 lifecycle 入口在第一个外部副作用前冻结 exact route，或明确冻结 owner-local root route；后续 stop/name/komi/clear/start/readiness/replacement callback 只能执行该 frozen route，不能重新读取 mutable history、engine slot 或 mirror。
- `EngineManager` 与 `Leelaz` lifecycle owner 各自持有 target、captured mirror、owner/admission、root/exact decision、reservation lifetime、readiness、availability、restart board fence 与 ponder disposition；本轮不新增通用 lifecycle module。
- lifecycle handoff、root initialization/payload/route state、reservation acquisition、restart orchestration、endpoint inclusion、ponder disposition 与 raw target/mirror getter 不属于 exact module；`LifecycleRestoreHandoff`、`mirrorLifecycleOwnedByOperation`、precommand choreography 和 `capturedKomi()` introspection 均不保留。
- lifecycle root replay 继续使用入口既有的 live-board/root-movelist 产品语义，但由 owner 在 captured target/mirror/admission 下一次性执行；root 路径不通过 exact failure fallback，也不在副作用后重新 `prepare`。
- captured mirror 不是独立 lifecycle reservation endpoint。secondary switch 只 reserve frozen previous secondary 与 frozen target；PK start/restart 只 reserve target；mirror 竞争在 exact/root enqueue 时按 captured admission fail-closed。
- exact Board restore 的 preclear 只发给 plan capture 时的 target set，不能在执行时重新解析 `Lizzie.leelaz2`。exact module 完成边界是所有 target 已接受 `loadsgf` tail 命令，调用方随后按自己的 fence 与 disposition 收敛 owner 状态。
- 异步 history navigation 的 exact admission 额外冻结 primary-engine generation。主引擎替换后，旧 generation 在 `notPondering`、preclear、`loadsgf`、tail 与 ponder disposition 前均 fail-closed；secondary/third-engine resend 使用既有非 primary-bound Board sync admission，不受该 generation fence 扩面。
- foreground/GMA adapter 只负责把自己的 session/reservation identity 映射为 opaque admission，再调用 generic history/current-position capture；产品-specific stop、name、komi、clear、quarantine 与 completion policy 留在 adapter/owner。
- 自动/直接 restart 的 exact 与 root 路线都经过同一个 owner board synchronization fence；owner 只能在 fence 成功后恢复 captured ponder，失败或不可用时不启动分析，并在既有 completion boundary 释放 reservation。
- `Leelaz` 继续唯一拥有 ordinary command queue、response handler、timeout、late-response retirement、output-stream invalidation 与 engine arbitration；exact module 只通过窄 admission-aware seam 使用这些能力。
- 手动终止 genmove 对局后，空 numbered ACK 仍是非终态；迟到的合法 analyze `play` 只结清原 reader binding 的 pending handler，不追加应用的真实 `MOVE/PASS` 或比赛结果。缺失终态继续按既有五秒物理请求 watchdog 回收。
- 已停止对局的前台引擎在物理请求退役后，由 `EngineManager` 异步冻结并执行当前应用盘面的 root/exact 恢复；退役屏障保留到既有稳定 board synchronization fence 完成。恢复命令仅获该 lifecycle owner 对原 binding 的写入授权，失败将原目标标为 unavailable，替换实例不受旧归还影响；手动停止不自动恢复 ponder。
- `PreparedRestore` 可在首次 `execute()` 前由捕获它的 owner 调用 one-shot `discard()` 释放 captured admission；`discard()` 不写临时 SGF、不发任何命令。首次 `execute()` 或 `discard()` 后，另一操作必须以既有 `Exact snapshot restore has already been executed` 失败；一旦 execute 开始，所有失败仍按 owner 的既有 fail-closed 语义处理。
- 没有可用静态锚点时，调用方保留既有 root replay；默认空 root 不是 exact 锚点。exact 一旦开始，`loadsgf`、tail 或 arbitration 失败都原样失败，禁止猜测性 root fallback。
- lifecycle exact/root 抛错时，owner 将 frozen target 标为 unavailable，并在既有 completion boundary 释放 reservation；不因本票据新建 `ENGINE_STATE_UNRESTORED` 或通用 retry。ReadBoard GMA 固定点既有 quarantine/retirement 行为保持独立。
- ponder 只由 lifecycle owner 在全部目标恢复和自身 board fence 成功后按 capture 时的 disposition 决定；restore module 不擅自停止或启动 ponder。
- tail replay 的 module 完成边界不等同于每条 GTP response 完成；后续 response/error、超时和 late-response isolation 继续由 `Leelaz` 管理。
- `exact snapshot restore` 的 `loadsgf` 生命周期按固定顺序执行：
  1. `loadsgf` 临时 SGF 准备完成后，命令先入队再发出。
  2. 命令发出前，当前次 `loadsgf` 的 pending response handler 与 dispatch 归属绑定完成，并持续到退休或完成。
  3. 发送失败时，当前次 pending handler 与 outstanding response 计数同步退休，dispatch 显式结束为失败。
  4. 收到 `?` 错误响应时，当前次 pending handler 与 outstanding response 计数同步退休，dispatch 显式结束为失败。
  5. 无响应超时时，当前次 pending handler 与 outstanding response 计数同步退休，dispatch 显式结束为失败。
  6. 只有 `loadsgf` 成功消费后，dispatch 才能结束为完成态。
  7. dispatch 完结后，才允许重放 `SNAPSHOT` 尾部真实 `MOVE/PASS`。
  8. 双引擎模式下，临时 SGF 生命周期覆盖两侧 `loadsgf` 消费与尾部真实 `MOVE/PASS` 重放；任一侧返回 `?` 后也不能取消另一侧已经 dispatch 的 consumer，直到两侧都真实消费、timeout retirement 或 fallback cleanup 后删除。
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

## 普通局面分析确认（Issue #429 / Ticket 01）

- 普通落子与连续真实 MOVE/PASS 前后导航先采用目标 history 节点，再转发引擎命令；分析请求在入队时冻结显示节点、Board context revision、引擎槽位与 reader incarnation，不能在 info 到达时重新选择来源目标。
- `Leelaz` 在位置命令入队时退休旧分析 generation，物理写出时仍执行原有 payload epoch/generation 失效。普通分析只有在依赖的位置响应成功结算后才能物理启动；无关只读查询的 pending response 不影响已合法启动的分析流。
- `undo`、`play`（含真实 PASS）与 `set_position` 使用精确 numbered response identity 和既有 queued/pending retirement。错误、发送失败或超时使所属位置 lineage 失败；迟到响应不能恢复失败来源，也不能结算后继命令。完整替换位置建立新的 lineage，但本身仍需确认。
- `requireResponseBeforeSend` 的普通队列等待真实已写出的响应义务，不使用包含尚未发送后继命令的计数阻塞自身。Engine-game 的精确 ACK/terminal 与 arbitration 语义独立保留。
- 解析和最终节点 publication 均复验冻结目标。被退休、目标过期或未确认的位置输出不能进入候选缓存或 ownership-only backfill；有效历史缓存、SGF 已保存分析、同上下文暂停/继续与主副引擎独立槽位保留。显式失效仍由既有 `isChanged/isChanged2` 允许首个合法低 visits 接管。
- KataGo 普通分析区分精确 root visits、候选 move visits、可选 edgeVisits 和推荐 order。总量与全局预算使用存在的 root；缺少 root 时保留 legacy 非对称候选求和。候选文字使用 move visits，分配比例使用存在的 edge（包括 0），排名沿用 order；节点评价仍来自首选候选。
- 旧 legacy 缓存准入只用 incoming 的同一 legacy 算法比较；通过保护门后可以整份保存 incoming 的真实 root。已存 root 与缺失 root 的新流不可比较，未显式失效时保留缓存。同一已采纳输出 owner 的同 root 新帧可更新评价/PV；新请求、binding 或真实搜索重建不继承该例外。ownership-only 回填不改变候选、计算量口径或已采纳来源。
- SGF 的 LZ/LZOP 及副槽在六个传统 header slots 后保存 `rootVisits=<精确整数>`，候选 PV 前保存 order 和存在的 edgeVisits。无标识数据保持 legacy/unknown；clone/restore 保留分析值但不复制活跃流刷新权限。扩展放在同一分析 payload 内，旧 writer 重建 payload 时一并降级。
- 用户显式通过准入的同树 focus 可为当前节点对应引擎槽位授予整份普通分析采纳权限，允许首份合法精确 root payload 替换更高旧缓存。首份前取消/暂停/失败/context 变化退休未消费权限；已采纳值在完成、取消和同局面暂停后保留。仅 focus 参数重发延续同树刷新关系；真正清树、binding/位置替换仍退休旧权限。具体关注、进度与渲染行为见 `docs/TRACKING_ANALYSIS_CONTRACT.md`。
- 跨 SNAPSHOT 的复合恢复及 ReadBoard 单次最终 resume 遵守下列对应 owner 契约。

## 复合局面恢复确认（Issue #429 / Ticket 02）

- Board 在首次位置转发前冻结目标节点、context revision、轮次、盘尺寸、主引擎 generation 与 captured mirror；exact plan 或 root 命令序列同时冻结。后续恢复和 completion 不重新选择当前 history 或引擎槽位。
- 普通 resend、跨静态节点的历史导航与 removed-stone 节点恢复共用 Board owner 的目标确认。恢复中的真实 MOVE/PASS 不单独触发最终目标分析；成功 disposition 必须同时满足冻结目标仍有效、用户未暂停分析和捕获引擎身份仍有效。
- capture 已完成但首条位置命令尚未入队时，普通分析请求也保持等待；同一原队列允许其依赖的位置命令和最终 fence 先执行。owner 确认后释放该请求；取消或失败只退休所属 capture，不影响后继恢复。
- 用户分析暂停同时退休原普通队列及已选中但尚未取得物理写出许可的普通分析请求，包括恢复等待期间新增的请求；取消与 `beginOutputWrite` 竞争同一命令状态，计数只退休一次。位置命令、foreground restore 与 engine-game owner 的命令不随此取消；已经取得写出许可的命令沿用既有停止流程。
- `Leelaz.PositionRestore` 仅提供捕获、作用域内命令执行与 callback confirmation，复用 ordinary queue、精确 response identity、timeout 和 retirement。一次复合恢复的 clear、尺寸/komi、loadsgf/set-position 与真实 tail 共用所属 endpoint 的失败 lineage；中间完整替换命令不得重置本次先前失败。
- 最终 board synchronization fence 同时等待 captured authority/mirror 的全部 required position responses 与各自最终 name 响应。单独的 name 成功不能覆盖先前位置命令错误、发送失败或超时；迟到响应只结清原操作，不能结算或使不同后继操作失效。
- exact module 继续拥有静态锚点、临时 SGF 消费与真实 tail sequencing；最终 owner confirmation、root replay 和 ponder 留在现有 owner。exact 一旦开始，失败不切换到 root fallback。
- Board 恢复的 GTP 等待在 EDT 和 Board monitor 外执行。完成时重新检查冻结目标，过期 completion 不恢复分析，也不把替换引擎当作原恢复目标。
- foreground handback 已冻结的同一 Board/主引擎盘面恢复先按捕获内容完成确认，再由现有 owner 检查稳定性并追赶最新目标；导航发生在 companion close 期间不能提前截断该收敛循环。Board 或主引擎 incarnation 替换仍拒绝旧恢复。
- 同一合法目标的缓存和已导入 SGF 分析继续保留；主副引擎槽位独立。未确认或已失效来源不得建立新的 visits 高水位。board-only 同步及真实 PASS、dummy PASS、setup 语义保持原合同。

## ReadBoard 单次同步分析恢复（Issue #429 / Ticket 03）

- 普通 ReadBoard 快照接受在 Board monitor 内完成本次本地导航、落子、视图恢复及 history adopt；这段收集期间不转发中间位置命令，也不触发中间分析。Board 从入口节点到最终目标冻结一次增量 undo/play 或完整 root/exact 路线，真实 GTP 执行和确认在 monitor 与 EDT 外完成。
- 已有节点命中保留原节点 identity 和全部已证明历史，不克隆目标、补造 MOVE/PASS/SNAPSHOT 或删除尾部。前次同步尚未确认或已失败时，新目标使用自己的完整恢复路线，不把未确认的本地起点当作引擎盘面。
- 增量同步继承起点已有的位置 response lineage，包含本次接受前已排队或已写出的普通位置命令；前置 play/undo 失败不能被新同步 capture 清除。只有完整 root/exact 替换路线可建立独立恢复 lineage，且仍须确认本次全部 required responses。
- ReadBoard 是本次 final resume disposition owner；延迟回调与 Board 确认两者均已完成后，才允许对同一目标最多启动一次普通分析。回调不再调用棋谱加载恢复，不发第二次 clear/replay/loadsgf，也不启动自动棋谱快析。
- 最终 resume 重查 sync epoch、confirmed-local-move 保护、Board identity/context revision、目标节点、captured primary generation、引擎可用性和 read-board 分析设置；错误、发送失败、超时及过期目标恢复零次。
- 用户分析暂停立即失效 pending ReadBoard resume，并继续遵守普通队列及 selected-before-write 取消规则。暂停后用户再次继续分析，也不能使旧同步回调重新取得恢复资格。
- 一致且无需恢复的重复快照不重新捕获恢复或重启合法分析流。普通首次同步直接采用最终视图，不以先回退再延迟前进触发额外分析。
- 无引擎时仍完成本地 board/history 更新；GMA 和对局 continuation 保持独立的路由与 ownership exclusion。手动导航、手动分析恢复及普通棋谱加载策略保持原契约。

## 初始启动导航契约（Issue #223）

初始引擎启动恢复属于 lifecycle owner 的协调范围，遵守以下合同：

- 初始启动捕获发生在首个外部 lifecycle 副作用之前：frozen immutable route（exact 或 root replay）与 board frame 同时冻结；此后不重新 prepare、不修改正在执行的旧 plan。
- 启动期间 UI / history 导航继续正常工作，但目标引擎不能交错接收普通 live-board `play/undo/clear/resync/analyze`。该窄 admission 只作用于 initial synchronization 活跃的目标引擎，`name/version/list_commands/komi/boardsize` 等启动握手命令不受影响；frozen / catch-up route 自身在 exact restore admission 语境下发送的命令也明确绕过该 gate。
- board frame 至少包含：history root identity、current node identity、Zobrist / 盘面、side-to-play、komi、`Board.contextRevision`（以及盘尺寸）。frame 捕获与每轮稳定性判断在 `synchronized (Board)` 内完成；restore 执行必须在锁外完成，不能阻塞 EDT。
- 每轮流程固定为：在当前 reservation 下执行 immutable route（loadEngine=false）→ 保持 initial synchronization active 并在 Board monitor 外释放 reservation → 在锁内重新捕获 frame。frame 一致时，reservation 已释放，在线性化判断的同一临界区结束屏障；不一致时在同一 lifecycle owner 下捕获新的 immutable catch-up route，退出 Board monitor 后重新获取 reservation（拒绝则 fail-closed），再执行下一轮。
- route 执行前，在同一 admission 语境下把引擎盘尺寸与 route 捕获 frame 对齐（exact route 由 SGF SZ 语义承担，root replay 必须显式 `boardsize`）；komi 由 route 捕获值在恢复时收敛，普通入口不补发。
- 普通 history-overwrite 入口（`clear` / `clearForOnline` / `reopen` / `setHistory`）与 frame 捕获共用同一个 Board monitor；屏障期间的普通 live-board resync（`resendMoveToEngine`、`clearAndSyncBoard`、`resendCurrentPositionToPrimaryEngine`、增量同步、`reopen` 的 engine resize）整体跳过，靠 catch-up 收敛。
- 最终 handoff 必须是两阶段提交：当前 route 完成后屏障保持 active，先锁外释放 reservation，再在 `synchronized (Board)` 内重查 frame；只有重查一致时才在该临界区结束屏障。这样导航要么发生在重查前（普通命令仍被屏障抑制，并触发新 catch-up），要么发生在屏障结束后（reservation 已释放，普通命令可直接到达已对齐的引擎），不存在“屏障已结束但 reservation 仍持有”的丢转发窗口。
- `markEngineReady`、ponder 与 analyze 只能在 reservation 已释放、屏障已结束的稳定恢复点之后执行一次，按既有启动策略（`initializeAfterVersionCheck`）触发；name 响应路径在屏障活跃期间不得提前 `markEngineReady`。
- 任一初始或 catch-up restore 失败都 fail-closed：目标引擎置为 unavailable、不发送 ponder/analyze、释放 lifecycle reservation、结束 initial synchronization 状态、走既有引擎同步失败提示；不执行猜测性 root fallback。
- `ExactSnapshotEngineRestore` 继续只负责单份 immutable exact plan；追赶最新棋盘的 lifecycle 收敛逻辑只属于 EngineManager 的 initial startup owner，不进入 exact module。

## 连续同步与本地落子确认权限（Issue #432）

- 本地落子的远端确认权限属于当前连接的 `ReadBoard`，仅在 helper 未退役、仍为窗口当前
  helper、连续同步 `syncBoard` 开启且双向模式 `bothSync` 有效时成立。管道与 socket
  保留各自传输可用性条件；帧处理标志 `isSyncing` 不表示连续同步权限。
- `Board` 出站与本地落子抑制、`ReadBoard` 直接 placement admission、重试和最终失败
  提交使用同一权限。停止后 helper 可以保持连接，本地 MOVE 按普通棋盘规则保留。
- `stopsync` / `endsync` 退役 pending ACK identity，清除 confirmed protection 和失败
  落子观察／抑制，并执行已有 placement UI cleanup。停止既不删除 pending MOVE，也不
  声称远端已确认它；停止本身不创建 `MOVE/PASS/SNAPSHOT`。
- pending 创建、ACK 结清、停止退役与最终失败提交由 ReadBoard 的短确认状态临界区串行化；
  history 提交先取得既有 Board monitor，再进入确认临界区。确认临界区不等待引擎、
  helper 输出或 EDT，也不取得 ReadBoard/GMA monitor。Board 负责纯 history 删除及 revision。
  本地落子的 pending／失败抑制检查也在同一 Board monitor 内、history 变更之前执行。
- 失败提交同时验证 helper、ACK generation、pending node、Board/history identity 和 mainEnd。
  停止先退役时，旧 callback 不删谱、不建立失败抑制、不启动旧节点引擎恢复；有效失败先
  提交时，该 history 决定保留，随后在临界区外按当前盘面与既有 primary generation fence
  恢复引擎。GMA 仍由原 session/engine arbitration owner 决定恢复归属。
- 出站 placement 捕获 pending identity；管道与 socket 均在实际输出锁取得后、开始写入前
  复验。已开始的写入或 helper 已接受的物理点击不能撤回。阻塞输出不占用 EDT 或确认状态锁。
- shutdown、helper replacement、外部换谱使旧 captured work 对当前棋盘失去权限。
  `clear` 与同尺寸 `start` 是活动帧边界，仍保留 pending 供随后快照确认；尺寸变化沿用
  既有 history/resume 失效规则。
  `end` 才提交一帧；`endsync` 直接退役并丢弃未提交采样，不隐式提交缓冲快照。
- `placeComplete` 只表示点击完成提示，匹配快照才是 ACK 证据。无编号的旧 wire failure
  不能与新 pending 可靠关联，继续遵守活动同步既有失败策略；host callback identity 不等于
  wire request ID。
- 暂停时保留有效 `resumeState` / `lastResolvedSnapshotNode` 和 first-frame re-arming。
  主动恢复后，远端快照仍按既有匹配／重建规则决定同步盘面；单次读盘不要求先开启连续同步。

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
44. 起始局面编辑在 root-only `SNAPSHOT` 上原地完成，设置子工具与轮次选择不创建真实
    `MOVE/PASS`、手数或 variation，也不执行普通落子合法性与提子规则。
45. 显式当前盘面转换在真实 `MOVE/PASS` 存在时先确认；确认后保留显示盘面、
    `side-to-play`、`GameInfo` 和 root 非 setup metadata，删除全部手顺/variation，并以 root
    `AB/AW/PL` 稳定 round-trip，不生成伪普通落子。
46. 当前盘面转换通过标准 history adoption seam 重新推导尺寸与 Kata/PK 标志；被删除子树
    独占的 analysis payload 不能在转换后遗留 board flag 或伪造 root `DZ`。
47. 设置模式期间不发送普通引擎落子；退出时通过既有 exact snapshot restore 异步同步最终
    root setup，且 setup stones 不进入 movelist、save-load movelist 或普通引擎重放。
