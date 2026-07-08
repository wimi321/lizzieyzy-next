# Network Proxy Settings Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one application-level proxy setting for Java-side outbound networking, with modes `direct`, `system`, and `manual`. The first manual default should be `127.0.0.1:7897`. Existing update, download, API, HttpClient, OkHttp, and Java-WebSocket callers must use the shared decision path.

**Architecture:** Introduce a small `featurecat.lizzie.util.NetworkProxy` helper as the only proxy decision point for Java URLConnection, Java `HttpClient`, OkHttp, and Java-WebSocket clients. Persist settings in existing `ui` config JSON and expose controls in `ConfigDialog2` Advanced settings. Callers do not parse proxy config directly.

**Tech Stack:** Java 11+, Swing, `java.net.Proxy`, `java.net.ProxySelector`, Java `HttpClient`, OkHttp, Java-WebSocket, Maven/JUnit.

---

## Source Documents

- Spec: `docs/specs/2026-07-08-network-proxy-settings-design.md`
- Target baseline: branch `proxy-settings-design`, based on current `upstream/main`

## Assumptions

- Implement in a clean worktree checked out at `proxy-settings-design`; do not implement on the currently dirty feature branch.
- `manual` mode supports HTTP proxy only in v1.
- `system` mode relies on Java's default `ProxySelector`; switching into or out of `system` mode during one app session shows a restart notice instead of promising immediate global behavior.
- Raw `java.net.Socket` classes listed in the spec remain intentionally direct in v1.
- UI labels can be Chinese to match the existing settings dialog.

## Work Breakdown

### 0. Prepare Workspace

- [ ] Verify current docs and target branch.
  - Run:
    ```bash
    git fetch upstream main
    git rev-parse proxy-settings-design
    git show proxy-settings-design:docs/specs/2026-07-08-network-proxy-settings-design.md >/tmp/proxy-spec-check.md
    ```
  - Verify: the spec exists on `proxy-settings-design` and mentions `127.0.0.1:7897`, Java-WebSocket, Socket.IO factories, and invalid config behavior.

- [ ] Create or switch to a clean implementation worktree.
  - Preferred if the partial clone can materialize files:
    ```bash
    git worktree add ../lizzieyzy-next-proxy proxy-settings-design
    cd ../lizzieyzy-next-proxy
    ```
  - Fallback if `git worktree add` still fails because the partial clone cannot fetch blobs: coordinate with the main agent before using the existing checkout. Do not overwrite unrelated untracked files.
  - Verify:
    ```bash
    git status --short
    ```

### 1. Add Proxy Config Defaults and Helper Tests First

- [ ] Add focused tests for proxy config parsing and URL/URI decisions.
  - New file: `src/test/java/featurecat/lizzie/util/NetworkProxyTest.java`
  - Cover:
    - missing keys -> `direct`
    - `direct` -> `Proxy.NO_PROXY`
    - `manual` with `127.0.0.1:7897` -> HTTP proxy address and port
    - `manual` with blank host, non-numeric port, out-of-range port -> invalid config exception
    - unknown mode -> invalid config exception
    - `ws://host/path` maps to system lookup URI `http://host/path`
    - `wss://host/path` maps to system lookup URI `https://host/path`
    - Java `HttpClient.Builder` gets the expected `ProxySelector`
    - OkHttp builder gets the expected `Proxy`
  - Verification command:
    ```bash
    mvn -Dtest=NetworkProxyTest test
    ```
  - Expected before implementation: test compile fails because `NetworkProxy` does not exist.

- [ ] Implement config defaults.
  - File: `src/main/java/featurecat/lizzie/Config.java`
  - Add default keys under `ui`:
    - `network-proxy-mode`: `"direct"`
    - `network-proxy-host`: `"127.0.0.1"`
    - `network-proxy-port`: `7897`
  - Verify by inspecting the generated/default config path used by existing tests if there is a config fixture test; otherwise this is covered by `NetworkProxyTest`.

- [ ] Implement the shared helper.
  - New file: `src/main/java/featurecat/lizzie/util/NetworkProxy.java`
  - Public API:
    ```java
    public final class NetworkProxy {
      public static final String MODE_DIRECT = "direct";
      public static final String MODE_SYSTEM = "system";
      public static final String MODE_MANUAL = "manual";

      public static void installSystemProxyPropertyFromSavedConfig();
      public static URLConnection openConnection(URL url) throws IOException;
      public static HttpClient.Builder configure(HttpClient.Builder builder);
      public static OkHttpClient.Builder configure(OkHttpClient.Builder builder);
      public static void configure(WebSocketClient client);
    }
    ```
  - Internal helpers should keep tests simple:
    - read from `Lizzie.config.uiConfig.optString/optInt`
    - `Proxy proxyForUrl(URL url)`
    - `ProxySelector proxySelector()`
    - `URI systemLookupUriForWebSocket(URI uri)`
    - `Proxy firstHttpSystemProxy(URI uri)`
  - Invalid stored values should throw one helper-owned unchecked exception, for example `NetworkProxy.InvalidNetworkProxyConfigException extends IllegalArgumentException`, with messages that name the bad key.
  - `openConnection(URL)` behavior:
    - `direct`: `url.openConnection(Proxy.NO_PROXY)`
    - `manual`: `url.openConnection(manualProxy)`
    - `system`: `url.openConnection()`
  - `configure(HttpClient.Builder)` behavior:
    - `direct`: `builder.proxy(ProxySelector.of(null))` is not valid; use a helper `ProxySelector` that always returns `Proxy.NO_PROXY`, or omit proxy only if tests prove Java will not use ambient system proxy.
    - `manual`: `builder.proxy(ProxySelector.of(manualSocketAddress))`
    - `system`: `builder.proxy(ProxySelector.getDefault())`
  - `configure(OkHttpClient.Builder)` behavior:
    - `direct`: `builder.proxy(Proxy.NO_PROXY)`
    - `manual`: `builder.proxy(manualProxy)`
    - `system`: `builder.proxySelector(ProxySelector.getDefault())`
  - `configure(WebSocketClient)` behavior:
    - `direct`: `client.setProxy(Proxy.NO_PROXY)`
    - `manual`: `client.setProxy(manualProxy)`
    - `system`: map `ws`/`wss` to `http`/`https`, select first HTTP proxy from `ProxySelector.getDefault()`, else `Proxy.NO_PROXY`
  - Verify:
    ```bash
    mvn -Dtest=NetworkProxyTest test
    ```

- [ ] Commit helper and tests.
  - Run:
    ```bash
    git status --short
    git add src/main/java/featurecat/lizzie/Config.java src/main/java/featurecat/lizzie/util/NetworkProxy.java src/test/java/featurecat/lizzie/util/NetworkProxyTest.java
    git commit -m "feat: add shared network proxy helper"
    ```

### 2. Add Settings UI and Startup Hook

- [ ] Add proxy controls to the Advanced settings card.
  - File: `src/main/java/featurecat/lizzie/gui/ConfigDialog2.java`
  - Existing anchor: advanced card setup near `createModernSettingsCard(tr("Advanced Settings"))`.
  - Add fields near other advanced network/runtime settings:
    - `JComboBox<String> comboNetworkProxyMode`
    - `JTextField txtNetworkProxyHost`
    - `JTextField txtNetworkProxyPort`
    - restart hint `JLabel`
  - Suggested UI text:
    - label: `网络代理`
    - options: `不使用代理`, `使用系统代理设置`, `手动配置代理`
    - host label: `代理主机`
    - port label: `代理端口`
    - hint: `切换系统代理模式后需重启程序才能完全生效。`
  - Host and port fields are enabled only when mode is `manual`.
  - Do not add a new settings page.

- [ ] Validate UI before hiding the dialog.
  - File: `src/main/java/featurecat/lizzie/gui/ConfigDialog2.java`
  - Existing issue: the OK action currently hides the dialog before saving.
  - Add a small method, for example:
    ```java
    private boolean validateNetworkProxyInputs()
    ```
  - Call it before `setVisible(false)` in the OK action.
  - Validation rules:
    - if mode is not manual, always pass
    - manual host must not be blank
    - manual port must be integer in `1..65535`
    - on failure, show the existing message dialog helper used by this class and keep the settings dialog open

- [ ] Persist UI values.
  - File: `src/main/java/featurecat/lizzie/gui/ConfigDialog2.java`
  - In `saveConfig()`, write:
    - `Lizzie.config.uiConfig.put("network-proxy-mode", selectedModeValue)`
    - `Lizzie.config.uiConfig.put("network-proxy-host", txtNetworkProxyHost.getText().trim())`
    - `Lizzie.config.uiConfig.put("network-proxy-port", parsedPort)`
  - Read existing values during initialization, defaulting to `direct`, `127.0.0.1`, and `7897`.

- [ ] Install the system proxy property during startup.
  - File: `src/main/java/featurecat/lizzie/Lizzie.java`
  - Add import for `NetworkProxy`.
  - Immediately after `config = new Config();`, call:
    ```java
    NetworkProxy.installSystemProxyPropertyFromSavedConfig();
    ```
  - Keep it before `Utils.applyMaintainedDefaultSettings()` and before any network client initialization.

- [ ] Verify UI/startup compile.
  - Run:
    ```bash
    mvn -DskipTests compile
    mvn -Dtest=NetworkProxyTest test
    ```

- [ ] Commit UI and startup changes.
  - Run:
    ```bash
    git status --short
    git add src/main/java/featurecat/lizzie/Lizzie.java src/main/java/featurecat/lizzie/gui/ConfigDialog2.java
    git commit -m "feat: add network proxy settings UI"
    ```

### 3. Migrate Existing Java Network Callers

- [ ] Migrate update manifest and installer downloads.
  - File: `src/main/java/featurecat/lizzie/update/WindowsUpdateService.java`
  - Replace direct `url.openConnection()` calls with `NetworkProxy.openConnection(url)`.
  - Preserve existing timeout, header, and redirect behavior.

- [ ] Migrate KataGo and runtime download/probe helpers.
  - Files:
    - `src/main/java/featurecat/lizzie/analysis/KataGoAutoSetupHelper.java`
    - `src/main/java/featurecat/lizzie/analysis/KataGoRuntimeHelper.java`
  - Replace direct `openConnection()` calls with `NetworkProxy.openConnection(url)`.
  - Preserve existing timeouts, User-Agent, progress callbacks, and file streaming logic.

- [ ] Migrate Fox, Tencent, Yike, and SGF fetch URLConnection callers.
  - Files:
    - `src/main/java/featurecat/lizzie/rules/GetFoxRequest.java`
    - `src/main/java/featurecat/lizzie/rules/GetTencentRequest.java`
    - `src/main/java/featurecat/lizzie/rules/YikeApiClient.java`
    - `src/main/java/featurecat/lizzie/gui/OnlineDialog.java`
  - Replace `openConnection(Proxy.NO_PROXY)` and direct `url.openConnection()` with `NetworkProxy.openConnection(url)`.
  - Preserve request headers and timeouts.

- [ ] Migrate cloud/self-hosted analysis HTTP clients.
  - Files:
    - `src/main/java/featurecat/lizzie/analysis/remote/ZhiziApiClient.java`
    - `src/main/java/featurecat/lizzie/analysis/remote/KataGoAnalysisWebSocketTransport.java`
  - Replace bare `HttpClient.newBuilder()` with:
    ```java
    NetworkProxy.configure(HttpClient.newBuilder())
    ```
  - Preserve existing timeouts and executor configuration.

- [ ] Migrate Socket.IO OkHttp transport.
  - File: `src/main/java/featurecat/lizzie/analysis/remote/ZhiziGtpTransport.java`
  - Replace `new OkHttpClient()` with a configured builder:
    ```java
    OkHttpClient socketHttpClient = NetworkProxy.configure(new OkHttpClient.Builder()).build();
    ```
  - Ensure the same `socketHttpClient` is still assigned to both:
    - `options.callFactory`
    - `options.webSocketFactory`

- [ ] Migrate generic Ajax helper.
  - File: `src/main/java/featurecat/lizzie/util/AjaxHttpRequest.java`
  - Replace direct `openConnection` path with `NetworkProxy.openConnection(url)`.
  - Remove or narrow any now-unused `Proxy` field only if this migration makes it unused.

- [ ] Migrate Java-WebSocket usage.
  - File: `src/main/java/featurecat/lizzie/gui/OnlineDialog.java`
  - After the anonymous `new WebSocketClient(uri) { ... }` assignment and before `client.connect()`, call:
    ```java
    NetworkProxy.configure(client);
    ```
  - Preserve callbacks and reconnect behavior.

- [ ] Verify targeted compile and helper tests.
  - Run:
    ```bash
    mvn -DskipTests compile
    mvn -Dtest=NetworkProxyTest test
    ```

- [ ] Commit caller migration.
  - Run:
    ```bash
    git status --short
    git add \
      src/main/java/featurecat/lizzie/update/WindowsUpdateService.java \
      src/main/java/featurecat/lizzie/analysis/KataGoAutoSetupHelper.java \
      src/main/java/featurecat/lizzie/analysis/KataGoRuntimeHelper.java \
      src/main/java/featurecat/lizzie/rules/GetFoxRequest.java \
      src/main/java/featurecat/lizzie/rules/GetTencentRequest.java \
      src/main/java/featurecat/lizzie/rules/YikeApiClient.java \
      src/main/java/featurecat/lizzie/gui/OnlineDialog.java \
      src/main/java/featurecat/lizzie/analysis/remote/ZhiziApiClient.java \
      src/main/java/featurecat/lizzie/analysis/remote/KataGoAnalysisWebSocketTransport.java \
      src/main/java/featurecat/lizzie/analysis/remote/ZhiziGtpTransport.java \
      src/main/java/featurecat/lizzie/util/AjaxHttpRequest.java
    git commit -m "feat: route Java network callers through proxy settings"
    ```

### 4. Add Inventory Guard

- [ ] Add a source inventory regression test.
  - New file: `src/test/java/featurecat/lizzie/util/NetworkProxyInventoryTest.java`
  - Scan `src/main/java` for these patterns:
    - `.openConnection(`
    - `Proxy.NO_PROXY`
    - `HttpClient.newBuilder()`
    - `new OkHttpClient()`
    - `new WebSocketClient(`
  - Allowed cases:
    - `src/main/java/featurecat/lizzie/util/NetworkProxy.java`
    - `new WebSocketClient(` in `OnlineDialog.java` only when the same file also contains `NetworkProxy.configure(client)`
    - local/test-only WebSocket clients if they exist outside `src/main/java`
  - Keep the guard narrow. Do not block the raw socket exception classes unless this test explicitly scans `new Socket(`.

- [ ] Add an optional raw socket inventory assertion if it is cheap.
  - If scanning `new Socket(`, allow only:
    - `src/main/java/featurecat/lizzie/gui/SocketLoggin.java`
    - `src/main/java/featurecat/lizzie/gui/SocketGetFile.java`
    - `src/main/java/featurecat/lizzie/gui/SocketKifuSearch.java`
    - `src/main/java/featurecat/lizzie/gui/SocketUpfile.java`
    - `src/main/java/featurecat/lizzie/gui/SocketEditFile.java`
    - local server sockets/listeners
  - If identifying local server sockets becomes noisy, skip the raw socket assertion and rely on the spec's explicit v1 exception list.

- [ ] Verify inventory guard.
  - Run:
    ```bash
    mvn -Dtest=NetworkProxyTest,NetworkProxyInventoryTest test
    ```

- [ ] Commit inventory guard.
  - Run:
    ```bash
    git status --short
    git add src/test/java/featurecat/lizzie/util/NetworkProxyInventoryTest.java
    git commit -m "test: guard proxy-aware Java networking"
    ```

### 5. End-to-End Verification

- [ ] Run focused tests.
  - Command:
    ```bash
    mvn -Dtest=NetworkProxyTest,NetworkProxyInventoryTest test
    ```

- [ ] Run compile.
  - Command:
    ```bash
    mvn -DskipTests compile
    ```

- [ ] Run source inventory manually once for review.
  - Command:
    ```bash
    git grep -n -e "openConnection" -e "Proxy.NO_PROXY" -e "HttpClient.newBuilder" -e "new OkHttpClient" -e "new WebSocketClient" -- src/main/java
    ```
  - Expected: all remaining hits are either inside `NetworkProxy` or are explicitly allowed by `NetworkProxyInventoryTest`.

- [ ] Optional Windows smoke check after WSL commit is ready.
  - In Windows clone `D:\dev\weiqi\lizzieyzy-next`, run:
    ```powershell
    git fetch
    git pull --ff-only
    .\.tools\apache-maven-3.9.10\bin\mvn.cmd -DskipTests compile
    ```
  - Only do this after the WSL implementation branch has been pushed or otherwise synchronized to the Windows clone.

### 6. Final Review Checklist

- [ ] Confirm no caller parses `network-proxy-*` keys except `NetworkProxy` and `ConfigDialog2`.
- [ ] Confirm manual default fields are `127.0.0.1` and `7897`, but default mode remains `direct`.
- [ ] Confirm `system` mode startup hook is placed immediately after config load.
- [ ] Confirm Socket.IO uses the same configured `OkHttpClient` for both `callFactory` and `webSocketFactory`.
- [ ] Confirm Java-WebSocket calls `NetworkProxy.configure(client)` before `connect()`.
- [ ] Confirm invalid manual UI input keeps the settings dialog open.
- [ ] Confirm no unrelated files are staged.

## Commit Plan

Use separate commits so review can isolate risk:

1. `feat: add shared network proxy helper`
2. `feat: add network proxy settings UI`
3. `feat: route Java network callers through proxy settings`
4. `test: guard proxy-aware Java networking`

Do not squash during implementation. Squashing can be decided only after review.
