package featurecat.lizzie.teacher;

import featurecat.lizzie.analysis.remote.PlatformCredentialStore;
import featurecat.lizzie.util.NetworkProxy;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Offline release gate: exercises the production loopback flow, never opens a browser. */
public final class ChatGptRuntimeSmoke {
  private ChatGptRuntimeSmoke() {}

  public static void main(String[] args) throws Exception {
    System.setProperty("java.awt.headless", "true");
    Path directory = Files.createTempDirectory("lizzie-chatgpt-runtime-");
    // Even an accidental token request must stay on loopback, away from real accounts.
    URI offline = URI.create("http://127.0.0.1:1");
    var sessions =
        new ChatGptSessions(
            directory,
            PlatformCredentialStore.create(directory.resolve("credentials")),
            new ChatGptHttp(offline, offline));
    try {
      try (var attempt = sessions.signIn(null)) {
        var parameters = ChatGptSignIn.query(attempt.authorization.getRawQuery());
        String callback = parameters.get("redirect_uri");
        if (request(callback + "?state=invalid") != 400 || attempt.result.isDone())
          throw new IOException("Invalid callback was accepted");
        if (request(callback + "?state=" + parameters.get("state") + "&error=access_denied") != 200)
          throw new IOException("Valid callback was not received");
        try {
          attempt.result.get(5, TimeUnit.SECONDS);
          throw new IOException("Denied authorization was accepted");
        } catch (ExecutionException expected) {
          if (!ChatGptHttp.error("denied").getMessage().equals(expected.getCause().getMessage()))
            throw expected;
        }
      }
      try (var retry = sessions.signIn(null)) {
        retry.close();
        if (!retry.result.isCancelled()) throw new IOException("Cancellation failed");
      }
      if (sessions.active() != null) throw new IOException("Offline probe created an account");
      System.out.println("CHATGPT_LOOPBACK_SMOKE_OK");
    } finally {
      try (var files = Files.walk(directory)) {
        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
      }
    }
  }

  private static int request(String url) throws IOException {
    var connection = (HttpURLConnection) NetworkProxy.openConnection(URI.create(url).toURL());
    connection.setConnectTimeout(5000);
    connection.setReadTimeout(5000);
    connection.setInstanceFollowRedirects(false);
    try {
      return connection.getResponseCode();
    } finally {
      connection.disconnect();
    }
  }
}
