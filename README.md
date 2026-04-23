<p align="center">
  <img src="assets/hero-chinese.svg" alt="LizzieYzy Next" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next?display_name=tag&label=Latest%20Release&color=111111" alt="Latest Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/wimi321/lizzieyzy-next/ci.yml?branch=main&label=CI&color=1f6feb" alt="CI"></a>
  <a href="LICENSE.txt"><img src="https://img.shields.io/github/license/wimi321/lizzieyzy-next?color=5b5b5b" alt="License"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-6b7280" alt="Platforms">
</p>

<p align="center">
  简体中文 · <a href="README_ZH_TW.md">繁體中文</a> · <a href="README_EN.md">English</a> · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a> · <a href="README_TH.md">ภาษาไทย</a>
</p>

<p align="center">
  <strong>LizzieYzy Next 是一个持续维护的 KataGo 围棋复盘桌面 GUI，也是 <code>lizzieyzy 2.5.3</code> 的延续维护线。</strong><br/>
  它优先解决真实用户最常遇到的事情：下载包怎么选、第一次启动怎么更省心、野狐棋谱怎么更快抓到、Windows 同步工具怎么随包可用，以及整盘复盘怎么更快进入状态。
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><strong>下载正式版本</strong></a>
  ·
  <a href="docs/INSTALL.md"><strong>安装指南</strong></a>
  ·
  <a href="docs/PACKAGES.md"><strong>发布包说明</strong></a>
  ·
  <a href="docs/TROUBLESHOOTING.md"><strong>常见问题</strong></a>
  ·
  <a href="https://github.com/wimi321/lizzieyzy-next/discussions"><strong>Discussions</strong></a>
</p>

| 项目状态 | 当前说明 |
| --- | --- |
| 用户可见版本线 | `LizzieYzy Next 1.0.0` |
| 上游基础 | `lizzieyzy 2.5.3` |
| 默认引擎 | `KataGo v1.16.4` |
| 默认权重 | `kata1-zhizi-b28c512nbt-muonfd2.bin.gz` |
| 官方下载入口 | GitHub Releases |

> [!IMPORTANT]
> 官方下载入口现在只保留 GitHub Releases。
> Windows 正常 release 已内置原生 `readboard.exe`；只有 native 缺失或启动失败时才回退到 `readboard_java`，不需要再单独找同步工具仓库。

## 为什么这个项目值得看

- 它不是一次性的补丁分支，而是继续维护 `lizzieyzy` 主使用链路的公开版本。
- 它不只改源码，也一起维护安装包、首次启动、发布页、安装文档和回归验证。
- 它优先服务真实使用场景：抓谱、复盘、看胜率图、做整盘分析、在 Windows 上完成同步与启动。

## 当前核心能力

| 你想做什么 | 当前体验 |
| --- | --- |
| 下载后尽快开始用 | Windows、macOS、Linux 都提供公开整合包，普通用户不用先拼环境 |
| 抓最近公开野狐棋谱 | 直接输入野狐昵称，程序自动匹配账号并获取公开棋谱 |
| 做智能测速优化 | 基于 KataGo 官方 benchmark，测速过程可见、可中止，并会暂停后恢复分析 |
| 在 Windows 上做棋盘同步 | 正常 release 默认使用原生 `readboard.exe`，缺失时自动回退 Java 简易版 |
| 打开棋谱后尽快能操作 | SGF、本地加载、野狐加载优先恢复主窗口可操作状态，胜率曲线继续补齐 |
| 在 macOS 上安装 | 官方 DMG 发布流程已接入签名与公证，降低首次打开门槛 |

## 下载哪个包

所有公开下载都在 [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases)。如果你只想先下对版本，按下面选就够了。

<p align="center">
  <img src="assets/package-guide-zh.svg" alt="LizzieYzy Next 下载选择图" width="100%" />
</p>

| 你的情况 | 到 Releases 里找包含这个关键词的文件 |
| --- | --- |
| Windows 大多数用户，默认推荐 | `*windows64.opencl.portable.zip` |
| Windows，OpenCL 不稳定，CPU 兼容兜底 | `*windows64.with-katago.portable.zip` |
| Windows，NVIDIA 显卡，希望更快 | `*windows64.nvidia.portable.zip` |
| Windows，自己配置引擎 | `*windows64.without.engine.portable.zip` |
| macOS Apple Silicon | `*mac-arm64.with-katago.dmg` |
| macOS Intel | `*mac-amd64.with-katago.dmg` |
| Linux | `*linux64.with-katago.zip` |

说明：

- 想保留安装流程的 Windows 用户，也可以选同系列的 `*.installer.exe`。
- 想看完整 11 个公开资产和每个包里带了什么，直接看 [docs/PACKAGES.md](docs/PACKAGES.md)。
- Windows 正常 release 已内置棋盘同步所需 native `readboard`，不需要额外下载。

## 当前公开版重点

- `Fox 昵称抓谱`
  直接输入野狐昵称，不再把“先查账号数字”当成普通用户前置步骤。
- `KataGo 一键设置`
  主整合包内置 `KataGo v1.16.4` 与默认权重，智能测速优化按官方 benchmark 思路执行，并支持中止。
- `更稳的 Windows 同步链路`
  主发布包随包内置 `readboard.exe` 和依赖，缺失时才回退 Java 版。
- `更直接的棋谱加载交互`
  下载完成后优先把主窗口恢复到可操作状态，用户可以先继续看谱，胜率曲线再继续补齐。
- `更像正式桌面项目的发布方式`
  持续维护跨平台打包、CI、release notes 和安装文档，而不是只留源码。

## 快速上手

1. 去 [Releases](https://github.com/wimi321/lizzieyzy-next/releases) 下载适合自己系统的包。
2. 启动程序后，如果你用的是内置 KataGo 的 Windows 版本，可以先在 `KataGo 一键设置` 里运行一次“智能测速优化”。
3. 直接打开本地 SGF，或者从 `野狐棋谱（输入野狐昵称获取）` 抓最近公开棋谱。
4. 用方向键、`Down` 和主胜率图快速浏览关键手，再结合底部概览回看大问题手。

<p align="center">
  <a href="assets/fox-id-demo-cn.gif">
    <img src="assets/fox-id-demo-cn-cover.png" alt="LizzieYzy Next 野狐昵称抓谱演示" width="100%" />
  </a>
</p>

## 界面预览

<p align="center">
  <img src="assets/interface-overview-2026-04.png" alt="LizzieYzy Next 当前界面预览" width="100%" />
</p>

<p align="center">
  <img src="assets/winrate-quick-overview-2026-04.png" alt="LizzieYzy Next 胜率图与快速概览" width="52%" />
</p>

你可以把现在的主界面理解成三层信息：

- 主棋盘区：看当前变化、推荐点和局面判断。
- 胜率图：快速看整盘走势，判断哪里开始出问题。
- 底部快速概览：先找到最该回看的区段，再决定要不要细看每一手。

## 文档与社区

- [安装指南](docs/INSTALL.md)
- [发布包说明](docs/PACKAGES.md)
- [常见问题与排错](docs/TROUBLESHOOTING.md)
- [已验证平台](docs/TESTED_PLATFORMS.md)
- [更新日志](CHANGELOG.md)
- [项目路线图](ROADMAP.md)
- [参与贡献](CONTRIBUTING.md)
- [获取帮助](SUPPORT.md)
- [GitHub Discussions](https://github.com/wimi321/lizzieyzy-next/discussions)
- QQ 群：`299419120`

## 从源码构建

要求：

- JDK 17
- Maven 3.9+

构建命令：

```bash
mvn test
mvn -DskipTests package
java -jar target/lizzie-yzy2.5.3-shaded.jar
```

如果你准备继续维护打包、发布或自动化流程，建议再看：

- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)
- [docs/MAINTENANCE.md](docs/MAINTENANCE.md)
- [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)

## 致谢

- 原项目：[yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- KataGo：[lightvector/KataGo](https://github.com/lightvector/KataGo)
- 野狐抓谱历史参考：[yzyray/FoxRequest](https://github.com/yzyray/FoxRequest)、[FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)

## 许可证

本项目遵循 [GPL-3.0](LICENSE.txt)。
