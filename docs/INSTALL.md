# 安装指南

这份指南只回答四件事：

1. 你应该下载哪个包
2. 装完以后怎么打开
3. 第一次启动会不会自动配置
4. 怎么用野狐昵称抓取公开棋谱

## 先直接说结论

这份安装指南对应的是当前仍在维护的 `LizzieYzy Next`，也就是很多人还在找的 `lizzieyzy 维护版 / 替代版本`。

- 如果你在找 `KataGo 围棋复盘软件` 的 Windows 免安装包，先下载 `windows64.opencl.portable.zip`
- 如果你想找 `还能继续用的 lizzieyzy 维护版`，这个项目就是现在应该优先看的版本
- 如果你想 `输入野狐昵称后直接抓谱再复盘`，当前维护版已经支持
- 如果你担心第一次启动要自己配很多东西，主推荐整合包已经内置 KataGo 和默认权重
- 如果你在意棋盘同步工具，主发布包现在也直接带 `readboard_java` 简易版，不用再额外找单独仓库

## 先选对包

| 你的系统 | 推荐下载 | 内置 Java | 内置 KataGo | 适合谁 |
| --- | --- | --- | --- | --- |
| Windows 64 位 | `<date>-windows64.opencl.portable.zip` | 是 | 是 | 普通用户首选，免安装，解压即用 |
| Windows 64 位 | `<date>-windows64.opencl.installer.exe` | 是 | 是 | 想保留安装流程的 OpenCL 用户 |
| Windows 64 位 | `<date>-windows64.with-katago.portable.zip` | 是 | 是 | OpenCL 不稳定时的 CPU 兜底，免安装 |
| Windows 64 位 | `<date>-windows64.with-katago.installer.exe` | 是 | 是 | 想安装的 CPU 兜底版 |
| Windows 64 位 | `<date>-windows64.nvidia.portable.zip` | 是 | 是 | 有 NVIDIA 显卡，想要更快分析，也不想安装 |
| Windows 64 位 | `<date>-windows64.nvidia.installer.exe` | 是 | 是 | 有 NVIDIA 显卡，想保留安装流程 |
| Windows 64 位 | `<date>-windows64.without.engine.portable.zip` | 是 | 否 | 想自己配引擎，也不想安装 |
| Windows 64 位 | `<date>-windows64.without.engine.installer.exe` | 是 | 否 | 想保留安装流程，但自己配引擎 |
| macOS Apple Silicon | `<date>-mac-arm64.with-katago.dmg` | App 自带运行时 | 是 | M 系列 Mac |
| macOS Intel | `<date>-mac-amd64.with-katago.dmg` | App 自带运行时 | 是 | Intel Mac |
| Linux 64 位 | `<date>-linux64.with-katago.zip` | 是 | 是 | Linux 桌面用户 |

一句话建议：

- 想最省事：选 `windows64.opencl.portable.zip`
- 如果 OpenCL 在你电脑上不稳定：改用 `windows64.with-katago.portable.zip`
- 想在 NVIDIA 显卡上跑得更快：选 `windows64.nvidia.portable.zip`
- 想自己管引擎：Windows 选 `without.engine.portable.zip`，想安装再选同名 `installer.exe`
- Windows 普通用户：优先选 `.portable.zip`，想保留安装流程再选同名 `.installer.exe`

### 历史 tag 说明

部分旧 tag 还会看到早期的 zip 命名或兼容包，但当前维护版公开 release 已统一成 11 个主资产：8 个 Windows、2 个 macOS、1 个 Linux。普通用户直接按上面的表选即可。

## Windows 安装

### Windows 64 位 OpenCL 免安装包（推荐）

1. 下载 `windows64.opencl.portable.zip`。
2. 解压到普通目录，例如 `D:\LizzieYzy-Next`。
3. 打开解压后的目录。
4. 双击 `LizzieYzy Next OpenCL.exe`。

这是当前最推荐给普通用户的 Windows 路径。
OpenCL 免安装包也能直接打开 `KataGo 一键设置`，点一次“智能测速优化”，自动写入更合适的线程数。

### Windows 64 位 OpenCL 安装器

如果你更喜欢安装流程：

1. 下载 `windows64.opencl.installer.exe`。
2. 双击运行安装器。
3. 按向导选择安装目录。
4. 安装完成后，从桌面快捷方式或开始菜单打开程序。

### Windows 64 位 CPU 兜底包

如果 OpenCL 在你的电脑上表现不稳定：

1. 优先下载 `windows64.with-katago.portable.zip`。
2. 解压后运行 `LizzieYzy Next.exe`。
3. 如果你更喜欢安装流程，再改用 `windows64.with-katago.installer.exe`。

### Windows 64 位 NVIDIA 极速版

如果你的电脑有 NVIDIA 显卡，而且你更在意分析速度：

1. 优先下载 `windows64.nvidia.portable.zip`。
2. 解压后运行 `LizzieYzy Next NVIDIA.exe`。
3. 第一次启动时，程序会自动把需要的官方 NVIDIA 运行库准备到你的用户目录。
4. 这个包内置的是官方 KataGo CUDA Windows 版本。想把线程数调到更合适，可以打开 `KataGo 一键设置`，点一次“智能测速优化”。

如果你更喜欢安装流程：

1. 下载 `windows64.nvidia.installer.exe`。
2. 双击运行安装器。
3. 安装完成后，从开始菜单或桌面打开程序。

注意：

- 这个版本只适合 NVIDIA 显卡电脑。
- 如果你不确定自己是不是 NVIDIA 显卡，直接下载普通的 `windows64.opencl.portable.zip`。

### Windows 64 位无引擎包

如果你想自己配引擎：

1. 优先下载 `windows64.without.engine.portable.zip`。
2. 解压后运行 `LizzieYzy Next.exe`。
3. 这个包带程序和 Java，但不带 KataGo。
4. 启动后请在软件里配置你自己的引擎。

如果你仍然想走正常安装流程：

1. 下载 `windows64.without.engine.installer.exe`。
2. 双击运行安装器。
3. 安装完成后，从开始菜单或桌面打开程序。
4. 启动后在软件里配置你自己的引擎。

## macOS 安装

### 先确认你的芯片

- `Apple 菜单 -> 关于本机` 中显示 Apple M 系列：下载 `mac-arm64.with-katago.dmg`
- 显示 Intel：下载 `mac-amd64.with-katago.dmg`

### 安装步骤

1. 下载对应的 `.dmg`。
2. 打开 `.dmg`。
3. 把 `LizzieYzy Next.app` 拖到 `Applications`。
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
2. 点击或打开菜单里的 **野狐棋谱（输入野狐昵称获取）**。
3. 输入野狐昵称。
4. 程序会自动找到账号并获取最近公开棋谱。

注意：

- 现在不需要你先知道账号数字
- 如果昵称输错，可能会找不到对应账号
- 如果该账号最近没有公开棋谱，返回空结果是正常现象

## 整合包里的引擎和权重在哪

- Windows / Linux 整合包权重：`Lizzieyzy/weights/default.bin.gz`
- macOS 整合包权重：`LizzieYzy Next.app/Contents/app/weights/default.bin.gz`
- macOS 整合包引擎：`LizzieYzy Next.app/Contents/app/engines/katago/`

当前默认内置信息：

- KataGo 版本：`v1.16.4`
- 默认权重：`kata1-zhizi-b28c512nbt-muonfd2.bin.gz`

## 需要更多说明

- [发布包说明](PACKAGES.md)
- [常见问题与排错](TROUBLESHOOTING.md)
- [已验证平台](TESTED_PLATFORMS.md)
