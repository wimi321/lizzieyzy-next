# 发布包说明

这份文档用来回答三个问题：

1. 现在到底维护哪些包
2. 每个包里内置了什么
3. 下载时应该怎么选

## 当前维护的包类型

当前维护版围绕下面这些包类型持续整理和发布：

| 包类型 | 典型文件名 | 适合谁 |
| --- | --- | --- |
| Windows 64 位整合包 | `<date>-windows64.with-katago.zip` | 下载后直接用 |
| Windows 64 位无引擎包 | `<date>-windows64.without.engine.zip` | 想自己配引擎 |
| Windows 32 位兼容包 | `<date>-windows32.without.engine.zip` | 老机器兼容 |
| macOS Apple Silicon 整合包 | `<date>-mac-arm64.with-katago.dmg` | M 系列 Mac 用户 |
| macOS Intel 整合包 | `<date>-mac-amd64.with-katago.dmg` | Intel Mac 用户 |
| Linux 64 位整合包 | `<date>-linux64.with-katago.zip` | Linux 桌面用户 |
| Mac/Linux 进阶无引擎包 | `<date>-Macosx.amd64.Linux.amd64.without.engine.zip` | 自定义配置 |

说明：

- 文件名前缀里的 `<date>` 代表发布日期，例如 `2026-03-16`。
- 我们现在已经不再提供 macOS 的额外 `.app.zip` 压缩包。
- 也不再保留“other systems”那种不清楚用途的大杂烩包。

## 每个包里内置了什么

| 包 | Java | KataGo | 权重 | 备注 |
| --- | --- | --- | --- | --- |
| `windows64.with-katago` | 内置 | 内置 | 内置 | 最推荐给普通用户 |
| `windows64.without.engine` | 内置 | 不内置 | 不内置 | 想自己配引擎时用 |
| `windows32.without.engine` | 不内置 | 不内置 | 不内置 | 需要自己补 Java 和引擎 |
| `mac-arm64.with-katago.dmg` | App 自带运行时 | 内置 | 内置 | 对应 Apple Silicon |
| `mac-amd64.with-katago.dmg` | App 自带运行时 | 内置 | 内置 | 对应 Intel Mac |
| `linux64.with-katago` | 内置 | 内置 | 内置 | Linux 桌面用户首选 |
| `Macosx.amd64.Linux.amd64.without.engine` | 不内置 | 不内置 | 不内置 | 只建议进阶用户 |

## 内置引擎信息

当前整合包默认使用：

- KataGo 版本：`v1.16.4`
- 默认权重：`g170e-b20c256x2-s5303129600-d1228401921.bin.gz`

路径说明：

- Windows / Linux 权重：`Lizzieyzy/weights/default.bin.gz`
- macOS 权重：`LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`

## 为什么包名这样设计

我们现在把包名尽量做成“一眼能看懂”的结构：

`日期-系统.是否内置引擎.扩展名`

比如：

- `2026-03-16-windows64.with-katago.zip`
- `2026-03-16-windows64.without.engine.zip`
- `2026-03-16-mac-arm64.with-katago.dmg`

这样用户能直接看懂三件事：

- 这是哪个日期的发布
- 适用于什么系统
- 是不是带内置引擎

## 给普通用户的下载建议

如果你只是想尽快上手：

- Windows：选 `windows64.with-katago.zip`
- macOS：按芯片选对应的 `with-katago.dmg`
- Linux：选 `linux64.with-katago.zip`

如果你已经熟悉引擎配置：

- Windows：选 `windows64.without.engine.zip`
- 其他需要完全自定义的情况：选 `Macosx.amd64.Linux.amd64.without.engine.zip`

## 给维护者的发布检查点

每次整理 release 时，建议至少确认：

- Windows 64 位同时有 `with-katago` 和 `without.engine`
- macOS 同时有 `arm64` 和 `amd64` 的 `.dmg`
- Linux 64 位有 `with-katago`
- 不再误上传 macOS `.app.zip`
- 不再误上传“other systems”旧包
- 发布说明里明确写出“野狐棋谱同步已修复，输入野狐ID获取”

## 相关文档

- [安装指南](INSTALL.md)
- [常见问题与排错](TROUBLESHOOTING.md)
- [项目首页](../README.md)
