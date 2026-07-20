package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.NetworkProxy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsUpdateServiceTest {
  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    System.clearProperty(WindowsUpdateService.MANIFEST_URL_PROPERTY);
    System.clearProperty(WindowsUpdatePaths.APP_ROOT_PROPERTY);
    System.clearProperty(WindowsUpdatePaths.APP_DIR_PROPERTY);
    System.clearProperty(WindowsUpdatePaths.WORK_DIR_PROPERTY);
    System.clearProperty(WindowsUpdatePaths.CURRENT_JAR_PROPERTY);
    Lizzie.config = null;
  }

  @Test
  void fetchLatestManifestUsesConfiguredProxy() throws Exception {
    try (OneShotHttp proxy = new OneShotHttp(UpdateManifestTest.validManifest().toString())) {
      useManualProxy(proxy.port());
      System.setProperty(
          WindowsUpdateService.MANIFEST_URL_PROPERTY, "http://example.invalid/update.json");

      UpdateManifest manifest = new WindowsUpdateService().fetchLatestManifest();

      assertEquals("next-2026-06-12.1", manifest.releaseTag);
      assertEquals(1, proxy.requests.get());
      assertTrue(proxy.lastRequestLine.contains("http://example.invalid/update.json"));
    }
  }

  @Test
  void downloadAndPrepareUsesConfiguredProxy() throws Exception {
    byte[] archive = "downloaded archive".getBytes(StandardCharsets.UTF_8);
    try (OneShotHttp proxy = new OneShotHttp(archive)) {
      useManualProxy(proxy.port());
      WindowsUpdatePlan plan = updatePlan("http://example.invalid/core.zip", archive);
      configureUpdatePaths();

      Path requestPath = new WindowsUpdateService().downloadAndPrepare(plan, null);

      Path archivePath = requestPath.getParent().resolve("2026-06-12-windows64.core-update.zip");
      assertEquals("downloaded archive", Files.readString(archivePath));
      JSONObject request = new JSONObject(Files.readString(requestPath));
      assertEquals(
          "app/lizzie-yzy2.5.3-shaded.jar",
          request.getJSONArray("components").getJSONObject(0).getString("sourcePath"));
      assertEquals(1, proxy.requests.get());
      assertTrue(proxy.lastRequestLine.contains("http://example.invalid/core.zip"));
    }
  }

  @Test
  void fetchLatestManifestReportsInvalidProxyConfigAsNetworkFailure() {
    Lizzie.config = ConfigTestHelper.createForTests(tempDir.resolve("config"));
    Lizzie.config.uiConfig =
        new JSONObject()
            .put(NetworkProxy.KEY_PROXY_MODE, NetworkProxy.MODE_MANUAL)
            .put(NetworkProxy.KEY_PROXY_HOST, " ")
            .put(NetworkProxy.KEY_PROXY_PORT, 7897);
    System.setProperty(
        WindowsUpdateService.MANIFEST_URL_PROPERTY, "http://example.invalid/update.json");

    IOException error =
        assertThrows(IOException.class, () -> new WindowsUpdateService().fetchLatestManifest());

    assertFalse(error.getMessage().contains("Invalid update manifest"));
    assertTrue(error.getMessage().contains(NetworkProxy.KEY_PROXY_HOST));
    assertTrue(error.getMessage().contains("Settings"));
  }

  @Test
  void manualUpdateCheckNeverOffersPrereleaseManifest() throws Exception {
    JSONObject prerelease = UpdateManifestTest.validManifest().put("prerelease", true);
    try (OneShotHttp server = new OneShotHttp(prerelease.toString())) {
      String previousVersion = Lizzie.nextVersion;
      try {
        Lizzie.nextVersion = "next-2026-06-01.1";
        Lizzie.config = ConfigTestHelper.createForTests(tempDir.resolve("prerelease-config"));
        Lizzie.config.uiConfig = new JSONObject();
        configureUpdatePaths();
        System.setProperty(
            WindowsUpdateService.MANIFEST_URL_PROPERTY,
            "http://127.0.0.1:" + server.port() + "/update.json");

        Optional<WindowsUpdatePlan> plan = new WindowsUpdateService().checkForUpdate();

        assertTrue(plan.isEmpty());
        assertEquals(1, server.requests.get());
      } finally {
        Lizzie.nextVersion = previousVersion;
      }
    }
  }

  private void useManualProxy(int port) {
    Lizzie.config = ConfigTestHelper.createForTests(tempDir.resolve("config"));
    Lizzie.config.uiConfig = new JSONObject();
    Lizzie.config.uiConfig.put(NetworkProxy.KEY_PROXY_MODE, NetworkProxy.MODE_MANUAL);
    Lizzie.config.uiConfig.put(NetworkProxy.KEY_PROXY_HOST, "127.0.0.1");
    Lizzie.config.uiConfig.put(NetworkProxy.KEY_PROXY_PORT, port);
  }

  private WindowsUpdatePlan updatePlan(String downloadUrl, byte[] archive) throws Exception {
    JSONObject manifest = UpdateManifestTest.validManifest();
    JSONObject component = manifest.getJSONArray("components").getJSONObject(0);
    component.put("downloadUrl", downloadUrl);
    component.put("sizeBytes", archive.length);
    component.put("sha256", sha256(archive));
    return WindowsUpdatePlan.create(
        UpdateManifest.parse(manifest),
        InstalledUpdateState.empty("next-2026-06-01.1", "windows", "opencl"),
        "next-2026-06-01.1",
        "opencl");
  }

  private void configureUpdatePaths() throws IOException {
    Path appRoot = Files.createDirectories(tempDir.resolve("app-root"));
    Path appDir = Files.createDirectories(appRoot.resolve("app"));
    Path workDir = Files.createDirectories(tempDir.resolve("work"));
    Path currentJar = appDir.resolve("lizzie-yzy2.5.3-shaded.jar");
    Files.writeString(currentJar, "old jar");
    System.setProperty(WindowsUpdatePaths.APP_ROOT_PROPERTY, appRoot.toString());
    System.setProperty(WindowsUpdatePaths.APP_DIR_PROPERTY, appDir.toString());
    System.setProperty(WindowsUpdatePaths.WORK_DIR_PROPERTY, workDir.toString());
    System.setProperty(WindowsUpdatePaths.CURRENT_JAR_PROPERTY, currentJar.toString());
  }

  private String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static final class OneShotHttp implements AutoCloseable {
    final AtomicInteger requests = new AtomicInteger();
    final ServerSocket server;
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final byte[] body;
    volatile String lastRequestLine = "";

    OneShotHttp(String body) throws IOException {
      this(body.getBytes(StandardCharsets.UTF_8));
    }

    OneShotHttp(byte[] body) throws IOException {
      this.body = body;
      this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
      executor.submit(this::serve);
    }

    int port() {
      return server.getLocalPort();
    }

    private void serve() {
      try {
        while (!server.isClosed()) {
          handle(server.accept());
        }
      } catch (IOException ignored) {
      }
    }

    private void handle(Socket socket) throws IOException {
      requests.incrementAndGet();
      try (socket) {
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        lastRequestLine = reader.readLine();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {}
        OutputStream out = socket.getOutputStream();
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Length: "
                    + body.length
                    + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(body);
      }
    }

    @Override
    public void close() throws IOException {
      server.close();
      executor.shutdownNow();
    }
  }
}
