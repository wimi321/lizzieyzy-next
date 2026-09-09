# 安装指南

这份指南只回答四件事：

1. 你应该下载哪个包
2. 装完以后怎么打开
3. 第一次启动会不会自动配置
4. 怎么用野狐昵称抓取公开棋谱

## 先直接说结论

这份安装指南对应的是当前仍在维护的 `LizzieYzy Next`，也就是很多人还在找的 `lizzieyzy 维护版 / 替代版本`。

- 如果你在找 `KataGo 围棋复盘软件` 的 Windows 免安装包，先按显卡选择：RTX 20/30/40/50 用 NVIDIA CUDA，AMD/Intel/较老 NVIDIA 用 OpenCL，没有合适 GPU 再用 CPU 兼容版
- 如果你想找 `还能继续用的 lizzieyzy 维护版`，这个项目就是现在应该优先看的版本
- 如果你想 `输入野狐昵称后直接抓谱再复盘`，当前维护版已经支持
- 如果你担心第一次启动要自己配很多东西，主推荐整合包已经内置 KataGo 和默认权重
- 如果你在意棋盘同步工具，Windows 主发布包现在直接带原生 `readboard.exe`，软件内只保留这套同步入口，不用再额外找单独仓库

## 先选对包

| 你的系统 | 推荐下载 | 内置 Java | 内置 KataGo | 适合谁 |
| --- | --- | --- | --- | --- |
| Windows 64 位 | `<date>-windows64.nvidia.portable.zip` | 是 | 是 | RTX 20/30/40/50 NVIDIA 显卡，推荐，免安装 |
| Windows 64 位 | `<date>-windows64.nvidia.installer.exe` | 是 | 是 | RTX 20/30/40/50 NVIDIA 显卡，想保留安装流程 |
| Windows 64 位 | `<date>-windows64.opencl.portable.zip` | 是 | 是 | AMD、Intel 或较老 NVIDIA 显卡，免安装 |
| Windows 64 位 | `<date>-windows64.opencl.installer.exe` | 是 | 是 | 想保留安装流程的 OpenCL 用户 |
| Windows 64 位 | `<date>-windows64.with-katago.portable.zip` | 是 | 是 | 没有合适 GPU 或 GPU 版本无法启动时的 CPU 兜底 |
| Windows 64 位 | `<date>-windows64.with-katago.installer.exe` | 是 | 是 | 想安装的 CPU 兜底版 |
| Windows 64 位 | `<date>-windows64.without.engine.portable.zip` | 是 | 否 | 想自己配引擎，也不想安装 |
| Windows 64 位 | `<date>-windows64.without.engine.installer.exe` | 是 | 否 | 想保留安装流程，但自己配引擎 |
| macOS Apple Silicon | `<date>-mac-apple-silicon.with-katago.dmg` | App 自带运行时 | 是 | M 系列 Mac |
| macOS Intel | `<date>-mac-intel.with-katago.dmg` | App 自带运行时 | 是 | Intel Mac |
| Linux 64 位 | `<date>-linux64.with-katago.zip` | 是 | 是 | Linux 桌面用户 |
| Linux 64 位 | `<date>-linux64.opencl.zip` | 是 | 是 | Linux + AMD/Intel GPU 用户 |
| Linux 64 位 | `<date>-linux64.nvidia.zip` | 是 | 是 | Linux + NVIDIA GPU 用户 |

一句话建议：

- RTX 20/30/40/50 NVIDIA 显卡：统一选 `windows64.nvidia.portable.zip`
- AMD、Intel 或较老 NVIDIA 显卡：选 `windows64.opencl.portable.zip`
- 没有合适 GPU，或 CUDA/OpenCL 在你电脑上无法正常启动：改用 `windows64.with-katago.portable.zip`
- RTX 40/50 默认使用 CUDA；RTX 30 系及以下可在 `KataGo 一键设置` 中按需安装 TensorRT 作为可选方案
- `KataGo 一键设置` 会检测 NVIDIA GPU / Compute Capability，再给出 TensorRT 推荐状态
- NVIDIA 驱动 `570.65` 及以上直接加载；`528.33` 至 `570.64` 首次运行会执行一次轻量真实推理探测；更旧驱动会显示明确修复提示
- GTX 10 系以前的 NVIDIA 显卡：优先使用 OpenCL 包
- 想自己管引擎：Windows 选 `without.engine.portable.zip`，想安装再选同名 `installer.exe`
- Windows 普通用户：优先选 `.portable.zip`，想保留安装流程再选同名 `.installer.exe`

### 历史 tag 说明

部分旧 tag 还会看到早期的 zip 命名或兼容包，但当前维护版公开 release 已统一成 13 个首次下载稳定资产：8 个 Windows、2 个 macOS、3 个 Linux。另提供 DirectML、OpenVINO 和四种 AMD ROCm 架构的 6 个 Windows 实验便携包，供匹配硬件的用户验证。TensorRT 的普通用户路径是软件内按需安装；Release 同时保留可选离线分卷及其 README、清单和校验文件。

### Windows 实验后端

实验包只提供免安装版，不替代上面的稳定包：

| 实验包 | 适合谁 |
| --- | --- |
| `<date>-windows64.experimental.directml.portable.zip` | Windows 10/11、支持 DirectX 12 的 GPU |
| `<date>-windows64.experimental.openvino.portable.zip` | Intel CPU、核显或受支持的 Intel NPU |
| `<date>-windows64.experimental.rocm.gfx103x.portable.zip` | AMD RDNA2 |
| `<date>-windows64.experimental.rocm.gfx110x.portable.zip` | AMD RDNA3 桌面显卡 |
| `<date>-windows64.experimental.rocm.gfx1151.portable.zip` | AMD RDNA3.5 |
| `<date>-windows64.experimental.rocm.gfx120x.portable.zip` | AMD RDNA4 |

这些包会在界面中标明实验状态。没有对应硬件时不要下载；出现兼容问题可回到 OpenCL 稳定包。

## Windows 安装

### Windows 64 位 OpenCL 免安装包

1. 下载 `windows64.opencl.portable.zip`。
2. 解压到普通目录，例如 `D:\LizzieYzy-Next`。
3. 打开解压后的目录。
4. 双击 `LizzieYzy Next OpenCL.exe`。

这是 AMD、Intel 和较老 NVIDIA 显卡的兼容路径。
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

### Windows 64 位 NVIDIA CUDA 推荐版

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
- 可在 Windows“任务管理器 -> 性能 -> GPU”查看显卡名称；RTX 20/30/40/50 选 NVIDIA 包，AMD、Intel 或较老 NVIDIA 选 OpenCL 包。

### Windows 64 位统一 NVIDIA CUDA 和 TensorRT 可选方案

RTX 20/30/40/50 统一下载 `windows64.nvidia.portable.zip`，解压后运行 `LizzieYzy Next NVIDIA.exe`；喜欢安装流程则使用同名安装器。该包使用 CUDA 12.8 + cuDNN 9.8，RTX 40/50 默认推荐 CUDA。

TensorRT 不再作为普通用户优先下载的巨大单文件包。RTX 30 系及以下用户可进入 `KataGo 一键设置`，点击 `安装 TensorRT 加速` 作为可选方案。安装界面会检测本机 NVIDIA GPU / Compute Capability，并显示推荐、可尝试、不推荐或未知状态。软件只从 KataGo / NVIDIA 官方源下载并校验；未点击安装就不会下载。需要完全离线安装的用户也可以从 Release 下载全部 `.7z.00N` 分卷，并按同名 README、清单和 SHA-256 文件校验后从 `.001` 解压。

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

- `Apple 菜单 -> 关于本机` 中显示 Apple M 系列：下载 `mac-apple-silicon.with-katago.dmg`
- 显示 Intel：下载 `mac-intel.with-katago.dmg`

### 安装步骤

1. 下载对应的 `.dmg`。
2. 打开 `.dmg`，确认安装画面右上角显示的芯片类型与你的 Mac 一致。
3. 按画面箭头，把 `LizzieYzy Next.app` 拖到右侧的“应用程序”文件夹。
4. 等待复制完成，然后在 Finder 侧边栏弹出 `LizzieYzy Next` 安装磁盘。
5. 打开 Finder 的“应用程序”，从这里启动 `LizzieYzy Next`。

不要直接双击安装磁盘里的 App。这样只是从临时挂载的 DMG 运行，并没有完成安装，
关闭或弹出安装磁盘后就无法继续从该位置打开。

### 第一次被系统拦住怎么办

当前官方 macOS release 会在发布流程里完成签名和公证。

如果你下载的是官方发布页里的当前 DMG，通常可以直接打开。
如果第一次仍被系统拦住，往往是系统缓存、安全策略或旧版本残留记录导致，可以按下面步骤处理：

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

- KataGo 版本：`v1.18.1`
- 默认权重：官方旗舰 B11 Transformer `kata1-tf3-b11c768-s11500M-d6163M.bin.gz`（界面显示“Transformer B11 · 2026-09-07”，`211,568,937` 字节，约 202 MiB）
- B11 单次判断更强、复杂局面效果更好，但搜索速度可能较慢；追求速度可在 `KataGo 一键设置` 中按需下载并切换 B10
- 旧完整包升级：请安装最新完整包；`core-update.zip` 不包含新引擎和权重

## 需要更多说明

- [发布包说明](PACKAGES.md)
- [常见问题与排错](TROUBLESHOOTING.md)
- [已验证平台](TESTED_PLATFORMS.md)
