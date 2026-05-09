# 弈客同步链路修复与 Debug Log 地图（2026-05-09）

## 背景

本轮集中修复的是“同步触发链路回归”，不是落子坐标微调：

- 直播房间首进不触发同步或不刷新
- 对弈房间可能触发刷新循环
- 白框出现与否和真实同步状态脱钩
- 一键同步在弈客下有时不会真正触发启动链路

## 已落地修复摘要

1. 页面分类拆分为 `live-list` / `live-room` / `unite-board` / `game-lobby`，避免等待页与棋盘页混判。
2. `BrowserFrame` 区分“监听开启”与“房间已就绪同步”，并把弈客启动入口做成幂等。
3. 路由切换时补了白框清理与几何会话归属判断，降低旧房间几何污染。
4. `OnlineDialog` 引入 active/pending 会话状态，只有 `syncReady + geometryReady` 才切活跃会话。
5. 停止 unite poller 时清理旧请求缓存，减少切房后旧 `game/info` 残留回流。
6. readboard `yikeSyncStart`、`syncPlatform yike` 触发路径统一到同一启动语义。

## Debug Log 地图（打在哪里）

日志统一通过 `featurecat.lizzie.util.YikeSyncDebugLog` 输出，主要打点区域：

- `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
  - readboard 指令接收与转发（`yikeSyncStart` / `yikeSyncStop` / `syncPlatform yike`）。
- `src/main/java/featurecat/lizzie/gui/BrowserFrame.java`
  - 地址切换、弈客启动/停止、reload 判定、geometry/unite probe 回调。
- `src/main/java/featurecat/lizzie/gui/OnlineDialog.java`
  - 同步会话切换、几何 envelope 处理、unite 拉流与 SGF 应用。
- 其他联动文件（保留原有调试点）：
  - `src/main/java/featurecat/lizzie/gui/LizzieFrame.java`
  - `src/main/java/featurecat/lizzie/analysis/Leelaz.java`
  - `src/main/java/featurecat/lizzie/gui/BoardRenderer.java`

## Debug Log 怎么打（如何开启）

### 默认行为

从本次提交开始，`YikeSyncDebugLog` 默认关闭：

- 属性：`lizzie.yike.debugLog.enabled`
- 默认值：`false`

### 临时开启

启动 JVM 时加参数：

```text
-Dlizzie.yike.debugLog.enabled=true
```

可选：指定日志路径（不指定时默认 `target/yike-sync-debug.log`）：

```text
-Dlizzie.yike.debugLog=target/yike-sync-debug.log
```

## 相关代码文件

- `src/main/java/featurecat/lizzie/util/YikeSyncDebugLog.java`
- `src/main/java/featurecat/lizzie/gui/BrowserFrame.java`
- `src/main/java/featurecat/lizzie/gui/OnlineDialog.java`
- `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
- `src/test/java/featurecat/lizzie/gui/BrowserFrameYikeSyncControlTest.java`
- `src/test/java/featurecat/lizzie/gui/YikeGeometryNormalizationTest.java`
- `src/test/java/featurecat/lizzie/gui/YikeUrlParserTest.java`
- `src/test/java/featurecat/lizzie/gui/OnlineDialogYikeSessionStateTest.java`
