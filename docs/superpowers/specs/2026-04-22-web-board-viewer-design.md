# 局域网 Web 旁观端设计文档

## 概述

为 LizzieYzy Next 添加内嵌的局域网 Web 旁观功能。启动后，同一局域网内的设备可通过浏览器访问 `http://<主机IP>:<端口>` 实时查看棋盘、候选点分析、变化图、形势判断热力图和胜率曲线。

**第一版范围：只读旁观模式。** Web 端不能操作棋盘（落子、导航等），仅展示 LizzieYzy 桌面端当前的分析画面。后续版本可增加交互功能。

## 架构

### 技术方案

WebSocket 实时推送 + 前端 Canvas 渲染。

- **Java 端**：内嵌 WebSocket 服务器（利用已有 `Java-WebSocket` 依赖）+ 简易 HTTP 静态文件服务器
- **前端**：纯 HTML/JS/CSS，Canvas 渲染棋盘和分析覆盖层
- **零额外依赖**：不引入新的 Maven 依赖

### 数据流

```
KataGo 引擎 ─ GTP ─▶ Leelaz.java ─ 事件 ─▶ WebBoardDataCollector ─ JSON ─▶ WebBoardServer
                                                                                    │
                                                                             ws:// 广播
                                                                                    │
                                                              ┌─────────────────────┼─────────────────────┐
                                                              ▼                     ▼                     ▼
                                                        📱 手机浏览器         💻 平板浏览器         🖥 电脑浏览器
                                                        (Canvas 独立渲染)    (Canvas 独立渲染)    (Canvas 独立渲染)
```

### 新增 Java 类

| 类 | 包路径 | 职责 |
|---|---|---|
| `WebBoardServer` | `featurecat.lizzie.gui.web` | 继承 `WebSocketServer`，管理所有 WebSocket 连接，广播棋盘状态和分析数据 |
| `WebBoardHttpServer` | `featurecat.lizzie.gui.web` | 极简 HTTP 服务器，从 classpath 提供前端静态文件（`src/main/resources/web/`） |
| `WebBoardDataCollector` | `featurecat.lizzie.gui.web` | 监听引擎分析事件和棋盘变化，将数据序列化为 JSON 并交给 `WebBoardServer` 广播 |

### 接入点（最小侵入）

- `Leelaz.java` 的 `parseInfoKatago()` 方法末尾 — 分析数据更新后通知 `WebBoardDataCollector`
- `Board.java` 的落子/导航方法 — 棋盘状态变化后通知 `WebBoardDataCollector`
- `LizzieFrame` 菜单 — 添加「工具 → Web 旁观」开关菜单项

### 生命周期

1. 用户在菜单点击「启动 Web 旁观」
2. 创建 `WebBoardServer`（WebSocket，默认端口 9999）和 `WebBoardHttpServer`（HTTP，默认端口 9998）
3. `WebBoardDataCollector` 注册到引擎分析回调，开始收集和广播数据
4. 用户点击「停止」时关闭两个服务器并清理资源

### 并发模型

- `WebBoardDataCollector` 内部持有一个单线程 `ScheduledExecutorService`
- 引擎回调（engine reader thread）和棋盘变化回调（EDT）仅向 collector 提交数据快照，不直接广播
- JSON 序列化和 WebSocket 广播在 collector 的单线程 executor 上执行，避免竞争
- `WebBoardServer` 的连接管理由 `Java-WebSocket` 内部线程处理，广播时对连接集合加锁

### 连接上限

最大同时连接数默认 **20**，可在 `config.json` 中配置（`max-connections`）。超出上限时拒绝新连接并返回关闭帧。

### HTTP 安全

`WebBoardHttpServer` 仅提供 classpath `/web/` 前缀下的文件。请求路径包含 `..` 或绝对路径时返回 403。仅支持 GET 方法。

### 端口冲突处理

默认端口被占用时，自动尝试 +1 递增，最多尝试 10 次。端口可在 `config.json` 中配置。

### 关闭顺序

停止时按以下顺序：
1. 注销 `WebBoardDataCollector` 的引擎/棋盘回调，停止 executor
2. 关闭 `WebBoardServer`（断开所有 WebSocket 连接）
3. 关闭 `WebBoardHttpServer`

## WebSocket JSON 消息协议

### 1. `full_state` — 完整状态快照

连接建立时和棋盘状态变化时发送。

```json
{
  "type": "full_state",
  "boardWidth": 19,
  "boardHeight": 19,
  "stones": [0, 0, 1, 2, 0, ...],
  "lastMove": [3, 3],
  "moveNumber": 42,
  "currentPlayer": "B",
  "bestMoves": [ ... ],
  "winrate": 56.3,
  "scoreMean": 2.5,
  "playouts": 12800,
  "estimateArray": [0.9, -0.8, ...]
}
```

字段说明：
- `stones`：长度 `boardWidth * boardHeight` 的数组，`0`=空、`1`=黑、`2`=白
- `lastMove`：`[x, y]` 坐标或 `null`
- `currentPlayer`：`"B"` 或 `"W"`
- `estimateArray`：形势判断，每点 `-1`（白方领地）到 `1`（黑方领地）；可为 `null`（引擎未启用形势判断时）

### 2. `analysis_update` — 分析增量更新

引擎每次输出分析结果时发送（高频，节流至最高 10 次/秒）。

```json
{
  "type": "analysis_update",
  "bestMoves": [
    {
      "coordinate": "Q16",
      "x": 15,
      "y": 3,
      "winrate": 56.3,
      "playouts": 3200,
      "scoreMean": 2.5,
      "scoreStdev": 8.1,
      "policy": 0.18,
      "lcb": 55.8,
      "order": 0,
      "variation": ["Q16", "D4", "R14", "C16", "Q10"]
    }
  ],
  "winrate": 56.3,
  "scoreMean": 2.5,
  "playouts": 12800,
  "estimateArray": [0.9, -0.8, ...]
}
```

字段说明：
- `coordinate`：GTP 格式坐标字符串（用于显示）
- `x`, `y`：数组索引坐标（用于 Canvas 渲染）
- `variation`：后续变化序列，悬停时在棋盘上绘制
- `order`：候选点排序序号（0 = 最佳）

### 3. `winrate_history` — 胜率曲线数据

棋盘状态变化时随 `full_state` 一起发送。

```json
{
  "type": "winrate_history",
  "data": [
    {"moveNumber": 1, "winrate": 50.0, "scoreMean": 0.5},
    {"moveNumber": 2, "winrate": 48.2, "scoreMean": -0.3}
  ]
}
```

### 节流策略

引擎高频输出时（KataGo 每秒可输出数十次），`WebBoardDataCollector` 限制广播频率最高 **10 次/秒**，取最新一次的完整数据发送。

## 前端设计

### 布局

**桌面端（宽度 > 768px）：左右布局**
- 左侧：棋盘区（Canvas），占主体空间
- 右侧：信息面板 — 状态栏（手数、轮到谁、胜率、目差、计算量）+ 胜率曲线图

**移动端（宽度 ≤ 768px）：上下布局（响应式切换）**
- 顶部：状态栏（紧凑一行）
- 中部：棋盘（Canvas，占满宽度）
- 底部：胜率曲线图

### 棋盘 Canvas 渲染

层级从下到上：
1. 棋盘底色 + 网格线 + 星位
2. 棋子（黑/白实心圆）+ 最后一手标记
3. 形势判断热力图覆盖层（半透明色块，可开关）
4. 候选点（半透明圆圈 + 胜率/目差文字，颜色按排名区分）
5. 悬停变化图覆盖层（半透明棋子 + 手数编号）

### 交互

**悬停变化图（核心交互）：**
- 桌面端：鼠标移到候选点上 → 在棋盘上叠加绘制后续变化序列
- 移动端：长按候选点触发，点击空白处取消
- 数据来自 `bestMoves[i].variation`，纯客户端渲染

**形势判断热力图：**
- 按钮切换显示/隐藏
- 黑方领地偏蓝/绿色，白方领地偏红/橙色，中性区域透明
- 当 `estimateArray` 为 `null` 时，隐藏热力图开关按钮

**胜率曲线图：**
- Canvas 折线图，X 轴手数，Y 轴胜率 0%-100%
- 50% 基准虚线
- 可切换显示目差曲线（`scoreMean`）

### 连接状态处理

- WebSocket 断开时显示半透明遮罩「连接断开，正在重连...」
- 自动重连（指数退避：1s → 2s → 4s → 8s → 最大 30s）
- 重连成功后服务端推送 `full_state` 恢复完整画面

## 菜单集成

`LizzieFrame` 菜单栏 → 「工具」 → 「Web 旁观」子菜单：
- **启动/停止**：切换 Web 服务开关
- **复制访问地址**：将 `http://<局域网IP>:<HTTP端口>` 复制到剪贴板

启动时状态栏显示 `Web: http://192.168.x.x:9998`（显示局域网 IP，非 localhost）。

## 前端文件位置

```
src/main/resources/web/
├── index.html      主页面
├── board.js        Canvas 渲染 + WebSocket 通信
└── board.css       样式
```

打包进 jar，运行时由 `WebBoardHttpServer` 从 classpath 读取并提供。

## 配置项（config.json）

```json
{
  "web-board": {
    "http-port": 9998,
    "ws-port": 9999,
    "max-connections": 20
  }
}
```

## 不在第一版范围内

- Web 端棋盘交互（落子、前后手导航、变化选择）
- 用户认证 / 访问控制
- HTTPS 支持
- 棋谱加载/保存
- 多语言支持（前端）
- HTTP 与 WebSocket 合并为单端口（当前 Java-WebSocket 1.6.0 不原生支持）
