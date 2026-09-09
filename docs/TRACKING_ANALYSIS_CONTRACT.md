# Tracking Analysis Contract

“评估此点”在当前前台、本地直连且已确认支持 move focus 的 KataGo 普通搜索树中追加研究。
开发入口和维护说明见 [`TRACKING_ANALYSIS_DEVELOPER_GUIDE.md`](TRACKING_ANALYSIS_DEVELOPER_GUIDE.md)。
本合同补充 [`SNAPSHOT_NODE_KIND.md`](SNAPSHOT_NODE_KIND.md)，不改变 MOVE、PASS、SNAPSHOT、
setup、dummy PASS、exact restore 或 ReadBoard history 的更高权威语义。

## 准入与能力

- `LizzieFrame.addTrackingPoint` 是本地和稳定 ReadBoard 的共同入口，同一 frame 只有一个
  `TrackingAnalysisController`，只使用当前 `Lizzie.leelaz`。
- Remote、SSH、WebSocket、双引擎、对局、GMA、Web trial、foreground 独占任务和未恢复位置
  不准入用户 focus；这些模式保留自己的既有 owner 与错误处理。
- move focus 是 analyze 参数，`list_commands` 或版本字符串不构成支持证明。
- 每个实际 engine incarnation 最多一次受控能力探针。在位置已确认、合法 idle owner 且普通
  ponder 尚未启动时发送合法 pass、probability 0 和 rootInfo 的 analyze，再结束分析。
  启动成功和 stop/final 完整响应边界均消费后才能标为 SUPPORTED。
- 语法拒绝标为 UNSUPPORTED，普通分析仍可用。通信、发送和 fence 不确定按当前 binding 的
  既有失败路径处理，不把通信故障缓存成“不支持”。旧探针不改变替换 binding 的能力或分析意图。
- 探针不打断活跃普通流；正常分析交接完成后才开放用户准入，不能由迟到的启动覆盖新 focus。
- 用户暂停时不启动探针；探针结束后的普通分析恢复与主线分析控制共用暂停锁，同时检查暂停
  代次和界面暂停状态。成功、语法拒绝及 lifecycle 延迟回调均不得绕过用户暂停。

## 关注、进度与取消

`TrackingAnalysisController` 唯一拥有用户关注点、其中尚需增益的活动点、每点累计目标、context、
进度高水位、8 秒无进展时钟及 immutable `DisplaySnapshot`。Controller 不拥有评价副本、引擎
进程、writer、queue、lease、receipt 或 handoff。

- 多点等权，总 focus probability 固定为 0.5；目标沿用每点累计 N visits。
- 进度读取裁剪前的当前合法普通 payload。旧 SGF 或其他树的候选不能证明当前树达标。
- 当前 live 已达目标可立即采纳，不重发 focus。报告中同时达标的点合并成一次集合更新。
- 达标只移出活动集合、停止该点时钟，关注保留；后续普通输出不自动重新添加增益。
- 只有该点 visits 严格超过已观察高水位才续期 8 秒；root 或其他点增长不续期。
  占用、受限、非法或无进展的点不能被标为完成。
- remove/clear 立即移除相应关注与外圈，安全撤去尚在运行的增益；不回滚已采纳普通分析。
- 达标、请求取消和引擎已确认撤去增益是不同状态。取消在途或失败时，不显示为已确认撤去。
- 暂停、时限、root 总量限制、超时、错误和 context 退休不自动重试、不恢复旧增益。
- 落子、导航离开、换谱、引擎切换/重启及语义失效清关注，返回或恢复不复活旧意图。

## 普通命令与搜索树

`Leelaz` 唯一拥有 ordinary queue/writer、numbered response、fence、timeout、reader binding、
output ownership/generation 和位置 lineage。锁序维持 `engineArbitrationLock -> commandQueue`，
controller 通知不得在引擎 ownership locks 内执行。

- 调整 focus 只重发普通 analyze，保持位置、轮次、allow/avoid、输出选项及非 focus 参数。
  不 clear、loadsgf、重放或修改允许集合；普通开始时间和总量预算不因重发重置。
- 先结束旧 GTP response，再将新输出绑定新请求；请求编号变化本身不表示清树。
- 相同集合、相同 ReadBoard 完整帧不重发。原队列内取消和 `QueuedCommand.beginOutputWrite`
  竞争同一物理写入边界，未写出的旧更新不能复活已移除点。
- 未确认旧 stream 已结束或通信已失效时，后续普通写入不能穿过该边界。
- 共享 foreground、GMA、retained mode 与 lifecycle owner 保留；focus 不再持有独占 tracking lease。

## Live、节点缓存与 SGF

- parser 和最终 publication 均复验 reader/incarnation、输出归属、位置 lineage、Board identity/
  revision、display node 和引擎槽位。focus payload 必须有精确 root，不能以候选 visits 和替代。
- 普通非 focus 分析维持既有缓存保护。合法显式 focus 为当前节点、槽位、树建立采纳权限；
  首份合法完整 payload 整槽接管，允许新 root1000 替换旧 root10000，不拼接两棵树的候选。
- 拒绝、发送失败、首份前 remove/clear/暂停/context 变化均保留旧缓存并退休未消费权限。
  仍有效的完整 live snapshot 可在请求接受时直接采纳。
- 首次接管后，同树同 root 的新评价继续刷新。只有 focus 重发延续同树关系；真正清树、
  binding/位置替换不能继承旧采纳权限，不能根据 visits 猜测来源。
- 主副槽独立。新树接管清旧树 ownership；同树后续缺失地图保留仍有效的同树地图。
- 完成、取消和同局面暂停不回滚已采纳结果；导航和引擎退休保留节点值、退休旧输出权限。
- 显示和 SGF 保存使用同一份普通节点分析，沿用 rootVisits/order/edgeVisits 的既有 payload
  格式。SGF 不保存或恢复关注点、目标、活动集合、timer 或已完成/取消的 focus 意图。

## ReadBoard

- accepted remote evidence、当前新请求准入和已有请求有效性分别判断。
- FRAME_PENDING/纯 SYNCING 关闭新请求准入，但保留属于最后 accepted 语义的 focus；相同
  完整帧不清关注、不重启分析。坏帧、停止、helper 退休和真实语义变化仍退休旧请求。
- 导航离开清关注但保留可重验的远端证据；返回时完整重验盘面、history/node、轮次、尺寸、
  rules/komi、当前 Board revision 和已确认的 engine incarnation，无新 helper frame 也可新建。
- processing epoch 隔离迟到接受；LF/CRLF 保持，非法单元整帧拒绝。Adapter 的失效 listener
  走 `contextChanged(null)`，不是用户 clear。

## 显示与配置

- 候选本体、文字、填充/透明度、字体、显示项和 order 完全走普通绘制；不存在第二份结果圆。
- 关注质量外圈使用同一普通 payload 的该候选与 order 0 候选，按 `MoveRankDefinition`
  胜率/目差损失实时配色；缺候选/基准时中性显示。质量外圈不表示仍在追加计算。
- 达标和同局面暂停保留质量外圈，活动进度结束；取消关注立即清圈。普通蓝点只服从引擎 order。
- 仍关注的点豁免数量/低比例过滤，包含已达标和外圈关闭时；取消关注恢复普通过滤，不删分析。
- `show-tracking-point-outline` 只控制外圈；重开不发 focus、不恢复已清关注。
- 保留 `tracking-analysis-max-visits` 和 `tracking-point-outline-opacity`。旧正值 visits 配置
  按既有规则迁移；独立结果填充和文字外观配置删除，不再读取或保存。
- 全部语言资源同次保持 keys、顺序、placeholder parity，说明评估会更新当前局面普通分析。

## 验证与发布边界

T1 经过 production add/remove/clear、真实 Leelaz queue/writer/response/parser/publication；
T2 经过真实 ReadBoard 帧和导航；T3 使用普通 payload、renderer 和生产 SGF parse/adopt/save。
受控 transport 只替代外部进程，不能伪造准入、缓存或 owner 校验。

真实 KataGo 保树证据、实际窗口绘制和自动化回归分别记录。固定实验候选证明不解除正式
KataGo 能力发布门禁 G3；#445 final-fence 和完整 Windows 整合门禁 G4 仍归后续验收票。
