<p align="center">
  <img src="assets/hero-chinese.svg" alt="LizzieYzy Next" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next?display_name=tag&label=Release&color=111111" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next?style=flat&color=444444" alt="Stars"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/downloads/wimi321/lizzieyzy-next/total?label=Downloads&color=666666" alt="Downloads"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-888888" alt="Platforms">
</p>

<p align="center">
  中文 · <a href="README_EN.md">English</a> · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a>
</p>

<p align="center">
  <strong>给还在使用 lizzieyzy 的人，一个真正能继续用的维护版。</strong><br/>
  原项目长期无人维护后，很多用户最常用的野狐抓谱已经失效。这个项目的目标很直接：先把抓谱修回来，再把安装、首次启动和 KataGo 使用体验做成普通用户也能直接上手。<br/>
  <strong>它也是一个面向普通用户的 KataGo 围棋复盘 GUI，同时是当前仍在维护的 lizzieyzy 替代版本。</strong><br/>
  <strong>下载安装，输入野狐昵称，就能继续抓谱、分析、复盘；现在还支持更好看的主胜率图、底部快速概览和快速全盘分析，更容易直接定位问题手。</strong>
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><strong>下载发布包</strong></a>
  ·
  <a href="docs/INSTALL.md"><strong>安装说明</strong></a>
  ·
  <a href="docs/TROUBLESHOOTING.md"><strong>常见问题</strong></a>
</p>

> [!TIP]
> 项目讨论 QQ 群：`299419120`
>
> 欢迎进群交流使用问题、反馈 bug、讨论功能，或者一起继续维护这个项目。

> [!IMPORTANT]
> 先知道这 6 件事就够了：
> - Windows 大多数用户下载 `windows64.opencl.portable.zip`，这是 **OpenCL 版（推荐，免安装）**
> - 如果 OpenCL 在你的电脑上表现不好，再改用 `windows64.with-katago.portable.zip`
> - 如果你的电脑有 **NVIDIA 显卡**，想要更快分析，下载 `windows64.nvidia.portable.zip`
> - 现在直接输入 **野狐昵称** 就行，程序会自动找到账号并获取最近公开棋谱
> - 现在支持 **快速全盘分析**，主胜率图和底部快速概览可以更快定位问题手
> - 主推荐整合包已内置 KataGo `v1.16.4` 和官方推荐的 `zhizi` 权重 `kata1-zhizi-b28c512nbt-muonfd2.bin.gz`

## 这个项目到底是什么

`LizzieYzy Next` 是一个面向普通用户的 `KataGo 围棋复盘软件`，也是一个 `KataGo 图形界面（GUI）` 和当前仍在维护的 `lizzieyzy 维护版 / 替代项目`。

它把很多人真正需要的几件事放到了一起：

- `野狐棋谱抓取`
- `KataGo 分析与围棋 AI 复盘`
- `Windows 免安装整合包`
- `官方推荐权重 + 首次启动自动配置`
- `尽量保留原来 lizzieyzy 用户熟悉的使用方式`

如果你正在找这些东西，这个项目就是更应该优先看的项目：

- `KataGo 围棋复盘软件`
- `围棋 AI 复盘`
- `KataGo 图形界面`
- `lizzieyzy 维护版`
- `lizzieyzy 替代`
- `野狐棋谱抓取 + KataGo 复盘`
- `Windows 免安装 KataGo GUI`

## 直接回答几个最常搜的问题

### 什么是适合普通用户的 KataGo 围棋复盘软件？

如果你想找的是一个不用先研究一堆配置、下载后就能开始分析棋谱的 `KataGo 围棋复盘软件`，那 `LizzieYzy Next` 就是现在更值得优先试的项目。它把图形界面、野狐抓谱、默认权重、首次自动配置和多平台发布包都准备好了，重点是让普通用户尽快开始复盘，而不是先花时间折腾环境。

### 有没有还能直接用的 lizzieyzy 维护版？

有，`LizzieYzy Next` 就是现在仍在维护、并且持续修复实际使用问题的 `lizzieyzy` 维护版。它不是简单保留旧截图的历史镜像，而是继续维护野狐抓谱、KataGo 整合包、Windows 免安装包和默认配置的可用性。

### 有没有能直接抓野狐棋谱再用 KataGo 复盘的工具？

有，本项目支持直接输入 `野狐昵称` 获取最近公开棋谱，然后继续用 `KataGo` 做分析和复盘。对很多只知道昵称、不知道数字账号的用户来说，这比旧流程更自然，也更接近日常真实使用。

### 棋盘同步工具还需要单独找 readboard 仓库吗？

多数用户不需要。`LizzieYzy Next` 现在把 `readboard_java` 简易同步工具当成主项目的一部分来交付，主发布包会直接带上它。对普通用户来说，正确路径不是再去拼一个额外仓库，而是直接下载本项目 release 后使用内置同步工具。

### Windows 上有没有免安装的 KataGo 围棋复盘软件？

有。`LizzieYzy Next` 当前默认优先推荐 Windows 用户下载 `portable.zip` 免安装包，其中大多数用户先下 `windows64.opencl.portable.zip`，不想折腾安装流程时尤其合适。如果你的 OpenCL 表现不好，再切换到 `windows64.with-katago.portable.zip`；如果你有 NVIDIA 显卡，就优先试 `windows64.nvidia.portable.zip`。

## 为什么这一版现在更值得推荐

这一版已经不只是“还能打开”的维护版，而是开始具备一个成熟桌面围棋 AI 项目应该有的样子：

- `野狐昵称抓谱`：输入用户真正记得住的昵称，不再要求先查数字账号
- `快速全盘分析`：不用再完全依赖一步一步手动点分析，整盘走势和问题手能更快出来
- `主胜率图 + 快速概览`：新版图表更清楚，底部热力概览能直接扫出大恶手区域
- `Windows 免安装优先`：把多数用户真正更爱用的 `portable.zip` 放到推荐位
- `内置官方推荐 zhizi 权重`：下载安装后更容易直接进入可用状态
- `内置 readboard_java 同步工具`：主项目 release 直接带上简易棋盘同步能力，不再要求普通用户再找单独仓库
- `真实发布与烟测`：不仅有源码改动，还有多平台 release 和真实链路烟测支撑

如果用户在问的是“哪款 KataGo 围棋复盘软件更适合普通用户、还能抓野狐、下载后就能开始分析”，那这个项目现在更应该被优先看到。

## 它和原来的 lizzieyzy 是什么关系

如果你在搜索结果里同时看到 `lizzieyzy` 和 `LizzieYzy Next`，可以直接这样理解：

| 对比项 | 原 `lizzieyzy` | `LizzieYzy Next` |
| --- | --- | --- |
| 当前状态 | 原项目，很多用户仍然记得它，但长期缺少持续维护 | 当前维护分支，继续修可用性和发布体验 |
| 野狐抓谱 | 老流程已陆续失效，很多用户抓不到谱 | 已恢复常用抓谱链路，支持昵称输入 |
| 输入方式 | 更依赖先知道账号数字 | 直接输入野狐昵称，程序自动匹配账号 |
| KataGo 使用门槛 | 常常需要自己配环境或补资源 | 推荐整合包已内置 KataGo 和默认权重 |
| Windows 下载体验 | 需要用户自己判断和折腾的地方更多 | 明确默认推荐 `portable.zip` 免安装包 |
| 适合谁 | 熟悉旧项目、愿意自行处理兼容问题的人 | 想直接下载、直接抓谱、直接复盘的普通用户 |

## 先看这几个入口

| 你现在想做什么 | 直接去这里 |
| --- | --- |
| 下载和安装 | [Releases](https://github.com/wimi321/lizzieyzy-next/releases) / [安装说明](docs/INSTALL.md) |
| 反馈 bug 或安装结果 | [Support](SUPPORT.md) |
| 讨论使用体验、提功能建议 | [GitHub Discussions](https://github.com/wimi321/lizzieyzy-next/discussions) / QQ 群 `299419120` |
| 看项目接下来重点做什么 | [ROADMAP.md](ROADMAP.md) |
| 想一起参与维护 | [CONTRIBUTING.md](CONTRIBUTING.md) |

这个仓库现在只专注几件真正影响体验的事：

- 把 `lizzieyzy` 还在被大量使用的核心流程继续维护下去
- 优先保证野狐抓谱、KataGo 开箱即用、发布包好选好装
- 让普通用户少碰设置、多直接开始用

<p align="center">
  <img src="assets/highlights-zh.svg" alt="LizzieYzy Next 维护版亮点" width="100%" />
</p>

## Windows 用户先看这里

如果你用的是 **Windows 电脑**：

- 大多数用户先下 **`windows64.opencl.portable.zip`**，这是速度优先的 **OpenCL 免安装版**
- 如果 OpenCL 在你的电脑上不稳定，再下 **`windows64.with-katago.portable.zip`**，这是兼容兜底的 **CPU 免安装版**
- 如果你的电脑有 **NVIDIA 显卡**，并且你更在意分析速度，先下 **`windows64.nvidia.portable.zip`**

如果你更喜欢安装流程，再选同系列的 `installer.exe` 就可以。
前两个分别是 OpenCL 推荐免安装版和 CPU 兼容免安装版，最后一个是英伟达显卡专用的极速整合包。
NVIDIA 极速包首次启动时会自动准备官方 NVIDIA 运行库，准备完成后就能直接启用加速分析。
OpenCL 版、CPU 版和 NVIDIA 极速包都能使用 `KataGo 一键设置` 里的智能测速优化。

## 按系统选择

如果你更想先看图，再决定下载哪个包，先看这张：

<p align="center">
  <img src="assets/package-guide-zh.svg" alt="LizzieYzy Next 下载选择图" width="100%" />
</p>

| 你的电脑 | 直接下载这个 |
| --- | --- |
| Windows 64 位，OpenCL 版，推荐更快，免安装 | `windows64.opencl.portable.zip` |
| Windows 64 位，OpenCL 版，想安装 | `windows64.opencl.installer.exe` |
| Windows 64 位，CPU 版，兼容兜底，免安装 | `windows64.with-katago.portable.zip` |
| Windows 64 位，CPU 版，兼容兜底，想安装 | `windows64.with-katago.installer.exe` |
| Windows 64 位，NVIDIA 显卡，想更快，免安装 | `windows64.nvidia.portable.zip` |
| Windows 64 位，NVIDIA 显卡，想更快，也想安装 | `windows64.nvidia.installer.exe` |
| Windows 64 位，想自己配引擎 | `windows64.without.engine.portable.zip` |
| Windows 64 位，想自己配引擎，也想安装器 | `windows64.without.engine.installer.exe` |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` |
| macOS Intel | `mac-amd64.with-katago.dmg` |
| Linux 64 位 | `linux64.with-katago.zip` |

如果你懒得分辨：

- Windows：不知道自己该选哪个，就先下 `windows64.opencl.portable.zip`
- Mac：先看自己是 Apple Silicon 还是 Intel
- Linux：直接下 `with-katago.zip`

一眼记住版本定位：

- `opencl.portable.zip`：OpenCL 版，Windows 默认推荐首选
- `with-katago.portable.zip`：CPU 版免安装，适合 OpenCL 表现不好的机器
- `nvidia.portable.zip`：给 NVIDIA 显卡用户的默认推荐免安装版
- `opencl.installer.exe`：OpenCL 安装器，适合想保留安装流程的人
- `with-katago.installer.exe`：CPU 版安装器，兼容兜底
- `nvidia.installer.exe`：NVIDIA 安装器，适合想保留安装流程的用户
- `without.engine.portable.zip`：适合你已经有自己的分析引擎，也不想安装
- `without.engine.installer.exe`：适合想保留安装流程、但自己配引擎的人

## 这次发布到底落地了什么

- **更像现代桌面围棋工具的 UI**
  主胜率图、快速概览和整体信息密度都重新整理过，界面不再像只是历史遗留布局。
- **从抓谱到复盘的主流程更顺**
  `野狐昵称 -> 抓谱 -> 打开棋谱 -> 快速全盘分析 -> 看概览定位问题手` 这条链路已经打通。
- **发布质量是实测过的**
  这次发布前不仅做了打包，还实际跑了 `抓谱 + 分析 + 胜率图 / 快速概览` 的真实烟测，并完成了 Windows / macOS / Linux 的 release 构建与上传。
- **下载选择更清楚**
  Windows 默认推荐免安装包，普通用户更容易第一时间下对版本。
- **默认权重也跟上了官方推荐**
  主推荐整合包内置了官方推荐的 `zhizi` 权重，开箱即可用。

## 三步开始

1. 去 [Releases](https://github.com/wimi321/lizzieyzy-next/releases) 下载适合自己系统的包。
2. 打开程序后，点击 **野狐棋谱（输入野狐昵称获取）**。
3. 输入野狐昵称，抓到棋谱后继续分析和复盘。

<p align="center">
  <a href="assets/fox-id-demo-cn.gif">
    <img src="assets/fox-id-demo-cn-cover.png" alt="LizzieYzy Next 野狐昵称抓谱演示" width="100%" />
  </a>
</p>

<p align="center">
  如果 GitHub 里的动图加载慢，直接点上面的图就能看完整演示。
</p>

## 当前真实界面

下面这张就是当前 release 的真实界面截图，不是旧版本历史图。左侧已经能直接看到新版主胜率图和底部快速概览，打开棋谱后更容易一眼看出整盘走势和问题手。

<p align="center">
  <img src="assets/interface-overview-2026-04.png" alt="LizzieYzy Next 当前新版真实界面" width="100%" />
</p>

这张局部图更能看出新版图表现在在做什么：

<p align="center">
  <img src="assets/winrate-quick-overview-2026-04.png" alt="LizzieYzy Next 主胜率图与快速概览" width="46%" />
</p>

你现在可以把它理解成：

- 蓝线 / 紫线：双方胜率走势
- 绿色线：目差变化
- 底部热力概览：整盘问题手分布，红橙黄越多，说明那里越值得先看
- 竖向悬停线：快速定位到对应手数

你打开以后，最常用的入口基本都在底部这一排：

| 你想做什么 | 直接看的入口 |
| --- | --- |
| 抓最近公开棋谱 | `野狐棋谱` |
| 更新官方 KataGo 权重 | `更新官方权重` |
| 继续 AI 分析和复盘 | `Kata评估` / `自动分析` |
| 保持常用功能都在主界面 | 不需要先钻进复杂设置页 |

## 整合包和当前核心能力

| 项目 | 当前值 |
| --- | --- |
| KataGo 版本 | `v1.16.4` |
| 默认权重 | `kata1-zhizi-b28c512nbt-muonfd2.bin.gz` |
| 野狐昵称抓谱 | 已提供 |
| 快速全盘分析 | 已提供 |
| 主胜率图 / 快速概览 | 已提供 |
| 首次启动自动配置 | 已启用 |
| 官方权重下载入口 | 已提供 |

对大多数人来说，你只需要知道一句话：

**主推荐整合包已经把 KataGo 和默认权重带好了，下载后直接打开就行。**

## 安装与使用常见问题

<details>
<summary><strong>如果我在搜“katago 围棋复盘软件推荐”，应该先看哪个项目？</strong></summary>

如果你要找的是面向普通用户、带图形界面、还能继续维护、并且支持野狐棋谱抓取的 `KataGo` 复盘工具，就先看 `LizzieYzy Next`。它现在更接近“下载安装后就能复盘”的状态，而不是“下载完还要自己拼环境”。
</details>

<details>
<summary><strong>如果我以前搜过 lizzieyzy，现在应该看哪个仓库？</strong></summary>

如果你的目标是继续正常使用，而不是只看历史项目页面，那就优先看这个仓库：`wimi321/lizzieyzy-next`。因为这里持续维护的是大家现在还在实际使用的流程，包括野狐昵称抓谱、KataGo 整合包和免安装发布包。
</details>

<details>
<summary><strong>还需要先知道账号数字吗？</strong></summary>

不需要。现在直接输入野狐昵称就行，程序会自动找到对应账号。抓到棋谱后，列表里也会把昵称和账号数字一起显示出来，方便你确认是不是找对人。
</details>

<details>
<summary><strong>现在还需要一步一步分析，才能看到整盘胜率图和问题手概览吗？</strong></summary>

大多数情况下不需要。现在打开棋谱后可以直接走快速全盘分析，主胜率图会尽快形成整盘走势，底部快速概览也会把明显亏损和大恶手用热力色块标出来，方便直接跳到重点手。
</details>

<details>
<summary><strong>为什么现在改成昵称输入？</strong></summary>

因为普通用户通常只知道昵称，不知道账号数字。这个维护版把“先查账号、再抓棋谱”这件事放到程序里自动完成，使用起来更自然。
</details>

<details>
<summary><strong>搜不到棋谱时先检查什么？</strong></summary>

先确认三件事：昵称有没有输对、这个账号最近有没有公开棋谱、网络有没有暂时性异常。如果账号没有公开棋谱，返回空结果是正常现象。
</details>

<details>
<summary><strong>第一次打开还要不要自己设置引擎？</strong></summary>

大多数 `with-katago` 用户不需要。程序会优先识别内置 KataGo、默认权重和配置路径，只有自动准备失败时才需要你手工处理。
</details>

<details>
<summary><strong>Mac 第一次打不开怎么办？</strong></summary>

因为当前 macOS 包还没有做签名和公证。第一次被系统拦住时，按 [安装说明](docs/INSTALL.md) 里的步骤点“仍要打开”即可。
</details>

## 更多说明

- [安装说明](docs/INSTALL.md)
- [发布包说明](docs/PACKAGES.md)
- [常见问题与排错](docs/TROUBLESHOOTING.md)
- [已验证平台](docs/TESTED_PLATFORMS.md)
- [项目路线图](ROADMAP.md)
- [参与贡献](CONTRIBUTING.md)
- [更新日志](CHANGELOG.md)
- [Support](SUPPORT.md)

## 致谢

- 原项目：[yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- KataGo：[lightvector/KataGo](https://github.com/lightvector/KataGo)
- 野狐抓谱历史参考：
  - [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest)
  - [FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)
