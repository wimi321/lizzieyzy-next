package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OgsSgfClientTest {
  @Test
  void resolvesGameAndDownloadLinksToHttpsWithoutQueryOrFragment() {
    for (String input :
        new String[] {
          " https://online-go.com/game/123 ",
          "http://www.online-go.com/game/123/?move=5#20",
          "https://online-go.com/api/v1/games/123/sgf",
          "https://online-go.com/api/v1/games/123/sgf/123.sgf"
        }) {
      assertEquals(
          URI.create("https://online-go.com/api/v1/games/123/sgf"),
          OgsSgfClient.sgfUri(input).orElseThrow(),
          input);
    }
  }

  @Test
  void rejectsUnrelatedMalformedAndMisleadingLinks() {
    for (String input :
        new String[] {
          "https://online-go.com.evil.test/game/123",
          "https://evil.test/online-go.com/game/123",
          "https://user@online-go.com/game/123",
          "https://online-go.com:8443/game/123",
          "ftp://online-go.com/game/123",
          "https://online-go.com/game/0",
          "https://online-go.com/game/-1",
          "https://online-go.com/game/abc",
          "https://online-go.com/player/123",
          "https://online-go.com/review/123",
          "not a URL",
          ""
        }) assertTrue(OgsSgfClient.sgfUri(input).isEmpty(), input);
    assertTrue(OgsSgfClient.sgfUri(null).isEmpty());
  }

  @Test
  void downloadsUtf8AndClosesResources() throws Exception {
    String sgf = "(;FF[4]GM[1]SZ[19]KM[6.5]PB[黒]PW[白];B[pd])";
    Connection connection = new Connection(200, "\uFEFF" + sgf + "\n");
    assertEquals(sgf, OgsSgfClient.download(connection));
    assertTrue(connection.closed);
    assertTrue(connection.disconnected);
    assertEquals(10000, connection.getConnectTimeout());
    assertEquals(10000, connection.getReadTimeout());
    assertFalse(connection.getInstanceFollowRedirects());
  }

  @Test
  void rejectsHttpErrorsRedirectsAndNonSgfWithoutReturningContent() {
    for (int code : new int[] {301, 403, 404, 429, 500}) {
      Connection connection = new Connection(code, "(;SZ[19])");
      assertThrows(IOException.class, () -> OgsSgfClient.download(connection));
      assertTrue(connection.disconnected);
    }
    for (String body : new String[] {"", "<html>Sign in</html>", "{\"error\":true}", "(;SZ[19]"}) {
      Connection connection = new Connection(200, body);
      assertThrows(IOException.class, () -> OgsSgfClient.download(connection));
      assertTrue(connection.closed);
      assertTrue(connection.disconnected);
    }
  }

  @Test
  void rejectsOversizedResponse() {
    Connection connection = new Connection(200, "(;C[" + "x".repeat(8 * 1024 * 1024) + "])");
    assertThrows(IOException.class, () -> OgsSgfClient.download(connection));
    assertTrue(connection.closed);
    assertTrue(connection.disconnected);
  }

  private static class Connection extends HttpURLConnection {
    private final int status;
    private final byte[] body;
    boolean closed;
    boolean disconnected;

    Connection(int status, String body) {
      super(null);
      this.status = status;
      this.body = body.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public int getResponseCode() {
      return status;
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(body) {
        @Override
        public void close() {
          closed = true;
        }
      };
    }

    @Override
    public void disconnect() {
      disconnected = true;
    }

    @Override
    public boolean usingProxy() {
      return false;
    }

    @Override
    public void connect() {}
  }
}
