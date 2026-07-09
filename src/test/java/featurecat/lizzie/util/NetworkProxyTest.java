package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import okhttp3.OkHttpClient;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class NetworkProxyTest {
  @Test
  void missingKeysDefaultToDirect() {
    ProxySelector selector = NetworkProxy.proxySelector(new JSONObject());

    assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("https://example.test")));
  }

  @Test
  void directModeDoesNotUseDefaultProxySelector() {
    ProxySelector defaultSelector = ProxySelector.getDefault();
    ProxySelector selector = NetworkProxy.proxySelector(ui(NetworkProxy.MODE_DIRECT));

    assertNotSame(defaultSelector, selector);
    assertEquals(List.of(Proxy.NO_PROXY), selector.select(URI.create("https://example.test")));
  }

  @Test
  void manualModeUsesConfiguredHttpProxy() throws Exception {
    Proxy proxy = NetworkProxy.proxyForUrl(URI.create("https://example.test").toURL(), manualUi());

    assertEquals(Proxy.Type.HTTP, proxy.type());
    assertAddress(proxy, "127.0.0.1", 7897);
  }

  @Test
  void rejectsInvalidManualSettings() {
    assertThrows(
        NetworkProxy.InvalidNetworkProxyConfigException.class,
        () ->
            NetworkProxy.proxySelector(
                ui(NetworkProxy.MODE_MANUAL).put(NetworkProxy.KEY_PROXY_HOST, " ")));
    assertThrows(
        NetworkProxy.InvalidNetworkProxyConfigException.class,
        () ->
            NetworkProxy.proxySelector(
                ui(NetworkProxy.MODE_MANUAL).put(NetworkProxy.KEY_PROXY_PORT, "abc")));
    assertThrows(
        NetworkProxy.InvalidNetworkProxyConfigException.class,
        () ->
            NetworkProxy.proxySelector(
                ui(NetworkProxy.MODE_MANUAL).put(NetworkProxy.KEY_PROXY_PORT, 70000)));
  }

  @Test
  void rejectsUnknownMode() {
    assertThrows(
        NetworkProxy.InvalidNetworkProxyConfigException.class,
        () -> NetworkProxy.proxySelector(ui("surprise")));
  }

  @Test
  void mapsWebSocketUrisForSystemProxyLookup() {
    assertEquals(
        URI.create("http://example.test/socket?token=1"),
        NetworkProxy.systemLookupUriForWebSocket(URI.create("ws://example.test/socket?token=1")));
    assertEquals(
        URI.create("https://example.test/socket"),
        NetworkProxy.systemLookupUriForWebSocket(URI.create("wss://example.test/socket")));
  }

  @Test
  void configuresHttpClientBuilder() {
    HttpClient manualClient = NetworkProxy.configure(HttpClient.newBuilder(), manualUi()).build();
    Proxy manualProxy =
        manualClient.proxy().orElseThrow().select(URI.create("https://example.test")).get(0);

    assertAddress(manualProxy, "127.0.0.1", 7897);

    HttpClient directClient =
        NetworkProxy.configure(HttpClient.newBuilder(), ui(NetworkProxy.MODE_DIRECT)).build();
    assertEquals(
        List.of(Proxy.NO_PROXY),
        directClient.proxy().orElseThrow().select(URI.create("https://example.test")));
  }

  @Test
  void configuresOkHttpBuilder() {
    OkHttpClient manualClient =
        NetworkProxy.configure(new OkHttpClient.Builder(), manualUi()).build();
    assertAddress(manualClient.proxy(), "127.0.0.1", 7897);

    OkHttpClient directClient =
        NetworkProxy.configure(new OkHttpClient.Builder(), ui(NetworkProxy.MODE_DIRECT)).build();
    assertEquals(Proxy.NO_PROXY, directClient.proxy());
  }

  private static JSONObject manualUi() {
    return ui(NetworkProxy.MODE_MANUAL)
        .put(NetworkProxy.KEY_PROXY_HOST, "127.0.0.1")
        .put(NetworkProxy.KEY_PROXY_PORT, 7897);
  }

  private static JSONObject ui(String mode) {
    return new JSONObject().put(NetworkProxy.KEY_PROXY_MODE, mode);
  }

  private static void assertAddress(Proxy proxy, String host, int port) {
    InetSocketAddress address = (InetSocketAddress) proxy.address();
    assertEquals(host, address.getHostString());
    assertEquals(port, address.getPort());
  }
}
