package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeSnapshotTest {
  @TempDir Path tempDir;

  @Test
  void captureIncludesRequiredFieldsWithNonNegativeMemory() throws Exception {
    Files.createDirectories(tempDir);
    JSONObject json = new JSONObject(RuntimeSnapshot.capture(tempDir, new ExportSanitizer()));

    for (String field : RuntimeSnapshot.REQUIRED_FIELDS) {
      assertTrue(json.has(field), field + " missing from " + json);
    }
    assertNonNegativeIfPresent(json, "processors");
    assertNonNegativeIfPresent(json, "heapUsedMiB");
    assertNonNegativeIfPresent(json, "heapCommittedMiB");
    assertNonNegativeIfPresent(json, "heapMaxMiB");
    assertNonNegativeIfPresent(json, "nonHeapUsedMiB");
    assertNonNegativeIfPresent(json, "uptimeSeconds");
    if (json.isNull("workDirUsableGiB")) {
      assertEquals(
          RuntimeSnapshot.MISSING_UNREADABLE,
          json.getJSONObject("missing").getString("workDirUsableGiB"));
    } else {
      assertTrue(json.getLong("workDirUsableGiB") >= 0L, json.toString());
    }
    assertFalse(json.toString().contains(tempDir.toAbsolutePath().toString()), json.toString());
  }

  @Test
  void diskInfoFailureIsExplicitMissingAndKeepsMemoryFields() {
    JSONObject json =
        new JSONObject(
            RuntimeSnapshot.capture(
                tempDir,
                new ExportSanitizer(),
                ignored -> {
                  throw new IOException("store-unreadable");
                }));

    assertTrue(json.has("workDirUsableGiB"), json.toString());
    assertTrue(json.isNull("workDirUsableGiB"), json.toString());
    assertEquals(
        RuntimeSnapshot.MISSING_UNREADABLE,
        json.getJSONObject("missing").getString("workDirUsableGiB"));
    assertTrue(json.getLong("heapUsedMiB") >= 0L, json.toString());
    assertTrue(json.getLong("heapCommittedMiB") >= 0L, json.toString());
    assertTrue(json.getLong("nonHeapUsedMiB") >= 0L, json.toString());
    assertTrue(json.getLong("processors") >= 1L, json.toString());
    assertFalse(json.toString().contains(tempDir.toAbsolutePath().toString()), json.toString());
  }

  @Test
  void missingWorkDirectoryDoesNotWriteThePath() {
    Path missing = Path.of("/this/runtime-snapshot-path-must-not-exist/" + UUID.randomUUID());
    JSONObject json = new JSONObject(RuntimeSnapshot.capture(missing, new ExportSanitizer()));

    assertTrue(json.has("workDirUsableGiB"), json.toString());
    assertTrue(json.isNull("workDirUsableGiB"), json.toString());
    assertTrue(json.getJSONObject("missing").has("workDirUsableGiB"), json.toString());
    assertFalse(json.toString().contains(missing.toString()), json.toString());
    assertTrue(json.getLong("heapUsedMiB") >= 0L, json.toString());
  }

  @Test
  void undefinedByteQuantityIsExplicitMissingNotNegative() {
    JSONObject json = new JSONObject();
    JSONObject missing = new JSONObject();
    RuntimeSnapshot.putByteQuantity(json, missing, "heapMaxMiB", -1L, 1024L * 1024L);

    assertTrue(json.has("heapMaxMiB"));
    assertTrue(json.isNull("heapMaxMiB"));
    assertEquals(RuntimeSnapshot.MISSING_UNDEFINED, missing.getString("heapMaxMiB"));
  }

  @Test
  void wholeCaptureFailureStillYieldsParseableMissingDocument() {
    JSONObject json = new JSONObject(RuntimeSnapshot.unavailableJson());
    for (String field : RuntimeSnapshot.REQUIRED_FIELDS) {
      assertTrue(json.has(field), field);
      assertTrue(json.isNull(field), field);
      assertEquals(
          RuntimeSnapshot.MISSING_UNREADABLE, json.getJSONObject("missing").getString(field));
    }
  }

  private static void assertNonNegativeIfPresent(JSONObject json, String field) {
    assertTrue(json.has(field), field);
    if (!json.isNull(field)) {
      assertTrue(json.getLong(field) >= 0L, field + "=" + json.get(field));
    }
  }
}
