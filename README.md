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
  <strong>下载安装，输入野狐昵称，就能继续抓谱、分析、复盘。</strong>
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
> 先知道这 3 件事就够了：
> - Windows 大多数用户下载 `windows64.with-katago.installer.exe`
> - 现在直接输入 **野狐昵称** 就行，程序会自动找到账号并获取最近公开棋谱
> - 主推荐整合包已内置 KataGo，第一次打开会优先自动完成配置

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

## Windows 用户先下载这个

如果你用的是 **Windows 电脑**，先下载：

**`windows64.with-katago.installer.exe`**

这是最省事的版本。下载后双击安装，装好就能直接打开使用。

## 按系统选择

如果你更想先看图，再决定下载哪个包，先看这张：

<p align="center">
  <img src="assets/package-guide-zh.svg" alt="LizzieYzy Next 下载选择图" width="100%" />
</p>

| 你的电脑 | 直接下载这个 |
| --- | --- |
| Windows 64 位 | `windows64.with-katago.installer.exe` |
| Windows 64 位，想免安装 | `windows64.with-katago.portable.zip` |
| Windows 64 位，想自己配引擎，也想安装器 | `windows64.without.engine.installer.exe` |
| Windows 64 位，想自己配引擎 | `windows64.without.engine.portable.zip` |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` |
| macOS Intel | `mac-amd64.with-katago.dmg` |
| Linux 64 位 | `linux64.with-katago.zip` |

如果你懒得分辨：

- Windows：不知道怎么选，就下 `installer.exe`
- Mac：先看自己是 Apple Silicon 还是 Intel
- Linux：直接下 `with-katago.zip`

一眼记住版本定位：

- `installer.exe`：最适合大多数 Windows 用户
- `portable.zip`：适合不想安装、只想解压就跑
- `without.engine.installer.exe`：适合想保留安装流程、但自己配引擎的人
- `without.engine.portable.zip`：适合你已经有自己的分析引擎，也不想安装

## 这个维护版为什么值得关注

- **先修好最影响使用的功能**
  原版已经有不少人抓不到野狐棋谱了，这个维护版先把最常用的抓谱链路恢复到可用状态。
- **把用户本来就知道的信息用起来**
  现在直接输入野狐昵称，程序自动去匹配账号，不再要求你先知道数字账号。
- **保留熟悉的使用方式，同时少折腾**
  界面仍然是大家熟悉的 lizzieyzy 路线，但整合包把 KataGo、默认权重和首次自动配置都尽量准备好了。

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

下面这张就是现在这个维护版的真实界面截图。底部可以直接看到 **野狐棋谱**、**更新官方权重** 等入口，不再是旧版本历史截图。

<p align="center">
  <img src="assets/interface-overview.png" alt="LizzieYzy Next 当前真实界面" width="100%" />
</p>

你打开以后，最常用的入口基本都在底部这一排：

| 你想做什么 | 直接看的入口 |
| --- | --- |
| 抓最近公开棋谱 | `野狐棋谱` |
| 更新官方 KataGo 权重 | `更新官方权重` |
| 继续 AI 分析和复盘 | `Kata评估` / `自动分析` |
| 保持常用功能都在主界面 | 不需要先钻进复杂设置页 |

## 整合包已经带好什么

| 项目 | 当前值 |
| --- | --- |
| KataGo 版本 | `v1.16.4` |
| 默认权重 | `g170e-b20c256x2-s5303129600-d1228401921.bin.gz` |
| 首次启动自动配置 | 已启用 |
| 官方权重下载入口 | 已提供 |

对大多数人来说，你只需要知道一句话：

**主推荐整合包已经把 KataGo 和默认权重带好了，下载后直接打开就行。**

## 常见问题

<details>
<summary><strong>还需要先知道账号数字吗？</strong></summary>

不需要。现在直接输入野狐昵称就行，程序会自动找到对应账号。抓到棋谱后，列表里也会把昵称和账号数字一起显示出来，方便你确认是不是找对人。
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
