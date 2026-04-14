# 发布包说明

这份文档回答三件事：

1. 当前维护版到底公开推荐哪些包
2. 每个包里内置了什么
3. 普通用户应该怎么选

## 先直接看结论

这份文档讲的是当前维护版 `LizzieYzy Next` 的公开发布格式，不是旧 `lizzieyzy` 的历史发布布局。

- 当前维护版公开主推 11 个用户向主资产
- Windows 默认优先推荐 `portable.zip`
- 大多数普通用户先下 `windows64.opencl.portable.zip`
- OpenCL 表现不好时改用 `windows64.with-katago.portable.zip`
- 有 NVIDIA 显卡并且更在意速度时改用 `windows64.nvidia.portable.zip`

如果你只想先看图再决定，先看这里：

<p align="center">
  <img src="../assets/package-guide-zh.svg" alt="LizzieYzy Next 下载选择图" width="100%" />
</p>

## 第一次下载就照这个选

| 包类型 | 典型文件名 | 适合谁 |
| --- | --- | --- |
| Windows 64 位 OpenCL 免安装包 | `<date>-windows64.opencl.portable.zip` | 普通用户首选，解压后直接运行 |
| Windows 64 位 OpenCL 安装器 | `<date>-windows64.opencl.installer.exe` | 想保留安装流程的 OpenCL 用户 |
| Windows 64 位 CPU 兜底免安装包 | `<date>-windows64.with-katago.portable.zip` | OpenCL 表现不好时切换使用 |
| Windows 64 位 CPU 兜底安装器 | `<date>-windows64.with-katago.installer.exe` | 想安装的 CPU 兜底用户 |
| Windows 64 位 NVIDIA 极速免安装包 | `<date>-windows64.nvidia.portable.zip` | 有 NVIDIA 显卡，想要更高分析速度，也不想安装 |
| Windows 64 位 NVIDIA 极速安装器 | `<date>-windows64.nvidia.installer.exe` | 有 NVIDIA 显卡，想保留安装流程 |
| Windows 64 位无引擎便携包 | `<date>-windows64.without.engine.portable.zip` | 想自己配置分析引擎 |
| Windows 64 位无引擎安装器 | `<date>-windows64.without.engine.installer.exe` | 想保留安装流程，但自己配置分析引擎 |
| macOS Apple Silicon 整合包 | `<date>-mac-arm64.with-katago.dmg` | M 系列 Mac |
| macOS Intel 整合包 | `<date>-mac-amd64.with-katago.dmg` | Intel Mac |
| Linux 64 位整合包 | `<date>-linux64.with-katago.zip` | Linux 桌面用户 |

说明：

- `<date>` 代表发布日期，例如 `2026-03-21`。
- 当前维护版公开 release 主列表只保留这 11 个用户向主资产。
- Windows 64 位现在优先推荐免安装包，安装器作为可选路径保留。
- 旧 tag 里如果还看到兼容 zip 或历史包，那属于历史发布格式。

## 每个包里内置了什么

| 包 | 是否自带运行环境 | 是否打开后就能分析 | 打开方式 |
| --- | --- | --- | --- |
| `windows64.opencl.portable.zip` | 是 | 是 | 解压后运行 `LizzieYzy Next OpenCL.exe` |
| `windows64.opencl.installer.exe` | 是 | 是 | 安装后从开始菜单或桌面打开 |
| `windows64.with-katago.portable.zip` | 是 | 是 | 解压后运行 `LizzieYzy Next.exe` |
| `windows64.with-katago.installer.exe` | 是 | 是 | 安装后从开始菜单或桌面打开 |
| `windows64.nvidia.portable.zip` | 是 | 是 | 解压后运行 `LizzieYzy Next NVIDIA.exe` |
| `windows64.nvidia.installer.exe` | 是 | 是 | 安装后从开始菜单或桌面打开 |
| `windows64.without.engine.portable.zip` | 是 | 否 | 解压后运行 `LizzieYzy Next.exe` |
| `windows64.without.engine.installer.exe` | 是 | 否 | 安装后从开始菜单或桌面打开 |
| `mac-arm64.with-katago.dmg` | App 自带 | 是 | 拖到 Applications |
| `mac-amd64.with-katago.dmg` | App 自带 | 是 | 拖到 Applications |
| `linux64.with-katago.zip` | 是 | 是 | 运行 `start-linux64.sh` |

## 给普通用户的选择建议

如果你只想尽快开始：

- Windows：选 `windows64.opencl.portable.zip`
- Windows + NVIDIA 显卡：选 `windows64.nvidia.portable.zip`
- macOS：按芯片选对应的 `with-katago.dmg`
- Linux：选 `linux64.with-katago.zip`

如果你已经熟悉引擎配置：

- Windows：想免安装就选 `windows64.without.engine.portable.zip`，想安装再选 `windows64.without.engine.installer.exe`
- macOS / Linux：也可以先装对应系统的主包，再在软件里改成你自己的分析引擎

## 为什么 Windows 现在优先推荐免安装包

因为普通用户真正需要的是：

1. 下载后解压就能直接运行
2. 如果更喜欢安装流程，也还有安装器可选
3. 运行环境已经带好
4. 第一次打开尽量自动准备好分析环境

对多数 Windows 用户来说，免安装包更接近“解压后就开始用”的真实习惯。
安装器依然保留，但它的角色已经变成“我明确想保留安装流程”。

## 当前内置引擎信息

当前整合包默认使用：

- KataGo 版本：`v1.16.4`
- 默认权重：`kata1-zhizi-b28c512nbt-muonfd2.bin.gz`

路径说明：

- Windows / Linux 权重：`Lizzieyzy/weights/default.bin.gz`
- macOS 权重：`LizzieYzy Next.app/Contents/app/weights/default.bin.gz`

## 当前内置同步工具信息

- 主发布包现在把 `readboard_java` 简易棋盘同步工具作为主项目的一部分交付
- Windows / Linux 包路径：`Lizzieyzy/readboard_java/`
- macOS 包路径：`LizzieYzy Next.app/Contents/app/readboard_java/`
- 旧的 Windows 原生 `readboard` 文件夹现在只作为兼容备用，不再是主 release 的默认依赖

## 新旧发布格式怎么理解

从新的维护版发布开始：

- Windows 64 位主推荐资产是 `portable.zip`
- Windows 64 位同时提供 `opencl`、`with-katago`、`nvidia` 三条线的安装器和便携包
- Windows 64 位无引擎包同时提供安装器和 `.portable.zip`
- 当前公开 release 主列表固定为 11 个用户向主资产
- 旧的兼容 zip 只作为历史 tag 说明保留，不再放进主推荐区

## 相关文档

- [安装指南](INSTALL.md)
- [常见问题与排错](TROUBLESHOOTING.md)
- [已验证平台](TESTED_PLATFORMS.md)
- [发布检查清单](RELEASE_CHECKLIST.md)
- [项目首页](../README.md)
