# AI commentary: ChatGPT sign-in and API keys

## 中文使用说明

打开 **AI 解说 → 设置**，在并列的 **ChatGPT 登录 / API Key** 中自行选择。
ChatGPT 登录通过系统浏览器完成，软件不收集密码；登录并授权后选择模型并保存。
两种方式分别保留配置，切换不会清除另一种方式的凭据，也不会自动切换计费方式。
KataGo 仍负责棋局计算，ChatGPT 只根据分析证据讲解；套餐资格和共享额度以 OpenAI 为准。
额度不足时点击 **管理用量**。安全存储不可用时只保持本次会话，关闭后需要重新登录。

本机 Swing 窗口截图（未登录，不包含真实账号）：

| ChatGPT 登录 | API Key |
| --- | --- |
| ![ChatGPT 登录设置](screenshots/chatgpt-login-zh-CN.png) | ![API Key 设置](screenshots/chatgpt-api-key-zh-CN.png) |

## Choosing a connection

Open **AI commentary > Settings**. **ChatGPT login** and **API Key** are equal alternatives.
New installations ask you to choose; existing API-key configurations stay selected. Switching
methods neither deletes the other configuration nor silently changes who pays for requests.

With ChatGPT, select **Continue with ChatGPT** and finish authorization in your system browser.
LizzieYzy Next never asks for a ChatGPT password. A one-time confirmation explains plan usage.
Select a model from the current account's catalog, then save. **Manage usage** opens ChatGPT's
own usage settings. Availability depends on your account, workspace, region and OpenAI policy;
this is not unlimited free inference. An app-specific limit does not necessarily mean your
entire ChatGPT plan is exhausted.

With **API Key**, the existing server URL, key, model discovery and secure-storage options
remain available. API-key billing is separate. A failed ChatGPT request never falls back to
an API key, changes the model, or repeats paid inference automatically.

KataGo still calculates the position. The selected analysis evidence and question are sent
to the selected commentary provider. Only completed commentary may be written into SGF;
an interrupted stream remains visibly incomplete.

## Security and protocol

- Use only official dynamic registration, OAuth authorization-code + S256 PKCE, a fresh
  state and nonce, and `127.0.0.1:<ephemeral-port>/auth/callback`.
- Validate the signed RS256 identity against OpenAI JWKS, issuer, audience, expiry and nonce.
  Reauthorization also validates the saved client and subject. Email never identifies a grant.
- Device registration is user-scoped. Different issued clients, account/workspace subjects,
  API providers and Zhizi credentials occupy different namespaces.
- Store each rotating access/refresh/identity token set together in macOS Keychain, Windows
  user DPAPI, or Linux Secret Service. Never fall back to plaintext or Base64 files. If secure
  persistence fails, disclose session-only operation.
- Serialize refresh with an OS file lock and reload the token set while holding that lock.
  A temporary network error preserves credentials; confirmed revoked refresh tokens are
  tombstoned and removed. Signing out retains only registration metadata for reauthorization.
- Logout cancels active requests, attempts official refresh-token revocation, and clears
  local credentials even when remote revocation cannot be confirmed. In that case disconnect
  the application in ChatGPT settings as well.
- The Responses request uses the account's listed model, `store: false`, `stream: true`, an
  input array and developer/user/assistant roles. No private ChatGPT endpoints, unsupported
  generation parameters, tools or server-side conversation persistence are used.
- Error diagnostics retain only bounded status/code/parameter/request-ID metadata, never raw
  response bodies, authorization URLs, codes or tokens. Account changes and game changes
  cancel old work; stale output cannot complete or write into another game's SGF.

Non-secret registration metadata is outside the portable installation:

| Platform | Location |
| --- | --- |
| macOS | `~/Library/Application Support/LizzieYzy Next/chatgpt` |
| Windows | `%APPDATA%/LizzieYzy Next/chatgpt` |
| Linux | `$XDG_CONFIG_HOME/lizzieyzy-next/chatgpt` (otherwise `~/.config/...`) |

Do not include this directory in public diagnostic bundles. It contains account identity
metadata, even though it contains no plaintext tokens. Existing API-key settings are unchanged.

## Verification

Automated fake-service tests exercise authorization, signature checks, forged callbacks,
denial, deadlines, scope changes, reconsent, model ordering, concurrent refresh, restart,
secure-store failure, revocation, incomplete streams and provider isolation.

```sh
mvn -B -Dfmt.skip=true -Djava.awt.headless=true verify
mvn -B -Dfmt.skip=true -Dtest=ChatGptSettingsNativeTest \
  -Dlizzie.test.chatgptNative=true \
  -Dlizzie.test.chatgptScreenshots=/tmp/chatgpt-ui-evidence test
```

The opt-in native test opens the real settings dialog in every shipped locale. Run separately
on each actual platform; macOS native results do not prove Windows DPI behavior.

`ChatGptLiveAcceptanceCli` in test sources provides explicit `settings`, `check`, `generate`
and `logout` modes, taking a dedicated temporary directory. It uses a separate registration,
opens normal browser login, and prints only status flags/counts. `generate` performs one
real request using explicitly synthetic candidate evidence, not a playing-strength test.
It must never be run automatically by CI. The account owner completes login themselves.
Run `check` in a new JVM to verify secure restart, and `logout` before removing its directory.
Do not mark real authorization/inference or Windows acceptance passed without corresponding
evidence. This feature does not submit an app to OpenAI's directory or publish a release.

## Official references

- [Sign-in contract](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Profiles and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Errors and recovery](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery)
- [UI/UX guidelines](https://developers.openai.com/siwc/ui-ux-guidelines)
- [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
