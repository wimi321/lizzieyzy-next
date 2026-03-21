# 安装指南

这份指南只回答四件事：

1. 你应该下载哪个包
2. 装完以后怎么打开
3. 第一次启动会不会自动配置
4. 怎么用野狐ID抓取公开棋谱

## 先选对包

| 你的系统 | 推荐下载 | 内置 Java | 内置 KataGo | 适合谁 |
| --- | --- | --- | --- | --- |
| Windows 64 位 | `<date>-windows64.with-katago.installer.exe` | 是 | 是 | 普通用户首选，双击安装 |
| Windows 64 位 | `<date>-windows64.with-katago.portable.zip` | 是 | 是 | 不想安装，只想解压后直接运行 |
| Windows 64 位 | `<date>-windows64.without.engine.portable.zip` | 是 | 否 | 想自己配引擎 |
| Windows 32 位 | `<date>-windows32.without.engine.zip` | 否 | 否 | 老机器兼容用途 |
| macOS Apple Silicon | `<date>-mac-arm64.with-katago.dmg` | App 自带运行时 | 是 | M 系列 Mac |
| macOS Intel | `<date>-mac-amd64.with-katago.dmg` | App 自带运行时 | 是 | Intel Mac |
| Linux 64 位 | `<date>-linux64.with-katago.zip` | 是 | 是 | Linux 桌面用户 |
| 进阶自定义 | `<date>-Macosx.amd64.Linux.amd64.without.engine.zip` | 否 | 否 | 自己配 Java 和引擎 |

一句话建议：

- 想最省事：选 `with-katago`
- 想完全自己配：选 `without.engine`
- Windows 普通用户：优先选 `.installer.exe`

## Windows 安装

### Windows 64 位安装器

1. 下载 `windows64.with-katago.installer.exe`。
2. 双击运行安装器。
3. 按向导选择安装目录。
4. 安装完成后，从桌面快捷方式或开始菜单打开程序。

这是目前最推荐给普通用户的 Windows 路径。

### Windows 64 位便携包

1. 下载 `windows64.with-katago.portable.zip`。
2. 解压到普通目录，例如 `D:\LizzieYzy-Next-FoxUID`。
3. 打开解压后的目录。
4. 双击 `LizzieYzy Next-FoxUID.exe`。

### Windows 64 位无引擎包

1. 下载 `windows64.without.engine.portable.zip`。
2. 解压后运行 `LizzieYzy Next-FoxUID.exe`。
3. 这个包带程序和 Java，但不带 KataGo。
4. 启动后请在软件里配置你自己的引擎。

### 如果你看到的是旧版本发布页

部分旧 tag 还保留原来的 `windows64.with-katago.zip` 形式。

如果你暂时没有看到新的 `.installer.exe` 或 `.portable.zip`：

1. 先下载同日期的 `windows64.with-katago.zip`。
2. 解压后按包内说明启动。
3. 新发布会逐步统一切到安装器优先的 Windows 资产。

## macOS 安装

### 先确认你的芯片

- `Apple 菜单 -> 关于本机` 中显示 Apple M 系列：下载 `mac-arm64.with-katago.dmg`
- 显示 Intel：下载 `mac-amd64.with-katago.dmg`

### 安装步骤

1. 下载对应的 `.dmg`。
2. 打开 `.dmg`。
3. 把 `LizzieYzy Next-FoxUID.app` 拖到 `Applications`。
4. 从“应用程序”中打开它。

### 第一次被系统拦住怎么办

当前维护版的 macOS 包仍然是未签名 / 未公证包。

如果第一次打不开：

1. 先尝试打开一次。
2. 打开 `系统设置 -> 隐私与安全性`。
3. 找到被拦截的应用提示。
4. 点击 `仍要打开`。
5. 再回到“应用程序”重新启动。

## Linux 安装

1. 下载 `linux64.with-katago.zip`。
2. 解压到你有写权限的目录。
3. 打开终端进入该目录。
4. 运行：

```bash
chmod +x start-linux64.sh
./start-linux64.sh
```

如果你的桌面环境双击没反应，优先从终端启动，这样更容易看到报错信息。

## 第一次启动会自动做什么

新维护版会优先自动完成这些事情：

- 检测内置 KataGo、默认权重和配置文件是否齐全
- 自动写入可用的默认引擎设置
- 如果内置权重缺失，提供下载推荐官方权重的入口
- 只有在自动配置仍然失败时，才回到手工设置

也就是说，大多数 `with-katago` 用户第一次打开后，不需要再先研究引擎路径。

## 打开后怎么抓野狐棋谱

1. 启动程序。
2. 点击或打开菜单里的 **野狐棋谱（输入野狐ID获取）**。
3. 输入纯数字的野狐ID。
4. 获取最新公开棋谱。

注意：

- 这里只支持 **野狐ID**。
- 不再支持用户名搜索。
- 如果该账号最近没有公开棋谱，可能会返回空结果。

## 整合包里的引擎和权重在哪

- Windows / Linux 整合包权重：`Lizzieyzy/weights/default.bin.gz`
- macOS 整合包权重：`LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`
- macOS 整合包引擎：`LizzieYzy Next-FoxUID.app/Contents/app/engines/katago/`

当前默认内置信息：

- KataGo 版本：`v1.16.4`
- 默认权重：`g170e-b20c256x2-s5303129600-d1228401921.bin.gz`

## 需要更多说明

- [发布包说明](PACKAGES.md)
- [常见问题与排错](TROUBLESHOOTING.md)
- [已验证平台](TESTED_PLATFORMS.md)
