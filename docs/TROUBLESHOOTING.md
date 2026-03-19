# 常见问题与排错

这份文档优先覆盖当前维护版最常见的几类问题：安装失败、启动失败、引擎找不到、野狐抓谱无结果。

## 1. macOS 提示“无法打开”或“已损坏”

先确认你下载的是正确芯片版本：

- Apple Silicon: `mac-arm64.with-katago.dmg`
- Intel: `mac-amd64.with-katago.dmg`

当前维护版 macOS 包是未签名 / 未公证发布包，第一次被系统拦住是正常现象。

处理步骤：

1. 先尝试打开一次应用。
2. 打开 `系统设置 -> 隐私与安全性`。
3. 找到应用拦截提示。
4. 选择 `仍要打开`。

如果你拿的是别的来源、改过名、被系统移动过位置的包，也建议重新从官方 Releases 下载再试一次。

## 2. Windows 或 Linux 打开后提示找不到 Java

先看你下载的是哪种包：

- `windows64.with-katago.zip`：当前维护策略下通常带 Java
- `windows64.without.engine.zip`：当前维护策略下通常带 Java
- `linux64.with-katago.zip`：当前维护策略下通常带 Java
- `windows32.without.engine.zip`：**不带 Java**
- `Macosx.amd64.Linux.amd64.without.engine.zip`：**不带 Java**

如果你下载的是后两种，请自行安装 Java 11+。

## 3. 软件能打开，但没有自动连上 KataGo

`with-katago` 整合包的设计目标是：

- 包内同时存在引擎、权重、配置文件时
- 程序在首次启动时自动优先使用内置 KataGo

如果没有自动连上，优先检查：

1. 你是不是下载了 `without.engine` 包。
2. 解压是否完整。
3. 是否误删了 `weights/default.bin.gz` 或 `engines/katago/`。
4. 是否把程序主体单独拎出来运行，导致相对路径丢失。

最稳妥的方式是：

- 不要只拷贝一个 `.jar` 单独运行
- 直接在完整解压目录中启动

## 4. 野狐抓谱没有结果

先确认下面几件事：

1. 输入的是 **纯数字野狐ID**，不是用户名。
2. 该账号最近确实有公开可见棋谱。
3. 网络访问正常。
4. 不是接口的临时波动。

当前维护版就是按 **野狐ID** 工作的。README、界面、Issue 模板也都已经统一到这个口径。

## 5. 提示“仅支持野狐ID，请输入纯数字ID”

这不是程序坏了，而是输入格式不对。

正确输入示例：

- `12345678`

不正确的输入示例：

- `什么好吃`
- `fox_123`
- `12345abc`

## 6. 我明明输入了野狐ID，还是抓不到

这种情况常见于：

- 该 ID 最近没有公开棋谱
- 接口临时失败
- 本地网络环境异常

建议按这个顺序排查：

1. 换一个你确认有公开棋谱的野狐ID再试。
2. 过几分钟重试。
3. 如果还是失败，到 GitHub Issues 提交反馈，并附上：
   - 安装包文件名
   - 操作系统版本
   - 野狐ID
   - 报错截图

## 7. 想替换权重文件

可以直接替换默认权重，但请注意文件名和路径。

默认位置：

- Windows / Linux：`Lizzieyzy/weights/default.bin.gz`
- macOS：`LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`

替换后如果启动异常，请先恢复原版权重确认是不是新权重本身的问题。

## 8. 想用自己熟悉的引擎，不用内置 KataGo

建议直接下载 `without.engine` 包，或者在整合包里手动改引擎设置。

如果你只是想换权重，保留内置 KataGo 即可。
如果你想换成别的 GTP 引擎，`without.engine` 会更干净。

## 9. 双击没反应，怎么拿到更多信息

### Windows

尽量从包里的 `.bat` 启动，不要只点 `.jar`。

### Linux

从终端启动：

```bash
./start-linux64.sh
```

### macOS

先按安装文档完成“仍要打开”，再重试。

## 10. 反馈问题时最好带什么

最有帮助的是这四项：

- 安装包文件名
- 操作系统和版本
- 是否是 `with-katago` 或 `without.engine`
- 完整报错截图或复现步骤

相关入口：

- [安装指南](INSTALL.md)
- [发布包说明](PACKAGES.md)
- [GitHub Issues](https://github.com/wimi321/lizzieyzy-next-foxuid/issues)
