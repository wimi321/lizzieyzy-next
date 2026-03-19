<p align="center">
  <img src="assets/hero.svg" alt="LizzieYzy Next-FoxUID" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next-foxuid?display_name=tag&label=Release&color=1B4D3E" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next-foxuid?style=flat&color=7F4F24" alt="Stars"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><img src="https://img.shields.io/github/downloads/wimi321/lizzieyzy-next-foxuid/total?label=Downloads&color=2F4858" alt="Downloads"></a>
  <a href="LICENSE.txt"><img src="https://img.shields.io/badge/License-GPL%20v3-E7A23B" alt="License"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-4A5D23" alt="Platforms">
</p>

<p align="center">
  中文 · <a href="README_EN.md">English</a> · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a>
</p>

<p align="center">
  <strong>把原版已经失效的野狐棋谱同步功能，真正修回来了。</strong><br/>
  这是一个持续维护的 LizzieYzy 分支，支持输入野狐ID获取最新公开棋谱，并提供更实用的 KataGo 多平台发布包。
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases">下载发布包</a>
  ·
  <a href="#快速开始">快速开始</a>
  ·
  <a href="docs/INSTALL.md">安装教程</a>
  ·
  <a href="docs/TROUBLESHOOTING.md">排错指南</a>
  ·
  <a href="#下载哪个包">下载建议</a>
  ·
  <a href="#参与维护">参与维护</a>
</p>

> [!IMPORTANT]
> 原版 LizzieYzy 的野狐棋谱同步流程已经失效。本维护版已修复该功能，界面入口统一为“野狐棋谱（输入野狐ID获取）”。

## 这个项目为什么值得关注

| 你关心的事 | 这个维护版给你的结果 |
| --- | --- |
| 原版野狐棋谱同步已经失效 | 改成直连 Fox H5 API，重新可用 |
| 用户不知道什么是 UID | 界面和文档统一改成“野狐ID” |
| 发布包不好选 | 现在按系统整理，下载决策更清楚 |
| 新手装引擎太麻烦 | Windows / macOS / Linux 都有可直接上手的整合包 |
| 项目没人维护 | 这个分支就是为继续维护而建的 |

## 第一眼先看这些亮点

- **野狐棋谱同步已修复**：原项目失效的同步流程已经替换成新的可用实现。
- **输入野狐ID即可获取**：不再走容易混淆的用户名检索逻辑。
- **整合包更实用**：Windows 64 和 Linux 64 提供内置 Java + KataGo 的版本，Mac 提供 `.dmg` 安装包。
- **保留 LizzieYzy 的强项**：鹰眼分析、闪电分析、批量分析、棋盘同步、双引擎模式、死活题分析等功能继续保留。
- **继续维护，不只修一次**：这个仓库的目标不是“补个洞就走”，而是把它做成真正可长期使用的分支。

## 项目截图

![LizzieYzy Next-FoxUID Screenshot](screenshot.png)

## 下载哪个包

| 系统 | 推荐包 | 是否内置 Java | 是否内置 KataGo | 适合谁 |
| --- | --- | --- | --- | --- |
| Windows 64 位 | `windows64.with-katago.zip` | 是 | 是 | 想下载后直接用 |
| Windows 64 位 | `windows64.without.engine.zip` | 是 | 否 | 想自己换引擎或轻量使用 |
| Windows 32 位 | `windows32.without.engine.zip` | 否 | 否 | 老机器或兼容场景 |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` | App 自带运行时 | 是 | M 系列 Mac 用户 |
| macOS Intel | `mac-amd64.with-katago.dmg` | App 自带运行时 | 是 | Intel Mac 用户 |
| Linux 64 位 | `linux64.with-katago.zip` | 是 | 是 | Linux 桌面用户 |
| Linux / Intel Mac 进阶用户 | `Macosx.amd64.Linux.amd64.without.engine.zip` | 否 | 否 | 想自己配引擎 |

发布页：<https://github.com/wimi321/lizzieyzy-next-foxuid/releases>

## 快速开始

1. 进入 [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases) 下载适合你系统的包。
2. 如果你想最省事，优先选 `with-katago` 整合包。
3. 启动程序后，使用“野狐棋谱（输入野狐ID获取）”功能。
4. 输入野狐ID，即可抓取最新公开棋谱。

## 详细文档

- [安装指南](docs/INSTALL.md)
- [常见问题与排错](docs/TROUBLESHOOTING.md)
- [发布包说明](docs/PACKAGES.md)

## 和原版相比，变化在哪里

| 项目 | 原 LizzieYzy | LizzieYzy Next-FoxUID |
| --- | --- | --- |
| 野狐棋谱同步 | 已不好用 | 已修复 |
| 获取方式 | 旧流程、容易混淆 | 直接输入野狐ID |
| 发布包策略 | 不够清晰 | 按系统整理，下载更直观 |
| Windows 64 | 有时还要自己补环境 | 提供内置 Java + KataGo 的整合包 |
| macOS | 安装体验不统一 | 直接提供 `.dmg` 安装包 |
| 维护状态 | 基本停更 | 持续维护 |

## 适合谁

- 想继续使用 LizzieYzy，但被原版野狐同步问题卡住的人
- 想拿到一个“下载就能复盘”的 KataGo 图形界面的人
- 教练、家长、棋社管理者，需要更稳定地抓取野狐公开棋谱的人
- 熟悉引擎配置，想自己换 KataGo 权重或其他 GTP 引擎的人

## 功能亮点

| 模块 | 说明 |
| --- | --- |
| 野狐棋谱同步 | 通过野狐ID获取最新公开棋谱 |
| 鹰眼分析 | 图表化展示吻合度、胜率波动、目差波动、失误手 |
| 闪电分析 | 使用 KataGo analysis 模式并行分析整盘棋谱 |
| 批量分析 | 支持多份棋谱批量处理 |
| 形势判断 | 支持基于 KataGo 的粗略领地 / 局势判断 |
| 棋盘同步 | 保留原项目 Windows / Java 同步方案 |
| 双引擎模式 | 同时加载两个引擎做对比分析 |
| 死活题分析 | 支持局部抓题和辅助框架生成 |
| 引擎对局 | 支持引擎间单盘或多盘对局 |

## 权重和引擎文件在哪里

- Windows / Linux 整合包：`Lizzieyzy/weights/default.bin.gz`
- macOS 整合包：`LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`
- macOS 如果 Finder 里只看到一个 `.app`，这是正常的。右键应用，选择“显示包内容”即可查看。
- 当前整合版内置的 KataGo 程序版本是 `v1.16.4`
- 当前默认内置权重是 `g170e-b20c256x2-s5303129600-d1228401921.bin.gz`

## 常见问题

<details>
<summary><strong>为什么不再支持用户名搜索？</strong></summary>

因为“用户名”对普通用户来说既不稳定也不直观，而且原来的同步流程已经失效。现在统一改成输入野狐ID，减少误解，也更方便定位问题。
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
<summary><strong>这个项目和原作者是什么关系？</strong></summary>

这是基于原项目继续维护的分支，保留原项目的大量能力，同时专门修复已失效的功能，并整理更适合现在用户的发布方式。
</details>

## 路线图

- [x] 修复野狐棋谱同步
- [x] 改成野狐ID获取
- [x] 恢复多平台发布包
- [x] 补齐 Intel Mac 打包流程
- [x] 梳理 Windows / macOS / Linux 下载策略
- [ ] 增加更多实机安装验收记录
- [ ] 继续优化首页视觉、截图与演示素材
- [ ] 收集真实用户反馈，持续压缩新手上手成本

## 参与维护

如果你愿意一起把它做成一个更稳、更好用、也更值得收藏的项目，欢迎：

- 提交 bug 反馈
- 提交功能建议
- 帮忙补实机测试结果
- 帮忙优化文档和翻译
- 提交代码修复或小改进

入口：

- [贡献指南](CONTRIBUTING.md)
- [行为准则](CODE_OF_CONDUCT.md)
- [安全说明](SECURITY.md)
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
