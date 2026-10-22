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

SGF `DD[true]` 恢复独立的 dummy PASS 占位节点，包括保存器输出的 `(; ;DD[true])` 终端分支；标记不能跨节点或分支污染后续真实动作。兼容同节点 `DD[true]B/W[...]` 时，占位节点与真实动作保留为两个兄弟节点，真实动作的颜色、MOVE/PASS、手数与 payload 不变。文件、字符串、编辑态、detached 与分支解析采用相同语义，dummy 不参与初始行棋方取证。

同节点 `DD[true]` 后仍有真实 `B/W` 时，注释、标记和受支持的 `LZ/LZOP/LZ2/LZOP2` 分析 payload 归属真实动作；payload 位于 `DD` 前、`DD` 与动作之间或动作之后，不改变归属。dummy 不是已完成的真实动作，不能使节点内 payload 提前落到占位节点。若节点结束前没有真实动作，payload 留在该独立 dummy；暂存不得跨 SGF 节点或分支边界。上述归属在连续两次保存重开后保持稳定。

独立 dummy 的 `DD[true]` 与自身注释、标记、双引擎分析必须序列化在同一 SGF 节点；连续两次保存重开后，父节点与相邻兄弟不能接收该 payload。根 SGF 节点内先 `DD` 后真实动作时，同样按节点整体确定 payload 归属；即使 payload 出现在 `DD` 前也归真实兄弟。识别节点边界必须尊重属性值内的转义及结构字符。普通根节点、根 setup 与后续独立节点的 metadata 边界及显式 `PL` 权威保持不变。

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
- 同一棋局内的起始局面转换保留 immutable 会话规则目标，不重新解释 root `RU`，也不
  继承旧 history 的失败规则继续许可。独立转换入口在有引擎时通过既有棋谱 owner 先确认
  规则、再同步最终 root；双方盘面确认后仅恢复转换前正在进行且用户未暂停的普通分析。
- 设置模式内的编辑只在本地生效，不向引擎发送普通 `play`。退出设置模式后，通过
  `EngineFollowController` 排队并使用既有 exact snapshot restore 同步最终 root setup；
  setup stones 不能进入普通引擎手顺重放。
- SGF 中“首手前但非 root 的独立 setup 节点”必须保留为独立 `SNAPSHOT` 子节点，`moveNumber` 保持 `0`，不能并入 root setup。
- SGF 初始行棋方统一规则（2026-10-14）：
  - 范围是 SGF 中首个真实 `MOVE/PASS` 之前的初始局面，包括根节点与独立初始 setup `SNAPSHOT`；不扩展 ReadBoard 轮次推断或中盘 setup 规则。
  - 在导入阶段按以下优先级确定各初始局面的 `blackToPlay / side-to-play`：
    1. 当前局面有效的显式 `PL[B] / PL[W]`，包括开局过程中仍有效的上游指定。
    2. 当前局面主线上的首个真实 `MOVE/PASS` 的颜色。
    3. 已证明的初始让子局面默认白先：有效 `HA >= 2` 与实际已完成的黑方设置子数量一致，没有白方设置子或其他冲突；不能仅凭 `HA` 或仅数棋子。
    4. 其余情况默认黑先。
  - 主线按 SGF 的第一分支确定；从当前局面沿主线取证，旁支不投票、不覆盖主线，也不随浏览选择改变判断。真实 PASS 与真实落子具有相同行棋证据效力，dummy PASS 不作为首手证据。
  - 首手证据可以穿过纯注释、标记节点，但不能跨越新的摆子、删子或显式 `PL` 节点向前反推。独立初始局面分别判断，不能把后续摆子局面的轮次提前应用到尚未摆子的空根节点。
  - 显式 `PL` 沿同一路径、尚未发生真实动作的开局过程向后延续；单纯摆子不消耗轮次，后续新的 `PL` 替代此前指定。默认值或推断值不能冒充上游显式指定，阻止后续局面独立判断。
  - 显式 `PL` 与后续首手冲突时保留指定，回放仍执行谱内原始 `B/W`；缺少有效 `PL` 时，主线首手证据优先于让子默认，即使 `HA` 与盘面不匹配也不能阻挡这项补全。不能改写落子颜色、让子声明或手数，不能补造 PASS。
  - 文件、字符串、编辑态和 detached 解析入口采用同一套规则；轮次在解析阶段统一确定，不依赖添加子节点时顺带改写父节点轮次的副作用。导入中的初始分支局面遵守同一规则；在已有真实动作之后导入的分支不属于本规则范围。
  - 导入完成后，浏览分支、提升主线、删除首手均不自动重判已确定的初始行棋方；用户通过现有执子方切换操作明确修改。重新导入外部棋谱才重新执行导入规则，内部编辑态重建必须保留当前已确定的轮次。
  - 保存时为对应初始局面显式写出已确定的 `PL`，包括推断值、默认值和用户修改值；保存重开及重复保存保持盘面、轮次、真实手顺与独立 setup 边界稳定。
  - 编辑撤销与重做保存完整 SGF 和当前节点的分支索引路径；路径每层记录实际直接子节点的位置，包括首分支为终端 dummy 的情况。重新载入后定位该节点，保留独立 setup、真实 PASS 和分析元数据。仅包含真实动作的 movelist 不用于重建已保存的历史树。
  - 历史导航、显示与引擎恢复只消费已确定的轮次，不另行推断。初始行棋方解析与引擎让子恢复资格分别判断；不满足 `set_free_handicap` 条件不代表不能白先，本次不放宽该命令的资格、能力或恢复确认条件。
  - 不按文件来源、文件名、某个固定让子数量或固定落点增加例外；上述 `HA >= 2` 是 SGF 让子声明的通用定义，不是针对样本的数量特判。
  - 语义依据：SGF FF[4] 的 [PL](https://www.red-bean.com/sgf/properties.html#PL)、[HA](https://www.red-bean.com/sgf/go.html#HA) 定义及用户指南的[第一分支为主线](https://www.red-bean.com/sgf/user_guide/index.html#style)。缺少 `PL` 时按主线补全及导入后保持轮次，是本项目已确认的产品规则，不宣称是规范要求的唯一策略。
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
- 文件和字符串 SGF 导入保留谱内已保存的 analysis payload；让子／setup 或 `KM` 不等于默认贴目本身不构成分析失效证据，不能据此清空全谱缓存。让子／setup 导入继续采用谱内贴目并按既有流程同步引擎。
- 启用 Lizzie 缓存时，导航回载入的已分析节点后，普通新分析流只有在可比计算量超过已保存值时才替换候选点、胜率、目差与变化图；既有显式缓存失效、PDA 变化、专用分析模式和 ownership 单独补全规则保持不变。
- 文件、字符串、编辑态和 detached SGF 导入统一兼容属性值之外、结构空白位置的字面量 `\n` 与 `\r\n`；属性值原文继续交给既有 SGF 转义解析，不全文反转义，不改写输入文件。
- 未知结构转义、双重包装、被转义换行拆开的属性名，以及含本次结构转义的未闭合树/属性值，必须导入失败；live 入口保留原棋谱，detached 入口返回 `null`。普通 SGF 的既有无关容错保持不变。
- detached 与 live 导入都把 `RE` 保存为 `GameInfo.result`；不能将结果伪造成根或末节点注释，保存重开保持真实注释与对局结果。
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
- 普通 KataGo 启动的 PDA/WRN 回读由 `Leelaz` 持有独立轮次，绑定既有 numbered pending response 与 reader incarnation；规则 operation 的成功、失败和超时不结算参数轮次。startup-post 查询仍遵守原有实际写出／失败关闭边界。
- PDA/WRN 仅在同一有效轮次两项有限数值均成功回读后，通过现有工具栏路径发布完整一对；回复次序不影响结果，合法零值不是空白。错误、格式错误、缺失或超时不以自动载入配置／默认零补齐成功。取消、替换及旧 timeout 不影响后继轮次；EDT 发布时复验原前台 generation、reader 与轮次，隔离启动不得更新全局显示。
- 普通分析广度只依赖当前 KataGo 的可用性与普通操作资格，不要求快速分析独占租约的完整命令集。启动、切换和重启在最终 lifecycle endpoint 释放后刷新控件；EDT 执行时重新读取当前前台身份及门禁，不缓存旧的 enabled 值。刷新只投影 enabled 状态，保留勾选与数值，不写配置、不发参数命令、不启动分析；独占任务、未恢复状态、Fox 所有权及 genmove 禁用约束保持有效。
- 手动终止 genmove 对局后，空 numbered ACK 仍是非终态；迟到的合法 analyze `play` 只结清原 reader binding 的 pending handler，不追加应用的真实 `MOVE/PASS` 或比赛结果。缺失终态继续按既有五秒物理请求 watchdog 回收。
- 已停止对局的前台引擎在物理请求退役后，由 `EngineManager` 异步冻结并执行当前应用盘面的 root/exact 恢复；退役屏障保留到既有稳定 board synchronization fence 完成。恢复命令仅获该 lifecycle owner 对原 binding 的写入授权，失败将原目标标为 unavailable，替换实例不受旧归还影响；手动停止不自动恢复 ponder。
- 对局中所有贴目入口统一提交给当前 engine-game owner；界面区分已确认贴目与待应用目标，连续输入只保留最新目标，不修改 frozen opening plan 或后续批次默认值。GENMOVE 已发出的当前合法手须先完成并由对手接受，再开始贴目切换。
- runtime komi 使用 captured owner 与 reader incarnation 授权，双方 `komi` ACK 及最终 `name` fence 均成功后，才原子提交当前 GameInfo 与分析缓存；连续目标间不恢复搜索，最终恢复遵循最新暂停意图。旧 komi revision 的分析与落子回调不得提交。
- 任一贴目确认拒绝、发送失败、超时或实例丢失均 fail-closed 结束当前批次，保留最后确认贴目，不制造结果或 successor。部分写入后失败或取消须退役可能持有未确认贴目的原实例；旧实例确认不得影响替换实例或新对局。
- 分析模式 KataGo 引擎对局的广度（`analysisWideRootNoise`）与激进度（`playoutDoublingAdvantage`）由 `EngineGameParameterSession` 按批次持有，按引擎 1／引擎 2 位置而非颜色绑定。首局覆盖前按 exact engine + reader incarnation 读取双方两项实际值作为不可变的批次前恢复值，全部读取完成后才开始写入；每局应用批次冻结的两项开局值并逐项回读确认，局中“对局参数”修改只影响本局，不写配置、不改冻结开局值或恢复值。
- 局中修改经当前 engine-game owner 与落子提交、runtime komi 串行：runtime komi 与参数更新由同一 `EngineGameSearchUpdates` worker 按提交顺序逐个执行，排队中的更新以自身 pending 状态阻止中途恢复搜索。每次更新先停止并排空已准入的落子操作，再递增 search revision 使旧搜索结果失去落子资格，然后只为目标与已确认值不同的位置/参数设值、回读（只改 PDA 不重发 WRN，反之亦然），全部所改项确认后才按最新暂停/终止意图恢复搜索，不重置本手计时；部分确认不算成功。确认失败暂停对局，未解决的参数不一致阻止普通继续。一次请求中任一所选位置不支持所改参数时，在任何写入前整体拒绝。非 KataGo 参赛方不支持两项，专用 PDA 引擎（`KataGoPda`、报告动态 PDA 的引擎）不支持激进度，不通过 `kata-set-param` 覆盖其策略，也不读取或恢复其 PDA：开局值 0 视为不改并跳过，非零则拒绝开局。不改写 `playoutDoublingAdvantagePla` 等颜色/动态配置。
- 批次正常结束或用户终止后才恢复各位置两项原值（批次间隔不恢复）；恢复只写原 incarnation，逐项设值并回读，任一项失败时强制退出该进程并报告；原进程已被替换或退出时不向替换进程重放旧恢复，但同样报告该位置未能恢复。对局期间普通工具栏与高级参数的 WRN/PDA 直接修改禁用，不再存在引擎对局专用的“关闭广度”。
- `PreparedRestore` 可在首次 `execute()` 前由捕获它的 owner 调用 one-shot `discard()` 释放 captured admission；`discard()` 不写临时 SGF、不发任何命令。首次 `execute()` 或 `discard()` 后，另一操作必须以既有 `Exact snapshot restore has already been executed` 失败；一旦 execute 开始，所有失败仍按 owner 的既有 fail-closed 语义处理。
- 没有可用静态锚点时，调用方保留既有 root replay；默认空 root 不是 exact 锚点。exact 一旦开始，`loadsgf`、tail 或 arbitration 失败都原样失败，禁止猜测性 root fallback。
- lifecycle exact/root 抛错时，owner 将 frozen target 标为 unavailable，并在既有 completion boundary 释放 reservation；该路径不引入 `ENGINE_STATE_UNRESTORED` 或通用 retry。ReadBoard GMA 固定点既有 quarantine/retirement 行为保持独立。
- ponder 只由 lifecycle owner 在全部目标恢复和自身 board fence 成功后按 capture 时的 disposition 决定；restore module 不擅自停止或启动 ponder。
- 当前进程线程覆盖的来源恢复复用自动 restart owner。该操作可将 captured ponder 延后到 final fence 与 endpoint release 之后：只向同一 Leelaz 的精确后继 reader 回读线程值、异步交接 PDA/WRN 草稿；交接成功且原 foreground identity 仍有效才执行一次既有 resume。失败或替换不恢复分析，普通 restart caller 的完成时序不变。线程设置或来源恢复回调只交接窗口打开后用户实际改过的 PDA/WRN 草稿；未改的副本不覆盖后继初始化或期间产生的较新值。
- 回滚恢复的初始化若将 KataGo 能力探测明确延迟到当前 lifecycle completion claim 释放 endpoint 后，允许同一 reader incarnation 先提交恢复；在后续分析命令实际写出前，旧分析输出仍保持隔离。需要继续分析且未延迟的恢复继续要求新的物理分析 owner，不能把 READY 或排队成功当作分析所有权。
- tail replay 的 module 完成边界不等同于每条 GTP response 完成；后续 response/error、超时和 late-response isolation 继续由 `Leelaz` 管理。
- exact module 在任何 `clear_board`、Remote Compute restore 或其他 engine mutation 之前，为全部本地 target 完成 SGF 落盘、可读性检查与 process-incarnation 复验；任一 target 无安全路径或落盘失败时，清理本轮已创建文件并以 snapshot-preparation failure 终止，不能留下半恢复状态。
- `Leelaz` 在最终 `ProcessBuilder` 配置（含 bundled runtime cwd override）完成后，把本地文件系统类别与显式 cwd 绑定到实际 reader/process incarnation；pre-start 捕获的 restore route 在执行时只采用新 admitted incarnation 的证据。落盘后 process rebind、退休或 admission 失效时，旧文件不能生成替换实例的 `loadsgf` 命令。
- GTP 文件参数必须是非空 printable ASCII（字符 33–126）且不含 `#`。候选顺序固定为：安全的默认临时目录绝对路径；有最终 cwd 证据时该 cwd 下的唯一 ASCII 相对文件名；既有应用 runtime / Windows 共享目录约定下的安全绝对路径。相对参数保持相对，不能在 dispatch 时重新绝对化。
- direct local engine 即使没有可信 cwd，仍可使用安全共享绝对路径；已知 SSH、WSL、Wine、container 或其他隔离文件系统 transport 不得使用 host `loadsgf`。仅当 frozen history 证明初始让子（HA 与实际黑子一致、无此前真实动作及冲突 setup/PL）且当前 admitted reader 完成 `set_free_handicap` 能力发现时，允许通过实际坐标的 in-band handicap 命令恢复，再重放真实 tail。空黑先根节点只清空，不提前应用后续让子。其他隔离 transport（含 `.bat`/脚本等间接启动器）的任意快照：当前 admitted reader 的 `list_commands` 声明 `set_position`（KataGo）时，通过 in-band `boardsize`/`komi`/`set_position` 恢复静态锚点，再重放真实 tail，全程不生成 host 文件路径；`set_position` 后引擎默认黑方行棋，因此白方行棋且没有真实 tail 的局面只允许交给分析与落子命令显式携带行棋方（`kata-analyze W …`、`genmove W`）的 reader，否则在改变引擎棋盘前以 `UNSUPPORTED_REMOTE_POSITION` fail-closed。未声明 `set_position` 的隔离 transport 继续以 `SNAPSHOT_PREPARATION` fail-closed；Remote Compute 的任意快照保留既有 in-band 路径与白方无 tail 限制。双引擎时本地 `loadsgf` 侧与 in-band 侧分别执行，in-band 侧的 preclear 不查找临时 SGF 文件访问证据。
- 双引擎 cwd 相同且安全绝对路径可用时共享同一物理文件；cwd 不同时可各自持有独立文件。所有本轮物理文件的删除边界覆盖全部 target 的 ACK/error/timeout/late-response retirement 以及真实 tail replay，首个 ACK 不能提前删除任一文件。
- 安全路径选择只改变物理文件地址与 GTP 参数；SGF 语义仍由 frozen plan 唯一生成，必须保留 `SZ`、可用 `KM`、`PL`、`AB/AW` 与 rectangular / extended-coordinate 行为。
- Board primary owner 在冻结目标时同时冻结锚点、初始行棋方、让子来源与真实 tail；执行时才绑定 admission，不重新读取可变历史。SGF 初始行棋方在导入时按本节统一规则确定，恢复只消费冻结结果。独立初始 setup 保持独立 `SNAPSHOT`、`moveNumber=0`，不得把后续摆子及其轮次提前应用到空根节点。所有 target 的 capability/路径 preflight 必须先于任何棋盘 mutation。
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

## 普通局面分析确认（Issue #429）

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

## 复合局面恢复确认（Issue #429）

- Board 在首次位置转发前冻结目标节点、context revision、轮次、盘尺寸、主引擎 generation 与 captured mirror；exact plan 或 root 命令序列同时冻结。后续恢复和 completion 不重新选择当前 history 或引擎槽位。
- 普通 resend、跨静态节点的历史导航与 removed-stone 节点恢复共用 Board owner 的目标确认。恢复中的真实 MOVE/PASS 不单独触发最终目标分析；成功 disposition 必须同时满足冻结目标仍有效、用户未暂停分析和捕获引擎身份仍有效。
- capture 已完成但首条位置命令尚未入队时，普通分析请求也保持等待；同一原队列允许其依赖的位置命令和最终 fence 先执行。owner 确认后释放该请求；取消或失败只退休所属 capture，不影响后继恢复。
- 新完整恢复 capture 建立后，旧恢复的后续位置命令仍归属旧 lineage，不得把 endpoint 的最新 queued lineage 改回旧值；新 owner 等待自己的全部响应和 fence。旧恢复的失败或取消不影响新 capture 的确认与分析恢复。
- 用户分析暂停同时退休原普通队列及已选中但尚未取得物理写出许可的普通分析请求，包括恢复等待期间新增的请求；取消与 `beginOutputWrite` 竞争同一命令状态，计数只退休一次。位置命令、foreground restore 与 engine-game owner 的命令不随此取消；已经取得写出许可的命令沿用既有停止流程。
- `Leelaz.PositionRestore` 仅提供捕获、作用域内命令执行与 callback confirmation，复用 ordinary queue、精确 response identity、timeout 和 retirement。一次复合恢复的 clear、尺寸/komi、loadsgf/set-position 与真实 tail 共用所属 endpoint 的失败 lineage；中间完整替换命令不得重置本次先前失败。
- 最终 board synchronization fence 同时等待 captured authority/mirror 的全部 required position responses 与各自最终 name 响应。单独的 name 成功不能覆盖先前位置命令错误、发送失败或超时；迟到响应只结清原操作，不能结算或使不同后继操作失效。
- exact module 继续拥有静态锚点、临时 SGF 消费与真实 tail sequencing；最终 owner confirmation、root replay 和 ponder 留在现有 owner。exact 一旦开始，失败不切换到 root fallback。
- Board 恢复的 GTP 等待在 EDT 和 Board monitor 外执行。完成时重新检查冻结目标，过期 completion 不恢复分析，也不把替换引擎当作原恢复目标。
- foreground handback 已冻结的同一 Board/主引擎盘面恢复先按捕获内容完成确认，再由现有 owner 检查稳定性并追赶最新目标；导航发生在 companion close 期间不能提前截断该收敛循环。Board 或主引擎 incarnation 替换仍拒绝旧恢复。
- 自动快析及后台主线补全（含复用预加载 worker）完成只采纳分析结果并保留当时的浏览节点 identity，不为完成通知推进棋盘，也不退回启动节点。借用主引擎时继续由 foreground lease 恢复并确认最新局面后归还；lease 持有期间不得借 `BOARD_SYNC` 导航。用户暂停、换谱或主引擎替换使旧 completion 失效，不能恢复 ponder；手动闪电分析既有完成导航不变。
- 同一合法目标的缓存和已导入 SGF 分析继续保留；主副引擎槽位独立。未确认或已失效来源不得建立新的 visits 高水位。board-only 同步及真实 PASS、dummy PASS、setup 语义保持原合同。

## 自动棋谱快析任务与交接

- `AutomaticQuickAnalysisTask` 是一次性任务：构造不启动，窗口登记后调用 `start`，后续导航通知同一任务。等待、退避重试、watchdog、attempt 回调与归属清理在任务内推进；状态限定 EDT，启动、请求发送和关闭在后台执行。窗口只保留用户意图、当前任务／载入上下文及产品入口的交接策略。
- 每轮尝试持有独立使用凭据。启动结果交接前属于启动 owner；撤销登记不能撤销后来使用者的登记。交接后仅该凭据可清除自己的回调、退出或移交 worker；旧任务／旧尝试迟到不影响后继使用者。预加载意图与申请登记分别保留，启动去重和原有 backend／租约／模式保留规则不变。
- 结束原因不代替恢复结果。`ForegroundRestoreResult.NOT_REQUIRED` 表示没有所需恢复；`SUCCEEDED`／`FAILED` 仅由实际前台恢复分支产生，不能从请求数、空闲或普通失败推导。任务失败可在归还后提供非终结前台机会，同时继续重试；窗口仍须检查当前上下文和用户暂停。已确认位置证明绑定 Board、节点、revision 和主引擎 identity，导航或引擎改变后不得复用。
- 用户暂停取消当前任务并保留已完成结果；清理期间明确请求继续，仅在当前载入上下文仍有效且所需恢复成功后恢复前台，失败保持暂停。换谱和引擎切换等待所需恢复结清后继续，失败也可继续；手动自动分析则在恢复失败时拒绝启动。结清不是 OS 进程退出，不为独立后台进程增加所有载入都等待退出的门禁。
- 当前任务可以关闭自己实际拥有的可复用预加载 worker；手动闪电分析接管则移交使用权，迟到自动回调不得清除手动请求或关闭其 worker。任务完成保留最新浏览节点及真实 MOVE／PASS 分析，SETUP／SNAPSHOT 不进入主线请求；既有前台租约继续负责最新局面的真实恢复与确认。
- 自动快析借用的前台租约在归还时失败（最终 stop 发送失败、错误或超时，或规则／盘面恢复失败）后，`Leelaz` 把 owner 已记录的失败原因绑定到该租约实际借用的 reader incarnation，并在 EDT 发布时绑定当时的 primary generation；reader、primary 选择或实例变化后该记录不再适用，只有新 reader 的合法启动与恢复确认才使引擎重新可分析。租约未激活前的初始 stop 失败不是归还失败，沿用既有 fail-closed 处理；transport 关闭沿用引擎 down 处理；手动闪电分析保留既有提示。窗口只消费当前记录：状态区域分两行显示“引擎状态未恢复”与失败类别、既有“重启当前引擎”菜单，不再回退为“加载中”；分析按钮呈现为未计算并在 tooltip 给出同一指引。空格、按钮等显式继续被该状态拒绝时只显示同一份非模态恢复说明（最多一份同时显示），说明归属产生它的 reader，失败不再适用时自动关闭；拒绝时不修改用户暂停意图、不登记 pending resume、不同步盘面、不发送分析或重启引擎。`isLoaded=false` 与既有 readiness 门禁不放宽，导航、换谱和关闭提示都不清除该状态。
- 当前 reader 的归还失败检查优先于分析控件对 active 自动任务的暂停判定：即使主线仍缺结果、自动任务正在退避重试，首次空格／分析按钮操作也只显示同一恢复说明，不取消任务或改变暂停意图。健康自动任务仍按原语义暂停／取消。
- 前台租约的初始 stop 响应由租约 owner 直接消费，不经过普通 pending-response 匹配或未匹配响应隔离。仅租约捕获且仍为当前、未终止的 reader 上的精确 stop 编号成功 ACK 加终止空行才能激活；ACK 本身、错误编号、旧 reader 或缺失空行均不能激活。其他严格编号请求仍待回复时，不得截获该合法 ACK；错误、发送失败和超时保持既有 fail-closed 语义。
- 自动棋谱快析通过本次使用凭据显式进入准备策略，包含复用预加载 worker；worker 构造用途、静默请求、显示名及 `useRemoteCompute` 均不授予流水资格。仅合法激活且实际 reader 绑定内置 `ZhiziGtpTransport` 的前台分析租约可启用最多 8 条在途准备命令。
- 窗口只覆盖同一局面的连续真实 `play`（MOVE／PASS）；原规则捕获、元信息及 `set_position` 静态锚点保持确认屏障。逐条独立编号并在物理写出前登记义务；全部必要准备成功才发送一次分析，setup／dummy PASS 不进入真实重放。
- 任一准备错误、发送失败或最早未结清义务超时均停止补发和该局面分析，沿用失败通知与真实租约归还结果；后续成功不刷新更早命令的截止时间。重复、迟到及无关编号与其终止空行不结算分析或最终 stop。
- 使用权退休立即撤销新的发送许可，不等待已获许可的物理写出；后者由既有停止／恢复流程收敛。旧请求、reader 及 timeout 不推进后继使用者；手动接管不继承自动流水策略。本地、其他远程后端及非自动分析保持既有行为。
- 同一自动用途凭据与实际内置智子 reader 的前台租约可在完整编号 `list_commands` 确认 `kata-search_analyze_cancellable`、`kata-get-param`、`kata-set-param`、`stop` 后选择有限搜索；每租约只选择一次。明确缺能力沿用持续分析，查询错误、畸形或超时失败；本地、其他远程与非自动请求保持原策略。
- 有限搜索先以四条独立编号命令完整捕获 `maxVisits/maxPlayouts/maxTime/ponderingEnabled`，再以四条独立编号命令安装原批次 visits、同值 playouts、`1e20`、`false`；全部捕获成功前不覆盖，全部覆盖完整确认前不准备局面。各阶段固定截止不被后续 ACK 延长，参数小批次不扩大真实 play 窗口。租约在写出前持有原值及恢复义务，取消或不确定写出不能丢弃该义务。
- 每节点以编号有限搜索的成功头、有效候选及 root visits、非取消 `play` 和终止空行为完成边界；root visits 至少达到原目标才通过同一结果准入采纳一次。选点不执行落子，不要求等于第一候选；中间 info 不触发逐节点 stop 或提前发布。完成 watchdog 从物理发送起计时，info 不续期。
- 智子有限命令使用 `10000` 厘秒输出间隔，以取得自然结束时完整预算的最后 payload；持续分析保留既有输出间隔。该间隔不改变 visits/playouts 预算、不延长完成 watchdog，也不替代完整终态与 root visits 准入。
- 有限搜索归还保留租约最终 stop，并等待原搜索终态与该 stop 的精确完整响应；随后由同一租约在原 reader 恢复所有可能覆盖的参数，全部成功确认后进入既有规则、最新棋盘和稳定恢复屏障。参数错误、缺 ACK、超时或 reader 替换均不能由后续 name 成功覆盖。恢复 ponder 参数不授予分析；预加载手动移交也等待该边界，用户暂停后的继续只恢复前台。
- 参数恢复错误在引擎仲裁锁内判定归属，完整失败收尾在锁外执行；失败回调与 Board 导航采用的锁顺序兼容，必须结清一次失败并保持引擎不可用，不启动后继分析。

## ReadBoard 单次同步分析恢复（Issue #429）

- 普通 ReadBoard 快照接受在 Board monitor 内完成本次本地导航、落子、视图恢复及 history adopt；这段收集期间不转发中间位置命令，也不触发中间分析。Board 从入口节点到最终目标冻结一次增量 undo/play 或完整 root/exact 路线，真实 GTP 执行和确认在 monitor 与 EDT 外完成。
- 已有节点命中保留原节点 identity 和全部已证明历史，不克隆目标、补造 MOVE/PASS/SNAPSHOT 或删除尾部。前次同步尚未确认或已失败时，新目标使用自己的完整恢复路线，不把未确认的本地起点当作引擎盘面。
- 增量同步继承起点已有的位置 response lineage，包含本次接受前已排队或已写出的普通位置命令；前置 play/undo 失败不能被新同步 capture 清除。只有完整 root/exact 替换路线可建立独立恢复 lineage，且仍须确认本次全部 required responses。
- ReadBoard 是本次 final resume disposition owner；延迟回调与 Board 确认两者均已完成后，才允许对同一目标最多启动一次普通分析。回调不再调用棋谱加载恢复，不发第二次 clear/replay/loadsgf，也不启动自动棋谱快析。
- 普通分析驱动的 ReadBoard 自动落子也走上述收集、盘面确认和单次 final resume；`isPondering` 或 `analysis-started` 仅表示分析意图，不能替代物理分析命令及有效输出。确认后的自动落子恢复由 ReadBoard 专属入口执行，不放开通用棋谱加载恢复对自动落子模式的排除（Issue #560）。
- 最终 resume 重查 sync epoch、confirmed-local-move 保护、Board identity/context revision、目标节点、captured primary generation、引擎可用性和 read-board 分析设置；错误、发送失败、超时及过期目标恢复零次。
- 用户分析暂停立即失效 pending ReadBoard resume，并继续遵守普通队列及 selected-before-write 取消规则。暂停后用户再次继续分析，也不能使旧同步回调重新取得恢复资格。
- 停止同步、关闭自动落子、切换自动落子执子方或模式都会失效旧的 pending resume；随后重新开启也不能复活旧回调。最终恢复还须满足当前自动落子启用、非用户暂停及既有自动分析/对局互斥条件。GMA 仍由其独立调度器负责，不转入普通 `kata-analyze` 路径（Issue #560）。
- 一致且无需恢复的重复快照不重新捕获恢复或重启合法分析流。普通首次同步直接采用最终视图，不以先回退再延迟前进触发额外分析。
- 无引擎时仍完成本地 board/history 更新；GMA 和对局 continuation 保持独立的路由与 ownership exclusion。手动导航、手动分析恢复及普通棋谱加载策略保持原契约。

### GMA 退出后的普通自动落子授权

- 已有 GMA 准备、物理请求或恢复工作时，普通 `play>` 只登记最新切换意图，并撤销旧 GMA 的落子授权；不得提前发送普通分析或与恢复冲突的位置命令。新 `play>` 替换旧意图，不替换旧物理工作的收尾责任。
- 收尾结果绑定原 reservation 和 reader incarnation，只有必需恢复成功且实际释放 reservation 后才可报告成功。准备阶段的零副作用取消可成功；错误、超时、终止、reader 替换或 quarantine 不授权普通分析。session terminal 和 reservation 字段为空均不能单独证明成功。
- 等待期间的权威快照继续更新本地 Board/history，只更新待恢复目标。收尾成功后，切换 owner 通过既有 Board/位置确认流程确认最新目标及全部 required position ACK、最终 fence，再一次性消费仍有效的意图。旧目标被后来的权威快照取代时，跟进最新目标确认；当前目标确认失败仍不启动分析。
- 权威恢复目标通过原物理 terminal capability 在同一 session 锁内接收并返回结果；当前退休的 GmaInFlight 仍可更新目标，exact/runtime 阶段沿用后继恢复，旧/外来 capability 和终态不得接收。该能力不恢复 helper 授权，不产生新 GMA 或点击。路由不得把未接收的目标报告为已处理。
- 尚未发送或尚未确认的目标责任属于绑定 drain、reader、Board 与主引擎代际的等待上下文，不属于可替换的普通启动意图。再次普通授权复用该责任；停止/暂停只取消自动启动，重新显式授权仍须补齐未确认盘面。仅对应当前目标的成功位置确认清除责任；Board/reader/引擎身份改变时不继承到新实例。成功 fence 不能替代目标 SGF/位置命令的实际发送。
- 停止、暂停、手动导航、Board/history/helper 替换、引擎切换或重启永久取消旧意图；重新开启或切回不复活旧回调。重新选择 GMA 即使被资源准入拒绝，也取消先前等待中的普通意图。最终分析准入与取消、替换共享串行边界，旧回调不能在取消完成后重新启动。切换等待期间的普通同步恢复与候选落子暂缓，不能绕过该 owner。
- 显式普通 `play>` 保留主动启动语义，不受后台同步的 `readBoardPonder` 开关抑制；普通同步恢复仍遵守其原设置。GMA 活跃期间不发送普通分析，只有退出 GMA 后的新有效授权可以启动它。
- 普通候选落子的排他边界覆盖仍在途或恢复中的物理 GMA，不只检查当前模式或待切换意图。再次选择 GMA 被拒绝并取消普通意图后，旧 GMA 的 `info` 仍不得触发普通 `play`；不能将引擎最终已执行的落子重复发送回引擎。


## SGF 会话规则确认（Issue #448）

- adopted `BoardHistoryList` 拥有独立于 root `RU` 元数据和引擎观测的 immutable 会话规则目标；外部采用和手动成功选择递增规则 revision，普通导航、SNAPSHOT 与试下还原不重新解释 root `RU`。
- `SGFParser` 的 `syncPrimaryEngine=false` 与 detached/edit 解析只产出棋谱，不发布会话目标或发送规则命令；GUI owner 在成功采用后显式发布。公共同步入口发布目标后进入同一规则→盘面确认顺序。
- 导入 owner 只捕获当前前台与当时活动的比较副引擎。每侧必须获得 fresh `CONFIRMED` actual 且与目标语义相同，才执行既有 frozen position restore/fence 并最多恢复一次分析；无 `RU` 继承现状，不发送规则操作。
- 无效声明、能力失败、set/get 错误、超时、未确认或回读不匹配均为规则永久失败，不进入盘面临时 `RETRY`。棋谱保持可浏览；用户明确按现有规则继续后仍须完成盘面确认，许可只对捕获的 history/rules revision/primary generation/比较实例有效，且不改变规则失败状态。
- GUI 采用本地解析结果后，Board 保留与该 history 绑定的待对齐状态；等待、规则失败及拒绝继续时，普通前后导航、分支和静态节点跳转只改变本地 history，不向旧引擎盘面发送增量或隐式重建。只有当前导入／生命周期 owner 完成双方位置响应与最终 fence 并复验捕获上下文后才解除；取消请求本身不表示对齐，解析回滚恢复原对齐状态。
- 规则失败后的显式分析继续重新使用当前失败提示，不重试 set/get，也不先开启分析 UI；拒绝或关闭仍停止，同意仅授权当前上下文并完整同步当前显示节点。等待中的重复继续请求不创建第二份恢复。
- 试下返回先在本地恢复保存的分支节点及会话规则，再提交一次最终位置恢复；不通过 live movelist 重放恢复历史，不越过 owner 追加 ponder，用户暂停继续生效。
- 加载活动比较副引擎若重建了双方盘面，最终 owner 在双方 fence 后恢复主引擎分析并由既有镜像通道启动副引擎，双方各一次；已暂停或捕获实例过期时不恢复。
- lifecycle 非 Engine Game owner 把会话规则目标纳入 `BoardFrame`，每轮在盘面恢复前、Board monitor 外确认 captured target 与 mirror；规则 revision 变化触发现有 release/recheck/catch-up。Engine Game 继续使用自身 match-rules 语义。
- 手动规则 set→get 成功且 actual 与请求语义一致时，实际观测成为当前 history 的新会话目标；失败只保留协议终态，不恢复旧导入请求。
- 普通分析入队与活动比较集合的发布共享 selection 临界区，锁顺序为 selection → primary → history → endpoint/queue。selection 内仅尝试取得 primary；竞争时先释放 selection，再等待并复验同一 primary generation 和比较集合，不能丢弃仍有效的分析请求或阻塞 primary owner 的物理写出。规则收敛或显式继续后的盘面恢复期间退出比较模式时，既有 coordinator 只在同一 history/规则 revision/primary generation/reader 仍有效时接续剩余主引擎；已知规则失败仍须为新引擎集合重新取得显式继续许可，不自动重试 set。
- 棋谱 coordinator 的终态及上下文失效回调在 EDT 执行前复验最新请求 generation；worker 已完成但 UI 尚未消费的旧回调，不能越过换谱或比较模式变更的确认边界。
- 已准入的本地主引擎显式重启在发布新 primary generation 前，将重启前 generation 与本次 switch token 通知当前棋谱 coordinator。仍属于同一 history/rules target/主引擎及比较集合的导入责任等待该 token 成功，再以新 reader/generation 重新执行既有规则→盘面确认；只有该确认可以清除窗口棋谱门禁，不能仅凭 loaded/ACTIVE 清除。重启失败保持门禁；换谱、规则变更、后继 switch、reader/generation 变化或关闭使旧回调失效。旧规则继续许可不迁移，接续不重置用户暂停意图、不主动重启，不改变远程重连、比较退出或初始启动的责任边界。
- 本地重启期间退出比较模式时，若退出先于重启通知交付，等待安装入口将捕获的副引擎移出本次恢复集合；若等待已经安装，则由上下文失效回调转交。两种顺序都仅接续同一导入责任的剩余主引擎，保留原 switch token 与 history/rules target/主引擎身份校验。仍须等待该 token 成功，再用新 reader/generation 重新确认规则与盘面；不能因退出比较模式或 reader 已 loaded 提前释放门禁，也不能向已退出的副引擎恢复棋谱。

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

- 野狐 marker 的颜色抖动可以忽略，但 marker 所在交点必须已有棋子；空交点不能命中该帧。
- 连续同步中，已接受且仍显示在 `mainEnd` 的真实 `MOVE`，若整盘与新帧一致而直播标题
  手数仅落后一手，保留该节点及真实历史，判为 `NO_CHANGE`。标记有无／颜色抖动沿用
  既有盘面比对规则，不能因此切回缺子的祖先或重建为 `SNAPSHOT`。
- 上述标题滞后处理不跨同步重启、房间身份冲突或强制重建，也不适用于棋谱浏览窗口。
  真实盘面回退、其他手数差异及 `PASS/SNAPSHOT` 仍走既有历史匹配／重建流程。

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

变化推演 `Branch` 只消费显式的隔离输入：宽高、源棋子、行棋方、节点种类、末手、
手数/提子数及显示元数据、PV/PV visits、长度和提子/编号选项。空 PV 保留源节点的
`SNAPSHOT` 与 `dummy`；真实 PASS 消耗一手并清空末手坐标，resign 终止推演且不消耗一手。
推演只推进私有数组和标量，结束时构造结果，不修改源 history，也不复制未用于显示的 SGF 属性。

GUI 捕获局面使用 Board 短锁，捕获分析列表使用与 BoardData 写入方配对的独立短锁；
两个阶段后复核 Board/history/current/source/candidate 身份、context revision、宽高及行棋方。
同节点换手同样使输入失效；锁内不推演、绘图或调用引擎。候选所属节点与推演源分别解析，
保留前一手、第二路分析、显示节点、浮动窗主线末端/编辑态及副棋盘保留变化的既有来源。
主棋盘、独立棋盘和浮动棋盘在候选进入时捕获不可变请求目标（PV、PV visits、来源及显示长度），
步进与再次选点立即更新目标，不依赖图片就绪。首次尚无可见结果时，应用输入消费该目标；
已有结果时，逗号及中键消费当前可见版本的 PV 和长度，新目标和图片就绪后整批替换可见版本。
石子、阴影和标注由共享生成器从捕获的局面、几何和样式生成私有结果；生成阶段不读取活动窗口、
配置或 history，不调用引擎。禁止鼠标移动刷新时冻结 PV 与 visits，几何/样式变化仍使用该选择。
中键按下取消显示并保留该次手势的选择和长度，候选切换或步进不改写它，松开只消费一次；
落子按下（即使落子无效）、清除、离开、来源切换及窗口/浮动候选隐藏使相应选择和手势失效。
独立棋盘打开普通右键候选菜单时保留本窗口选择，菜单动作不改用主棋盘或第一候选。
浮动浏览的候选即使在主线末端合法，也必须在当前推演源的对应交叉点为空，不能覆盖当前棋子。
无本窗口选择时上下键/滚轮执行原历史导航，不消费另一窗口的选择；独立棋盘 Page 键保持主棋盘路由。
主窗口双引擎模式的普通上下键可消费第二路的选择；回到第 1 手仍由该选择接收输入。
主棋盘、独立棋盘和浮动棋盘从完整预览后退时使用捕获的有效长度上限，图片未完成时也不越过该上限。
三宿主在输入事件中立即提交完整请求，共用单个后台 worker；每宿主只保留最新待办，
按宿主 FIFO 轮转。后台完成全部推演、石子/阴影/标注后自行投递 EDT，复核来源、代次、
几何、样式、模式及窗口生命周期后整批发布并轻量 repaint，不等待引擎输出或悬停计时器。
同局面、来源、模式、几何和样式下的步进或换点仅退役旧工作，保留一份完整已发布图片及其可应用版本，
首着信息标记也跟随可见候选；新图完成后整体替换，避免等待期间棋子消失。首次尚无图片或清除后重新进入候选时，
继续绘制普通候选标记（含低访问量候选），直到完整预览发布后再切换到预览棋子及标注；不绘制未完成的变化。
离开、落子、局面/来源/模式/样式或尺寸变化立即清除结果，不跨有效表面保留旧图；取消后恢复正常候选显示。
进入数子模式同步清除主棋盘、第二路、独立棋盘及浮动棋盘的选择和结果；模式内拒绝重新选择及旧工作发布。
退出数子后仅由新请求建立预览。
同候选 PV/visits 更新只替换待办，不取消有效在途结果。显式选点/步进优先，旧任务代次永不复活。
窗口隐藏释放请求和图片，退出终止共享 worker。失败不在 EDT 回退推演。
调度器保持活动轮次至发布结束，但不持调度器锁调用宿主验证/发布，避免与 Board 清除路径反向加锁。
预览状态以每宿主短锁串行化；捕获与来源复核在锁外进行，捕获前后的代次一致才允许提交。
最终代次/选择检查、结果提交及渲染器接收同一结果处于一个临界区；清除不得穿过该发布边界，旧完成不得覆盖新步进。
临界区内不获取 Board 锁、不推演、不调用引擎、不等待 EDT；持 Board 锁的引擎路径可同步清除选择、结果及回放所有权。
三宿主手动回放及主窗口/浮动窗自动回放在 EDT 定时步进；显式步进、清除、来源替换或停止使旧回放令牌失效，旧回放不得覆盖新长度或恢复原显示长度。

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

## 跑谱贡献启动

- `ContributeEngine` 独立持有每次进程启动的诊断、两路输出完成状态、错误类别和终态。输出先进入原尝试诊断，再由后台 reader 分类错误及合法开始日志；reader 不等待 EDT，永久错误在清理前记录，即使 EDT 忙于其他操作也不重试。对局解析、棋盘采纳和 UI 在 EDT 按当前 owner 身份处理；停止、替换或永久失败后，旧输出与排队 UI 不得复活终态。
- 输出通知的状态采纳仍在原尝试的同步边界内完成，模态交互必须在生命周期 monitor 外执行；通知显示前与关闭后的停止动作均复验原 owner/launch。嵌套 EDT 循环中的永久错误、EOF、停止或重新启动不等待旧通知关闭，旧通知不得停止或覆盖后继任务。
- 错误 JSON 优先于对局解析。只有既有对局解析实际成功，或合法的 `Starting game <number>` 日志才确认就绪；通用 `EngineStartupDiagnostics` 对真实就绪尝试的 `fail` 语义保持不变。
- 明确的 `Compile with -DBUILD_DISTRIBUTED` 与 `This version of KataGo is not enabled for distributed` 分别为缺分布式支持和服务器拒绝构建，首次识别即记录失败并清理原进程，不再重试。HTTP 状态码、退出码和其他 JSON 错误本身不构成这两个分类。
- 每次用户启动最多四次进程启动。缺 CA 证书的明确证据只允许本地非完全自定义命令追加一次证书参数，并消耗同一预算；JavaSSH 不合成客户端文件系统证书路径，已有远程参数及保存命令不变。用户停止使重试和旧回调失效。
- 任一路 EOF 只启动后台尾收集；两路完成或现有诊断策略期限到达后，才决定重试。非永久失败的收尾在同一截止期限内等待已排队对局解析，保留正常输出先于 EOF 的就绪顺序；排队但未成功解析的对局不构成就绪，不能阻止永久失败或无限延长清理。最终失败展示等待原诊断的有界收集收敛，保留不完整标记；自然退出与 owner 终止保持区分。菜单、按钮与失败窗口在 EDT 一次性收敛为已停止，展示和导出继续使用既有脱敏机制。

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
7. SGF 初始行棋方遵守 setup 契约中的统一解析优先级、主线取证范围与导入后稳定性要求；新增验收场景见下节。
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

## Fox 官方 AI 实时会话

- 从同步菜单唯一入口显式启动本机 loopback 单客户端监听；端口仅接受无分组的 1–65535 整数，默认 7000，监听期间锁定。关闭窗口不隐式停止会话，停止按钮负责释放。用户确认自动落子告知后，同一实时搜索提供候选与最终选择；仅在权威己方回合、历史完整、当前时钟和 owner 有效时自动发送一次 `AFPLAY` / `AFSKIP`。
- 开启时捕获应用当前 immutable 会话规则目标；未指定目标时仅采用当前引擎已确认的完整规则观测，不读取配置默认值替代当前选择。对话框以当前界面语言只读展示规则：与 KataGo 内置预设语义完全相同时显示本地化预设名，字段相同的预设共用一个名称；否则按规则设置页用语逐项展示全部字段，无对应用语的字段或取值保留原文。不设独立规则选择器。独占 owner 写入完整 `KataGoRules` 并以匹配 ACK 和语义相同的完整 readback 确认；自定义计分、劫争、自杀、税、让子补偿等字段保留，失败禁止搜索。Fox 协议独立提供盘尺寸、贴目、设置子和时间；线程及未接管参数不改写。
- 打开直连窗口时，若应用没有显式会话规则目标且当前引擎规则尚未确认，直连通过既有单 reader 的规则查询操作异步读取当前规则；已有未完成的规则操作先等待完成，不另起写入覆盖它。无需预先打开规则设置窗口。查询不阻塞 EDT、不改规则或用配置默认值兜底；查询未完成或仍无完整规则时禁用开启，失败明确提示并允许重新打开直连窗口重试。异步结果核对当前引擎及 reader 身份，显式会话规则仍优先，真正开启时再次读取当前规则。
- `FoxGameState` 分开保存协议编号 placements 与有完整证据的 `MOVE/PASS` actions。完整初始化需要规则、状态、时钟与回合；全量状态不能补造 pass，也不能把冷接入的中盘提升为完整历史。
- 初始让子通过 `AB/HA/PL` setup 基座进入历史，不计作普通手数。实时尾节点、显示游标与带 connection/game/revision/search 身份的候选分别保存。Board 的前后、步进、分支、手数和任意节点导航以及 BoardHistoryList 的直接游标操作只改变显示；实战期间不转发历史 play/undo/restore/analyze，不改变实战搜索、时钟或 pending。
- 已采用 history 的游标边界通知唯一 `FoxLiveSession.displayCursor`；controller 追加确认动作时临时操作 liveTail，随后恢复用户选定节点。导航不写实战分析到历史缓存；既有缓存仍属于原节点。回看提示与返回按钮独立于连接/时钟状态；回看不显示实时候选数值，返回只显示当前身份候选或等待，不重启搜索。
- 主棋盘候选、普通候选表及其 PV 预览直接消费同一 fenced 实战结果，沿用普通绘制设置；直连对话框不重复候选正文。结果仅保存在 Fox display 层，不写 `BoardData`。回看、恢复观察、失效或等待新结果时，这些入口统一隐藏实时候选；旧 hover 缓存不能补回已失效候选。返回当前节点不发 GTP、不重启搜索。
- 直连对话框只读显示 Leelaz 现有独占读写通道的原始 GTP，逐行标记连接与发送/接收方向；无命令输入或第二个 stdout reader。显示缓冲及文档分别上限 65536 字符，100ms EDT 批处理，淘汰显示不消费协议响应。响应按原命令连接归属，重连清空显示而不把旧响应改归新连接，owner/reader 更换不接纳旧流。面板不启用磁盘完整日志；连接、失败、回看/返回和时钟状态独立保留，停止显示简洁停止状态。
- 停止或终局归还以实际显示节点为恢复目标，归还期间冻结导航和普通修改，直到原 reader 上完成规则、时间覆盖、运行参数与棋盘确认。不完整观察仍回到接入前稳定棋盘，不将观察信息升级为可重放历史。
- `FoxEngineSession` 通过当前 `Leelaz` 的独占 owner 使用同一 KataGo，普通快速分析取消不能释放该 owner。先失效候选再取消搜索；每次候选只属于其原始连接、棋局、修订和搜索。
- 停止及终局通过既有恢复管线重建确认的棋盘；断线保留已采用历史时暂停实战并持有 owner，直到显式归还。取得 owner 后、改变运行状态前完整捕获 rules、maxTime 与 maxVisits；归还时三者必须分别收到匹配成功响应，规则还须经既有规则查询操作确认完整 readback 并发布当前观测，且当前 reader/引擎仍一致，才可宣告交还成功；失败显示未恢复，不以最终 `name` 响应掩盖前序拒绝。
- Fox 归还只恢复操作资格：直接 Stop 与官方终局先行均在完整确认恢复后保持普通分析暂停，丢弃进入直连前的 ponder 意图，随后仅用户显式启动普通分析才继续。成功交还后通过既有菜单状态投影同步普通分析按钮文字和图标；暂停时显示开始分析，用户首次点击产生当前局面分析后显示暂停分析。其他租约使用方的恢复思考政策保持原义。
- 从取得 Fox owner 到成功归还，工具栏广度保持禁用，涵盖断线保留、历史重放和恢复；归还失败仍按普通引擎 admission 拒绝操作。成功归还按当前普通资格和广度选择状态恢复控件。原因只在直连对话框说明，不将普通分析广度映射成实战搜索参数。
- 工具栏激进度意图交给 Fox owner；取得租约只查询当前 PDA，不重置。直连窗口分别显示期望值与已确认值。兼容配置下先撤销候选和旧搜索资格，取消并完整排空旧搜索，匹配 stop 成功 ACK 后设置并回读 PDA；快速输入合并到最新有效值，旧确认不能覆盖后继意图。重新搜索读取同一回合最新 snapshot，保留原起点、提交截止及唯一时钟刷新资格。
- PDA 兼容性采用实际本机进程启动时捕获的配置与命令覆盖；显式 `dynamicPlayoutDoublingAdvantageCapPerOppLead`（包括零）会阻止静态优先级，拒绝热修改。无法可靠检查的远程、包装命令、include 或未知配置也拒绝并在窗口说明，保留确认值与安全搜索，不改动态策略。setter/readback 不代表实战使用值；实际搜索验证采用 KataGo 自身搜索参数诊断。
- PDA 启动检查复用既有 indirect-launcher 分类，先拒绝已知脚本后缀及解释器/远程包装入口，再检查直接本机命令的配置；包装器表面的静态配置不作为实际配置证据。不读取脚本内容推断其转发参数。拒绝工具栏修改时保留已确认 PDA 和原有合格搜索，不为该修改发送 setter 或取消搜索。
- PDA 是用户参数意图，不属于临时 maxTime/maxVisits；成功确认的最新 PDA 在暂停交还后继续供工具栏和普通分析使用。stop、setter、回读或响应归属失败禁止继续搜索/提交，并保留既有未恢复屏障，不能用其他恢复 ACK 把可能未确认的 PDA 当作健康引擎状态。
- 对话框独立配置每手最大秒数与每次搜索最大 visits，开启前可编辑，监听、占用和恢复期间锁定。未设置值通过当前前台 reader 的确认查询初始化，失败不伪造默认值；零沿用普通 KataGo 控件的无上限值（maxTime 1e20、maxVisits 1125899906842624）。显式值替代这两个接管参数，可高于原普通上限；线程、playouts 等未暴露参数不改写。visits 保持引擎每次搜索语义，不累计 CPU 计数。
- 每个己方回合请求一次 `AFSTATUS` 刷新时钟。`FoxGameState` 保留权威回合起点和只收紧的 Fox 时钟截止；snapshot 的 `submitDeadlineNanos` 为 Fox 安全截止与起点加直连最大用时的更紧者。初始化、参数 ACK、顺序重放、排队、同回合重新搜索全部消费此预算；刷新、重复快照和搜索重启均不能重置。引擎搜索 maxTime 以剩余预算设置并留少量交付余量；最大用时为上限而非等待下限，visits 先到可立即提交。最终结果和物理 write-start 都按同一截止拒绝过期提交。保留己方已用主时间与已消耗读秒次数，对手思考时间不扣己方主时间；日式读秒次数不作为剩余落子数。Fox 时钟默认一秒安全余量，不主动规划额外读秒段。
- `Snapshot.foxDeadlineNanos` 只表达 Fox 时钟安全截止，`time_left` 使用该剩余时间；直连上限由 maxTime 和更紧的提交截止表达，不把用户每手上限伪装为野狐剩余主时间。停止搜索、调参和重放后读取同一回合 snapshot，不创建第二个计时所有者。
- 最终选择先登记 immutable pending，再在 loopback 输出锁取得后的 write-start 边界核对连接、棋局、局面修订、搜索、截止、未消费 pending 及同一 Leelaz owner/reader。该一次性 claim 与身份撤销串行化；已开始的物理写入不能撤回。socket 写成功不表示接受，任何情况下都不重发或用旧候选、随机动作、pass 兜底。
- PDA 更新的 `cancelUnwrittenForPda` 与 `claimSubmission` 共用同步状态边界；仅在尚未消费物理 write-start 权限时将 pending 记为 CANCELLED，保留原身份、原因和结清时间。旧排队发送及迟到完成不能影响新搜索/提交。已经 claim（含 I/O 失败或结果不明）的 pending 不可取消、替换或重发；期望 PDA 等原提交按既有协议结清后才作用后续搜索。
- pending 不推进 Board 或引擎；仅匹配的 `FAMOVE` / 新增单个 matching placement 的 `FASTATUS`，或预期回合转换的 `FASKIP` 确认后追加真实动作并立即排队同步引擎。PASS 不增加 placements 编号，后继 `AFPLAY` 取 placements.size + 1。
- 确认等待五秒仍无匹配证据、拒绝、矛盾、停止、断线或终局撤销提交权。`SubmissionFact` 保留原 connection/game/revision/search、动作、准备/写入/截止/结清单调时间和 CONFIRMED/FAILED 原因；FAILED 不被后续查询、重连或迟到证据改写成可恢复请求。重连恢复必须检查此提交历史，不能仅以当前 pending == null 推断可靠检查点。
- 搜索同步/开始/结束、权威时钟与回合起点、已用时间/截止/有效搜索上限、最终选择、物理写入返回及确认/失败事实通过现有 `lizzie.engine` 持久通道记录，按 connection/game/revision/search 关联，并记录原 engine/reader。确认事件保留实际 write-start 与结清单调时间；socket 写出事件不替代确认。日志不含账号或原始 Fox 帧，不改变准入/预算。启用 `engine-gtp` Full Trace 时，Leelaz 唯一 reader/writer 对独占命令和消费的响应逐次记录，复用既有有界、脱敏及 scope 生命周期，不重复经过普通 parser；生产证据不依赖宿主 stdout 捕获。
- `FASCORE` 或引擎 resign 停止自动提交并提示用户操作官方客户端；不发送分数、自动认输或替代 PASS。时钟无效、非正预算或截止前没有有效最终选择时同样停止并提示超时风险；一秒余量不是永不超时保证，官方短时限校准仍须完成。
- Fox 取得所有权前要求 `kata-time_settings` 与 `kata-list_time_settings` 的 `none` 能力。归还时在同一 reader 上确认 `kata-time_settings none`，清除实战外部时钟后才开放普通命令；不重建或恢复另一盘对局的旧时钟。后续引擎自身时间模式不发送时间覆盖，因此不能让它继承 Fox 读秒。reset 拒绝/超时按同一归还失败屏障处理。KataGo v1.18.2 [GTP Extensions](https://github.com/lightvector/KataGo/blob/v1.18.2/docs/GTP_Extensions.md) 定义 `none` 为无外部 time control；此 disposition 不改变已捕获 rules/maxTime/maxVisits 的原值恢复。
- 断线先撤销候选、搜索和提交身份，再取消搜索。仅完整动作历史且本局所有实际提交均已确认时保留内存检查点；FAILED/PENDING 的 `SubmissionFact` 不会因当前 pending 为空而洗成可靠历史。PDA 专用 CANCELLED 仅证明原请求从未取得写入权限，不是失败恢复或重试；它不阻塞其他条件均满足的检查点。完整新局仍通过明确终局/空闲边界重新准入。
- 重连只消费新连接完整有效的规则、设置子、全量 placements、AI 颜色、FAMOVE/FASKIP 行动标记和新时钟。行动标记结合 AI 颜色确定待走方，再与完整历史校验；不按 placement 奇偶或末 tuple 颜色推断。规则/初始设置/AI 颜色兼容时，自动恢复不变 placement 对应的已确认 MOVE/PASS；也支持最后已确认 AI 动作（含 PASS）之后，对手离线新增恰好一手普通落子、旧前缀严格保留且轮到 AI 的延伸。保留原 PASS，追加唯一已报告的对方 MOVE；例如 B R16、W D4、B PASS、W Q4 为四个真实动作、三个 placement、黑方待走。
- 自动恢复只证明当前进程内保留历史在该支持边界下足够，不认证唯一棋局身份，不要求人工同局声明。冷中盘、超出一手延伸、前缀/规则/初始设置/颜色/待走方矛盾、失败或不确定旧提交、无效时钟和 reader 更换都保持观察。原 owner 逐条匹配成功 ACK 重放全部真实 MOVE/PASS，任一步拒绝或超时禁止后续搜索/提交；不以摆石代替历史或绕过引擎劫争合法性。完成后仅当前连接/棋局/修订/搜索输出可采纳，己方新搜索按新时钟及既有一次性发送门禁提交，对方只显示候选，不重发旧请求。未变化的己方回合保留断线前 turnStartedNanos 与更紧截止，初始化、离线、重放、排队和刷新均不补充每手预算。
- 已采用的可靠历史在断线、恢复拒绝或未确认提交阻塞期间仍可本地浏览，因此保留同一个不可自动中断的 Fox owner（不搜索、不提交），直到恢复、终局或 Stop。检查点不可恢复不等于旧已确认动作失去证据；UI 标记为保留历史观察，不宣称它是当前远端实战局面。
- 同一 owner 的准备成功确认在暂时不可搜索（含断线）期间仍保留就绪状态；新完整初始化恢复后复用该确认，并按当前局面资格重新调度。准备完成本身不授予搜索或提交权，停止/恢复交还及已替换 owner 的迟到回调不能重启实战。
- 终局/Stop 在同 reader 确认恢复退出时实际显示节点，包括恢复受阻后选择的历史节点；入站不完整观察不能替换此已证明历史。仅冷接入、没有已采用可靠历史的观察路径回到接入前普通棋盘。新局须经终局/空闲边界释放旧 owner，再重新初始化并取得所有权。
- 自动停止原因与动作历史完整性独立：`FASCORE` 不抹掉已确认历史；官方操作提示在查询及回看/返回实战后保持可见，且不重新授予搜索、提交或重连准入。终局与 Stop 仍确认恢复实际显示节点。
- 同一连接未观察到终局/空闲时，重复相同 `FARULE` 仅视为重复参数，不重置棋局/局面身份、动作、pending 事实、已用预算或失败屏障；矛盾参数停止自动化并保留原历史和规则。只有明确终局/空闲后的完整初始化才能建立新局，且必须等待旧 owner 的确认归还后取得新 owner；旧 FAILED 提交事实永久保留在其原身份下。

### SGF 初始行棋方修复验收

以下验收要求覆盖初始行棋方的导入、初始分支、保存重开、导入后编辑、显式切换及真实引擎恢复。聚焦回归见 `SGFParserSemanticRoundTripTest`、`BoardNodeKindHistoryPipelineTest`；私有原谱的实际窗口验收入口为 `SgfUiAcceptanceIT.originalInitialTurnsSurviveOpenNavigateSaveAndReopen`，启用条件见本节末尾。相关变更见 [PR #612](https://github.com/wimi321/lizzieyzy-next/pull/612)。本节定义验收要求，不代表每个环境都已执行通过。

| 场景 | 必须观察到的结果 |
| --- | --- |
| 用户提供的两份无 `HA/PL`、根设置子不同但白方首手明确的原谱 | 两份首手前局面均为白先；第一份保留两颗黑子，第二份保留黑白各两颗设置子；原始首手、后续手顺及分析 payload 按既有导入契约保留。原谱只用于本地验收，不纳入仓库。 |
| 用户提供的野狐对照谱：根元信息 → 独立让子 setup → 白方首手 | 空根节点黑先，摆子节点白先；保留独立 `SNAPSHOT`，摆子不消耗手数。 |
| 无 `PL` 的初始局面，主线首手分别为 `B[...]`、`W[...]`、`B[]`、`W[]` | 初始行棋方与该真实动作颜色一致；空根节点直接接白方首手时也应白先。dummy PASS 不提供首手证据。 |
| `(;HA[2]AB[dp][pd];B[dd])`；以及 `HA` 与实际设置子不匹配但后续有明确首手 | 采用主线首手颜色，不被让子默认或引擎让子资格拒绝覆盖；保留原 `HA`。 |
| 同一初始局面主线白先、旁支黑先；再互换两种颜色构造另一份输入 | 每份输入采用自己的第一分支首手颜色；浏览旁支不改变已确定的初始行棋方。 |
| `(;PL[B];AB[dp][pd];W[dd])`，以及开局过程中后续新的 `PL[W]` | 前者摆子后仍黑先，原始白棋首手照常回放；后者从新指定处采用白先，不向前改写此前局面。 |
| 主线首手之前加入纯注释、标记；或另插摆子、删子、显式 `PL` 节点 | 前者不阻断首手取证；后者阻断向更早局面的反推，各初始局面独立判断。 |
| 没有 `PL`、没有后续手顺：完整且匹配的初始让子、普通摆子、`HA` 数量不匹配、仅有 `HA` 的空盘 | 只有已证明的初始让子局面默认白先，其余默认黑先；不新增选择执子方的导入弹窗。 |
| 等价输入交换 `AB/AW/PL` 属性顺序；分别经文件、字符串、编辑态与 detached 入口导入 | 盘面与行棋方一致，setup 边界一致；detached 入口继续保持既有解析隔离。 |
| 导入后浏览分支、提升主线、删除首手；随后显式切换执子方 | 前三者保持已确定的初始行棋方，显式切换才改变它；内部编辑态重建不重新猜测。 |
| 导入 → 导航 → 引擎恢复 → 保存重开 → 再次保存 | 对应初始局面、引擎盘面与行棋方一致，输出 `PL` 保留已确定轮次；手数、真实 PASS、独立 setup 边界及重复保存稳定性不退化。 |

验收必须替换旧的“`AB[aa];W[bb]` 无 `PL` 仍黑先并保存为 `PL[B]`”断言；保留有效的引擎让子资格拒绝断言，但将其与初始行棋方断言分开。实际用户样本与合成输入均须通过生产导入路径验证，不能仅验证新的内部判断函数。

真实 CPU 验收仅在显式选择对应验收类或提供引擎参数时启用；普通 Maven 集成测试发现不要求本机预置引擎和模型。显式启用后仍严格校验完整参数、运行环境及真实引擎响应，缺失配置必须失败，不能跳过后报告验收通过。

依赖三个本地原谱文件的 Swing 验收仅在显式选择 `originalInitialTurnsSurviveOpenNavigateSaveAndReopen` 方法或提供任一 `lizzie.initialTurn.original.0/1/2` 参数时启用。普通测试发现及仅选择 `SgfUiAcceptanceIT` 整类不要求私有原谱；显式启用后，三个文件必须全部可用且显示环境必须有效，否则失败，不以跳过替代实际验收。
