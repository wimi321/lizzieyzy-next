# Network Proxy Settings Design

## Background

This spec targets the current `upstream/main` networking tree used by the `proxy-settings-design` branch. Class names below refer to that tree.

LizzieYzy Next has several Java-side network paths today:

- Windows update manifest checks and update archive downloads.
- KataGo weight, HumanSL model, TensorRT, and NVIDIA runtime downloads.
- Fox, Tencent, and Yike Java API requests.
- Zhizi and custom remote-compute connections through `HttpClient`, WebSocket, OkHttp, and Socket.IO.

Some paths currently call plain `openConnection()`, while others explicitly use `Proxy.NO_PROXY`. A proxy option that only patches the updater would leave the next download or API feature with a different network policy.

## Goals

1. Add one saved proxy choice for Java-side app networking.
2. Support three modes: no proxy, system proxy, and manual proxy.
3. Make future Java HTTP/WebSocket callers reuse the same proxy decision.
4. Keep the first version small and testable.

## Non-Goals

- Do not promise control over external browser windows opened through `Desktop.browse(...)`.
- Do not promise control over external engine processes in the first version.
- Do not include proxy authentication, PAC parsing, per-host bypass rules, or SOCKS in the first version.
- Do not route raw `java.net.Socket` protocols in the first version. The legacy share/search socket classes (`SocketLoggin`, `SocketGetFile`, `SocketKifuSearch`, `SocketUpfile`, and `SocketEditFile`) should remain direct until SOCKS or a protocol-specific replacement is designed.
- Do not replace existing download, update, or remote-compute business logic with a new HTTP framework.

## User Settings

Persist the settings under the existing `ui` config object:

- `network-proxy-mode`: `direct`, `system`, or `manual`.
- `network-proxy-host`: manual proxy host, for example `127.0.0.1`.
- `network-proxy-port`: manual proxy port, for example `7897`.

Default mode should be `direct`, so first-time users keep the existing direct-network behavior unless they opt into a proxy.

Manual mode validates that host is non-empty and port is in `1..65535`. Invalid manual settings should be rejected when saving the settings dialog instead of silently falling back to direct.

Missing keys from older configs default to `direct`. Unknown mode values or invalid stored manual settings are treated as configuration errors during actual network operations: show a clear settings warning and require the user to save a valid mode before applying proxy changes.

Network callers must not silently fall back to direct when a saved proxy config is invalid. The helper should throw a small checked or unchecked configuration exception; UI-triggered callers show that message to the user, and background callers log it and surface the normal operation failure.

## UI

Add a small "Network Proxy" section to `ConfigDialog2` under Advanced:

- Proxy mode combo box: no proxy, system proxy, manual proxy.
- Host text field.
- Port text field.

Host and port are enabled only in manual mode. The visible example should use `127.0.0.1` and `7897`.

Saving applies `direct` and `manual` to future requests. Switching to or from `system` during the same run should show a restart-required notice unless the implementation proves, with tests, that the JVM default `ProxySelector` can be refreshed safely. Existing long-lived remote-compute connections may need reconnecting.

## Shared Proxy Helper

Add a small utility in `featurecat.lizzie.util`, for example `NetworkProxy`.

It should expose only the shapes the codebase needs:

- Open a `URLConnection` for a `URL`.
- Configure a `java.net.http.HttpClient.Builder`.
- Configure an `okhttp3.OkHttpClient.Builder`.
- Configure the existing `org.java_websocket.client.WebSocketClient` used by `OnlineDialog` through its `setProxy(Proxy)` API.
- Return a `ProxySelector` for code that must build a client outside the helper.

Mode behavior:

- `direct`: use `Proxy.NO_PROXY` for `URLConnection`, a no-proxy `ProxySelector` for `HttpClient`, and direct mode for OkHttp.
- `system`: use the JVM default `ProxySelector`. In `Lizzie.main`, immediately after `config = new Config()` and before `Utils.applyMaintainedDefaultSettings()` or any network client creation, read only the raw saved `network-proxy-mode` value and set `java.net.useSystemProxies=true` only when it is `system`. Startup must not validate manual host/port, and unknown or invalid mode values must not terminate startup. A saved `system` setting is fully supported after restart; runtime switching into or out of `system` is restart-required unless a tested refresh path is added. `direct` and `manual` must use explicit selectors/proxies so they do not accidentally inherit JVM default proxy behavior.
- `manual`: use `new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port))` and equivalent selectors/builders.

Java-WebSocket needs an explicit `Proxy` because it does not accept a `ProxySelector`. For `OnlineDialog`, map `direct` to `client.setProxy(Proxy.NO_PROXY)`, `manual` to the manual HTTP proxy, and `system` by converting the client URI from `ws`/`wss` to `http`/`https`, calling the default `ProxySelector.select(...)`, then applying the first usable HTTP proxy or `Proxy.NO_PROXY`.

Keep this helper stateless apart from reading `Lizzie.config.uiConfig`. No background service and no global mutable client cache are needed.

Required coverage should prefer the opener, builder, or selector methods. Callers should not construct a bare `Proxy` from settings because that cannot represent `system` mode.

## Required Java-Side Coverage

The first implementation must route these Java-side network paths through the shared helper:

- `WindowsUpdateService`: manifest fetch and component downloads.
- `KataGoAutoSetupHelper`: weight list fetches, weight downloads, and HumanSL downloads.
- `KataGoRuntimeHelper`: NVIDIA mirror probes, runtime downloads, TensorRT downloads, and release metadata fetches.
- `GetFoxRequest`, `GetTencentRequest`, `YikeApiClient`, and the remaining direct SGF fetch in `OnlineDialog`.
- `ZhiziApiClient` and `KataGoAnalysisWebSocketTransport` `HttpClient` builders.
- `ZhiziGtpTransport` OkHttp client creation for Socket.IO; the configured client must be assigned to both `options.callFactory` and `options.webSocketFactory`.
- `AjaxHttpRequest`, so older call sites inherit the same policy if they keep using it.
- `OnlineDialog` `org.java_websocket.client.WebSocketClient` through the `setProxy(...)` mapping described above.

Any newly added Java network caller should use this helper instead of calling `openConnection()` or constructing a bare client.

## Future Extensions

JCEF and external process support should be tracked as separate follow-up work:

- JCEF can later map manual/system proxy settings to Chromium proxy flags after checking initialization side effects.
- External engines can later receive `HTTP_PROXY` and `HTTPS_PROXY` environment variables through their `ProcessBuilder`, but only as best effort because engines may ignore them.

## Testing

Add focused tests for the helper:

- Direct mode returns no proxy.
- Manual mode builds an HTTP proxy for `127.0.0.1:7897`.
- Invalid manual host or port is rejected.
- Missing config keys default to `direct`.
- Unknown mode and invalid stored manual settings surface a configuration error instead of silently using direct.
- `HttpClient` and OkHttp configuration tests assert the actual selector/proxy choice: direct must not use the default selector, and manual must use `127.0.0.1:7897`.
- A Socket.IO test or focused seam test verifies that the configured OkHttp client is used for both `callFactory` and `webSocketFactory`.
- Add a cheap source inventory guard for direct `openConnection()`, `Proxy.NO_PROXY`, bare `HttpClient.newBuilder()`, bare `new OkHttpClient()`, and `new WebSocketClient(...)`. The guard should allow only the helper itself, local server sockets, local/test WebSocket clients, and the listed raw share/search socket exceptions; every other outbound Java HTTP/WebSocket caller must route through the helper.

For higher-level coverage, add one small test around `WindowsUpdateService` or the helper seam to prove update manifest fetches use the shared opener. Do not add real network tests.
