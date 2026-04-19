# 已验证平台

这份文档记录当前主发布资产的已知验证状态。

目的不是假装“所有平台都完全测过”，而是明确告诉用户：

- 哪些已经实机验证过
- 哪些已经完成构建与发布验证，但还缺少真实机器反馈
- 哪些仍然主要依赖社区回报

状态说明：

- `Maintainer tested`：维护者在真实机器上完成了安装或启动验证
- `Build verified`：资产命名、内容结构、工作流和公开 release 已验证，但还缺少真实机器反馈
- `Needs report`：目前仍缺少足够反馈

当前主推荐列表覆盖 11 个用户向资产；历史兼容包不再放进主推荐区。

## 当前状态

| 包 | 平台 | 当前状态 | 已确认内容 | 备注 |
| --- | --- | --- | --- | --- |
| `windows64.opencl.portable.zip` | Windows x64 | `Build verified` | 默认推荐免安装包已纳入正式发布矩阵 | 当前面向大多数 Windows 用户的首推下载 |
| `windows64.opencl.installer.exe` | Windows x64 | `Build verified` | OpenCL 安装器已纳入正式发布矩阵 | 面向更偏好安装流程的 Windows 用户 |
| `windows64.with-katago.portable.zip` | Windows x64 | `Build verified` | CPU 兜底便携包工作流和公开 release 资产已验证 | OpenCL 不稳定时的兼容兜底 |
| `windows64.with-katago.installer.exe` | Windows x64 | `Build verified` | CPU 兜底安装器工作流和公开 release 资产已验证 | 面向想安装的 CPU 兜底用户 |
| `windows64.nvidia.portable.zip` | Windows x64 + NVIDIA | `Build verified` | NVIDIA CUDA 便携包已纳入正式发布矩阵 | 面向不想安装的 NVIDIA 用户 |
| `windows64.nvidia.installer.exe` | Windows x64 + NVIDIA | `Build verified` | NVIDIA CUDA 整合安装器已纳入正式发布矩阵 | 需要真实 NVIDIA Windows 反馈 |
| `windows64.without.engine.portable.zip` | Windows x64 | `Build verified` | 无引擎便携包已纳入正式发布矩阵 | 面向进阶用户 |
| `windows64.without.engine.installer.exe` | Windows x64 | `Build verified` | 无引擎安装器已纳入正式发布矩阵 | 面向想安装但自己配引擎的用户 |
| `mac-arm64.with-katago.dmg` | macOS Apple Silicon | `Maintainer tested` | 安装、启动、界面打开、野狐昵称抓谱入口可见 | 当前最完整的实机验证链路 |
| `mac-amd64.with-katago.dmg` | macOS Intel | `Build verified` | 已纳入独立发布流程 | 需要真实 Intel Mac 反馈 |
| `linux64.with-katago.zip` | Linux x64 | `Build verified` | 整合包继续提供 | 需要真实 Linux 桌面反馈 |

## 我们重点关心什么

如果你帮忙验证，最有价值的是这些信息：

- 包能不能正常下载、安装、解压或挂载
- 首次启动是否被系统安全策略拦截
- 程序能不能进入主界面
- `with-katago` 包里引擎是否正常加载
- “野狐棋谱（输入野狐昵称获取）”是否能抓到公开棋谱

## 如何补充反馈

1. 去 GitHub Issues 里选择 `Installation Report`
2. 写清楚安装包文件名、系统版本、结果和额外步骤
3. 如果有截图或报错，一起附上

相关入口：

- [获取帮助](../SUPPORT.md)
- [发布包说明](PACKAGES.md)
- [安装指南](INSTALL.md)
- [常见问题与排错](TROUBLESHOOTING.md)
