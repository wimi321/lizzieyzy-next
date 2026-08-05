package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class SignedUpdateEnvelopeTest {
  @Test
  void loadsBundledStableSigningKey() throws Exception {
    Map<String, java.security.PublicKey> keys = TrustedUpdateKeys.load();

    assertTrue(keys.containsKey("stable-2026-08"));
    assertTrue(
        java.util.Set.of("EdDSA", "Ed25519").contains(keys.get("stable-2026-08").getAlgorithm()));
  }

  @Test
  void verifiesExactSchemaV2Payload() throws Exception {
    KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String payload = validPayload().toString();
    String envelope = envelope(payload, pair, "test-key").toString();

    SignedUpdateEnvelope parsed =
        SignedUpdateEnvelope.parseAndVerify(envelope, Map.of("test-key", pair.getPublic()));

    assertEquals("next-2026-08-03.1", parsed.manifest.releaseTag);
    assertEquals(1, parsed.manifest.packages.size());
    assertEquals("macos", parsed.manifest.packages.get(0).platform);
  }

  @Test
  void rejectsTamperedPayloadAndUnknownKey() throws Exception {
    KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    JSONObject envelope = envelope(validPayload().toString(), pair, "test-key");
    envelope.put(
        "payload",
        Base64.getEncoder()
            .encodeToString(
                validPayload()
                    .put("releaseTag", "next-2099-01-01.1")
                    .toString()
                    .getBytes(StandardCharsets.UTF_8)));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            SignedUpdateEnvelope.parseAndVerify(
                envelope.toString(), Map.of("test-key", pair.getPublic())));

    JSONObject signed = envelope(validPayload().toString(), pair, "unknown-key");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SignedUpdateEnvelope.parseAndVerify(
                signed.toString(), Map.of("test-key", pair.getPublic())));
  }

  @Test
  void rejectsSignedLegacyManifestToPreventDowngrade() throws Exception {
    KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    JSONObject envelope = envelope(UpdateManifestTest.validManifest().toString(), pair, "test-key");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            SignedUpdateEnvelope.parseAndVerify(
                envelope.toString(), Map.of("test-key", pair.getPublic())));
  }

  static JSONObject validPayload() {
    JSONObject core =
        UpdateManifestTest.validManifest().getJSONArray("components").getJSONObject(0);
    core.put("downloadUrl", "https://download.goagent.top/releases/test/core.zip");
    core.put("mirrorUrls", new JSONArray().put("https://github.com/example/core.zip"));

    JSONObject packageAsset = new JSONObject();
    packageAsset.put("platform", "macos");
    packageAsset.put("arch", "arm64");
    packageAsset.put("flavor", "with-katago");
    packageAsset.put("installMode", "open-dmg");
    packageAsset.put("assetName", "2026-08-03-mac-arm64.with-katago.dmg");
    packageAsset.put("sizeBytes", 456L);
    packageAsset.put("sha256", "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
    packageAsset.put("downloadUrl", "https://download.goagent.top/releases/test/mac.dmg");
    packageAsset.put("mirrorUrls", new JSONArray().put("https://github.com/example/mac.dmg"));

    JSONObject payload = new JSONObject();
    payload.put("schemaVersion", 2);
    payload.put("releaseTag", "next-2026-08-03.1");
    payload.put("publishedAt", "2026-08-03T00:00:00Z");
    payload.put(
        "notesUrl", "https://github.com/wimi321/lizzieyzy-next/releases/tag/next-2026-08-03.1");
    payload.put("minUpdaterVersion", "2");
    payload.put("prerelease", false);
    payload.put("components", new JSONArray().put(core));
    payload.put("packages", new JSONArray().put(packageAsset));
    return payload;
  }

  private static JSONObject envelope(String payload, KeyPair pair, String keyId) throws Exception {
    byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(pair.getPrivate());
    signer.update(payloadBytes);
    JSONObject envelope = new JSONObject();
    envelope.put("envelopeVersion", 1);
    envelope.put("algorithm", "Ed25519");
    envelope.put("keyId", keyId);
    envelope.put("payload", Base64.getEncoder().encodeToString(payloadBytes));
    envelope.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
    return envelope;
  }
}
