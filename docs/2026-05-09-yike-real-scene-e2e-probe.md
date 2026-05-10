# 弈客真实场景自动探针（readboard 按钮链路）

日期：2026-05-09

## 目的

给「直播房间点 readboard 同步不刷新」提供可重复、可脚本化的真实场景验证，不再依赖手动翻日志。

覆盖链路：

1. 点击 readboard 同步按钮
2. lizzie 收到 `yikeSyncStart`
3. `BrowserFrame start from readboard`
4. 显式触发当前页面 reload（`force reload`）

## 脚本

文件：`scripts/yike_sync_e2e_probe.py`

依赖：

- Python 3（当前环境示例：`C:\Python314\python.exe`）
- `uiautomation`

安装示例：

```powershell
C:\Python314\python.exe -m pip install --user uiautomation
```

运行示例：

```powershell
C:\Python314\python.exe D:\dev\weiqi\lizzieyzy-next\scripts\yike_sync_e2e_probe.py
```

可选参数：

- `--log`：`yike-sync-debug.log` 路径
- `--window-regex`：readboard 窗口名匹配（默认 `.*readboard.*`）
- `--button-contains`：按钮名关键字（默认 `同步|Sync|Keep|开始`）

## 前置条件

1. readboard 和 lizzie 都已启动
2. 弈客浏览器页面已停留在待验证页面（例如 `#/live/new-room/...`）
3. 已开启 debug log（本仓库默认关闭）：

```text
-Dlizzie.yike.debugLog.enabled=true
```

4. `target/target/yike-sync-debug.log`（或你自定义的日志路径）正在写入

## 输出解释

脚本会输出：

- `start_command_seen`
- `start_from_readboard_seen`
- `force_reload_seen`
- `route_from_start_log`
- `pass`

`pass=true` 才表示这条“readboard 按钮 -> lizzie 启动 -> 强制刷新”链路完整打通。

## 本次真实场景结论（2026-05-09）

1. 当 readboard 平台选中为 `野狐` 时，点击同步按钮不会触发弈客链路，这是预期行为。
2. 把平台切到 `弈客` 后：
   - 点击 `持续同步` 可以稳定触发 `yikeSyncStart -> start from readboard -> force reload`。
   - 点击 `一键同步` 原先不会发送 `yikeSyncStart`，会出现“看起来点了同步但直播房间不刷新”。
3. 已在 readboard 侧修复：`oneTimeSync()` 进入弈客同步时也发送 `yikeSyncStart`。
