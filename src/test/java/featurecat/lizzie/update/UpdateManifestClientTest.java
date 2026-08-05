package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class UpdateManifestClientTest {
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
}
