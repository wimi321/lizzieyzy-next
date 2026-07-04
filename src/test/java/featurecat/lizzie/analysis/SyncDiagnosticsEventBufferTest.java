package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class SyncDiagnosticsEventBufferTest {
  @Test
  void keepsOnlyNewestValuesWhenCapacityIsExceeded() {
    SyncDiagnosticsEventBuffer<String> buffer = new SyncDiagnosticsEventBuffer<>(3);
    buffer.add("one");
    buffer.add("two");
    buffer.add("three");
    buffer.add("four");

    assertEquals(List.of("two", "three", "four"), buffer.snapshot());
  }

  @Test
  void snapshotCannotMutateInternalState() {
    SyncDiagnosticsEventBuffer<String> buffer = new SyncDiagnosticsEventBuffer<>(2);
    buffer.add("one");

    List<String> snapshot = buffer.snapshot();
    assertThrows(UnsupportedOperationException.class, () -> snapshot.add("two"));
    assertEquals(List.of("one"), buffer.snapshot());
  }

  @Test
  void environmentSanitizesKnownUserPathsAndDeniesUnknownAbsolutePaths() {
    assertEquals(
        "C:\\Users\\<user>", SyncDiagnosticsEnvironment.sanitizePath("C:\\Users\\alice\\Lizzie"));
    assertEquals(
        "/mnt/c/Users/<user>",
        SyncDiagnosticsEnvironment.sanitizePath("/mnt/c/Users/alice/Lizzie"));
    assertEquals(
        "\\\\wsl.localhost\\Ubuntu\\home\\<user>",
        SyncDiagnosticsEnvironment.sanitizePath("\\\\wsl.localhost\\Ubuntu\\home\\alice\\dev"));
    assertEquals("/home/<user>", SyncDiagnosticsEnvironment.sanitizePath("/home/alice/dev"));
    assertEquals("/Users/<user>", SyncDiagnosticsEnvironment.sanitizePath("/Users/alice/dev"));
    assertEquals("<redacted-path>", SyncDiagnosticsEnvironment.sanitizePath("/var/log/lizzie"));
    assertEquals("file.sgf", SyncDiagnosticsEnvironment.sanitizePath("relative/parent/file.sgf"));
  }
}
