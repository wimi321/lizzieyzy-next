# 同树选点评估开发指南

行为以 [Tracking Analysis Contract](TRACKING_ANALYSIS_CONTRACT.md) 为准；历史与同步语义继续服从
[Snapshot Node Kind](SNAPSHOT_NODE_KIND.md)。内部类名和仍有用途的配置键保留 `tracking`，
其含义是用户关注，不再表示独立评价进程或独占评估流。

## 模块边界

- `LizzieFrame` 捕获当前 history/node、尺寸、盘面、轮次、规则、贴目、引擎 incarnation 和参数，
  将本地或 ReadBoard 入口交给同一个 controller。
- `TrackingAnalysisController` 管理关注集合、活动集合、累计 visits 高水位和每点超时。
  `DisplaySnapshot` 是 immutable；其中没有胜率、目差、PV 或另一份候选结果。
- `Leelaz` 独占普通命令队列、物理 writer、响应边界、输出归属和 transport 生命周期。
  focus 集合变化使用普通 analyze 重发，不调用 ponder 重置预算，不清树重放。
- `BoardData` 处理整份普通分析的采纳与槽位保护；`BoardRenderer` 使用普通候选绘制本体，
  只额外叠加关注质量圈和独立进度指示。
- `ReadBoard` 管理 accepted evidence 与当前请求准入；其 listener 退休 context，不执行用户 clear。

## 准入与能力探针

能力探针由初始化完成、位置已确认的生产入口调用 `startMoveFocusProbeAfterInitialization`。
不要把探针挂到每次 `ponder()`：导航恢复和手动继续分析不是初始化，会被额外 stream 打断。
探针使用 pass、probability 0 与 rootInfo；分析启动成功及 stop/final 空行完整消费后才支持用户
focus。语法拒绝与通信不确定分别处理。每个实际 binding 只尝试一次，旧 completion 不能启动
替换引擎或改变其能力。普通分析交接完成后才开放准入，避免迟到普通命令覆盖用户 focus。

初始化探针和完成后的恢复必须尊重 `isUserAnalysisPaused()`，并与
`pauseForAnalysisControl` 共用 `analysisControlPonderLock`。探针还检查自己的暂停代次；
lifecycle 释放后的 fallback 使用 `ponderIfAnalysisControlAllows()`，不能直接 `ponder()`。
`TrackingProductionCutoverTest` 覆盖启动前暂停、成功/拒绝响应中暂停和已有界面暂停状态。

## 请求到结果

1. `addTrackingPoint` 捕获并复验当前 context；ReadBoard 请求额外通过完整 stable-frame 重验。
2. Controller 校验坐标、占用和已有 allow/avoid 限制。合法显式请求向 Leelaz 申请当前树的
   scoped adoption；拒绝不改变缓存。
3. 有仍有效的完整 live payload 时立即采纳，已达累计目标则无需发送增益。
4. 更新活动集合时先结束旧 response，然后通过原 queue 写出同一 base analyze 加 focus 集合。
   多点等权、总 probability 0.5；其他分析参数保持。
5. 合法普通 payload 同时更新 live、节点分析和 controller 进度。每个点只有自身 visits 新高
   才延长 8 秒时钟；同帧完成一次更新集合。
6. 达标结束活动计算但保留关注；remove/clear 立即隐藏关注，取消确认仍等待真实响应边界。

## 缓存与持久化

普通分析沿用保护高缓存的策略。显式合法 focus 才允许当前节点对应槽位整份接管较高旧缓存；
真实 root 下降如实显示和保存。首份前取消、拒绝、发送失败、暂停或 context 变化退休未消费
权限。首份之后同树同 root 评价可继续更新；真正清树、导航、引擎替换不继承旧输出权限。

新树采用时旧 ownership 地图退役；同树缺新地图保留有效旧地图。不要只把目标点拼进旧候选，
不要用候选访问量推断搜索树来源。SGF 使用既有 `rootVisits`、`order`、`edgeVisits` 扩展，
不保存关注/活动集合、目标或 timer。

## 取消与锁序

原 `QueuedCommand.beginOutputWrite()` 是实际写出许可；取消未写出的 focus 必须与它竞争，
不是只从 queue 列表移除。已取得许可的命令继续完成必要的 stop/fence，不能声称字节已撤回。
故障或 fence 不确定时 fail-closed，普通命令不能穿过开放 stream。

保持 `engineArbitrationLock -> commandQueue`，controller 通知在引擎 ownership locks 外。
focus 不拥有独占 lease；foreground、GMA、retained mode 和 lifecycle 保持各自原 owner。
重启实际绑定替换仍须建立现有 bootstrap receipt，不能随旧 tracking 专属分支一起删除。

## ReadBoard 与显示

FRAME_PENDING/纯 SYNCING 只关闭新请求，相同完整 accepted 语义保留关注、进度和分析流。
坏帧、停止、helper 退休、真实语义变化仍清请求。导航离开退休旧 context，返回可通过完整重验
重新准入，不要求新 frame，也不复活旧关注。

候选本体必须和普通绘制完全相同，蓝点由 order 决定。质量圈比较同份合法普通 payload 的
目标候选和 order 0 候选，复用 `MoveRankDefinition`；缺基准中性显示。关注集合不受外圈开关
或完成状态影响，低 rank/edge0 仍可见；取消关注重走普通过滤而不删除分析数据。

仅保留累计 visits 目标、外圈开关与不透明度。全语言资源变化运行 parity，不保留独立结果
内部颜色/透明度/文字配置或第二套结果渲染。

## 验证

- `TrackingProductionCutoverTest`：production add/remove/clear、普通 queue/reader/publication、
  ReadBoard frame/navigation、缓存/SGF、暂停/缺 root/逐点超时及实际 renderer 路径。
- `TrackingAnalysisControllerTest`：未知能力拒绝用户请求。
- `TrackingConfigMigrationTest`、`LocalizationResourceParityTest`：配置 cutover 和资源一致性。
- `PositionConfirmedRollbackTest` 与现有 foreground/GMA/lifecycle/SGF 套件守护共享 owner。
- 固定真实 KataGo 的保树 smoke 与实际窗口绘制独立留证；受控 transport 不等于原生验收。

先运行覆盖变更的聚焦测试，最终共享边界整合再运行全套。不要以故障注入的 stderr 判失败，
以进程退出码和 Surefire failures/errors 为准。不要全仓格式化。

正式发布能力 G3 与 #445/Windows 整合 G4 仍由后续票验收；固定实验候选的通过不能替代它们。
