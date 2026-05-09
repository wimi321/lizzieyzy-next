# yike readboard 控制信号幂等修复（2026-05-09）

## 背景

`readboard` 新增 `yikeSyncStart` 后，宿主侧存在两条启动路径：

1. `BrowserFrame.startYikeSyncFromReadBoard()`（显式 start）
2. `BrowserFrame.ensureYikeSyncFromCurrentAddress()`（平台信号兜底）

当前显式路径没有复用同房间幂等判断，可能导致同房间重复 full restart（`syncOnline -> stopSync -> applyChangeWeb`）。

## 修复目标

1. 显式 `yikeSyncStart` 路径与平台信号路径统一使用同一套幂等 gate。
2. 同房间重复信号不重启会话；新房间或未启用监听时仍可启动。
3. 不改变等待页（`#/live` / `#/game`）的 listener 语义。

## 边界

### In Scope

- `src/main/java/featurecat/lizzie/gui/BrowserFrame.java`
- `src/test/java/featurecat/lizzie/gui/BrowserFrameYikeSyncControlTest.java`
- `src/test/java/featurecat/lizzie/analysis/ReadBoardYikeSyncControlTest.java`（仅在需要时补充）

### Out of Scope

- `OnlineDialog` 的 geometry 选择/归属状态机
- `ReadBoard` 的落子、回放、diff gating 逻辑
- 页面探针脚本（`yike_grid_probe.js` / CEF 注入脚本）行为

## 约束

1. 不改变现有 route 分类规则（`live-room` / `unite-board` / waiting pages）。
2. 不增加新配置项。
3. 不把“同房间重复 start”重新解释为“强制 reload”。

## 验证要求

至少覆盖：

1. 同房间重复 `yikeSyncStart` 时，幂等 gate 返回 no-op。
2. 未启用监听但当前地址是有效房间时，允许启动。
3. 等待页信号不会错误触发 room full restart。
