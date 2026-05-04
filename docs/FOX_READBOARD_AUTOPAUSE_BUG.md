# 野狐 readboard 同步期间分析自动暂停问题

**日期**：2026-04-29
**状态**：未修复，已诊断根因

## 现象

使用 readboard 同步野狐对局时：

- 野狐双方下棋很快（连续多手）
- lizzieyzy-next 棋盘历史能跟上、当前位置在主线末尾
- 但底栏的"停止分析"按钮会**自动翻成"开始分析"**——分析被关掉
- 手动点"开始分析"可以恢复

不影响弈客 / 弈城 / 新浪等其他平台。

## 根因

`src/main/java/featurecat/lizzie/analysis/Leelaz.java:1556-1558`：

```java
if (!isInputCommand && params.length == 2) {
  isPondering = false;
}
```

这一段在解析 KataGo 输出 `play <move>` 行时执行。设计意图是 KataGo 自发 genmove（lz-genmove_analyze 等）后停掉 ponder 状态。

野狐 readboard 路径：

1. readboard 发 `play b D4` 给 lizzieyzy
2. lizzieyzy 调 `Leelaz.playMove(...)` → 设 `isInputCommand=true`、`sendCommand("play b D4")`
3. KataGo 异步回 `= ` 应答（成功），params.length=1，不触发上面那行
4. 同时 KataGo 持续输出 lz-analyze 的 `info ...` 和偶尔的 `play <move>`
5. **快速连续走子时**，前一个 `play` 命令的 `isInputCommand=true` 还没复位（line 1561 才复位），下一个 `play` 命令又来；某个时刻 KataGo 输出 `play X` 行被识别为非 input-command，命中 line 1556 → `isPondering=false`

底栏看到的"暂停按钮"反映的就是 `isPondering` 这个布尔值。

## 排除掉的嫌疑

- **readboard `noponder` 命令**：检查过 readboard `Form1.cs:2560`，`SendNoPonder()` 只在用户手动按某按钮 (`button7_Click_1`) 时发送，自动场景不会发
- **`isPlayingAgainstLeelaz` 路径**（Leelaz.java:1535/1644 togglePonder）：受 `Lizzie.frame.isPlayingAgainstLeelaz` 守护，野狐 sync 没开这个
- **`isAnaPlayingAgainstLeelaz` resign 路径**（Leelaz.java:1810/1819）：同上，野狐场景不开
- **clear/replay 路径**：`Leelaz.clear()`（line 3940）保留 ponder 状态（`if (isPondering) ponder()`），不会主动关
- **新棋谱触发的 `BoardHistoryNode.sync` 卡死**：那是 EDT 卡死症状，不是这里"按钮翻"

## 修法选项

### A（最简，3 行）

ReadBoard 处理完 `play X Y` 后，无条件 `if (!isPondering()) ponder()`。

副作用：用户手动按"暂停分析"在野狐 sync 期间会失效——下一手到来就被强制恢复。

### A1（折中，~30 行）

加 `userPaused` 标志区分用户主动暂停 vs 系统自动暂停：

1. Leelaz 加 `boolean userPaused`
2. 包装一个 `userTogglePonder()`：根据当前 isPondering 设 userPaused 为 true（暂停）或 false（恢复）
3. 把所有用户暂停入口改走 `userTogglePonder()`：
   - `LizzieFrame.togglePonderMannul()`
   - `BottomToolbar.java:1701` 暂停按钮
   - `AnalysisTable.java:64`
   - `FloatBoard.java:165`
   - 键盘快捷键
4. ReadBoard 在 `play` 后强制恢复时多加一个判断：`if (!userPaused && !isPondering()) ponder()`

风险：必须把所有用户能按到的暂停入口都罩住，漏一个那条入口的暂停会被破坏。

### B（根治，风险中等）

直接修 Leelaz.java:1556。比如改成 `if (!isInputCommand && params.length == 2 && Lizzie.frame.isPlayingAgainstLeelaz)`——只在和 KataGo 对弈模式下才停 ponder。

风险：这行原始意图不明，可能有别的代码路径依赖它（比如某种 genmove_analyze 流程）。需要回归测试主菜单的"和 KataGo 对弈" / 各种自对弈 / KataGo 自我对局等。

### D（待调研）

野狐着法有没有可能不走 `Leelaz.playMove`、改走旁路命令避开 line 1556？需要先搜清楚野狐 readboard 的着法到底通过哪个 Leelaz API 喂给 KataGo。

## 推荐顺序

1. 先 A（3 行），放两天看用户是否真的踩到"暂停被打破"
2. 如果真踩到 → 升 A1
3. B 等有空 + 有完整测试时再做（风险最高、收益最大）

## 相关代码位置

- `src/main/java/featurecat/lizzie/analysis/Leelaz.java:1495-1562` —— `play` 行解析（含 line 1556 嫌疑代码）
- `src/main/java/featurecat/lizzie/analysis/ReadBoard.java` —— readboard 命令处理入口
- `src/main/java/featurecat/lizzie/analysis/Leelaz.java:4151` —— `togglePonder()` 实现
- `src/main/java/featurecat/lizzie/gui/LizzieFrame.java:10047` —— `togglePonderMannul()` 用户入口
- `src/main/java/featurecat/lizzie/analysis/Leelaz.java:4220-4225` —— `Pondering()` / `notPondering()` 状态 setter
