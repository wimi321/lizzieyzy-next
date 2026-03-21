# 常见问题与排错

这份文档优先覆盖当前维护版最常见的几类问题：

- Windows / macOS 安装被拦住
- 首次启动后没自动连上 KataGo
- 野狐抓谱没有结果
- 不知道该用安装器、便携包还是无引擎包

## 1. Windows 安装器打不开或被系统拦住

先确认你下载的是：

- `windows64.with-katago.installer.exe`

常见处理方式：

1. 右键安装器，选择“以管理员身份运行”再试一次。
2. 如果被 SmartScreen 拦截，点击“更多信息”，再点“仍要运行”。
3. 确认安装目录不是只读目录。

如果你不想走安装器，也可以先改用 `windows64.with-katago.portable.zip`。

## 2. Windows 便携包或无引擎包打开后没反应

先确认你下载的是：

- `windows64.with-katago.portable.zip`
- 或 `windows64.without.engine.portable.zip`

排查顺序：

1. 确认你解压的是完整目录，不是只拎出一个 `.jar`。
2. 确认是双击 `LizzieYzy Next-FoxUID.exe`，不是只看到了某个说明文件。
3. 如果你用的是无引擎包，第一次打开后还需要自己配置引擎，这属于正常现象。

## 3. macOS 提示“无法打开”或“已损坏”

先确认你下载的是正确芯片版本：

- Apple Silicon：`mac-arm64.with-katago.dmg`
- Intel：`mac-amd64.with-katago.dmg`

当前维护版 macOS 包是未签名 / 未公证发布包，第一次被系统拦住是正常现象。

处理步骤：

1. 先尝试打开一次应用。
2. 打开 `系统设置 -> 隐私与安全性`。
3. 找到应用拦截提示。
4. 选择 `仍要打开`。

## 4. 软件能打开，但没有自动连上 KataGo

`with-katago` 包的设计目标是：

- 包里同时带好引擎、权重和配置文件
- 第一次启动时优先自动配置

如果没有自动连上，优先检查：

1. 你是不是下载了 `without.engine` 包。
2. 包内的 `weights/default.bin.gz` 是否还在。
3. `engines/katago/` 目录是否被误删。
4. 你是不是只把程序主体单独拎出来运行，导致相对路径丢失。

最稳妥的方式是：

- 不要只拿一个 `.jar` 单独运行
- 保持完整目录结构
- 让程序从完整安装目录或完整解压目录启动

## 5. 野狐抓谱没有结果

先确认下面几件事：

1. 输入的是 **纯数字野狐ID**，不是用户名。
2. 该账号最近确实有公开可见棋谱。
3. 网络访问正常。
4. 不是接口的临时波动。

当前维护版统一按 **野狐ID** 工作。README、界面、Issue 模板也都已经统一到这个口径。

## 6. 提示“仅支持野狐ID，请输入纯数字ID”

这不是程序坏了，而是输入格式不对。

正确输入示例：

- `12345678`

不正确的输入示例：

- `什么好吃`
- `fox_123`
- `12345abc`

## 7. 我明明输入了野狐ID，还是抓不到

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

## 8. 想替换权重文件

可以直接替换默认权重，但请注意文件名和路径。

默认位置：

- Windows / Linux：`Lizzieyzy/weights/default.bin.gz`
- macOS：`LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`

替换后如果启动异常，请先恢复原版权重确认是不是新权重本身的问题。

## 9. 想用自己熟悉的引擎，不用内置 KataGo

推荐直接下载：

- `windows64.without.engine.portable.zip`
- 或 `Macosx.amd64.Linux.amd64.without.engine.zip`

如果你只是想换权重，保留内置 KataGo 即可。

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
