# 弈客页面结构实抓记录

日期：2026-05-08

目的：记录 `home.yikeweiqi.com` 当前真实路由、页面类型和棋盘 DOM 结构，用于校准 `BrowserFrame` 的路由识别与 `OnlineDialog` 的几何采集判断。

范围：只记录本次通过 Chrome CDP 实抓到的事实，不包含修复方案，不假设弈客后续前端不会变更。

## 1. 结论摘要

1. `#/live` 是直播列表页，不是棋盘页。
2. `#/live/new-room/:id/:hall/:room` 是直播房间棋盘页。
3. `#/unite/:id` 是对弈中的真实棋盘页。
4. `#/game` 是对弈大厅列表页，不是棋盘页。
5. 当前把 `live-list / live-room / game` 三分法直接对应到页面类型还不够精确；至少还要把 `#/game` 和 `#/unite/:id` 区分开。

## 2. 实抓路由与页面类型

| URL | 标题 | 页面类型 | 是否有棋盘 |
|---|---|---|---|
| `https://home.yikeweiqi.com/#/live` | `弈客围棋 — 直播大厅` | 直播列表页 | 否 |
| `https://home.yikeweiqi.com/#/live/new-room/186592/0/0` | `高水平对弈` | 直播房间页 | 是 |
| `https://home.yikeweiqi.com/#/unite/66299144` | `弈客围棋 — 对弈大厅` | 对弈棋盘页 | 是 |
| `https://home.yikeweiqi.com/#/game` | `弈客围棋 — 对弈大厅` | 对弈大厅列表页 | 否 |

本次从前端路由表抓到的相关路径：

| path | name |
|---|---|
| `/game` | `game` |
| `/unite/:id` | `unite` |
| `/game/play/:hall/:room` | `play` |
| `/live` | `live` |
| `/live/board/:id` | `live-board` |
| `/match/board/:id` | `match-board` |
| `/live/room/:id/:hall/:room` | `room` |
| `/live/new-room/:id/:hall/:room` | `new-room` |

## 3. `#/live` 列表页

### 3.1 页面事实

- 根组件名：`golive`
- 当前路由：`name=live path=/live fullPath=/live`
- 页面上直播条目不是普通 `<a>` 链接。
- 直播条目主体是 `div.live_detail` 等可点击块。
- 页面里存在：
  - `room_list_chat ivu-row`
  - `room_list_box`
  - `live_title_box white ivu-card ivu-card-bordered`
  - `live_detail`
  - `livedtl_medium`
  - `livedtl_second`

### 3.2 前端跳房间逻辑

从 Vue 实例抓到的方法名为 `toLiveRoom`，关键逻辑如下：

- `Version == 1` 时跳到 `/#/live/room/:id/:hall/:room`
- `Version == 2` 时跳到 `/#/live/new-room/:id/:hall/:room`
- 当 `hall == 0` 或 `room == 0` 或 `Status >= 3` 时，仍会进入房间路由，只是参数为 `.../0/0`

本次实际抓到的 `liveList` 样本中：

- `Id=186592`
- `GameName=高水平对弈`
- `Status=2`
- `Version=2`
- `hall=0`
- `room=0`

因此实际打开的直播房间 URL 为：

`https://home.yikeweiqi.com/#/live/new-room/186592/0/0`

### 3.3 对同步逻辑的影响

- `#/live` 不应被当成棋盘页做几何采集。
- 从 `#/live` 切换到房间页时，路由会变，但不是进入 `#/game` 或 `#/unite/:id`。
- 如果 route classifier 只区分 `live-list / live-room / game`，至少要确保 `live-room` 覆盖 `#/live/new-room/...` 和 `#/live/room/...` 两类路径。

## 4. `#/live/new-room/:id/:hall/:room` 直播房间页

### 4.1 页面事实

实抓 URL：

`https://home.yikeweiqi.com/#/live/new-room/186592/0/0`

页面正文可见：

- 棋盘
- 提子
- 手数
- 解说 / 讨论
- 打赏

### 4.2 棋盘相关 DOM

本次在直播房间页抓到的棋盘相关类名：

- `board_content`
- `board`
- `wgo-player-board`
- `wgo-board`
- `game_info`
- `game_info_detail`
- `room_chat`

未看到以下对弈页外壳类名：

- `board_detail_new`
- `board_width`

### 4.3 对同步逻辑的影响

- 直播房间和 `#/unite/:id` 都使用 WGo 棋盘，但外层容器不同。
- 任何只接受 `board_detail_new` / `board_width` 的筛选，都会漏掉直播房间页。
- 如果当前 `live-room` 路由上对白框或候选棋盘做了额外过滤，需要以 `board_content` / `wgo-player-board` / `wgo-board` 这组真实结构为准重新核对。

## 5. `#/unite/:id` 对弈棋盘页

### 5.1 页面事实

实抓 URL：

`https://home.yikeweiqi.com/#/unite/66299144`

页面正文可见：

- 棋盘
- 双方读秒与提子
- `AI判断`
- `Winrate`
- `领地`
- `Territory`
- `试下`
- `聊天室`

### 5.2 棋盘相关 DOM

本次在 `#/unite/:id` 页抓到的棋盘相关类名：

- `board_detail_new`
- `board_width`
- `board`
- `wgo-player-board`
- `wgo-board`
- 多个棋盘 `canvas`

其中棋盘区域实测矩形为：

- `board_width`: `1149 x 1149`
- `board`: `1149 x 1149`
- `wgo-player-board`: `1149 x 1149`
- 左上角约在 `(386, 60)`

### 5.3 对同步逻辑的影响

- `#/unite/:id` 才是当前真实的对弈棋盘页。
- 如果当前代码把“game”泛指为所有对弈相关页面，会把 `#/game` 大厅页和 `#/unite/:id` 棋盘页混在一起。
- `#/unite/:id` 的自动同步触发与 probe 采集，应该和 `#/game` 大厅页明确分开。

## 6. `#/game` 对弈大厅列表页

### 6.1 页面事实

实抓 URL：

`https://home.yikeweiqi.com/#/game`

页面为对弈大厅，不是棋盘页。左侧是对局列表，右侧是约战 / 搜索 / 好友等面板。

### 6.2 对同步逻辑的影响

- `#/game` 不能当成棋盘页做几何采集。
- 如果 `shouldAutoStartYikeSyncForAddress(...)` 或 route classifier 把 `#/game` 和 `#/unite/:id` 归为同一路径类别，容易误触发：
  - 进入大厅就触发同步
  - 大厅页也装棋盘 probe
  - 大厅与棋盘页切换时误清理 / 误重启同步

## 7. 当前最直接的实现启示

这次实抓只支持以下结论，不支持更多外推：

1. `live-list`、`live-room`、`unite-board`、`game-lobby` 至少是四种不同页面语义。
2. `live-room` 与 `unite-board` 都有棋盘，但外层 DOM 不同。
3. `live-list` 与 `game-lobby` 都不是棋盘页，不应发棋盘几何。
4. 当前把 `#/game` 和 `#/unite/:id` 混成一个 `game` 类别，风险很高。

## 8. 本次抓取方式

- 使用本机 Chrome CDP 连接用户当前登录态浏览器。
- 打开实际页面并读取：
  - 页面标题
  - 当前 URL
  - 路由表
  - Vue 实例方法
  - 页面正文
  - 棋盘相关 DOM 类名和矩形
- 所有结论以本次实抓结果为准，不来自搜索引擎或静态猜测。
