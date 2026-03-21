# 发布包说明

这份文档回答三件事：

1. 当前维护版到底提供哪些包
2. 每个包里内置了什么
3. 普通用户应该怎么选

## 当前推荐的发布资产

| 包类型 | 典型文件名 | 适合谁 |
| --- | --- | --- |
| Windows 64 位安装器 | `<date>-windows64.with-katago.installer.exe` | 想双击安装、直接使用的普通用户 |
| Windows 64 位整合便携包 | `<date>-windows64.with-katago.portable.zip` | 不想安装，只想解压后直接运行 |
| Windows 64 位无引擎便携包 | `<date>-windows64.without.engine.portable.zip` | 想自己配置 KataGo |
| Windows 32 位兼容包 | `<date>-windows32.without.engine.zip` | 老机器或兼容环境 |
| macOS Apple Silicon 整合包 | `<date>-mac-arm64.with-katago.dmg` | M 系列 Mac |
| macOS Intel 整合包 | `<date>-mac-amd64.with-katago.dmg` | Intel Mac |
| Linux 64 位整合包 | `<date>-linux64.with-katago.zip` | Linux 桌面用户 |
| Mac/Linux 进阶无引擎包 | `<date>-Macosx.amd64.Linux.amd64.without.engine.zip` | 完全自定义配置 |

说明：

- `<date>` 代表发布日期，例如 `2026-03-21`。
- Windows 64 位现在优先推荐安装器，其次才是便携包。
- 旧 tag 里如果还看到 `windows64.with-katago.zip`，那属于历史发布格式。
- macOS 继续只保留 `.dmg` 主包，不再把 `.app.zip` 当主推荐。

## 每个包里内置了什么

| 包 | Java | KataGo | 权重 | 打开方式 |
| --- | --- | --- | --- | --- |
| `windows64.with-katago.installer.exe` | 内置 | 内置 | 内置 | 安装后从开始菜单或桌面打开 |
| `windows64.with-katago.portable.zip` | 内置 | 内置 | 内置 | 解压后运行 `LizzieYzy Next-FoxUID.exe` |
| `windows64.without.engine.portable.zip` | 内置 | 不内置 | 不内置 | 解压后运行 `LizzieYzy Next-FoxUID.exe` |
| `windows32.without.engine.zip` | 不内置 | 不内置 | 不内置 | 按包内说明启动 |
| `mac-arm64.with-katago.dmg` | App 自带运行时 | 内置 | 内置 | 拖到 Applications |
| `mac-amd64.with-katago.dmg` | App 自带运行时 | 内置 | 内置 | 拖到 Applications |
| `linux64.with-katago.zip` | 内置 | 内置 | 内置 | 运行 `start-linux64.sh` |
| `Macosx.amd64.Linux.amd64.without.engine.zip` | 不内置 | 不内置 | 不内置 | 手工配置 |

## 给普通用户的选择建议

如果你只想尽快开始：

- Windows：选 `windows64.with-katago.installer.exe`
- macOS：按芯片选对应的 `with-katago.dmg`
- Linux：选 `linux64.with-katago.zip`

如果你已经熟悉引擎配置：

- Windows：选 `windows64.without.engine.portable.zip`
- 其它平台完全自定义：选 `Macosx.amd64.Linux.amd64.without.engine.zip`

## 为什么 Windows 现在优先推荐安装器

因为普通用户真正需要的是：

1. 下载后双击就能安装
2. 不需要理解 `.bat`
3. 不需要自己找 Java
4. 第一次打开尽量自动配好内置 KataGo

便携包依然保留，但它的角色已经变成“我明确知道自己不想安装”。

## 当前内置引擎信息

当前整合包默认使用：

- KataGo 版本：`v1.16.4`
- 默认权重：`g170e-b20c256x2-s5303129600-d1228401921.bin.gz`

路径说明：

- Windows / Linux 权重：`Lizzieyzy/weights/default.bin.gz`
- macOS 权重：`LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`

## 新旧发布格式怎么理解

从新的维护版发布开始：

- Windows 64 位主推荐资产是 `installer.exe`
- Windows 64 位无引擎包也改成 `.exe` 便携形式
- `windows64.with-katago.zip` 这种旧格式只作为历史 tag 的兼容说明保留

如果你正在整理 release 页面，建议不要再把旧的 Windows 64 位 zip 包放进主推荐列表。

## 相关文档

- [安装指南](INSTALL.md)
- [常见问题与排错](TROUBLESHOOTING.md)
- [已验证平台](TESTED_PLATFORMS.md)
- [发布检查清单](RELEASE_CHECKLIST.md)
- [项目首页](../README.md)
