package featurecat.lizzie.util.katago.tuning;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KataGoTuningProfileStoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void profileJsonRoundTripsWithoutLosingMetrics() {
    KataGoTuningProfile profile = profile("fingerprint-a");

    Optional<KataGoTuningProfile> decoded = KataGoTuningProfile.fromJson(profile.toJson());

    assertEquals(Optional.of(profile), decoded);
    assertEquals(KataGoTuningProfile.Mode.EXPERIMENTAL_HARDWARE, decoded.orElseThrow().mode());
    assertTrue(decoded.orElseThrow().managesHardwareSettings());
    assertEquals(List.of(0, 0, 100), decoded.orElseThrow().devices());
    assertEquals(7, decoded.orElseThrow().searchThreads());
    assertEquals(1_729_999_123_456L, decoded.orElseThrow().updatedAtMillis());
    assertEquals(KataGoTuningProfile.SCHEMA_VERSION, profile.toJson().getInt("schemaVersion"));
    assertEquals("EXPERIMENTAL_HARDWARE", profile.toJson().getString("mode"));
  }

  @Test
  void officialThreadProfileRoundTripsWithoutHardwareOverrides() throws IOException {
    KataGoTuningFingerprint fingerprint = fingerprint("Apple M4 Pro");
    KataGoTuningProfile profile =
        KataGoTuningProfile.officialThreads(
            fingerprint,
            3,
            new KataGoTuningProfile.Metrics(100, 100, 88.5, 101.0, 42.0, 2.4),
            "Metal",
            1_729_999_123_456L);

    JSONObject json = profile.toJson();
    Optional<KataGoTuningProfile> decoded = KataGoTuningProfile.fromJson(json);

    assertEquals(Optional.of(profile), decoded);
    assertEquals(KataGoTuningProfile.Mode.OFFICIAL_THREADS, profile.mode());
    assertFalse(profile.managesHardwareSettings());
    assertTrue(profile.devices().isEmpty());
    assertEquals(0, profile.batch());
    assertFalse(json.has("devices"));
    assertFalse(json.has("batch"));
    assertEquals("OFFICIAL_THREADS", json.getString("mode"));

    JSONObject backingJson = new JSONObject();
    KataGoTuningStore store = new KataGoTuningStore(backingJson);
    store.save(profile);
    assertEquals(Optional.of(profile), store.loadMatching(fingerprint));
  }

  @Test
  void legacyJsonWithoutModeMigratesAsExperimentalHardwareProfile() {
    KataGoTuningProfile expected = profile("fingerprint-a");
    JSONObject legacy = expected.toJson();
    legacy.remove("schemaVersion");
    legacy.remove("mode");

    Optional<KataGoTuningProfile> decoded = KataGoTuningProfile.fromJson(legacy);

    assertEquals(Optional.of(expected), decoded);
    assertEquals(KataGoTuningProfile.Mode.EXPERIMENTAL_HARDWARE, decoded.orElseThrow().mode());
    assertTrue(decoded.orElseThrow().managesHardwareSettings());
  }

  @Test
  void storeLoadsOnlyProfilesWithMatchingFingerprintAndCanClear() throws IOException {
    JSONObject backingJson = new JSONObject();
    KataGoTuningStore store = new KataGoTuningStore(backingJson);
    KataGoTuningFingerprint expected = fingerprint("Apple M4 Pro");
    KataGoTuningFingerprint otherHost = fingerprint("Apple M4 Max");
    KataGoTuningProfile saved =
        new KataGoTuningProfile(
            expected,
            List.of(0, 100),
            4,
            6,
            new KataGoTuningProfile.Metrics(100, 100, 412.5, 930.0, 151.2, 6.15),
            "Metal",
            1_729_999_123_456L);

    store.save(saved);

    assertTrue(store.hasStoredProfile());
    assertEquals(Optional.of(saved), store.loadMatching(expected));
    assertTrue(store.loadMatching(otherHost).isEmpty());
    assertTrue(backingJson.has(KataGoTuningStore.KEY));

    store.clear();
    assertFalse(backingJson.has(KataGoTuningStore.KEY));
    assertFalse(store.hasStoredProfile());
    assertTrue(store.loadMatching(expected).isEmpty());
  }

  @Test
  void fastPresenceCheckRejectsMissingWrongTypeAndMalformedProfiles() {
    JSONObject backingJson = new JSONObject();
    KataGoTuningStore store = new KataGoTuningStore(backingJson);

    assertFalse(store.hasStoredProfile());
    backingJson.put(KataGoTuningStore.KEY, "not-a-json-object");
    assertFalse(store.hasStoredProfile());
    backingJson.put(KataGoTuningStore.KEY, new JSONObject().put("devices", List.of(0)));
    assertFalse(store.hasStoredProfile());

    JSONObject invalidDevice = profile("fingerprint-a").toJson().put("devices", List.of(50));
    backingJson.put(KataGoTuningStore.KEY, invalidDevice);
    assertFalse(store.hasStoredProfile());

    JSONObject unsafeMixedBatch =
        profile("fingerprint-a").toJson().put("devices", List.of(0, 100)).put("batch", 1);
    backingJson.put(KataGoTuningStore.KEY, unsafeMixedBatch);
    assertFalse(store.hasStoredProfile());

    JSONObject unsupportedSchema = profile("fingerprint-a").toJson().put("schemaVersion", 999);
    backingJson.put(KataGoTuningStore.KEY, unsupportedSchema);
    assertFalse(store.hasStoredProfile());

    JSONObject unknownMode = profile("fingerprint-a").toJson().put("mode", "FUTURE_MODE");
    backingJson.put(KataGoTuningStore.KEY, unknownMode);
    assertFalse(store.hasStoredProfile());

    JSONObject officialWithHardwareFields =
        KataGoTuningProfile.officialThreads(
                "fingerprint-a",
                2,
                new KataGoTuningProfile.Metrics(1, 1, 80.0, 80.0, 40.0, 2.0),
                "Metal",
                123L)
            .toJson()
            .put("devices", List.of(0))
            .put("batch", 1);
    backingJson.put(KataGoTuningStore.KEY, officialWithHardwareFields);
    assertFalse(store.hasStoredProfile());

    store.save(profile("fingerprint-a"));
    assertTrue(store.hasStoredProfile());
  }

  @Test
  void corruptStoredJsonIsAlwaysAQuietCacheMiss() throws IOException {
    JSONObject backingJson = new JSONObject();
    KataGoTuningStore store = new KataGoTuningStore(backingJson);
    KataGoTuningFingerprint fingerprint = fingerprint("Apple M4 Pro");

    backingJson.put(
        KataGoTuningStore.KEY,
        new JSONObject()
            .put("fingerprint", fingerprint.canonicalDigest())
            .put("devices", "not-an-array"));
    assertDoesNotThrow(() -> store.loadMatching(fingerprint));
    assertTrue(store.loadMatching(fingerprint).isEmpty());

    backingJson.put(KataGoTuningStore.KEY, "not-a-json-object");
    assertDoesNotThrow(() -> store.loadMatching(fingerprint));
    assertTrue(store.loadMatching(fingerprint).isEmpty());
  }

  private KataGoTuningFingerprint fingerprint(String chip) throws IOException {
    Path engine = writeOnce("katago", "engine");
    Path model = writeOnce("model.bin.gz", "model");
    Path config = writeOnce("analysis.cfg", "config");
    AppleSiliconHardwareProbe.HardwareProfile hardware =
        new AppleSiliconHardwareProbe.HardwareProfile(
            "Mac16,1", chip, "arm64", 14, 51_539_607_552L, "25F90", false);
    return KataGoTuningFingerprint.create(engine, model, config, hardware);
  }

  private Path writeOnce(String name, String content) throws IOException {
    Path path = temporaryDirectory.resolve(name);
    if (Files.notExists(path)) {
      Files.writeString(path, content);
    }
    return path;
  }

  private static KataGoTuningProfile profile(String fingerprint) {
    return new KataGoTuningProfile(
        fingerprint,
        List.of(0, 0, 100),
        3,
        7,
        new KataGoTuningProfile.Metrics(50, 50, 487.25, 1_248.5, 201.1, 6.208),
        "Metal",
        1_729_999_123_456L);
  }
}
