package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.NetworkProxy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateManifestClientTest {
  @TempDir Path tempDir;

  @Test
  void rejectsTamperedPrimaryEnvelopeAndUsesSignedMirror() throws Exception {
    KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    JSONObject validEnvelope = signedEnvelope(SignedUpdateEnvelopeTest.validPayload(), pair);
    JSONObject tamperedEnvelope = new JSONObject(validEnvelope.toString());
    tamperedEnvelope.put(
        "payload",
        Base64.getEncoder()
            .encodeToString(
                SignedUpdateEnvelopeTest.validPayload()
                    .put("releaseTag", "next-2099-01-01.1")
                    .toString()
                    .getBytes(StandardCharsets.UTF_8)));

    try (TestServer server = new TestServer()) {
      server.context("/r2", exchange -> respond(exchange, tamperedEnvelope.toString()));
      server.context("/github", exchange -> respond(exchange, validEnvelope.toString()));
      UpdateManifestClient client =
          new UpdateManifestClient(
              List.of(server.url("/r2"), server.url("/github")),
              Map.of("test-key", pair.getPublic()));

      UpdateManifestClient.FetchResult result = client.fetchLatest();

      assertEquals("next-2026-08-03.1", result.manifest.releaseTag);
      assertEquals(server.url("/github"), result.sourceUrl);
      assertTrue(result.signatureVerified);
    }
  }

  @Test
  void rejectsUnexpectedlyLargeManifestResponse() throws Exception {
    String oversized = "x".repeat(1024 * 1024 + 1);
    try (TestServer server = new TestServer()) {
      server.context("/oversized", exchange -> respond(exchange, oversized));

      IOException error =
          assertThrows(
              IOException.class, () -> UpdateManifestClient.fetchText(server.url("/oversized")));

      assertTrue(error.getMessage().contains("unexpectedly large"));
    }
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(UpdateManifestClient.ENVELOPE_URLS_PROPERTY);
    System.clearProperty(UpdateManifestClient.LEGACY_MANIFEST_URL_PROPERTY);
    Lizzie.config = null;
  }

  @Test
  void fetchLatestUsesConfiguredProxy() throws Exception {
    try (OneShotHttp proxy = new OneShotHttp(UpdateManifestTest.validManifest().toString())) {
      useManualProxy(proxy.port());
      System.setProperty(
          UpdateManifestClient.LEGACY_MANIFEST_URL_PROPERTY, "http://example.invalid/update.json");

      UpdateManifest manifest = new UpdateManifestClient().fetchLatest().manifest;

      assertEquals("next-2026-06-12.1", manifest.releaseTag);
      assertEquals(1, proxy.requests.get());
      assertTrue(proxy.lastRequestLine.contains("http://example.invalid/update.json"));
    }
  }

  @Test
  void fetchLatestReportsInvalidProxyConfigAsNetworkFailure() {
    Lizzie.config = ConfigTestHelper.createForTests(tempDir.resolve("config"));
    Lizzie.config.uiConfig =
        new JSONObject()
            .put(NetworkProxy.KEY_PROXY_MODE, NetworkProxy.MODE_MANUAL)
            .put(NetworkProxy.KEY_PROXY_HOST, " ")
            .put(NetworkProxy.KEY_PROXY_PORT, 7897);
    System.setProperty(
        UpdateManifestClient.LEGACY_MANIFEST_URL_PROPERTY, "http://example.invalid/update.json");

    IOException error =
        assertThrows(IOException.class, () -> new UpdateManifestClient().fetchLatest());

    assertFalse(error.getMessage().contains("Invalid update manifest"));
    assertTrue(error.getMessage().contains(NetworkProxy.KEY_PROXY_HOST));
    assertTrue(error.getMessage().contains("Settings"));
  }

  @Test
  void fetchLatestDoesNotFilterPrereleasePayloads() throws Exception {
    JSONObject prerelease = UpdateManifestTest.validManifest().put("prerelease", true);
    try (TestServer server = new TestServer()) {
      server.context("/update.json", exchange -> respond(exchange, prerelease.toString()));
      System.setProperty(
          UpdateManifestClient.LEGACY_MANIFEST_URL_PROPERTY, server.url("/update.json"));

      UpdateManifest manifest = new UpdateManifestClient().fetchLatest().manifest;

      assertTrue(manifest.prerelease);
    }
  }

  @Test
  void officialChannelOfficialSourceUsesOnlyR2Envelope() {
    assertEquals(
        List.of(UpdateManifestClient.R2_ENVELOPE_URL),
        UpdateManifestClient.envelopeUrlsFor(
            UpdateChannel.STABLE, UpdateSource.OFFICIAL_SITE));
  }

  @Test
  void officialChannelGithubSourceUsesOnlyGithubEnvelope() {
    assertEquals(
        List.of(UpdateManifestClient.GITHUB_ENVELOPE_URL),
        UpdateManifestClient.envelopeUrlsFor(UpdateChannel.STABLE, UpdateSource.GITHUB));
  }

  @Test
  void testChannelIgnoresSourceAndOfficialEnvelopeOverride() {
    System.setProperty(
        UpdateManifestClient.ENVELOPE_URLS_PROPERTY,
        UpdateManifestClient.R2_ENVELOPE_URL + "," + UpdateManifestClient.GITHUB_ENVELOPE_URL);

    assertEquals(
        List.of(UpdateManifestClient.TEST_CHANNEL_POINTER_URL),
        UpdateManifestClient.envelopeUrlsFor(
            UpdateChannel.BETA, UpdateSource.OFFICIAL_SITE));
    assertEquals(
        List.of(UpdateManifestClient.TEST_CHANNEL_POINTER_URL),
        UpdateManifestClient.envelopeUrlsFor(UpdateChannel.BETA, UpdateSource.GITHUB));
    assertEquals(
        "https://github.com/wimi321/lizzieyzy-next/releases/download/channel-beta/"
            + "lizzieyzy-next-update-envelope.json",
        UpdateManifestClient.TEST_CHANNEL_POINTER_URL);
  }

  @Test
  void officialEnvelopeOverrideTakesPrecedenceOverSelectedSource() {
    System.setProperty(
        UpdateManifestClient.ENVELOPE_URLS_PROPERTY,
        "http://example.test/primary.json,http://example.test/secondary.json");

    assertEquals(
        List.of("http://example.test/primary.json", "http://example.test/secondary.json"),
        UpdateManifestClient.envelopeUrlsFor(UpdateChannel.STABLE, UpdateSource.GITHUB));

    System.clearProperty(UpdateManifestClient.ENVELOPE_URLS_PROPERTY);

    assertEquals(
        List.of(UpdateManifestClient.GITHUB_ENVELOPE_URL),
        UpdateManifestClient.envelopeUrlsFor(UpdateChannel.STABLE, UpdateSource.GITHUB));
  }


  private static JSONObject signedEnvelope(JSONObject payload, KeyPair pair) throws Exception {
    byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(pair.getPrivate());
    signer.update(bytes);
    JSONObject envelope = new JSONObject();
    envelope.put("envelopeVersion", SignedUpdateEnvelope.SUPPORTED_ENVELOPE_VERSION);
    envelope.put("algorithm", SignedUpdateEnvelope.ALGORITHM);
    envelope.put("keyId", "test-key");
    envelope.put("payload", Base64.getEncoder().encodeToString(bytes));
    envelope.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
    return envelope;
  }

  private static void respond(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static final class TestServer implements AutoCloseable {
    private final HttpServer server;

    TestServer() throws IOException {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.start();
    }

    void context(String path, com.sun.net.httpserver.HttpHandler handler) {
      server.createContext(path, handler);
    }

    String url(String path) {
      return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private void useManualProxy(int port) {
    Lizzie.config = ConfigTestHelper.createForTests(tempDir.resolve("config"));
    Lizzie.config.uiConfig = new JSONObject();
    Lizzie.config.uiConfig.put(NetworkProxy.KEY_PROXY_MODE, NetworkProxy.MODE_MANUAL);
    Lizzie.config.uiConfig.put(NetworkProxy.KEY_PROXY_HOST, "127.0.0.1");
    Lizzie.config.uiConfig.put(NetworkProxy.KEY_PROXY_PORT, port);
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
