package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResumableDownloaderTest {
  @TempDir Path tempDir;

  @Test
  void resumesPartFileWithRangeAndVerifiesResult() throws Exception {
    byte[] body = "hello resumable world".getBytes(StandardCharsets.UTF_8);
    AtomicReference<String> range = new AtomicReference<>();
    try (TestServer server = new TestServer()) {
      server.context(
          "/asset",
          exchange -> {
            String requestedRange = exchange.getRequestHeaders().getFirst("Range");
            range.set(requestedRange);
            int offset =
                Integer.parseInt(
                    requestedRange.substring("bytes=".length(), requestedRange.length() - 1));
            byte[] remaining = java.util.Arrays.copyOfRange(body, offset, body.length);
            exchange
                .getResponseHeaders()
                .add(
                    "Content-Range",
                    "bytes " + offset + "-" + (body.length - 1) + "/" + body.length);
            respond(exchange, 206, remaining);
          });
      Path output = tempDir.resolve("asset.zip");
      Files.write(output.resolveSibling("asset.zip.part"), java.util.Arrays.copyOf(body, 6));

      new ResumableDownloader()
          .download(
              spec("asset.zip", body, List.of(server.url("/asset"))),
              output,
              new ResumableDownloader.Control(),
              null);

      assertEquals("bytes=6-", range.get());
      assertArrayEquals(body, Files.readAllBytes(output));
      assertFalse(Files.exists(output.resolveSibling("asset.zip.part")));
    }
  }

  @Test
  void safelyRestartsWhenServerIgnoresRange() throws Exception {
    byte[] body = "complete response".getBytes(StandardCharsets.UTF_8);
    try (TestServer server = new TestServer()) {
      server.context("/asset", exchange -> respond(exchange, 200, body));
      Path output = tempDir.resolve("asset.zip");
      Files.writeString(output.resolveSibling("asset.zip.part"), "wrong prefix");

      new ResumableDownloader()
          .download(
              spec("asset.zip", body, List.of(server.url("/asset"))),
              output,
              new ResumableDownloader.Control(),
              null);

      assertArrayEquals(body, Files.readAllBytes(output));
    }
  }

  @Test
  void fallsBackFromR2ToMirrorWithoutLosingProgressContract() throws Exception {
    byte[] body = "mirror result".getBytes(StandardCharsets.UTF_8);
    AtomicInteger primaryRequests = new AtomicInteger();
    AtomicInteger mirrorRequests = new AtomicInteger();
    try (TestServer server = new TestServer()) {
      server.context(
          "/primary",
          exchange -> {
            primaryRequests.incrementAndGet();
            respond(exchange, 503, new byte[0]);
          });
      server.context(
          "/mirror",
          exchange -> {
            mirrorRequests.incrementAndGet();
            respond(exchange, 200, body);
          });
      Path output = tempDir.resolve("asset.zip");

      new ResumableDownloader()
          .download(
              spec("asset.zip", body, List.of(server.url("/primary"), server.url("/mirror"))),
              output,
              new ResumableDownloader.Control(),
              null);

      assertEquals(1, primaryRequests.get());
      assertEquals(1, mirrorRequests.get());
      assertArrayEquals(body, Files.readAllBytes(output));
    }
  }

  @Test
  void continuesPartialPrimaryDownloadFromMirrorRange() throws Exception {
    byte[] body = "primary prefix then github remainder".getBytes(StandardCharsets.UTF_8);
    int split = 12;
    AtomicReference<String> mirrorRange = new AtomicReference<>();
    try (TestServer server = new TestServer()) {
      server.context(
          "/primary", exchange -> respond(exchange, 200, java.util.Arrays.copyOf(body, split)));
      server.context(
          "/mirror",
          exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            mirrorRange.set(range);
            byte[] remaining = java.util.Arrays.copyOfRange(body, split, body.length);
            exchange
                .getResponseHeaders()
                .add(
                    "Content-Range",
                    "bytes " + split + "-" + (body.length - 1) + "/" + body.length);
            respond(exchange, 206, remaining);
          });
      Path output = tempDir.resolve("asset.zip");

      new ResumableDownloader()
          .download(
              spec("asset.zip", body, List.of(server.url("/primary"), server.url("/mirror"))),
              output,
              new ResumableDownloader.Control(),
              null);

      assertEquals("bytes=" + split + "-", mirrorRange.get());
      assertArrayEquals(body, Files.readAllBytes(output));
    }
  }

  @Test
  void pausesAndResumesWithoutRestartingTheDownload() throws Exception {
    byte[] body = new byte[ResumableDownloaderTestData.TWO_AND_A_HALF_MIB];
    new java.util.Random(42L).nextBytes(body);
    AtomicBoolean pauseObserved = new AtomicBoolean(false);
    AtomicBoolean pausedStateObserved = new AtomicBoolean(false);
    ResumableDownloader.Control control = new ResumableDownloader.Control();
    try (TestServer server = new TestServer()) {
      server.context("/asset", exchange -> respond(exchange, 200, body));
      Path output = tempDir.resolve("large-asset.zip");

      new ResumableDownloader()
          .download(
              spec("large-asset.zip", body, List.of(server.url("/asset"))),
              output,
              control,
              progress -> {
                if (progress.state == ResumableDownloader.State.PAUSED) {
                  pausedStateObserved.set(true);
                }
                if (progress.state == ResumableDownloader.State.DOWNLOADING
                    && progress.completedBytes > 0
                    && !control.isPaused()
                    && pauseObserved.compareAndSet(false, true)) {
                  control.pause();
                  Thread resume =
                      new Thread(
                          () -> {
                            try {
                              Thread.sleep(50L);
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                            control.resume();
                          });
                  resume.setDaemon(true);
                  resume.start();
                }
              });

      assertTrue(pauseObserved.get());
      assertTrue(pausedStateObserved.get());
      assertArrayEquals(body, Files.readAllBytes(output));
    }
  }

  @Test
  void rejectsCorruptDataAndDoesNotPublishOutput() throws Exception {
    byte[] expected = "expected".getBytes(StandardCharsets.UTF_8);
    byte[] corrupt = "corrupt!".getBytes(StandardCharsets.UTF_8);
    try (TestServer server = new TestServer()) {
      server.context("/asset", exchange -> respond(exchange, 200, corrupt));
      Path output = tempDir.resolve("asset.zip");

      assertThrows(
          IOException.class,
          () ->
              new ResumableDownloader()
                  .download(
                      spec("asset.zip", expected, List.of(server.url("/asset"))),
                      output,
                      new ResumableDownloader.Control(),
                      null));

      assertFalse(Files.exists(output));
      assertFalse(Files.exists(output.resolveSibling("asset.zip.part")));
    }
  }

  @Test
  void cancellationKeepsExistingPartForLaterResume() throws Exception {
    byte[] body = "future download".getBytes(StandardCharsets.UTF_8);
    Path output = tempDir.resolve("asset.zip");
    Path part = output.resolveSibling("asset.zip.part");
    Files.writeString(part, "future");
    ResumableDownloader.Control control = new ResumableDownloader.Control();
    control.cancel();

    assertThrows(
        ResumableDownloader.DownloadCancelledException.class,
        () ->
            new ResumableDownloader()
                .download(
                    spec("asset.zip", body, List.of("http://127.0.0.1:1/asset")),
                    output,
                    control,
                    null));

    assertTrue(Files.exists(part));
  }

  private ResumableDownloader.DownloadSpec spec(String name, byte[] body, List<String> urls)
      throws Exception {
    return new ResumableDownloader.DownloadSpec(
        name,
        body.length,
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)),
        urls);
  }

  private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
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

  private static final class ResumableDownloaderTestData {
    static final int TWO_AND_A_HALF_MIB = 2 * 1024 * 1024 + 512 * 1024;

    private ResumableDownloaderTestData() {}
  }
}
