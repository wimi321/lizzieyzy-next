# ReadBoard 引擎决策落子（`gma`）设计（2026-06-24）

## 状态

已与用户确认，待实施。

## 背景与目标

ReadBoard 当前的自动落子会持续运行 `kata-analyze`，并在条件满足时从当前候选第一手落子。这不等同于 KataGo 自己完成一次 `kata-genmove_analyze` 后所选择的最终着法。

本设计在 ReadBoard 发起的自动落子链路中新增“引擎决策落子”模式：由 KataGo 持续输出分析信息、达到搜索限制后自行给出最终 `play`。候选、胜率、PV 和 ownership 仍实时更新。

本功能遵守 `docs/SNAPSHOT_NODE_KIND.md` 的同步与历史契约；不改变棋盘快照、`SNAPSHOT` 节点或远端盘面恢复语义。

## 范围

- 仅处理 ReadBoard 的 `play>` 自动落子链路，不改变 Lizzie 通用人机对弈、引擎对弈或手动分析路径。
- 支持两种落子方式：既有“一选落子”和新增“引擎决策落子”。
- 新模式只使用 `kata-genmove_analyze`，不会增加“持续显示分析（`kata-analyze`）”选项。

## 兼容协议

ReadBoard 在原有第三段数值末尾增加可选 token：

```text
play>black>5 1000 0 gma
```

- `gma` 表示通过 `kata-genmove_analyze` 由引擎最终决策落子。
- 缺少 token 的旧消息仍严格解释为“一选落子”。
- 旧 Host 只读取前三个数值并自然忽略末尾 token，因此保持兼容。
- 此扩展明确覆盖旧的“本次功能不新增 wire protocol”范围；只为本功能增加这一个可选 token，不改变其他既有命令文字或时序。

## 能力判定

能力结果属于一个 `Leelaz` 引擎实例，而不是每一手棋：

1. 复用引擎启动时已有的 `name`、`version`、`list_commands` 结果，不额外发送探测命令。
2. 在引擎实例中缓存三态：`未知`、`支持`、`不支持`。支持条件是名称已识别为 KataGo、`list_commands` 已完成且同时含 `kata-genmove_analyze`、`kata-get-param`、`kata-set-param`；三者共同保证最终决策和 ReadBoard 后台思考开关可用。
3. 每次启动一个引擎进程前，必须清空上一个进程的 command list、GMA 三态和“已提示不支持”标记；同一 `Leelaz` 对象重启时也不得继承旧进程结果。
4. 只有双向同步、自动落子、轮到配置棋色且即将发起新一手时才读取缓存。
5. `未知` 时静默等待现有启动握手完成，完成后自动继续；不显示“正在检测”，也不要求用户操作。
6. `不支持` 时本手不自动落子，对同一引擎会话只显示一次“该模式仅支持 KataGo”。切换或重启引擎才使缓存和提示资格失效。

## 命令与参数

轮到引擎棋色且能力为支持时，Host 构造 `kata-genmove_analyze`。分析输出沿用现有 KataGo 解析路径更新 UI；最终 `play` 才触发实际落子。

- `time > 0` 映射为 `maxTime`。
- `playouts > 0` 映射为 `maxVisits`，以保持现有“最大计算量”依据 `visits` 的语义。
- 空白和 `0` 均不传对应限制；两个都为空或 `0` 时完全交给 KataGo 配置中的 `maxTime` / `maxVisits`。
- `firstPolicy` 在 `gma` 模式不参与命令构造。
- 既有“一选落子”模式的时间回退、首选计算量和自动落子判定保持不变。

`gma` 不得从 toolbar 的显示值反推限制：现有一选路径会把 `time=0` 显示为游戏默认时间。Host 必须单独保存 `gma` 的原始 time/playouts（`0` 或正数）并仅从这份状态构造下一手命令。既有 `timechanged` / `playoutschanged` 在 `gma` 模式也更新这份原始状态，但不改变已经发出的当前手搜索；模式切换则由完整 `play>` 原子更新。

## 统一的 GMA 调度入口

`gma` 激活后，ReadBoard 的自动落子不再通过现有 `ponder()`、`notifyAutoPlay()` 或通用人机对弈调度来驱动。Host 应有一个受同步状态保护的单一入口，用于在稳定局面上判断下一步：

1. 对手回合：不发送 `kata-analyze`；仅在本规格的后台思考条件成立时保留 KataGo 原生 ponder。
2. 我方回合且没有待决请求：按能力缓存和原始限制发起一次 `kata-genmove_analyze`。
3. 我方回合且已有待决请求：不重复发命令。

收到 `play>`、`sync`、快照重建后的延迟恢复、失败落子恢复及其他原本会调用 `ponder()` / `togglePonder()` 的 ReadBoard 入口，都必须在 `gma` 模式改走这个入口或成为 no-op；整局 `gma` 命令记录不得出现 `kata-analyze`。

在 GMA 待决期间，KataGo 的每条 `info` 仍更新候选、胜率、PV 和 ownership，但必须绕开 `notifyAutoPlay()`：即使旧 time/playouts/firstPolicy 阈值已满足，也不得调用 `Board.place(...)` 或发送外部 `place`。只有对应最终 `play` 可以实际落子。

## GTP 队列冻结与引擎收敛

`kata-genmove_analyze` 不能异步取消。若它的逻辑授权失效但物理请求仍在飞行，除了禁止第二个 GMA 外，还必须冻结所有会改变引擎局面的 ReadBoard GTP 输出，例如普通 `play`、`clear_board`、`loadsgf`、重建和恢复命令。有效坐标的外部点击失败、以及最终 `pass` / `resign` 未被对外执行时，也进入同一“引擎恢复隔离”状态：虽然物理请求已结束，KataGo 内部局面仍可能领先于权威盘面。

- 本地棋盘、历史和来自 ReadBoard 的权威快照照常更新；冻结期只记录最新应恢复的权威局面，不把增量 GTP 命令排在旧 GMA 后面。
- 旧 GMA 的最终 `play` 或错误终态到达后，消费其输出但不落子；对于外部点击失败、`pass` 或 `resign` 则在得知该结果后立即开始隔离；两种情况均随后以最新权威快照执行唯一一次 exact engine restore。
- 仅当 restore 完成后才解除引擎命令冻结并允许后续普通同步命令或下一次 GMA。

这条规则同时适用于 `stopAutoPlay` 后对手立即走棋的场景，避免 KataGo 先在过期根局面落子、再消费一条错误根局面的普通 `play`。

## 后台思考

ReadBoard 已有、已持久化的“后台思考”设置是本模式的唯一开关。它通过既有 `playponder on/off` 协议更新 Host 的 `readBoardPonder` 状态；在 GMA 会话中，它也拥有 `ponderingEnabled` 的运行时值。

- 首次接管一个 GMA 会话时，先用官方 `kata-get-param ponderingEnabled` 读取并保存此引擎原始运行时值。该值只用于离开 GMA 会话时恢复；不写 KataGo 配置文件。
- **每一次** GMA 命令之前，按顺序发送 `kata-set-param ponderingEnabled <readBoardPonder>`（`true` 或 `false`）并确认完成；随后才发送 `kata-genmove_analyze`。因此即使 KataGo 原配置为 `true`，ReadBoard 未勾选后台思考时也不会在该手后遗留原生 ponder。
- `readBoardPonder=true` 时，最终落子后不发送 `kata-analyze`、不调用会主动启动分析流的通用 `ponder()` 路径、也不主动停止，让 KataGo 原生 ponder 复用搜索。
- `readBoardPonder=false` 时，最终落子后停止任何原生 ponder 并保持 `ponderingEnabled=false`，直到下一次发起 GMA 前再次按当前设置覆盖。
- 在一次 `kata-genmove_analyze` 计算期间切换“后台思考”为关闭，不取消本手最终落子；记下关闭意图，并在最终回应后停止原生 ponder、设为 `false`。ReadBoard 的显式 `noponder`/停止自动落子则使当前待决结果失效。
- 离开 GMA 模式、同步会话结束或引擎更换时，若没有 GTP 请求在飞行，则停止 GMA 会话启动的原生 ponder 并恢复首次读取的原始 `ponderingEnabled`；若仍在飞行，恢复动作延后到该最终回应或错误终态已被消费后执行。

## 最终着法与同步安全

Host 为每个 GMA 维护两个独立状态：逻辑落子授权和物理 GTP 请求。发起时保存单调递增的同步 epoch、ReadBoard 会话身份、目标历史节点身份、棋色、落子方式、自动落子开关和 `bothSync` 状态。最终结果只有逻辑授权仍匹配时才可接受。

- `stopAutoPlay`、显式 `noponder`、`noboth`、`start`、`clear`、`stopsync`、`endsync`、同步重建、模式/棋色改变或 ReadBoard 会话改变都会递增 epoch 或使逻辑授权失效。
- 逻辑失效不等于 KataGo 已停止：`kata-genmove_analyze` 的物理请求仍保持“在飞行”直到最终 `play` 或错误终态被消费。期间不得发起第二个 GMA，即使新局面又轮到我方。
- 对失效最终结果、GTP 错误或没有有效坐标着法，只清理物理请求，不重试，也不对外点击；先用最新权威快照强制使引擎重新收敛，再允许下一次 GMA。`pass` / `resign` 首版不对 ReadBoard 伪造点击，且下一帧必须强制执行一次仅恢复引擎的 exact snapshot restore，即使石子比较结果为 `NO_CHANGE`；这同样在恢复完成前禁止下一次 GMA。
- 接受有效最终着法时，使用仅供 GMA 的完成分支：临时抑制本地 `Board.place` 再发 GTP `play`，保留一次 ReadBoard 外部落子请求，但不得进入通用 `isInputCommand` 后处理，也不得调用 `ponder()`、`nameCmdfornoponder()` 或额外 `stop`。
- 外部点击失败时，等待远端权威快照使引擎重新收敛；不得退回普通 `genmove()`、`kata-analyze` 或再次点击。
- 对手同步来的落子仍是普通 `play`；下一次轮到引擎时再开始新的 `kata-genmove_analyze`。

## 非目标

- 不在对手回合启动 `kata-analyze` 来刷新候选表。
- 不把当前 `kata-analyze` 已积累的 visits 当成下一手 `kata-genmove_analyze` 可继承的搜索。
- 不改变 ReadBoard 快照、同步历史、PASS 或 `SNAPSHOT` 处理。
- 不增加通用 genmove 模式、可取消搜索模式或新的后台思考选项。
- 不支持双引擎/引擎 PK 会话中的 `gma` 自动落子：这类会话选择 `gma` 时阻止自动落子，不向默认会镜像 `sendCommand` 的第二引擎发送 GMA 或参数命令。一选落子和既有双引擎行为不变。

## 功能完成后的使用说明

本规格不在实现前创建面向使用者的说明文档。全部代码、测试和联调完成后，再依据实际行为分别在两个维护仓库的 `docs/` 下新增说明；README 是否链接到该说明留待后续决定。

说明以简洁文字和一张对照表为主，解释“一选落子”从 `kata-analyze` 当前第一候选落子，而“引擎决策落子”只接受 `kata-genmove_analyze` 最终着法；还要明确 GMA 本手 UI 实时分析、对手回合不发 `kata-analyze`、后台思考控制原生 ponder、两种模式的 `time` / `playouts` / `firstPolicy` 与 `0`/空白语义差异、KataGo 三项 GTP 能力要求、`gma` 的旧协议兼容、双引擎/PK 限制和“持续对手回合分析”不在本轮范围。不得复制 epoch、队列隔离或测试设计等实现细节。

## 验收

1. 旧三数值 `play>` 保持一选自动落子行为。
2. `gma` 且 KataGo 支持时，多个达到旧 time/playouts/firstPolicy 阈值的 `info` 只刷新 UI，产生零次本地或外部落子；最终 `play` 恰好同步一次引擎选择的着法。
3. `time` / `playouts` 的原始 `0` 与正数正确映射或省略，`firstPolicy` 不影响 `gma` 最终选择。
4. 整个 GMA 会话的命令记录不出现 `kata-analyze`；最终处理无重复 GTP `play`、无通用后处理产生的额外 `stop`。
5. 非 KataGo、缺少任一所需 GTP 扩展或双引擎/PK 会话不会自动点击，并且每个引擎会话至多提示一次；支持引擎重启为不支持引擎后不会复用旧 command list 缓存。
6. 每次 GMA 前都先读取/设置并确认 `ponderingEnabled=<readBoardPonder>`；退出 GMA 会话后恢复原始值，且不会启动 `kata-analyze`。
7. 所有列出的 epoch 失效事件、外部点击失败与 `pass` / `resign` 都不会点击过期或错误局面；`pass` / `resign` 即使远端石子和手数未变化也会强制仅恢复引擎。上述任一恢复隔离发生后，对手立即落子的普通同步命令也必须等到 restore 完成才可发送。
8. 失效后又很快轮到我方时，旧 GTP 请求仍在飞行，第二个 GMA 不会重叠发送；普通 `play` / 重建 GTP 也被冻结，旧终态消费与 exact engine restore 后才允许下一次。
9. 相关新增单测通过，随后运行 Maven 全量测试与真实 KataGo 双向同步验证。
