package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import okhttp3.OkHttpClient;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

class NetworkProxyTest {
  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    Lizzie.config = null;
    System.clearProperty("java.net.useSystemProxies");
  }

  @Test
  void missingKeysDefaultToDirectProxy() {
    ProxySelector defaultSelector = ProxySelector.getDefault();
    ProxySelector selector = NetworkProxy.proxySelector(new JSONObject());

    assertNotSame(defaultSelector, selector);
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
                manualUi().put(NetworkProxy.KEY_PROXY_PORT, "abc")));
    assertThrows(
        NetworkProxy.InvalidNetworkProxyConfigException.class,
        () ->
            NetworkProxy.proxySelector(
                manualUi().put(NetworkProxy.KEY_PROXY_PORT, 70000)));
    assertThrows(
        NetworkProxy.InvalidNetworkProxyConfigException.class,
        () ->
            NetworkProxy.proxySelector(
                manualUi().put(NetworkProxy.KEY_PROXY_PORT, 7897.5)));
    assertThrows(
        NetworkProxy.InvalidNetworkProxyConfigException.class,
        () ->
            NetworkProxy.proxySelector(
                manualUi().put(NetworkProxy.KEY_PROXY_PORT, 4294975193L)));
  }

  @Test
  void rejectsUnknownMode() {
    assertThrows(
        NetworkProxy.InvalidNetworkProxyConfigException.class,
        () -> NetworkProxy.proxySelector(ui("surprise")));
    assertThrows(
        NetworkProxy.InvalidNetworkProxyConfigException.class,
        () -> NetworkProxy.proxySelector(ui(" ")));
  }

  @Test
  void startupSystemProxyHookIgnoresInvalidManualSettings() {
    Lizzie.config = ConfigTestHelper.createForTests(tempDir.resolve("config"));
    Lizzie.config.uiConfig =
        ui(NetworkProxy.MODE_MANUAL).put(NetworkProxy.KEY_PROXY_HOST, " ");

    assertDoesNotThrow(NetworkProxy::installSystemProxyPropertyFromSavedConfig);
  }

  @Test
  void startupSystemProxyHookDefersDirectSelectorInitialization() throws Exception {
    String javaExecutable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    String classPath =
        System.getProperty(
            "surefire.test.class.path", System.getProperty("java.class.path", ""));
    Process process =
        new ProcessBuilder(
                javaExecutable,
                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-cp",
                classPath,
                StartupProbe.class.getName(),
                tempDir.resolve("startup-probe").toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    assertEquals(0, process.waitFor(), output);
  }

  @Test
  void publicEntrypointsWrapInvalidStoredConfigAsIOException() throws Exception {
    Lizzie.config = ConfigTestHelper.createForTests(tempDir.resolve("config"));
    Lizzie.config.uiConfig =
        ui(NetworkProxy.MODE_MANUAL).put(NetworkProxy.KEY_PROXY_HOST, " ");

    assertInvalidStoredConfig(() -> NetworkProxy.openConnection(new URL("https://example.test")));
    assertInvalidStoredConfig(NetworkProxy::proxySelector);
    assertInvalidStoredConfig(() -> NetworkProxy.configure(HttpClient.newBuilder()));
    assertInvalidStoredConfig(() -> NetworkProxy.configure(new OkHttpClient.Builder()));
    assertInvalidStoredConfig(() -> NetworkProxy.configure(testWebSocketClient()));
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

  private static void assertInvalidStoredConfig(IoCallable callable) {
    IOException error = assertThrows(IOException.class, callable::call);

    assertEquals(NetworkProxy.InvalidNetworkProxyConfigException.class, error.getCause().getClass());
    assertTrue(error.getMessage().contains(NetworkProxy.KEY_PROXY_HOST));
    assertTrue(error.getMessage().contains("Settings"));
  }

  private static WebSocketClient testWebSocketClient() {
    return new WebSocketClient(URI.create("ws://example.test/socket")) {
      @Override
      public void onOpen(ServerHandshake handshake) {}

      @Override
      public void onMessage(String message) {}

      @Override
      public void onMessage(ByteBuffer bytes) {}

      @Override
      public void onClose(int code, String reason, boolean remote) {}

      @Override
      public void onError(Exception ex) {}
    };
  }

  private static void assertAddress(Proxy proxy, String host, int port) {
    InetSocketAddress address = (InetSocketAddress) proxy.address();
    assertEquals(host, address.getHostString());
    assertEquals(port, address.getPort());
  }

  private static Unsafe unsafe() throws ReflectiveOperationException {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (Unsafe) field.get(null);
  }

  private static boolean shouldBeInitialized(Unsafe unsafe, Class<?> target)
      throws ReflectiveOperationException {
    Method sunUnsafeMethod = findShouldBeInitialized(Unsafe.class);
    if (sunUnsafeMethod != null) {
      return (Boolean) sunUnsafeMethod.invoke(unsafe, target);
    }
    Class<?> internalUnsafeClass = Class.forName("jdk.internal.misc.Unsafe");
    Field field = internalUnsafeClass.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    Method internalUnsafeMethod = internalUnsafeClass.getMethod("shouldBeInitialized", Class.class);
    return (Boolean) internalUnsafeMethod.invoke(field.get(null), target);
  }

  private static Method findShouldBeInitialized(Class<?> unsafeClass) {
    try {
      return unsafeClass.getMethod("shouldBeInitialized", Class.class);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  public static final class StartupProbe {
    public static void main(String[] args) throws Exception {
      for (Field field : NetworkProxy.class.getDeclaredFields()) {
        if (ProxySelector.class.isAssignableFrom(field.getType())) {
          throw new AssertionError(
              "NetworkProxy eagerly declares a ProxySelector field: " + field.getName());
        }
      }

      Class<?> holder =
          Class.forName(
              NetworkProxy.class.getName() + "$DirectSelectorHolder",
              false,
              NetworkProxy.class.getClassLoader());
      Unsafe unsafe = unsafe();
      if (!shouldBeInitialized(unsafe, holder)) {
        throw new AssertionError("Direct selector holder initialized before startup hook");
      }

      Lizzie.config = ConfigTestHelper.createForTests(Path.of(args[0]));
      Lizzie.config.uiConfig = ui(NetworkProxy.MODE_SYSTEM);
      NetworkProxy.installSystemProxyPropertyFromSavedConfig();

      if (!"true".equals(System.getProperty("java.net.useSystemProxies"))) {
        throw new AssertionError("System proxy property was not installed");
      }
      if (!shouldBeInitialized(unsafe, holder)) {
        throw new AssertionError("Startup hook initialized the direct selector holder");
      }

      Lizzie.config.uiConfig.put(NetworkProxy.KEY_PROXY_MODE, NetworkProxy.MODE_DIRECT);
      NetworkProxy.proxySelector();
      if (shouldBeInitialized(unsafe, holder)) {
        throw new AssertionError("Direct selector holder was not initialized on first use");
      }
    }
  }

  private interface IoCallable {
    void call() throws IOException;
  }
}
