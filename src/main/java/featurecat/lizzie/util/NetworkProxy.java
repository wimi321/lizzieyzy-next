package featurecat.lizzie.util;

import featurecat.lizzie.Lizzie;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Objects;
import okhttp3.OkHttpClient;
import org.java_websocket.client.WebSocketClient;
import org.json.JSONObject;

public final class NetworkProxy {
  public static final String KEY_PROXY_MODE = "network-proxy-mode";
  public static final String KEY_PROXY_HOST = "network-proxy-host";
  public static final String KEY_PROXY_PORT = "network-proxy-port";

  public static final String MODE_DIRECT = "direct";
  public static final String MODE_SYSTEM = "system";
  public static final String MODE_MANUAL = "manual";
  public static final String DEFAULT_MODE = MODE_DIRECT;

  private NetworkProxy() {}

  public static void installSystemProxyPropertyFromSavedConfig() {
    if (MODE_SYSTEM.equals(currentUiConfig().optString(KEY_PROXY_MODE, DEFAULT_MODE).trim())) {
      System.setProperty("java.net.useSystemProxies", "true");
    }
  }

  public static URLConnection openConnection(URL url) throws IOException {
    try {
      Proxy proxy = proxyForUrl(url, currentUiConfig());
      return proxy == null ? url.openConnection() : url.openConnection(proxy);
    } catch (InvalidNetworkProxyConfigException e) {
      throw invalidSettings(e);
    }
  }

  public static ProxySelector proxySelector() throws IOException {
    try {
      return proxySelector(currentUiConfig());
    } catch (InvalidNetworkProxyConfigException e) {
      throw invalidSettings(e);
    }
  }

  public static HttpClient.Builder configure(HttpClient.Builder builder) throws IOException {
    try {
      return configure(builder, currentUiConfig());
    } catch (InvalidNetworkProxyConfigException e) {
      throw invalidSettings(e);
    }
  }

  public static OkHttpClient.Builder configure(OkHttpClient.Builder builder) throws IOException {
    try {
      return configure(builder, currentUiConfig());
    } catch (InvalidNetworkProxyConfigException e) {
      throw invalidSettings(e);
    }
  }

  public static void configure(WebSocketClient client) throws IOException {
    try {
      client.setProxy(proxyForWebSocket(client.getURI(), currentUiConfig()));
    } catch (InvalidNetworkProxyConfigException e) {
      throw invalidSettings(e);
    }
  }

  static HttpClient.Builder configure(HttpClient.Builder builder, JSONObject uiConfig) {
    return builder.proxy(proxySelector(uiConfig));
  }

  static OkHttpClient.Builder configure(OkHttpClient.Builder builder, JSONObject uiConfig) {
    Settings settings = settings(uiConfig);
    switch (settings.mode) {
      case MODE_DIRECT:
        return builder.proxy(Proxy.NO_PROXY);
      case MODE_MANUAL:
        return builder.proxy(settings.manualProxy());
      case MODE_SYSTEM:
        return builder.proxySelector(defaultProxySelector());
      default:
        throw new InvalidNetworkProxyConfigException(KEY_PROXY_MODE, settings.mode);
    }
  }

  static Proxy proxyForUrl(URL url, JSONObject uiConfig) {
    Objects.requireNonNull(url, "url");
    Settings settings = settings(uiConfig);
    switch (settings.mode) {
      case MODE_DIRECT:
        return Proxy.NO_PROXY;
      case MODE_MANUAL:
        return settings.manualProxy();
      case MODE_SYSTEM:
        return null;
      default:
        throw new InvalidNetworkProxyConfigException(KEY_PROXY_MODE, settings.mode);
    }
  }

  static ProxySelector proxySelector(JSONObject uiConfig) {
    Settings settings = settings(uiConfig);
    switch (settings.mode) {
      case MODE_DIRECT:
        return directSelector();
      case MODE_MANUAL:
        return ProxySelector.of(settings.address());
      case MODE_SYSTEM:
        return defaultProxySelector();
      default:
        throw new InvalidNetworkProxyConfigException(KEY_PROXY_MODE, settings.mode);
    }
  }

  static Proxy proxyForWebSocket(URI uri, JSONObject uiConfig) {
    Settings settings = settings(uiConfig);
    switch (settings.mode) {
      case MODE_DIRECT:
        return Proxy.NO_PROXY;
      case MODE_MANUAL:
        return settings.manualProxy();
      case MODE_SYSTEM:
        return firstHttpSystemProxy(systemLookupUriForWebSocket(uri));
      default:
        throw new InvalidNetworkProxyConfigException(KEY_PROXY_MODE, settings.mode);
    }
  }

  static URI systemLookupUriForWebSocket(URI uri) {
    String scheme = uri.getScheme();
    if ("ws".equalsIgnoreCase(scheme)) {
      return URI.create("http" + uri.toString().substring(scheme.length()));
    }
    if ("wss".equalsIgnoreCase(scheme)) {
      return URI.create("https" + uri.toString().substring(scheme.length()));
    }
    return uri;
  }

  static Proxy firstHttpSystemProxy(URI uri) {
    List<Proxy> proxies = defaultProxySelector().select(uri);
    if (proxies != null) {
      for (Proxy proxy : proxies) {
        if (proxy.type() == Proxy.Type.HTTP) {
          return proxy;
        }
      }
    }
    return Proxy.NO_PROXY;
  }

  private static ProxySelector defaultProxySelector() {
    ProxySelector selector = ProxySelector.getDefault();
    return selector == null ? directSelector() : selector;
  }

  private static ProxySelector directSelector() {
    return DirectSelectorHolder.INSTANCE;
  }

  private static JSONObject currentUiConfig() {
    if (Lizzie.config == null || Lizzie.config.uiConfig == null) {
      return new JSONObject();
    }
    return Lizzie.config.uiConfig;
  }

  private static Settings settings(JSONObject uiConfig) {
    JSONObject ui = uiConfig == null ? new JSONObject() : uiConfig;
    String mode =
        ui.has(KEY_PROXY_MODE) ? ui.optString(KEY_PROXY_MODE, "").trim() : DEFAULT_MODE;
    switch (mode) {
      case MODE_DIRECT:
      case MODE_SYSTEM:
        return new Settings(mode, "", 0);
      case MODE_MANUAL:
        return manualSettings(ui);
      default:
        throw new InvalidNetworkProxyConfigException(KEY_PROXY_MODE, mode);
    }
  }

  private static Settings manualSettings(JSONObject ui) {
    String host = ui.optString(KEY_PROXY_HOST, "").trim();
    if (host.isEmpty()) {
      throw new InvalidNetworkProxyConfigException(KEY_PROXY_HOST, host);
    }
    int port = parsePort(ui.opt(KEY_PROXY_PORT));
    if (port < 1 || port > 65535) {
      throw new InvalidNetworkProxyConfigException(KEY_PROXY_PORT, String.valueOf(port));
    }
    return new Settings(MODE_MANUAL, host, port);
  }

  private static int parsePort(Object rawPort) {
    if (rawPort instanceof Number || rawPort instanceof String) {
      try {
        return Integer.parseInt(String.valueOf(rawPort).trim());
      } catch (NumberFormatException e) {
        throw new InvalidNetworkProxyConfigException(KEY_PROXY_PORT, String.valueOf(rawPort));
      }
    }
    throw new InvalidNetworkProxyConfigException(KEY_PROXY_PORT, String.valueOf(rawPort));
  }

  private static IOException invalidSettings(InvalidNetworkProxyConfigException e) {
    return new IOException(
        "Network proxy settings are invalid. Open Settings > Advanced > Network Proxy and fix "
            + e.getMessage(),
        e);
  }

  public static final class InvalidNetworkProxyConfigException extends IllegalArgumentException {
    InvalidNetworkProxyConfigException(String key, String value) {
      super("Invalid " + key + ": " + value);
    }
  }

  private static final class DirectSelectorHolder {
    private static final ProxySelector INSTANCE =
        new ProxySelector() {
          @Override
          public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
          }

          @Override
          public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {}
        };
  }

  private static final class Settings {
    final String mode;
    final String host;
    final int port;

    Settings(String mode, String host, int port) {
      this.mode = mode;
      this.host = host;
      this.port = port;
    }

    InetSocketAddress address() {
      return InetSocketAddress.createUnresolved(host, port);
    }

    Proxy manualProxy() {
      return new Proxy(Proxy.Type.HTTP, address());
    }
  }
}
