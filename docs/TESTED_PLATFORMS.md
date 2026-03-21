# 已验证平台

这份文档记录当前发布资产的已知验证状态。

目的不是假装“所有平台都完全测过”，而是明确告诉用户：

- 哪些已经实机验证过
- 哪些已经准备好发布流程，但还缺少真实机器反馈
- 哪些仍然主要依赖社区回报

状态说明：

- `Maintainer tested`：维护者在真实机器上完成了安装或启动验证
- `Build verified`：资产命名、内容结构或工作流已经验证，但还缺少真实机器反馈
- `Workflow ready`：打包脚本和工作流已准备好，等待新 release 实际发布和回报
- `Needs report`：目前仍缺少足够反馈

## 当前状态

| 包 | 平台 | 当前状态 | 已确认内容 | 备注 |
| --- | --- | --- | --- | --- |
| `mac-arm64.with-katago.dmg` | macOS Apple Silicon | `Maintainer tested` | 安装、启动、界面打开、野狐ID抓谱入口可见 | 当前最完整的实机验证链路 |
| `mac-amd64.with-katago.dmg` | macOS Intel | `Build verified` | 已纳入独立发布流程 | 需要真实 Intel Mac 反馈 |
| `windows64.with-katago.installer.exe` | Windows x64 | `Workflow ready` | Windows 安装器工作流已补齐 | 下一次公开 release 后需要实机反馈 |
| `windows64.with-katago.portable.zip` | Windows x64 | `Workflow ready` | `.exe` 便携包工作流已补齐 | 作为免安装备选 |
| `windows64.without.engine.portable.zip` | Windows x64 | `Workflow ready` | 无引擎 `.exe` 便携包已纳入新打包策略 | 面向进阶用户 |
| `windows32.without.engine.zip` | Windows x86 | `Needs report` | 兼容包继续保留 | 需要老机器或兼容环境验证 |
| `linux64.with-katago.zip` | Linux x64 | `Build verified` | 整合包继续提供 | 需要真实 Linux 桌面反馈 |
| `Macosx.amd64.Linux.amd64.without.engine.zip` | Intel Mac / Linux | `Needs report` | 面向进阶用户的无引擎包 | 仅建议熟悉手工配置的用户使用 |

## 我们重点关心什么

如果你帮忙验证，最有价值的是这些信息：

- 包能不能正常下载、安装、解压或挂载
- 首次启动是否被系统安全策略拦截
- 程序能不能进入主界面
- `with-katago` 包里引擎是否正常加载
- “野狐棋谱（输入野狐ID获取）”是否能抓到公开棋谱

## 如何补充反馈

1. 去 GitHub Issues 里选择 `Installation Report`
2. 写清楚安装包文件名、系统版本、结果和额外步骤
3. 如果有截图或报错，一起附上

相关入口：

- [获取帮助](../SUPPORT.md)
- [发布包说明](PACKAGES.md)
- [安装指南](INSTALL.md)
- [常见问题与排错](TROUBLESHOOTING.md)
