<p align="center">
  <img src="assets/hero.svg" alt="LizzieYzy Next-FoxUID" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next-foxuid?display_name=tag&label=Release&color=111111" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next-foxuid?style=flat&color=444444" alt="Stars"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><img src="https://img.shields.io/github/downloads/wimi321/lizzieyzy-next-foxuid/total?label=Downloads&color=666666" alt="Downloads"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-888888" alt="Platforms">
</p>

<p align="center">
  中文 · <a href="README_EN.md">English</a> · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a>
</p>

<p align="center">
  <strong>让 lizzieyzy 重新变得好用。</strong><br/>
  原项目长期无人维护后，很多人已经没法正常抓野狐棋谱。这个维护版先把最常用的抓谱链路修回来，再把安装、启动和内置 KataGo 做得更省事。<br/>
  <strong>下载安装，输入野狐昵称，就能继续抓谱、分析、复盘。</strong>
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><strong>下载发布包</strong></a>
  ·
  <a href="docs/INSTALL.md"><strong>安装说明</strong></a>
  ·
  <a href="docs/TROUBLESHOOTING.md"><strong>常见问题</strong></a>
</p>

> [!IMPORTANT]
> 先知道这 3 件事就够了：
> - Windows 大多数用户下载 `windows64.with-katago.installer.exe`
> - 现在直接输入 **野狐昵称** 就行，程序会自动找到账号并获取最近公开棋谱
> - 主推荐整合包已内置 KataGo，第一次打开会自动准备好

<p align="center">
  <img src="assets/highlights-zh.svg" alt="LizzieYzy Next-FoxUID 维护版亮点" width="100%" />
</p>

## Windows 用户先下载这个

如果你用的是 **Windows 电脑**，先下载：

**`windows64.with-katago.installer.exe`**

这是最省事的版本。下载后双击安装，装好就能直接打开使用。

## 按系统选择

| 你的电脑 | 直接下载这个 |
| --- | --- |
| Windows 64 位 | `windows64.with-katago.installer.exe` |
| Windows 64 位，想免安装 | `windows64.with-katago.portable.zip` |
| Windows 64 位，想自己配引擎 | `windows64.without.engine.portable.zip` |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` |
| macOS Intel | `mac-amd64.with-katago.dmg` |
| Linux 64 位 | `linux64.with-katago.zip` |

如果你懒得分辨：

- Windows：不知道怎么选，就下 `installer.exe`
- Mac：先看自己是 Apple Silicon 还是 Intel
- Linux：直接下 `with-katago.zip`

## 为什么很多人先看这个维护版

- **先把最重要的问题修好**
  原版很多人已经抓不到野狐棋谱了，这个维护版把常用抓谱链路先修回来了。
- **把普通用户最容易卡住的步骤省掉**
  现在直接输入野狐昵称，程序会自动匹配账号，不需要你先去找数字账号。
- **把下载安装和第一次启动做简单**
  主推荐整合包已经带好 KataGo 和默认权重，下载后更接近“打开就能用”的状态。

## 三步开始

1. 去 [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases) 下载适合自己系统的包。
2. 打开程序后，点击 **野狐棋谱（输入野狐昵称获取）**。
3. 输入野狐昵称，抓到棋谱后继续分析和复盘。

<p align="center">
  <a href="assets/fox-id-demo-cn.gif">
    <img src="assets/fox-id-demo-cn-cover.png" alt="LizzieYzy Next-FoxUID 野狐昵称抓谱演示" width="100%" />
  </a>
</p>

<p align="center">
  如果 GitHub 里的动图加载慢，直接点上面的图就能看完整演示。
</p>

## 当前真实界面

下面这张就是现在这个维护版的真实界面截图。底部可以直接看到 **野狐棋谱**、**更新官方权重** 等入口，不再是旧版本历史截图。

<p align="center">
  <img src="assets/interface-overview.png" alt="LizzieYzy Next-FoxUID 当前真实界面" width="100%" />
</p>

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
- [更新日志](CHANGELOG.md)
- [Support](SUPPORT.md)

## 致谢

- 原项目：[yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- KataGo：[lightvector/KataGo](https://github.com/lightvector/KataGo)
- 野狐抓谱历史参考：
  - [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest)
  - [FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)
