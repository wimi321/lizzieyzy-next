package featurecat.lizzie.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves public OGS game links and retrieves their SGF through the configured proxy. */
public final class OgsSgfClient {
  private static final Pattern GAME =
      Pattern.compile(
          "/(?:game/([1-9][0-9]*)|api/v1/games/([1-9][0-9]*)/sgf(?:/[1-9][0-9]*\\.sgf)?)/?");
  private static final int MAX_BYTES = 8 * 1024 * 1024;

  private OgsSgfClient() {}

  public static Optional<URI> sgfUri(String input) {
    try {
      URI uri = URI.create(input.trim());
      if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          || !("online-go.com".equalsIgnoreCase(uri.getHost())
              || "www.online-go.com".equalsIgnoreCase(uri.getHost()))
          || uri.getUserInfo() != null
          || uri.getPort() != -1) {
        return Optional.empty();
      }
      Matcher game = GAME.matcher(uri.getPath());
      if (!game.matches()) return Optional.empty();
      String id = game.group(1) != null ? game.group(1) : game.group(2);
      return Optional.of(URI.create("https://online-go.com/api/v1/games/" + id + "/sgf"));
    } catch (IllegalArgumentException | NullPointerException e) {
      return Optional.empty();
    }
  }

  public static String download(URI uri) throws IOException {
    return download((HttpURLConnection) NetworkProxy.openConnection(uri.toURL()));
  }

  static String download(HttpURLConnection connection) throws IOException {
    String sgf = readResponse(connection).strip();
    if (sgf.startsWith("\uFEFF")) sgf = sgf.substring(1).stripLeading();
    // Do not send HTML/login/error responses to the board parser.
    if (!sgf.matches("(?s)\\(\\s*;.*") || !sgf.endsWith(")")) {
      throw new IOException("Invalid SGF response");
    }
    return sgf;
  }

  static String readResponse(URI uri) throws IOException {
    return readResponse((HttpURLConnection) NetworkProxy.openConnection(uri.toURL()));
  }

  private static String readResponse(HttpURLConnection connection) throws IOException {
    try {
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(10000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("User-Agent", "LizzieYzy-Next");
      connection.setRequestProperty(
          "Accept", "application/json,application/x-go-sgf,text/plain,*/*");
      int status = connection.getResponseCode();
      if (status != 200) throw new IOException("HTTP " + status);
      try (InputStream input = connection.getInputStream()) {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IOException("SGF exceeds size limit");
        return new String(bytes, StandardCharsets.UTF_8);
      }
    } finally {
      connection.disconnect();
    }
  }
}
