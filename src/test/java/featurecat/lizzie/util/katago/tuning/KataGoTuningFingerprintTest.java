package featurecat.lizzie.util.katago.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KataGoTuningFingerprintTest {
  @TempDir Path temporaryDirectory;

  @BeforeEach
  void clearContentHashCache() {
    KataGoTuningFingerprint.clearHashCacheForTests();
  }

  @Test
  void everyArtifactContentAffectsTheCanonicalDigest() throws IOException {
    ArtifactSet baselineFiles = createArtifacts("baseline", "engine-v1", "model-v1", "config-v1");
    KataGoTuningFingerprint baseline = fingerprint(baselineFiles, hardware("Apple M4 Pro"));

    assertEquals(3, baseline.plannerVersion());

    KataGoTuningFingerprint changedEngine =
        fingerprint(
            createArtifacts("changed-engine", "engine-v2", "model-v1", "config-v1"),
            hardware("Apple M4 Pro"));
    KataGoTuningFingerprint changedModel =
        fingerprint(
            createArtifacts("changed-model", "engine-v1", "model-v2", "config-v1"),
            hardware("Apple M4 Pro"));
    KataGoTuningFingerprint changedConfig =
        fingerprint(
            createArtifacts("changed-config", "engine-v1", "model-v1", "config-v2"),
            hardware("Apple M4 Pro"));

    assertNotEquals(baseline.canonicalDigest(), changedEngine.canonicalDigest());
    assertNotEquals(baseline.canonicalDigest(), changedModel.canonicalDigest());
    assertNotEquals(baseline.canonicalDigest(), changedConfig.canonicalDigest());
  }

  @Test
  void changingOnlyPathsDoesNotInvalidateIdenticalContents() throws IOException {
    ArtifactSet firstFiles = createArtifacts("first", "engine", "model", "config");
    ArtifactSet movedFiles = createArtifacts("moved", "engine", "model", "config");

    KataGoTuningFingerprint first = fingerprint(firstFiles, hardware("Apple M4 Max"));
    KataGoTuningFingerprint moved = fingerprint(movedFiles, hardware("Apple M4 Max"));

    assertEquals(first, moved);
    assertEquals(first.canonicalDigest(), moved.canonicalDigest());
    assertEquals(first.engineSha256(), moved.engineSha256());
    assertEquals(first.modelSha256(), moved.modelSha256());
    assertEquals(first.configSha256(), moved.configSha256());
  }

  @Test
  void rewritingAFileAtTheSamePathInvalidatesTheHashCache() throws IOException {
    ArtifactSet files = createArtifacts("same-path", "engine", "model", "config");
    KataGoTuningFingerprint before = fingerprint(files, hardware("Apple M4 Max"));
    FileTime priorModifiedTime = Files.getLastModifiedTime(files.model());

    Files.writeString(files.model(), "MODEL");
    Files.setLastModifiedTime(
        files.model(), FileTime.fromMillis(priorModifiedTime.toMillis() + 2_000L));
    KataGoTuningFingerprint after = fingerprint(files, hardware("Apple M4 Max"));

    assertEquals(before.modelSizeBytes(), after.modelSizeBytes());
    assertNotEquals(before.modelSha256(), after.modelSha256());
    assertNotEquals(before.canonicalDigest(), after.canonicalDigest());
  }

  @Test
  void hostCapabilityChangeInvalidatesOtherwiseIdenticalArtifacts() throws IOException {
    ArtifactSet files = createArtifacts("host-change", "engine", "model", "config");

    KataGoTuningFingerprint nativeFingerprint = fingerprint(files, hardware("Apple M4"));
    AppleSiliconHardwareProbe.HardwareProfile translatedHost =
        new AppleSiliconHardwareProbe.HardwareProfile(
            "Mac16,1", "Apple M4", "arm64", 10, 34_359_738_368L, "25F90", true);
    KataGoTuningFingerprint translated = fingerprint(files, translatedHost);

    assertNotEquals(nativeFingerprint.canonicalDigest(), translated.canonicalDigest());
  }

  @Test
  void nonManagedCommandSemanticsInvalidateTheProfile() throws IOException {
    ArtifactSet files = createArtifacts("command-change", "engine", "model", "config");
    AppleSiliconHardwareProbe.HardwareProfile host = hardware("Apple M4");

    KataGoTuningFingerprint first =
        KataGoTuningFingerprint.create(
            files.engine(), files.model(), files.config(), host, "rules=chinese\n");
    KataGoTuningFingerprint second =
        KataGoTuningFingerprint.create(
            files.engine(), files.model(), files.config(), host, "rules=japanese\n");

    assertNotEquals(first.canonicalDigest(), second.canonicalDigest());
  }

  @Test
  void cancellationBeforeHashingPropagatesTheCallersExceptionUnchanged() {
    IOException cancellation = new IOException("benchmark cancelled");

    IOException thrown =
        assertThrows(
            IOException.class,
            () ->
                KataGoTuningFingerprint.create(
                    temporaryDirectory.resolve("missing-engine"),
                    temporaryDirectory.resolve("missing-model"),
                    temporaryDirectory.resolve("missing-config"),
                    hardware("Apple M4"),
                    () -> {
                      throw cancellation;
                    }));

    assertSame(cancellation, thrown);
  }

  @Test
  void nonCancellingCallbackPreservesTheLegacyFingerprint() throws IOException {
    ArtifactSet files = createArtifacts("non-cancelled", "engine", "model", "config");
    AppleSiliconHardwareProbe.HardwareProfile host = hardware("Apple M4");

    KataGoTuningFingerprint legacy =
        KataGoTuningFingerprint.create(files.engine(), files.model(), files.config(), host);
    KataGoTuningFingerprint cancellable =
        KataGoTuningFingerprint.create(
            files.engine(), files.model(), files.config(), host, () -> {});

    assertEquals(legacy, cancellable);
  }

  @Test
  void cancellationIsCheckedBetweenLargeFileHashChunks() throws IOException {
    Path directory = Files.createDirectories(temporaryDirectory.resolve("cancel-mid-hash"));
    Path engine = directory.resolve("katago");
    Files.write(engine, new byte[512 * 1024]);
    Path model = Files.writeString(directory.resolve("model.bin.gz"), "model");
    Path config = Files.writeString(directory.resolve("analysis.cfg"), "config");
    AtomicInteger checks = new AtomicInteger();
    IOException cancellation = new IOException("cancelled during engine hash");

    IOException thrown =
        assertThrows(
            IOException.class,
            () ->
                KataGoTuningFingerprint.create(
                    engine,
                    model,
                    config,
                    hardware("Apple M4"),
                    "rules=chinese",
                    () -> {
                      if (checks.incrementAndGet() == 8) {
                        throw cancellation;
                      }
                    }));

    assertSame(cancellation, thrown);
    assertTrue(checks.get() >= 8);
  }

  private KataGoTuningFingerprint fingerprint(
      ArtifactSet artifacts, AppleSiliconHardwareProbe.HardwareProfile hardwareProfile)
      throws IOException {
    return KataGoTuningFingerprint.create(
        artifacts.engine(), artifacts.model(), artifacts.config(), hardwareProfile);
  }

  private ArtifactSet createArtifacts(
      String directoryName, String engine, String model, String config) throws IOException {
    Path directory = Files.createDirectories(temporaryDirectory.resolve(directoryName));
    Path enginePath = Files.writeString(directory.resolve("katago"), engine);
    Path modelPath = Files.writeString(directory.resolve("model.bin.gz"), model);
    Path configPath = Files.writeString(directory.resolve("analysis.cfg"), config);
    return new ArtifactSet(enginePath, modelPath, configPath);
  }

  private static AppleSiliconHardwareProbe.HardwareProfile hardware(String chip) {
    return new AppleSiliconHardwareProbe.HardwareProfile(
        "Mac16,1", chip, "arm64", 10, 34_359_738_368L, "25F90", false);
  }

  private record ArtifactSet(Path engine, Path model, Path config) {}
}
