# macOS 代码签名与公证

## 需要的 GitHub Secrets

在仓库的 `Settings → Secrets and variables → Actions` 里添加以下 secrets（缺任何一个都会自动跳过签名步骤，产物仍然是未签名的 DMG）：

| Secret | 作用 | 获取方式 |
|---|---|---|
| `APPLE_CERT_P12` | Developer ID Application 证书 (.p12) 的 base64 字符串 | 在 Keychain Access 导出 `.p12`，然后 `base64 -i cert.p12 -o cert.b64` |
| `APPLE_CERT_PASSWORD` | 上面 `.p12` 的密码（可为空） | 导出时设置的密码 |
| `APPLE_ID` | Apple ID 邮箱（用于 notarytool） | 你的开发者账号邮箱 |
| `APPLE_APP_PASSWORD` | app-specific password | https://appleid.apple.com/account/manage → App 专用密码 |
| `APPLE_TEAM_ID` | 10 字符的 Team ID | https://developer.apple.com/account → Membership Details |
| `APPLE_SIGN_IDENTITY` | 可选覆盖，例如 `Developer ID Application: Your Name (TEAMID)` | 有多个证书时可以手工指定 |

## 本地准备

1. 登录 https://developer.apple.com/account 确认你的 Team ID。
2. 到 `Certificates, Identifiers & Profiles → Certificates` 创建一个 `Developer ID Application` 证书（如果还没有）：
   - 在 Keychain Access → 证书助理 → 从证书颁发机构请求证书，保存到磁盘
   - 上传 CSR 到 Apple
   - 下载并双击 `.cer` 把它装到 Keychain Access
3. 在 Keychain Access 找到这个证书 + 对应私钥，右键 `导出 2 项...`，选 `.p12` 保存
4. 命令行把 p12 编码：
   ```bash
   base64 -i DeveloperID.p12 -o DeveloperID.p12.b64
   pbcopy < DeveloperID.p12.b64
   ```
   把结果粘贴到 `APPLE_CERT_P12` secret。
5. 到 https://appleid.apple.com/account/manage 创建 App 专用密码，放到 `APPLE_APP_PASSWORD`。

## Workflow 行为

`build-macos-arm64-release.yml` 和 `build-macos-amd64-release.yml` 在 jpackage 生成 DMG 之后，如果以上 secrets 都已配置，会调用 `scripts/sign_macos_release.sh`：

- 创建临时 keychain 导入证书
- 把 DMG 里的 `.app` 解出，用 `codesign --options runtime --timestamp --deep` 签名所有 `.dylib` 和可执行文件
- 重新打包成 DMG，再次签名 DMG
- 用 `xcrun notarytool submit --wait` 提交公证
- 用 `xcrun stapler staple` 附着公证票据
- 用 `spctl --assess` 复查通过

如果 secrets 没配置，脚本打印一行后 `exit 0`，构建流程完全不受影响。

## 验证

下载签名后的 DMG，在另一台 Mac 上直接双击。应该能直接打开，不再出现 "无法验证开发者" 的拦截。

命令行验证：

```bash
spctl --assess --type install -vvv path/to/LizzieYzy-Next.dmg
# 应该显示:
#   path: accepted
#   source=Notarized Developer ID
```

## 失败兜底

如果签名/公证失败，已上传到 GitHub Releases 的资产会是未签名版本（这步失败不会阻断 artifact 上传）。但 workflow 整体会标记失败，方便你看错误日志。

常见问题：
- `notarytool` 超时 → 重跑 workflow 即可
- `errSecInternalComponent` → p12 密码错误
- `No Developer ID Application identity` → 证书没能导入 keychain，检查 `APPLE_CERT_P12` 是否是 base64 后的完整字符串
