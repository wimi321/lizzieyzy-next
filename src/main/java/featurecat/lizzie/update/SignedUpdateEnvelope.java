package featurecat.lizzie.update;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import org.json.JSONObject;

/** Verifies the exact manifest bytes carried by an Ed25519 update envelope. */
public final class SignedUpdateEnvelope {
  public static final int SUPPORTED_ENVELOPE_VERSION = 1;
  public static final String ALGORITHM = "Ed25519";
  private static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;

  public final int envelopeVersion;
  public final String keyId;
  public final UpdateManifest manifest;

  private SignedUpdateEnvelope(int envelopeVersion, String keyId, UpdateManifest manifest) {
    this.envelopeVersion = envelopeVersion;
    this.keyId = keyId;
    this.manifest = manifest;
  }

  public static SignedUpdateEnvelope parseAndVerify(
      String rawEnvelope, Map<String, PublicKey> trustedKeys) {
    if (UpdateManifest.isBlank(rawEnvelope)) {
      throw new IllegalArgumentException("Signed update envelope is empty.");
    }
    JSONObject envelope = new JSONObject(rawEnvelope);
    int version = envelope.optInt("envelopeVersion", -1);
    if (version != SUPPORTED_ENVELOPE_VERSION) {
      throw new IllegalArgumentException("Unsupported update envelope version: " + version);
    }
    String algorithm = UpdateManifest.requiredString(envelope, "algorithm");
    if (!ALGORITHM.equals(algorithm)) {
      throw new IllegalArgumentException("Unsupported update signature algorithm: " + algorithm);
    }
    String keyId = UpdateManifest.requiredString(envelope, "keyId");
    PublicKey publicKey = trustedKeys == null ? null : trustedKeys.get(keyId);
    if (publicKey == null) {
      throw new IllegalArgumentException("Update envelope uses an untrusted signing key: " + keyId);
    }

    byte[] payload = decode(envelope, "payload");
    if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("Signed update payload has an invalid size.");
    }
    byte[] signature = decode(envelope, "signature");
    verify(publicKey, payload, signature);
    UpdateManifest manifest = UpdateManifest.parse(new String(payload, StandardCharsets.UTF_8));
    if (manifest.schemaVersion != UpdateManifest.SUPPORTED_SCHEMA_VERSION) {
      throw new IllegalArgumentException("Signed updates must use manifest schema v2.");
    }
    return new SignedUpdateEnvelope(version, keyId, manifest);
  }

  private static byte[] decode(JSONObject envelope, String field) {
    String encoded = UpdateManifest.requiredString(envelope, field);
    try {
      return Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Update envelope has invalid " + field + ".", e);
    }
  }

  private static void verify(PublicKey key, byte[] payload, byte[] signatureBytes) {
    try {
      Signature verifier = Signature.getInstance(ALGORITHM);
      verifier.initVerify(key);
      verifier.update(payload);
      if (!verifier.verify(signatureBytes)) {
        throw new IllegalArgumentException("Update manifest signature is invalid.");
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("Update manifest signature could not be verified.", e);
    }
  }
}
