# Contributing Guide

感谢你愿意帮助 `LizzieYzy Next` 变得更稳、更好用。

This project is a maintained fork. We care about practical fixes, clear packaging, and keeping the real user workflow working.

## What This Project Optimizes For

当前最重要的事情很直接：

- 普通用户下载安装后，能尽快开始用
- 野狐抓谱、KataGo 分析、首次启动这条主链路保持可用
- 发布页、README、安装文档说法一致，不让用户被旧信息带偏

如果你想先看当前公开维护重点，建议先看 [ROADMAP.md](ROADMAP.md)。

## Before You Open An Issue

请先确认下面几件事：

1. 你下载的是不是正确的发布包。
2. 你的问题是不是已经在 README、release notes 或现有 issue 里出现过。
3. 如果是野狐抓谱问题，请优先确认你输入的是 **野狐昵称**，并确认该账号最近有公开棋谱。
4. 如果是 macOS 问题，请说明是 Apple Silicon 还是 Intel。
5. 如果是 Windows / Linux 问题，请说明你使用的是普通 `with-katago`、`nvidia`，还是 `without.engine` 包。

如果你只是想确认“某个发布包在这台机器上能不能正常安装”，优先使用 Installation Report 模板，而不是普通 bug 模板。

## What Makes A Good Bug Report

请尽量带上这些信息：

- 操作系统和版本
- 下载的安装包文件名
- 是否内置引擎版本
- 问题出现时的操作步骤
- 截图或录屏
- 报错文本
- 如果和野狐抓谱相关，请写清楚你输入的野狐昵称，以及返回结果
- 如果能复现，请写出稳定复现步骤

A concise, reproducible report saves a lot of time for everyone.

## What Makes A Good Feature Request

功能建议最好回答这三个问题：

1. 你现在遇到的真实问题是什么。
2. 你希望软件怎么改。
3. 这个改动会帮助到哪一类用户。

## Pull Request Guidelines

如果你准备提交代码，请尽量遵守这些规则：

1. 改动尽量聚焦，一个 PR 解决一类问题。
2. 不要顺手混入无关重构。
3. 如果改了打包、引擎路径、野狐同步流程，请说明测试方式。打包相关改动建议同时参考 [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)。
4. 如果改了文案或界面，请附截图。
5. 尽量保持中英文术语一致，尤其是 `Fox nickname / 野狐昵称`、`with-katago`、`nvidia`、`without.engine`。

如果你准备从源码构建、改打包脚本或继续维护，建议先看 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。

## Good First Contributions

这些方向都很欢迎：

- 实机安装测试
- README / 翻译优化
- 小的 UI 文案修正
- 打包脚本健壮性修复
- 野狐抓谱兼容性反馈
- CI / 自动化文档改进
- Windows / macOS / Linux 实机安装反馈
- 官方权重下载体验反馈

实机安装验证也很有价值：

- 成功安装也欢迎反馈，不一定非要等出错
- 这些结果会整理进 [docs/TESTED_PLATFORMS.md](docs/TESTED_PLATFORMS.md)
- 建议使用 Installation Report 模板提交

## Community

- GitHub Discussions：适合公开讨论使用体验、路线建议和打包选择
- QQ 群 `299419120`：适合中文用户快速交流
- Pull Request：适合已经确认方向的具体改动

## Community Tone

请默认友好、具体、直接。

- 可以指出问题，但尽量附带上下文。
- 可以提出不同方案，但尽量说明取舍。
- 请避免嘲讽式反馈或无复现信息的情绪化报错。
- 讨论和协作默认遵循 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。

## Where To Contribute

- Bug reports: GitHub Issues
- Installation verification: Installation Report issue form
- Ideas and discussion: GitHub Discussions
- Code changes: Pull Requests
