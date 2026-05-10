# 弈客同步会话状态机设计

日期：2026-05-08

## 概述

当前弈客集成的主要问题不是落子坐标精度，而是同步触发、页面切换、几何归属这三类状态没有被明确定义。

现状里至少混杂了这些语义：

- “用户是否开启 readboard 弈客同步”
- “当前 URL 是否是可同步房间”
- “当前房间的同步源是否已经 ready”
- “当前房间的棋盘几何是否已经 ready”
- “旧房间状态是否还应继续生效”

这些语义分散在 `BrowserFrame`、`OnlineDialog`、浏览器 probe 和 unite poller 里，导致出现以下已知问题：

1. `#/unite/:id` 下可能重复触发 `syncOnline`，导致浏览器页面持续刷新。
2. 第一次进入 `#/live/new-room/...` 时可能既不显示白框，也不同步棋盘。
3. 必须先去 `#/unite/:id` 触发一次后，回到直播房间才可能激活同步。
4. 回到直播房间后白框尺寸可能错误，说明旧房间 geometry 污染了新房间。
5. 某些页面已经能拿到几何，但 `boardSize` 未 resolve，导致“白框有了但不同步”。

本设计的目标是把这些状态整理成一套可验证的运行时状态机，再按这套状态机改代码。

## 目标

1. 弈客同步开关表示“持续监听弈客浏览器”，而不是“绑定当前房间立刻重建同步”。
2. 在 `live-room` 和 `unite-board` 之间切换时，使用软切换，不闪空，不误落子。
3. 在 `#/live` 和 `#/game` 这种非棋盘页时进入等待态，不自动停止同步。
4. 第一次直接进入 `live-room` 或 `unite-board` 都必须能够独立启动，不依赖之前成功同步过其他房间。
5. 白框可以先于棋谱同步出现，用来表示 geometry 已 ready。
6. 只有“同步源 ready + geometry ready”同时满足时，新的房间会话才允许切为活跃会话。
7. 一旦离开棋盘页，旧 geometry 必须立刻失效，不能再对旧房间落子。

## 不在范围内

1. 不修改 readboard 的协议结构。
2. 不调整 readboard 的落子坐标算法。
3. 不处理弈客页面 DOM 结构之外的通用网页同步问题。
4. 不引入新的跨模块架构层，例如单独的通用 sync framework。

## 路由语义

当前弈客页面至少应区分为 4 类：

| route kind | 例子 | 是否是棋盘页 |
|---|---|---|
| `live-list` | `#/live` | 否 |
| `game-lobby` | `#/game` | 否 |
| `live-room` | `#/live/new-room/:id/:hall/:room`、`#/live/room/:id/:hall/:room` | 是 |
| `unite-board` | `#/unite/:id`、`#/game/play/:hall/:room` | 是 |

`live-list` 和 `game-lobby` 进入等待态。  
`live-room` 和 `unite-board` 才允许建立同步候选会话。

## 核心状态模型

### 1. 监听态

`listenerEnabled`

只表示用户是否开启了 readboard 的弈客持续监听。

它不等于：

- 当前一定已有活跃同步会话
- 当前一定拥有有效 geometry
- 当前一定处于棋盘页

### 2. 页面态

`pageKind`

只由当前 URL 解析得到，用于决定：

- 是进入等待态，还是允许建立候选会话
- 当前几何候选是否与页面语义兼容

### 3. 活跃会话

`activeSession`

表示当前真正对外生效的同步会话。一个时刻最多只有一个。

建议字段：

- `sessionKey`
- `routeKind`
- `roomId`
- `syncReady`
- `geometryReady`
- `boardSize`
- `geometry`
- `sourceUrl`

### 4. 候选会话

`pendingSession`

当用户从房间 A 切到房间 B 时，不立刻替换 `activeSession`，而是先建立 `pendingSession`。

`pendingSession` 负责收集新房间的两类 readiness：

- `syncReady`
- `geometryReady`

只有两者都为真，才允许原子切换为新的 `activeSession`。

## session identity

每个房间会话必须有稳定 `sessionKey`，至少由以下信息组成：

- `routeKind`
- `roomId` 或等价 URL 主键

目的：

1. 判断当前页面是否还是同一房间。
2. 拒绝旧房间异步回流的数据。
3. 让 geometry 和同步源都能按房间归属，而不是按“最后一次成功结果”归属。

## readiness 定义

### `geometryReady`

满足以下条件即为真：

1. 当前页面 route kind 是 `live-room` 或 `unite-board`
2. probe 已选中与当前 route 兼容的棋盘候选
3. geometry 绑定到当前 `sessionKey`

白框可以在 `geometryReady=true` 时显示，即使此时 `syncReady=false`。

### `syncReady`

满足以下条件即为真：

1. 当前房间已进入正确的同步源路径
2. 已拿到有效棋谱或等价首帧同步结果
3. `boardSize` 已 resolve

日志上常见的：

- `sendYikeGeometry skipped hasBoardSize=false geometry=true`

应被视为：

- `geometryReady=true`
- `syncReady=false`

而不是“probe 没跑”。

## 状态转移规则

### 1. 用户点击开始同步

- 仅设置 `listenerEnabled=true`
- 不把“开始同步信号”解释为“立即重建当前会话”
- 根据当前 `pageKind` 决定：
  - `live-list` / `game-lobby` -> 进入等待态
  - `live-room` / `unite-board` -> 建立 `pendingSession`

### 2. 同房间重复收到开始同步信号

如果当前 URL 对应的 `sessionKey` 与现有 `activeSession` 或 `pendingSession` 相同：

- 只允许刷新状态
- 不允许重新调用整套 `syncOnline -> applyChangeWeb -> proc`

这条规则用于压住 `#/unite/:id` 下的重复刷新问题。

### 3. 从棋盘页切到另一个棋盘页

例如：

- `unite-board -> live-room`
- `live-room -> unite-board`
- `live-room A -> live-room B`
- `unite-board A -> unite-board B`

处理规则：

1. 建立新的 `pendingSession`
2. 旧 `activeSession` 保留显示结果
3. 新房间独立启动同步源与 geometry 采集
4. 当且仅当新房间 `syncReady=true` 且 `geometryReady=true` 时：
   - 原子切换 `pendingSession -> activeSession`

### 4. 从棋盘页切到等待页

例如：

- `live-room -> #/live`
- `unite-board -> #/game`

处理规则：

1. 保留旧棋盘显示内容
2. 立即撤销当前生效 geometry
3. 禁止任何自动落子
4. 保持 `listenerEnabled=true`
5. 不视为停止同步

这条规则对应用户确认的语义：“旧棋盘可留着看，但旧 geometry 立即失效”。

### 5. 用户点击停止同步

必须同时清理：

- `listenerEnabled`
- `activeSession`
- `pendingSession`
- unite poller / live fetch / 定时任务
- 生效 geometry

## geometry 归属规则

这是本次修复的核心约束。

### 1. geometry 必须绑定 `sessionKey`

每一份 geometry 在进入 `OnlineDialog` 后，必须能判断其属于哪个会话。

只有以下两类 geometry 允许生效：

- 属于当前 `pendingSession`
- 属于当前 `activeSession`

其他来源一律丢弃。

### 2. geometry 不能跨房间复用

当用户进入新房间时：

- 即使旧 geometry 仍然可见
- 也不能把旧 geometry 继续作为当前落子 geometry 使用

这条规则用于修复：

- “回到直播房间后白框大小错误”

### 3. geometry clear 的语义

离开棋盘页进入等待态时，需要撤销当前生效 geometry。  
但“撤销 geometry”不等于“必须立刻清空棋盘显示”。

因此应区分：

- `display state`
- `placement geometry state`

## bootstrap 规则

`live-room` 和 `unite-board` 的首次进入都必须能从零状态独立启动。

禁止以下隐式依赖：

1. 依赖先前成功的 `unite-board` 会话
2. 依赖旧 `boardSize`
3. 依赖旧 poller 存活
4. 依赖 probe 已经在另一类页面上安装成功

这条规则直接对应现象：

- 第一次进入直播房间可能不会触发同步
- 必须先去对弈房间触发一次，回来直播房间才会活

## 可观测行为

为了让故障可区分，日志和 UI 语义需要一致：

1. 白框已显示，但棋盘未同步
   - 含义：`geometryReady=true, syncReady=false`

2. 棋盘同步已开始，但没有白框
   - 含义：`syncReady=true, geometryReady=false`

3. 新房间尚未 ready，旧棋盘仍在显示
   - 含义：处于软切换中的 `pendingSession`

4. 进入等待页后棋盘仍显示，但不能落子
   - 含义：旧显示保留，geometry 已撤销

## 建议实现边界

本次实现应集中在以下文件，不额外铺新框架：

- `src/main/java/featurecat/lizzie/gui/BrowserFrame.java`
- `src/main/java/featurecat/lizzie/gui/OnlineDialog.java`

建议做法：

1. 把 `listenerEnabled` 与“当前房间是否 ready”拆开
2. 在 `BrowserFrame` 里只处理页面态、监听态和房间切换触发
3. 在 `OnlineDialog` 里只处理会话 readiness、geometry 归属和活跃会话切换

## 验收标准

1. 第一次直接进入 `live-room`，点击同步后能独立启动，不依赖先前进入 `unite-board`
2. 第一次直接进入 `unite-board`，点击同步后能独立启动
3. `unite-board` 内不会因重复开始信号进入刷新循环
4. `unite-board -> live-room` 切换时，不需要先手动停同步
5. `live-room -> unite-board` 切换时，不沿用旧房间 geometry
6. 在 `#/live` / `#/game` 时保留等待态，不会继续对旧房间落子
7. 直播房间白框尺寸必须只来自当前 `sessionKey` 对应的 geometry
8. 只有新房间 `syncReady + geometryReady` 同时成立时，才允许切换活跃会话

## 参考

1. `docs/2026-05-08-yike-page-structure-capture.md`
2. `docs/specs/2026-04-22-web-board-viewer-design.md`
