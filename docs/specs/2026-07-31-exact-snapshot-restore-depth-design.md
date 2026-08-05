# Exact snapshot restore 完整 depth 设计（事后重建）

**原始设计日期**：2026-07-29
**重建日期**：2026-07-31
**状态**：Post-hoc reconstructed design（事后重建设计记录）

## 文档定位

这份文档不是实施前保存下来的原始设计稿。它根据原始设计会话、已批准决策、Git
提交、当前合同、实现和合同测试事后重建，用于保存模块形状、设计理由、取舍和演进过程。

权威性按以下顺序理解：

1. [`docs/SNAPSHOT_NODE_KIND.md`](../SNAPSHOT_NODE_KIND.md) 是
   `SNAPSHOT`、setup、PASS 与 exact restore 行为的权威合同。
2. 本文解释为什么采用当前 module/interface、ownership 和 failure semantics；不重新定义
   上述行为合同。
3. 当前源码与测试是实现和回归证据。若它们与权威合同冲突，必须显式报告并由维护者裁决，
   不能以本文替代合同。
4. Review handoff 只记录审查进度和待核证事项，不是设计 source of truth。

## 2026-08 exact-core rebaseline note

本文件保留 2026-07-31 的事后重建设计证据；其中关于 `LifecycleRestoreHandoff`、lifecycle root route、reservation endpoint inclusion、product-specific `prepare*` wrapper 与 ponder capture 的段落属于历史方案，不是当前实现合同。当前 exact-core 以 `.scratch/exact-snapshot-restore-core/spec.md` 与 issue 06 为准：exact module 只保留 opaque admission + immutable exact capture + one-shot execute/completion；switch/restart/PK/foreground/GMA 的 root replay、reservation、readiness、ponder 与 product choreography 留在 owner-local adapter。`原始已批准决策`、`后续演进记录`、`测试策略`章节只保留历史取舍与证据，不定义当前 surface。

## 重建证据

本设计由以下证据交叉恢复：

- 原始架构设计会话：用户选择“让 exact snapshot restore 拥有完整 depth”，随后逐项批准
  本文“原始已批准决策”中的九项决策。
- `ecba216d`：`refactor(engine): 收敛精确快照恢复架构`，实现初始 deep module。
- `aa757492`：`fix(engine): capture snapshot restore plan before commands`，补齐所有前置命令前
  capture 的合同。
- [`docs/SNAPSHOT_NODE_KIND.md`](../SNAPSHOT_NODE_KIND.md) 当前 exact restore 合同。
- [`CONTEXT.md`](../../CONTEXT.md) 中“精确快照引擎恢复”的领域定义。
- 当前 `ExactSnapshotEngineRestore`、各恢复入口和相关合同测试。
- `lizzieyzy-exact-snapshot-restore-review-handoff-2026-07-31.md` 中记录的后续 review
  结论；其中 worker 自报测试结果仍需独立 fresh 验证。

## 背景与问题

重构前，exact restore 的知识分散在 `Board`、`BoardHistoryNode`、`ReadBoard`、
`LeelazEngineCommandSink`、`Leelaz` 和一个浅层 restore helper 中。调用方需要分别知道：

- 如何从目标节点找到最近可用的静态 `SNAPSHOT` 锚点；
- 哪些后继节点是真实 `MOVE/PASS`，可以作为 tail 重放；
- 如何 materialize 临时 SGF、选择主/副引擎并安排 `loadsgf`；
- 临时 SGF 何时可以删除；
- 失败、超时、晚到响应、部分写和 engine arbitration 应如何收敛；
- ponder、komi、当前 history 和目标引擎应该在哪个时点冻结。

这使 restore helper 的 interface 很浅：调用方仍然掌握几乎全部实现知识。删除该 helper
不会消除复杂度，只会让同一复杂度继续留在多个 caller。目标是把这些知识集中到一个
拥有完整 depth 的 module，让 caller 通过小 interface 获得恢复能力和一致语义。

## 领域术语

**精确快照引擎恢复（exact snapshot restore）**：从目标之前最近可用的静态
`SNAPSHOT` 锚点恢复引擎盘面，在该锚点成功生效后，只续接锚点后的真实
`MOVE/PASS`。所有 captured target 达到模块完成边界前，恢复不算完成。

**prepared restore**：`prepare(...)` 返回的句柄，封装一次已经冻结的 immutable
restore plan。后续前置命令和 callback 不能改变该 plan 的目标、盘面、tail、komi、mirror、
ponder disposition 或 admission identity。

**未进入 exact restore**：目标祖先链没有可用静态锚点，或者只有新棋局默认的空 root
`SNAPSHOT`。此时 caller 保留原有 root replay；这不是 exact restore 的失败 fallback。

**exact restore 失败**：已经捕获并开始执行 exact restore 后，admission、`loadsgf`、tail
提交或底层协议生命周期失败。该状态禁止降级为 root replay。

## 目标

- 让 `ExactSnapshotEngineRestore` 成为 exact restore 的唯一编排 owner。
- 通过 `prepare(...) -> execute()` 的小 interface 隐藏 anchor、tail、mirror、SGF、cleanup
  和 completion sequencing。
- 在任何可能改变恢复输入或目标的前置动作之前冻结 immutable plan。
- 让所有恢复入口共享同一套 fail-closed 语义。
- 保持 `Leelaz` 对 ordinary GTP queue、response、timeout、output stream 和 engine
  arbitration 的唯一 ownership。
- 让 caller 和合同测试都通过同一个 module seam 使用恢复能力，提升 leverage 与 locality。

## 非目标

- 不改变 `MOVE`、真实 `PASS`、dummy PASS、`SNAPSHOT` 或 setup 的历史语义。
- 不新增第二条 GTP queue、通用 transaction、rollback、retry 或新的长期 ownership
  resource。
- 不把 `Leelaz` 的 response handler、timeout、outstanding retirement 或 output-stream
  invalidation 搬入 restore module。
- 不在 exact restore 失败后猜测性 root replay，也不把失败静默改写为成功。
- 不由 restore module 设置通用 `ENGINE_STATE_UNRESTORED`。
- 不让 restore module拥有或启动 ponder；它只冻结 disposition 并返回完成结果。
- 不把 ReadBoard GMA 的 final-play epoch、外部点击授权或同步状态机耦合进 restore module。
- 不为测试替身、任意 subclass 或未来可能性增加通用 adapter/DI 抽象。
- 不改变 Web trial、tracking、PK 或 ReadBoard 的产品语义；本设计只统一它们需要使用的
  exact restore handoff。

## Module seam 与 ownership

### `ExactSnapshotEngineRestore`

当前 exact module 只拥有 exact restore 的闭环：

- 从 history target 找到最近可用的静态 `SNAPSHOT` 锚点；
- 将 removed-stone 或显式 current position materialize 为静态 snapshot；
- 冻结 stones、side-to-play、盘尺寸、setup metadata、komi 与真实 `MOVE/PASS` tail；
- 冻结 opaque admission、authority target 与可用 mirror；
- 生成临时 SGF，编排所有 captured target 的 `loadsgf -> tail`，并负责 cleanup；
- 在所有 target 达到 completion boundary 后返回 `Completion`，失败保持 fail-closed。

Exact module 不拥有 root replay、reservation、restart、engine start/readiness、ponder、foreground
lease 或 ReadBoard GMA 状态机；也不向 caller 暴露 target、mirror、komi、tail、SGF、root payload
或 protocol handler。

### `Leelaz`

`Leelaz` 继续唯一拥有：

- ordinary command queue 与实际发送；
- response handler 绑定、GTP response 解析与 timeout；
- outstanding response retirement、late-response isolation 与发送窗口推进；
- output-stream cleanup/invalidation；
- engine arbitration，以及创建和校验 opaque exact admission 的窄协议 seam。

`ExactSnapshotEngineRestore` 只能通过这些窄 seam 使用底层能力，不能复制第二条 queue、response
stack 或通用 transaction。

### Caller / lifecycle owner

Caller 只负责：

- 选择 history target 或显式 current position；
- 在自己的 owner 语境中捕获合法 opaque admission，并调用 exact capture；
- 在 plan 冻结后执行自己的 `stop`、`name`、`komi`、`clear_board`、engine start/switch、root
  replay 或 GMA completion choreography；
- 调用 `PreparedRestore.execute()`，处理 `Completion` 或原始失败；
- 由自身既有策略决定是否恢复 ponder、释放 reservation 或收敛 availability。

Caller 不能提供或拼装 exact tail，不能重新选择 mirror，不能持有 exact 临时 SGF lifecycle，也不能
直接调用 exact cleanup/dispatch callback。Lifecycle、foreground 与 GMA 的 product identity 只在
各自 adapter 中映射为 opaque admission，不进入 exact core interface。

## 小 interface

Exact module 的 frozen interface 只有两个 capture route 和一个 one-shot completion：

```text
prepare(historyTarget, opaqueAdmission) -> Optional<PreparedRestore>
prepare(explicitCurrentPosition, opaqueAdmission) -> PreparedRestore

PreparedRestore.execute() -> Completion
```

- history target 没有可用静态锚点时返回 empty，表示未进入 exact restore；root replay 仍由 caller
  按既有产品语义处理。Exact restore 开始后的失败不允许 root fallback。
- explicit current position 必须包含可物化的有效静态局面；无效输入在任何文件或 GTP 副作用前显式失败。
- module 内部负责 clone/materialize；caller 不调用公共 snapshot conversion helper。
- `PreparedRestore` 隐藏 immutable plan，不暴露 target、mirror、komi、tail、SGF、root payload、
  dispatch 或 cleanup callback。
- `PreparedRestore` 是 one-shot；第二次 `execute()` 在任何文件或引擎副作用前显式失败。
- capture 时冻结的 preclear policy 在 `execute()` 内部执行；不暴露第二种 execute 或 precommand seam。
- 成功返回 `Completion`；失败传播原始异常/失败原因，没有 retry 或 fallback 结果。

## Immutable restore plan

`prepare(...)` 一次性冻结：

- 最近可用静态锚点及其 snapshot clone；
- stones、side-to-play、盘尺寸、setup properties/metadata、手数与 captures 等恢复所需状态；
- 当前棋局 komi，而不是稍后可能变化的目标引擎默认/cache komi；
- 锚点到 target 之间的真实 `MOVE/PASS` tail；
- module 校验并冻结的 authority engine、captured mirror 和最终 target 集合；
- owner admission 及其 execution-time validity；
- 由 admission 冻结的内部 preclear policy。

后续 callback 不得重新读取以下 mutable state 来修改同一个 plan：

- `Lizzie.board` 或当前/display history node；
- `Lizzie.leelaz`、`Lizzie.leelaz2` 或双引擎配置；
- engine cache 中可能被前置命令改写的 komi；
- 已被替换的新 target、mirror 或 owner admission。

## Capture handoff

恢复入口必须在可能改变恢复输入、目标或 ownership 的第一个外部动作前完成 exact capture：

```text
resolve history target or current position
    -> capture opaque admission
    -> prepare immutable exact plan
    -> caller-owned stop / name / komi / clear / start / switch choreography
    -> execute captured exact plan
    -> caller applies completion and product disposition
```

Exact module 不冻结或执行 caller 的 lifecycle route。Caller 可以在自己的 owner-local 状态中保留
root replay、reservation、readiness、availability、restart fence 与 ponder，但不能在 exact plan
捕获后重新选择 target 或 mirror。

## 执行顺序

1. `execute()` 使用 plan 中的 snapshot 生成临时 SGF。
2. 在发送前复验 captured admission 与 target identity。
3. 通过 `Leelaz` 的既有 queue/response seam 向所有 captured target dispatch `loadsgf`。
4. 等待每个已 dispatch 的 `loadsgf` 成功消费，或收敛为明确失败。
5. 只有所有 target 的 snapshot 都成功后，才向所有 captured target 提交真实 tail。
6. 模块完成边界是所有 tail command 已被对应 `Leelaz` ordinary queue 接受；tail 的逐命令 GTP
   response 继续由 `Leelaz` 管理。
7. 临时 SGF lifecycle 覆盖所有已 dispatch target 的消费、retirement 与 tail 提交；达到完成或
   失败 cleanup 边界后才删除。
8. 成功时返回 `Completion`；caller 再按自身冻结的 disposition 执行后续策略。

## Failure semantics

Exact restore 一旦开始即 fail-closed：

| 场景 | 结果 |
|---|---|
| 没有可用静态锚点 | 未进入 exact restore；caller 保留原有 root replay |
| Capture 时 owner/admission 冲突 | 零 restore 命令；显式失败 |
| Execute 前 admission 已失效 | `loadsgf` 前失败并清理已生成的临时 SGF |
| Captured-target preclear 被 arbitration 拒绝 | `loadsgf` 前显式失败；不重选 execution-time mirror |
| `loadsgf` enqueue/send/write/flush 失败 | 不发 tail；退休对应协议状态并清理；传播原始失败 |
| GTP `?` 或无响应超时 | 不发 tail；隔离晚到响应并清理；传播原始失败 |
| 部分写导致 output stream 污染 | 不发 tail；由 `Leelaz` 失效该 stream；传播原始失败 |
| 一侧已 dispatch、另一侧失败 | 已发出侧完成消费或 retirement 后再清理；所有 target 都不发 tail |
| Tail 被 engine arbitration 拒绝 | 显式失败；不 fallback 到 root replay |

Restore module 不把这些失败改写为 `ENGINE_STATE_UNRESTORED`，也不自行决定 UI、engine
replacement、reservation 或 retry 策略。具体 caller 按各自既有产品语义处理原始失败。

## Mirror 语义

- Capture 时由 restore module 校验并一次性冻结 authority、mirror 与最终 target 集合；执行期间不
  重新读取全局 engine 字段或新增 mirror。
- 从主引擎或副引擎入口发起时，只要 admission 合法，双方使用同一 snapshot、tail 与临时 SGF
  lifecycle。
- 第三实例或临时 engine 不属于 captured pairing，只恢复自身。
- 任一 captured target 的 `loadsgf` 失败时，所有 target 都不提交 tail。
- 任一侧已经 dispatch 后，另一侧发送失败或任一侧返回 `?`，其余已发出侧仍必须完成消费、timeout
  retirement 或 fallback cleanup；失败不能提前删除临时 SGF。
- Mirror 不获得新的 queue 或 ownership；两侧仍各自通过自己的 `Leelaz` 协议生命周期完成请求。

## Owner 与 admission

Exact core 只消费 opaque admission，不解释 product identity。Ordinary history/current-position、
Board sync、foreground 与 ReadBoard GMA adapter 在自己的 owner 语境中捕获 admission，并将同一
admission 交给 exact capture 与 execute。

Lifecycle reservation、restart fence、root replay、ponder、foreground lease、GMA terminal/binding/
retirement/reservation timing 均留在 owner-local choreography。本分支不迁入 ReadBoard GMA
combined barrier，也不建立通用 lifecycle module。

## 恢复入口覆盖

Exact contract 只覆盖 module seam；其余入口作为 affected no-regression fixtures：

| 入口类别 | 本分支要求 | 主要合同测试 |
|---|---|---|
| 普通 resync、导航、removed-stone、clear/restore | 通过 history capture、current-position capture 与 one-shot execute 获得 exact 盘面 | `ExactSnapshotEngineRestoreContractTest`、`BoardMovelistExportTest`、history tests |
| 前台/副引擎切换与配置更新 | owner 在副作用前冻结 target/admission；exact 不接管 reservation/readiness | `EngineManagerLifecycleReservationTest` |
| 自动/直接 restart、PK、OpenCL、benchmark | owner-local route 保持 fixed-point 行为；exact diff 不得引入 regression | lifecycle/recovery fixtures |
| Foreground lease 与 ReadBoard GMA | adapter 只调用 generic exact capture；terminal/binding/retirement/timing 继续由 owner 持有 | `LeelazExclusiveRemoteGtpSessionTest`、`LeelazReadBoardGmaTest`、`ReadBoardEngineResumeTest` |

Board-size mismatch 表示创建新棋盘，不是把旧尺寸 snapshot exact restore 到新尺寸 engine。

## 原始已批准决策

原始会话逐项确认了以下决策：

1. `ExactSnapshotEngineRestore` 拥有 anchor、`loadsgf`、真实 tail、mirror 和临时 SGF
   cleanup；`Leelaz` 保留 queue、response 和 arbitration。
2. Module 统一拥有 sequencing，caller 不再调用 `finishTailReplay()` 或拼装 lifecycle。
3. `loadsgf` 发送失败、`?`、超时或部分写统一 fail-closed，不发 tail、不 root replay。
4. 入口立即冻结 immutable plan，callback 不重读 mutable globals/history。
5. Mirror 在 capture 时固定；第三实例只恢复自身；任一侧失败时所有目标不发 tail。
6. Plan 冻结 ponder disposition；restore module 不拥有 ponder。
7. Interface 只接收恢复目标和 engine context，不接收 caller 拼装的 tail。
8. Completion 不暴露 dispatch、response binding、timeout retirement 或 cleanup callback。
9. 先通过新 module interface 锁定失败不发 tail、双引擎 mirror、第三实例和 tail-only 四类
   合同测试，再实施迁移。

用户随后明确确认 shared understanding，可以开始实现。

## 后续演进记录

### 完整 capture handoff

初始实现后发现部分入口先发送 `stop/name/komi/clear_board`，再进入 restore module。这会允许
前置命令改变 history、komi、ponder 或 engine identity，违反 immutable plan 的本意。

`aa757492` 将 interface 明确拆为 `prepare(...) -> execute()`，并把 capture 移到这些命令及
可产生等价外部效果的 lifecycle 操作之前。后续未提交 repair 又继续覆盖 engine update/switch、
automatic restart、OpenCL、benchmark、PK 与 foreground lease 等入口。

### Restore admission

完整入口覆盖引出了不同 owner 共享同一 GTP stream 时的 admission 问题。解决方案是在 plan
中捕获窄 owner/admission，而不是新增 generic transaction 或第二套 queue。

### ReadBoard GMA reservation identity

最终 review 发现仅记录“存在某个 GMA reservation”不足以防止 A 退休、B 建立后的 ABA。
后续窄修固定具体 reservation identity，并增加 stale plan 在 dispatch 前失败的合同测试。

这些演进补强原始 immutable handoff，不改变 module 与 `Leelaz` 的 ownership 分工。

### Opaque lifecycle restore handoff

后续完整复审确认，`LifecycleTargetPair`、caller 创建的 raw owner、
`mirrorLifecycleOwnedByOperation` primitive 与 caller 独立推导的 reservation target 仍把同一个
ownership 决策拆在 module seam 两侧，并曾直接引出 mirror self-conflict。窄修将这些事实收敛为
一个 `LifecycleRestoreHandoff`：module 校验/freeze pairing 并内部推导 mirror ownership，
`Leelaz` 只允许 handoff 在其冻结的 lifecycle endpoint 上申请既有 reservation。Handoff 还在
`prepare(...)` 返回 empty 时冻结 root-replay route/admission，避免副作用后 generic re-prepare；
exact 与 root 都使用同一 target/mirror/identity 完成本次 board synchronization。Handoff 本身不
新增 reservation/state machine，也不接管 start/stop、root payload、reservation lifetime 或 ponder
等 caller 产品语义。

### Captured precommands 与 restart fence

最终耦合复审又发现两个会绕过 immutable handoff 的路径：Board prepared restore 用普通 `sendCommand("clear_board")` 在执行时重新解析全局 secondary；automatic/direct restart 的 exact 分支在 `loadsgf` 后提前 return，跳过 board fence，并按 plan 过早恢复 ponder。修复后，Board preclear 由 `PreparedRestore` 发送到 captured target set；lifecycle root 的每条命令通过 active captured admission 显式 enqueue。Restart plan 不自行 resume，exact/root 都恢复 frozen target 并等待同一 fence 后才完成 reservation 与 ponder 策略。

## 被拒绝的方案

- **只统一 `Board` 与 `LeelazEngineCommandSink`**：风险较小，但 ReadBoard/GMA 等 caller 仍会
  保留第二套 exact restore 语义，无法获得 locality。
- **Caller 提供 tail 或 cleanup callback**：把 anchor/tail/lifecycle 知识重新泄漏到 interface，
  module 仍然浅。
- **Restore module 接管 GTP queue/response/arbitration**：复制 `Leelaz` 的底层协议 owner，
  形成第二套状态机和新的交错风险。
- **Callback 动态读取当前 history 或全局 engine**：执行期间导航、同步或 engine replacement
  会把一次 restore 拼成多个时点的状态。
- **Exact 失败后 root replay**：掩盖真实恢复失败，并可能把不可证明的静态局面拆成错误手顺。
- **通用 transaction、rollback 或 retry**：超出当前问题；增加长期 owner 和状态，而不是深化
  现有 module。
- **把 GMA epoch/final-play authorization 放进 restore module**：混合两个领域状态机，降低
  depth 与 locality。
- **为了 sequencing 新建公共异步 interface**：既有 `Leelaz` dispatch 已具备等待和完成机制，
  只需移动 ownership。

## 测试策略

测试通过 module interface 和真实 caller handoff 锁定以下不变量：

- 最近静态锚点恢复后只重放真实 `MOVE/PASS`；
- 默认空 root 不误进入 exact restore；
- prepare 后修改 history、komi、ponder 或全局 engine 不改变已捕获 plan；
- 所有前置命令与 lifecycle 入口都在 capture 之后；
- 主/副入口 mirror 对称，第三实例只恢复自身；
- enqueue/send/write/flush、GTP `?`、timeout、晚到响应和 stream pollution 均 fail-closed；
- tail 被 arbitration 拒绝时显式失败；
- 临时 SGF 生命周期覆盖所有已 dispatch target；
- foreground retry 使用新的 attempt/plan，不修改旧 plan；
- stale ReadBoard GMA reservation identity 在 `loadsgf` 前失败且完成 cleanup。

主要证据文件：

- `src/test/java/featurecat/lizzie/analysis/ExactSnapshotEngineRestoreContractTest.java`
- `src/test/java/featurecat/lizzie/analysis/EngineManagerLifecycleReservationTest.java`
- `src/test/java/featurecat/lizzie/analysis/LeelazExclusiveRemoteGtpSessionTest.java`
- `src/test/java/featurecat/lizzie/analysis/LeelazOpenClRecoveryTest.java`
- `src/test/java/featurecat/lizzie/analysis/LeelazReadBoardGmaTest.java`
- `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`
- `src/test/java/featurecat/lizzie/util/KataGoRuntimeHelperBenchmarkLeaseTest.java`

本文只描述测试意图，不把历史或 worker 自报数字当作当前 fresh 验证。最终完成声明仍必须按
review handoff 重新运行定向与全量验证。

## 相关合同

- [`SNAPSHOT_NODE_KIND.md`](../SNAPSHOT_NODE_KIND.md)
- [`Web 试下模式 - 引擎跟随分析`](2026-04-30-web-trial-engine-follow-design.md)
- [`ReadBoard 引擎决策自动落子`](2026-06-24-readboard-gma-engine-decision-design.md)

## 重建时的 review 状态

设计层面没有遗留待确认决策。本文重建时，handoff 中尚未完成的是实现复审、异步测试 NPE
的归因、fresh 定向/全量验证以及 Windows GUI/真实 engine 验收。这些属于 review 与
verification，不通过滚动修改架构合同记录完成状态。
