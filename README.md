<p align="center">
  <img src="assets/hero.svg" alt="LizzieYzy Next-FoxUID" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next-foxuid?display_name=tag&label=Release&color=1B4D3E" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/actions/workflows/ci.yml"><img src="https://github.com/wimi321/lizzieyzy-next-foxuid/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next-foxuid?style=flat&color=7F4F24" alt="Stars"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><img src="https://img.shields.io/github/downloads/wimi321/lizzieyzy-next-foxuid/total?label=Downloads&color=2F4858" alt="Downloads"></a>
  <a href="LICENSE.txt"><img src="https://img.shields.io/badge/License-GPL%20v3-E7A23B" alt="License"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-4A5D23" alt="Platforms">
</p>

<p align="center">
  中文 · <a href="README_EN.md">English</a> · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a>
</p>

<p align="center">
  <strong>继续维护的 LizzieYzy 版本，重点把原版已经失效的野狐棋谱同步恢复到可用状态。</strong><br/>
  现在统一通过“野狐ID”获取最新公开棋谱，并继续提供更清晰的 Windows / macOS / Linux 发布包。
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases">下载发布包</a>
  ·
  <a href="#如果你只想马上开始">现在开始</a>
  ·
  <a href="#下载哪个包">下载哪个包</a>
  ·
  <a href="#快速开始">快速开始</a>
  ·
  <a href="#文档与支持">文档与支持</a>
  ·
  <a href="#参与维护">参与维护</a>
</p>

> [!IMPORTANT]
> 原版 LizzieYzy 的野狐棋谱同步流程已经失效。本维护版已修复该功能，界面入口统一为“野狐棋谱（输入野狐ID获取）”。

## 这个维护版解决了什么

`LizzieYzy Next-FoxUID` 是原 `lizzieyzy` 的持续维护分支。

这个仓库不是另起炉灶做一个新项目，而是在原项目基础上，把大家最常用、最需要的功能继续维护下去。

核心就几件事：

- 能正常安装
- 能同步野狐公开棋谱
- 能继续用 KataGo 分析

如果你关心的是下面这些事情，这个维护版就是为了解决它们：

- 原版已经失效的野狐棋谱同步，这里已经恢复可用
- 界面和文档统一改成“野狐ID”，不再混用 UID、用户名等说法
- 发布包按系统和用途重新整理，普通用户更容易选对
- 安装、排错、维护资料集中在这个仓库里，后续继续更新

## 如果你只想马上开始

| 你现在要做什么 | 直接去这里 |
| --- | --- |
| 下载后直接用 | 去 [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases)，优先选 `with-katago` 包 |
| 自己换引擎 | 看 [发布包说明](docs/PACKAGES.md)，选 `without.engine` 包 |
| 确认某个平台有没有人实测过 | 看 [已验证平台](docs/TESTED_PLATFORMS.md) |
| 安装或首次启动遇到问题 | 看 [安装指南](docs/INSTALL.md) 和 [排错指南](docs/TROUBLESHOOTING.md) |
| 提交安装成功 / 失败反馈 | 用 [Installation Report 模板](https://github.com/wimi321/lizzieyzy-next-foxuid/issues/new?template=installation_report.yml) |
| 提交 bug 或功能建议 | 用 [Issues](https://github.com/wimi321/lizzieyzy-next-foxuid/issues) 或 [Discussions](https://github.com/wimi321/lizzieyzy-next-foxuid/discussions) |

## 项目截图

界面里原来容易让人困惑的 UID 入口，已经统一改成“野狐棋谱（输入野狐ID获取）”。其他常用分析功能仍然保留原来的使用方式。

![LizzieYzy Next-FoxUID Screenshot](screenshot.png)

## 下载哪个包

> [!TIP]
> 大多数用户直接选 `with-katago` 就行。只有在你明确知道自己要自己配引擎时，再选 `without.engine`。

<p align="center">
  <img src="assets/package-guide.svg" alt="LizzieYzy Next-FoxUID Package Guide" width="100%" />
</p>

| 系统 | 推荐包 | 是否内置 Java | 是否内置 KataGo | 适合谁 |
| --- | --- | --- | --- | --- |
| Windows 64 位 | `windows64.with-katago.zip` | 是 | 是 | 想下载后直接用 |
| Windows 64 位 | `windows64.without.engine.zip` | 是 | 否 | 想自己换引擎或轻量使用 |
| Windows 32 位 | `windows32.without.engine.zip` | 否 | 否 | 老机器或兼容场景 |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` | App 自带运行时 | 是 | M 系列 Mac 用户 |
| macOS Intel | `mac-amd64.with-katago.dmg` | App 自带运行时 | 是 | Intel Mac 用户 |
| Linux 64 位 | `linux64.with-katago.zip` | 是 | 是 | Linux 桌面用户 |
| Linux / Intel Mac 进阶用户 | `Macosx.amd64.Linux.amd64.without.engine.zip` | 否 | 否 | 想自己配引擎 |

相关入口：

- [发布页](https://github.com/wimi321/lizzieyzy-next-foxuid/releases)
- [发布包说明](docs/PACKAGES.md)
- [已验证平台](docs/TESTED_PLATFORMS.md)

## 实际使用流程

<p align="center">
  <img src="assets/start-flow.svg" alt="LizzieYzy Next-FoxUID Start Flow" width="100%" />
</p>

大多数用户实际使用时，基本就是这几步：

1. 下载适合自己系统的包，优先选 `with-katago`
2. 启动程序，打开“野狐棋谱（输入野狐ID获取）”
3. 输入纯数字的野狐ID，获取最新公开棋谱
4. 继续用内置或自定义 KataGo 做分析和复盘

## 快速开始

1. 去 [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases) 下载适合你系统的包。
2. 如果你只是想尽快开始，优先选 `with-katago` 整合包。
3. 启动程序后，找到“野狐棋谱（输入野狐ID获取）”。
4. 输入纯数字的野狐ID，获取最新公开棋谱。
5. 如果系统拦截首次启动，先看 [安装指南](docs/INSTALL.md) 和 [排错指南](docs/TROUBLESHOOTING.md)。

## 你可以用它做什么

| 使用场景 | 当前可用能力 |
| --- | --- |
| 获取棋谱 | 通过野狐ID抓取最新公开棋谱 |
| 对局复盘 | 鹰眼分析、胜率波动、目差波动、失误手统计 |
| 快速分析 | 使用 KataGo analysis 模式并行分析整盘棋谱 |
| 批量处理 | 支持多份棋谱批量分析 |
| 引擎对比 | 支持双引擎模式和引擎对局 |
| 局部研究 | 支持死活题分析与局部框架生成 |
| 其他保留能力 | 棋盘同步、形势判断等原项目常用功能 |

## 和原版相比

| 项目 | 原版 LizzieYzy | Next-FoxUID |
| --- | --- | --- |
| 野狐棋谱同步 | 对很多用户已经失效 | 已修复 |
| 输入方式 | UID / 用户名说法混杂 | 统一为野狐ID |
| 发布包 | 不够容易选 | 已按系统和用途重新整理 |
| macOS 发布 | 历史包型较混杂 | 以 `.dmg` 为主，区分 Apple Silicon / Intel |
| Windows 64 位 | 包策略不够清楚 | 同时保留 `with-katago` 和 `without.engine` |
| 项目维护 | 基本停滞 | 按继续维护的分支推进 |

## 如果你从原版迁移过来

如果你原来就在用 LizzieYzy，最直接的变化就是这些：

- 野狐同步入口现在统一叫“野狐棋谱（输入野狐ID获取）”
- 获取方式改成野狐ID，不再走旧的用户名检索逻辑
- Windows 64 位继续保留 `with-katago` 和 `without.engine` 两种包
- macOS 现在以 `.dmg` 为主，不再额外保留旧的 `.app.zip`
- 这个仓库会继续发包、修文档、收安装反馈，不是临时修一下就停

## 当前整合包内置内容

| 项目 | 当前值 |
| --- | --- |
| KataGo 版本 | `v1.16.4` |
| 默认权重 | `g170e-b20c256x2-s5303129600-d1228401921.bin.gz` |

路径说明：

- Windows / Linux 整合包权重：`Lizzieyzy/weights/default.bin.gz`
- macOS 整合包权重：`LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`
- macOS 如果 Finder 里只看到一个 `.app`，这是正常现象。右键应用，选择“显示包内容”即可查看。

## 文档与支持

| 你需要什么 | 入口 |
| --- | --- |
| 安装说明 | [安装指南](docs/INSTALL.md) |
| 排查启动或运行问题 | [常见问题与排错](docs/TROUBLESHOOTING.md) |
| 看懂每个发布包 | [发布包说明](docs/PACKAGES.md) |
| 看哪些平台有人实测 | [已验证平台](docs/TESTED_PLATFORMS.md) |
| 求助或反馈 | [获取帮助](SUPPORT.md) |
| 从源码构建或参与开发 | [开发指南](docs/DEVELOPMENT.md) |
| 查看更新历史 | [更新日志](CHANGELOG.md) |
| 了解维护策略 | [维护说明](docs/MAINTENANCE.md) |
| 发版时自查 | [发布检查清单](docs/RELEASE_CHECKLIST.md) |

## 常见问题

<details>
<summary><strong>为什么不再支持用户名搜索？</strong></summary>

因为这个维护分支已经统一按野狐ID工作。这样对普通用户更直观，对排查问题也更稳定。
</details>

<details>
<summary><strong>macOS 为什么只提供 dmg，不再提供 app.zip？</strong></summary>

因为大多数用户真正需要的是“能双击安装”的包，而不是再多下载一个压缩版。现在发布页优先保留更直观的 `.dmg`。
</details>

<details>
<summary><strong>Windows 64 为什么同时有 with-katago 和 without.engine？</strong></summary>

因为两类用户需求完全不同：一类想开箱即用，一类想自己管理引擎。现在这两个需求都明确分开提供。
</details>

<details>
<summary><strong>这个仓库和原作者是什么关系？</strong></summary>

这是基于原项目继续维护的分支。目标不是替代原作者，而是在原项目停更后，把已经失效的功能和发布方式继续维护下去。
</details>

<details>
<summary><strong>如果我的平台还没有实机验证记录怎么办？</strong></summary>

可以先看 [已验证平台](docs/TESTED_PLATFORMS.md) 了解当前状态，再按 [安装指南](docs/INSTALL.md) 尝试安装。如果你验证成功或失败，都欢迎提交 Installation Report，能直接帮助这个项目变得更稳。
</details>

## 当前维护重点

接下来会继续推进这些事情：

- 补更多 Windows / Linux / Intel Mac 的真实安装记录
- 持续跟进野狐抓谱兼容性反馈
- 继续优化首页、截图、演示素材和发布页说明
- 补完整的日文 / 韩文安装与排错文档
- 根据真实用户反馈继续压缩新手上手成本

## 参与维护

欢迎通过 issue、discussion 或 pull request 参与维护。

当前最有帮助的贡献方向：

- 补 Windows / Linux / Intel Mac 的真实安装反馈
- 提交野狐抓谱兼容性反馈
- 优化文档、翻译和界面文案
- 修复打包脚本、引擎路径或发布流程问题
- 提交聚焦的小型代码修复

入口：

- [贡献指南](CONTRIBUTING.md)
- [行为准则](CODE_OF_CONDUCT.md)
- [安全说明](SECURITY.md)
- [获取帮助](SUPPORT.md)
- [已验证平台](docs/TESTED_PLATFORMS.md)
- [问题反馈](https://github.com/wimi321/lizzieyzy-next-foxuid/issues)
- [讨论区](https://github.com/wimi321/lizzieyzy-next-foxuid/discussions)

## 致谢

- 原项目：[yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- 上游基础：[featurecat/lizzie](https://github.com/featurecat/lizzie)
- KataGo：[lightvector/KataGo](https://github.com/lightvector/KataGo)
- 相关 jar 仓库：
  - [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest)
  - [yzyray/testbuffer](https://github.com/yzyray/testbuffer)
  - [yzyray/captureTsumeGo](https://github.com/yzyray/captureTsumeGo/blob/main/README.md)

## 许可证

本项目沿用原项目许可证，详见 [LICENSE.txt](LICENSE.txt)。
