package featurecat.lizzie.update;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Loads the small, application-bundled allowlist of update signing keys. */
public final class TrustedUpdateKeys {
  static final String RESOURCE = "/update/trusted-update-keys.properties";

  private TrustedUpdateKeys() {}

  public static Map<String, PublicKey> load() throws IOException {
    Properties properties = new Properties();
    try (InputStream input = TrustedUpdateKeys.class.getResourceAsStream(RESOURCE)) {
      if (input == null) {
        throw new IOException("Trusted update key resource is missing.");
      }
      properties.load(input);
    }
    Map<String, PublicKey> keys = new LinkedHashMap<>();
    for (String keyId : properties.stringPropertyNames()) {
      String encoded = properties.getProperty(keyId, "").trim();
      if (keyId.trim().isEmpty() || encoded.isEmpty()) {
        continue;
      }
      try {
        byte[] der = Base64.getDecoder().decode(encoded);
        PublicKey key =
            KeyFactory.getInstance(SignedUpdateEnvelope.ALGORITHM)
                .generatePublic(new X509EncodedKeySpec(der));
        keys.put(keyId.trim(), key);
      } catch (IllegalArgumentException | GeneralSecurityException e) {
        throw new IOException("Trusted update key " + keyId + " is invalid.", e);
      }
    }
    if (keys.isEmpty()) {
      throw new IOException("No trusted update signing keys are configured.");
    }
    return Collections.unmodifiableMap(keys);
  }
}
