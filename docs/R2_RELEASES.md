# Cloudflare R2 正式版下载与升级

`download.goagent.top` 是 LizzieYzy Next 当前正式版的主下载源。GitHub Release 始终保留
完整资产、安装器、Linux 包、历史版本和自动备用下载；pre-release 不上传 R2。

## 固定资源范围

R2 桶名固定为 `lizzieyzy-next-downloads`，只保留一个正式版。发布脚本要求每个正式版恰好
包含以下 13 个镜像对象，数量或名称不一致会直接停止：

- 5 个 Windows 免安装包：OpenCL、CPU、NVIDIA、RTX 50 CUDA、无引擎
- `windows64.core-update.zip`
- macOS Apple Silicon 与 Intel 两个 DMG
- TensorRT `.7z.001`、`.7z.002`、README、manifest、SHA-256 文件

Windows 安装器、Linux 包、pre-release 和历史版本不占用 R2。版本化对象位于
`releases/<tag>/`，稳定频道使用：

- `channels/stable/update-envelope.json`
- `channels/stable/catalog.json`
- `index.html`

下载首页面向普通用户，只展示可直接安装的 Windows、macOS 和主程序小更新。Windows
区直接列出 NVIDIA、RTX 50 CUDA、TensorRT、OpenCL、CPU 与无引擎版；TensorRT 只展示
必须下载的两个 `.7z` 分卷，README、manifest 和 SHA-256 等发布元数据不进入用户界面。
首页也不展示 R2、镜像切换或对象存储等实现细节。
下载页图标来自 Bootstrap Icons（MIT），已随仓库保存并内联，不依赖第三方 CDN。

R2 自定义域名本身不会把 `/` 自动映射到 `index.html`。Cloudflare URL 重写规则必须使用
`URI Path equals /`，将路径重写为 `/index.html`，并保留原查询字符串。不要按完整 URL
精确匹配，否则 `/?source=...` 这类正常链接会返回 404。发布器会用带缓存穿透参数的根地址
验证这条规则。

镜像资产总量不得超过 `9,000,000,000` 字节。门禁失败、旧版本对象清理失败或任一 SHA-256
不一致时，GitHub Release 保持原状态，不会被晋升为正式版。

## 发布流程

正式版只能通过 `.github/workflows/promote-stable-release.yml` 晋升。手动运行时必须输入两次
完全相同的 release tag。工作流按以下顺序执行：

1. 从 GitHub Release API 读取资产大小和 SHA-256，执行严格白名单与 9 GB 门禁。
2. 先把下载首页切换到 GitHub 维护模式，再删除旧 R2 正式版对象。
3. 使用 GitHub HTTP Range 与 R2 multipart 流式上传，不在 runner 保存完整大包。
4. 上传过程中计算完整 SHA-256；上传后再核对 R2 对象大小和 SHA 元数据。
5. 通过自定义域名检查 HTTPS、长度、Range、缓存、下载响应头和根首页重写。
6. 发布 catalog 与下载首页，最后发布签名 envelope 作为稳定频道的激活指针。
7. 使用缓存穿透参数逐字节核对公网首页、catalog 和 envelope 与本次上传内容一致。
8. 将 v2 签名清单、catalog 和旧客户端使用的 v1 GitHub 清单上传到 Release。
9. 最后才把 GitHub pre-release 改为正式版和 latest。

重复执行同一 tag 会复用大小与 SHA 已匹配的 R2 对象。已是正式版时，只有明确启用
`allow_stable_recovery` 才能恢复稳定频道。

## GitHub 配置

GitHub `stable-release` Environment 使用以下配置。R2 凭据必须限定到单个桶的
`Object Read & Write`，不能授予管理其他桶的权限。

Secrets：

- `CLOUDFLARE_R2_ACCOUNT_ID`
- `CLOUDFLARE_R2_ACCESS_KEY_ID`
- `CLOUDFLARE_R2_SECRET_ACCESS_KEY`
- `UPDATE_SIGNING_PRIVATE_KEY`

Variables：

- `CLOUDFLARE_R2_BUCKET=lizzieyzy-next-downloads`
- `R2_PUBLIC_BASE_URL=https://download.goagent.top`

Ed25519 私钥只能存在于 GitHub Secret；应用内只包含公钥。更换签名密钥时必须先发布同时
信任新旧公钥的客户端，再切换发布工作流，最后在旧客户端覆盖率足够后移除旧公钥。

## 客户端行为

应用只在用户手动点击“检查更新”时联网，不在启动时自动检查。新客户端依次读取 R2 与
GitHub 上的签名 v2 清单，签名、版本、大小或 SHA-256 不正确时拒绝安装。

- Windows 使用 `core-update` 原位更新并保留用户数据、引擎和权重。
- macOS 按 CPU 架构下载并校验 DMG，随后打开 DMG，不直接修改已签名 App。
- Linux 从 GitHub 下载匹配 flavor 的 zip，完成后打开下载目录。
- 下载使用 `.part` 断点续传，支持暂停、继续、取消和重试；R2 失败会带着已有进度切换
  GitHub。服务器忽略 Range 时会安全清空并重下，不拼接错误内容。

旧客户端继续读取 GitHub 上的 v1 清单，因此可以先升级到支持 R2 的版本。

## 发布后验收

除完整 Maven 测试和打包外，正式晋升后必须确认：

- `https://download.goagent.top/` 显示正确 stable tag，且没有 pre-release。
- 13 个对象均返回 HTTPS 200、正确 `Content-Length`、`Accept-Ranges: bytes`、
  `Content-Disposition: attachment` 和 immutable 缓存策略。
- `Range: bytes=0-0` 返回 206 和正确 `Content-Range`。
- R2 对象总量小于 9 GB，`releases/` 下不存在旧正式版目录。
- Windows 断网续传与 R2 到 GitHub 切换、macOS 两种芯片 DMG、Linux GitHub 下载均通过真机验收。
- Release 正文中的镜像资产以 R2 为主链接，并保留 GitHub 全量备用入口。
