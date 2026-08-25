# Cloudflare R2 正式版下载与升级

用户唯一公开入口是 `https://goagent.top/download/`。`download.goagent.top` 只作为安装包、
公开目录和软件更新接口的技术后台；GitHub Release 始终保留完整资产、安装器、Linux 包、
历史版本和自动备用下载，pre-release 不上传 R2。

测试通道不走 R2。签名测试清单只发布到 GitHub：版本化 pre-release 上的
`lizzieyzy-next-update-envelope.json`，以及固定 tag `channel-beta` 上的同名指针。
客户端稳定地址是
`https://github.com/wimi321/lizzieyzy-next/releases/download/channel-beta/lizzieyzy-next-update-envelope.json`。
`channel-beta` 不是打包版本，只托管这份 envelope，保持 pre-release，不得 `make_latest`，
也不得带安装包。正式晋升不得改写该指针，也不得把测试安装包或测试指针上传到 R2。

## 固定资源范围

R2 桶名固定为 `lizzieyzy-next-downloads`，只保留一个正式版。发布脚本要求每个正式版恰好
包含以下 12 个镜像对象，数量或名称不一致会直接停止：

- 4 个 Windows 免安装包：OpenCL、CPU、统一 NVIDIA CUDA、无引擎
- `windows64.core-update.zip`
- macOS Apple Silicon 与 Intel 两个 DMG
- TensorRT `.7z.001`、`.7z.002`、README、manifest、SHA-256 文件

Windows 安装器、Linux 包、pre-release 和历史版本不占用 R2。版本化对象位于
`releases/<tag>/`，稳定频道使用：

- `channels/stable/update-envelope.json`
- `channels/stable/catalog.json`
- `index.html`

完整的普通用户下载界面由 GoAgent 官网维护，动态读取 stable catalog。R2 的 `index.html`
仅包含跳往 `https://goagent.top/download/` 的轻量兜底，不再生成第二套下载界面。

Cloudflare Redirect Rule 必须仅匹配 `download.goagent.top` 的 `/` 和 `/index.html`，以 301
跳转到 `https://goagent.top/download/` 并保留查询字符串。`/releases/*` 与 `/channels/*`
不能被重定向。发布器会同时验证两个根入口的 301，以及目录、更新清单和安装包接口。

镜像资产总量不得超过 `9,000,000,000` 字节。门禁失败、旧版本对象清理失败或任一 SHA-256
不一致时，GitHub Release 保持原状态，不会被晋升为正式版。

## 发布流程

正式版只能通过 `.github/workflows/promote-stable-release.yml` 晋升。手动运行时必须输入两次
完全相同的 release tag。工作流按以下顺序执行：

1. 从 GitHub Release API 读取资产大小和 SHA-256，执行严格白名单与 9 GB 门禁。
2. 先把 stable catalog 切换为 GitHub 原始文件地址并验证公网可用，再删除旧 R2 正式版对象。
3. 使用 GitHub HTTP Range 与 R2 multipart 流式上传，不在 runner 保存完整大包。
4. 上传过程中计算完整 SHA-256；上传后再核对 R2 对象大小和 SHA 元数据。
5. 通过自定义域名检查 HTTPS、长度、Range、缓存、下载响应头和根入口 301。
6. 发布 R2 主链接 catalog 与轻量跳转页，最后发布签名 envelope 作为稳定频道的激活指针。
7. 使用缓存穿透参数核对公网根入口、catalog 和 envelope 与本次发布一致。
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

发布脚本中的用户入口独立固定为 `https://goagent.top/download/`，不能用
`R2_PUBLIC_BASE_URL` 代替。前者供人访问，后者供目录、安装包和更新器使用。

Ed25519 私钥只能存在于 GitHub Secret；应用内只包含公钥。更换签名密钥时必须先发布同时
信任新旧公钥的客户端，再切换发布工作流，最后在旧客户端覆盖率足够后移除旧公钥。

## 客户端行为

帮助菜单的「检查更新」只打开检查更新页，不联网。用户在页上选择更新通道和更新源后点「检查更新」
才取清单。默认正式通道、官网源。正式通道只读取用户选择的那一个签名 v2 清单：官网
`download.goagent.top` 或 GitHub `releases/latest`，失败不会改试另一源。选择写入
`update-source=official|github`，缺省官网。测试通道只读固定指针
`https://github.com/wimi321/lizzieyzy-next/releases/download/channel-beta/lizzieyzy-next-update-envelope.json`，
页面显示 GitHub 为固定有效源，但不覆盖已记住的正式源；没有官网回退，也不扫 Releases API。
签名、通道、版本、大小或 SHA-256 不正确时拒绝安装。
通道选择写入 `update-channel`，缺省为正式；切换通道不改已安装文件，也不自动降级。

- Windows 使用 `core-update` 原位更新并保留用户数据、引擎和权重。
- macOS 按 CPU 架构下载并校验 DMG，随后打开 DMG，不直接修改已签名 App。
- Linux 从 GitHub 下载匹配 flavor 的 zip，完成后打开下载目录。
- 下载使用 `.part` 断点续传，支持暂停、继续、取消和重试；R2 失败会带着已有进度切换
  GitHub。服务器忽略 Range 时会安全清空并重下，不拼接错误内容。

旧客户端继续读取 GitHub 上的 v1 清单，因此可以先升级到支持 R2 的版本。

## 发布后验收

除完整 Maven 测试和打包外，正式晋升后必须确认：

- `https://goagent.top/download/` 显示正确 stable tag，且没有 pre-release。
- `https://www.goagent.top/download/`、`https://download.goagent.top/` 与
  `https://download.goagent.top/index.html` 均以 301 跳到统一官网下载页。
- 12 个对象均返回 HTTPS 200、正确 `Content-Length`、`Accept-Ranges: bytes`、
  `Content-Disposition: attachment` 和 immutable 缓存策略。
- `Range: bytes=0-0` 返回 206 和正确 `Content-Range`。
- R2 对象总量小于 9 GB，`releases/` 下不存在旧正式版目录。
- Windows 断网续传与 R2 到 GitHub 切换、macOS 两种芯片 DMG、Linux GitHub 下载均通过真机验收。
- Release 正文顶部指向统一官网下载页，正文中的具体文件仍全部使用 GitHub 原始链接。
