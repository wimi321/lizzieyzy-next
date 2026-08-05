package featurecat.lizzie.update;

import featurecat.lizzie.util.NetworkProxy;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Fetches only signed production manifests, with an explicit legacy override for test recovery. */
public final class UpdateManifestClient {
  public static final String R2_ENVELOPE_URL =
      "https://download.goagent.top/channels/stable/update-envelope.json";
  public static final String GITHUB_ENVELOPE_URL =
      "https://github.com/wimi321/lizzieyzy-next/releases/latest/download/"
          + "lizzieyzy-next-update-envelope.json";
  public static final String ENVELOPE_URLS_PROPERTY = "lizzie.update.envelopeUrls";
  public static final String LEGACY_MANIFEST_URL_PROPERTY = "lizzie.update.manifestUrl";

  private static final int CONNECT_TIMEOUT_MS = 10000;
  private static final int READ_TIMEOUT_MS = 30000;
  private static final int MAX_ENVELOPE_BYTES = 1024 * 1024;

  private final List<String> envelopeUrls;
  private final Map<String, PublicKey> trustedKeys;

  public UpdateManifestClient() {
    this(configuredEnvelopeUrls(), null);
  }

  UpdateManifestClient(List<String> envelopeUrls, Map<String, PublicKey> trustedKeys) {
    this.envelopeUrls = Collections.unmodifiableList(new ArrayList<>(envelopeUrls));
    this.trustedKeys = trustedKeys;
  }

  public FetchResult fetchLatest() throws IOException {
    String explicitLegacy = System.getProperty(LEGACY_MANIFEST_URL_PROPERTY, "").trim();
    if (!explicitLegacy.isEmpty()) {
      try {
        return new FetchResult(
            UpdateManifest.parse(fetchText(explicitLegacy)), explicitLegacy, false);
      } catch (IllegalArgumentException e) {
        throw new IOException("Invalid explicitly configured update manifest.", e);
      }
    }

    Map<String, PublicKey> keys = trustedKeys == null ? TrustedUpdateKeys.load() : trustedKeys;
    IOException failure = new IOException("No signed update source could be verified.");
    for (String url : envelopeUrls) {
      try {
        SignedUpdateEnvelope envelope = SignedUpdateEnvelope.parseAndVerify(fetchText(url), keys);
        return new FetchResult(envelope.manifest, url, true);
      } catch (IOException | IllegalArgumentException e) {
        failure.addSuppressed(new IOException(url + ": " + e.getMessage(), e));
      }
    }
    throw failure;
  }

  static List<String> configuredEnvelopeUrls() {
    String configured = System.getProperty(ENVELOPE_URLS_PROPERTY, "").trim();
    if (configured.isEmpty()) {
      return List.of(R2_ENVELOPE_URL, GITHUB_ENVELOPE_URL);
    }
    List<String> urls = new ArrayList<>();
    Arrays.stream(configured.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .forEach(urls::add);
    return urls.isEmpty() ? List.of(R2_ENVELOPE_URL, GITHUB_ENVELOPE_URL) : urls;
  }

  static String fetchText(String url) throws IOException {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) NetworkProxy.openConnection(URI.create(url).toURL());
      connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
      connection.setReadTimeout(READ_TIMEOUT_MS);
      connection.setRequestProperty("Accept", "application/json");
      connection.setRequestProperty("Accept-Encoding", "identity");
      connection.setRequestProperty("User-Agent", "LizzieYzy-Next-Updater");
      int code = connection.getResponseCode();
      if (code < 200 || code >= 300) {
        throw new IOException("HTTP " + code + " from update manifest.");
      }
      long contentLength = connection.getContentLengthLong();
      if (contentLength > MAX_ENVELOPE_BYTES) {
        throw new IOException("Update manifest is unexpectedly large.");
      }
      try (InputStream input = connection.getInputStream()) {
        byte[] body = input.readNBytes(MAX_ENVELOPE_BYTES + 1);
        if (body.length > MAX_ENVELOPE_BYTES) {
          throw new IOException("Update manifest is unexpectedly large.");
        }
        return new String(body, StandardCharsets.UTF_8);
      }
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid update manifest URL.", e);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  public static final class FetchResult {
    public final UpdateManifest manifest;
    public final String sourceUrl;
    public final boolean signatureVerified;

    FetchResult(UpdateManifest manifest, String sourceUrl, boolean signatureVerified) {
      this.manifest = manifest;
      this.sourceUrl = sourceUrl;
      this.signatureVerified = signatureVerified;
    }
  }
}
