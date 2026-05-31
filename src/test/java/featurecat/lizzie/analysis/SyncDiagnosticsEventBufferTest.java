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
}
