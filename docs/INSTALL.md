# 安装指南

这份文档面向第一次接触 `LizzieYzy Next-FoxUID` 的用户，目标只有一件事：

**帮你尽快选对包、装起来、打开后直接用上“野狐棋谱（输入野狐ID获取）”。**

## 先选对包

| 你的系统 | 推荐下载 | 内置 Java | 内置 KataGo | 说明 |
| --- | --- | --- | --- | --- |
| Windows 64 位 | `<date>-windows64.with-katago.zip` | 是 | 是 | 最省事，解压后基本可直接用 |
| Windows 64 位 | `<date>-windows64.without.engine.zip` | 是 | 否 | 想自己配置引擎 |
| Windows 32 位 | `<date>-windows32.without.engine.zip` | 否 | 否 | 老机器兼容用途 |
| macOS Apple Silicon | `<date>-mac-arm64.with-katago.dmg` | App 自带运行时 | 是 | M1/M2/M3/M4 等机器 |
| macOS Intel | `<date>-mac-amd64.with-katago.dmg` | App 自带运行时 | 是 | Intel 芯片 Mac |
| Linux 64 位 | `<date>-linux64.with-katago.zip` | 是 | 是 | Linux 桌面用户首选 |
| 进阶用户 | `<date>-Macosx.amd64.Linux.amd64.without.engine.zip` | 否 | 否 | 自己配 Java 和引擎 |

如果你不想折腾，优先选 `with-katago`。

## Windows 安装

### Windows 64 位整合包

1. 下载 `windows64.with-katago.zip`。
2. 解压到一个普通目录，比如 `D:\LizzieYzy-Next-FoxUID`。
3. 双击 `start-windows64.bat`。
4. 第一次启动后，程序通常会自动识别内置 KataGo，不需要手动配置引擎。

说明：

- 这个包按当前维护策略会同时带上 Java 运行时和 KataGo。
- 如果你只是想开箱即用，这是最推荐的包。

### Windows 64 位无引擎包

1. 下载 `windows64.without.engine.zip`。
2. 解压后运行 `start-windows64.bat`。
3. 这个包通常已经自带 Java 运行时，但**不带 KataGo**。
4. 启动后请在软件里配置你自己的引擎。

### Windows 32 位兼容包

1. 下载 `windows32.without.engine.zip`。
2. 解压后运行 `start-windows32.bat`。
3. 这个包**不带 Java，也不带 KataGo**。
4. 你需要自行安装 Java 11+，并手动配置引擎。

## macOS 安装

### 先确认你的芯片

- `Apple 菜单 -> 关于本机`，看到 `芯片` 为 Apple M 系列：下载 `mac-arm64.with-katago.dmg`
- 看到 `处理器` 为 Intel：下载 `mac-amd64.with-katago.dmg`

### 安装步骤

1. 下载对应的 `.dmg`。
2. 打开 `.dmg`，把 `LizzieYzy Next-FoxUID.app` 拖到 `Applications`。
3. 到“应用程序”里打开它。

### 第一次打不开怎么办

当前 macOS 包是**未签名 / 未公证**的维护版发布包，第一次可能会被 Gatekeeper 拦住。

处理方法：

1. 先尝试打开一次。
2. 打开 `系统设置 -> 隐私与安全性`。
3. 找到被拦截的应用提示，点击 `仍要打开`。
4. 再次启动应用。

说明：

- macOS 包已经自带 App 运行时和 KataGo。
- 一般不需要你另外安装 Java。
- Intel 和 Apple Silicon 是分别打包的，不是一个通用二进制包。

## Linux 安装

1. 下载 `linux64.with-katago.zip`。
2. 解压到一个你有写权限的目录。
3. 打开终端进入目录。
4. 运行：

```bash
chmod +x start-linux64.sh
./start-linux64.sh
```

说明：

- 当前维护的 Linux 64 位整合包按打包脚本会带上 Java 运行时和 KataGo。
- 如果桌面环境双击没反应，优先从终端启动，这样更容易看到报错信息。

## 打开后怎么用野狐抓谱

1. 启动程序。
2. 找到菜单里的 **野狐棋谱（输入野狐ID获取）**。
3. 输入纯数字的野狐ID。
4. 获取最新公开棋谱。

注意：

- 这里只支持 **野狐ID**。
- 不再支持用户名检索。
- 如果该账号没有可见的最新公开棋谱，可能会返回空结果。

## 整合包里的引擎和权重在哪

- Windows / Linux 整合包权重：`Lizzieyzy/weights/default.bin.gz`
- macOS 整合包权重：`LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`
- macOS 整合包引擎：`LizzieYzy Next-FoxUID.app/Contents/app/engines/katago/`

当前默认内置信息：

- KataGo 版本：`v1.16.4`
- 默认权重：`g170e-b20c256x2-s5303129600-d1228401921.bin.gz`

## 想自己换引擎或权重

可以，推荐两种方式：

1. 下载 `without.engine` 包，自己完整配置。
2. 在 `with-katago` 包基础上替换权重或调整引擎设置。

如果你准备这样做，建议同时阅读：

- [发布包说明](PACKAGES.md)
- [常见问题与排错](TROUBLESHOOTING.md)
